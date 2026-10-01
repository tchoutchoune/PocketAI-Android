#include "../../main/cpp/inference_helpers.h"
#include <cassert>

int main() {
    pocketai::GenerationBudget budget;
    budget.start(3);
    for (int i = 0; i < 3; ++i) {
        assert(!budget.exhausted());
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

    assert(pocketai::reusable_token_prefix({1, 2, 3, 4}, {1, 2, 9}) == 2);
    assert(pocketai::reusable_token_prefix({1, 2, 3}, {1, 2, 3, 4}) == 2);
    assert(pocketai::reusable_token_prefix({1, 2, 3, 4}, {1, 2, 3}) == 3);
    assert(pocketai::reusable_token_prefix({7}, {7}) == 0);
    assert(pocketai::reusable_token_prefix({}, {1, 2}) == 0);

    assert(pocketai::strip_thinking("bonjour").compare("bonjour") == 0);
    assert(pocketai::strip_thinking("<think>secret</think>visible").compare("visible") == 0);
    assert(pocketai::strip_thinking("avant<think>secret</think>apres").compare("avantapres") == 0);
    assert(pocketai::strip_thinking("<think>incomplet").empty());
    assert(pocketai::thinking_content("visible").empty());
    assert(pocketai::thinking_content("<think>secret</think>visible").compare("secret") == 0);
    assert(pocketai::thinking_content("<think>a</think>x<think>b</think>").compare("a\nb") == 0);
    assert(pocketai::utf8_codepoints("abc") == 3);
    assert(pocketai::utf8_codepoints("\xC3\xA9") == 1);
    assert(pocketai::utf8_codepoints("\xF0\x9F\x98\x80") == 1);

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
