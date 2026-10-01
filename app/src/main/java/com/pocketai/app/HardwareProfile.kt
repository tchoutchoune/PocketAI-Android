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

    /**
     * GGUF weights are memory-mapped by llama.cpp, so comparing the whole file size
     * to ActivityManager.availMem is invalid and rejects usable models. Refuse only
     * obviously unsafe cases; native context allocation provides the final fallback.
     */
    fun canAttemptModelLoad(modelBytes: Long): Boolean =
        modelBytes > 0 &&
            availableRamBytes >= 384 * MIB &&
            modelBytes <= totalRamBytes * 70 / 100

    /** Keep memory for Android, weights, KV cache, and (when used) GPU allocations. */
    fun recommend(modelBytes: Long = 0, mode: String = "balanced"): InferenceOptions {
        val normalizedMode = mode.lowercase(Locale.ROOT)
        val hot = thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        val warm = thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        val eco = normalizedMode == "eco" || hot
        val cpuPerformance = normalizedMode == "cpu-performance"
        val vulkanExperimental = normalizedMode == "performance" || normalizedMode == "vulkan"
        val available = availableRamBytes.coerceAtLeast(0)
        val hugeModel = modelBytes > totalRamBytes * 55 / 100

        val context = when {
            eco || hugeModel || available < 768 * MIB -> 1024
            vulkanExperimental && totalRamBytes >= 10 * GIB && available >= 2 * GIB -> 8192
            totalRamBytes >= 8 * GIB && available >= 1536 * MIB -> 4096
            totalRamBytes >= 6 * GIB && available >= 1024 * MIB -> 2048
            else -> 1024
        }

        // Do not equate "performance cores" with the only useful llama.cpp workers.
        // On modern ARM SoCs the additional cores materially accelerate prompt
        // prefill. Explicit CPU Performance is allowed to override battery saver;
        // the thermal listener still reduces concurrency if the device heats up.
        val threads = when {
            eco -> minOf(2, cpuCores)
            cpuPerformance -> minOf(6, cpuCores)
            vulkanExperimental -> minOf(4, cpuCores)
            // Android battery saver should reduce load without making a 4B model
            // practically unusable. The explicit Autonomy profile remains 2 threads.
            powerSave -> minOf(3, cpuCores)
            warm -> minOf(3, cpuCores)
            else -> minOf(4, cpuCores)
        }.coerceAtLeast(1)

        val gpuLayers = when {
            // Vulkan remains explicit/experimental because Adreno 840 has produced
            // corrupted logits in real-device testing. CPU modes never offload.
            !vulkanExperimental || eco || hot || vulkanVersion == null -> 0
            available < 1024 * MIB || totalRamBytes < 6 * GIB -> 0
            totalRamBytes >= 10 * GIB -> 16
            else -> 8
        }

        return InferenceOptions(
            threads = threads,
            contextSize = context,
            batchSize = when {
                context <= 1024 || eco -> 64
                cpuPerformance || vulkanExperimental -> 256
                powerSave -> 128
                else -> 128
            },
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
