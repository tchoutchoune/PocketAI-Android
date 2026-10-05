// Compile the actual JNI engine against a deterministic, non-GPU fake backend.
// This tests recovery/state ownership, not Vulkan kernels or model quality.
#include "../../main/cpp/ai_chat.cpp"
#include <cassert>
#include <iostream>

struct llama_model { int layers; bool cpu_output; };
struct llama_context {
    llama_model *model;
    uint32_t capacity, batch_size;
    std::vector<llama_token> kv;
    float values[3] = {0.1f, 0.2f, 0.3f};
};
struct llama_vocab {};
struct common_sampler { std::vector<llama_token> accepted; };
struct common_chat_templates {};
static llama_vocab fake_vocab;
static int contexts_alive = 0, models_alive = 0, synchronizations = 0;
static bool fail_gpu = false, fail_output = false, fail_all = false, fail_replay = false;
static bool cancel_on_failure = false, nan_logits = false;
static bool fail_prefill = false;
static std::vector<std::pair<int, bool>> loads;

extern "C" {
int __android_log_write(int, const char *, const char *) { return 0; }
llama_model_params llama_model_default_params() { return {}; }
llama_context_params llama_context_default_params() { return {}; }
ggml_backend_dev_t ggml_backend_dev_by_type(enum ggml_backend_dev_type) { return reinterpret_cast<ggml_backend_dev_t>(1); }
ggml_backend_buffer_type_t ggml_backend_dev_buffer_type(ggml_backend_dev_t) { return reinterpret_cast<ggml_backend_buffer_type_t>(1); }
llama_model *llama_model_load_from_file(const char *, llama_model_params params) {
    const bool cpu = params.tensor_buft_overrides != nullptr;
    if (cpu) {
        assert(std::string(params.tensor_buft_overrides[0].pattern) == "^(output|output_norm|token_embd)\\.(weight|bias)$");
        assert(params.tensor_buft_overrides[1].pattern == nullptr);
    }
    loads.push_back({params.n_gpu_layers, cpu});
    ++models_alive;
    return new llama_model{params.n_gpu_layers, cpu};
}
void llama_model_free(llama_model *value) { --models_alive; delete value; }
int32_t llama_model_n_layer(const llama_model *) { return 36; }
int32_t llama_model_n_ctx_train(const llama_model *) { return 4096; }
const llama_vocab *llama_model_get_vocab(const llama_model *) { return &fake_vocab; }
int32_t llama_vocab_n_tokens(const llama_vocab *) { return 3; }
llama_token llama_vocab_bos(const llama_vocab *) { return 0; }
llama_token llama_vocab_eos(const llama_vocab *) { return 1; }
llama_context *llama_init_from_model(llama_model *m, llama_context_params params) {
    if (m->cpu_output) assert(!params.op_offload);
    ++contexts_alive;
    return new llama_context{m, params.n_ctx, params.n_batch, {}};
}
void llama_free(llama_context *c) { --contexts_alive; delete c; }
uint32_t llama_n_ctx(const llama_context *c) { return c->capacity; }
uint32_t llama_n_batch(const llama_context *c) { return c->batch_size; }
llama_memory_t llama_get_memory(const llama_context *c) { return reinterpret_cast<llama_memory_t>(const_cast<llama_context *>(c)); }
void llama_memory_clear(llama_memory_t memory, bool) { reinterpret_cast<llama_context *>(memory)->kv.clear(); }
void llama_set_n_threads(llama_context *, int32_t, int32_t) {}
void llama_synchronize(llama_context *) { ++synchronizations; }
float *llama_get_logits_ith(llama_context *c, int32_t) {
    llama_synchronize(c);
    c->values[0] = nan_logits ? NAN : 0.1f;
    return c->values;
}
int32_t llama_decode(llama_context *c, llama_batch input) {
    assert(input.n_tokens > 0 && uint32_t(input.n_tokens) <= c->batch_size);
    const bool magic = input.token[0] == 99;
    const bool reject = (magic && (fail_all || (fail_gpu && c->model->layers > 0) ||
                          (fail_output && c->model->layers > 0 && !c->model->cpu_output))) ||
                        (fail_replay && input.token[0] == 7 && c->model->layers == 37 && c->model->cpu_output) ||
                        (fail_prefill && input.n_tokens > 1 && c->model->layers > 0 && !c->model->cpu_output);
    if (reject) {
        // Fatal native decodes may leave a partially populated KV cache.
        c->kv.push_back(999);
        if (cancel_on_failure) cancelled.store(true);
        return -3;
    }
    for (int i = 0; i < input.n_tokens; ++i) {
        assert(input.n_seq_id[i] == 1 && input.seq_id[i][0] == 0);
        assert(input.pos[i] == int(c->kv.size()));
        c->kv.push_back(input.token[i]);
    }
    return 0;
}
}
void common_chat_templates_free(common_chat_templates *value) { delete value; }
common_chat_templates_ptr common_chat_templates_init(const llama_model *, const std::string &, const std::string &, const std::string &) {
    return common_chat_templates_ptr(new common_chat_templates);
}
common_sampler *common_sampler_init(const llama_model *, common_params_sampling &) { return new common_sampler; }
void common_sampler_free(common_sampler *s) { delete s; }
void common_sampler_reset(common_sampler *s) { s->accepted.clear(); }
void common_sampler_accept(common_sampler *s, llama_token token, bool) { s->accepted.push_back(token); }

