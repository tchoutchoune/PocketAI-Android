package com.pocketai.app

/** A complete fenced file, ready for the app's private artifact store. */
data class GeneratedTextFile(val name: String, val mimeType: String, val content: String)

/** Keeps model control tokens and private reasoning out of messages and exports. */
object ResponseText {
    private val controlTokens = setOf(
        "<|im_start|>", "<|im_end|>", "<|im_sep|>", "<|endoftext|>",
        "<|begin_of_text|>", "<|end_of_text|>", "<|eot_id|>",
        "<|start_header_id|>", "<|end_header_id|>", "<|finetune_right_pad_id|>",
        "<|assistant|>", "<|user|>", "<|system|>", "<|end|>", "<|endofturn|>"
    )

    fun visible(raw: String): String {
        val result = StringBuilder(raw.length)
        var index = 0
        var thinkingDepth = 0
        var fence: Fence? = null
        while (index < raw.length) {
            if (index == 0 || raw[index - 1] == '\n') {
                val nextFence = fenceAt(raw, index)
                if (nextFence != null) {
                    if (fence == null) fence = nextFence
                    else if (nextFence.character == fence.character && nextFence.length >= fence.length &&
                        raw.substring(nextFence.markerEnd, raw.indexOf('\n', nextFence.markerEnd).let { if (it < 0) raw.length else it }).isBlank()
                    ) fence = null
                    val lineEnd = raw.indexOf('\n', index).let { if (it < 0) raw.length else it + 1 }
                    if (thinkingDepth == 0) result.append(raw, index, lineEnd)
                    index = lineEnd
                    continue
                }
            }
            if (fence == null && raw[index] == '<') {
                if (raw.regionMatches(index, "<think>", 0, 7, ignoreCase = true)) {
                    thinkingDepth++
                    index += 7
                    continue
                }
                if (raw.regionMatches(index, "</think>", 0, 8, ignoreCase = true)) {
                    if (thinkingDepth > 0) thinkingDepth-- else result.setLength(0)
                    index += 8
                    continue
                }
                // A streaming chunk may end in the middle of a reasoning delimiter.
                if (raw.length - index < 8) {
                    val tail = raw.substring(index)
                    if ("<think>".startsWith(tail, true) || "</think>".startsWith(tail, true)) break
                }
                val token = controlTokens.firstOrNull { raw.startsWith(it, index) }
                if (token != null) {
                    index += token.length
                    // These two templates emit the assistant role immediately after the token.
                    if (token == "<|im_start|>" || token == "<|start_header_id|>") {
                        val role = listOf("assistant", "user", "system").firstOrNull { raw.startsWith(it, index) }
                        if (role != null) index += role.length
                    }
                    continue
                }
            }
            if (thinkingDepth == 0) result.append(raw[index])
            index++
        }
        return result.toString().trim()
    }

    /** Only complete code fences are downloadable; streaming fragments stay in the chat. */
    fun extractFiles(raw: String): List<GeneratedTextFile> {
        val text = visible(raw)
        val files = mutableListOf<GeneratedTextFile>()
        var index = 0
        var totalBytes = 0
        while (index < text.length && files.size < 8) {
            val lineEnd = text.indexOf('\n', index).let { if (it < 0) text.length else it }
            val opening = fenceAt(text, index)
            if (opening == null || lineEnd == text.length) {
                index = (lineEnd + 1).coerceAtMost(text.length)
                continue
            }
            val info = text.substring(opening.markerEnd, lineEnd).trim()
            val bodyStart = lineEnd + 1
            var closeStart = bodyStart
            var closing: Fence? = null
            while (closeStart < text.length) {
                val candidate = fenceAt(text, closeStart)
                val end = text.indexOf('\n', closeStart).let { if (it < 0) text.length else it }
                if (candidate != null && candidate.character == opening.character && candidate.length >= opening.length &&
                    text.substring(candidate.markerEnd, end).isBlank()
                ) {
                    closing = candidate
                    break
                }
                closeStart = (end + 1).coerceAtMost(text.length)
            }
            if (closing == null) break
            val content = text.substring(bodyStart, closeStart)
            val bytes = content.toByteArray(Charsets.UTF_8).size
            if (bytes <= MAX_FILE_BYTES && totalBytes + bytes <= MAX_TOTAL_BYTES) {
                val language = info.substringBefore(' ').substringBefore('\t').lowercase()
                val requestedName = Regex("(?:^|\\s)(?:file|filename)\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s]+))", RegexOption.IGNORE_CASE)
                    .find(info)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
                val name = requestedName?.takeIf(::safeName) ?: "fichier-${files.size + 1}.${extension(language)}"
                files += GeneratedTextFile(name, mimeType(name.substringAfterLast('.', "txt").lowercase()), content)
                totalBytes += bytes
            }
            index = text.indexOf('\n', closeStart).let { if (it < 0) text.length else it + 1 }
        }
        return files
    }

    private data class Fence(val character: Char, val length: Int, val markerEnd: Int)

    private fun fenceAt(text: String, lineStart: Int): Fence? {
        var marker = lineStart
        while (marker < text.length && text[marker] == ' ' && marker - lineStart < 4) marker++
        if (marker - lineStart > 3 || marker >= text.length || text[marker] !in "`~") return null
        val character = text[marker]
        var end = marker
        while (end < text.length && text[end] == character) end++
        return if (end - marker >= 3) Fence(character, end - marker, end) else null
    }

    private fun safeName(name: String): Boolean = name.length in 1..100 &&
        name != "." && name != ".." && !name.contains('/') && !name.contains('\\') &&
        name.none { it.isISOControl() } && !name.startsWith('.')

    private fun extension(language: String): String = when (language) {
        "python", "py" -> "py"
        "javascript", "js" -> "js"
        "typescript", "ts" -> "ts"
        "markdown", "md" -> "md"
        "bash", "shell", "sh" -> "sh"
        "kotlin", "kt" -> "kt"
        "java", "json", "html", "css", "xml", "csv", "sql", "svg", "c", "cpp", "yaml", "yml", "go" -> language
        "rust", "rs" -> "rs"
        else -> "txt"
    }

    private fun mimeType(extension: String): String = when (extension) {
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "csv" -> "text/csv"
        "md" -> "text/markdown"
        "svg" -> "image/svg+xml"
        "xml" -> "application/xml"
        "js" -> "text/javascript"
        "css" -> "text/css"
        else -> "text/plain"
    }

    private const val MAX_FILE_BYTES = 512 * 1024
    private const val MAX_TOTAL_BYTES = 1024 * 1024
}
