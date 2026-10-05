#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
TEST_PACKAGE="com.arm.aichat.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
APK=""
SERIAL=""
LOCAL_MODEL=""
DEVICE_MODEL=""
REQUIRE_VULKAN=0

usage() {
    cat <<'EOF'
Usage:
  bash DEVICE-SMOKE.sh --local-model /path/to/model.gguf [--serial SERIAL] [--require-vulkan]
  bash DEVICE-SMOKE.sh --device-model /sdcard/.../model.gguf [--serial SERIAL] [--require-vulkan]
  bash scripts/device-smoke.sh --local-model /path/to/model.gguf [options]

Options:
  --apk PATH           Instrumentation APK. Auto-detected from the artifact/repo by default.
  --serial SERIAL      adb device serial when several devices are connected.
  --local-model PATH   Push a local GGUF into the test app external files directory.
  --device-model PATH  Use an already present path readable by com.arm.aichat.test.
  --require-vulkan     Fail unless the requested Vulkan pass remains active on Vulkan + CPU.
EOF
}

while (($#)); do
    case "$1" in
        --apk) APK=${2:?missing APK path}; shift 2 ;;
        --serial) SERIAL=${2:?missing serial}; shift 2 ;;
        --local-model) LOCAL_MODEL=${2:?missing local model path}; shift 2 ;;
        --device-model) DEVICE_MODEL=${2:?missing device model path}; shift 2 ;;
        --require-vulkan) REQUIRE_VULKAN=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
    esac
done

if [[ -n "$LOCAL_MODEL" && -n "$DEVICE_MODEL" ]] || [[ -z "$LOCAL_MODEL" && -z "$DEVICE_MODEL" ]]; then
    echo "Choose exactly one of --local-model or --device-model." >&2
    exit 2
fi

command -v adb >/dev/null || { echo "adb is required (Android platform-tools)." >&2; exit 2; }

ADB=(adb)
[[ -n "$SERIAL" ]] && ADB+=(-s "$SERIAL")

if [[ -z "$APK" ]]; then
    for candidate in         "$SCRIPT_DIR/PocketAI-4.2.2-engine-androidTest.apk"         "$SCRIPT_DIR/../out/PocketAI-4.2.2-engine-androidTest.apk"         "$SCRIPT_DIR/../lib/build/outputs/apk/androidTest/debug/lib-debug-androidTest.apk"; do
        if [[ -s "$candidate" ]]; then APK="$candidate"; break; fi
    done
fi
[[ -n "$APK" && -s "$APK" ]] || { echo "Instrumentation APK not found. Use --apk PATH." >&2; exit 2; }

"${ADB[@]}" get-state >/dev/null
echo "Installing engine instrumentation APK: $APK"
"${ADB[@]}" install -r -t "$APK" >/dev/null

if [[ -n "$LOCAL_MODEL" ]]; then
    [[ -s "$LOCAL_MODEL" ]] || { echo "Local model is missing or empty: $LOCAL_MODEL" >&2; exit 2; }
    DEVICE_MODEL="/sdcard/Android/data/$TEST_PACKAGE/files/pocketai-validation.gguf"
    "${ADB[@]}" shell mkdir -p "/sdcard/Android/data/$TEST_PACKAGE/files"
    echo "Pushing model to $DEVICE_MODEL"
    "${ADB[@]}" push "$LOCAL_MODEL" "$DEVICE_MODEL" >/dev/null
fi

if [[ "$DEVICE_MODEL" == *" "* ]]; then
    echo "Model paths containing spaces are not supported by this helper. Use --local-model instead." >&2
    exit 2
fi

# Verify readability as the test application UID. This prevents an Assume/skip from
# being mistaken for a successful device validation.
if ! "${ADB[@]}" shell run-as "$TEST_PACKAGE" sh -c "test -r '$DEVICE_MODEL'" >/dev/null 2>&1; then
    echo "The model is not readable by $TEST_PACKAGE: $DEVICE_MODEL" >&2
    echo "Use --local-model so the helper copies it into the test app's external files directory." >&2
    exit 3
fi

OUT_DIR="$SCRIPT_DIR"
if [[ "$SCRIPT_DIR" == */scripts ]]; then
    OUT_DIR="$SCRIPT_DIR/../out"
fi
mkdir -p "$OUT_DIR"
STAMP=$(date +%Y%m%d-%H%M%S)
REPORT="$OUT_DIR/device-validation-$STAMP.txt"
LOGCAT="$OUT_DIR/device-logcat-$STAMP.txt"

"${ADB[@]}" logcat -c || true
echo "Running CPU + requested Vulkan semantic smoke test..."
set +e
"${ADB[@]}" shell am instrument -w -r \
    -e class com.arm.aichat.DeviceInferenceTest \
    -e modelPath "$DEVICE_MODEL" \
    "$TEST_PACKAGE/$RUNNER" | tee "$REPORT"
TEST_STATUS=${PIPESTATUS[0]}
set -e

"${ADB[@]}" logcat -d -v threadtime     -s PocketAI.DeviceTest:I PocketAI.Native:I AndroidRuntime:E '*:S' > "$LOGCAT" || true

if (( TEST_STATUS != 0 )) || grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$REPORT"; then
    echo "Device validation FAILED. See:"
    echo "  $REPORT"
    echo "  $LOGCAT"
    exit 1
fi

if ! grep -q 'OK (1 test)' "$REPORT"; then
    echo "The instrumentation run did not report a completed test; refusing a false pass." >&2
    echo "See $REPORT" >&2
    exit 1
fi

if (( REQUIRE_VULKAN )); then
    if ! grep -q 'Requested: Vulkan; active: Vulkan + CPU' "$LOGCAT"; then
        echo "Reliability may have passed via CPU fallback, but active Vulkan was required." >&2
        echo "See $LOGCAT" >&2
        exit 4
    fi
fi

echo "Device validation PASSED."
echo "Report: $REPORT"
echo "Backend diagnostics: $LOGCAT"
if (( ! REQUIRE_VULKAN )); then
    echo "Note: this proves reliability only. Re-run with --require-vulkan to require active Vulkan."
fi
