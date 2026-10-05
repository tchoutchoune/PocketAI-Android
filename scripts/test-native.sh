#!/usr/bin/env bash
set -euo pipefail
TASK_REPO=$(cd "$(dirname "$0")/.." && pwd)
TASK_LLAMA_DIR=${1:?Pass the pinned llama.cpp checkout}
cd "$TASK_REPO"
mkdir -p out build/native-tests
for test in inference_helpers runtime_policy; do
    g++ -std=c++17 -O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined \
        -I"$TASK_LLAMA_DIR/include" -I"$TASK_LLAMA_DIR/ggml/include" \
        "lib/src/test/cpp/${test}_test.cpp" -o "build/native-tests/$test"
    "build/native-tests/$test"
done
g++ -std=c++17 -O1 -g -ffunction-sections -fdata-sections -Wl,--gc-sections \
    -fno-omit-frame-pointer -fsanitize=address,undefined \
    -Ilib/src/test/cpp/stubs -I"$TASK_LLAMA_DIR/include" -I"$TASK_LLAMA_DIR/common" \
    -I"$TASK_LLAMA_DIR/ggml/include" -I"$TASK_LLAMA_DIR/vendor" \
    lib/src/test/cpp/runtime_engine_test.cpp -o build/native-tests/runtime_engine
build/native-tests/runtime_engine
printf '%s\n'  'Helpers, policy and actual engine with a fake backend (prefill, replay, mid-generation fallback, cancellation, logits, ownership): passed with ASan/UBSan. Hardware inference remains a device validation gate.' > out/NATIVE-TESTS.txt
