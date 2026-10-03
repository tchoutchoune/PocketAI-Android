package com.pocketai.app

internal data class CpuSample(val threads: Int, val prompt: Double, val generation: Double)
internal data class CpuThreadSelection(val generation: Int, val prompt: Int)

/** Prefer fewer workers when within 5% of the fastest measurement, independently per phase. */
internal object CpuTuning {
    fun select(samples: List<CpuSample>): CpuThreadSelection {
        val valid = samples.filter { it.threads in 1..32 && it.prompt.isFinite() && it.generation.isFinite() && it.prompt > 0 && it.generation > 0 }
        require(valid.isNotEmpty()) { "Le benchmark CPU n’a produit aucune mesure exploitable." }
        val fastestPrompt = valid.maxOf { it.prompt }
        val fastestGeneration = valid.maxOf { it.generation }
        return CpuThreadSelection(
            valid.filter { it.generation >= fastestGeneration * 0.95 }.minOf { it.threads },
            valid.filter { it.prompt >= fastestPrompt * 0.95 }.minOf { it.threads },
        )
    }
}
