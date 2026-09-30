package com.pocketai.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtifactStoreTest {
    @Test fun filenamesStayInsideThePrivateArtifactDirectory() {
        for (name in listOf("../../secret", "/storage/download/file.txt", "..", "\u0000name", "a\\b.txt", "", "x".repeat(300))) {
            val safe = ArtifactStore.safeFileName(name)
            assertFalse(safe.contains('/'))
            assertFalse(safe.contains('\\'))
            assertFalse(safe.any { it.isISOControl() })
            assertTrue(safe.isNotBlank())
            assertTrue(safe.length <= 100)
            assertNotEquals("..", safe)
        }
    }

    @Test fun htmlExportNeverExecutesCodeFromAnAssistantResponse() {
        val result = ArtifactStore.encodeText("<script>alert('x')</script> & text", ExportFormat.HTML)
        assertFalse(result.contains("<script>"))
        assertTrue(result.contains("&lt;script&gt;"))
        assertTrue(result.contains("&amp; text"))
    }

    @Test fun jsonExportRoundTripsQuotesNewlinesAndUnicode() {
        val text = "Bonjour 👋\n\"quoted\"\\path"
        assertEquals(text, JSONObject(ArtifactStore.encodeText(text, ExportFormat.JSON)).getString("response"))
    }

    @Test fun csvQuotesMultilineContentAndNeutralizesFormulaInjection() {
        assertEquals("response\r\n\"Hello \"\"world\"\"\nnext\"\r\n", ArtifactStore.encodeText("Hello \"world\"\nnext", ExportFormat.CSV))
        assertEquals("response\r\n\"'=HYPERLINK(\"\"https://evil.example\"\")\"\r\n", ArtifactStore.encodeText("=HYPERLINK(\"https://evil.example\")", ExportFormat.CSV))
    }
}
