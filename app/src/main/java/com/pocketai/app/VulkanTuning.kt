package com.pocketai.app

import com.arm.aichat.InferenceOptions

internal data class BackendMeasurement(
    val options: InferenceOptions,
    val promptTps: Double,
    val generationTps: Double,
    val validated: Boolean,
)

internal object VulkanTuning {
    const val REVISION = "adreno-isolated-v2"

    fun candidates(base: InferenceOptions): List<InferenceOptions> =
        listOf(16, 256).flatMap { layers -> listOf(32, 64).map { micro ->
            base.copy(gpuLayers = layers, microBatchSize = micro)
        } } + listOf(16, 4).map { base.copy(gpuLayers = it, microBatchSize = 1) }

    private fun usable(sample: BackendMeasurement) = sample.validated &&
        sample.promptTps.isFinite() && sample.promptTps > 0 &&
        sample.generationTps.isFinite() && sample.generationTps > 0

    // Generation gets priority, with a guard against a large prefill regression.
    fun bestGpu(cpu: BackendMeasurement, samples: List<BackendMeasurement>): BackendMeasurement? =
        samples.filter { usable(it) && it.options.gpuLayers > 0 }.maxByOrNull {
            0.75 * it.generationTps / cpu.generationTps + 0.25 * it.promptTps / cpu.promptTps
        }

    fun improvesCpu(cpu: BackendMeasurement, gpu: BackendMeasurement): Boolean =
        usable(cpu) && usable(gpu) && gpu.options.gpuLayers > 0 &&
            gpu.generationTps >= cpu.generationTps && gpu.promptTps >= cpu.promptTps * 0.9 &&
            0.75 * gpu.generationTps / cpu.generationTps + 0.25 * gpu.promptTps / cpu.promptTps >= 1.05

    fun preferred(cpu: BackendMeasurement, samples: List<BackendMeasurement>): BackendMeasurement =
        bestGpu(cpu, samples.filter { improvesCpu(cpu, it) }) ?: cpu
}
