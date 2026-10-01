#pragma once
#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

namespace pocketai {
struct GenerationBudget {
    int requested = 0;
    int limit = 0;
    int produced = 0;

    void start(int maximum, int effective = -1) {
        requested = std::max(0, maximum);
        limit = effective < 0 ? requested : std::min(requested, std::max(0, effective));
        produced = 0;
    }

    bool exhausted() const { return produced >= limit; }
    bool context_limited() const { return limit < requested; }
    void consume() { ++produced; }
};

/**
 * Compute a safe generation budget from the real tokenized prompt.
 * capacity is the usable context after native safety headroom.
 */
inline int generation_limit(int capacity, int system, int prompt_tokens, int requested, int minimum = 1) {
    if (capacity <= 0 || system < 0 || prompt_tokens < 1 || requested < 1 || minimum < 1) return 0;
    const int available = capacity - system - prompt_tokens;
    if (available < minimum) return 0;
    return std::min(requested, available);
}

/**
 * Return an exact token-identical prefix safe for KV reuse. If the entire new
 * prompt is already cached, leave its final token to be decoded again so logits
 * correspond to the current prompt end.
 */
inline size_t token_prefix_length(
    const std::vector<int32_t> &left,
    const std::vector<int32_t> &right
) {
    const size_t limit = std::min(left.size(), right.size());
    size_t prefix = 0;
    while (prefix < limit && left[prefix] == right[prefix]) ++prefix;
    return prefix;
}

inline size_t reusable_token_prefix(
    const std::vector<int32_t> &prompt,
    const std::vector<int32_t> &cached
) {
    size_t prefix = token_prefix_length(prompt, cached);
    if (!prompt.empty() && prefix >= prompt.size()) --prefix;
    return prefix;
}

/** Remove <think>...</think> sections without exposing their contents. */
inline std::string strip_thinking(const std::string &text) {
    std::string result;
    result.reserve(text.size());
    size_t cursor = 0;
    int depth = 0;
    while (cursor < text.size()) {
        const auto open = text.find("<think>", cursor);
        const auto close = text.find("</think>", cursor);
        if (depth == 0) {
            if (open == std::string::npos) {
                result.append(text, cursor, std::string::npos);
                break;
            }
            result.append(text, cursor, open - cursor);
            cursor = open + 7;
            depth = 1;
        } else {
            if (close == std::string::npos) break;
            cursor = close + 8;
            depth = 0;
        }
    }
    return result;
}

inline std::string thinking_content(const std::string &text) {
    std::string result;
    size_t cursor = 0;
    while (cursor < text.size()) {
        const auto open = text.find("<think>", cursor);
        if (open == std::string::npos) break;
        const auto content_start = open + 7;
        const auto close = text.find("</think>", content_start);
        if (close == std::string::npos) {
            result.append(text, content_start, std::string::npos);
            break;
        }
        if (!result.empty()) result.push_back('\n');
        result.append(text, content_start, close - content_start);
        cursor = close + 8;
    }
    return result;
}

inline size_t utf8_codepoints(const std::string &text) {
    size_t count = 0;
    for (size_t i = 0; i < text.size();) {
        const auto first = static_cast<unsigned char>(text[i]);
        const size_t width = first < 0x80 ? 1 : first < 0xE0 ? 2 : first < 0xF0 ? 3 : 4;
        i += std::min(width, text.size() - i);
        ++count;
    }
    return count;
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
