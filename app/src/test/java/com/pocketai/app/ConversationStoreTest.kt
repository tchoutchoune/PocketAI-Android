package com.pocketai.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConversationStoreTest {
    private lateinit var context: Context
    private lateinit var store: ConversationStore

    @Before fun createStore() {
        context = RuntimeEnvironment.getApplication()
        store = ConversationStore(context)
        store.clear()
    }

    @Test fun roundTripsMessagesAndStripsPrivateReasoning() {
        store.save(listOf(
            ChatMessage("user", "Bonjour", true),
            ChatMessage("assistant", "<think>private</think>**Bonjour**", false, true)
        ))
        val restored = store.load()
        assertEquals(2, restored.size)
        assertEquals("**Bonjour**", restored.last().content)
        assertFalse(restored.last().isStreaming)
        assertFalse(File(context.filesDir, "conversation-v1.json").readText().contains("private"))
    }

    @Test fun keepsNewestMessagesAndBoundsHistoryFile() {
        store.save((0..120).map { ChatMessage("$it", "Message $it", true) })
        assertEquals(100, store.load().size)
        assertEquals("21", store.load().first().id)
        store.save((0..120).map { ChatMessage("$it", "x".repeat(128 * 1024), true) })
        assertTrue(File(context.filesDir, "conversation-v1.json").length() <= 1024 * 1024)
        assertTrue(store.load().isNotEmpty())
        assertEquals("120", store.load().last().id)
    }

    @Test fun corruptedMissingOrOversizedHistoryDoesNotCrash() {
        assertTrue(store.load().isEmpty())
        val history = File(context.filesDir, "conversation-v1.json")
        history.writeText("{not json}")
        assertTrue(store.load().isEmpty())
        history.writeText("x".repeat(1024 * 1024 + 1))
        assertTrue(store.load().isEmpty())
    }

    @Test fun clearingHistoryRemovesStoredMessages() {
        store.save(listOf(ChatMessage(content = "Bonjour", isUser = true)))
        store.clear()
        assertTrue(store.load().isEmpty())
        assertFalse(File(context.filesDir, "conversation-v1.json").exists())
    }
}
