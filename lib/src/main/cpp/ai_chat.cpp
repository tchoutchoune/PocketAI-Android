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
constexpr int HEADROOM = 64;
constexpr int MIN_GENERATION_TOKENS = 64;
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
bool template_supports_thinking = false;
std::vector<common_chat_msg> messages;
std::vector<llama_token> kv_tokens;
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
int history_resets = 0;
int64_t generation_start = 0;
int64_t generation_end = 0;
int64_t model_load_us = 0;
int64_t context_prepare_us = 0;
int64_t system_prompt_us = 0;
int64_t prompt_render_us = 0;
int64_t prompt_tokenize_us = 0;
int64_t prompt_us = 0;
int prompt_tokens = 0;
int prompt_reused_tokens = 0;
int prompt_decoded_tokens = 0;
int history_alignment_tokens = 0;
int history_alignment_kv_tokens = 0;
int history_alignment_plain_tokens = 0;
int history_alignment_empty_tokens = 0;
int history_divergence_index = -1;
std::string history_alignment_mode = "none";
std::string history_divergence_window = "none";
int fallback_events = 0;
llama_token last_generated_token = -1;
int repeated_token_streak = 0;
bool generation_degenerate = false;

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
    kv_tokens.clear();
    position = system_position = 0;
    cached_bytes.clear();
    assistant_text.clear();
    generating = false;
    generation_eog = false;
    needs_end_of_turn = false;
    context_dirty = false;
    last_generated_token = -1;
    repeated_token_streak = 0;
    generation_degenerate = false;
}

void free_context() {
    generating = false;
    generation_eog = false;
    needs_end_of_turn = false;
    if (sampler) { common_sampler_free(sampler); sampler = nullptr; }
    templates.reset();
    template_supports_thinking = false;
    if (batch_allocated) { llama_batch_free(batch); batch = {}; batch_allocated = false; }
    if (context) { llama_free(context); context = nullptr; }
    messages.clear();
    kv_tokens.clear();
    position = system_position = 0;
    cached_bytes.clear();
    assistant_text.clear();
    context_dirty = false;
    last_generated_token = -1;
    repeated_token_streak = 0;
    generation_degenerate = false;
}

