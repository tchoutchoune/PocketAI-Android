#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>
#include "chat.h"
#include "common.h"
#include "sampling.h"
#include "log.h"
#include "llama.h"
#include "ggml-backend.h"
#include "inference_helpers.h"
#include "runtime_policy.h"
#include "native_batch.h"
#include <memory>

namespace {
constexpr int HEADROOM = 8;
struct Options {
    int threads = 4;
    int context = 2048;
    int batch = 256;
    int gpu_layers = 0;
    bool prefer_cpu_output = false;
    float temperature = 0.6f;
} options;
llama_model *model = nullptr;
llama_context *context = nullptr;
std::unique_ptr<NativeBatch> batch;
llama_tokens resident_tokens;
llama_tokens sampled_tokens;
bool output_on_cpu = false;
int runtime_recoveries = 0;
pocketai::DecodeDiagnostics decode_diagnostics;
common_chat_templates_ptr templates;
common_sampler *sampler = nullptr;
std::vector<common_chat_msg> messages;
struct TurnSpan {
    llama_pos start = 0;
    llama_pos end = 0;
};
std::vector<TurnSpan> turns;
llama_pos current_turn_start = -1;
std::string system_prompt;
std::string model_path;
std::string cached_bytes;
std::string assistant_text;
std::string fallback;
std::string gpu_description;
ggml_backend_dev_t gpu = nullptr;
int gpu_layers = 0;
int active_threads = 4;
llama_pos position = 0;
llama_pos system_position = 0;
std::atomic<bool> cancelled{false};
std::atomic<int> thread_limit{32};
std::atomic<int> backend_warnings{0};
std::atomic<int> backend_errors{0};
pocketai::GenerationBudget budget;
bool generating = false;
bool generation_eog = false;
bool needs_end_of_turn = false;
bool context_dirty = false;
int shifts = 0;
int turn_evictions = 0;
int gpu_load_attempts = 0;
int context_backoffs = 0;
int gpu_probe_failures = 0;
int tuned_thread_choice = 0;
int64_t generation_start = 0;
int64_t generation_end = 0;
int64_t prompt_us = 0;
int prompt_tokens = 0;

void log_event(int priority, const char *message) {
    __android_log_write(priority, "PocketAI.Native", message);
}

// Third-party logs can contain model paths, metadata, or text. Keep only their counts.
void private_backend_log(ggml_log_level level, const char *, void *) {
    if (level == GGML_LOG_LEVEL_WARN) ++backend_warnings;
    if (level == GGML_LOG_LEVEL_ERROR) ++backend_errors;
}

bool abort_decode(void *) { return cancelled.load(std::memory_order_relaxed); }
bool load_progress(float, void *) { return !cancelled.load(std::memory_order_relaxed); }

void note_fallback(const std::string &message) {
    if (!fallback.empty()) fallback += "; ";
    fallback += message;
}

void throw_io(JNIEnv *env, const char *message) {
    const auto type = env->FindClass("java/io/IOException");
    if (type) env->ThrowNew(type, message);
}

std::string java_text(JNIEnv *env, jstring value) {
    if (!value) return {};
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (!chars) return {};
    const int size = env->GetStringLength(value);
    std::string result;
    for (int i = 0; i < size; ++i) {
        uint32_t code = chars[i];
        if (code >= 0xD800 && code <= 0xDBFF && i + 1 < size && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF)
            code = 0x10000 + ((code - 0xD800) << 10) + (chars[++i] - 0xDC00);
        else if (code >= 0xD800 && code <= 0xDFFF) code = 0xFFFD;
        if (code < 0x80) result.push_back(static_cast<char>(code));
        else if (code < 0x800) { result.push_back(0xC0 | (code >> 6)); result.push_back(0x80 | (code & 0x3F)); }
        else if (code < 0x10000) {
            result.push_back(0xE0 | (code >> 12)); result.push_back(0x80 | ((code >> 6) & 0x3F)); result.push_back(0x80 | (code & 0x3F));
        } else {
            result.push_back(0xF0 | (code >> 18)); result.push_back(0x80 | ((code >> 12) & 0x3F));
            result.push_back(0x80 | ((code >> 6) & 0x3F)); result.push_back(0x80 | (code & 0x3F));
        }
    }
    env->ReleaseStringChars(value, chars);
    return result;
}

jstring android_text(JNIEnv *env, const std::string &text) {
    const auto utf16 = pocketai::utf8_to_utf16(text);
    const jchar empty = 0;
    return env->NewString(utf16.empty() ? &empty : reinterpret_cast<const jchar *>(utf16.data()), utf16.size());
}

void apply_threads(llama_context *target = nullptr) {
    active_threads = std::max(1, std::min(options.threads, thread_limit.load(std::memory_order_relaxed)));
    if (target || context) llama_set_n_threads(target ? target : context, active_threads, active_threads);
}

void clear_conversation() {
    if (context) llama_memory_clear(llama_get_memory(context), false);
    if (sampler) common_sampler_reset(sampler);
    messages.clear();
    turns.clear();
    resident_tokens.clear();
    sampled_tokens.clear();
    current_turn_start = -1;
    position = system_position = 0;
    shifts = 0;
    turn_evictions = 0;
    cached_bytes.clear();
    assistant_text.clear();
    generating = false;
    generation_eog = false;
    needs_end_of_turn = false;
    context_dirty = false;
}

void free_context() {
    generating = false;
    generation_eog = false;
    needs_end_of_turn = false;
    if (sampler) { common_sampler_free(sampler); sampler = nullptr; }
    templates.reset();
    batch.reset();
    if (context) { llama_free(context); context = nullptr; }
    messages.clear();
    turns.clear();
    resident_tokens.clear();
    sampled_tokens.clear();
    current_turn_start = -1;
    position = system_position = 0;
    cached_bytes.clear();
    assistant_text.clear();
    context_dirty = false;
}

void free_model() {
    free_context();
    if (model) { llama_model_free(model); model = nullptr; }
    model_path.clear();
    system_prompt.clear();
    gpu_layers = 0;
    output_on_cpu = false;
}

bool load_selected_model_layers(int layers, bool cpu_output = false) {
    const bool use_gpu = layers > 0 && gpu;
    auto params = llama_model_default_params();
    ggml_backend_dev_t devices[2] = {use_gpu ? gpu : nullptr, nullptr};
    params.devices = devices;
    params.n_gpu_layers = use_gpu ? layers : 0;
    params.split_mode = LLAMA_SPLIT_MODE_NONE;
    // Keep these descriptors alive for the model lifetime (including lazy loads).
    static llama_model_tensor_buft_override output_overrides[2]{};
    if (use_gpu && cpu_output) {
        auto cpu = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        if (!cpu) return false;
        output_overrides[0] = {"^(output|output_norm|token_embd)\\.(weight|bias)$", ggml_backend_dev_buffer_type(cpu)};
        output_overrides[1] = {nullptr, nullptr};
        params.tensor_buft_overrides = output_overrides;
    }
    params.progress_callback = load_progress;
    params.progress_callback_user_data = nullptr;
    ++gpu_load_attempts;
    model = llama_model_load_from_file(model_path.c_str(), params);
    if (model) {
        gpu_layers = use_gpu ? std::min(layers, llama_model_n_layer(model) + 1) : 0;
        output_on_cpu = use_gpu && cpu_output;
    }
    return model != nullptr;
}

bool reload_model_layers(int layers, bool cpu_output = false) {
    free_context();
    if (model) { llama_model_free(model); model = nullptr; }
    gpu_layers = 0;
    output_on_cpu = false;
    try { return load_selected_model_layers(layers, cpu_output); }
    catch (...) { return false; }
}

// All calls go through this wrapper, including tuning, probes and generation.
// Return values are private to this wrapper; raw llama_decode codes stay intact.
constexpr int DECODE_EXCEPTION = -10000;
constexpr int INVALID_LOGITS = -10001;
int checked_decode(llama_context *target, llama_batch input, bool logits, const char *phase) {
    try {
        const int raw = llama_decode(target, input);
        decode_diagnostics.record(raw, phase, gpu_layers);
        if (raw != 0) return raw;
        if (logits) {
            const auto values = llama_get_logits_ith(target, -1); // synchronizes GPU work and readback
            if (!pocketai::valid_logits(values, llama_vocab_n_tokens(llama_model_get_vocab(model)))) {
                decode_diagnostics.failure(phase, gpu_layers, "invalid logits");
                return INVALID_LOGITS;
            }
        } else {
            llama_synchronize(target);
        }
        return 0;
    } catch (...) {
        decode_diagnostics.failure(phase, gpu_layers, "native exception (no fabricated decode code)");
        return DECODE_EXCEPTION;
    }
}

bool probe_logits(llama_context *candidate) {
    if (!candidate || !model) return false;
    try {
        const int n = std::min<int>(llama_n_batch(candidate), static_cast<int>(llama_n_ctx(candidate)) - HEADROOM - 12);
        if (n < 1) return false;
        NativeBatch probe(n);
        auto token = llama_vocab_bos(llama_model_get_vocab(model));
        if (token < 0) token = llama_vocab_eos(llama_model_get_vocab(model));
        if (token < 0) token = 0;
        llama_tokens tokens(n, token);
        // A short prefill, a full batch after existing KV entries, then sequential
        // decoding exercise graph transitions missed by a BOS-only probe.
        int pos = 0;
        bool ok = true;
        for (int count : {std::min(10, n), n, 1, 1}) {
            if (cancelled.load()) { ok = false; break; }
            apply_threads(candidate);
            if (checked_decode(candidate, probe.make(tokens.data(), count, pos, true), true,
                               count == 1 ? "probe.generation" : "probe.prefill") != 0) {
                ok = false;
                break;
            }
            pos += count;
        }
        llama_synchronize(candidate);
        llama_memory_clear(llama_get_memory(candidate), false);
        return ok && !cancelled.load();
    } catch (...) {
        decode_diagnostics.failure("probe", gpu_layers, "allocation or native exception");
        return false;
    }
}

llama_context *new_context(int requested = 0) {
    auto params = llama_context_default_params();
    const int trained = llama_model_n_ctx_train(model);
    params.n_ctx = std::min(requested ? requested : options.context, trained > 0 ? trained : options.context);
    params.n_batch = std::min(options.batch, static_cast<int>(params.n_ctx));
    params.n_ubatch = params.n_batch;
    active_threads = std::max(1, std::min(options.threads, thread_limit.load(std::memory_order_relaxed)));
    params.n_threads = params.n_threads_batch = active_threads;
    params.offload_kqv = gpu_layers > 0;
    params.op_offload = gpu_layers > 0 && !output_on_cpu; // do not send CPU output weights back to Vulkan
    params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    params.abort_callback = abort_decode;
    params.abort_callback_data = nullptr;
    params.no_perf = false;
    return llama_init_from_model(model, params);
}

void drop_oldest_message_turn() {
    if (messages.empty()) return;
    const size_t first = messages.front().role == "system" ? 1 : 0;
    const size_t available = messages.size() > first ? messages.size() - first : 0;
    const size_t count = std::min<size_t>(2, available);
    if (count) messages.erase(messages.begin() + first, messages.begin() + first + count);
}

bool evict_oldest_turn() {
    if (turns.empty() || !llama_memory_can_shift(llama_get_memory(context))) return false;
    const auto oldest = turns.front();
    const llama_pos start = std::max(system_position, oldest.start);
    const llama_pos end = oldest.end;
    if (end <= start || end > position) return false;
    const llama_pos discard = end - start;
    if (!llama_memory_seq_rm(llama_get_memory(context), 0, start, end)) return false;
    llama_memory_seq_add(llama_get_memory(context), 0, end, position, -discard);
    resident_tokens.erase(resident_tokens.begin() + start, resident_tokens.begin() + end);
    position -= discard;
    turns.erase(turns.begin());
    for (auto &turn : turns) {
        turn.start -= discard;
        turn.end -= discard;
    }
    if (current_turn_start >= end) current_turn_start -= discard;
    else if (current_turn_start > start) current_turn_start = start;
    drop_oldest_message_turn();
    ++shifts;
    ++turn_evictions;
    return true;
}

bool make_room(int required) {
    const int capacity = static_cast<int>(llama_n_ctx(context)) - HEADROOM;
    if (required < 0 || system_position + required > capacity) return false;
    while (position + required > capacity) {
        if (!evict_oldest_turn()) return false;
    }
    return true;
}

bool recover_context(const llama_tokens &retry_tokens, bool logits, const char *phase);

int decode_main(const llama_tokens &tokens, bool logits, const char *phase) {
    if (cancelled.load()) return 2;
    resident_tokens.reserve(resident_tokens.size() + tokens.size());
    apply_threads();
    const int result = checked_decode(context, batch->make(tokens.data(), tokens.size(), position, logits), logits, phase);
    if (result == 0) {
        resident_tokens.insert(resident_tokens.end(), tokens.begin(), tokens.end());
        return 0;
    }
    // KV capacity and cancellation are not GPU compatibility failures.
    if (result == 1 || result == 2 || cancelled.load() || gpu_layers <= 0) return result;
    // Each candidate must pass the actual failed batch, not only the synthetic
    // probe, before it is accepted. This keeps the fallback list finite.
    return recover_context(tokens, logits, phase) ? 0 : result;
}

int decode_prompt(const llama_tokens &tokens, bool last_logit) {
    for (size_t offset = 0; offset < tokens.size();) {
        if (cancelled.load()) { context_dirty = true; return 3; }
        const int count = std::min<int>(llama_n_batch(context), tokens.size() - offset);
        if (!make_room(count)) { context_dirty = true; return 1; }
        const llama_tokens part(tokens.begin() + offset, tokens.begin() + offset + count);
        const int result = decode_main(part, last_logit && offset + count == tokens.size(),
                                      last_logit ? "prompt.user" : "prompt.history");
        if (result) {
            context_dirty = true;
            if (cancelled.load() || result == 2) return 3;
            return result == 1 ? 1 : 2;
        }
        position += count;
        offset += count;
    }
    return 0;
}

std::string format_message(const std::string &role, const std::string &content, bool add_assistant) {
    common_chat_msg message;
    message.role = role;
    message.content = content;
    return common_chat_format_single(templates.get(), messages, message, add_assistant, false);
}

void add_message(const std::string &role, const std::string &content) {
    common_chat_msg message;
    message.role = role;
    message.content = content;
    messages.push_back(std::move(message));
    // Message history is pruned only when the corresponding KV-cache turn is evicted.
    // Keeping both structures in lockstep prevents chat-template history from drifting.
}

llama_tokens tokenize_input(const std::string &text, bool parse_special) {
    auto tokens = common_tokenize(context, text, false, parse_special);
    const auto vocab = llama_model_get_vocab(model);
    const auto bos = llama_vocab_bos(vocab);
    if (!position && llama_vocab_get_add_bos(vocab) && bos >= 0 && (tokens.empty() || tokens.front() != bos))
        tokens.insert(tokens.begin(), bos);
    return tokens;
}

int install_system_prompt() {
    clear_conversation();
    if (system_prompt.empty()) return 0;
    const bool chat_template = common_chat_templates_was_explicit(templates.get());
    const auto formatted = chat_template ? format_message("system", system_prompt, false) : system_prompt;
    const auto tokens = tokenize_input(formatted, chat_template);
    if (tokens.size() > (llama_n_ctx(context) - HEADROOM) / 2) { context_dirty = true; return 1; }
    const int result = decode_prompt(tokens, false);
    if (result) return result;
    system_position = position;
    if (chat_template) add_message("system", system_prompt);
    return 0;
}

void finish_generation() {
    if (!generating) return;
    generation_end = ggml_time_us();
    if (!context_dirty) {
        if (common_chat_templates_was_explicit(templates.get())) {
            add_message("assistant", assistant_text);
            needs_end_of_turn = !generation_eog;
        }
        if (current_turn_start >= system_position && position > current_turn_start) {
            turns.push_back({current_turn_start, position});
        }
    }
    current_turn_start = -1;
    generating = false;
    cached_bytes.clear();
    log_event(ANDROID_LOG_INFO, cancelled.load() ? "Generation cancelled" : "Generation complete");
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_init(JNIEnv *env, jobject, jstring directory) {
    try {
        common_log_set_verbosity_thold(-1);
        llama_log_set(private_backend_log, nullptr);
        ggml_log_set(private_backend_log, nullptr);
        const auto path = java_text(env, directory);
        ggml_backend_load_all_from_path(path.c_str());
        llama_backend_init();
        if (!ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU)) {
            throw_io(env, "CPU inference backend is unavailable"); return;
        }
        gpu = nullptr;
        gpu_description.clear();
        try {
            auto reg = ggml_backend_reg_by_name("Vulkan");
            if (reg) {
                for (size_t i = 0; i < ggml_backend_reg_dev_count(reg); ++i) {
                    auto device = ggml_backend_reg_dev_get(reg, i);
                    const auto type = ggml_backend_dev_type(device);
                    if (type != GGML_BACKEND_DEVICE_TYPE_GPU && type != GGML_BACKEND_DEVICE_TYPE_IGPU) continue;
                    if (!gpu) gpu = device;
                    if (!gpu_description.empty()) gpu_description += "; ";
                    gpu_description += ggml_backend_dev_description(device);
                    size_t free = 0, total = 0;
                    ggml_backend_dev_memory(device, &free, &total);
                    gpu_description += " (" + std::to_string(total / 1024 / 1024) + " MiB shared/device memory)";
                }
            }
        } catch (...) {
            gpu = nullptr;
            gpu_description.clear();
            log_event(ANDROID_LOG_WARN, "Vulkan capability query failed; CPU retained");
        }
        log_event(ANDROID_LOG_INFO, gpu ? "CPU and Vulkan GPU detected" : "CPU detected; Vulkan unavailable");
    } catch (...) { throw_io(env, "Inference backend initialization failed"); }
}

extern "C" JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_configureNative(JNIEnv *env, jobject, jint threads, jint ctx, jint bs, jint layers, jboolean preferCpuOutput, jfloat temp) {
    if (model || threads < 1 || threads > 32 || ctx < 512 || ctx > 32768 || bs < 32 || bs > 1024 || bs > ctx || layers < 0 || layers > 256 || !std::isfinite(temp) || temp < 0 || temp > 2) {
        throw_io(env, "Invalid inference configuration or model still loaded"); return;
    }
    options = {threads, ctx, bs, layers, preferCpuOutput == JNI_TRUE, temp};
    thread_limit.store(threads);
    active_threads = threads;
    fallback.clear();
    gpu_load_attempts = 0;
    context_backoffs = 0;
    gpu_probe_failures = 0;
    decode_diagnostics = {};
    runtime_recoveries = 0;
    tuned_thread_choice = 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_setThreadLimitNative(JNIEnv *, jobject, jint threads) {
    thread_limit.store(std::clamp(static_cast<int>(threads), 1, 32));
}

extern "C" JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_requestCancel(JNIEnv *, jobject) { cancelled.store(true); }

extern "C" JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_resetCancellation(JNIEnv *, jobject) { cancelled.store(false); }

extern "C" JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring path) {
    free_model();
    model_path = java_text(env, path);
    fallback.clear();
    gpu_load_attempts = 0;
    context_backoffs = 0;
    gpu_probe_failures = 0;
    decode_diagnostics = {};
    runtime_recoveries = 0;
    const bool want_gpu = options.gpu_layers > 0 && gpu;
    if (options.gpu_layers > 0 && !gpu) note_fallback("Vulkan device unavailable; CPU selected");

    if (!want_gpu) {
        try { return load_selected_model_layers(0) ? 0 : 1; }
        catch (...) { return 1; }
    }

    // Fast path: request the maximum selected offload. llama.cpp clamps this to the model.
    try { if (load_selected_model_layers(options.gpu_layers, options.prefer_cpu_output)) return 0; }
    catch (...) { log_event(ANDROID_LOG_WARN, "Maximum Vulkan offload failed"); }
    if (cancelled.load()) return 3;
    if (model) { llama_model_free(model); model = nullptr; }

    // Load once on CPU to discover the exact layer count, then retry useful partial offloads.
    int model_layers = 0;
    try {
        if (load_selected_model_layers(0)) model_layers = llama_model_n_layer(model) + 1;
    } catch (...) { model_layers = 0; }
    if (cancelled.load()) return 3;
    if (!model) return 1;
    llama_model_free(model); model = nullptr;

    const int requested = std::min(options.gpu_layers, model_layers);
    for (const auto choice : pocketai::fallback_choices(requested, options.prefer_cpu_output)) {
        if (cancelled.load()) return 3;
        if (choice.layers == 0) continue;
        if (reload_model_layers(choice.layers, choice.output_cpu)) {
            note_fallback("Vulkan model load auto-tuned to " + std::to_string(gpu_layers) + " layers");
            return 0;
        }
    }

    log_event(ANDROID_LOG_WARN, "Partial Vulkan model loading failed; selecting CPU");
    if (reload_model_layers(0)) {
        note_fallback("Vulkan model allocation failed; CPU selected");
        return 0;
    }
    return 1;
}

namespace {
bool try_context(int requested) {
    try { context = new_context(requested); }
    catch (...) { context = nullptr; }
    if (!context) {
        decode_diagnostics.failure("context", gpu_layers, "allocation failed");
        return false;
    }
    if (!probe_logits(context)) {
        if (gpu_layers > 0 && !cancelled.load()) ++gpu_probe_failures;
        llama_free(context);
        context = nullptr;
    }
    return context != nullptr;
}

bool initialize_context_resources() {
    try {
        batch = std::make_unique<NativeBatch>(llama_n_batch(context));
        templates = common_chat_templates_init(model, "");
        common_params_sampling sampling;
        sampling.temp = options.temperature;
        sampler = common_sampler_init(model, sampling);
        if (!sampler || !templates) { free_context(); return false; }
        clear_conversation();
        return true;
    } catch (...) { free_context(); return false; }
}

bool recover_context(const llama_tokens &retry_tokens, bool logits, const char *phase) {
    if (!context || gpu_layers <= 0 || cancelled.load()) return false;
    const int old_layers = gpu_layers;
    const bool old_output = output_on_cpu;
    const int old_ctx = llama_n_ctx(context);
    if (resident_tokens.size() != static_cast<size_t>(position)) return false;
    // Copy before unloading: model/template/sampler pointers cannot survive a reload.
    auto saved_tokens = resident_tokens;
    auto restored_tokens = resident_tokens;
    restored_tokens.insert(restored_tokens.end(), retry_tokens.begin(), retry_tokens.end());
    auto saved_sampled = sampled_tokens;
    auto saved_messages = messages;
    auto saved_turns = turns;
    const auto saved_position = position;
    const auto saved_system = system_position;
    const auto saved_turn_start = current_turn_start;
    auto saved_assistant = assistant_text;
    auto saved_bytes = cached_bytes;
    const bool saved_generating = generating, saved_eog = generation_eog, saved_eot = needs_end_of_turn;
    const int saved_shifts = shifts, saved_evictions = turn_evictions;

    const bool restored = pocketai::try_fallbacks(old_layers, old_output,
        [] { return cancelled.load(); }, [&](pocketai::BackendChoice choice) {
            try {
            if (!reload_model_layers(choice.layers, choice.output_cpu)) return false;
            // Runtime recovery never shrinks context or silently discards conversation.
            if (!try_context(old_ctx) || !initialize_context_resources()) return false;
            NativeBatch replay(llama_n_batch(context));
            for (size_t offset = 0; offset < saved_tokens.size();) {
                if (cancelled.load()) return false;
                const int count = std::min<size_t>(llama_n_batch(context), saved_tokens.size() - offset);
                const bool last = offset + count == saved_tokens.size();
                if (checked_decode(context, replay.make(saved_tokens.data() + offset, count, offset, last),
                                   last, "recovery.replay") != 0) return false;
                offset += count;
            }
            for (const auto token : saved_sampled) common_sampler_accept(sampler, token, true);
            return checked_decode(context, batch->make(retry_tokens.data(), retry_tokens.size(), saved_position, logits),
                                  logits, phase) == 0;
            } catch (...) {
                decode_diagnostics.failure("recovery", choice.layers, "allocation or native exception");
                return false;
            }
        });
    if (!restored) { free_context(); return false; }
    resident_tokens = std::move(restored_tokens);
    sampled_tokens = std::move(saved_sampled);
    messages = std::move(saved_messages);
    turns = std::move(saved_turns);
    position = saved_position;
    system_position = saved_system;
    current_turn_start = saved_turn_start;
    assistant_text = std::move(saved_assistant);
    cached_bytes = std::move(saved_bytes);
    generating = saved_generating;
    generation_eog = saved_eog;
    needs_end_of_turn = saved_eot;
    shifts = saved_shifts;
    turn_evictions = saved_evictions;
    ++runtime_recoveries;
    note_fallback("runtime recovery " + std::to_string(old_layers) + " -> " + std::to_string(gpu_layers) +
                  (output_on_cpu ? " output tensors on CPU" : gpu_layers == 0 ? " CPU" : " GPU"));
    return true;
}
}

extern "C" JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(JNIEnv *, jobject) {
    if (!model) return 1;
    try {
        const int requested_context = options.context;
        const int initial_gpu_layers = gpu_layers;
        const bool initial_output_cpu = output_on_cpu;
        free_context();
        if (!try_context(requested_context) && initial_gpu_layers > 0 && !cancelled.load()) {
            pocketai::try_fallbacks(initial_gpu_layers, initial_output_cpu,
                [] { return cancelled.load(); }, [&](pocketai::BackendChoice choice) {
                    if (!reload_model_layers(choice.layers, choice.output_cpu)) return false;
                    if (!try_context(requested_context)) return false;
                    note_fallback("validated offload " + std::to_string(initial_gpu_layers) + " -> " +
                                  std::to_string(gpu_layers) + (output_on_cpu ? " output tensors on CPU" : " CPU"));
                    return true;
                });
        }
        if (!context && model && !cancelled.load()) {
            for (int candidate : pocketai::context_backoff_candidates(requested_context)) {
                if (cancelled.load()) break;
                free_context();
                if (try_context(candidate)) {
                    ++context_backoffs;
                    note_fallback("context auto-tuned " + std::to_string(requested_context) + " -> " + std::to_string(candidate));
                    break;
                }
            }
        }
        if (!context || cancelled.load() || !initialize_context_resources()) { free_context(); return 1; }
        budget.start(0);
        prompt_tokens = 0;
        prompt_us = 0;
        generation_start = generation_end = 0;
        log_event(ANDROID_LOG_INFO, gpu_layers > 0 ? "Vulkan model ready (prefill and logits validated)" : "CPU model ready");
        return 0;
    } catch (...) { free_context(); return 1; }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processSystemPrompt(JNIEnv *env, jobject, jstring text) {
    if (!context) return 2;
    try { system_prompt = java_text(env, text); return install_system_prompt(); }
    catch (...) { context_dirty = true; return 2; }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_countPromptTokensNative(JNIEnv *env, jobject, jstring text) {
    if (!context || !model || !templates) return -1;
    try {
        const auto user = java_text(env, text);
        const bool chat_template = common_chat_templates_was_explicit(templates.get());
        const auto formatted = chat_template ? format_message("user", user, true) : user;
        const auto tokens = tokenize_input(formatted, chat_template);
        return static_cast<jint>(tokens.size() + (needs_end_of_turn && !turns.empty() ? 1 : 0));
    } catch (...) {
        throw_io(env, "Prompt tokenization failed");
        return -1;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_promptCapacityNative(JNIEnv *, jobject) {
    if (!context) return -1;
    return std::max(0, static_cast<int>(llama_n_ctx(context)) - static_cast<int>(system_position) - HEADROOM - 1);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_tuneThreadsNative(JNIEnv *, jobject, jint maximum) {
    if (!model || !context || maximum < 1 || maximum > 32) return -1;
    llama_context *bench_context = nullptr;
    std::unique_ptr<NativeBatch> bench_batch;
    try {
        bench_context = new_context(512);
        if (!bench_context) return -1;
        if (!probe_logits(bench_context)) throw std::runtime_error("thread tune validation");
        bench_batch = std::make_unique<NativeBatch>(1);

        const auto candidates = pocketai::thread_candidates(static_cast<int>(maximum));

        const auto vocab = llama_model_get_vocab(model);
        auto token = llama_vocab_bos(vocab);
        if (token < 0) token = 0;
        double best_speed = -1.0;
        int best_threads = std::min(static_cast<int>(maximum), options.threads);
        std::vector<int> tested;

        const auto benchmark = [&](int threads) {
            if (cancelled.load()) throw std::runtime_error("cancelled");
            llama_memory_clear(llama_get_memory(bench_context), false);
            llama_set_n_threads(bench_context, threads, threads);

            // One warm-up token is enough to wake CPU/GPU clocks without making tuning intrusive.
            if (checked_decode(bench_context, bench_batch->make(&token, 1, 0, true), true, "tune.warmup"))
                throw std::runtime_error("thread tune warmup");

            llama_memory_clear(llama_get_memory(bench_context), false);
            const auto started = ggml_time_us();
            constexpr int TOKENS = 6;
            for (int i = 0; i < TOKENS; ++i) {
                if (cancelled.load()) throw std::runtime_error("cancelled");
                if (checked_decode(bench_context, bench_batch->make(&token, 1, i, true), true, "tune.generation"))
                    throw std::runtime_error("thread tune decode");
            }
            const auto elapsed = std::max<int64_t>(1, ggml_time_us() - started);
            return TOKENS * 1e6 / elapsed;
        };

        const auto consider = [&](int threads) {
            if (threads < 1 || threads > maximum ||
                std::find(tested.begin(), tested.end(), threads) != tested.end()) return;
            const double speed = benchmark(threads);
            tested.push_back(threads);
            if (speed > best_speed) {
                best_speed = speed;
                best_threads = threads;
            }
        };

        for (const int threads : candidates) consider(threads);
        const int coarse_best = best_threads;
        consider(coarse_best - 1);
        consider(coarse_best + 1);

        bench_batch.reset();
        llama_free(bench_context);
        bench_context = nullptr;
        tuned_thread_choice = best_threads;
        thread_limit.store(best_threads);
        active_threads = std::max(1, std::min(options.threads, best_threads));
        llama_set_n_threads(context, active_threads, active_threads);
        log_event(ANDROID_LOG_INFO, "CPU thread auto-tune completed");
        return best_threads;
    } catch (...) {
        bench_batch.reset();
        if (bench_context) llama_free(bench_context);
        return cancelled.load() ? -2 : -1;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processUserPrompt(JNIEnv *env, jobject, jstring text, jint maximum) {
    if (!context || maximum < 1 || maximum > 32768) return 2;
    finish_generation();
    try {
        if (context_dirty && install_system_prompt()) return 2;
        if (needs_end_of_turn) {
            const auto vocab = llama_model_get_vocab(model);
            auto eot = llama_vocab_eot(vocab);
            if (eot < 0) eot = llama_vocab_eos(vocab);
            if (eot < 0) { context_dirty = true; return 2; }
            // Reserve room first. If this evicts the previous turn entirely, its EOT
            // must not be decoded as an orphan token at the beginning of the new turn.
            if (!make_room(1)) { context_dirty = true; return 1; }
            if (!turns.empty()) {
                const int result = decode_prompt({eot}, false);
                if (result) return result;
                turns.back().end = position;
            }
            needs_end_of_turn = false;
        }
        const auto user = java_text(env, text);
        const bool chat_template = common_chat_templates_was_explicit(templates.get());
        const auto formatted = chat_template ? format_message("user", user, true) : user;
        const auto tokens = tokenize_input(formatted, chat_template);
        if (tokens.empty() || tokens.size() > llama_n_ctx(context) - system_position - HEADROOM - 1) return 1;
        cached_bytes.clear();
        assistant_text.clear();
        common_sampler_reset(sampler);
        sampled_tokens.clear();
        const auto start = ggml_time_us();
        if (!make_room(static_cast<int>(tokens.size()))) { context_dirty = true; return 1; }
        current_turn_start = position;
        const int result = decode_prompt(tokens, true);
        prompt_us = ggml_time_us() - start;
        prompt_tokens = tokens.size();
        if (result) {
            current_turn_start = -1;
            return result;
        }
        if (chat_template) add_message("user", user);
        budget.start(maximum);
        generation_start = ggml_time_us();
        generation_end = 0;
        generation_eog = false;
        generating = true;
        return 0;
    } catch (...) {
        current_turn_start = -1;
        context_dirty = true;
        return 2;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_generateNextToken(JNIEnv *env, jobject) {
    if (!context || !generating || cancelled.load() || budget.exhausted()) { finish_generation(); return nullptr; }
    try {
        if (!make_room(1)) {
            finish_generation();
            throw_io(env, "This model cannot slide its context; reset the conversation"); return nullptr;
        }
        apply_threads();
        sampled_tokens.reserve(sampled_tokens.size() + 1);
        const auto token = common_sampler_sample(sampler, context, -1);
        if (decode_main({token}, true, "generation")) {
            context_dirty = true;
            finish_generation();
            if (!cancelled.load()) throw_io(env, "Native token decode failed; reset or reduce model/context size");
            return nullptr;
        }
        common_sampler_accept(sampler, token, true);
        sampled_tokens.push_back(token);
        ++position;
        if (llama_vocab_is_eog(llama_model_get_vocab(model), token)) { generation_eog = true; finish_generation(); return nullptr; }
        budget.consume();
        cached_bytes += common_token_to_piece(context, token);
        if (pocketai::complete_utf8(cached_bytes)) {
            assistant_text += cached_bytes;
            const auto result = android_text(env, cached_bytes);
            cached_bytes.clear();
            return result;
        }
        if (cached_bytes.size() > 8) {
            cached_bytes.clear();
            assistant_text += "\xEF\xBF\xBD";
            return android_text(env, "\xEF\xBF\xBD");
        }
        return android_text(env, "");
    } catch (...) {
        context_dirty = true;
        finish_generation();
        throw_io(env, "Native generation failed"); return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_finishGeneration(JNIEnv *, jobject) { finish_generation(); }

extern "C" JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeDiagnostics(JNIEnv *env, jobject) {
    std::ostringstream out;
    out.precision(3);
    out << "Vulkan GPU: " << (gpu_description.empty() ? "unavailable (device/driver unsupported or backend absent)" : gpu_description) << '\n';
    out << "Requested: " << (options.gpu_layers ? "Vulkan" : "CPU") << "; active: " << (context ? gpu_layers > 0 ? "Vulkan + CPU" : "CPU" : "no model") << '\n';
    out << "GPU layers: " << gpu_layers << "; load attempts: " << gpu_load_attempts
        << "; logits probe failures: " << gpu_probe_failures
        << "; fallback: " << (fallback.empty() ? "none" : fallback) << '\n';
    out << "Threads: " << active_threads << " / " << options.threads << "; thermal/preferred limit: " << thread_limit.load()
        << "; auto-tuned: " << (tuned_thread_choice ? std::to_string(tuned_thread_choice) : "not run") << '\n';
    out << "Context: " << (context ? llama_n_ctx(context) : options.context) << " / requested " << options.context
        << "; backoffs: " << context_backoffs << "; batch: " << options.batch << "; temperature: " << options.temperature << '\n';
    out << "Generated: " << budget.produced << " / " << budget.limit << "; context shifts: " << shifts
        << "; turn evictions: " << turn_evictions << "; resident turns: " << turns.size() << '\n';
    const int64_t duration = generation_start ? (generation_end ? generation_end : ggml_time_us()) - generation_start : 0;
    out << "Generation: " << (duration > 0 ? budget.produced * 1e6 / duration : 0.0) << " tokens/s; prompt: " << (prompt_us > 0 ? prompt_tokens * 1e6 / prompt_us : 0.0) << " tokens/s\n";
    out << "Backend warnings: " << backend_warnings.load() << "; errors: " << backend_errors.load()
        << "; last decode code: " << (decode_diagnostics.has_code ? std::to_string(decode_diagnostics.code) : "not run")
        << "; phase: " << decode_diagnostics.phase << '\n';
    out << "Output placement policy: " << (gpu_layers == 0 ? "CPU" : output_on_cpu ? "CPU tensor overrides; host op offload disabled" : "backend default")
        << "; runtime recoveries: " << runtime_recoveries << '\n';
    for (const auto &failure : decode_diagnostics.failures) out << "Failure: " << failure << '\n';
    out << "Prompt/content logging: disabled\n" << llama_print_system_info();
    return android_text(env, out.str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_benchModel(JNIEnv *env, jobject, jint pp, jint tg, jint pl, jint nr) {
    if (!model || pp < 1 || pp > options.context - HEADROOM || tg < 1 || tg > options.context - HEADROOM || pl != 1 || nr < 1 || nr > 5)
        return android_text(env, "Invalid benchmark parameters for the configured context");
    llama_context *bench_context = nullptr;
    std::unique_ptr<NativeBatch> bench_batch;
    try {
        bench_context = new_context(context ? llama_n_ctx(context) : options.context);
        if (!bench_context) return android_text(env, "Benchmark context allocation failed");
        if (pp > static_cast<int>(llama_n_ctx(bench_context)) - HEADROOM || tg > static_cast<int>(llama_n_ctx(bench_context)) - HEADROOM) {
            llama_free(bench_context);
            return android_text(env, "Benchmark exceeds the model context");
        }
        if (!probe_logits(bench_context)) throw std::runtime_error("benchmark validation");
        bench_batch = std::make_unique<NativeBatch>(llama_n_batch(bench_context));
        double prompt_speed = 0, generation_speed = 0;
        const auto vocab = llama_model_get_vocab(model);
        auto token = llama_vocab_bos(vocab);
        if (token < 0) token = 0;
        for (int repeat = 0; repeat < nr; ++repeat) {
            llama_memory_clear(llama_get_memory(bench_context), false);
            const auto start = ggml_time_us();
            for (int offset = 0; offset < pp;) {
                if (cancelled.load()) throw std::runtime_error("cancelled");
                apply_threads(bench_context);
                const int count = std::min<int>(llama_n_batch(bench_context), pp - offset);
                const llama_tokens tokens(count, token);
                const bool last = offset + count == pp;
                if (checked_decode(bench_context, bench_batch->make(tokens.data(), count, offset, last), last, "benchmark.prefill"))
                    throw std::runtime_error("decode");
                offset += count;
            }
            prompt_speed += pp * 1e6 / std::max<int64_t>(1, ggml_time_us() - start);
            llama_memory_clear(llama_get_memory(bench_context), false);
            const auto generation = ggml_time_us();
            for (int i = 0; i < tg; ++i) {
                if (cancelled.load()) throw std::runtime_error("cancelled");
                apply_threads(bench_context);
                if (checked_decode(bench_context, bench_batch->make(&token, 1, i, true), true, "benchmark.generation"))
                    throw std::runtime_error("decode");
            }
            generation_speed += tg * 1e6 / std::max<int64_t>(1, ggml_time_us() - generation);
        }
        bench_batch.reset();
        llama_free(bench_context);
        bench_context = nullptr;
        std::ostringstream out;
        out.precision(3);
        out << "Prompt: " << prompt_speed / nr << " tokens/s\nGeneration: " << generation_speed / nr << " tokens/s\nBackend: " << (gpu_layers > 0 ? "Vulkan + CPU" : "CPU");
        return android_text(env, out.str());
    } catch (...) {
        bench_batch.reset();
        if (bench_context) llama_free(bench_context);
        return android_text(env, cancelled.load() ? "Benchmark cancelled" : "Benchmark failed (allocation or decode)");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_unload(JNIEnv *, jobject) {
    free_model();
    log_event(ANDROID_LOG_INFO, "Model resources released");
}

extern "C" JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_shutdown(JNIEnv *, jobject) { llama_backend_free(); }

extern "C" JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_isContextReadyNative(JNIEnv *, jobject) {
    return context && sampler && templates && batch ? JNI_TRUE : JNI_FALSE;
}
