#pragma once
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>
#include <vector>

namespace pocketai {
struct LogitComparison {
    bool passed = false;
    double js = 0;
    double relative_rmse = 0;
    bool top_match = false;
};

inline bool finite_logits(const float *values, size_t count) {
    if (!values || count == 0) return false;
    for (size_t i = 0; i < count; ++i) if (!std::isfinite(values[i])) return false;
    return true;
}

// Softmax divergence and centered RMS ignore a harmless constant logit offset.
// A small top-token margin permits numerical ties, while gross corruption fails.
inline LogitComparison compare_logits(const std::vector<float> &reference, const float *actual, size_t count) {
    LogitComparison result;
    if (reference.size() != count || !finite_logits(reference.data(), count) || !finite_logits(actual, count)) return result;
    size_t top_a = 0, top_b = 0;
    double mean_delta = 0, mean_ref = 0, sum_a = 0, sum_b = 0;
    for (size_t i = 0; i < count; ++i) {
        if (reference[i] > reference[top_a]) top_a = i;
        if (actual[i] > actual[top_b]) top_b = i;
        mean_delta += double(actual[i]) - reference[i];
        mean_ref += reference[i];
    }
    mean_delta /= count;
    mean_ref /= count;
    for (size_t i = 0; i < count; ++i) {
        sum_a += std::exp(double(reference[i]) - reference[top_a]);
        sum_b += std::exp(double(actual[i]) - actual[top_b]);
    }
    double error = 0, variance = 0;
    for (size_t i = 0; i < count; ++i) {
        const double a = std::exp(double(reference[i]) - reference[top_a]) / sum_a;
        const double b = std::exp(double(actual[i]) - actual[top_b]) / sum_b;
        const double mixture = (a + b) * 0.5;
        if (a > 0) result.js += 0.5 * a * std::log(a / mixture);
        if (b > 0) result.js += 0.5 * b * std::log(b / mixture);
        const double delta = double(actual[i]) - reference[i] - mean_delta;
        error += delta * delta;
        const double centered = reference[i] - mean_ref;
        variance += centered * centered;
    }
    result.relative_rmse = std::sqrt(error / std::max(variance, double(count) * 1e-6));
    result.top_match = top_a == top_b;
    const bool close_top = reference[top_a] - reference[top_b] <= 0.35 && actual[top_b] - actual[top_a] <= 0.35;
    result.passed = std::isfinite(result.js) && std::isfinite(result.relative_rmse) &&
        result.js <= 0.01 && result.relative_rmse <= 0.03 && close_top;
    return result;
}
}
