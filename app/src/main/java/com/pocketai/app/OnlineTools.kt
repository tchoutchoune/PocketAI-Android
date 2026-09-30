package com.pocketai.app

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

data class WebSource(val title: String, val url: String, val snippet: String) {
    fun markdownCitation(index: Int): String {
        val label = title.replace(Regex("[\\r\\n\\t]+"), " ")
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace(Regex("([\\\\\\[\\]*_`~|])")) { "\\${it.value}" }
        val link = URI(url).toASCIIString().replace("(", "%28").replace(")", "%29")
        return "[${index.coerceAtLeast(1)}. $label](<$link>)"
    }
}
class OnlineToolException(message: String) : IOException(message)

/** All calls require an explicit UI action and the provider's own user-configured key. */
class OnlineTools(private val settings: OnlineSettings, private val artifacts: ArtifactStore) {
    suspend fun search(query: String): List<WebSource> = withContext(Dispatchers.IO) {
        if (!settings.webSearchEnabled) throw OnlineToolException("Activez la recherche Internet pour cette conversation.")
        val key = requireKey(settings.braveApiKey, "Brave Search")
        val cleanQuery = query.trim().take(2000)
        if (cleanQuery.isEmpty()) throw OnlineToolException("Saisissez une recherche.")
        val url = URL("https://api.search.brave.com/res/v1/web/search?q=${URLEncoder.encode(cleanQuery, "UTF-8")}&count=5&search_lang=fr")
        val response = requestJson(url, "GET", mapOf("X-Subscription-Token" to key))
        parseWebSources(response)
    }

    suspend fun generateImage(prompt: String): GeneratedArtifact = withContext(Dispatchers.IO) {
        val key = requireKey(settings.imageApiKey, "Images")
        val model = settings.imageModel.trim()
        if (model.isEmpty() || model.length > 200) throw OnlineToolException("Renseignez le modèle du fournisseur d'images.")
        val url = imageEndpoint(settings.imageBaseUrl)
        val payload = imagePayload(prompt, model)
        val response = requestJson(url, "POST", mapOf("Authorization" to "Bearer $key"), payload,
            MAX_IMAGE_BYTES * 4 / 3 + 1024 * 1024, timeoutMs = 180000)
        val item = response.optJSONArray("data")?.optJSONObject(0)
            ?: throw OnlineToolException("Le fournisseur n'a pas renvoyé d'image.")
        val encoded = item.optString("b64_json", "")
        if (encoded.isNotEmpty()) {
            if (encoded.length > MAX_IMAGE_BYTES * 4 / 3 + 8) throw OnlineToolException("Image trop volumineuse (20 Mo maximum).")
            val bytes = try { Base64.decode(encoded, Base64.DEFAULT) } catch (error: IllegalArgumentException) {
                throw OnlineToolException("L'image reçue est invalide.")
            }
            if (bytes.size > MAX_IMAGE_BYTES) throw OnlineToolException("Image trop volumineuse (20 Mo maximum).")
            val mime = detectedMediaType(bytes, false)
                ?: throw OnlineToolException("Le fournisseur a renvoyé un format d'image non pris en charge.")
            artifacts.createBytes("PocketAI-image.${extensionFor(mime)}", mime, bytes)
        } else {
            val remoteUrl = item.optString("url", "")
            if (remoteUrl.isBlank()) throw OnlineToolException("Le fournisseur n'a pas renvoyé d'image téléchargeable.")
            downloadMedia(remoteUrl, video = false)
        }
    }

