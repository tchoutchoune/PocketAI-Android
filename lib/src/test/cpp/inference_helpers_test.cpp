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
    budget.start(10, 3);
    assert(budget.requested == 10);
    assert(budget.limit == 3);
    assert(budget.context_limited());
    assert(!budget.exhausted() && budget.produced == 0);
    budget.consume();
    budget.consume();
    budget.consume();
    assert(budget.exhausted());

    assert(pocketai::generation_limit(4032, 128, 512, 2048, 64) == 2048);
    assert(pocketai::generation_limit(1984, 128, 512, 2048, 64) == 1344);
    assert(pocketai::generation_limit(960, 128, 800, 512, 64) == 0);
    assert(pocketai::generation_limit(960, 128, 800, 16, 16) == 16);

    assert(pocketai::discard_count(2000, 100, 128, 2040) == 950);
    assert(pocketai::discard_count(1050, 100, 128, 2040) == 0);
    assert(pocketai::discard_count(100, 100, 1941, 2040) == -1);
    assert(pocketai::discard_count(2040, 2039, 1, 2040) == 1);
    assert(pocketai::discard_count(2000, 100, 1930, 2040) == 1890);

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
