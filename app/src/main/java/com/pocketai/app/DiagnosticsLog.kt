package com.pocketai.app

import android.content.Context
import android.os.Build
import java.io.File
import java.time.Instant

/** Device-local diagnostic events only. Callers must never pass conversation content. */
class DiagnosticsLog(context: Context) {
    private val directory = File(context.filesDir, "diagnostics").apply { mkdirs() }
    private val current = File(directory, "pocketai.log")
    private val previous = File(directory, "pocketai.previous.log")

    init {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        event("session app=$version android=${Build.VERSION.SDK_INT} " +
            "device=${Build.MANUFACTURER} ${Build.MODEL} abis=${Build.SUPPORTED_ABIS.joinToString()}")
        runCatching { event("hardware ${HardwareProfile.detect(context)}") }
    }

    @Synchronized
    fun event(message: String) {
        // Logging cannot break inference if storage is full/unavailable.
        runCatching {
            val line = "${Instant.now()} ${redact(message).replace('\n', ' ').take(MAX_EVENT_CHARS)}\n"
            val bytes = line.toByteArray(Charsets.UTF_8)
            if (current.length() + bytes.size > MAX_LOG_BYTES) {
                previous.delete()
                if (!current.renameTo(previous)) current.delete()
            }
            current.appendBytes(bytes)
        }
    }

    fun failure(label: String, throwable: Throwable) {
        // Exception messages can contain user input, HTTP bodies, or credentials.
        // Class and source frames provide a useful trace without recording those values.
        event("failure ${redact(label)}: ${throwable.javaClass.simpleName} " +
            throwable.stackTrace.take(8).joinToString(" <- ") {
                "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
            })
    }

    @Synchronized
    fun snapshot(): String = buildString {
        append("PocketAI diagnostics — conversations and API credentials are not recorded.\n")
        listOf(previous, current).filter { it.isFile }.forEach { file ->
            runCatching { append(redact(file.readText().takeLast(MAX_LOG_BYTES))) }
        }
    }

    companion object {
        private const val MAX_LOG_BYTES = 512 * 1024
        private const val MAX_EVENT_CHARS = 8192
        private val url = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)
        private val bearer = Regex("(?i)\\bBearer\\s+[^\\s,;]+")
        private val keyValue = Regex(
            "(?i)([\\\"']?(?:api[_-]?key|authorization|access[_-]?token|token|secret)[\\\"']?\\s*[:=]\\s*)[\\\"']?[^\\s,;\\\"']+[\\\"']?"
        )
        private val providerKey = Regex("\\b(?:sk-[A-Za-z0-9_-]{8,}|hf_[A-Za-z0-9]{8,})\\b")

        internal fun redact(message: String): String {
            val safeUrls = url.replace(message) { match ->
                match.value.substringBefore('?').substringBefore('#')
                    .replace(Regex("(https?://)[^/@]+@", RegexOption.IGNORE_CASE), "$1[redacted]@") +
                    if ('?' in match.value) "?[redacted]" else ""
            }
            return providerKey.replace(
                keyValue.replace(bearer.replace(safeUrls, "Bearer [redacted]")) {
                    "${it.groupValues[1]}[redacted]"
                },
                "[redacted]",
            )
        }
    }
}
