package com.pocketai.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

/**
 * Minimal public Hugging Face browser for single-file GGUF models.
 * Results are pinned to the repository commit SHA and require an LFS SHA-256 before PocketAI will download them.
 */
class HuggingFaceRepository {
    suspend fun search(query: String, limit: Int = 12): List<ModelEntry> = withContext(Dispatchers.IO) {
        val clean = query.trim().take(80)
        require(clean.length >= 2) { "Saisis au moins deux caractères pour rechercher un modèle." }
        val encoded = URLEncoder.encode(clean, StandardCharsets.UTF_8.toString())
        val searchUrl = "https://huggingface.co/api/models?search=$encoded&filter=gguf&limit=8&full=true"
        val root = JSONArray(get(searchUrl))

        val candidates = mutableListOf<ModelEntry>()
        for (index in 0 until root.length()) {
            val model = root.optJSONObject(index) ?: continue
            val repoId = model.optString("id").takeIf { it.contains('/') } ?: continue
            if (model.optBoolean("private", false) || model.optBoolean("gated", false)) continue
            val revision = model.optString("sha").takeIf { it.matches(Regex("[a-fA-F0-9]{40,64}")) } ?: "main"
            val details = runCatching {
                JSONObject(get("https://huggingface.co/api/models/${encodePath(repoId)}?blobs=true"))
            }.getOrNull() ?: continue
            val pinnedRevision = details.optString("sha")
                .takeIf { it.matches(Regex("[a-fA-F0-9]{40,64}")) }
                ?: revision
            val siblings = details.optJSONArray("siblings") ?: continue
            val license = details.optJSONObject("cardData")?.optString("license").orEmpty()
            val downloads = details.optLong("downloads", model.optLong("downloads", 0L))

            val files = mutableListOf<ModelEntry>()
            for (fileIndex in 0 until siblings.length()) {
                val sibling = siblings.optJSONObject(fileIndex) ?: continue
                val filename = sibling.optString("rfilename")
                if (!filename.endsWith(".gguf", ignoreCase = true)) continue
                if (Regex("(?i)-\\d{5}-of-\\d{5}\\.gguf$").containsMatchIn(filename)) continue
                val lfs = sibling.optJSONObject("lfs") ?: continue
                val sha = lfs.optString("sha256").lowercase()
                val size = lfs.optLong("size", -1L)
                if (!sha.matches(Regex("[a-f0-9]{64}")) || size < 16) continue

                val pinned = if (pinnedRevision == "main") "main" else pinnedRevision
                val url = "https://huggingface.co/${encodePath(repoId)}/resolve/$pinned/${encodePath(filename)}?download=true"
                val quant = filename.substringBeforeLast(".gguf").substringAfterLast('-').uppercase()
                val description = buildString {
                    append(formatBytes(size))
                    if (quant.isNotBlank()) append(" · ").append(quant)
                    if (downloads > 0) append(" · repo ").append(downloads).append(" téléchargements")
                    if (license.isNotBlank()) append(" · licence ").append(license)
                    append(" · résultat Hugging Face public, compatibilité vérifiée au chargement")
                }
                files += ModelEntry(
                    id = "hf:$repoId:$filename",
                    title = "$repoId · ${filename.substringAfterLast('/')}",
                    url = url,
                    sizeBytes = size,
                    description = description,
                    sha256 = sha,
                    licenseUrl = "https://huggingface.co/${encodePath(repoId)}",
                )
            }

            candidates += files.sortedWith(
                compareBy<ModelEntry> { quantizationRank(it.title) }
                    .thenBy { it.sizeBytes }
            ).take(3)
            if (candidates.size >= limit) break
        }
        candidates.distinctBy { it.sha256 }.take(limit)
    }

    private fun get(address: String): String {
        val url = URL(address)
        require(url.protocol == "https" && url.host == "huggingface.co") { "Adresse Hugging Face invalide." }
        val connection = url.openConnection() as HttpsURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 18_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "PocketAI-Android/4.2")
        connection.setRequestProperty("Accept", "application/json")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Hugging Face répond HTTP $code.")
            return BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                val output = StringBuilder()
                var total = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    total += line.length
                    require(total <= MAX_JSON_CHARS) { "Réponse Hugging Face trop volumineuse." }
                    output.append(line)
                }
                output.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8.toString()).replace("+", "%20") }

    private fun quantizationRank(title: String): Int = when {
        Regex("(?i)Q4_K_M").containsMatchIn(title) -> 0
        Regex("(?i)Q5_K_M").containsMatchIn(title) -> 1
        Regex("(?i)Q4_K_S").containsMatchIn(title) -> 2
        Regex("(?i)Q4_0").containsMatchIn(title) -> 3
        Regex("(?i)Q5").containsMatchIn(title) -> 4
        Regex("(?i)Q8").containsMatchIn(title) -> 5
        else -> 10
    }

    private fun formatBytes(bytes: Long): String =
        if (bytes >= 1024L * 1024 * 1024) String.format(java.util.Locale.FRANCE, "%.2f Go", bytes / (1024.0 * 1024 * 1024))
        else String.format(java.util.Locale.FRANCE, "%.0f Mo", bytes / (1024.0 * 1024))

    private companion object {
        const val MAX_JSON_CHARS = 4 * 1024 * 1024
    }
}