void free_model() {
    free_context();
    if (model) { llama_model_free(model); model = nullptr; }
    model_path.clear();
    system_prompt.clear();
    gpu_layers = 0;
    model_load_us = 0;
    context_prepare_us = 0;
    system_prompt_us = 0;
    prompt_render_us = 0;
    prompt_tokenize_us = 0;
    prompt_us = 0;
    prompt_tokens = 0;
    prompt_reused_tokens = 0;
    prompt_decoded_tokens = 0;
    history_alignment_tokens = 0;
    history_alignment_kv_tokens = 0;
    history_alignment_plain_tokens = 0;
    history_alignment_empty_tokens = 0;
    history_divergence_index = -1;
    history_alignment_mode = "none";
    history_divergence_window = "none";
    fallback_events = 0;
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

int desired_context_size() {
    const int trained = llama_model_n_ctx_train(model);
    return std::max(512, std::min(options.context, trained > 0 ? trained : options.context));
}

llama_context *new_context_with_fallback(int &selected) {
    const int desired = desired_context_size();
    std::vector<int> candidates{desired};
    for (const int candidate : {16384, 8192, 4096, 2048, 1024, 512}) {
        if (candidate < desired && std::find(candidates.begin(), candidates.end(), candidate) == candidates.end())
            candidates.push_back(candidate);
    }

    for (const int candidate : candidates) {
        if (cancelled.load()) break;
        try {
            auto *attempt = new_context(candidate);
            if (attempt) {
                selected = static_cast<int>(llama_n_ctx(attempt));
                return attempt;
            }
        } catch (...) {
            // Try the next conservative context size.
        }
    }
    selected = 0;
    return nullptr;
}

void append_fallback(const std::string &message) {
    if (message.empty()) return;
    if (!fallback.empty()) fallback += "; ";
    fallback += message;
}

/**
 * Reserve the whole current turn before decoding it. When the existing history
 * no longer leaves enough room, drop the old conversational KV tail at once
 * instead of sliding the context during the assistant response. The system
 * prompt remains pinned.
 */
bool make_turn_room(int required) {
    const int capacity = static_cast<int>(llama_n_ctx(context)) - HEADROOM;
    if (position + required <= capacity) return true;
    if (system_position + required > capacity) return false;

    auto memory = llama_get_memory(context);
    if (!memory || !llama_memory_seq_rm(memory, 0, system_position, position)) return false;
    position = system_position;
    if (kv_tokens.size() >= static_cast<size_t>(system_position))
        kv_tokens.resize(static_cast<size_t>(system_position));
    else
        kv_tokens.clear();

    if (!messages.empty() && messages.front().role == "system") messages.resize(1);
    else messages.clear();

    ++history_resets;
    return true;
}

int decode_prompt(const llama_tokens &tokens, bool last_logit) {
    for (size_t offset = 0; offset < tokens.size();) {
        if (cancelled.load()) { context_dirty = true; return 3; }
        const int count = std::min(options.batch, static_cast<int>(tokens.size() - offset));
        const int capacity = static_cast<int>(llama_n_ctx(context)) - HEADROOM;
        if (position + count > capacity) { context_dirty = true; return 1; }
        apply_threads();
        common_batch_clear(batch);
        for (int i = 0; i < count; ++i)
            common_batch_add(batch, tokens[offset + i], position + i, {0}, last_logit && offset + i + 1 == tokens.size());
        const int result = llama_decode(context, batch);
        if (result) { context_dirty = true; return cancelled.load() ? 3 : 2; }
        kv_tokens.insert(kv_tokens.end(), tokens.begin() + static_cast<std::ptrdiff_t>(offset),
                         tokens.begin() + static_cast<std::ptrdiff_t>(offset + count));
        position += count;
        offset += count;
    }
    return 0;
}

/**
 * Keep an exact token-identical KV prefix. Re-evaluate at least the final prompt
 * token afterwards so llama.cpp exposes logits for the current prompt rather than
 * stale logits from an older, longer sequence.
 */
size_t trim_kv_to_prefix(size_t requested_prefix) {
    if (!context || kv_tokens.size() != static_cast<size_t>(position)) {
        if (context) {
            auto memory = llama_get_memory(context);
            if (memory) llama_memory_clear(memory, false);
        }
        kv_tokens.clear();
        position = 0;
        return 0;
    }

    const size_t prefix = std::min(requested_prefix, kv_tokens.size());
    if (prefix == kv_tokens.size()) return prefix;

    auto memory = llama_get_memory(context);
    if (!memory || !llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(prefix), position)) {
        if (memory) llama_memory_clear(memory, false);
        kv_tokens.clear();
        position = 0;
        return 0;
    }

    position = static_cast<llama_pos>(prefix);
    kv_tokens.resize(prefix);
    return prefix;
}

std::string format_message(const std::string &role, const std::string &content, bool add_assistant) {
    common_chat_msg message;
    message.role = role;
    message.content = content;

    // Keep legacy formatting for ordinary instruct models. Reasoning-capable
    // templates (for example Qwen3) are rendered through Jinja with thinking
    // explicitly disabled because PocketAI intentionally hides private reasoning.
    if (!template_supports_thinking)
        return common_chat_format_single(templates.get(), messages, message, add_assistant, false);

    const auto vocab = llama_model_get_vocab(model);
    common_chat_templates_inputs inputs;
    inputs.use_jinja = true;
    inputs.enable_thinking = false;
    inputs.add_bos = llama_vocab_get_add_bos(vocab);
    inputs.add_eos = llama_vocab_get_add_eos(vocab);

    std::string formatted_past;
    if (!messages.empty()) {
        inputs.messages = messages;
        inputs.add_generation_prompt = false;
        formatted_past = common_chat_templates_apply(templates.get(), inputs).prompt;
    }

    std::ostringstream result;
    if (add_assistant && !formatted_past.empty() && formatted_past.back() == '\n')
        result << '\n';

    inputs.messages.push_back(message);
    inputs.add_generation_prompt = add_assistant;
    const auto formatted = common_chat_templates_apply(templates.get(), inputs).prompt;
    if (formatted.size() < formatted_past.size() ||
        formatted.compare(0, formatted_past.size(), formatted_past) != 0)
        throw std::runtime_error("Chat template did not preserve history prefix");

    result << formatted.substr(formatted_past.size());
    return result.str();
}

