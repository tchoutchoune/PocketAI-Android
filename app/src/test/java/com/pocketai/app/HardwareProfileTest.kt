package com.pocketai.app

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareProfileTest {
    private val gib = 1024L * 1024 * 1024
    private val capable = HardwareProfile(12 * gib, 8 * gib, 8, 4, "1.3.0", false, 0)

    @Test fun cpuSelectionDisablesGpuEvenWhenVulkanIsDeclared() {
        assertEquals(0, capable.recommend(gib, "cpu").gpuLayers)
        assertEquals(0, capable.recommend(gib, "cpu-performance").gpuLayers)
        assertTrue(capable.recommend(gib, "performance").gpuLayers > 0)
    }

    @Test fun ampleMemoryScalesContextWithoutIgnoringMode() {
        assertEquals(4096, capable.recommend(gib, "balanced").contextSize)
        assertEquals(4096, capable.recommend(gib, "cpu-performance").contextSize)
        assertEquals(8192, capable.recommend(gib, "performance").contextSize)
    }

    @Test fun balancedModeStaysCpuSafeEvenWithVulkanAvailable() {
        val balanced = capable.recommend(gib, "balanced")
        assertEquals(0, balanced.gpuLayers)
        assertEquals(4096, balanced.contextSize)
    }

    @Test fun batterySaverModeratesBalancedButExplicitAutonomyIsStricter() {
        val phone = capable.copy(powerSave = true)
        val balanced = phone.recommend(gib, "balanced")
        val eco = phone.recommend(gib, "eco")
        val fastCpu = phone.recommend(gib, "cpu-performance")
        assertEquals(3, balanced.threads)
        assertEquals(0, balanced.gpuLayers)
        assertEquals(128, balanced.batchSize)
        assertTrue(eco.threads <= 2)
        assertEquals(64, eco.batchSize)
        assertEquals(6, fastCpu.threads)
        assertEquals(0, fastCpu.gpuLayers)
        assertEquals(256, fastCpu.batchSize)
        assertEquals(4096, fastCpu.contextSize)
    }

    @Test fun severeThermalStateForcesConservativeCpuProfile() {
        val hot = capable.copy(thermalStatus = PowerManager.THERMAL_STATUS_SEVERE).recommend(gib, "performance")
        assertTrue(hot.threads <= 2)
        assertEquals(0, hot.gpuLayers)
        assertEquals(1024, hot.contextSize)
        assertEquals(64, hot.batchSize)
    }

    @Test fun lowAvailableMemoryUsesConservativeContextAndCpu() {
        val options = capable.copy(availableRamBytes = 700L * 1024 * 1024).recommend(gib, "performance")
        assertEquals(1024, options.contextSize)
        assertEquals(64, options.batchSize)
        assertEquals(0, options.gpuLayers)
    }

    @Test fun mmapModelCanExceedCurrentFreeRamWithoutBeingRejected() {
        val phone = capable.copy(availableRamBytes = 3 * gib)
        assertTrue(phone.canAttemptModelLoad(4 * gib))
    }

    @Test fun modelLoadAdmissionRejectsOnlyClearlyUnsafeCases() {
        assertEquals(false, capable.copy(availableRamBytes = 300L * 1024 * 1024).canAttemptModelLoad(gib))
        assertEquals(false, capable.canAttemptModelLoad(9 * gib))
    }

    @Test fun cpuPerformanceUsesAvailableCoresNotOnlyHighestFrequencyCluster() {
        val phone = capable.copy(cpuCores = 8, bigCores = 2)
        val options = phone.recommend(gib, "cpu-performance")
        assertEquals(6, options.threads)
        assertEquals(256, options.batchSize)
        assertEquals(0, options.gpuLayers)
    }

    @Test fun adaptiveProfileSpeedsLargeModelsWithoutEnablingGpu() {
        val options = capable.recommend(2 * gib, "auto")
        assertEquals(5, options.threads)
        assertEquals(256, options.batchSize)
        assertEquals(4096, options.contextSize)
        assertEquals(0, options.gpuLayers)
    }

    @Test fun adaptiveProfileRespectsBatterySaver() {
        val options = capable.copy(powerSave = true).recommend(2 * gib, "auto")
        assertEquals(3, options.threads)
        assertEquals(128, options.batchSize)
        assertEquals(0, options.gpuLayers)
    }

    @Test fun inaccessibleCoreFrequenciesStillProduceValidThreadCount() {
        val options = capable.copy(cpuCores = 1, bigCores = 0).recommend(mode = "balanced")
        assertEquals(1, options.threads)
    }
}
