package com.pocketai.app

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import com.arm.aichat.InferenceOptions
import java.io.File
import java.util.Locale

/** Android's Vulkan declaration is a candidate; the native backend must confirm support. */
data class HardwareProfile(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val cpuCores: Int,
    val bigCores: Int,
    val vulkanVersion: String?,
    val powerSave: Boolean,
    val thermalStatus: Int,
) {
    val summary: String
        get() = "${String.format(Locale.FRANCE, "%.1f", totalRamBytes / GIB.toDouble())} Go RAM · " +
            "$cpuCores cœurs · ${if (vulkanVersion != null) "Vulkan $vulkanVersion disponible à vérifier" else "CPU"}" +
            if (powerSave) " · économie d’énergie" else ""

    /** Keep memory for Android, weights, KV cache, and (when used) GPU allocations. */
    fun recommend(modelBytes: Long = 0, mode: String = "balanced"): InferenceOptions {
        val normalizedMode = mode.lowercase(Locale.ROOT)
        val hot = thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        val warm = thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        val eco = normalizedMode == "eco" || powerSave || hot
        val performant = normalizedMode == "performance"
        val usableRam = minOf(availableRamBytes.coerceAtLeast(0), totalRamBytes * 65 / 100)
        val afterWeights = (usableRam - modelBytes.coerceAtLeast(0) - 384 * MIB).coerceAtLeast(0)
        val context = when {
            eco || afterWeights < 512 * MIB -> 1024
            performant && totalRamBytes >= 10 * GIB && afterWeights >= 3 * GIB -> 8192
            afterWeights >= 1536 * MIB -> 4096
            else -> 2048
        }
        val threads = when {
            eco -> minOf(2, cpuCores)
            warm -> minOf(3, bigCores.coerceAtLeast(1))
            performant -> minOf(8, bigCores.coerceAtLeast(1))
            else -> minOf(4, bigCores.coerceAtLeast(1))
        }.coerceAtLeast(1)
        val gpuLayers = when {
            normalizedMode == "cpu" || eco || warm || vulkanVersion == null -> 0
            afterWeights < 768 * MIB -> 0
            performant && afterWeights >= 1536 * MIB -> 16
            else -> 8
        }
        return InferenceOptions(
            threads = threads,
            contextSize = context,
            batchSize = if (eco || afterWeights < 512 * MIB) 64 else if (performant) 256 else 128,
            gpuLayers = gpuLayers,
            temperature = 0.6f,
        )
    }

    companion object {
        private const val MIB = 1024L * 1024
        private const val GIB = 1024L * MIB

        fun detect(context: Context): HardwareProfile {
            val memory = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memory)
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val frequencies = (0 until cores).map { core ->
                runCatching {
                    File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq")
                        .readText().trim().toLong()
                }.getOrNull()
            }
            val maximum = frequencies.filterNotNull().maxOrNull()
            val bigCores = if (maximum != null && frequencies.all { it != null }) {
                frequencies.count { it!! >= maximum * 85 / 100 }.coerceAtLeast(1)
            } else minOf(4, cores)
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val vulkan = context.packageManager.systemAvailableFeatures.orEmpty().firstOrNull {
                it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION
            }
            val version = vulkan?.version?.takeIf { it >= (1 shl 22) }?.let {
                "${it ushr 22}.${(it ushr 12) and 0x3ff}.${it and 0xfff}"
            }
            return HardwareProfile(
                totalRamBytes = memory.totalMem,
                availableRamBytes = memory.availMem,
                cpuCores = cores,
                bigCores = bigCores,
                vulkanVersion = version,
                powerSave = power?.isPowerSaveMode == true,
                thermalStatus = runCatching { power?.currentThermalStatus ?: 0 }.getOrDefault(0),
            )
        }
    }
}
