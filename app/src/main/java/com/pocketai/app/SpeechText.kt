package com.pocketai.app

/** Text preparation for local speech synthesis. Keeps prose, skips code payloads and noisy markup. */
internal object SpeechText {
    fun prepare(raw: String): String {
        var text = ResponseText.visible(raw)
        text = text.replace(
            Regex("(?s)\`\`\`.*?\`\`\`|~~~.*?~~~"),
            " Bloc de code. ",
        )
        text = text.replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
        text = text.replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        text = text.replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), " lien ")
        text = text.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
        text = text.replace(Regex("[*_\`>]+"), " ")
        return text.replace(Regex("\\s+"), " ").trim()
    }

    fun chunks(text: String, maximum: Int): List<String> {
        require(maximum >= 64)
        if (text.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        var remaining = text.trim()
        while (remaining.isNotEmpty()) {
            if (remaining.length <= maximum) {
                result += remaining
                break
            }
            val window = remaining.substring(0, maximum + 1)
            val preferred = listOf(
                window.lastIndexOf(". "),
                window.lastIndexOf("! "),
                window.lastIndexOf("? "),
                window.lastIndexOf("; "),
                window.lastIndexOf(", "),
                window.lastIndexOf(' '),
            ).maxOrNull() ?: -1
            val split = preferred.takeIf { it >= maximum / 2 }?.let { it + 1 } ?: maximum
            result += remaining.substring(0, split).trim()
            remaining = remaining.substring(split).trimStart()
        }
        return result.filter { it.isNotBlank() }
    }
}
