package com.pocketai.app

import com.arm.aichat.InferenceOptions
import org.junit.Assert.*
import org.junit.Test

class VulkanTuningTest {
    private val cpu = BackendMeasurement(InferenceOptions(), 50.0, 12.0, true)
    private fun gpu(prompt: Double = 80.0, generation: Double = 20.0, validated: Boolean = true) =
        BackendMeasurement(InferenceOptions(gpuLayers = 16, microBatchSize = 32), prompt, generation, validated)

    @Test fun corruptedAndFallbackSamplesNeverSelectGpu() {
        assertNull(VulkanTuning.bestGpu(cpu, listOf(gpu(validated = false), gpu().copy(options = InferenceOptions()))))
        assertEquals(cpu, VulkanTuning.preferred(cpu, listOf(gpu(validated = false))))
    }

    @Test fun nonfiniteAndZeroSpeedsAreExcluded() {
        assertNull(VulkanTuning.bestGpu(cpu, listOf(gpu(generation = Double.NaN), gpu(prompt = 0.0), gpu(generation = Double.POSITIVE_INFINITY))))
    }

    @Test fun fastValidGpuIsSelected() {
        val faster = gpu()
        assertEquals(faster, VulkanTuning.preferred(cpu, listOf(gpu(generation = 11.0), faster)))
    }

    @Test fun smallGainAndLargePromptRegressionKeepCpu() {
        assertEquals(cpu, VulkanTuning.preferred(cpu, listOf(gpu(prompt = 51.0, generation = 12.1))))
        assertEquals(cpu, VulkanTuning.preferred(cpu, listOf(gpu(prompt = 20.0, generation = 30.0))))
    }

    @Test fun slowerPromptWinnerDoesNotHideAnotherUsefulGpuVariant() {
        val useful = gpu(prompt = 55.0, generation = 15.0)
        assertEquals(useful, VulkanTuning.preferred(cpu, listOf(gpu(prompt = 20.0, generation = 50.0), useful)))
    }

    @Test fun tuningCoversPartialAndFullOffloadWithSmallPhysicalBatches() {
        val candidates = VulkanTuning.candidates(InferenceOptions(batchSize = 128))
        assertEquals(setOf(16 to 32, 16 to 64, 256 to 32, 256 to 64, 16 to 1, 4 to 1), candidates.map { it.gpuLayers to it.microBatchSize }.toSet())
        assertTrue(candidates.all { it.batchSize == 128 })
    }
}
