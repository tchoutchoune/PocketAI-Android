#pragma once
#include "inference_helpers.h"
#include <cmath>
#include <cstddef>
#include <deque>
#include <string>

namespace pocketai {
struct BackendChoice {
    int layers;
    bool output_cpu;
};

// n_gpu_layers includes the output in the pinned llama.cpp. Never infer its
// placement from a reduced layer count: use explicit tensor overrides instead.
inline std::vector<BackendChoice> fallback_choices(int layers, bool output_cpu) {
    std::vector<BackendChoice> result;
    if (layers <= 0) return result;
    if (!output_cpu) result.push_back({layers, true});
    for (int n : gpu_layer_candidates(layers)) {
        if (n < layers) result.push_back({n, n > 0});
    }
    return result;
}

template<class Cancel, class Attempt>
bool try_fallbacks(int layers, bool output_cpu, Cancel cancelled, Attempt attempt) {
    for (const auto choice : fallback_choices(layers, output_cpu)) {
        if (cancelled()) return false;
        if (attempt(choice)) return !cancelled();
    }
    return false;
}

inline bool valid_logits(const float *values, size_t count) {
    if (!values || count == 0) return false;
    // Negative infinity may legitimately mask a vocabulary entry; NaN/+inf and
    // an entirely masked distribution are not usable by the sampler.
    bool finite = false;
    for (size_t i = 0; i < count; ++i) {
        if (std::isnan(values[i]) || values[i] == INFINITY) return false;
        finite = finite || std::isfinite(values[i]);
    }
    return finite;
}

struct DecodeDiagnostics {
    bool has_code = false;
    int code = 0;
    std::string phase = "none";
    std::deque<std::string> failures;
    void failure(const std::string &phase, int layers, const std::string &reason) {
        if (failures.size() == 12) failures.pop_front();
        failures.push_back(phase + " layers=" + std::to_string(layers) + " " + reason);
    }
    void record(int raw, const char *where, int layers) {
        has_code = true;
        code = raw;
        phase = where;
        if (raw != 0) failure(where, layers, "decode=" + std::to_string(raw));
    }
};
}
