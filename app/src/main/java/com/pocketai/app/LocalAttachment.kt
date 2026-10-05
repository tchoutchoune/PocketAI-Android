package com.pocketai.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset

internal data class LocalAttachment(
    val displayName: String,
    val mimeType: String,
    val text: String,
    val sourceBytes: Long,
)

internal object TextDocumentDecoder {
    fun decode(bytes: ByteArray): String {
        require(bytes.size <= MAX_BYTES) { "Le fichier dépasse 2 Mo. Réduis-le ou sélectionne un extrait." }
        require(bytes.none { it == 0.toByte() }) { "Ce fichier semble binaire et ne peut pas être analysé comme du texte." }

        val utf8 = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .removePrefix("\uFEFF")
        }.getOrNull()

        val decoded = utf8 ?: Charset.forName("windows-1252").decode(ByteBuffer.wrap(bytes)).toString()
        val controls = decoded.count { it.code in 0..8 || it.code in 11..12 || it.code in 14..31 || it.code == 127 }
        require(decoded.isEmpty() || controls * 50 <= decoded.length) {
            "Le fichier contient trop de caractères binaires ou de contrôle."
        }
        return decoded
    }

    const val MAX_BYTES = 2 * 1024 * 1024
}

internal class LocalDocumentReader(private val context: Context) {
    suspend fun read(uri: Uri): LocalAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri).orEmpty().ifBlank { "text/plain" }
        val metadata = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) null else {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                    name to size
                }
            }
        val name = metadata?.first?.takeIf { !it.isNullOrBlank() } ?: "document.txt"
        val declaredSize = metadata?.second
        require(declaredSize == null || declaredSize <= TextDocumentDecoder.MAX_BYTES) {
            "Le fichier $name dépasse 2 Mo. Sélectionne un fichier plus petit ou un extrait."
        }

        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= TextDocumentDecoder.MAX_BYTES) {
                    "Le fichier $name dépasse 2 Mo. Sélectionne un fichier plus petit ou un extrait."
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException("Impossible de lire ce fichier.")

        require(bytes.isNotEmpty()) { "Le fichier $name est vide." }
        val text = TextDocumentDecoder.decode(bytes)
        require(text.isNotBlank()) { "Le fichier $name ne contient pas de texte exploitable." }

        LocalAttachment(
            displayName = name.take(160),
            mimeType = mime.take(120),
            text = text,
            sourceBytes = bytes.size.toLong(),
        )
    }
}
