package com.pocketai.app

/**
 * Conservative streaming corruption detector.
 *
 * This intentionally does not try to judge language quality or hallucinations. It only catches
 * byte/character corruption patterns that a healthy tokenizer stream should not emit repeatedly.
 */
internal class OutputHealthGuard {
    private val tail = StringBuilder()

    fun observe(fragment: String): String? {
        if (fragment.isEmpty()) return null
        if ('\uFFFD' in fragment) return "invalid_utf8_replacement"
        if (fragment.any { it.code in 0..8 || it.code in 11..12 || it.code in 14..31 || it.code == 127 }) {
            return "unexpected_control_character"
        }

        tail.append(fragment)
        if (tail.length > MAX_TAIL) tail.delete(0, tail.length - MAX_TAIL)
        val visible = tail.filterNot(Char::isWhitespace)
        if (visible.length < MIN_SAMPLE) return null

        var previous: Char? = null
        var run = 0
        for (char in visible) {
            if (char == previous) run++ else {
                previous = char
                run = 1
            }
            if (run >= MAX_IDENTICAL_RUN && char !in ALLOWED_SEPARATOR_RUNS) {
                return "repeated_character"
            }
        }

        val recent = visible.takeLast(LOW_DIVERSITY_WINDOW)
        if (
            recent.length >= LOW_DIVERSITY_WINDOW &&
            recent.toSet().size <= 2 &&
            recent.none(Char::isLetterOrDigit) &&
            recent.any { it !in ALLOWED_SEPARATOR_RUNS }
        ) {
            return "low_diversity_symbol_stream"
        }
        return null
    }

    companion object {
        private const val MAX_TAIL = 96
        private const val MIN_SAMPLE = 12
        private const val MAX_IDENTICAL_RUN = 12
        private const val LOW_DIVERSITY_WINDOW = 32
        private val ALLOWED_SEPARATOR_RUNS = setOf('-', '=', '_', '*', '#')
    }
}

internal class DegenerateOutputException(val reasonCode: String) :
    IllegalStateException("Sortie locale anormale détectée ($reasonCode)")
