package com.pocketai.app

enum class ModelFit(val label: String) {
    IDEAL("Idéal"),
    COMFORTABLE("Confortable"),
    DEMANDING("Exigeant"),
    AVOID("Déconseillé"),
}

data class ModelAdvice(
    val fit: ModelFit,
    val contextTokens: Int,
    val gpuCandidate: Boolean,
    val detail: String,
)

fun HardwareProfile.adviseModel(modelBytes: Long, mode: String = "balanced"): ModelAdvice {
    require(modelBytes >= 0)
    val options = recommend(modelBytes, mode)
    val usableRam = minOf(availableRamBytes.coerceAtLeast(0), totalRamBytes * 70 / 100)
    val reserveAfterWeights = usableRam - modelBytes - 512L * 1024 * 1024

    val fit = when {
        availableRamBytes <= 0L || totalRamBytes <= 0L -> ModelFit.DEMANDING
        modelBytes > availableRamBytes * 75 / 100 || reserveAfterWeights < 256L * 1024 * 1024 -> ModelFit.AVOID
        reserveAfterWeights >= 3L * 1024 * 1024 * 1024 -> ModelFit.IDEAL
        reserveAfterWeights >= 1024L * 1024 * 1024 -> ModelFit.COMFORTABLE
        else -> ModelFit.DEMANDING
    }

    val detail = when (fit) {
        ModelFit.IDEAL -> "Bonne marge RAM pour le modèle, le contexte et Android."
        ModelFit.COMFORTABLE -> "Devrait fonctionner avec une marge mémoire correcte."
        ModelFit.DEMANDING -> "Fonctionnera potentiellement, mais le contexte ou le GPU peuvent être réduits."
        ModelFit.AVOID -> "Risque élevé de pression mémoire ou d’échec de chargement dans l’état actuel."
    }

    return ModelAdvice(
        fit = fit,
        contextTokens = options.contextSize,
        gpuCandidate = options.gpuLayers > 0,
        detail = detail,
    )
}
