package com.pocketai.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val isUser: Boolean,
    val isStreaming: Boolean = false
)

/** Stores conversation history only in app-private storage, never in diagnostic logs. */
class ConversationStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "conversation-v1.json"))
    private val lock = Any()

    fun load(): MutableList<ChatMessage> = synchronized(lock) {
        try {
            val input = file.openRead()
            val bytes = input.use { it.readNBytes(MAX_BYTES + 1) }
            if (bytes.size > MAX_BYTES) return@synchronized mutableListOf()
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            if (root.optInt("version") != 1) return@synchronized mutableListOf()
            val stored = root.optJSONArray("messages") ?: return@synchronized mutableListOf()
            val messages = mutableListOf<ChatMessage>()
            for (index in (stored.length() - MAX_MESSAGES).coerceAtLeast(0) until stored.length()) {
                val entry = stored.optJSONObject(index) ?: continue
                if (entry.opt("content") !is String || entry.opt("isUser") !is Boolean) continue
                val content = bounded(entry.getString("content"))
                if (content.isBlank()) continue
                val id = entry.optString("id").takeIf { it.isNotBlank() && it.length <= 128 }
                    ?: UUID.randomUUID().toString()
                messages += ChatMessage(id, content, entry.getBoolean("isUser"))
            }
            messages
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun save(messages: List<ChatMessage>) = synchronized(lock) {
        val retained = messages.takeLast(MAX_MESSAGES).mapNotNull { message ->
            val content = bounded(if (message.isUser) message.content else ResponseText.visible(message.content))
            if (content.isBlank()) null else JSONObject()
                .put("id", message.id.take(128))
                .put("content", content)
                .put("isUser", message.isUser)
        }.toMutableList()
        var bytes: ByteArray
        do {
            bytes = JSONObject().put("version", 1).put("messages", JSONArray(retained)).toString().toByteArray(Charsets.UTF_8)
            if (bytes.size <= MAX_BYTES) break
            retained.removeAt(0)
        } while (retained.isNotEmpty())
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    fun clear() = synchronized(lock) { file.delete() }

    private fun bounded(text: String): String {
        if (text.length <= MAX_MESSAGE_CHARS) return text
        val length = if (text[MAX_MESSAGE_CHARS - 1].isHighSurrogate()) MAX_MESSAGE_CHARS - 1 else MAX_MESSAGE_CHARS
        return text.substring(0, length)
    }

    private companion object {
        const val MAX_MESSAGES = 100
        const val MAX_MESSAGE_CHARS = 128 * 1024
        const val MAX_BYTES = 1024 * 1024
    }
}
