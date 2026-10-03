package com.pocketai.app

enum class ModelUse(val label: String) {
    CHAT("Chat"), CODE("Code"), VISION("Vision"), EMBEDDING("Documents / RAG"),
    TRANSCRIPTION("Transcription"), SPEECH("Voix"), IMAGE("Images")
}

data class HubModel(
    val id: String, val title: String, val repository: String, val use: ModelUse,
    val description: String, val vision: Boolean = false, val local: ModelEntry? = null,
) {
    val pageUrl: String get() = "https://huggingface.co/$repository"
    val supportsChat: Boolean get() = use in setOf(ModelUse.CHAT, ModelUse.CODE, ModelUse.VISION)
}

/** Curated text GGUF metadata, verified against HF LFS on 2026-10-03.
 * Vision/audio/diffusion require their own serving backend; they are never loaded as text GGUF.
 */
object ModelHub {
    val models: List<HubModel> = listOf(
        HubModel("qwen35-2b", "Qwen 3.5 · 2B", "Qwen/Qwen3.5-2B", ModelUse.CHAT,
            "Assistant rapide, rédaction, traduction. Vision via serveur.", vision = true, local = ModelEntry(
                id = "qwen35-2b", title = "Qwen 3.5 · 2B",
                url = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/f6d5376be1edb4d416d56da11e5397a961aca8ae/Qwen3.5-2B-Q4_K_M.gguf", sizeBytes = 1280835840L,
                description = "Assistant rapide, rédaction, traduction. Vision via serveur. Texte seul dans le moteur local.",
                sha256 = "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223", licenseUrl = "https://huggingface.co/Qwen/Qwen3.5-2B",
            )),
        HubModel("qwen3-4b-2507", "Qwen 3 · 4B Instruct 2507", "Qwen/Qwen3-4B-Instruct-2507", ModelUse.CHAT,
            "Chat direct sans raisonnement long, bon choix polyvalent.", vision = false, local = ModelEntry(
                id = "qwen3-4b-2507", title = "Qwen 3 · 4B Instruct 2507",
                url = "https://huggingface.co/bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF/resolve/ae44f08e1392f39c0e474af10c3ff8355c8b6688/Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf", sizeBytes = 2497280736L,
                description = "Chat direct sans raisonnement long, bon choix polyvalent. Texte seul dans le moteur local.",
                sha256 = "2fde00ce69dd4899c70d020845e2638353015bba0fdf161b3eb965f2bca4464e", licenseUrl = "https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507",
            )),
        HubModel("qwen35-4b", "Qwen 3.5 · 4B", "Qwen/Qwen3.5-4B", ModelUse.CHAT,
            "Analyse, code et documents. Plus exigeant que le 2B. Vision via serveur.", vision = true, local = ModelEntry(
                id = "qwen35-4b", title = "Qwen 3.5 · 4B",
                url = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/e87f176479d0855a907a41277aca2f8ee7a09523/Qwen3.5-4B-Q4_K_M.gguf", sizeBytes = 2740937888L,
                description = "Analyse, code et documents. Plus exigeant que le 2B. Vision via serveur. Texte seul dans le moteur local.",
                sha256 = "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4", licenseUrl = "https://huggingface.co/Qwen/Qwen3.5-4B",
            )),
        HubModel("ministral3-3b", "Ministral 3 · 3B", "mistralai/Ministral-3-3B-Instruct-2512", ModelUse.CHAT,
            "Assistant multilingue, français, extraction structurée. Vision via serveur.", vision = true, local = ModelEntry(
                id = "ministral3-3b", title = "Ministral 3 · 3B",
                url = "https://huggingface.co/mistralai/Ministral-3-3B-Instruct-2512-GGUF/resolve/eb599d408350ea2bb60452cb86be7c7b2fc28227/Ministral-3-3B-Instruct-2512-Q4_K_M.gguf", sizeBytes = 2147023008L,
                description = "Assistant multilingue, français, extraction structurée. Vision via serveur. Texte seul dans le moteur local.",
                sha256 = "9ed150d4367e68df0ac8e1540f6ddc65b42d0ee26378329d1ecbca60f93fc5f8", licenseUrl = "https://huggingface.co/mistralai/Ministral-3-3B-Instruct-2512",
            )),
        HubModel("smollm3-3b", "SmolLM3 · 3B", "HuggingFaceTB/SmolLM3-3B", ModelUse.CHAT,
            "Assistant compact, résumés, rédaction et outils.", vision = false, local = ModelEntry(
                id = "smollm3-3b", title = "SmolLM3 · 3B",
                url = "https://huggingface.co/bartowski/HuggingFaceTB_SmolLM3-3B-GGUF/resolve/86b3536ed1ca0dcb4716745642db4e6804bf3d32/HuggingFaceTB_SmolLM3-3B-Q4_K_M.gguf", sizeBytes = 1915305792L,
                description = "Assistant compact, résumés, rédaction et outils. Texte seul dans le moteur local.",
                sha256 = "519732558d5fa7420ab058e1b776dcfe73da78013c2fe59c7ca43c325ef89132", licenseUrl = "https://huggingface.co/HuggingFaceTB/SmolLM3-3B",
            )),
        HubModel("gemma4-e2b", "Gemma 4 · E2B", "google/gemma-4-E2B-it", ModelUse.CHAT,
            "Assistant compact (5,1B au total). Texte local, vision via serveur.", vision = true, local = ModelEntry(
                id = "gemma4-e2b", title = "Gemma 4 · E2B",
                url = "https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF/resolve/b4243c156154b6dca9324415f8c7ccc098b4aed1/gemma-4-E2B-it-Q4_0.gguf", sizeBytes = 2841481184L,
                description = "Assistant compact (5,1B au total). Texte local, vision via serveur. Texte seul dans le moteur local.",
                sha256 = "8e30dff3ac4c8434c49a7036fa15564bdbb6044e42bf04550bf1a096ad7e6a52", licenseUrl = "https://huggingface.co/google/gemma-4-E2B-it",
            )),
        HubModel("gemma4-e4b", "Gemma 4 · E4B", "google/gemma-4-E4B-it", ModelUse.CHAT,
            "Qualité accrue (8B au total). RAM importante, vision via serveur.", vision = true, local = ModelEntry(
                id = "gemma4-e4b", title = "Gemma 4 · E4B",
                url = "https://huggingface.co/ggml-org/gemma-4-E4B-it-GGUF/resolve/b8093469224f83f5c38f691eb906c380e9e63114/gemma-4-E4B-it-Q4_0.gguf", sizeBytes = 4590807392L,
                description = "Qualité accrue (8B au total). RAM importante, vision via serveur. Texte seul dans le moteur local.",
                sha256 = "a555b900214b477d8880e7832e0b8925e139b0159640036b09fe472b6f2097f2", licenseUrl = "https://huggingface.co/google/gemma-4-E4B-it",
            )),
        HubModel("qwen25-coder-15b", "Qwen 2.5 Coder · 1,5B", "Qwen/Qwen2.5-Coder-1.5B-Instruct", ModelUse.CODE,
            "Code rapide, scripts courts et complétion. Vérifier le code produit.", vision = false, local = ModelEntry(
                id = "qwen25-coder-15b", title = "Qwen 2.5 Coder · 1,5B",
                url = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/f86cb2c1fa58255f8052cc32aeede1b7482d4361/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf", sizeBytes = 1117320768L,
                description = "Code rapide, scripts courts et complétion. Vérifier le code produit. Texte seul dans le moteur local.",
                sha256 = "cc324af070c2ecbfd324a30884d2f951a7ff756aba85cb811a6ec436933bb046", licenseUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct",
            )),
        HubModel("qwen25-coder-7b", "Qwen 2.5 Coder · 7B", "Qwen/Qwen2.5-Coder-7B-Instruct", ModelUse.CODE,
            "Code, débogage et refactorisation. Plus lent et gourmand en RAM.", vision = false, local = ModelEntry(
                id = "qwen25-coder-7b", title = "Qwen 2.5 Coder · 7B",
                url = "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/13fb94bfda8c8cf22497dc57b78f391a9acb426a/qwen2.5-coder-7b-instruct-q4_k_m.gguf", sizeBytes = 4683073536L,
                description = "Code, débogage et refactorisation. Plus lent et gourmand en RAM. Texte seul dans le moteur local.",
                sha256 = "509287f78cb4d4cf6b3843734733b914b2c158e43e22a7f4bf5e963800894d3c", licenseUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct",
            )),
        HubModel("phi4-mini", "Phi 4 mini · 3,8B", "microsoft/Phi-4-mini-instruct", ModelUse.CHAT,
            "Raisonnement, mathématiques et code. Licence MIT.", vision = false, local = ModelEntry(
                id = "phi4-mini", title = "Phi 4 mini · 3,8B",
                url = "https://huggingface.co/bartowski/microsoft_Phi-4-mini-instruct-GGUF/resolve/7ff82c2aaa4dde30121698a973765f39be5288c0/microsoft_Phi-4-mini-instruct-Q4_K_M.gguf", sizeBytes = 2491874688L,
                description = "Raisonnement, mathématiques et code. Licence MIT. Texte seul dans le moteur local.",
                sha256 = "01999f17c39cc3074afae5e9c539bc82d45f2dd7faa3917c66cbef76fce8c0c2", licenseUrl = "https://huggingface.co/microsoft/Phi-4-mini-instruct",
            )),
        HubModel("qwen3-4b", "Qwen 3 · 4B", "Qwen/Qwen3-4B", ModelUse.CHAT,
            "Modèle historique avec raisonnement, souvent plus lent que Instruct 2507.", vision = false, local = ModelEntry(
                id = "qwen3-4b", title = "Qwen 3 · 4B",
                url = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/bc640142c66e1fdd12af0bd68f40445458f3869b/Qwen3-4B-Q4_K_M.gguf", sizeBytes = 2497280256L,
                description = "Modèle historique avec raisonnement, souvent plus lent que Instruct 2507. Texte seul dans le moteur local.",
                sha256 = "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5", licenseUrl = "https://huggingface.co/Qwen/Qwen3-4B",
            )),
        HubModel("qwen35-9b", "Qwen 3.5 · 9B", "Qwen/Qwen3.5-9B", ModelUse.CHAT, "Analyse et code sur serveur ; trop lourd pour un usage mobile fluide.", vision = true),
        HubModel("qwen38-27b", "Qwen 3.8 · 27B", "Qwen/Qwen3.8-27B", ModelUse.CHAT, "Assistant avancé et analyse de documents sur serveur.", vision = true),
        HubModel("gemma4-26b-a4b", "Gemma 4 · 26B A4B", "google/gemma-4-26B-A4B-it", ModelUse.CHAT, "Modèle à experts pour serveur ; la mémoire dépend des 26B totaux.", vision = true),
        HubModel("gemma4-31b", "Gemma 4 · 31B", "google/gemma-4-31B-it", ModelUse.CHAT, "Qualité sur serveur, mémoire et GPU importants.", vision = true),
        HubModel("qwen3-coder-next", "Qwen 3 Coder Next", "Qwen/Qwen3-Coder-Next", ModelUse.CODE, "Code et agents sur serveur ; 80B totaux, environ 3B actifs.", vision = false),
        HubModel("smolvlm2", "SmolVLM2 · 2,2B", "HuggingFaceTB/SmolVLM2-2.2B-Instruct", ModelUse.VISION, "Questions sur une photo via un serveur multimodal. Vidéo non intégrée.", vision = true),
        HubModel("qwen3-embedding", "Qwen 3 Embedding · 0,6B", "Qwen/Qwen3-Embedding-0.6B", ModelUse.EMBEDDING, "Recherche sémantique des passages de la pièce jointe, puis réponse du chat.", vision = false),
        HubModel("whisper-small", "Whisper small", "openai/whisper-small", ModelUse.TRANSCRIPTION, "Transcrire un fichier audio multilingue avec un serveur ASR.", vision = false),
        HubModel("kokoro", "Kokoro · 82M", "hexgrad/Kokoro-82M", ModelUse.SPEECH, "Synthèse vocale ; voix françaises ff_siwis et fm_gilles selon le serveur.", vision = false),
        HubModel("qwen-image", "Qwen Image · 2.1", "Qwen/Qwen-Image-2.1", ModelUse.IMAGE, "Génération et édition de photos via serveur diffusion compatible. Licence Qwen Research.", vision = false),
    )
    fun find(id: String): HubModel = models.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("Modèle inconnu.")
    val localCatalogue: List<ModelEntry> get() = models.mapNotNull { it.local }
}
