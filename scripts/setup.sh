#!/usr/bin/env bash
set -euo pipefail

# Keep setup tools outside the checkout. Each cloud task already has its own checkout.
TASK_REPO=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
TASK_TOOLS=${POCKETAI_TOOLS_DIR:-/workspace/pocketai-tools}
TASK_LLAMA_COMMIT=db00347a4b33393bf53986e976fe8505bbf92665
TASK_JDK_VERSION=17.0.20.1+1
TASK_ANDROID_TOOLS_SHA1=5fdcc763663eefb86a5b8879697aa6088b041e70

case "$(uname -s)/$(uname -m)" in
    Linux/x86_64) ;;
    *) printf '%s\n' 'Setup requires Linux x86_64 for the pinned JDK and Android host tools.' >&2; exit 1 ;;
esac
for TASK_COMMAND in curl python3 tar unzip sha256sum sha1sum git openssl; do
    command -v "$TASK_COMMAND" >/dev/null || { printf 'Missing prerequisite: %s\n' "$TASK_COMMAND" >&2; exit 1; }
done
mkdir -p -- "$TASK_TOOLS"
TASK_TOOLS=$(cd -- "$TASK_TOOLS" && pwd)
TASK_JDK="$TASK_TOOLS/jdk17"
TASK_SDK="$TASK_TOOLS/android-sdk"
TASK_LLAMA="$TASK_TOOLS/llama.cpp"

# Never reset, patch, or replace an existing upstream checkout.
if [ -e "$TASK_LLAMA" ]; then
    if [ ! -e "$TASK_LLAMA/.git" ] || [ "$(git -C "$TASK_LLAMA" rev-parse HEAD 2>/dev/null || true)" != "$TASK_LLAMA_COMMIT" ]; then
        printf 'Existing llama.cpp must be at %s. Choose a fresh POCKETAI_TOOLS_DIR; the existing directory was preserved.\n' "$TASK_LLAMA_COMMIT" >&2
        exit 1
    fi
fi

TASK_TEMP=$(mktemp -d "$TASK_TOOLS/setup-XXXXXX")
trap 'rm -rf -- "$TASK_TEMP"' EXIT

task_download() {
    curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
        --retry 3 --retry-delay 1 "$1" --output "$2"
}

task_jdk_valid() {
    [ -x "$TASK_JDK/bin/java" ] && [ -x "$TASK_JDK/bin/keytool" ] && \
        [ -f "$TASK_JDK/release" ] && \
        python3 - "$TASK_JDK/release" "$TASK_JDK_VERSION" <<'CHECK_JDK'
import pathlib, sys
values = dict(line.split("=", 1) for line in pathlib.Path(sys.argv[1]).read_text().splitlines() if "=" in line)
assert values.get("JAVA_RUNTIME_VERSION", "").strip('"') == sys.argv[2], "Unexpected Java runtime version"
assert values.get("IMPLEMENTOR", "").strip('"') == "Eclipse Adoptium", "Unexpected JDK provider"
CHECK_JDK
}

if ! task_jdk_valid; then
    if [ -e "$TASK_JDK" ]; then
        printf 'Existing JDK is not Temurin %s; it was preserved. Choose a fresh POCKETAI_TOOLS_DIR.\n' "$TASK_JDK_VERSION" >&2
        exit 1
    fi
    TASK_JDK_URL='https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz'
    task_download "$TASK_JDK_URL" "$TASK_TEMP/jdk.tar.gz"
    task_download "$TASK_JDK_URL.sha256.txt" "$TASK_TEMP/jdk.sha256.txt"
    TASK_JDK_SHA=$(python3 - "$TASK_TEMP/jdk.sha256.txt" <<'READ_SHA'
import pathlib, re, sys
digest = pathlib.Path(sys.argv[1]).read_text().split()[0]
assert re.fullmatch(r"[0-9a-fA-F]{64}", digest), "Invalid official JDK checksum"
print(digest.lower())
READ_SHA
)
    printf '%s  %s\n' "$TASK_JDK_SHA" "$TASK_TEMP/jdk.tar.gz" | sha256sum --check --status
    mkdir "$TASK_TEMP/jdk"
    tar -xzf "$TASK_TEMP/jdk.tar.gz" -C "$TASK_TEMP/jdk" --strip-components=1
    mv -- "$TASK_TEMP/jdk" "$TASK_JDK"
    task_jdk_valid
fi
env -u JAVA_TOOL_OPTIONS "$TASK_JDK/bin/java" -version >/dev/null 2>&1

TASK_ANDROID_TOOLS="$TASK_SDK/cmdline-tools/19.0"
if [ ! -x "$TASK_ANDROID_TOOLS/bin/sdkmanager" ] || \
    ! python3 - "$TASK_ANDROID_TOOLS/source.properties" <<'CHECK_ANDROID_TOOLS'
