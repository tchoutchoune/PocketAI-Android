package com.pocketai.app

import java.util.Locale
import kotlin.math.max

data class AttachmentContextSelection(
    val text: String,
    val selectedChunks: Int,
    val totalChunks: Int,
    val truncated: Boolean,
)

/**
 * Small local retrieval layer for attachments.
 *
 * The source may be much larger than the active LLM context. We therefore select
 * representative/relevant chunks before the prompt reaches llama.cpp. No document
 * content leaves the device and no embedding model is required.
 */
internal object AttachmentContextBuilder {
    private val word = Regex("[\\p{L}\\p{N}_-]{3,}")
    private val summaryHints = listOf(
        "résum", "resume", "synth", "overview", "summary", "points clés", "points cles",
        "principal", "global", "ensemble", "document", "fichier",
    )
    private val stopWords = setOf(
        "avec", "dans", "pour", "des", "les", "une", "que", "qui", "sur", "est", "sont",
        "this", "that", "with", "from", "the", "and", "what", "which", "document", "fichier",
        "analyse", "analyser", "explique", "expliquer", "faire", "peux", "peut",
    )

    fun select(
        document: String,
        query: String,
        maxChars: Int,
        chunkChars: Int = 1400,
        overlapChars: Int = 140,
    ): AttachmentContextSelection {
        if (document.isBlank() || maxChars <= 0) {
            return AttachmentContextSelection("", 0, 0, document.isNotBlank())
        }
        val safeChunk = chunkChars.coerceIn(400, max(400, maxChars))
        val safeOverlap = overlapChars.coerceIn(0, safeChunk / 3)
        val chunks = chunk(document, safeChunk, safeOverlap)
        if (chunks.isEmpty()) return AttachmentContextSelection("", 0, 0, false)

        val normalizedQuery = query.lowercase(Locale.ROOT)
        val terms = word.findAll(normalizedQuery)
            .map { it.value }
            .filterNot { it in stopWords }
            .distinct()
            .take(24)
            .toList()
        val summaryIntent = summaryHints.any { normalizedQuery.contains(it) }

        val ranked = if (summaryIntent || terms.isEmpty()) {
            evenlySample(chunks.indices.toList(), estimateChunkCount(maxChars, safeChunk))
        } else {
            chunks.indices
                .map { index ->
                    val lower = chunks[index].lowercase(Locale.ROOT)
                    var score = 0.0
                    for (term in terms) {
                        var from = 0
                        var matches = 0
                        while (true) {
                            val hit = lower.indexOf(term, from)
                            if (hit < 0) break
                            matches++
                            from = hit + term.length
                        }
                        if (matches > 0) score += 3.0 + matches.coerceAtMost(6) * 1.5
                    }
                    if (normalizedQuery.length >= 8 && lower.contains(normalizedQuery)) score += 20.0
                    index to score
                }
                .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })
                .let { scored ->
                    // A focused query should not consume the prompt budget with
                    // unrelated zero-score chunks after the relevant passage was found.
                    // If nothing matches, keep one deterministic fallback chunk so
                    // callers still get useful document context instead of an empty prompt.
                    val relevant = scored.filter { it.second > 0.0 }
                    (if (relevant.isNotEmpty()) relevant else scored.take(1)).map { it.first }
                }
        }

        val selected = mutableListOf<Int>()
        var used = 0
        for (index in ranked) {
            if (index in selected) continue
            val prefixCost = 28
            val chunk = chunks[index]
            if (selected.isNotEmpty() && used + prefixCost + chunk.length > maxChars) continue
            selected += index
            used += prefixCost + chunk.length
            if (used >= maxChars - 128) break
        }
        if (selected.isEmpty()) selected += 0
        selected.sort()

        val out = StringBuilder()
        for (index in selected) {
            val header = "[Extrait ${index + 1}/${chunks.size}]\n"
            val room = maxChars - out.length
            if (room <= header.length) break
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(header)
            val remaining = maxChars - out.length
            if (remaining <= 0) break
            val chunk = chunks[index]
            if (chunk.length <= remaining) out.append(chunk)
            else {
                val end = safeBoundary(chunk, remaining)
                out.append(chunk.substring(0, end))
                break
            }
        }

        return AttachmentContextSelection(
            text = out.toString().take(maxChars),
            selectedChunks = selected.size,
            totalChunks = chunks.size,
            truncated = selected.size < chunks.size || document.length > out.length,
        )
    }

    private fun chunk(document: String, chunkChars: Int, overlapChars: Int): List<String> {
        val normalized = document.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isBlank()) return emptyList()
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < normalized.length) {
            var end = minOf(normalized.length, start + chunkChars)
            if (end < normalized.length) {
                val windowStart = max(start + chunkChars / 2, start)
                val breakAt = sequenceOf(
                    normalized.lastIndexOf("\n\n", end - 1),
                    normalized.lastIndexOf("\n", end - 1),
                    normalized.lastIndexOf(". ", end - 1),
                    normalized.lastIndexOf(' ', end - 1),
                ).firstOrNull { it >= windowStart }
                if (breakAt != null) end = breakAt + 1
            }
            end = safeBoundary(normalized, end)
            if (end <= start) end = minOf(normalized.length, start + chunkChars)
            chunks += normalized.substring(start, end).trim()
            if (end >= normalized.length) break
            start = max(0, end - overlapChars)
            if (start < normalized.length && normalized[start].isLowSurrogate()) start++
        }
        return chunks.filter { it.isNotBlank() }
    }

    private fun evenlySample(indices: List<Int>, count: Int): List<Int> {
        if (indices.size <= count) return indices
        if (count <= 1) return listOf(indices.first())
        return (0 until count).map { slot ->
            val position = slot.toDouble() * (indices.size - 1) / (count - 1)
            indices[position.toInt().coerceIn(indices.indices)]
        }.distinct()
    }

    private fun estimateChunkCount(maxChars: Int, chunkChars: Int): Int =
        (maxChars / (chunkChars + 32)).coerceIn(1, 12)

    private fun safeBoundary(text: String, requested: Int): Int {
        var end = requested.coerceIn(0, text.length)
        if (end > 0 && end < text.length && text[end].isLowSurrogate()) end--
        return end
    }
}
