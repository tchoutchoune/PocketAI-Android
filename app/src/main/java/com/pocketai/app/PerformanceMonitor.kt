package com.pocketai.app

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.util.Locale

/**
 * Lightweight, permission-free process telemetry.
 *
 * Android does not expose a stable cross-device GPU utilisation percentage. PocketAI therefore
 * reports the backend/layers from llama.cpp diagnostics instead of presenting a fabricated value.
 */
class PerformanceMonitor(context: Context) {
    private val activityManager = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private var lastWallMs = SystemClock.elapsedRealtime()
    private var lastCpuMs = Process.getElapsedCpuTime()

    @Synchronized
    fun report(engineDiagnostics: String): String {
        val nowWall = SystemClock.elapsedRealtime()
        val nowCpu = Process.getElapsedCpuTime()
        val wallDelta = (nowWall - lastWallMs).coerceAtLeast(1)
        val cpuDelta = (nowCpu - lastCpuMs).coerceAtLeast(0)
        lastWallMs = nowWall
        lastCpuMs = nowCpu

        val cpuOfDevice = (cpuDelta * 100.0 / wallDelta / cores).coerceIn(0.0, 100.0)
        val memory = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
        val processPssKb = runCatching {
            activityManager?.getProcessMemoryInfo(intArrayOf(Process.myPid()))?.firstOrNull()?.totalPss ?: 0
        }.getOrDefault(0)
        val thermalStatus = runCatching { powerManager?.currentThermalStatus ?: 0 }.getOrDefault(0)
        val thermal = when {
            thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL -> "critique"
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> "élevé"
            thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> "modéré"
            thermalStatus >= PowerManager.THERMAL_STATUS_LIGHT -> "léger"
            else -> "normal"
        }

        fun gib(bytes: Long) = String.format(Locale.FRANCE, "%.2f", bytes / (1024.0 * 1024 * 1024))
        fun mib(kib: Int) = String.format(Locale.FRANCE, "%.0f", kib / 1024.0)

        val backendHealth = BackendHealthPolicy.parse(engineDiagnostics)
        return buildString {
            appendLine("CPU PocketAI ≈ ${String.format(Locale.FRANCE, "%.1f", cpuOfDevice)} % de la capacité totale ($cores cœurs)")
            appendLine("RAM PocketAI (PSS) ≈ ${mib(processPssKb)} Mo")
            appendLine("RAM système disponible : ${gib(memory.availMem)} / ${gib(memory.totalMem)} Go")
            appendLine("Thermique Android : $thermal")
            appendLine("Économie d’énergie : ${if (powerManager?.isPowerSaveMode == true) "active" else "inactive"}")
            appendLine()
            if (backendHealth != null) {
                appendLine("État moteur : ${BackendHealthPolicy.statusLabel(backendHealth)}")
                appendLine(
                    "Santé backend : " + when {
                        !backendHealth.requestedVulkan -> "CPU de référence"
                        backendHealth.runtimeRecoveries > 0 -> "récupération automatique déclenchée"
                        backendHealth.logitsProbeFailures > 0 -> "Vulkan adapté après échec de validation"
                        backendHealth.activeGpuLayers > 0 -> "Vulkan actif · validation technique réussie"
                        else -> "CPU actif"
                    }
                )
                appendLine()
            }
            appendLine("GPU : Android ne fournit pas de pourcentage d’utilisation portable et fiable.")
            appendLine("Les lignes natives ci-dessous indiquent le backend réellement actif, les replis et les débits.")
            appendLine()
            append(engineDiagnostics.ifBlank { "Charge un modèle pour afficher les métriques du moteur." })
        }
    }
}
