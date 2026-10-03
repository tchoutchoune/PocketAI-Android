#!/usr/bin/env python3
"""Compile the portable byte expansion and reject 8-bit vector bitcasts."""
import argparse
import re
import subprocess
import tempfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--glslc', default='glslc')
args = parser.parse_args()
header = Path(__file__).resolve().parents[1] / 'lib/src/main/cpp'
shader = '''#version 450
#extension GL_GOOGLE_include_directive : require
#extension GL_EXT_shader_explicit_arithmetic_types_int8 : require
#include "pocketai_unpack.glsl"
layout(local_size_x = 1) in;
layout(set = 0, binding = 0, std430) buffer Data { uint packed; uvec4 unsignedBytes; ivec4 signedBytes; } data;
void main() {
    data.unsignedBytes = uvec4(unpack8(data.packed));
    data.signedBytes = ivec4(unpack8(int(data.packed)));
}
'''
with tempfile.TemporaryDirectory() as directory:
    source = Path(directory) / 'unpack.comp'
    source.write_text(shader)
    for optimization in ['-O0', '-O']:
        result = subprocess.run([args.glslc, optimization, '-S', '--target-env=vulkan1.2', '-I', str(header), str(source), '-o', '-'], check=True, text=True, capture_output=True)
        assembly = result.stdout
        byte_types = set(re.findall(r'(%\w+) = OpTypeInt 8 [01]', assembly))
        byte_vectors = {identifier for identifier, element in re.findall(r'(%\w+) = OpTypeVector (%\w+) \d+', assembly) if element in byte_types}
        for target in re.findall(r'OpBitcast (%\w+)', assembly):
            assert target not in byte_types | byte_vectors, 'Compiler emitted the unsafe packed-byte bitcast'
        assert 'OpShiftRightLogical' in assembly and 'OpBitwiseAnd' in assembly
        assert 'OpSConvert' in assembly and 'OpUConvert' in assembly
print('Portable unsigned/signed byte expansion: GLSL compiled; no 8-bit bitcasts at -O0/-O.')
