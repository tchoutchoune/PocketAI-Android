package com.pocketai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    @Test
    fun stripsMarkdownUrlsAndCodePayloads() {
        val spoken = SpeechText.prepare(
            "# Titre\nVoici **une réponse** avec [un lien](https://example.org/a?secret=x).\n" +
                "\`\`\`kotlin\nval secret = \"ne pas lire\"\n\`\`\`"
        )
        assertEquals("Titre Voici une réponse avec un lien . Bloc de code.", spoken)
    }

    @Test
    fun chunksLongSpeechAtNaturalBoundaries() {
        val text = (1..40).joinToString(" ") { "Phrase $it." }
        val chunks = SpeechText.chunks(text, 80)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 80 })
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test
    fun emptySpeechProducesNoChunks() {
        assertTrue(SpeechText.chunks("   ", 128).isEmpty())
    }
}
