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
        assertFalse(health.outputOnCpu)
        assertFalse(health.compatibilityEvent)
        assertNull(health.learnedProfile)
        assertEquals("Vulkan 37 couches", BackendHealthPolicy.statusLabel(health))
    }

    @Test
    fun learnsExactCpuOutputPlacementAfterProbeFailure() {
        val health = BackendHealthPolicy.parse(
            """
            Requested: Vulkan; active: Vulkan + CPU
            GPU layers: 37; load attempts: 2; logits probe failures: 1; fallback: validated offload 37 -> 37 output tensors on CPU
            Backend warnings: 1; errors: 0; last decode code: 0; phase: probe.generation
            Output placement policy: CPU tensor overrides; host op offload disabled; runtime recoveries: 0
            """.trimIndent(),
        )!!

        assertTrue(health.compatibilityEvent)
        assertEquals(LearnedBackendProfile(37, true), health.learnedProfile)
        val applied = BackendHealthPolicy.applyLearnedProfile(
            InferenceOptions(gpuLayers = 128),
            health.learnedProfile,
            "balanced",
        )
        assertEquals(37, applied.gpuLayers)
        assertTrue(applied.preferCpuOutput)
        assertTrue(BackendHealthPolicy.statusLabel(health).contains("sortie CPU"))
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

        assertEquals(LearnedBackendProfile(0, false), health.learnedProfile)
        val applied = BackendHealthPolicy.applyLearnedProfile(
            InferenceOptions(gpuLayers = 128),
            health.learnedProfile,
            "balanced",
        )
        assertEquals(0, applied.gpuLayers)
        assertFalse(applied.preferCpuOutput)
        assertEquals("CPU · récupération automatique", BackendHealthPolicy.statusLabel(health))
    }

    @Test
    fun performanceModeExplicitlyRetriesDefaultGpuPlacement() {
        val options = InferenceOptions(gpuLayers = 128)
        val applied = BackendHealthPolicy.applyLearnedProfile(
            options,
            LearnedBackendProfile(37, true),
            "performance",
        )
        assertEquals(options, applied)
    }

    @Test
    fun saferProfileKeepsCpuOutputForEqualLayerCount() {
        assertEquals(
            LearnedBackendProfile(37, true),
            BackendHealthPolicy.saferProfile(
                LearnedBackendProfile(37, false),
                LearnedBackendProfile(37, true),
            ),
        )
        assertEquals(
            LearnedBackendProfile(18, true),
            BackendHealthPolicy.saferProfile(
                LearnedBackendProfile(37, true),
                LearnedBackendProfile(18, true),
            ),
        )
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
        assertNull(health.learnedProfile)
    }

    @Test
    fun malformedDiagnosticsAreIgnored() {
        assertNull(BackendHealthPolicy.parse("GPU unknown"))
    }
}
