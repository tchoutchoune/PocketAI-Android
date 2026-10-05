#include "../../main/cpp/runtime_policy.h"
#include "../../main/cpp/native_batch.h"
#include <cassert>
#include <limits>
#include <regex>
#include <stdexcept>
#include <iostream>

int main() {
    using namespace pocketai;
    // Full GPU fails, explicit CPU output succeeds: preserve all transformer layers.
    std::vector<BackendChoice> attempts;
    assert(try_fallbacks(37, false, [] { return false; }, [&](BackendChoice c) {
        attempts.push_back(c);
        return c.output_cpu;
    }));
    assert(attempts.size() == 1 && attempts[0].layers == 37 && attempts[0].output_cpu);
    attempts.clear();
    // Several model/context/replay failures, CPU succeeds last.
    assert(try_fallbacks(37, false, [] { return false; }, [&](BackendChoice c) {
        attempts.push_back(c);
        return c.layers == 0;
    }));
    assert(attempts.size() == 5);
    assert(attempts[1].layers == 27 && attempts[2].layers == 18 && attempts[3].layers == 9);
    assert(attempts.back().layers == 0 && !attempts.back().output_cpu);
    // Recovery never repeats the failed setting and cancellation stops before reload.
    bool cancelled = false;
    attempts.clear();
    assert(!try_fallbacks(37, true, [&] { return cancelled; }, [&](BackendChoice c) {
        attempts.push_back(c); cancelled = true; return false;
    }));
    assert(attempts.size() == 1 && attempts[0].layers == 27);
    assert(!try_fallbacks(0, false, [] { return false; }, [](BackendChoice) { assert(false); return true; }));
    assert(!try_fallbacks(1, false, [] { return true; }, [](BackendChoice) { assert(false); return true; }));
    assert(!try_fallbacks(37, false, [] { return false; }, [](BackendChoice) { return false; }));

    const float normal[] = {-INFINITY, 0.25f, -20.0f};
    const float nan[] = {0, std::numeric_limits<float>::quiet_NaN()};
    const float positive_inf[] = {0, INFINITY};
    const float masked[] = {-INFINITY, -INFINITY};
    assert(valid_logits(normal, 3));
    assert(!valid_logits(nan, 2) && !valid_logits(positive_inf, 2));
    assert(!valid_logits(masked, 2) && !valid_logits(nullptr, 10));
    assert(!valid_logits(normal, 0));

    DecodeDiagnostics diagnostics;
    assert(!diagnostics.has_code);
    diagnostics.record(-3, "prompt.user", 37);
    diagnostics.record(0, "recovery.replay", 27);
    assert(diagnostics.code == 0 && diagnostics.phase == "recovery.replay");
    assert(diagnostics.failures.front().find("decode=-3") != std::string::npos);
    diagnostics.record(-2, "generation", 27);
    assert(diagnostics.code == -2 && diagnostics.phase == "generation");
    diagnostics.failure("benchmark", 27, "invalid logits");
    assert(diagnostics.code == -2); // no fabricated llama_decode result
    for (int i = 0; i < 30; ++i) diagnostics.record(-1, "probe.prefill", 9);
    assert(diagnostics.failures.size() == 12);

    // Batch boundary logits must select the final token, not the initial one.
    NativeBatch batch(256);
    std::vector<llama_token> tokens(256, 42);
    auto input = batch.make(tokens.data(), 256, 120, true);
    assert(input.n_tokens == 256 && input.pos[255] == 375);
    for (int i = 0; i < 256; ++i) {
        assert(input.token[i] == 42 && input.n_seq_id[i] == 1 && input.seq_id[i][0] == 0);
        assert(input.logits[i] == (i == 255));
    }
    input = batch.make(tokens.data(), 10, 376, false);
    for (int i = 0; i < 10; ++i) assert(input.logits[i] == 0);
    bool rejected = false;
    try { batch.make(tokens.data(), 257, 0, true); } catch (const std::invalid_argument &) { rejected = true; }
    assert(rejected);
    std::cout << "Fallback injection, cancellation, logits validation, diagnostics and batch boundaries passed.\n";
}
