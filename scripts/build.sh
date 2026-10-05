#!/usr/bin/env bash
set -euo pipefail
TASK_REPO=$(cd "$(dirname "$0")/.." && pwd)
TASK_TOOLS=${POCKETAI_TOOLS_DIR:-/workspace/pocketai-tools}
if [ -f "$TASK_TOOLS/activate.sh" ]; then source "$TASK_TOOLS/activate.sh"; fi
TASK_LLAMA_DIR=${POCKETAI_LLAMA_DIR:-$TASK_TOOLS/llama.cpp}
TASK_VULKAN_DIR=${POCKETAI_VULKAN_DIR:-$TASK_TOOLS/vulkan-tools}
export PATH="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/cmake/3.31.6/bin:$TASK_VULKAN_DIR/bin:$PATH"
cd "$TASK_REPO"
export POCKETAI_SOURCE_REVISION=$(git rev-parse HEAD)
bash scripts/test-native.sh "$TASK_LLAMA_DIR"
bash gradlew -PllamaCppDir="$TASK_LLAMA_DIR" -PvulkanToolsDir="$TASK_VULKAN_DIR" \
    :app:assembleDebug :app:testDebugUnitTest :lib:testDebugUnitTest :lib:assembleDebugAndroidTest \
    --no-daemon --max-workers=4 '-Dorg.gradle.jvmargs=-Xmx6g' "$@"
bash scripts/verify-apk.sh
