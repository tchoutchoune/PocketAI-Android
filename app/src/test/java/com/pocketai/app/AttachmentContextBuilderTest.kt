package com.pocketai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentContextBuilderTest {
    @Test fun retrievesRelevantSectionNearEndOfLargeDocument() {
        val filler = (1..20).joinToString("\n\n") { "Section $it : informations générales sans rapport avec la recherche." }
        val document = filler + "\n\nIncident critique : le code ALPHA-OMEGA-739 indique une saturation du cluster BeeGFS."
        val selected = AttachmentContextBuilder.select(
            document = document,
            query = "Que signifie ALPHA-OMEGA-739 pour BeeGFS ?",
            maxChars = 1800,
            chunkChars = 420,
            overlapChars = 40,
        )
        assertTrue(selected.text.contains("ALPHA-OMEGA-739"))
        assertTrue(selected.selectedChunks < selected.totalChunks)
    }

    @Test fun summaryIntentSamplesAcrossWholeDocument() {
        val document = (1..18).joinToString("\n\n") { index ->
            "Chapitre $index. " + "contenu-$index ".repeat(35)
        }
        val selected = AttachmentContextBuilder.select(
            document = document,
            query = "Fais un résumé global du document",
            maxChars = 2600,
            chunkChars = 500,
            overlapChars = 40,
        )
        assertTrue(selected.text.contains("Chapitre 1"))
        assertTrue(selected.text.contains("Chapitre 18") || selected.text.contains("contenu-18"))
        assertTrue(selected.truncated)
    }

    @Test fun respectsCharacterBudget() {
        val selected = AttachmentContextBuilder.select(
            document = "abcdef ".repeat(5000),
            query = "abcdef",
            maxChars = 1200,
            chunkChars = 500,
            overlapChars = 50,
        )
        assertTrue(selected.text.length <= 1200)
        assertTrue(selected.selectedChunks > 0)
    }

    @Test fun emptyDocumentProducesEmptySelection() {
        val selected = AttachmentContextBuilder.select("", "question", 1000)
        assertTrue(selected.text.isEmpty())
        assertFalse(selected.truncated)
    }
}
