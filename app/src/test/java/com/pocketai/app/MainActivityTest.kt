package com.pocketai.app

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {
    private var controller: ActivityController<MainActivity>? = null
    private fun start(): MainActivity = Robolectric.buildActivity(MainActivity::class.java).setup().also { controller = it }.get()
    private fun views(activity: MainActivity): List<View> {
        fun collect(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { collect(view.getChildAt(it)) } else emptyList()
        return collect(activity.window.decorView)
    }
    private fun tabs(activity: MainActivity) = views(activity).filterIsInstance<TabLayout>().single()
    private fun button(activity: MainActivity, text: String) = views(activity).filterIsInstance<MaterialButton>().first { it.text.toString() == text }

    @After fun close() { controller?.pause()?.stop()?.destroy() }

    @Test fun initialScreenHasLocalChatAndFourSections() {
        val activity = start()
        val tabs = tabs(activity)
        assertEquals(4, tabs.tabCount)
        assertEquals(listOf("Chat", "Modèles", "Créer", "Réglages"), (0 until 4).map { tabs.getTabAt(it)?.text.toString() })
        assertFalse(ViewModelProvider(activity)[ChatViewModel::class.java].state.value.busy)
        assertNull(ViewModelProvider(activity)[ChatViewModel::class.java].state.value.modelName)
    }

    @Test fun webCannotBeEnabledWithoutExplicitCredentialConfiguration() {
        val activity = start()
        val toggle = views(activity).filterIsInstance<SwitchMaterial>().single()
        toggle.isChecked = true
        assertFalse(toggle.isChecked)
        assertFalse(ViewModelProvider(activity)[ChatViewModel::class.java].settings.webSearchEnabled)
        assertEquals(3, tabs(activity).selectedTabPosition)
    }

    @Test fun importingModelUsesSystemDocumentPickerWithoutStoragePermissions() {
        val activity = start()
        tabs(activity).getTabAt(1)!!.select()
        button(activity, "Importer un fichier GGUF").performClick()
        val started = shadowOf(activity).nextStartedActivityForResult
        assertNotNull(started)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.action)
        assertEquals("*/*", started.intent.type)
    }

    @Test fun imageCreationRequiresProviderBeforeAnyGeneration() {
        val activity = start()
        tabs(activity).getTabAt(2)!!.select()
        button(activity, "Générer une image").performClick()
        assertEquals(3, tabs(activity).selectedTabPosition)
        assertFalse(ViewModelProvider(activity)[ChatViewModel::class.java].state.value.busy)
    }

    @Test fun noModelDoesNotDiscardTypedQuestion() {
        val activity = start()
        val input = views(activity).filterIsInstance<TextInputEditText>().single()
        input.setText("Bonjour")
        button(activity, "Envoyer").performClick()
        assertEquals("Bonjour", input.text.toString())
        assertTrue(ViewModelProvider(activity)[ChatViewModel::class.java].state.value.messages.isEmpty())
    }

    @Test fun responseLengthSupportsLargeButBoundedGenerationBudgets() {
        val activity = start()
        val model = ViewModelProvider(activity)[ChatViewModel::class.java]
        model.maxTokens = 8192
        assertEquals(8192, model.maxTokens)
        model.maxTokens = 100_000
        assertEquals(8192, model.maxTokens)
    }
}
