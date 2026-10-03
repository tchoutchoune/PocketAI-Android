#!/usr/bin/env python3
"""Build an isolated Vulkan overlay; never modify the pinned upstream checkout."""
import argparse
import hashlib
import shutil
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('source', type=Path)
parser.add_argument('destination', type=Path)
args = parser.parse_args()
source = args.source.resolve()
destination = args.destination.resolve()
helper = Path(__file__).resolve().parents[1] / 'lib/src/main/cpp/pocketai_unpack.glsl'
types = source / 'ggml/src/ggml-vulkan/vulkan-shaders/types.glsl'
anchor = '#if defined(DATA_A_F32)'
text = types.read_text()
assert text.count(anchor) == 1, 'Pinned shader layout changed; review the compatibility overlay'
revision = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
stamp = hashlib.sha256((str(source) + revision + text + helper.read_text() + Path(__file__).read_text()).encode()).hexdigest()
marker = destination / '.pocketai-overlay'
if not marker.is_file() or marker.read_text() != stamp:
    assert destination != source and source not in destination.parents
    if destination.exists():
        shutil.rmtree(destination)
    shutil.copytree(source, destination, ignore=shutil.ignore_patterns('.git', 'build', '__pycache__'))
    shaders = destination / 'ggml/src/ggml-vulkan/vulkan-shaders'
    (shaders / 'types.glsl').write_text(text.replace(anchor, '#include "pocketai_unpack.glsl"\n\n' + anchor))
    shutil.copy2(helper, shaders / helper.name)
    marker.write_text(stamp)
print(destination)
