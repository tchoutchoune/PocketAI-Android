package com.pocketai.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray
import org.json.JSONObject

internal fun interface InferenceTransport {
    suspend fun request(url: URL, key: String, body: ByteArray?, contentType: String, limit: Int): ByteArray
}

/** Explicit user-configured HTTPS endpoints; no redirects, no implicit hosted provider. */
internal class HttpsInferenceTransport : InferenceTransport {
    override suspend fun request(url: URL, key: String, body: ByteArray?, contentType: String, limit: Int): ByteArray =
        withContext(Dispatchers.IO) {
            withTimeout(180_000) {
                val connection = url.openConnection() as HttpsURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 120_000
                connection.requestMethod = if (body == null) "GET" else "POST"
                try {
                    coroutineScope {
                        val parent = currentCoroutineContext()
                        val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                            try { while (parent.isActive) delay(100) }
                            finally { if (!parent.isActive) connection.disconnect() }
                        }
                        try {
                            if (key.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $key")
                            if (body != null) {
                                connection.doOutput = true
                                connection.setRequestProperty("Content-Type", contentType)
                                connection.setFixedLengthStreamingMode(body.size)
                                connection.outputStream.use { output ->
                                    var start = 0
                                    while (start < body.size) {
                                        parent.ensureActive()
                                        val length = minOf(16_384, body.size - start)
                                        output.write(body, start, length); start += length
                                    }
                                }
                            }
                            parent.ensureActive()
                            val status = connection.responseCode
                            require(status in 200..299) {
                                when (status) {
                                    401, 403 -> "Accès au serveur refusé : vérifie la clé et les droits."
                                    404 -> "Modèle ou endpoint absent du serveur. Vérifie sa configuration."
                                    429 -> "Serveur saturé ou quota dépassé."
                                    in 300..399 -> "Redirection refusée. Configure directement l’URL HTTPS finale."
                                    else -> "Le serveur d’inférence a échoué (HTTP $status)."
                                }
                            }
                            require(connection.contentLengthLong <= limit) { "Réponse serveur trop volumineuse." }
                            connection.inputStream.use { input ->
                                val out = ByteArrayOutputStream()
                                val buffer = ByteArray(16_384)
                                while (true) {
                                    parent.ensureActive()
                                    val size = input.read(buffer)
                                    if (size < 0) break
                                    require(out.size() + size <= limit) { "Réponse serveur trop volumineuse." }
                                    out.write(buffer, 0, size)
                                }
                                out.toByteArray()
                            }
                        } finally { watcher.cancel() }
                    }
                } finally { connection.disconnect() }
            }
        }
}