void add_message(common_chat_msg message) {
    messages.push_back(std::move(message));
    // Templates need recent role ordering; keep native bookkeeping bounded.
    if (messages.size() > 64) {
        const size_t first = messages.front().role == "system" ? 1 : 0;
        messages.erase(messages.begin() + first, messages.begin() + first + 2);
    }
}

void add_message(const std::string &role, const std::string &content) {
    common_chat_msg message;
    message.role = role;
    message.content = content;
    add_message(std::move(message));
}

llama_tokens tokenize_input(const std::string &text, bool parse_special) {
    auto tokens = common_tokenize(context, text, false, parse_special);
    const auto vocab = llama_model_get_vocab(model);
    const auto bos = llama_vocab_bos(vocab);
    if (!position && llama_vocab_get_add_bos(vocab) && bos >= 0 && (tokens.empty() || tokens.front() != bos))
        tokens.insert(tokens.begin(), bos);
    return tokens;
}

llama_tokens tokenize_from_start(const std::string &text, bool parse_special) {
    auto tokens = common_tokenize(context, text, false, parse_special);
    const auto vocab = llama_model_get_vocab(model);
    const auto bos = llama_vocab_bos(vocab);
    if (llama_vocab_get_add_bos(vocab) && bos >= 0 && (tokens.empty() || tokens.front() != bos))
        tokens.insert(tokens.begin(), bos);
    return tokens;
}

std::string render_reasoning_chat(
    const std::vector<common_chat_msg> &chat,
    bool add_generation_prompt
) {
    const auto vocab = llama_model_get_vocab(model);
    common_chat_templates_inputs inputs;
    inputs.messages = chat;
    inputs.use_jinja = true;
    inputs.enable_thinking = false;
    inputs.add_generation_prompt = add_generation_prompt;
    inputs.add_bos = llama_vocab_get_add_bos(vocab);
    inputs.add_eos = llama_vocab_get_add_eos(vocab);
    return common_chat_templates_apply(templates.get(), inputs).prompt;
}

std::string render_full_reasoning_chat(const std::vector<common_chat_msg> &chat) {
    return render_reasoning_chat(chat, true);
}

std::string token_id_window(
    const std::vector<llama_token> &cached,
    const llama_tokens &rendered,
    size_t pivot
) {
    const size_t start = pivot > 4 ? pivot - 4 : 0;
    const size_t limit = std::max(cached.size(), rendered.size());
    const size_t end = std::min(limit, pivot + 5);
    std::ostringstream out;
    for (size_t i = start; i < end; ++i) {
        if (i > start) out << " ";
        out << i << ":";
        if (i < cached.size()) out << cached[i]; else out << "-";
        out << "/";
        if (i < rendered.size()) out << rendered[i]; else out << "-";
    }
    return out.str().empty() ? "none" : out.str();
}

/**
 * Some reasoning templates emit an empty <think> block only in their generation
 * prompt, but omit it when the completed assistant message is rendered again.
 * That tiny rewrite invalidates the KV state of the whole assistant answer.
 *
 * Try representations that are semantically identical to PocketAI (no private
 * reasoning) and keep the one whose rendered history matches the live KV for the
 * longest exact token prefix. Qwen3 accepts a newline-only reasoning_content:
 * it is truthy to the template but strips to empty inside the think block.
 */
