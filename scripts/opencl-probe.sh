#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
TEST_PACKAGE="com.arm.aichat.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="com.arm.aichat.OpenClRuntimeProbeTest"
APK=""
SERIAL=""

usage() {
    cat <<'EOF'
Usage:
  bash OPENCL-PROBE.sh [--serial SERIAL] [--apk PATH]
  bash scripts/opencl-probe.sh [--serial SERIAL] [--apk PATH]

This probe does not need a GGUF model. It only checks whether Android exposes
Qualcomm/OpenCL runtime entry points to the PocketAI test package.
EOF
}

while (($#)); do
    case "$1" in
        --apk) APK=${2:?missing APK path}; shift 2 ;;
        --serial) SERIAL=${2:?missing serial}; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
    esac
done

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
echo "Installing OpenCL probe instrumentation APK: $APK"
"${ADB[@]}" install -r -t "$APK" >/dev/null

OUT_DIR="$SCRIPT_DIR"
if [[ "$SCRIPT_DIR" == */scripts ]]; then
    OUT_DIR="$SCRIPT_DIR/../out"
fi
mkdir -p "$OUT_DIR"
STAMP=$(date +%Y%m%d-%H%M%S)
REPORT="$OUT_DIR/opencl-probe-$STAMP.txt"
LOGCAT="$OUT_DIR/opencl-probe-logcat-$STAMP.txt"

"${ADB[@]}" logcat -c || true
set +e
"${ADB[@]}" shell am instrument -w -r     -e class "$TEST_CLASS"     "$TEST_PACKAGE/$RUNNER" | tee "$REPORT"
TEST_STATUS=${PIPESTATUS[0]}
set -e

"${ADB[@]}" logcat -d -v threadtime     -s PocketAI.OpenCLProbe:I PocketAI.Native:I AndroidRuntime:E '*:S' > "$LOGCAT" || true

if (( TEST_STATUS != 0 )) || grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$REPORT"; then
    echo "OpenCL runtime probe FAILED. See:"
    echo "  $REPORT"
    echo "  $LOGCAT"
    exit 1
fi

if ! grep -q 'OK (1 test)' "$REPORT"; then
    echo "The OpenCL probe did not complete exactly one test." >&2
    echo "See $REPORT" >&2
    exit 1
fi

if ! grep -q 'OpenCL runtime probe:' "$LOGCAT"; then
    echo "Probe ran but the expected native diagnostic line was not captured." >&2
    echo "See $LOGCAT" >&2
    exit 1
fi

echo
grep 'OpenCL runtime probe:' "$LOGCAT" | tail -n 1 || true
echo

if grep -Eq 'libOpenCL(\.so|_adreno\.so) loadable' "$LOGCAT"; then
    echo "RESULT: at least one OpenCL runtime name is visible to the app sandbox."
    echo "Next step: compile the experimental ggml-opencl backend and validate clGetPlatformIDs/device enumeration."
else
    echo "RESULT: the probe worked, but neither OpenCL runtime name is currently loadable."
    echo "Next step: resolve Android vendor-library namespace/linkage before enabling GGML_OPENCL."
fi

echo "Report: $REPORT"
echo "Diagnostics: $LOGCAT"
