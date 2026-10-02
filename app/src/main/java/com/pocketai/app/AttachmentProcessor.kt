package com.pocketai.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser

data class PreparedAttachment(
    val name: String,
    val mimeType: String,
    val kind: String,
    val text: String,
    val truncated: Boolean = false,
    val pages: Int = 0,
) {
    val summary: String
        get() = buildString {
            append(name).append(" · ").append(kind)
            if (pages > 0) append(" · ").append(pages).append(" page(s)")
            append(" · ").append(text.length).append(" caractères")
            if (truncated) append(" · extrait tronqué")
        }
}

/**
 * Local-only attachment extraction. No content is uploaded by this class.
 * Text files and DOCX are parsed directly; images and PDFs use the bundled ML Kit OCR model.
 */
class AttachmentProcessor(private val context: Context) {
    suspend fun prepare(uri: Uri, onProgress: (String) -> Unit = {}): PreparedAttachment = withContext(Dispatchers.IO) {
        val metadata = metadata(uri)
        val name = metadata.first
        val mime = metadata.second
        val lower = name.lowercase(Locale.ROOT)

        when {
            mime == "application/pdf" || lower.endsWith(".pdf") -> preparePdf(uri, name, mime, onProgress)
            mime.startsWith("image/") || lower.endsWith(".png") || lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".heic") ->
                prepareImage(uri, name, mime.ifBlank { "image/*" }, onProgress)
            mime == DOCX_MIME || lower.endsWith(".docx") -> {
                onProgress("Lecture du document DOCX…")
                prepareDocx(uri, name, DOCX_MIME)
            }
            isTextLike(mime, lower) -> {
                onProgress("Lecture du fichier texte…")
                prepareText(uri, name, mime.ifBlank { "text/plain" })
            }
            else -> throw IOException("Format non pris en charge pour l’analyse locale : $mime")
        }
    }

    private fun metadata(uri: Uri): Pair<String, String> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "piece-jointe"
        var mime = context.contentResolver.getType(uri).orEmpty()
        if (uri.scheme == "content") {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && !cursor.isNull(index)) name = cursor.getString(index)
                }
            }
        }
        if (mime.isBlank()) mime = guessMime(name)
        return name.take(160) to mime
    }

    private fun prepareText(uri: Uri, name: String, mime: String): PreparedAttachment {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Impossible d’ouvrir la pièce jointe.")
        val bytes = input.use { it.readNBytes(MAX_TEXT_BYTES + 1) }
        val truncatedBytes = bytes.size > MAX_TEXT_BYTES
        val safe = if (truncatedBytes) bytes.copyOf(MAX_TEXT_BYTES) else bytes
        val decoded = safe.toString(Charsets.UTF_8)
            .removePrefix("\uFEFF")
            .replace("\u0000", "")
        val bounded = bound(decoded)
        require(bounded.first.isNotBlank()) { "Le fichier texte est vide." }
        return PreparedAttachment(
            name = name,
            mimeType = mime,
            kind = "texte",
            text = bounded.first,
            truncated = truncatedBytes || bounded.second,
        )
    }

    private fun prepareDocx(uri: Uri, name: String, mime: String): PreparedAttachment {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Impossible d’ouvrir le document DOCX.")
        var xml: ByteArray? = null
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name == "word/document.xml") {
                    xml = zip.readNBytes(MAX_DOCX_XML_BYTES + 1)
                    break
                }
            }
        }
        val bytes = xml ?: throw IOException("Le DOCX ne contient pas word/document.xml.")
        require(bytes.size <= MAX_DOCX_XML_BYTES) { "Le document DOCX est trop volumineux." }

        val parser = android.util.Xml.newPullParser().apply {
            setInput(ByteArrayInputStream(bytes), "UTF-8")
        }
        val text = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && text.length <= MAX_EXTRACTED_CHARS * 2) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                    "t" -> text.append(parser.nextText())
                    "tab" -> text.append('\t')
                    "br", "cr" -> text.append('\n')
                }
                XmlPullParser.END_TAG -> if (parser.name.substringAfter(':') == "p") text.append('\n')
            }
            event = parser.next()
        }
        val bounded = bound(text.toString())
        require(bounded.first.isNotBlank()) { "Aucun texte lisible trouvé dans le DOCX." }
        return PreparedAttachment(name, mime, "DOCX", bounded.first, bounded.second)
    }

    private fun prepareImage(uri: Uri, name: String, mime: String, onProgress: (String) -> Unit): PreparedAttachment {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        return try {
            onProgress("OCR et analyse locale de l’image…")
            val image = InputImage.fromFilePath(context, uri)
            val textResult = Tasks.await(recognizer.process(image))
            val labels = Tasks.await(labeler.process(image))
                .asSequence()
                .filter { it.confidence >= 0.45f }
                .sortedByDescending { it.confidence }
                .take(12)
                .toList()

            val combined = buildString {
                if (textResult.text.isNotBlank()) {
                    append("Texte OCR détecté :\n")
                    append(textResult.text.trim())
                    append("\n\n")
                }
                if (labels.isNotEmpty()) {
                    append("Indices visuels détectés localement :\n")
                    labels.forEach { label ->
                        append("- ").append(label.text)
                            .append(" (").append(String.format(Locale.ROOT, "%.0f%%", label.confidence * 100f)).append(")\n")
                    }
                    append("\nCes labels sont des indices génériques et peuvent être inexacts ; ne pas les présenter comme une certitude.")
                }
            }
            val bounded = bound(combined)
            require(bounded.first.isNotBlank()) {
                "Aucun texte ni indice visuel exploitable détecté. Un vrai modèle vision multimodal sera nécessaire pour cette image."
            }
            PreparedAttachment(name, mime, "image OCR + vision légère", bounded.first, bounded.second, pages = 1)
        } finally {
            recognizer.close()
            labeler.close()
        }
    }

    private fun preparePdf(uri: Uri, name: String, mime: String, onProgress: (String) -> Unit): PreparedAttachment {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Impossible d’ouvrir le PDF.")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            PdfRenderer(descriptor).use { renderer ->
                val total = renderer.pageCount
                require(total > 0) { "Le PDF ne contient aucune page." }
                val pagesToRead = minOf(total, MAX_PDF_PAGES)
                val output = StringBuilder()
                for (index in 0 until pagesToRead) {
                    if (output.length >= MAX_EXTRACTED_CHARS) break
                    onProgress("OCR PDF · page ${index + 1}/$pagesToRead")
                    renderer.openPage(index).use { page ->
                        val scale = minOf(
                            MAX_PDF_BITMAP_WIDTH.toFloat() / page.width.coerceAtLeast(1),
                            MAX_PDF_BITMAP_HEIGHT.toFloat() / page.height.coerceAtLeast(1),
                            2.0f,
                        ).coerceAtLeast(0.5f)
                        val width = (page.width * scale).toInt().coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                            if (result.text.isNotBlank()) {
                                output.append("\n\n--- Page ").append(index + 1).append(" ---\n")
                                output.append(result.text)
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
                val bounded = bound(output.toString())
                require(bounded.first.isNotBlank()) { "Aucun texte détecté dans les pages analysées du PDF." }
                PreparedAttachment(
                    name = name,
                    mimeType = mime,
                    kind = "PDF OCR",
                    text = bounded.first,
                    truncated = bounded.second || total > pagesToRead,
                    pages = pagesToRead,
                )
            }
        } finally {
            recognizer.close()
            descriptor.close()
        }
    }

    private fun bound(value: String): Pair<String, Boolean> {
        val normalized = value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\n{4,}"), "\n\n\n")
            .trim()
        if (normalized.length <= MAX_EXTRACTED_CHARS) return normalized to false
        val end = if (normalized[MAX_EXTRACTED_CHARS - 1].isHighSurrogate()) MAX_EXTRACTED_CHARS - 1 else MAX_EXTRACTED_CHARS
        return normalized.substring(0, end) to true
    }

    private fun isTextLike(mime: String, lowerName: String): Boolean =
        mime.startsWith("text/") ||
            mime in setOf("application/json", "application/xml", "application/javascript") ||
            lowerName.substringAfterLast('.', "") in setOf(
                "txt", "md", "markdown", "csv", "json", "xml", "html", "htm", "log",
                "kt", "java", "py", "js", "ts", "sh", "yaml", "yml", "toml", "ini", "conf", "sql"
            )

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "pdf" -> "application/pdf"
        "docx" -> DOCX_MIME
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "csv" -> "text/csv"
        "md", "markdown" -> "text/markdown"
        else -> "text/plain"
    }

    private companion object {
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val MAX_TEXT_BYTES = 512 * 1024
        const val MAX_DOCX_XML_BYTES = 8 * 1024 * 1024
        const val MAX_EXTRACTED_CHARS = 18_000
        const val MAX_PDF_PAGES = 12
        const val MAX_PDF_BITMAP_WIDTH = 1600
        const val MAX_PDF_BITMAP_HEIGHT = 2200
    }
}
