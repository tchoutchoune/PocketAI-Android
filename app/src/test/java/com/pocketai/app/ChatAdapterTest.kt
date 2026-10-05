package com.pocketai.app

import android.app.Activity
import android.content.Intent
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatAdapterTest {
    @Test fun sourceLinksAreClickableAndOpenTheBrowser() {
        val activity = activity()
        val adapter = ChatAdapter(activity, mutableListOf(ChatMessage(content = "[Source](https://example.org/page)", isUser = false)), {}, {})
        val holder = adapter.onCreateViewHolder(LinearLayout(activity), 0)
        adapter.onBindViewHolder(holder, 0)
        assertTrue(holder.body.movementMethod is LinkMovementMethod)
        val text = holder.body.text as Spanned
        text.getSpans(0, text.length, ClickableSpan::class.java).single().onClick(holder.body)
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://example.org/page", intent.data.toString())
    }

    @Test fun modelLinksCannotLaunchLocalFilesOrApplicationSchemes() {
        val activity = activity()
        for (url in listOf("file:///private/data", "javascript:alert(1)", "intent://example", "content://private/data")) {
            val adapter = ChatAdapter(activity, mutableListOf(ChatMessage(content = "[Lien]($url)", isUser = false)), {}, {})
            val holder = adapter.onCreateViewHolder(LinearLayout(activity), 0)
            adapter.onBindViewHolder(holder, 0)
            val text = holder.body.text as Spanned
            text.getSpans(0, text.length, ClickableSpan::class.java).forEach { it.onClick(holder.body) }
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }

    @Test fun hidesActionsUntilStreamingResponseIsComplete() {
        val activity = activity()
        val messages = mutableListOf(ChatMessage(content = "Début de réponse", isUser = false, isStreaming = true))
        val adapter = ChatAdapter(activity, messages, {}, {})
        val holder = adapter.onCreateViewHolder(LinearLayout(activity), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, holder.actions.visibility)
        assertFalse(holder.body.movementMethod is LinkMovementMethod)
        assertEquals("Début de réponse", holder.body.text.toString())

        messages[0] = messages[0].copy(isStreaming = false)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.actions.visibility)
        assertTrue(holder.body.movementMethod is LinkMovementMethod)
    }

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
        setTheme(R.style.Theme_PocketAI)
    }
}