static void reset() {
    free_model();
    assert(contexts_alive == 0 && models_alive == 0);
    cancelled.store(false);
    fail_gpu = fail_output = fail_all = fail_replay = cancel_on_failure = nan_logits = fail_prefill = false;
    loads.clear(); decode_diagnostics = {}; runtime_recoveries = 0;
    gpu_probe_failures = 0; fallback.clear();
    options = {4, 2048, 256, 37, false, 0.0f};
    gpu = reinterpret_cast<ggml_backend_dev_t>(1);
    model_path = "fake";
    assert(load_selected_model_layers(37));
}
static void prepare() {
    assert(Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(nullptr, nullptr) == 0);
    assert(context && context->kv.empty() && resident_tokens.empty());
}
static void prefix() {
    assert(decode_main({7, 8, 9}, true, "prompt.user") == 0);
    position = 3;
    system_position = 1;
    messages.push_back(common_chat_msg{});
    turns.push_back({1, 3});
    current_turn_start = 1;
    generating = true;
    assistant_text = "already displayed";
    sampled_tokens = {8, 9};
    common_sampler_accept(sampler, 8, true);
    common_sampler_accept(sampler, 9, true);
    budget.start(20); budget.consume();
}
int main() {
    reset();
    free_model();
    options.prefer_cpu_output = true;
    assert(load_selected_model_layers(options.gpu_layers, options.prefer_cpu_output));
    assert(output_on_cpu && loads.back() == std::make_pair(37, true));
    free_model();

    reset();
    fail_prefill = true;
    prepare();
    assert(output_on_cpu && gpu_layers == 37 && gpu_probe_failures == 1);
    assert(synchronizations > 0); // accepting decode=0 alone must not suffice

    reset(); prepare(); prefix();
    fail_output = true;
    assert(decode_main({99}, true, "generation") == 0);
    assert(output_on_cpu && gpu_layers == 37 && runtime_recoveries == 1);
    assert((context->kv == std::vector<llama_token>{7, 8, 9, 99}));
    assert(resident_tokens == context->kv);
    assert(position == 3 && system_position == 1 && current_turn_start == 1);
    assert(messages.size() == 1 && turns.size() == 1 && turns[0].end == 3);
    assert(generating && assistant_text == "already displayed" && budget.produced == 1);
    assert(sampler->accepted == sampled_tokens);
    assert(decode_diagnostics.code == 0 && !decode_diagnostics.failures.empty());

    reset(); prepare(); prefix();
    fail_output = fail_replay = true;
    assert(decode_main({99}, true, "prompt.user") == 0);
    assert(gpu_layers == 27 && output_on_cpu); // failed replay does not accept this backend
    assert((context->kv == std::vector<llama_token>{7, 8, 9, 99}));

    reset(); prepare(); prefix(); fail_gpu = true;
    assert(decode_main({99}, true, "generation") == 0);
    assert(gpu_layers == 0 && runtime_recoveries > 0);
    assert((context->kv == std::vector<llama_token>{7, 8, 9, 99}));
    assert(loads.size() <= 9); // finite sequence of strictly safer settings

    reset(); prepare(); prefix(); fail_output = cancel_on_failure = true;
    assert(decode_main({99}, true, "generation") == -3);
    assert(loads.size() == 1 && cancelled.load()); // no reload after cancellation

    reset(); prepare(); prefix(); fail_all = true;
    assert(decode_main({99}, true, "generation") == -3);
    assert(gpu_layers == 0 && loads.size() <= 9);
    assert(decode_diagnostics.code == -3);

    reset(); nan_logits = true;
    assert(Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(nullptr, nullptr) != 0);
    assert(context == nullptr); // even CPU fallback must validate its actual outputs
    free_model();
    assert(contexts_alive == 0 && models_alive == 0);
    std::cout << "Actual engine: prefill, output override, replay, mid-generation fallback, cancellation, invalid logits and ownership passed.\n";
}
