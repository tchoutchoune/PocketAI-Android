package com.pocketai.app

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAdvisorTest {
    private fun profile(
        totalGiB: Long = 16,
        availableGiB: Long = 10,
        vulkan: Boolean = true,
    ) = HardwareProfile(
        totalRamBytes = totalGiB * GIB,
        availableRamBytes = availableGiB * GIB,
        cpuCores = 8,
        bigCores = 4,
        vulkanVersion = if (vulkan) "1.3.0" else null,
        powerSave = false,
        thermalStatus = PowerManager.THERMAL_STATUS_NONE,
    )

    @Test
    fun smallModelIsIdealOnHighMemoryPhone() {
        val advice = profile().adviseModel(500L * MIB)
        assertEquals(ModelFit.IDEAL, advice.fit)
        assertTrue(advice.contextTokens >= 2048)
        assertTrue(advice.gpuCandidate)
    }

    @Test
    fun modelNearAvailableMemoryIsAvoided() {
        val advice = profile(totalGiB = 8, availableGiB = 4).adviseModel(3500L * MIB)
        assertEquals(ModelFit.AVOID, advice.fit)
    }

    @Test
    fun noVulkanNeverClaimsGpuCandidate() {
        val advice = profile(vulkan = false).adviseModel(500L * MIB)
        assertFalse(advice.gpuCandidate)
    }

    @Test
    fun performanceModeCanRecommendLargerContext() {
        val p = profile()
        assertTrue(p.adviseModel(1500L * MIB, "performance").contextTokens >= p.adviseModel(1500L * MIB, "balanced").contextTokens)
    }

    companion object {
        private const val MIB = 1024L * 1024
        private const val GIB = 1024L * MIB
    }
}
