#pragma once
#include "llama.h"
#include <vector>
#include <stdexcept>

// Own every batch array with C++ containers. Partial allocation failures unwind
// safely; llama_batch_init in the pinned dependency does not check every malloc.
class NativeBatch {
    std::vector<llama_token> tokens;
    std::vector<llama_pos> positions;
    std::vector<int32_t> sequence_counts;
    std::vector<llama_seq_id> sequences;
    std::vector<llama_seq_id *> sequence_ptrs;
    std::vector<int8_t> logits;
public:
    explicit NativeBatch(int capacity) : tokens(capacity), positions(capacity),
        sequence_counts(capacity, 1), sequences(capacity, 0), sequence_ptrs(capacity), logits(capacity) {
        for (int i = 0; i < capacity; ++i) sequence_ptrs[i] = &sequences[i];
    }
    NativeBatch(const NativeBatch &) = delete;
    NativeBatch &operator=(const NativeBatch &) = delete;
    llama_batch make(const llama_token *input, int count, int position, bool last_logits) {
        if (count < 1 || size_t(count) > tokens.size()) throw std::invalid_argument("batch capacity");
        for (int i = 0; i < count; ++i) {
            tokens[i] = input[i];
            positions[i] = position + i;
            logits[i] = last_logits && i == count - 1;
        }
        llama_batch result{};
        result.n_tokens = count;
        result.token = tokens.data();
        result.pos = positions.data();
        result.n_seq_id = sequence_counts.data();
        result.seq_id = sequence_ptrs.data();
        result.logits = logits.data();
        return result;
    }
};
