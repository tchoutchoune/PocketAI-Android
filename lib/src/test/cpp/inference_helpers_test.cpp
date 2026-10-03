#include "../../main/cpp/inference_helpers.h"
#include "../../main/cpp/backend_validation.h"
#include <cassert>
#include <iostream>
#include <iterator>

int main(int argc, char **argv) {
    if (argc == 2 && std::string(argv[1]) == "adapt-template") {
        const std::string source((std::istreambuf_iterator<char>(std::cin)), std::istreambuf_iterator<char>());
        std::cout << pocketai::stable_qwen3_template(source);
        return 0;
    }
    pocketai::GenerationBudget budget;
    const std::vector<float> reference{-4.0f, 0.0f, 3.0f, 2.0f};
    const std::vector<float> shifted{6.0f, 10.0f, 13.0f, 12.0f};
    const std::vector<float> close{-3.999f, 0.001f, 3.002f, 1.999f};
    const std::vector<float> corrupt{3.0f, -4.0f, 0.0f, 2.0f};
    const std::vector<float> nonfinite{0.0f, std::numeric_limits<float>::infinity(), 3.0f, 2.0f};
    assert(pocketai::compare_logits(reference, shifted.data(), shifted.size()).passed);
    assert(pocketai::compare_logits(reference, close.data(), close.size()).passed);
    assert(!pocketai::compare_logits(reference, corrupt.data(), corrupt.size()).passed);
    assert(!pocketai::compare_logits(reference, nonfinite.data(), nonfinite.size()).passed);
    assert(!pocketai::compare_logits(reference, close.data(), 0).passed);
    assert(!pocketai::finite_logits(nullptr, 4));
    const std::vector<float> flat(32, 0.0f);
    assert(!pocketai::compare_logits(std::vector<float>(32, 4.0f), reference.data(), 4).passed);
    auto spike = flat;
    spike[7] = 12.0f;
    assert(!pocketai::compare_logits(flat, spike.data(), spike.size()).passed);
    assert(pocketai::compare_logits(flat, flat.data(), flat.size()).passed);
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

    assert(pocketai::token_prefix_length({1, 2, 3}, {1, 2, 9}) == 2);
    assert(pocketai::token_prefix_length({1, 2, 3}, {1, 2, 3, 4}) == 3);
    assert(pocketai::token_prefix_length({}, {1, 2}) == 0);
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
