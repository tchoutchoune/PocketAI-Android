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

    @Test fun diagnosticTimingIsBoundedToTwoPartialGpuProfiles() {
        assertEquals(listOf(16 to 32, 4 to 1), VulkanTuning.candidates(InferenceOptions())
            .filter(VulkanTuning::diagnosticCandidate).map { it.gpuLayers to it.microBatchSize })
    }

    private fun measurable(reason: String = "numeric_mismatch", stage: String = "completed",
        samples: Int = 12, failureMask: Int = 8, topMatches: Int = 12,
        decodeStatus: Int = 0, nonfiniteCount: Int = 0, maxJs: Double = 0.0055,
        maxRelativeRmse: Double = 0.31, policiesMatch: Boolean = true) =
        VulkanTuning.canMeasureRejectedGpu(reason, stage, samples, failureMask, topMatches,
            decodeStatus, nonfiniteCount, maxJs, maxRelativeRmse, policiesMatch)

    @Test fun onlyCompleteFiniteRmsOnlyRefusalsCanBeTimed() {
        assertTrue(measurable())
        assertFalse(measurable(reason = "nonfinite_logits"))
        assertFalse(measurable(stage = "logit_comparison"))
        assertFalse(measurable(samples = 11))
        assertFalse(measurable(failureMask = 12))
        assertFalse(measurable(topMatches = 11))
        assertFalse(measurable(decodeStatus = -1))
        assertFalse(measurable(nonfiniteCount = 1))
        assertFalse(measurable(nonfiniteCount = -1))
        assertFalse(measurable(policiesMatch = false))
    }

    @Test fun invalidOrOutOfRangeDiagnosticMetricsAreRejected() {
        assertFalse(measurable(maxJs = Double.NaN))
        assertFalse(measurable(maxJs = Double.POSITIVE_INFINITY))
        assertFalse(measurable(maxJs = -0.001))
        assertFalse(measurable(maxJs = 0.010001))
        assertFalse(measurable(maxRelativeRmse = Double.NaN))
        assertFalse(measurable(maxRelativeRmse = Double.POSITIVE_INFINITY))
        assertFalse(measurable(maxRelativeRmse = 0.03))
        assertTrue(measurable(maxJs = 0.01))
    }
}
