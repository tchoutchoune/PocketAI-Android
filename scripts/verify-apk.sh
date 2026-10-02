#!/usr/bin/env bash
set -euo pipefail
TASK_REPO=$(cd "$(dirname "$0")/.." && pwd)
TASK_SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
test -n "$TASK_SDK"
TASK_APK="$TASK_REPO/app/build/outputs/apk/debug/app-debug.apk"
test -s "$TASK_APK"
mkdir -p "$TASK_REPO/out"
unzip -t "$TASK_APK" > "$TASK_REPO/out/ZIP-CHECK.txt"
"$TASK_SDK/build-tools/35.0.0/apksigner" verify --verbose "$TASK_APK" > "$TASK_REPO/out/SIGNATURE.txt"
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
assert "name='io.github.tchoutchoune.pocketai.assistant420'" in badging
assert "versionCode='420'" in badging
print(f'Validated {len(libs)} ARM64 native libraries, including Vulkan')
PY
cp "$TASK_APK" "$TASK_REPO/out/PocketAI-4.2.0-assistant-arm64-debug.apk"
cd "$TASK_REPO/out"
sha256sum PocketAI-4.2.0-assistant-arm64-debug.apk > SHA256.txt
