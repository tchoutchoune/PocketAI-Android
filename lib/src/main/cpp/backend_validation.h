#pragma once
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>
#include <vector>

namespace pocketai {
struct ActivationStats {
    size_t nan = 0;
    size_t infinity = 0;
    size_t positive_infinity = 0;
    double max_abs = 0;
    size_t nonfinite() const { return nan + infinity; }
};

inline ActivationStats activation_stats(const float *values, size_t count) {
    ActivationStats result;
    for (size_t i = 0; i < count; ++i) {
        if (std::isnan(values[i])) ++result.nan;
        else if (!std::isfinite(values[i])) {
            ++result.infinity;
            if (values[i] > 0) ++result.positive_infinity;
        }
        else result.max_abs = std::max(result.max_abs, std::abs(double(values[i])));
    }
    return result;
}

enum LogitFailure {
    INVALID_LOGITS = 1, NONFINITE_METRICS = 2, JS_DIVERGENCE = 4,
    RELATIVE_RMSE = 8, TOP_TOKEN_MARGIN = 16,
};
struct LogitComparison {
    bool passed = false;
    double js = 0;
    double relative_rmse = 0;
    // Diagnostic only: none of these fields changes the acceptance thresholds.
    double mean_offset = 0, reference_std = 0, actual_std = 0;
    double max_centered_delta = 0, probability_log_rmse = 0, tail_error_share = 0;
    bool top_match = false;
    int failures = INVALID_LOGITS;
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
    result.mean_offset = mean_delta;
    for (size_t i = 0; i < count; ++i) {
        sum_a += std::exp(double(reference[i]) - reference[top_a]);
        sum_b += std::exp(double(actual[i]) - actual[top_b]);
    }
    const double log_sum_a = std::log(sum_a), log_sum_b = std::log(sum_b);
    double error = 0, variance = 0, actual_variance = 0, probability_error = 0, tail_error = 0;
    for (size_t i = 0; i < count; ++i) {
        const double a = std::exp(double(reference[i]) - reference[top_a]) / sum_a;
        const double b = std::exp(double(actual[i]) - actual[top_b]) / sum_b;
        const double mixture = (a + b) * 0.5;
        if (a > 0) result.js += 0.5 * a * std::log(a / mixture);
        if (b > 0) result.js += 0.5 * b * std::log(b / mixture);
        const double delta = double(actual[i]) - reference[i] - mean_delta;
        error += delta * delta;
        result.max_centered_delta = std::max(result.max_centered_delta, std::abs(delta));
        if (std::max(a, b) < 1e-8) tail_error += delta * delta;
        // Log probabilities are also invariant to a constant logit offset.
        const double probability_delta = (double(actual[i]) - actual[top_b] - log_sum_b) -
            (double(reference[i]) - reference[top_a] - log_sum_a);
        probability_error += mixture * probability_delta * probability_delta;
        const double centered = reference[i] - mean_ref;
        variance += centered * centered;
        const double actual_centered = double(actual[i]) - mean_ref - mean_delta;
        actual_variance += actual_centered * actual_centered;
    }
    result.relative_rmse = std::sqrt(error / std::max(variance, double(count) * 1e-6));
    result.reference_std = std::sqrt(variance / count);
    result.actual_std = std::sqrt(actual_variance / count);
    result.probability_log_rmse = std::sqrt(probability_error);
    result.tail_error_share = error > 0 ? tail_error / error : 0;
    result.top_match = top_a == top_b;
    const bool close_top = reference[top_a] - reference[top_b] <= 0.35 && actual[top_b] - actual[top_a] <= 0.35;
    result.failures = 0;
    if (!std::isfinite(result.js) || !std::isfinite(result.relative_rmse)) result.failures |= NONFINITE_METRICS;
    if (result.js > 0.01) result.failures |= JS_DIVERGENCE;
    if (result.relative_rmse > 0.03) result.failures |= RELATIVE_RMSE;
    if (!close_top) result.failures |= TOP_TOKEN_MARGIN;
    result.passed = result.failures == 0;
    return result;
}
}
