#include "../../main/cpp/inference_helpers.h"
#include <cassert>

int main() {
    pocketai::GenerationBudget budget;
    budget.start(3);
    int position = 2040;
    for (int i = 0; i < 3; ++i) {
        assert(!budget.exhausted());
        if (i == 1) position -= 1000;
        ++position;
        budget.consume();
    }
    assert(budget.exhausted());
    assert(budget.produced == 3);
    budget.start(1);
    assert(!budget.exhausted() && budget.produced == 0);

    assert(pocketai::discard_count(2000, 100, 128, 2040) == 950);
    assert(pocketai::discard_count(1050, 100, 128, 2040) == 0);
    assert(pocketai::discard_count(100, 100, 1941, 2040) == -1);
    assert(pocketai::discard_count(2040, 2039, 1, 2040) == 1);
    assert(pocketai::discard_count(2000, 100, 1930, 2040) == 1890);

    assert((pocketai::gpu_layer_candidates(32) == std::vector<int>{32, 24, 16, 8, 0}));
    assert((pocketai::gpu_layer_candidates(1) == std::vector<int>{1, 0}));
    assert((pocketai::context_backoff_candidates(32768) == std::vector<int>{16384, 8192, 4096, 2048, 1024, 512}));
    assert((pocketai::context_backoff_candidates(4096) == std::vector<int>{2048, 1024, 512}));
    assert((pocketai::thread_candidates(8) == std::vector<int>{2, 4, 6, 8}));
    assert((pocketai::thread_candidates(5) == std::vector<int>{2, 4, 5}));
    assert((pocketai::thread_candidates(1) == std::vector<int>{1}));

    const std::string emoji = "\xF0\x9F\x98\x80";
    assert(pocketai::complete_utf8(emoji));
    assert(!pocketai::complete_utf8(emoji.substr(0, 3)));
    assert(!pocketai::complete_utf8("\xED\xA0\x80"));
    assert(!pocketai::complete_utf8("\xC0\x80"));
    assert(!pocketai::complete_utf8("\xF4\x90\x80\x80"));
    const auto utf16 = pocketai::utf8_to_utf16(emoji);
    assert(utf16.size() == 2 && utf16[0] == 0xD83D && utf16[1] == 0xDE00);
    assert(pocketai::utf8_to_utf16("\xC3\xA9")[0] == 0xE9);
    const std::string with_zero("a\0b", 3);
    assert(pocketai::complete_utf8(with_zero));
    assert(pocketai::utf8_to_utf16(with_zero).size() == 3);
}
