package com.pocketai.app

import java.net.URL
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/** OpenAI-compatible wire format; the server must actually host the configured model. */
internal object InferenceProtocol {
    fun baseUrl(value: String): URL {
        val url = runCatching { URL(value.trim().trimEnd('/')) }.getOrNull()
        require(url != null && url.protocol == "https" && url.host.isNotBlank() &&
            url.userInfo == null && url.query == null && url.ref == null && url.port in -1..65535 &&
            url.port != 0) { "Utilise l’URL HTTPS du serveur (ex. https://serveur.example/v1), sans clé dans l’URL." }
        return url
    }

    fun chatPayload(model: String, history: List<ChatMessage>, prompt: String, system: String,
                    maxTokens: Int, image: String? = null): JSONObject {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        // Bound history by total characters, not merely the number of messages.
        var budget = 24_000
        val selected = history.filter { it.content.isNotBlank() && !it.isStreaming }.takeLast(12)
            .asReversed().mapNotNull {
                if (budget <= 0) null else {
                    val content = (if (it.isUser) it.content else ResponseText.visible(it.content)).take(budget)
                    budget -= content.length
                    JSONObject().put("role", if (it.isUser) "user" else "assistant").put("content", content)
                }
            }.asReversed()
        selected.forEach(messages::put)
        val content: Any = if (image == null) prompt else JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", image)))
        messages.put(JSONObject().put("role", "user").put("content", content))
        return JSONObject().put("model", model).put("messages", messages)
            .put("max_tokens", maxTokens.coerceIn(64, 4096)).put("stream", false)
    }

    fun embeddings(result: JSONObject, count: Int): List<DoubleArray> {
        val data = result.getJSONArray("data")
        require(data.length() == count) { "Le serveur n’a pas renvoyé tous les embeddings." }
        val ordered = arrayOfNulls<DoubleArray>(count)
        var dimension = 0
        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            val index = item.getInt("index")
            require(index in ordered.indices && ordered[index] == null) { "Index d’embedding invalide." }
            val vector = item.getJSONArray("embedding")
            require(vector.length() in 1..8192) { "Dimension d’embedding invalide." }
            if (dimension == 0) dimension = vector.length()
            require(vector.length() == dimension) { "Dimensions d’embedding incohérentes." }
            ordered[index] = DoubleArray(dimension) { vector.getDouble(it).also { value ->
                require(value.isFinite()) { "Embedding non numérique." }
            } }
        }
        return ordered.map { requireNotNull(it) }
    }

    fun cosine(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size && a.isNotEmpty())
        var dot = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
        return if (aa == 0.0 || bb == 0.0) 0.0 else dot / sqrt(aa * bb)
    }
}
