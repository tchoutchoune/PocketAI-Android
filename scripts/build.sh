#!/usr/bin/env bash
set -euo pipefail
TASK_REPO=$(cd "$(dirname "$0")/.." && pwd)
TASK_TOOLS=${POCKETAI_TOOLS_DIR:-/workspace/pocketai-tools}
if [ -f "$TASK_TOOLS/activate.sh" ]; then source "$TASK_TOOLS/activate.sh"; fi
TASK_LLAMA_DIR=${POCKETAI_LLAMA_DIR:-$TASK_TOOLS/llama.cpp}
TASK_VULKAN_DIR=${POCKETAI_VULKAN_DIR:-$TASK_TOOLS/vulkan-tools}
export PATH="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/cmake/3.31.6/bin:$TASK_VULKAN_DIR/bin:$PATH"
cd "$TASK_REPO"
mkdir -p out build/native-tests
BUILD_LOG="$TASK_REPO/out/BUILD.log"
set +e
bash gradlew -PllamaCppDir="$TASK_LLAMA_DIR" -PvulkanToolsDir="$TASK_VULKAN_DIR" \
    :app:assembleDebug :app:testDebugUnitTest :lib:testDebugUnitTest \
    --no-daemon --max-workers=4 '-Dorg.gradle.jvmargs=-Xmx6g' "$@" 2>&1 | tee "$BUILD_LOG"
GRADLE_STATUS=${PIPESTATUS[0]}
set -e
if [ "$GRADLE_STATUS" -ne 0 ]; then
    printf 'Gradle failed with exit code %s\n' "$GRADLE_STATUS" | tee -a "$BUILD_LOG" >&2
    exit "$GRADLE_STATUS"
fi
g++ -std=c++17 -O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined \
    lib/src/test/cpp/inference_helpers_test.cpp -o build/native-tests/inference-helpers
build/native-tests/inference-helpers
POCKETAI_LLAMA_DIR="$TASK_LLAMA_DIR" python3 scripts/test-qwen-template.py
printf '%s\n' 'Generation limits, context shifts and Unicode: passed with ASan/UBSan.' 'Pinned Qwen3 template: stable KV prefix across 3 turns; enabled-thinking behavior unchanged.' > out/NATIVE-TESTS.txt
bash scripts/verify-apk.sh