common_chat_msg aligned_assistant_message(const std::string &content) {
    common_chat_msg plain;
    plain.role = "assistant";
    plain.content = content;

    history_alignment_tokens = 0;
    history_alignment_kv_tokens = 0;
    history_alignment_plain_tokens = 0;
    history_alignment_empty_tokens = 0;
    history_divergence_index = -1;
    history_alignment_mode = "plain";
    history_divergence_window = "none";

    if (!template_supports_thinking || !context || kv_tokens.empty()) return plain;

    std::vector<common_chat_msg> variants;
    variants.push_back(plain);
    common_chat_msg empty_reasoning = plain;
    empty_reasoning.reasoning_content = "\n";
    variants.push_back(std::move(empty_reasoning));

    common_chat_msg best = variants.front();
    size_t best_prefix = 0;
    size_t best_total = 0;
    std::string best_mode = "plain";

    for (size_t index = 0; index < variants.size(); ++index) {
        try {
            auto candidate = messages;
            candidate.push_back(variants[index]);

            // Probe the representation as a *previous* assistant turn. Qwen3 renders
            // the last assistant differently from an assistant followed by a new
            // user message, which is exactly what caused the next-turn KV mismatch.
            common_chat_msg probe_user;
            probe_user.role = "user";
            probe_user.content = "__pocketai_alignment_probe__";
            candidate.push_back(std::move(probe_user));

            const auto rendered = render_reasoning_chat(candidate, false);
            const auto rendered_tokens = tokenize_from_start(rendered, true);
            const size_t prefix = pocketai::token_prefix_length(rendered_tokens, kv_tokens);
            if (index == 0) history_alignment_plain_tokens = static_cast<int>(prefix);
            else history_alignment_empty_tokens = static_cast<int>(prefix);
            if (prefix > best_prefix || (prefix == best_prefix && index == 0)) {
                best = variants[index];
                best_prefix = prefix;
                best_total = kv_tokens.size();
                best_mode = index == 0 ? "plain" : "empty-reasoning";
                history_divergence_index = prefix < kv_tokens.size() ? static_cast<int>(prefix) : -1;
                history_divergence_window = prefix < kv_tokens.size()
                    ? token_id_window(kv_tokens, rendered_tokens, prefix)
                    : "none";
            }
        } catch (...) {
            // Keep the ordinary assistant representation if alignment probing fails.
        }
    }

    history_alignment_tokens = static_cast<int>(best_prefix);
    history_alignment_kv_tokens = static_cast<int>(best_total);
    history_alignment_mode = best_mode;
    return best;
}

bool drop_oldest_history_turn(std::vector<common_chat_msg> &chat) {
    const size_t first = (!chat.empty() && chat.front().role == "system") ? 1 : 0;
    // Keep at least the current user message, which is always the final element.
    if (chat.size() <= first + 1) return false;

    size_t end = first + 1;
    if (end < chat.size() - 1 && chat[end].role == "assistant") ++end;
    chat.erase(chat.begin() + first, chat.begin() + end);
    return true;
}

