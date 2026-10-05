package com.pocketai.app

import com.arm.aichat.InferenceOptions

internal data class LearnedBackendProfile(
    val gpuLayers: Int,
    val cpuOutput: Boolean,
)

/**
 * Parses native backend diagnostics and turns real compatibility failures into a
 * reusable compatibility profile for future automatic loads.
 *
 * Learning is intentionally limited to failed logits probes or runtime recovery.
 * Memory pressure/context backoff alone must never permanently quarantine the GPU.
 */
internal data class BackendHealth(
    val requestedVulkan: Boolean,
    val activeGpuLayers: Int,
    val outputOnCpu: Boolean,
    val logitsProbeFailures: Int,
    val runtimeRecoveries: Int,
    val lastDecodeCode: Int?,
    val fallback: String,
) {
    val activeBackend: String
        get() = if (activeGpuLayers > 0) "Vulkan + CPU" else "CPU"

    val compatibilityEvent: Boolean
        get() = requestedVulkan && (logitsProbeFailures > 0 || runtimeRecoveries > 0)

    val learnedProfile: LearnedBackendProfile?
        get() = if (compatibilityEvent) {
            LearnedBackendProfile(
                gpuLayers = activeGpuLayers,
                cpuOutput = activeGpuLayers > 0 && outputOnCpu,
            )
        } else null
}

internal object BackendHealthPolicy {
    private val requested = Regex("""Requested:\s*(Vulkan|CPU);\s*active:\s*([^\n]+)""")
    private val layers = Regex("""GPU layers:\s*(\d+);\s*load attempts:\s*\d+;\s*logits probe failures:\s*(\d+);\s*fallback:\s*([^\n]+)""")
    private val recovery = Regex("""runtime recoveries:\s*(\d+)""")
    private val decode = Regex("""last decode code:\s*(-?\d+|not run)""")
    private val output = Regex("""Output placement policy:\s*([^\n]+)""")

    fun parse(diagnostics: String): BackendHealth? {
        val requestedMatch = requested.find(diagnostics) ?: return null
        val layerMatch = layers.find(diagnostics) ?: return null
        val recoveryMatch = recovery.find(diagnostics) ?: return null
        val decodeValue = decode.find(diagnostics)?.groupValues?.getOrNull(1)
        val outputPolicy = output.find(diagnostics)?.groupValues?.getOrNull(1).orEmpty()
        return BackendHealth(
            requestedVulkan = requestedMatch.groupValues[1] == "Vulkan",
            activeGpuLayers = layerMatch.groupValues[1].toIntOrNull() ?: return null,
            outputOnCpu = outputPolicy.contains("CPU tensor overrides", ignoreCase = true),
            logitsProbeFailures = layerMatch.groupValues[2].toIntOrNull() ?: return null,
            runtimeRecoveries = recoveryMatch.groupValues[1].toIntOrNull() ?: return null,
            lastDecodeCode = decodeValue?.takeUnless { it == "not run" }?.toIntOrNull(),
            fallback = layerMatch.groupValues[3].trim(),
        )
    }

    /**
     * Automatic/balanced modes reuse the exact learned placement.
     * Performance mode is an explicit retry override; CPU mode already requests zero layers.
     */
    fun applyLearnedProfile(
        options: InferenceOptions,
        learned: LearnedBackendProfile?,
        mode: String,
    ): InferenceOptions {
        if (learned == null || options.gpuLayers == 0 || mode.equals("performance", true)) return options
        val layers = minOf(options.gpuLayers, learned.gpuLayers.coerceAtLeast(0))
        return options.copy(
            gpuLayers = layers,
            preferCpuOutput = layers > 0 && learned.cpuOutput,
        )
    }

    fun saferProfile(
        previous: LearnedBackendProfile?,
        learned: LearnedBackendProfile,
    ): LearnedBackendProfile {
        if (previous == null) return learned
        return when {
            learned.gpuLayers < previous.gpuLayers -> learned
            learned.gpuLayers > previous.gpuLayers -> previous
            else -> LearnedBackendProfile(
                gpuLayers = learned.gpuLayers,
                cpuOutput = learned.cpuOutput || previous.cpuOutput,
            )
        }
    }

    fun statusLabel(health: BackendHealth): String {
        val placement = if (health.activeGpuLayers > 0 && health.outputOnCpu) " · sortie CPU" else ""
        return when {
            !health.requestedVulkan -> "CPU"
            health.runtimeRecoveries > 0 && health.activeGpuLayers == 0 -> "CPU · récupération automatique"
            health.runtimeRecoveries > 0 -> "Vulkan ${health.activeGpuLayers} couches$placement · récupération automatique"
            health.logitsProbeFailures > 0 && health.activeGpuLayers == 0 -> "CPU · Vulkan incompatible"
            health.logitsProbeFailures > 0 -> "Vulkan ${health.activeGpuLayers} couches$placement · profil compatible appris"
            health.activeGpuLayers > 0 -> "Vulkan ${health.activeGpuLayers} couches$placement"
            else -> "CPU"
        }
    }
}