import pathlib, sys
file = pathlib.Path(sys.argv[1])
if not file.is_file(): sys.exit(1)
values = dict(line.split("=", 1) for line in file.read_text().splitlines() if "=" in line)
sys.exit(0 if values.get("Pkg.Revision", "").strip() == "19.0" else 1)
CHECK_ANDROID_TOOLS
then
    if [ -e "$TASK_ANDROID_TOOLS" ]; then
        printf '%s\n' 'Existing Android command-line tools are incomplete or have the wrong version; directory preserved.' >&2
        exit 1
    fi
    task_download 'https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip' "$TASK_TEMP/android-tools.zip"
    # SHA-1 is the checksum published for this exact archive in Google's repository metadata.
    printf '%s  %s\n' "$TASK_ANDROID_TOOLS_SHA1" "$TASK_TEMP/android-tools.zip" | sha1sum --check --status
    unzip -q "$TASK_TEMP/android-tools.zip" -d "$TASK_TEMP/android-tools"
    mkdir -p "$TASK_SDK/cmdline-tools"
    mv -- "$TASK_TEMP/android-tools/cmdline-tools" "$TASK_ANDROID_TOOLS"
fi

# Extend the official JDK truststore with public, machine-provided proxy CAs.
# Keep existing truststore entries; compare DER certificates to skip unchanged imports.
TASK_TRUST="$TASK_TOOLS/java-cacerts"
if [ ! -f "$TASK_TRUST" ]; then cp -- "$TASK_JDK/lib/security/cacerts" "$TASK_TRUST"; fi
env -u JAVA_TOOL_OPTIONS "$TASK_JDK/bin/keytool" -list -keystore "$TASK_TRUST" -storepass changeit >/dev/null 2>&1
for TASK_CERT in /usr/local/share/ca-certificates/*.crt; do
    [ -f "$TASK_CERT" ] || continue
    TASK_ALIAS="env-$(basename -- "$TASK_CERT")"
    openssl x509 -in "$TASK_CERT" -outform DER -out "$TASK_TEMP/expected-cert.der"
    if env -u JAVA_TOOL_OPTIONS "$TASK_JDK/bin/keytool" -exportcert -alias "$TASK_ALIAS" \
        -keystore "$TASK_TRUST" -storepass changeit -file "$TASK_TEMP/installed-cert.der" >/dev/null 2>&1; then
        if cmp -s "$TASK_TEMP/expected-cert.der" "$TASK_TEMP/installed-cert.der"; then continue; fi
        env -u JAVA_TOOL_OPTIONS "$TASK_JDK/bin/keytool" -delete -alias "$TASK_ALIAS" \
            -keystore "$TASK_TRUST" -storepass changeit >/dev/null 2>&1
    fi
    env -u JAVA_TOOL_OPTIONS "$TASK_JDK/bin/keytool" -importcert -noprompt -trustcacerts -alias "$TASK_ALIAS" \
        -file "$TASK_CERT" -keystore "$TASK_TRUST" -storepass changeit >/dev/null 2>&1
done

cat > "$TASK_TEMP/activate.sh" <<'ACTIVATE'
# Source this file before building. Paths follow the directory containing this file.
TASK_POCKETAI_ACTIVE_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
export POCKETAI_TOOLS_DIR="$TASK_POCKETAI_ACTIVE_ROOT"
export JAVA_HOME="$TASK_POCKETAI_ACTIVE_ROOT/jdk17"
export ANDROID_HOME="$TASK_POCKETAI_ACTIVE_ROOT/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.13113456"
export GRADLE_USER_HOME="$TASK_POCKETAI_ACTIVE_ROOT/gradle-cache"
export ANDROID_USER_HOME="$TASK_POCKETAI_ACTIVE_ROOT/android-user"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/19.0/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmake/3.31.6/bin:$TASK_POCKETAI_ACTIVE_ROOT/vulkan-tools/bin:$PATH"
export CMAKE_BUILD_PARALLEL_LEVEL=4
TASK_POCKETAI_JAVA_PROXY=$(python3 - <<'JAVA_PROXY'
import os, re, urllib.parse
value = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy") or os.environ.get("HTTP_PROXY") or os.environ.get("http_proxy")
if value:
    try:
        proxy = urllib.parse.urlparse(value)
        host = proxy.hostname
        port = proxy.port or (443 if proxy.scheme == "https" else 80)
        assert proxy.scheme in ("http", "https") and host and re.fullmatch(r"[A-Za-z0-9.:-]+", host)
        assert 1 <= port <= 65535
    except (ValueError, AssertionError):
        raise SystemExit("Unsupported proxy address; use a valid HTTP(S) proxy URL.")
    # Proxy credentials are neither copied nor printed. Authentication stays platform-managed.
    print(f"-Dhttps.proxyHost={host} -Dhttps.proxyPort={port} -Dhttp.proxyHost={host} -Dhttp.proxyPort={port}")
JAVA_PROXY
)
export JAVA_TOOL_OPTIONS="${TASK_POCKETAI_JAVA_PROXY} -Djavax.net.ssl.trustStore=\"$TASK_POCKETAI_ACTIVE_ROOT/java-cacerts\" -Djavax.net.ssl.trustStorePassword=changeit"
unset TASK_POCKETAI_JAVA_PROXY TASK_POCKETAI_ACTIVE_ROOT
ACTIVATE
if ! cmp -s "$TASK_TEMP/activate.sh" "$TASK_TOOLS/activate.sh"; then
    cp -- "$TASK_TEMP/activate.sh" "$TASK_TOOLS/activate.sh"
fi
source "$TASK_TOOLS/activate.sh"
mkdir -p "$GRADLE_USER_HOME" "$ANDROID_USER_HOME"

# Use installed package files instead of sdkmanager --list, avoiding network access on a repeat run.
TASK_MISSING_PACKAGES=()
task_sdk_package_valid() {
    local TASK_PACKAGE_PATH=${1//;/\/}
    python3 - "$ANDROID_HOME/$TASK_PACKAGE_PATH" "$1" <<'CHECK_SDK_PACKAGE'
import pathlib, sys
root, package = pathlib.Path(sys.argv[1]), sys.argv[2]
metadata = root / "source.properties"
if not metadata.is_file(): sys.exit(1)
values = dict(line.split("=", 1) for line in metadata.read_text().splitlines() if "=" in line)
values = {key.strip(): value.strip() for key, value in values.items()}
kind, version = package.split(";", 1)
required = {"platforms": "android.jar", "build-tools": "aapt2", "ndk": "toolchains/llvm/prebuilt/linux-x86_64/bin/clang", "cmake": "bin/cmake"}[kind]
valid = (root / required).is_file()
if kind == "platforms": valid = valid and values.get("AndroidVersion.ApiLevel") == "36"
elif kind == "ndk": valid = valid and values.get("Pkg.BaseRevision", values.get("Pkg.Revision")) == version
else: valid = valid and values.get("Pkg.Revision") == version
sys.exit(0 if valid else 1)
CHECK_SDK_PACKAGE
}
for TASK_PACKAGE in 'platforms;android-36' 'build-tools;35.0.0' 'build-tools;36.0.0' 'ndk;29.0.13113456' 'cmake;3.31.6'; do
    if ! task_sdk_package_valid "$TASK_PACKAGE"; then TASK_MISSING_PACKAGES+=("$TASK_PACKAGE"); fi
done
if [ "${#TASK_MISSING_PACKAGES[@]}" -gt 0 ]; then
    set +e
    yes 2>/dev/null | "$TASK_ANDROID_TOOLS/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses >/dev/null
    TASK_LICENSE_STATUS=${PIPESTATUS[1]}
    set -e
    if [ "$TASK_LICENSE_STATUS" -ne 0 ]; then
        printf 'Android license acceptance failed (exit %s).\n' "$TASK_LICENSE_STATUS" >&2
        exit "$TASK_LICENSE_STATUS"
    fi
    "$TASK_ANDROID_TOOLS/bin/sdkmanager" --sdk_root="$ANDROID_HOME" "${TASK_MISSING_PACKAGES[@]}"
fi
for TASK_PACKAGE in 'platforms;android-36' 'build-tools;35.0.0' 'build-tools;36.0.0' 'ndk;29.0.13113456' 'cmake;3.31.6'; do
    task_sdk_package_valid "$TASK_PACKAGE" || { printf 'Android package validation failed: %s\n' "$TASK_PACKAGE" >&2; exit 1; }
done

if [ ! -e "$TASK_LLAMA" ]; then
    git init -q "$TASK_TEMP/llama.cpp"
    git -C "$TASK_TEMP/llama.cpp" remote add origin https://github.com/ggml-org/llama.cpp.git
    git -C "$TASK_TEMP/llama.cpp" fetch --depth=1 origin "$TASK_LLAMA_COMMIT"
    git -C "$TASK_TEMP/llama.cpp" checkout --detach "$TASK_LLAMA_COMMIT"
    mv -- "$TASK_TEMP/llama.cpp" "$TASK_LLAMA"
fi
test "$(git -C "$TASK_LLAMA" rev-parse HEAD)" = "$TASK_LLAMA_COMMIT"
POCKETAI_TOOLS_DIR="$TASK_TOOLS" bash "$TASK_REPO/scripts/setup-native-tools.sh"
printf 'PocketAI setup complete. Source %s/activate.sh before building.\n' "$TASK_TOOLS"