int process_reasoning_user(const std::string &user, int maximum) {
    const int capacity = static_cast<int>(llama_n_ctx(context)) - HEADROOM;
    const int minimum = std::min(maximum, MIN_GENERATION_TOKENS);

    std::vector<common_chat_msg> candidate = messages;
    common_chat_msg current;
    current.role = "user";
    current.content = user;
    candidate.push_back(std::move(current));

    std::string formatted;
    llama_tokens tokens;
    int effective = 0;
    int dropped_turns = 0;
    prompt_render_us = 0;
    prompt_tokenize_us = 0;
    prompt_reused_tokens = 0;
    prompt_decoded_tokens = 0;

    for (;;) {
        const auto render_start = ggml_time_us();
        formatted = render_full_reasoning_chat(candidate);
        prompt_render_us += ggml_time_us() - render_start;

        const auto tokenize_start = ggml_time_us();
        tokens = tokenize_from_start(formatted, true);
        prompt_tokenize_us += ggml_time_us() - tokenize_start;
        if (tokens.empty()) return 1;

        effective = pocketai::generation_limit(
            capacity,
            0,
            static_cast<int>(tokens.size()),
            maximum,
            minimum
        );
        if (effective > 0) break;

        if (!drop_oldest_history_turn(candidate)) return 1;
        ++dropped_turns;
    }

    // Reuse only a token-identical prefix. Qwen3 templates may rewrite the tail
    // between turns, so byte/string assumptions are unsafe. Exact token comparison
    // lets us keep the stable KV prefix and recompute only the changed/new suffix.
    size_t reusable = pocketai::reusable_token_prefix(tokens, kv_tokens);
    reusable = trim_kv_to_prefix(reusable);

    common_sampler_reset(sampler);
    system_position = 0; // reasoning turns are managed as complete templated prompts
    cached_bytes.clear();
    assistant_text.clear();
    needs_end_of_turn = false;
    context_dirty = false;

    llama_tokens suffix(tokens.begin() + static_cast<std::ptrdiff_t>(reusable), tokens.end());
    const auto start = ggml_time_us();
    const int result = decode_prompt(suffix, true);
    prompt_us = ggml_time_us() - start;
    prompt_tokens = static_cast<int>(tokens.size());
    prompt_reused_tokens = static_cast<int>(reusable);
    prompt_decoded_tokens = static_cast<int>(suffix.size());
    if (result) return result;

    messages = std::move(candidate);
    history_resets += dropped_turns;
    budget.start(maximum, effective);
    last_generated_token = -1;
    repeated_token_streak = 0;
    generation_degenerate = false;
    generation_start = ggml_time_us();
    generation_end = 0;
    generation_eog = false;
    generating = true;
    return 0;
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
    if (!context_dirty && common_chat_templates_was_explicit(templates.get())) {
        if (template_supports_thinking) add_message(aligned_assistant_message(assistant_text));
        else add_message("assistant", assistant_text);
        needs_end_of_turn = !generation_eog;
    }
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
    const auto load_start = ggml_time_us();
    model_path = java_text(env, path);
    fallback.clear();
    const bool want_gpu = options.gpu_layers > 0 && gpu;
    if (options.gpu_layers > 0 && !gpu) {
        fallback = "Vulkan device unavailable; CPU selected";
        ++fallback_events;
    }
    try {
        if (load_selected_model(want_gpu)) {
            model_load_us = ggml_time_us() - load_start;
            return 0;
        }
    } catch (...) { log_event(ANDROID_LOG_WARN, "Model backend load failed"); }
    if (cancelled.load()) {
        model_load_us = ggml_time_us() - load_start;
        return 3;
    }
    if (want_gpu) {
        if (model) { llama_model_free(model); model = nullptr; }
        gpu_layers = 0;
        fallback = "GPU model allocation failed; CPU fallback";
        ++fallback_events;
        log_event(ANDROID_LOG_WARN, "Retrying model on CPU");
        try {
            if (load_selected_model(false)) {
                model_load_us = ggml_time_us() - load_start;
                return 0;
            }
        } catch (...) { }
    }
    model_load_us = ggml_time_us() - load_start;
    return 1;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(JNIEnv *, jobject) {
    if (!model) return 1;
    const auto prepare_start = ggml_time_us();
    free_context();

    int selected_context = 0;
    const int requested_context = desired_context_size();
    context = new_context_with_fallback(selected_context);

    if (!context && gpu_layers > 0 && !cancelled.load()) {
        llama_model_free(model); model = nullptr;
        gpu_layers = 0;
        fallback = "GPU context allocation failed; CPU fallback";
        ++fallback_events;
        log_event(ANDROID_LOG_WARN, "Retrying context on CPU");
        try {
            if (load_selected_model(false)) {
                const int cpu_requested_context = desired_context_size();
                context = new_context_with_fallback(selected_context);
                if (context && selected_context < cpu_requested_context) {
                    append_fallback("context reduced to " + std::to_string(selected_context) + " tokens");
                    ++fallback_events;
                }
            }
        } catch (...) { context = nullptr; }
    } else if (context && selected_context < requested_context) {
        append_fallback("context reduced to " + std::to_string(selected_context) + " tokens");
        ++fallback_events;
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
        try { template_supports_thinking = common_chat_templates_support_enable_thinking(templates.get()); }
        catch (...) { template_supports_thinking = false; }
        clear_conversation();
        history_resets = 0;
        budget.start(0);
        prompt_tokens = 0;
        prompt_us = 0;
        generation_start = generation_end = 0;
        context_prepare_us = ggml_time_us() - prepare_start;
        log_event(ANDROID_LOG_INFO, gpu_layers > 0 ? "Vulkan model ready" : "CPU model ready");
        return 0;
    } catch (...) { free_context(); return 1; }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processSystemPrompt(JNIEnv *env, jobject, jstring text) {
    if (!context) return 2;
    const auto start = ggml_time_us();
    try {
        system_prompt = java_text(env, text);
        const int result = install_system_prompt();
        system_prompt_us = ggml_time_us() - start;
        return result;
    } catch (...) {
        system_prompt_us = ggml_time_us() - start;
        context_dirty = true;
        return 2;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processUserPrompt(JNIEnv *env, jobject, jstring text, jint maximum) {
    if (!context || maximum < 1 || maximum > 32768) return 2;
    finish_generation();
    try {
        if (context_dirty && install_system_prompt()) return 2;

        const auto user = java_text(env, text);
        const bool chat_template = common_chat_templates_was_explicit(templates.get());

        // Reasoning-capable templates such as Qwen3 can rewrite their rendered tail
        // between turns. The reasoning path renders the complete structured chat, then
        // reuses only the exact token-identical KV prefix and decodes the changed suffix.
        if (chat_template && template_supports_thinking)
            return process_reasoning_user(user, static_cast<int>(maximum));

        if (needs_end_of_turn) {
            const int capacity = static_cast<int>(llama_n_ctx(context)) - HEADROOM;
            if (position + 1 > capacity) {
                const int resets_before = history_resets;
                if (!make_turn_room(1)) { context_dirty = true; return 2; }
                if (history_resets == resets_before) { context_dirty = true; return 2; }
                needs_end_of_turn = false;
            } else {
                const auto vocab = llama_model_get_vocab(model);
                auto eot = llama_vocab_eot(vocab);
                if (eot < 0) eot = llama_vocab_eos(vocab);
                if (eot < 0) { context_dirty = true; return 2; }
                const int result = decode_prompt({eot}, false);
                if (result) return result;
                needs_end_of_turn = false;
            }
        }

        prompt_render_us = 0;
        prompt_tokenize_us = 0;
        prompt_reused_tokens = 0;
        prompt_decoded_tokens = 0;

        const auto render_start = ggml_time_us();
        auto formatted = chat_template ? format_message("user", user, true) : user;
        prompt_render_us += ggml_time_us() - render_start;

        const auto tokenize_start = ggml_time_us();
        auto tokens = tokenize_input(formatted, chat_template);
        prompt_tokenize_us += ggml_time_us() - tokenize_start;
        if (tokens.empty()) return 1;

        const int capacity = static_cast<int>(llama_n_ctx(context)) - HEADROOM;
        const int minimum = std::min(static_cast<int>(maximum), MIN_GENERATION_TOKENS);
        int effective = pocketai::generation_limit(
            capacity,
            static_cast<int>(position),
            static_cast<int>(tokens.size()),
            static_cast<int>(maximum),
            minimum
        );

        if (!effective) {
            const int resets_before = history_resets;
            if (!make_turn_room(static_cast<int>(tokens.size()) + minimum)) return 1;

            if (history_resets != resets_before) {
                const auto rerender_start = ggml_time_us();
                formatted = chat_template ? format_message("user", user, true) : user;
                prompt_render_us += ggml_time_us() - rerender_start;
                const auto retokenize_start = ggml_time_us();
                tokens = tokenize_input(formatted, chat_template);
                prompt_tokenize_us += ggml_time_us() - retokenize_start;
                if (tokens.empty()) return 1;
            }

            effective = pocketai::generation_limit(
                capacity,
                system_position,
                static_cast<int>(tokens.size()),
                static_cast<int>(maximum),
                minimum
            );
            if (!effective || !make_turn_room(static_cast<int>(tokens.size()) + effective)) return 1;
        } else if (!make_turn_room(static_cast<int>(tokens.size()) + effective)) {
            return 1;
        }

        cached_bytes.clear();
        assistant_text.clear();
        common_sampler_reset(sampler);
        const auto start = ggml_time_us();
        const int result = decode_prompt(tokens, true);
        prompt_us = ggml_time_us() - start;
        prompt_tokens = static_cast<int>(tokens.size());
        prompt_reused_tokens = 0;
        prompt_decoded_tokens = static_cast<int>(tokens.size());
        if (result) return result;
        if (chat_template) add_message("user", user);
        budget.start(maximum, effective);
        generation_start = ggml_time_us();
        generation_end = 0;
        generation_eog = false;
        generating = true;
        return 0;
    } catch (...) {
        context_dirty = true;
        return 2;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_generateNextToken(JNIEnv *env, jobject) {
    if (!context || !generating || cancelled.load() || budget.exhausted()) { finish_generation(); return nullptr; }
    try {
        const int capacity = static_cast<int>(llama_n_ctx(context)) - HEADROOM;
        if (position >= capacity) {
            // The turn is pre-budgeted, so reaching this guard indicates an unexpected
            // accounting mismatch. Stop cleanly rather than deleting the active prompt.
            log_event(ANDROID_LOG_WARN, "Generation reached reserved context boundary");
            finish_generation();
            return nullptr;
        }
        apply_threads();
        const auto token = common_sampler_sample(sampler, context, -1);

        if (token == last_generated_token) ++repeated_token_streak;
        else {
            last_generated_token = token;
            repeated_token_streak = 1;
        }

        // Identical-token runs of this length are practically never intentional in
        // normal chat, but are a known symptom of corrupted GPU logits on some
        // Android Vulkan/Adreno stacks. Stop before filling the UI with garbage.
        if (repeated_token_streak >= 32) {
            generation_degenerate = true;
            context_dirty = true;
            log_event(ANDROID_LOG_ERROR, "Degenerate repeated-token output detected");
            finish_generation();
            throw_io(
                env,
                gpu_layers > 0
                    ? "Sortie GPU incoherente detectee; recharge le modele en profil Equilibre ou CPU"
                    : "Boucle de tokens detectee; reinitialise la conversation ou change de modele"
            );
            return nullptr;
        }

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
        kv_tokens.push_back(token);
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
    out << "Generated: " << budget.produced << " / " << budget.limit
        << "; requested: " << budget.requested
        << "; context-limited: " << (budget.context_limited() ? "yes" : "no")
        << "; history resets: " << history_resets << '\n';
    out << "Reasoning template: " << (template_supports_thinking ? "detected; thinking disabled" : "not detected") << '\n';
    out << "Degenerate output guard: " << (generation_degenerate ? "triggered" : "clear")
        << "; repeated-token streak: " << repeated_token_streak << '\n';

    const auto visible_text = pocketai::strip_thinking(assistant_text);
    const auto hidden_thinking_text = pocketai::thinking_content(assistant_text);
    int visible_tokens_estimate = 0;
    int hidden_thinking_tokens_estimate = 0;
    if (context) {
        try {
            if (!visible_text.empty())
                visible_tokens_estimate = static_cast<int>(common_tokenize(context, visible_text, false, false).size());
            if (!hidden_thinking_text.empty())
                hidden_thinking_tokens_estimate = static_cast<int>(common_tokenize(context, hidden_thinking_text, false, false).size());
        } catch (...) {
            visible_tokens_estimate = 0;
            hidden_thinking_tokens_estimate = 0;
        }
    }

    const int64_t duration = generation_start ? (generation_end ? generation_end : ggml_time_us()) - generation_start : 0;
    out << "Load timing: model " << model_load_us / 1000.0
        << " ms; context " << context_prepare_us / 1000.0
        << " ms; system-prompt " << system_prompt_us / 1000.0
        << " ms; fallback-events " << fallback_events << '\n';
    out << "Prompt timing: total " << prompt_tokens
        << " tokens; reused " << prompt_reused_tokens
        << "; decoded " << prompt_decoded_tokens
        << "; render " << prompt_render_us / 1000.0
        << " ms; tokenize " << prompt_tokenize_us / 1000.0
        << " ms; decode " << prompt_us / 1000.0
        << " ms; " << (prompt_us > 0 ? prompt_decoded_tokens * 1e6 / prompt_us : 0.0)
        << " decoded tokens/s\n";
    out << "History alignment: " << history_alignment_tokens << " / "
        << history_alignment_kv_tokens << " cached tokens; mode " << history_alignment_mode << '\n';
    out << "History alignment variants: plain " << history_alignment_plain_tokens
        << "; empty-reasoning " << history_alignment_empty_tokens << '\n';
    out << "KV divergence: index " << history_divergence_index
        << "; token-ids cached/rendered " << history_divergence_window << '\n';
    out << "Output metrics: raw-tokens " << budget.produced
        << "; visible-tokens-est " << visible_tokens_estimate
        << "; hidden-thinking-tokens-est " << hidden_thinking_tokens_estimate
        << "; raw-chars " << pocketai::utf8_codepoints(assistant_text)
        << "; visible-chars " << pocketai::utf8_codepoints(visible_text) << '\n';
    out << "Generation: " << (duration > 0 ? budget.produced * 1e6 / duration : 0.0)
        << " tokens/s; generation-time " << duration / 1000.0 << " ms\n";
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
