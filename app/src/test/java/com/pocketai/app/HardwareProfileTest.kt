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

    @Test fun powerSavingAndOverheatingReducePerformanceProfile() {
        val saving = capable.copy(powerSave = true).recommend(gib, "performance")
        val hot = capable.copy(thermalStatus = PowerManager.THERMAL_STATUS_SEVERE).recommend(gib, "performance")
        listOf(saving, hot).forEach {
            assertTrue(it.threads <= 2)
            assertEquals(0, it.gpuLayers)
            assertEquals(1024, it.contextSize)
            assertEquals(64, it.batchSize)
        }
    }

    @Test fun lowAvailableMemoryUsesConservativeContextAndCpu() {
        val options = capable.copy(availableRamBytes = gib).recommend(gib, "performance")
        assertEquals(1024, options.contextSize)
        assertEquals(64, options.batchSize)
        assertEquals(0, options.gpuLayers)
    }

    @Test fun inaccessibleCoreFrequenciesStillProduceValidThreadCount() {
        val options = capable.copy(cpuCores = 1, bigCores = 0).recommend(mode = "balanced")
        assertEquals(1, options.threads)
    }

    @Test fun modernHighMemoryPhoneGetsLargerAdaptiveContexts() {
        assertEquals(4096, capable.recommend(gib, "balanced").contextSize)
        assertEquals(16384, capable.recommend(gib, "performance").contextSize)

        val flagship = capable.copy(totalRamBytes = 16 * gib, availableRamBytes = 14 * gib)
        assertEquals(32768, flagship.recommend(gib, "performance").contextSize)
    }

    @Test fun performanceProfileRequestsMaximumGpuOffloadAndLargerBatch() {
        val options = capable.recommend(gib, "performance")
        assertEquals(256, options.gpuLayers)
        assertEquals(512, options.batchSize)
        assertTrue(options.threads <= capable.cpuCores)
    }
}
