#!/usr/bin/env bash
set -euo pipefail
TASK_REPO=$(cd "$(dirname "$0")/.." && pwd)
TASK_TOOLS=${POCKETAI_TOOLS_DIR:-/workspace/pocketai-tools}
if [ -f "$TASK_TOOLS/activate.sh" ]; then source "$TASK_TOOLS/activate.sh"; fi
TASK_LLAMA_DIR=${POCKETAI_LLAMA_DIR:-$TASK_TOOLS/llama.cpp}
TASK_VULKAN_DIR=${POCKETAI_VULKAN_DIR:-$TASK_TOOLS/vulkan-tools}
export PATH="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/cmake/3.31.6/bin:$TASK_VULKAN_DIR/bin:$PATH"
cd "$TASK_REPO"
bash gradlew -PllamaCppDir="$TASK_LLAMA_DIR" -PvulkanToolsDir="$TASK_VULKAN_DIR" \
    :app:assembleDebug :app:testDebugUnitTest :lib:testDebugUnitTest \
    --no-daemon --max-workers=4 '-Dorg.gradle.jvmargs=-Xmx6g' "$@"
mkdir -p out build/native-tests
g++ -std=c++17 -O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined \
    lib/src/test/cpp/inference_helpers_test.cpp -o build/native-tests/inference-helpers
build/native-tests/inference-helpers
printf '%s\n' 'Generation limits, context shifts and Unicode: passed with ASan/UBSan.' > out/NATIVE-TESTS.txt
bash scripts/verify-apk.sh
