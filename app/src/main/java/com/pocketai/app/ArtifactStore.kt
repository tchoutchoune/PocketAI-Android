package com.pocketai.app

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

data class GeneratedArtifact(val file: File, val mimeType: String, val displayName: String)

enum class ExportFormat(val extension: String, val mimeType: String, val label: String) {
    TEXT("txt", "text/plain", "Texte"),
    MARKDOWN("md", "text/markdown", "Markdown"),
    HTML("html", "text/html", "HTML"),
    JSON("json", "application/json", "JSON"),
    CSV("csv", "text/csv", "CSV"),
    PDF("pdf", "application/pdf", "PDF")
}

/** Generated files are private until the user explicitly exports or shares one. */
class ArtifactStore(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "generated").apply { mkdirs() }

    fun createDocument(name: String, mime: String, content: String): GeneratedArtifact {
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_BYTES) { "Document trop volumineux (8 Mo maximum)." }
        require(mime.matches(MIME_PATTERN)) { "Type de fichier invalide." }
        return createBytes(name, mime, content.toByteArray(Charsets.UTF_8))
    }

    fun exportText(text: String, format: ExportFormat, name: String = "PocketAI-reponse"): GeneratedArtifact {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_BYTES) { "Réponse trop volumineuse." }
        val displayName = safeFileName("$name.${format.extension}")
        if (format == ExportFormat.PDF) return createPdf(displayName, text)
        return createDocument(displayName, format.mimeType, encodeText(text, format))
    }

    /** SAF URI comes from the system document picker. No broad storage permission is needed. */
    fun save(uri: Uri, artifact: GeneratedArtifact) {
        require(artifact.file.canonicalFile.parentFile == directory.canonicalFile) { "Fichier exportable invalide." }
        val stream = appContext.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("Impossible d'ouvrir le fichier choisi.")
        stream.use { output -> artifact.file.inputStream().use { it.copyTo(output) } }
    }

    /** Private artifact history survives process restarts; temporary/incomplete downloads stay hidden. */
    @Synchronized
    fun list(): List<GeneratedArtifact> {
        val now = System.currentTimeMillis()
        directory.listFiles().orEmpty().filter { it.name.endsWith(".part") && now - it.lastModified() > 24 * 60 * 60 * 1000L }
            .forEach { it.delete() }
        return artifactFiles().sortedByDescending { it.lastModified() }.take(MAX_STORED_ARTIFACTS).map { file ->
            val fallbackName = file.name.drop(37)
            val metadata = try { JSONObject(metadataFile(file).readText()) } catch (_: Exception) { null }
            val displayName = safeFileName(metadata?.optString("displayName", fallbackName) ?: fallbackName)
            val type = metadata?.optString("mimeType", "")?.takeIf { it.matches(MIME_PATTERN) }
                ?: inferMimeType(displayName)
            GeneratedArtifact(file, type, displayName)
        }
    }

    @Synchronized
    fun delete(artifact: GeneratedArtifact): Boolean {
        require(artifact.file.canonicalFile.parentFile == directory.canonicalFile && artifact.file.name.matches(ARTIFACT_PATTERN)) {
            "Fichier à supprimer invalide."
        }
        val deleted = !artifact.file.exists() || artifact.file.delete()
        if (deleted) metadataFile(artifact.file).delete()
        return deleted
    }

    @Synchronized
    internal fun temporaryFile(): File {
        requireCapacity(0)
        return File.createTempFile("media-", ".part", directory)
    }

    @Synchronized
    internal fun adoptDownloaded(file: File, mimeType: String, name: String): GeneratedArtifact {
        require(file.canonicalFile.parentFile == directory.canonicalFile)
        requireCapacity(file.length(), file)
        val destination = uniqueFile(safeFileName(name))
        if (!file.renameTo(destination)) throw IOException("Impossible d'enregistrer le média.")
        return completeArtifact(destination, mimeType, safeFileName(name))
    }

    @Synchronized
    internal fun createBytes(name: String, mimeType: String, bytes: ByteArray): GeneratedArtifact {
        requireCapacity(bytes.size.toLong())
        val displayName = safeFileName(name)
        val file = uniqueFile(displayName)
        try {
            file.outputStream().use { it.write(bytes) }
        } catch (error: Exception) {
            file.delete()
            throw error
        }
        return completeArtifact(file, mimeType, displayName)
    }

    private fun uniqueFile(name: String): File = File(directory, "${UUID.randomUUID()}-$name")

    @Synchronized
    private fun createPdf(name: String, text: String): GeneratedArtifact {
        requireCapacity(0)
        val file = uniqueFile(name)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f }
        val document = PdfDocument()
        try {
            val source = text.replace("\r\n", "\n").replace('\r', '\n')
            val lines = buildList {
                var lineStart = 0
                do {
                    val lineEnd = source.indexOf('\n', lineStart).let { if (it < 0) source.length else it }
                    var offset = lineStart
                    if (offset == lineEnd) add("")
                    while (offset < lineEnd) {
                        // Keep source offsets instead of repeatedly copying a potentially huge line.
                        val count = paint.breakText(source, offset, lineEnd, true, 515f, null).coerceAtLeast(1)
                        add(source.substring(offset, offset + count))
                        offset += count
                        require(size <= 45 * 300) { "PDF trop long (300 pages maximum)." }
                    }
                    require(size <= 45 * 300) { "PDF trop long (300 pages maximum)." }
                    lineStart = lineEnd + 1
                } while (lineStart <= source.length)
            }
            lines.ifEmpty { listOf("") }.chunked(45).forEachIndexed { index, pageLines ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                pageLines.forEachIndexed { line, value -> page.canvas.drawText(value, 40f, 48f + line * 16f, paint) }
                document.finishPage(page)
            }
            file.outputStream().use { document.writeTo(it) }
            // The PDF size is known only after rendering; check before adding it to history.
            requireCapacity(file.length(), file)
            return completeArtifact(file, ExportFormat.PDF.mimeType, name)
        } catch (error: Exception) {
            file.delete()
            throw error
        } finally {
            document.close()
        }
    }

    private fun artifactFiles(): List<File> = directory.listFiles().orEmpty()
        .filter { it.isFile && it.name.matches(ARTIFACT_PATTERN) }

    private fun metadataFile(file: File) = File(directory, ".${file.name}.meta.json")

    private fun completeArtifact(file: File, mimeType: String, displayName: String): GeneratedArtifact {
        try {
            metadataFile(file).writeText(JSONObject().put("displayName", displayName).put("mimeType", mimeType).toString())
        } catch (error: Exception) {
            file.delete()
            metadataFile(file).delete()
            throw IOException("Impossible d'enregistrer les informations du fichier.")
        }
        return GeneratedArtifact(file, mimeType, displayName)
    }

    private fun requireCapacity(newBytes: Long, replacing: File? = null) {
        val artifacts = artifactFiles().filter { it != replacing }
        if (artifacts.size >= MAX_STORED_ARTIFACTS) throw IOException("Limite de 100 fichiers atteinte. Enregistrez puis supprimez des fichiers dans Création.")
        val usedBytes = directory.listFiles().orEmpty().filter { it.isFile && it != replacing }.sumOf { it.length() }
        if (usedBytes + newBytes + 1024 > MAX_STORAGE_BYTES) throw IOException("Stockage des créations plein (512 Mo). Enregistrez puis supprimez des fichiers dans Création.")
    }

    companion object {
        private const val MAX_DOCUMENT_BYTES = 8 * 1024 * 1024
        private const val MAX_STORED_ARTIFACTS = 100
        private const val MAX_STORAGE_BYTES = 512 * 1024 * 1024L
        private val MIME_PATTERN = Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")
        private val ARTIFACT_PATTERN = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}-.+")

        fun safeFileName(name: String): String {
            val clean = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim(' ', '.')
                .take(100).ifBlank { "PocketAI-document.txt" }
            // Neither relative paths nor dot-only names can escape the app's private directory.
            return if (clean == "." || clean == "..") "PocketAI-document.txt" else clean
        }

        internal fun encodeText(text: String, format: ExportFormat): String = when (format) {
            ExportFormat.HTML -> "<!doctype html><html lang=\"fr\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>PocketAI</title><body><pre style=\"white-space:pre-wrap;font-family:system-ui\">${escapeHtml(text)}</pre></body></html>"
            ExportFormat.JSON -> JSONObject().put("response", text).toString(2)
            ExportFormat.CSV -> {
                val cell = if (text.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$text" else text
                "response\r\n\"${cell.replace("\"", "\"\"")}\"\r\n"
            }
            else -> text
        }

        private fun escapeHtml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

        private fun inferMimeType(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
            "txt", "log" -> "text/plain"; "md" -> "text/markdown"; "html", "htm" -> "text/html"
            "json" -> "application/json"; "csv" -> "text/csv"; "pdf" -> "application/pdf"
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"
            "mp4" -> "video/mp4"; "webm" -> "video/webm"; "py" -> "text/x-python"; "kt", "java", "js", "sh" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
