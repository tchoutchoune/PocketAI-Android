package com.pocketai.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InferenceProtocolTest {
    @Test fun onlyExplicitHttpsEndpointsWithoutEmbeddedCredentialsAreAccepted() {
        listOf("http://host/v1", "https://user:secret@host/v1", "https://host/v1?key=secret",
            "https://host/v1#fragment", "file:///tmp/model", "https://host:0/v1").forEach {
            assertTrue(it, runCatching { InferenceProtocol.baseUrl(it) }.isFailure)
        }
        assertEquals("https://my-server.example:8443/v1", InferenceProtocol.baseUrl("https://my-server.example:8443/v1/").toString())
    }

    @Test fun visionPayloadUsesTheImageContentTypeAndSelectedServerAlias() {
        val history = listOf(ChatMessage(content = "ancienne question", isUser = true),
            ChatMessage(content = "réponse", isUser = false))
        val result = InferenceProtocol.chatPayload("vision-alias", history, "Que vois-tu ?", "system", 512,
            "data:image/jpeg;base64,aGVsbG8=")
        assertEquals("vision-alias", result.getString("model"))
        assertFalse(result.getBoolean("stream"))
        val messages = result.getJSONArray("messages")
        assertEquals(4, messages.length())
        val content = messages.getJSONObject(3).getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertTrue(content.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;"))
    }

    @Test fun textModelKeepsPlainTextAndBoundsConversationHistory() {
        val history = (0 until 20).map { ChatMessage(content = "x".repeat(8000), isUser = it % 2 == 0) }
        val result = InferenceProtocol.chatPayload("text-alias", history, "question", "system", 99999)
        val messages = result.getJSONArray("messages")
        assertEquals("question", messages.getJSONObject(messages.length() - 1).getString("content"))
        assertTrue((1 until messages.length() - 1).sumOf { messages.getJSONObject(it).getString("content").length } <= 24000)
        assertEquals(4096, result.getInt("max_tokens"))
    }

    @Test fun embeddingResponseOrderIsRestoredAndMalformedBatchesAreRejected() {
        fun item(index: Int, values: List<Double>) = JSONObject().put("index", index).put("embedding", JSONArray(values))
        val good = JSONObject().put("data", JSONArray().put(item(1, listOf(0.0, 1.0))).put(item(0, listOf(1.0, 0.0))))
        assertArrayEquals(doubleArrayOf(1.0, 0.0), InferenceProtocol.embeddings(good, 2)[0], 0.0)
        val duplicate = JSONObject().put("data", JSONArray().put(item(0, listOf(1.0))).put(item(0, listOf(1.0))))
        assertTrue(runCatching { InferenceProtocol.embeddings(duplicate, 2) }.isFailure)
        val mismatched = JSONObject().put("data", JSONArray().put(item(0, listOf(1.0))).put(item(1, listOf(1.0, 2.0))))
        assertTrue(runCatching { InferenceProtocol.embeddings(mismatched, 2) }.isFailure)
        assertTrue(runCatching { InferenceProtocol.embeddings(good, 3) }.isFailure)
        assertEquals(1.0, InferenceProtocol.cosine(doubleArrayOf(1.0, 0.0), doubleArrayOf(3.0, 0.0)), 0.001)
        assertEquals(0.0, InferenceProtocol.cosine(doubleArrayOf(0.0, 0.0), doubleArrayOf(3.0, 0.0)), 0.001)
    }
}
