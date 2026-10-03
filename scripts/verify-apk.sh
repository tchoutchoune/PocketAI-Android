#!/usr/bin/env bash
set -euo pipefail
TASK_REPO=$(cd "$(dirname "$0")/.." && pwd)
TASK_SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
test -n "$TASK_SDK"
TASK_APK="$TASK_REPO/app/build/outputs/apk/debug/app-debug.apk"
test -s "$TASK_APK"
mkdir -p "$TASK_REPO/out"
unzip -t "$TASK_APK" > "$TASK_REPO/out/ZIP-CHECK.txt"
"$TASK_SDK/build-tools/35.0.0/apksigner" verify --verbose --print-certs "$TASK_APK" > "$TASK_REPO/out/SIGNATURE.txt"
EXPECTED_PREVIEW_CERT_SHA256="a0f04583b124e77b5ea2c739e7206d8c92d9a06615166f94fe26c245c2985e77"
ACTUAL_PREVIEW_CERT_SHA256=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$TASK_REPO/out/SIGNATURE.txt" | tr '[:upper:]' '[:lower:]' | tr -d ':')
test "$ACTUAL_PREVIEW_CERT_SHA256" = "$EXPECTED_PREVIEW_CERT_SHA256"
printf 'Preview signing certificate SHA-256: %s\n' "$ACTUAL_PREVIEW_CERT_SHA256" >> "$TASK_REPO/out/SIGNATURE.txt"
"$TASK_SDK/build-tools/35.0.0/aapt" dump badging "$TASK_APK" > "$TASK_REPO/out/PACKAGE.txt"
python3 - "$TASK_APK" "$TASK_REPO/out" <<'PY'
from pathlib import Path
from zipfile import ZipFile
import sys
import struct
apk, out = Path(sys.argv[1]), Path(sys.argv[2])
with ZipFile(apk) as z:
    libs=[n for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')]
    assert libs and all(n.startswith('lib/arm64-v8a/') for n in libs), libs
    for lib in ['libai-chat.so','libllama.so','libggml-vulkan.so']:
        assert 'lib/arm64-v8a/'+lib in libs, f'Missing {lib}'
    for name in libs:
        data = z.read(name)
        assert data[:5] == b'\x7fELF\x02', f'Not ELF64: {name}'
        offset = struct.unpack_from('<Q', data, 32)[0]
        size, count = struct.unpack_from('<HH', data, 54)
        loads = [struct.unpack_from('<IIQQQQQQ', data, offset + i * size)
                 for i in range(count) if struct.unpack_from('<I', data, offset + i * size)[0] == 1]
        assert loads and all(header[-1] >= 16384 for header in loads), f'16 KB page alignment missing: {name}'
    (out/'NATIVE-LIBS.txt').write_text('\n'.join(libs)+'\n')
    (out/'PAGE-ALIGNMENT.txt').write_text(f'All {len(libs)} ARM64 native libraries support 16 KB page alignment.\n')
badging=(out/'PACKAGE.txt').read_text()
assert "name='io.github.tchoutchoune.pocketai.preview'" in badging
assert "versionCode='452'" in badging
assert "application-label:'PocketAI'" in badging
print(f'Validated {len(libs)} ARM64 native libraries, including Vulkan')
PY
cp "$TASK_APK" "$TASK_REPO/out/PocketAI-4.5.2-vulkan-isolated-arm64-debug.apk"
cd "$TASK_REPO/out"
sha256sum PocketAI-4.5.2-vulkan-isolated-arm64-debug.apk > SHA256.txt