internal class RemoteInferenceClient(
    private val context: Context, private val settings: OnlineSettings, private val artifacts: ArtifactStore,
    private val transport: InferenceTransport = HttpsInferenceTransport(),
) {
    private suspend fun request(id: String, path: String, body: ByteArray? = null,
                                type: String = "application/json", limit: Int = 4 * 1024 * 1024): ByteArray {
        val base = InferenceProtocol.baseUrl(settings.inferenceUrl(id))
        return transport.request(URL(base.toString().trimEnd('/') + path), settings.inferenceKey(id), body, type, limit)
    }
    private suspend fun json(id: String, path: String, payload: JSONObject? = null, limit: Int = 4 * 1024 * 1024): JSONObject {
        val bytes = request(id, path, payload?.toString()?.toByteArray(Charsets.UTF_8), limit = limit)
        return runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrElse {
            throw IllegalArgumentException("Réponse JSON du serveur invalide.")
        }
    }

    suspend fun checkModel(model: HubModel): Boolean {
        val list = json(model.id, "/models").getJSONArray("data")
        return (0 until list.length()).any { list.getJSONObject(it).optString("id") == settings.inferenceModel(model.id) }
    }

    suspend fun chat(model: HubModel, history: List<ChatMessage>, prompt: String, system: String,
                     maxTokens: Int, imageUri: Uri?): Pair<String, Int> {
        require(model.supportsChat) { "Ce modèle ne répond pas dans le chat." }
        val image = if (model.vision && imageUri != null) withContext(Dispatchers.IO) {
            "data:image/jpeg;base64," + Base64.encodeToString(imageBytes(imageUri), Base64.NO_WRAP)
        } else null
        val result = json(model.id, "/chat/completions", InferenceProtocol.chatPayload(
            settings.inferenceModel(model.id), history, prompt, system, maxTokens, image))
        val text = result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").opt("content")
        require(text is String && text.isNotBlank()) { "Le serveur n’a pas renvoyé de réponse texte." }
        return text to result.optJSONObject("usage")?.optInt("completion_tokens", 0).orZero().coerceAtLeast(0)
    }

    suspend fun retrieve(document: String, query: String): String {
        val id = "qwen3-embedding"
        val all = AttachmentContextBuilder.chunk(document, 1400, 140)
        // Sample the entire document when it exceeds the batch budget, including the end.
        val indices = if (all.size <= 64) all.indices.toList()
            else (0 until 64).map { it * (all.size - 1) / 63 }.distinct()
        val chunks = indices.map(all::get)
        if (chunks.isEmpty()) return ""
        val inputs = listOf("Instruct: Retrieve relevant passages for the question\nQuery: ${query.take(4000)}") + chunks
        val vectors = mutableListOf<DoubleArray>()
        for (batch in inputs.chunked(16)) {
            val result = json(id, "/embeddings", JSONObject().put("model", settings.inferenceModel(id))
                .put("input", JSONArray(batch)).put("encoding_format", "float"))
            vectors += InferenceProtocol.embeddings(result, batch.size)
        }
        require(vectors.all { it.size == vectors.first().size }) { "Dimensions incohérentes entre les lots." }
        val best = chunks.indices.sortedByDescending { InferenceProtocol.cosine(vectors.first(), vectors[it + 1]) }
            .take(4).sorted()
        return best.joinToString("\n\n") { "[Extrait ${indices[it] + 1}/${all.size}]\n${chunks[it]}" } +
            "\n[Recherche sémantique partielle : ${chunks.size}/${all.size} passages examinés, ${best.size} fournis.]"
    }

    suspend fun transcribe(uri: Uri): GeneratedArtifact = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri).orEmpty()
        val formats = mapOf("audio/mpeg" to "mp3", "audio/mp3" to "mp3", "audio/wav" to "wav",
            "audio/x-wav" to "wav", "audio/mp4" to "m4a", "audio/x-m4a" to "m4a",
            "audio/ogg" to "ogg", "audio/flac" to "flac", "audio/webm" to "webm", "video/mp4" to "mp4")
        val extension = formats[mime] ?: throw IllegalArgumentException("Choisis un audio MP3, WAV, M4A, OGG, FLAC ou WebM.")
        val audio = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(25 * 1024 * 1024 + 1) }
            ?: throw IllegalArgumentException("Fichier audio indisponible.")
        require(audio.isNotEmpty() && audio.size <= 25 * 1024 * 1024) { "Audio vide ou supérieur à 25 Mo." }
        val id = "whisper-small"
        val (body, type) = multipart(mapOf("model" to settings.inferenceModel(id), "response_format" to "json"),
            "file", "audio.$extension", mime, audio)
        val result = JSONObject(request(id, "/audio/transcriptions", body, type).toString(Charsets.UTF_8))
        val text = result.optString("text")
        require(text.isNotBlank()) { "Aucune transcription reçue." }
        artifacts.createDocument("transcription.txt", "text/plain", text)
    }

    suspend fun speak(text: String): GeneratedArtifact {
        require(text.isNotBlank() && text.length <= 4000) { "Saisis entre 1 et 4 000 caractères." }
        val payload = JSONObject().put("model", settings.inferenceModel("kokoro")).put("input", text)
            .put("voice", settings.speechVoice).put("response_format", "wav")
        val bytes = request("kokoro", "/audio/speech", payload.toString().toByteArray(), limit = 16 * 1024 * 1024)
        require(bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE") { "Le serveur n’a pas renvoyé un fichier WAV." }
        return artifacts.createBytes("kokoro.wav", "audio/wav", bytes)
    }

    suspend fun image(prompt: String, editUri: Uri? = null): GeneratedArtifact = withContext(Dispatchers.IO) {
        require(prompt.isNotBlank() && prompt.length <= 8000) { "Description vide ou trop longue." }
        val id = "qwen-image"
        val result = if (editUri == null) json(id, "/images/generations", JSONObject()
            .put("model", settings.inferenceModel(id)).put("prompt", prompt).put("n", 1)
            .put("size", "1024x1024").put("response_format", "b64_json"), limit = 24 * 1024 * 1024)
        else {
            val (body, type) = multipart(mapOf("model" to settings.inferenceModel(id), "prompt" to prompt,
                "response_format" to "b64_json"), "image", "image.jpg", "image/jpeg", imageBytes(editUri))
            JSONObject(request(id, "/images/edits", body, type, 24 * 1024 * 1024).toString(Charsets.UTF_8))
        }
        val encoded = result.getJSONArray("data").getJSONObject(0).optString("b64_json")
        require(encoded.isNotBlank()) { "Le serveur doit retourner l’image en b64_json." }
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 &&
            bounds.outMimeType in setOf("image/png", "image/jpeg", "image/webp")) { "Image serveur invalide." }
        val extension = when (bounds.outMimeType) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> "png" }
        artifacts.createBytes("qwen-image.$extension", bounds.outMimeType, bytes)
    }

    private fun imageBytes(uri: Uri): ByteArray {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
            if (longest > 1280) decoder.setTargetSize((info.size.width * 1280L / longest).toInt().coerceAtLeast(1),
                (info.size.height * 1280L / longest).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return try {
            val out = ByteArrayOutputStream()
            require(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) { "Conversion de la photo impossible." }
            require(out.size() <= 2 * 1024 * 1024) { "Photo trop volumineuse après réduction." }
            out.toByteArray()
        } finally { bitmap.recycle() }
    }

    private fun multipart(fields: Map<String, String>, field: String, filename: String, mime: String,
                          bytes: ByteArray): Pair<ByteArray, String> {
        val boundary = "PocketAI-${UUID.randomUUID()}"
        val out = ByteArrayOutputStream()
        fun write(value: String) { out.write(value.toByteArray(Charsets.UTF_8)) }
        fields.forEach { (name, value) ->
            write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
        }
        write("--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"$filename\"\r\nContent-Type: $mime\r\n\r\n")
        out.write(bytes); write("\r\n--$boundary--\r\n")
        return out.toByteArray() to "multipart/form-data; boundary=$boundary"
    }
    private fun Int?.orZero() = this ?: 0
}
