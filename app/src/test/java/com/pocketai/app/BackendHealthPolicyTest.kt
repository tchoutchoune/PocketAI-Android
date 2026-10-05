package com.pocketai.app

import com.arm.aichat.InferenceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendHealthPolicyTest {
    @Test
    fun parsesHealthyVulkanDiagnostics() {
        val health = BackendHealthPolicy.parse(
            """
            Requested: Vulkan; active: Vulkan + CPU
            GPU layers: 37; load attempts: 1; logits probe failures: 0; fallback: none
            Backend warnings: 0; errors: 0; last decode code: 0; phase: generation
            Output placement policy: backend default; runtime recoveries: 0
            """.trimIndent(),
        )!!

        assertTrue(health.requestedVulkan)
        assertEquals(37, health.activeGpuLayers)
        assertFalse(health.compatibilityEvent)
        assertNull(health.learnedGpuLimit)
        assertEquals("Vulkan 37 couches", BackendHealthPolicy.statusLabel(health))
    }

    @Test
    fun learnsReducedLayerCeilingAfterProbeFailure() {
        val health = BackendHealthPolicy.parse(
            """
            Requested: Vulkan; active: Vulkan + CPU
            GPU layers: 18; load attempts: 4; logits probe failures: 2; fallback: validated offload 37 -> 18
            Backend warnings: 2; errors: 0; last decode code: 0; phase: probe.generation
            Output placement policy: CPU tensor overrides; host op offload disabled; runtime recoveries: 0
            """.trimIndent(),
        )!!

        assertTrue(health.compatibilityEvent)
        assertEquals(18, health.learnedGpuLimit)
        assertEquals(
            18,
            BackendHealthPolicy.applyLearnedLimit(
                InferenceOptions(gpuLayers = 128),
                health.learnedGpuLimit,
                "balanced",
            ).gpuLayers,
        )
    }

    @Test
    fun learnsCpuOnlyAfterRuntimeRecoveryToCpu() {
        val health = BackendHealthPolicy.parse(
            """
            Requested: Vulkan; active: CPU
            GPU layers: 0; load attempts: 5; logits probe failures: 1; fallback: runtime recovery 9 -> 0 CPU
            Backend warnings: 1; errors: 1; last decode code: 0; phase: generation
            Output placement policy: CPU; runtime recoveries: 1
            """.trimIndent(),
        )!!

        assertEquals(0, health.learnedGpuLimit)
        assertEquals(
            0,
            BackendHealthPolicy.applyLearnedLimit(
                InferenceOptions(gpuLayers = 128),
                health.learnedGpuLimit,
                "balanced",
            ).gpuLayers,
        )
        assertEquals("CPU · récupération automatique", BackendHealthPolicy.statusLabel(health))
    }

    @Test
    fun performanceModeExplicitlyRetriesGpu() {
        val options = InferenceOptions(gpuLayers = 128)
        assertEquals(128, BackendHealthPolicy.applyLearnedLimit(options, 0, "performance").gpuLayers)
    }

    @Test
    fun contextOrMemoryFallbackDoesNotCreateCompatibilityLearning() {
        val health = BackendHealthPolicy.parse(
            """
            Requested: Vulkan; active: CPU
            GPU layers: 0; load attempts: 2; logits probe failures: 0; fallback: Vulkan model allocation failed; CPU selected
            Backend warnings: 0; errors: 0; last decode code: 0; phase: probe.generation
            Output placement policy: CPU; runtime recoveries: 0
            """.trimIndent(),
        )!!

        assertFalse(health.compatibilityEvent)
        assertNull(health.learnedGpuLimit)
    }

    @Test
    fun malformedDiagnosticsAreIgnored() {
        assertNull(BackendHealthPolicy.parse("GPU unknown"))
    }
}
