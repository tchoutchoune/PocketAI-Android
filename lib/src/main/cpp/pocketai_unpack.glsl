// MIT; PocketAI Vulkan compatibility workaround.
// Expand packed bytes with 32-bit shifts/conversions rather than unpack8 bitcasts.
// Qualcomm driver reports: ggml-org/llama.cpp#28290; validate on the actual device.
u8vec4 pocketai_unpack8(uint bits) {
    return u8vec4(uvec4(bits, bits >> 8, bits >> 16, bits >> 24) & 255u);
}
i8vec4 pocketai_unpack8(int bits) {
    uint value = uint(bits);
    ivec4 bytes = ivec4(uvec4(value, value >> 8, value >> 16, value >> 24) & 255u);
    return i8vec4((bytes ^ 128) - 128);
}
#define unpack8 pocketai_unpack8
