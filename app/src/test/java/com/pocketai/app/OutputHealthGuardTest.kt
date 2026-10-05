package com.pocketai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutputHealthGuardTest {
    @Test
    fun ignoresNormalFrenchAndCodeSeparators() {
        val guard = OutputHealthGuard()
        assertNull(guard.observe("Bonjour, voici une réponse normale."))
        assertNull(guard.observe("\n----------------\n"))
        assertNull(guard.observe("val answer = 42"))
    }

    @Test
    fun catchesRepeatedAtCorruptionAcrossChunks() {
        val guard = OutputHealthGuard()
        assertNull(guard.observe("@@@@"))
        assertNull(guard.observe("@@@@"))
        assertEquals("repeated_character", guard.observe("@@@@"))
    }

    @Test
    fun catchesReplacementCharacterImmediately() {
        assertEquals("invalid_utf8_replacement", OutputHealthGuard().observe("abc\uFFFDdef"))
    }

    @Test
    fun catchesUnexpectedControlCharacters() {
        assertEquals("unexpected_control_character", OutputHealthGuard().observe("hello\u0001world"))
    }

    @Test
    fun catchesLowDiversitySymbolStreamWithoutFlaggingSeparators() {
        val guard = OutputHealthGuard()
        assertEquals(
            "low_diversity_symbol_stream",
            guard.observe("@!@!@!@!@!@!@!@!@!@!@!@!@!@!@!@!"),
        )
        assertNull(OutputHealthGuard().observe("================================"))
    }
}
