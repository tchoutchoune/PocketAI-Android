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
        assertTrue(capable.recommend(gib, "performance").gpuLayers > 0)
    }

    @Test fun ampleMemoryScalesContextWithoutIgnoringMode() {
        assertEquals(4096, capable.recommend(gib, "balanced").contextSize)
        assertEquals(8192, capable.recommend(gib, "performance").contextSize)
    }

    @Test fun batterySaverLimitsCpuButKeepsSafeVulkanOffload() {
        val saving = capable.copy(powerSave = true).recommend(gib, "balanced")
        assertTrue(saving.threads <= 2)
        assertEquals(4, saving.gpuLayers)
        assertEquals(4096, saving.contextSize)
        assertEquals(64, saving.batchSize)
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

    @Test fun inaccessibleCoreFrequenciesStillProduceValidThreadCount() {
        val options = capable.copy(cpuCores = 1, bigCores = 0).recommend(mode = "balanced")
        assertEquals(1, options.threads)
    }
}
