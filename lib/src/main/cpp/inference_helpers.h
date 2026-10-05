#pragma once
#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

namespace pocketai {
struct GenerationBudget {
    int limit = 0;
    int produced = 0;
    void start(int maximum) { limit = maximum; produced = 0; }
    bool exhausted() const { return produced >= limit; }
    void consume() { ++produced; }
};

inline int discard_count(int position, int system, int required, int capacity) {
    if (position + required <= capacity) return 0;
    const int history = position - system;
    if (history < 1 || system + required > capacity) return -1;
    return std::min(history, std::max(position + required - capacity, std::max(1, history / 2)));
}

inline std::vector<int> gpu_layer_candidates(int requested) {
    std::vector<int> result;
    const auto add = [&](int value) {
        value = std::max(0, value);
        if (std::find(result.begin(), result.end(), value) == result.end()) result.push_back(value);
    };
    add(requested);
    if (requested > 1) add(std::max(1, requested * 3 / 4));
    if (requested > 1) add(std::max(1, requested / 2));
    if (requested > 1) add(std::max(1, requested / 4));
    add(0);
    return result;
}

inline std::vector<int> context_backoff_candidates(int requested) {
    std::vector<int> result;
    for (int value : {32768, 16384, 8192, 4096, 2048, 1024, 512}) {
        if (value < requested) result.push_back(value);
    }
    return result;
}

inline std::vector<int> thread_candidates(int maximum) {
    if (maximum <= 1) return {1};
    std::vector<int> result;
    for (int value : {2, 4, 6, 8, maximum}) {
        if (value <= maximum && std::find(result.begin(), result.end(), value) == result.end()) result.push_back(value);
    }
    return result;
}

// Complete UTF-8 prefixes only; an unfinished multibyte token waits for the next token.
inline bool complete_utf8(const std::string &text) {
    for (size_t i = 0; i < text.size();) {
        const auto first = static_cast<unsigned char>(text[i]);
        size_t count = first < 0x80 ? 1 : first >= 0xC2 && first <= 0xDF ? 2 :
                       first >= 0xE0 && first <= 0xEF ? 3 : first >= 0xF0 && first <= 0xF4 ? 4 : 0;
        if (!count || i + count > text.size()) return false;
        for (size_t j = 1; j < count; ++j)
            if ((static_cast<unsigned char>(text[i + j]) & 0xC0) != 0x80) return false;
        const auto second = count > 1 ? static_cast<unsigned char>(text[i + 1]) : 0;
        if ((first == 0xE0 && second < 0xA0) || (first == 0xED && second >= 0xA0) ||
            (first == 0xF0 && second < 0x90) || (first == 0xF4 && second >= 0x90)) return false;
        i += count;
    }
    return true;
}

inline std::vector<uint16_t> utf8_to_utf16(const std::string &text) {
    std::vector<uint16_t> result;
    for (size_t i = 0; i < text.size();) {
        const auto first = static_cast<unsigned char>(text[i++]);
        uint32_t code = first;
        const int extra = first < 0x80 ? 0 : first < 0xE0 ? 1 : first < 0xF0 ? 2 : 3;
        if (extra) code &= (1u << (6 - extra)) - 1;
        for (int j = 0; j < extra && i < text.size(); ++j) code = (code << 6) | (text[i++] & 0x3F);
        if (code < 0x10000) result.push_back(static_cast<uint16_t>(code));
        else { code -= 0x10000; result.push_back(0xD800 | (code >> 10)); result.push_back(0xDC00 | (code & 0x3FF)); }
    }
    return result;
}
}
