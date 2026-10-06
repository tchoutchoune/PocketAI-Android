package com.pocketai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class LocalAttachmentTest {
    @Test
    fun decodesUtf8AndRemovesBom() {
        val text = TextDocumentDecoder.decode("\uFEFFBonjour éèà".toByteArray(Charsets.UTF_8))
        assertEquals("Bonjour éèà", text)
    }

    @Test
    fun fallsBackToWindows1252ForCommonFrenchTextFiles() {
        val original = "Prix : 12,50 € — résumé"
        val text = TextDocumentDecoder.decode(original.toByteArray(Charset.forName("windows-1252")))
        assertEquals(original, text)
    }

    @Test
    fun rejectsNullContainingBinaryPayload() {
        assertThrows(IllegalArgumentException::class.java) {
            TextDocumentDecoder.decode(byteArrayOf(1, 2, 0, 4, 5))
        }
    }

    @Test
    fun rejectsOversizedPayloadBeforePrompting() {
        val data = ByteArray(TextDocumentDecoder.MAX_BYTES + 1) { 'a'.code.toByte() }
        assertThrows(IllegalArgumentException::class.java) { TextDocumentDecoder.decode(data) }
    }

    @Test
    fun acceptsCodeAndStructuredText() {
        val content = """{"hello":"world"}\nSELECT * FROM users;\nval answer = 42"""
        val decoded = TextDocumentDecoder.decode(content.toByteArray())
        assertTrue(decoded.contains("SELECT"))
        assertTrue(decoded.contains("answer = 42"))
    }
}
