package com.arm.aichat

/** Configuration applied before a model is loaded. GPU layers are an explicit opt-in. */
data class InferenceOptions(
    val threads: Int = 4,
    val contextSize: Int = 2048,
    val batchSize: Int = 256,
    val gpuLayers: Int = 0,
    val temperature: Float = 0.6f,
    val microBatchSize: Int = batchSize,
) {
    init {
        require(threads in 1..32) { "Threads must be between 1 and 32" }
        require(contextSize in 512..32768) { "Context must be between 512 and 32768 tokens" }
        require(batchSize in 32..1024 && batchSize <= contextSize) { "Batch must be between 32 and 1024 and fit the context" }
        require(gpuLayers in 0..256) { "GPU layers must be between 0 and 256" }
        require(microBatchSize in 1..batchSize) { "Micro-batch must fit the logical batch" }
        require(temperature.isFinite() && temperature in 0f..2f) { "Temperature must be between 0 and 2" }
    }
}