    /** fal.ai's documented queue protocol; no video is claimed ready before a real file arrives. */
    suspend fun generateVideo(prompt: String, onProgress: (String) -> Unit = {}): GeneratedArtifact = withContext(Dispatchers.IO) {
        val key = requireKey(settings.falApiKey, "fal.ai")
        val model = settings.videoModel.trim()
        if (!model.matches(Regex("fal-ai/[a-zA-Z0-9._/-]+")) || model.contains("..") || model.length > 180) {
            throw OnlineToolException("Identifiant fal.ai invalide. Exemple : fal-ai/wan/v2.2-a14b/text-to-video")
        }
        var cancelUrl: URL? = null
        var completed = false
        try {
            withTimeout(VIDEO_TIMEOUT_MS) {
                onProgress("Envoi au fournisseur fal.ai…")
                val auth = mapOf("Authorization" to "Key $key")
                val submitted = requestJson(URL("https://queue.fal.run/$model"), "POST", auth, videoPayload(prompt, model))
                // Authenticated follow-up requests never leave fal's queue host.
                cancelUrl = falQueueUrl(submitted.optString("cancel_url"))
                val statusUrl = falQueueUrl(submitted.optString("status_url"))
                val responseUrl = falQueueUrl(submitted.optString("response_url"))
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val status = requestJson(statusUrl, "GET", auth)
                    when (status.optString("status")) {
                        "IN_QUEUE" -> {
                            val position = status.optInt("queue_position", -1)
                            onProgress(if (position >= 0) "En attente chez fal.ai (position $position)…" else "En attente chez fal.ai…")
                        }
                        "IN_PROGRESS" -> onProgress("Génération de la vidéo chez fal.ai…")
                        "COMPLETED" -> {
                            completed = true
                            val result = requestJson(responseUrl, "GET", auth)
                            val videoUrl = result.optJSONObject("video")?.optString("url", "")
                            if (videoUrl.isNullOrBlank()) throw OnlineToolException("Le fournisseur n'a pas renvoyé de vidéo.")
                            onProgress("Téléchargement de la vidéo…")
                            return@withTimeout downloadMedia(videoUrl, video = true)
                        }
                        "FAILED", "CANCELLED", "ERROR" -> throw OnlineToolException("La génération vidéo a échoué chez fal.ai. Consultez votre tableau de bord fournisseur.")
                        else -> throw OnlineToolException("État de génération vidéo inconnu. Vérifiez le modèle fal.ai configuré.")
                    }
                    delay(3000)
                }
                @Suppress("UNREACHABLE_CODE")
                throw OnlineToolException("Génération interrompue.")
            }
        } catch (error: TimeoutCancellationException) {
            throw OnlineToolException("La génération dépasse 10 minutes. Une annulation a été demandée à fal.ai.")
        } finally {
            val target = cancelUrl
            if (!completed && target != null) {
                // Cancellation is best effort: fal documents that running jobs may still finish/bill.
                withContext(NonCancellable) {
                    try {
                        withTimeout(6000) { requestJson(target, "PUT", mapOf("Authorization" to "Key $key"), timeoutMs = 4000) }
                    } catch (_: Exception) { /* Preserve the original failure/cancellation, never expose credentials. */ }
                }
            }
        }
    }

    private suspend fun requestJson(
        url: URL,
        method: String,
        headers: Map<String, String>,
        payload: JSONObject? = null,
        maxBytes: Int = 2 * 1024 * 1024,
        timeoutMs: Int = 30000
    ): JSONObject = guarded {
        val connection = openConnection(url, method, timeoutMs)
        try {
            withDisconnectOnCancellation(connection) {
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                connection.setRequestProperty("Accept", "application/json")
                if (payload != null) {
                    val data = payload.toString().toByteArray(Charsets.UTF_8)
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setFixedLengthStreamingMode(data.size)
                    connection.outputStream.use { it.write(data) }
                }
                val code = connection.responseCode
                if (code !in 200..299) throw statusError(code)
                val contentType = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
                if (contentType != "application/json" && !contentType.endsWith("+json")) {
                    throw OnlineToolException("Le fournisseur a renvoyé une réponse inattendue.")
                }
                if (connection.contentLengthLong > maxBytes) throw OnlineToolException("Réponse du fournisseur trop volumineuse.")
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > maxBytes) throw OnlineToolException("Réponse du fournisseur trop volumineuse.")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                try { JSONObject(bytes.toString(Charsets.UTF_8)) } catch (error: Exception) {
                    throw OnlineToolException("Réponse JSON du fournisseur invalide.")
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadMedia(remoteUrl: String, video: Boolean): GeneratedArtifact = guarded {
        val file = artifacts.temporaryFile()
        var keep = false
        try {
            var url = safeHttpsUrl(remoteUrl)
            var redirects = 0
            while (true) {
                // Provider API keys are intentionally absent from artifact download requests.
                val connection = openConnection(url, "GET", 60000)
                try {
                    val result = withDisconnectOnCancellation(connection) {
                        val status = connection.responseCode
                        if (status in setOf(301, 302, 303, 307, 308)) {
                            if (++redirects > 3) throw OnlineToolException("Trop de redirections pendant le téléchargement.")
                            val location = connection.getHeaderField("Location")
                                ?: throw OnlineToolException("Lien de téléchargement invalide.")
                            url = safeHttpsUrl(URL(url, location).toString())
                            null
                        } else {
                            if (status !in 200..299) throw statusError(status)
                            val maxBytes = if (video) MAX_VIDEO_BYTES else MAX_IMAGE_BYTES
                            if (connection.contentLengthLong > maxBytes) throw OnlineToolException("Média trop volumineux (${maxBytes / 1024 / 1024} Mo maximum).")
                            val declaredMime = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
                            val allowedTypes = if (video) VIDEO_TYPES else IMAGE_TYPES
                            if (declaredMime.isNotEmpty() && declaredMime !in allowedTypes && declaredMime != "application/octet-stream") {
                                throw OnlineToolException("Le lien renvoyé n'est pas un média pris en charge.")
                            }
                            var size = 0L
                            connection.inputStream.use { input -> file.outputStream().use { output ->
                                val buffer = ByteArray(16384)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    size += count
                                    if (size > maxBytes) throw OnlineToolException("Média trop volumineux (${maxBytes / 1024 / 1024} Mo maximum).")
                                    output.write(buffer, 0, count)
                                }
                            } }
                            val prefix = file.inputStream().use { input -> ByteArray(256).let { data ->
                                val count = input.read(data)
                                if (count < 0) ByteArray(0) else data.copyOf(count)
                            } }
                            val mime = detectedMediaType(prefix, video)
                                ?: throw OnlineToolException("Le média reçu est invalide ou son format n'est pas pris en charge.")
                            if (declaredMime in allowedTypes && declaredMime != mime) throw OnlineToolException("Le type du média reçu est incohérent.")
                            artifacts.adoptDownloaded(file, mime, "PocketAI-${if (video) "video" else "image"}.${extensionFor(mime)}")
                        }
                    }
                    if (result != null) {
                        keep = true
                        return@guarded result
                    }
                } finally {
                    connection.disconnect()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw OnlineToolException("Téléchargement interrompu.")
        } finally {
            if (!keep) file.delete()
        }
    }

    private suspend fun <T> withDisconnectOnCancellation(connection: HttpsURLConnection, action: suspend () -> T): T = coroutineScope {
        // HttpsURLConnection is blocking; a separate cancellation watcher closes the socket promptly.
        val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { connection.disconnect() }
        }
        try { action() } finally { watcher.cancel() }
    }

    private suspend fun openConnection(url: URL, method: String, timeoutMs: Int): HttpsURLConnection {
        currentCoroutineContext().ensureActive()
        validatePublicHost(url)
        return (url.openConnection() as HttpsURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            useCaches = false
            setRequestProperty("User-Agent", "PocketAI-Android/4")
        }
    }

    private suspend fun <T> guarded(action: suspend () -> T): T = try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (error: OnlineToolException) {
        throw error
    } catch (error: SocketTimeoutException) {
        currentCoroutineContext().ensureActive()
        throw OnlineToolException("Le fournisseur met trop de temps à répondre. Vérifiez l'état du service avant de relancer une génération.")
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        // No server body, URL, prompt, key or unsanitized transport exception reaches debug logs.
        throw OnlineToolException("Connexion au fournisseur impossible. Vérifiez Internet, l'adresse HTTPS et les paramètres du service.")
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        private const val MAX_VIDEO_BYTES = 150 * 1024 * 1024
        private const val VIDEO_TIMEOUT_MS = 10 * 60 * 1000L
        private val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
        private val VIDEO_TYPES = setOf("video/mp4", "video/webm")

        internal fun imagePayload(prompt: String, model: String): JSONObject {
            val cleanPrompt = requirePrompt(prompt)
            val payload = JSONObject().put("model", model).put("prompt", cleanPrompt).put("n", 1).put("size", "1024x1024")
            if (model.startsWith("dall-e", ignoreCase = true)) payload.put("response_format", "b64_json")
            return payload
        }

        internal fun videoPayload(prompt: String, model: String): JSONObject {
            val payload = JSONObject().put("prompt", requirePrompt(prompt))
            // Wan-specific options are sent only to the documented Wan endpoint.
            if (model == "fal-ai/wan/v2.2-a14b/text-to-video") {
                payload.put("num_frames", 81).put("frames_per_second", 16).put("resolution", "480p")
                    .put("aspect_ratio", "16:9").put("enable_safety_checker", true)
                    .put("enable_output_safety_checker", true)
            }
            return payload
        }

        internal fun parseWebSources(response: JSONObject): List<WebSource> {
            val results = response.optJSONObject("web")?.optJSONArray("results") ?: return emptyList()
            return buildList {
                for (index in 0 until minOf(results.length(), 20)) {
                    val result = results.optJSONObject(index) ?: continue
                    val url = result.optString("url", "")
                    if (url.length > 4096 || url.any { it.isISOControl() }) continue
                    val uri = try { URI(url) } catch (_: Exception) { continue }
                    if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) continue
                    val title = plainSnippet(result.optString("title", "")).take(300)
                    val snippet = plainSnippet(result.optString("description", "")).take(2000)
                    add(WebSource(title, uri.toASCIIString(), snippet))
                    if (size >= 5) break
                }
            }
        }

        internal fun imageEndpoint(baseUrl: String): URL {
            val base = safeHttpsUrl(baseUrl.trim().trimEnd('/'))
            if (base.query != null || base.ref != null) throw OnlineToolException("L'adresse du fournisseur doit être une base HTTPS sans paramètres.")
            return safeHttpsUrl("${base.toExternalForm()}/images/generations")
        }

        internal fun falQueueUrl(value: String): URL {
            val url = safeHttpsUrl(value)
            if (!url.host.equals("queue.fal.run", ignoreCase = true) || url.port !in listOf(-1, 443) || !url.path.startsWith("/fal-ai/")) {
                throw OnlineToolException("Le fournisseur a renvoyé une adresse de suivi non autorisée.")
            }
            return url
        }

        internal fun safeHttpsUrl(value: String): URL {
            val uri = try { URI(value) } catch (_: Exception) { throw OnlineToolException("Adresse HTTPS invalide.") }
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null || uri.port !in -1..65535 || uri.port == 0) {
                throw OnlineToolException("Seules les adresses HTTPS sans identifiants intégrés sont autorisées.")
            }
            val host = uri.host.lowercase().trimEnd('.')
            if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
                throw OnlineToolException("L'adresse doit désigner un fournisseur Internet public.")
            }
            return uri.toURL()
        }

        private fun validatePublicHost(url: URL) {
            safeHttpsUrl(url.toExternalForm())
            val addresses = InetAddress.getAllByName(url.host)
            if (addresses.isEmpty() || addresses.any { address ->
                    val bytes = address.address
                    address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                        address.isSiteLocalAddress || address.isMulticastAddress ||
                        (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) ||
                        (bytes.size == 4 && (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xff) in 64..127)
                }) throw OnlineToolException("L'adresse de téléchargement n'est pas un fournisseur Internet public.")
        }

        internal fun detectedMediaType(bytes: ByteArray, video: Boolean): String? {
            fun starts(vararg expected: Int) = bytes.size >= expected.size && expected.indices.all { (bytes[it].toInt() and 255) == expected[it] }
            fun ascii(offset: Int, count: Int) = if (bytes.size >= offset + count) bytes.copyOfRange(offset, offset + count).toString(Charsets.US_ASCII) else ""
            return if (video) when {
                ascii(4, 4) == "ftyp" && ascii(8, 4) in setOf("isom", "iso2", "iso5", "iso6", "mp41", "mp42", "avc1", "M4V ", "MSNV", "dash", "qt  ") -> "video/mp4"
                starts(0x1a, 0x45, 0xdf, 0xa3) && bytes.toString(Charsets.ISO_8859_1).contains("webm") -> "video/webm"
                else -> null
            } else when {
                starts(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "image/png"
                starts(0xff, 0xd8, 0xff) -> "image/jpeg"
                ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> "image/webp"
                ascii(0, 6) in setOf("GIF87a", "GIF89a") -> "image/gif"
                else -> null
            }
        }

        private fun extensionFor(mime: String) = when (mime) {
            "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"
            "image/gif" -> "gif"; "video/mp4" -> "mp4"; "video/webm" -> "webm"
            else -> throw OnlineToolException("Format média non pris en charge.")
        }

        private fun plainSnippet(text: String) = text.replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

        private fun requirePrompt(prompt: String): String {
            val clean = prompt.trim()
            if (clean.isBlank() || clean.length > 8000) throw OnlineToolException("Saisissez une description de 1 à 8 000 caractères.")
            return clean
        }

        private fun requireKey(key: String, provider: String): String {
            if (key.isBlank()) throw OnlineToolException("Ajoutez votre clé $provider dans les réglages Internet.")
            if (key.length > 4096 || key.any { it.isISOControl() }) throw OnlineToolException("Format de clé fournisseur invalide.")
            return key
        }

        private fun statusError(code: Int) = OnlineToolException(when (code) {
            401, 403 -> "Accès fournisseur refusé (HTTP $code). Vérifiez la clé et les droits du modèle."
            429 -> "Limite du fournisseur atteinte (HTTP 429). Vérifiez votre quota ou réessayez plus tard."
            400, 404, 422 -> "Le fournisseur refuse cette requête (HTTP $code). Vérifiez le modèle et l'adresse dans les réglages."
            in 500..599 -> "Le fournisseur est temporairement indisponible (HTTP $code)."
            in 300..399 -> "Redirection du fournisseur refusée pour protéger votre clé (HTTP $code)."
            else -> "Échec du service en ligne (HTTP $code)."
        })
    }
}
