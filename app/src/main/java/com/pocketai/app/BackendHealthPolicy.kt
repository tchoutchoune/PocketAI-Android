package com.pocketai.app

import com.arm.aichat.InferenceOptions

/**
 * Parses native backend diagnostics and turns real compatibility failures into a
 * conservative GPU-layer limit for future automatic loads.
 *
 * A limit is learned only from a failed logits probe or a runtime recovery. Memory
 * pressure/context backoff alone must never permanently quarantine the GPU.
 */
internal data class BackendHealth(
    val requestedVulkan: Boolean,
    val activeGpuLayers: Int,
    val logitsProbeFailures: Int,
    val runtimeRecoveries: Int,
    val lastDecodeCode: Int?,
    val fallback: String,
) {
    val activeBackend: String
        get() = if (activeGpuLayers > 0) "Vulkan + CPU" else "CPU"

    val compatibilityEvent: Boolean
        get() = requestedVulkan && (logitsProbeFailures > 0 || runtimeRecoveries > 0)

    val learnedGpuLimit: Int?
        get() = activeGpuLayers.takeIf { compatibilityEvent }
}

internal object BackendHealthPolicy {
    private val requested = Regex("""Requested:\s*(Vulkan|CPU);\s*active:\s*([^\n]+)""")
    private val layers = Regex("""GPU layers:\s*(\d+);\s*load attempts:\s*\d+;\s*logits probe failures:\s*(\d+);\s*fallback:\s*([^\n]+)""")
    private val recovery = Regex("""runtime recoveries:\s*(\d+)""")
    private val decode = Regex("""last decode code:\s*(-?\d+|not run)""")

    fun parse(diagnostics: String): BackendHealth? {
        val requestedMatch = requested.find(diagnostics) ?: return null
        val layerMatch = layers.find(diagnostics) ?: return null
        val recoveryMatch = recovery.find(diagnostics) ?: return null
        val decodeValue = decode.find(diagnostics)?.groupValues?.getOrNull(1)
        return BackendHealth(
            requestedVulkan = requestedMatch.groupValues[1] == "Vulkan",
            activeGpuLayers = layerMatch.groupValues[1].toIntOrNull() ?: return null,
            logitsProbeFailures = layerMatch.groupValues[2].toIntOrNull() ?: return null,
            runtimeRecoveries = recoveryMatch.groupValues[1].toIntOrNull() ?: return null,
            lastDecodeCode = decodeValue?.takeUnless { it == "not run" }?.toIntOrNull(),
            fallback = layerMatch.groupValues[3].trim(),
        )
    }

    /**
     * Automatic/balanced modes respect a learned compatibility ceiling.
     * Performance mode is an explicit retry override; CPU mode already requests zero layers.
     */
    fun applyLearnedLimit(options: InferenceOptions, learnedLimit: Int?, mode: String): InferenceOptions {
        if (learnedLimit == null || options.gpuLayers == 0 || mode.equals("performance", true)) return options
        return options.copy(gpuLayers = minOf(options.gpuLayers, learnedLimit.coerceAtLeast(0)))
    }

    fun statusLabel(health: BackendHealth): String = when {
        !health.requestedVulkan -> "CPU"
        health.runtimeRecoveries > 0 && health.activeGpuLayers == 0 -> "CPU · récupération automatique"
        health.runtimeRecoveries > 0 -> "Vulkan ${health.activeGpuLayers} couches · récupération automatique"
        health.logitsProbeFailures > 0 && health.activeGpuLayers == 0 -> "CPU · Vulkan incompatible"
        health.logitsProbeFailures > 0 -> "Vulkan ${health.activeGpuLayers} couches · profil compatible appris"
        health.activeGpuLayers > 0 -> "Vulkan ${health.activeGpuLayers} couches"
        else -> "CPU"
    }
}
