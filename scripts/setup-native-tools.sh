#!/usr/bin/env bash
set -euo pipefail
TASK_TOOLS=${POCKETAI_TOOLS_DIR:-/workspace/pocketai-tools}
TASK_VULKAN_DIR="$TASK_TOOLS/vulkan-tools"
TASK_APT="$TASK_TOOLS/apt"
mkdir -p "$TASK_VULKAN_DIR/packages" "$TASK_APT/state/lists/partial" "$TASK_APT/cache/archives/partial" "$TASK_APT/log"
TASK_APT_ARGS=(-o "Dir::State=$TASK_APT/state" -o Dir::State::status=/var/lib/dpkg/status -o "Dir::Cache=$TASK_APT/cache" -o "Dir::Log=$TASK_APT/log")
if [ ! -x "$TASK_VULKAN_DIR/usr/bin/glslc" ]; then
    /usr/bin/apt-get "${TASK_APT_ARGS[@]}" update
    cd "$TASK_VULKAN_DIR/packages"
    /usr/bin/apt-get "${TASK_APT_ARGS[@]}" download glslc libshaderc1 libvulkan-dev spirv-headers
    for package in ./*.deb; do dpkg-deb -x "$package" "$TASK_VULKAN_DIR"; done
fi
mkdir -p "$TASK_VULKAN_DIR/bin"
ln -sfn usr/include "$TASK_VULKAN_DIR/include"
ln -sfn usr/share "$TASK_VULKAN_DIR/share"
cat > "$TASK_VULKAN_DIR/bin/glslc" <<'SHADER_COMPILER'
#!/usr/bin/env bash
set -e
TASK_VULKAN_ROOT=$(cd "$(dirname "$0")/.." && pwd)
exec env LD_LIBRARY_PATH="$TASK_VULKAN_ROOT/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" "$TASK_VULKAN_ROOT/usr/bin/glslc" "$@"
SHADER_COMPILER
chmod +x "$TASK_VULKAN_DIR/bin/glslc"
"$TASK_VULKAN_DIR/bin/glslc" --version
