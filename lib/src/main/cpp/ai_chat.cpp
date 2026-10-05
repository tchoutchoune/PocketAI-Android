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

namespace {
constexpr int HEADROOM = 8;
struct Options {
    int threads = 4;
    int context = 2048;
    int batch = 256;
    int gpu_layers = 0;
    float temperature = 0.6f;
} options;
llama_model *model = nullptr;
llama_context *context = nullptr;
llama_batch batch{};
bool batch_allocated = false;
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
    if (batch_allocated) { llama_batch_free(batch); batch = {}; batch_allocated = false; }
    if (context) { llama_free(context); context = nullptr; }
    messages.clear();
    turns.clear();
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
}

bool load_selected_model(bool use_gpu) {
    auto params = llama_model_default_params();
    ggml_backend_dev_t devices[2] = {use_gpu ? gpu : nullptr, nullptr};
    params.devices = devices;
    params.n_gpu_layers = use_gpu ? options.gpu_layers : 0;
    params.split_mode = LLAMA_SPLIT_MODE_NONE;
    params.progress_callback = load_progress;
    params.progress_callback_user_data = nullptr;
    model = llama_model_load_from_file(model_path.c_str(), params);
    if (model) gpu_layers = use_gpu ? std::min(options.gpu_layers, llama_model_n_layer(model) + 1) : 0;
    return model != nullptr;
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
    params.op_offload = gpu_layers > 0;
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

int decode_prompt(const llama_tokens &tokens, bool last_logit) {
    for (size_t offset = 0; offset < tokens.size();) {
        if (cancelled.load()) { context_dirty = true; return 3; }
        const int count = std::min(options.batch, static_cast<int>(tokens.size() - offset));
        if (!make_room(count)) { context_dirty = true; return 1; }
        apply_threads();
        common_batch_clear(batch);
        for (int i = 0; i < count; ++i)
            common_batch_add(batch, tokens[offset + i], position + i, {0}, last_logit && offset + i + 1 == tokens.size());
        const int result = llama_decode(context, batch);
        if (result) { context_dirty = true; return cancelled.load() ? 3 : 2; }
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
    // Templates need recent role ordering; keep the native history bounded as the KV cache slides.
    if (messages.size() > 64) {
        const size_t first = messages.front().role == "system" ? 1 : 0;
        messages.erase(messages.begin() + first, messages.begin() + first + 2);
    }
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
Java_com_arm_aichat_internal_InferenceEngineImpl_configureNative(JNIEnv *env, jobject, jint threads, jint ctx, jint bs, jint layers, jfloat temp) {
    if (model || threads < 1 || threads > 32 || ctx < 512 || ctx > 32768 || bs < 32 || bs > 1024 || bs > ctx || layers < 0 || layers > 256 || !std::isfinite(temp) || temp < 0 || temp > 2) {
        throw_io(env, "Invalid inference configuration or model still loaded"); return;
    }
    options = {threads, ctx, bs, layers, temp};
    thread_limit.store(threads);
    active_threads = threads;
    fallback.clear();
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
    const bool want_gpu = options.gpu_layers > 0 && gpu;
    if (options.gpu_layers > 0 && !gpu) fallback = "Vulkan device unavailable; CPU selected";
    try { if (load_selected_model(want_gpu)) return 0; }
    catch (...) { log_event(ANDROID_LOG_WARN, "Model backend load failed"); }
    if (cancelled.load()) return 3;
    if (want_gpu) {
        if (model) { llama_model_free(model); model = nullptr; }
        gpu_layers = 0;
        fallback = "GPU model allocation failed; CPU fallback";
        log_event(ANDROID_LOG_WARN, "Retrying model on CPU");
        try { if (load_selected_model(false)) return 0; } catch (...) { }
    }
    return 1;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(JNIEnv *, jobject) {
    if (!model) return 1;
    free_context();
    try { context = new_context(); } catch (...) { context = nullptr; }
    if (!context && gpu_layers > 0 && !cancelled.load()) {
        llama_model_free(model); model = nullptr;
        gpu_layers = 0;
        fallback = "GPU context allocation failed; CPU fallback";
        log_event(ANDROID_LOG_WARN, "Retrying context on CPU");
        try { if (load_selected_model(false)) context = new_context(); } catch (...) { context = nullptr; }
    }
    if (!context || cancelled.load()) { free_context(); return 1; }
    try {
        batch = llama_batch_init(options.batch, 0, 1);
        batch_allocated = true;
        if (!batch.token || !batch.pos || !batch.n_seq_id || !batch.seq_id || !batch.logits) { free_context(); return 1; }
        templates = common_chat_templates_init(model, "");
        common_params_sampling sampling;
        sampling.temp = options.temperature;
        sampler = common_sampler_init(model, sampling);
        if (!sampler || !templates) { free_context(); return 1; }
        clear_conversation();
        shifts = 0;
        budget.start(0);
        prompt_tokens = 0;
        prompt_us = 0;
        generation_start = generation_end = 0;
        log_event(ANDROID_LOG_INFO, gpu_layers > 0 ? "Vulkan model ready" : "CPU model ready");
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
        return static_cast<jint>(tokenize_input(formatted, chat_template).size());
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
        const auto token = common_sampler_sample(sampler, context, -1);
        common_sampler_accept(sampler, token, true);
        common_batch_clear(batch);
        common_batch_add(batch, token, position, {0}, true);
        if (llama_decode(context, batch)) {
            context_dirty = true;
            finish_generation();
            if (!cancelled.load()) throw_io(env, "Native token decode failed; reset or reduce model/context size");
            return nullptr;
        }
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
    out << "GPU layers: " << gpu_layers << "; fallback: " << (fallback.empty() ? "none" : fallback) << '\n';
    out << "Threads: " << active_threads << " / " << options.threads << "; thermal limit: " << thread_limit.load() << '\n';
    out << "Context: " << (context ? llama_n_ctx(context) : options.context) << "; batch: " << options.batch << "; temperature: " << options.temperature << '\n';
    out << "Generated: " << budget.produced << " / " << budget.limit << "; context shifts: " << shifts
        << "; turn evictions: " << turn_evictions << "; resident turns: " << turns.size() << '\n';
    const int64_t duration = generation_start ? (generation_end ? generation_end : ggml_time_us()) - generation_start : 0;
    out << "Generation: " << (duration > 0 ? budget.produced * 1e6 / duration : 0.0) << " tokens/s; prompt: " << (prompt_us > 0 ? prompt_tokens * 1e6 / prompt_us : 0.0) << " tokens/s\n";
    out << "Backend warnings: " << backend_warnings.load() << "; errors: " << backend_errors.load() << '\n';
    out << "Prompt/content logging: disabled\n" << llama_print_system_info();
    return android_text(env, out.str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_benchModel(JNIEnv *env, jobject, jint pp, jint tg, jint pl, jint nr) {
    if (!model || pp < 1 || pp > options.context - HEADROOM || tg < 1 || tg > options.context - HEADROOM || pl != 1 || nr < 1 || nr > 5)
        return android_text(env, "Invalid benchmark parameters for the configured context");
    llama_context *bench_context = nullptr;
    llama_batch bench_batch{};
    bool allocated = false;
    try {
        bench_context = new_context();
        if (!bench_context) return android_text(env, "Benchmark context allocation failed");
        if (pp > static_cast<int>(llama_n_ctx(bench_context)) - HEADROOM || tg > static_cast<int>(llama_n_ctx(bench_context)) - HEADROOM) {
            llama_free(bench_context);
            return android_text(env, "Benchmark exceeds the model context");
        }
        bench_batch = llama_batch_init(options.batch, 0, 1);
        allocated = true;
        if (!bench_batch.token || !bench_batch.pos || !bench_batch.seq_id || !bench_batch.logits) throw std::runtime_error("batch allocation");
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
                common_batch_clear(bench_batch);
                const int count = std::min(options.batch, pp - offset);
                for (int i = 0; i < count; ++i) common_batch_add(bench_batch, token, offset + i, {0}, offset + i + 1 == pp);
                if (llama_decode(bench_context, bench_batch)) throw std::runtime_error("decode");
                offset += count;
            }
            prompt_speed += pp * 1e6 / std::max<int64_t>(1, ggml_time_us() - start);
            llama_memory_clear(llama_get_memory(bench_context), false);
            const auto generation = ggml_time_us();
            for (int i = 0; i < tg; ++i) {
                if (cancelled.load()) throw std::runtime_error("cancelled");
                apply_threads(bench_context);
                common_batch_clear(bench_batch);
                common_batch_add(bench_batch, token, i, {0}, true);
                if (llama_decode(bench_context, bench_batch)) throw std::runtime_error("decode");
            }
            generation_speed += tg * 1e6 / std::max<int64_t>(1, ggml_time_us() - generation);
        }
        llama_batch_free(bench_batch);
        llama_free(bench_context);
        std::ostringstream out;
        out.precision(3);
        out << "Prompt: " << prompt_speed / nr << " tokens/s\nGeneration: " << generation_speed / nr << " tokens/s\nBackend: " << (gpu_layers > 0 ? "Vulkan + CPU" : "CPU");
        return android_text(env, out.str());
    } catch (...) {
        if (allocated) llama_batch_free(bench_batch);
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
