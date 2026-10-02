package com.pocketai.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

data class ModelEntry(
    val id: String,
    val title: String,
    val url: String,
    val sizeBytes: Long,
    val description: String,
    val sha256: String? = null,
    val licenseUrl: String = "",
)

/** Private GGUF storage. Downloads are pinned, authenticated by checksum, and never overwrite imports. */
class ModelRepository(private val context: Context) {
    private val directory = File(context.filesDir, "models").apply { mkdirs() }

    fun installed(): List<File> = directory.listFiles().orEmpty().filter {
        it.isFile && it.extension.equals("gguf", true) && runCatching { validateHeader(it) }.isSuccess
    }.sortedBy { it.name.lowercase(Locale.ROOT) }

    suspend fun import(uri: Uri): File = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            require(uri.scheme == "content" || uri.scheme == "file") { "Sélectionnez un fichier GGUF local." }
            var displayName = uri.lastPathSegment ?: "modele.gguf"
            var expectedSize = -1L
            if (uri.scheme == "content") {
                context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) expectedSize = cursor.getLong(sizeIndex)
                    }
                }
            } else expectedSize = File(uri.path.orEmpty()).length()
            val limit = modelSizeLimit()
            if (expectedSize > 0) preflight(expectedSize)
            val destination = uniqueFile(safeFilename(displayName))
            val temporary = partialFile()
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Impossible d’ouvrir ce fichier.")
                val count = input.use { copyToFile(it, temporary, limit) }
                require(expectedSize <= 0 || count == expectedSize) { "Import incomplet : resélectionnez le fichier." }
                validateHeader(temporary)
                currentCoroutineContext().ensureActive()
                commit(temporary, destination)
                destination
            } finally {
                temporary.delete()
            }
        }
    }

    suspend fun download(entry: ModelEntry, onProgress: (downloaded: Long, total: Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            ioMutex.withLock {
                val expectedHash = entry.sha256?.lowercase(Locale.ROOT)
                require(expectedHash != null && expectedHash.matches(Regex("[a-f0-9]{64}"))) {
                    "Une empreinte SHA-256 officielle est requise pour le téléchargement."
                }
                require(entry.sizeBytes >= 16) { "Taille du modèle invalide." }
                val name = safeFilename(URL(entry.url).path.substringAfterLast('/'))
                val preferred = File(directory, name)
                if (preferred.isFile && preferred.length() == entry.sizeBytes &&
                    runCatching { validateHeader(preferred) }.isSuccess && sha256(preferred) == expectedHash
                ) {
                    onProgress(entry.sizeBytes, entry.sizeBytes)
                    return@withLock preferred
                }
                preflight(entry.sizeBytes)
                val destination = uniqueFile(name)
                val temporary = partialFile()
                var connection: HttpsURLConnection? = null
                try {
                    connection = openHttps(entry.url)
                    val advertisedLength = connection.contentLengthLong
                    require(advertisedLength <= 0 || advertisedLength == entry.sizeBytes) {
                        "La taille annoncée ne correspond pas au catalogue officiel."
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    val count = connection.inputStream.use {
                        copyToFile(it, temporary, entry.sizeBytes, digest) { progress ->
                            onProgress(progress, entry.sizeBytes)
                        }
                    }
                    require(count == entry.sizeBytes) { "Téléchargement incomplet : recommencez." }
                    validateHeader(temporary)
                    require(digest.digest().toHex() == expectedHash) {
                        "Empreinte SHA-256 incorrecte : le modèle téléchargé a été supprimé."
                    }
                    currentCoroutineContext().ensureActive()
                    commit(temporary, destination)
                    onProgress(count, entry.sizeBytes)
                    destination
                } finally {
                    connection?.disconnect()
                    temporary.delete()
                }
            }
        }

    private fun preflight(bytes: Long) {
        require(bytes <= modelSizeLimit()) {
            "Ce modèle dépasse le budget mémoire prudent de cet appareil. Choisissez un modèle plus petit."
        }
        require(directory.usableSpace >= bytes + STORAGE_RESERVE) {
            "Espace insuffisant : libérez au moins ${((bytes + STORAGE_RESERVE) / MIB)} Mo."
        }
    }

    private fun modelSizeLimit(): Long {
        val memory = HardwareProfile.detect(context).totalRamBytes
        return if (memory > 0) minOf(MAX_MODEL_BYTES, (memory * 65 / 100 - 384 * MIB).coerceAtLeast(64 * MIB))
        else MAX_MODEL_BYTES
    }

    private suspend fun copyToFile(
        input: InputStream,
        target: File,
        maximumBytes: Long,
        digest: MessageDigest? = null,
        progress: ((Long) -> Unit)? = null,
    ): Long {
        var count = 0L
        var lastReport = 0L
        BufferedInputStream(input, BUFFER_BYTES).use { buffered ->
            FileOutputStream(target).use { fileOutput ->
                BufferedOutputStream(fileOutput, BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = buffered.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        count += read
                        require(count <= maximumBytes) { "Le fichier dépasse la taille autorisée." }
                        if (count == read.toLong()) validateHeader(buffer, read)
                        output.write(buffer, 0, read)
                        digest?.update(buffer, 0, read)
                        val now = System.nanoTime()
                        if (now - lastReport >= 250_000_000L) {
                            require(directory.usableSpace >= STORAGE_RESERVE) { "Espace insuffisant pendant la copie." }
                            progress?.invoke(count)
                            lastReport = now
                        }
                    }
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
        }
        return count
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_BYTES).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun uniqueFile(name: String): File {
        var candidate = File(directory, name)
        var suffix = 2
        while (candidate.exists()) candidate = File(directory, "${name.removeSuffix(".gguf")}-${suffix++}.gguf")
        return candidate
    }

    private fun partialFile(): File = File(directory, ".${UUID.randomUUID()}.part")

    private fun commit(temporary: File, destination: File) {
        require(!destination.exists()) { "Un autre fichier porte déjà ce nom." }
        try {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath())
        }
    }

    private fun openHttps(address: String): HttpsURLConnection {
        var next = URL(address)
        repeat(6) {
            require(next.protocol == "https" && next.userInfo == null) { "Seules les adresses HTTPS sont autorisées." }
            val connection = next.openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "PocketAI-Android/4.2")
            connection.setRequestProperty("Accept-Encoding", "identity")
            try {
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("Redirection sans destination.")
                    next = URL(next, location)
                    connection.disconnect()
                } else {
                    if (code != 200) throw IOException("Le serveur du modèle répond HTTP $code.")
                    return connection
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
        throw IOException("Trop de redirections du serveur de téléchargement.")
    }

    companion object {
        private const val MIB = 1024L * 1024
        private const val MAX_MODEL_BYTES = 8L * 1024 * MIB
        private const val STORAGE_RESERVE = 256L * MIB
        private const val BUFFER_BYTES = 256 * 1024
        private val ioMutex = Mutex()

        /** Sizes, SHA-256 (LFS OID), revisions and licenses checked against the official HF API. */
        val catalogue: List<ModelEntry> = listOf(
            ModelEntry(
                id = "qwen2.5-0.5b-q4_k_m",
                title = "Qwen 2.5 · 0,5B · rapide",
                url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/9217f5db79a29953eb74d5343926648285ec7e67/qwen2.5-0.5b-instruct-q4_k_m.gguf",
                sizeBytes = 491400032L,
                description = "491 Mo · léger, adapté aux téléphones modestes. Licence Apache 2.0.",
                sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
                licenseUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/blob/9217f5db79a29953eb74d5343926648285ec7e67/LICENSE",
            ),
            ModelEntry(
                id = "qwen2.5-1.5b-q4_k_m",
                title = "Qwen 2.5 · 1,5B · équilibré",
                url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/91cad51170dc346986eccefdc2dd33a9da36ead9/qwen2.5-1.5b-instruct-q4_k_m.gguf",
                sizeBytes = 1117320736L,
                description = "1,12 Go · compromis qualité et rapidité. Licence Apache 2.0.",
                sha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
                licenseUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/blob/91cad51170dc346986eccefdc2dd33a9da36ead9/LICENSE",
            ),
            ModelEntry(
                id = "qwen2.5-3b-q4_k_m",
                title = "Qwen 2.5 · 3B · qualité",
                url = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/7dabda4d13d513e3e842b20f0d435c732f172cbe/qwen2.5-3b-instruct-q4_k_m.gguf",
                sizeBytes = 2104932768L,
                description = "2,10 Go · réponses plus riches, au moins 4 Go de RAM conseillés. Licence Qwen Research (conditions spécifiques).",
                sha256 = "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d",
                licenseUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/blob/7dabda4d13d513e3e842b20f0d435c732f172cbe/LICENSE",
            ),
        )

        internal fun safeFilename(name: String): String {
            val basename = name.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("(?i)\\.gguf$"), "")
                .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                .trim('.', '_', '-').take(100).ifBlank { "modele" }
            return "$basename.gguf"
        }

        internal fun validateHeader(file: File) {
            require(file.length() >= 16) { "Le fichier est trop court pour être un modèle GGUF." }
            file.inputStream().use { input ->
                val header = ByteArray(8)
                var read = 0
                while (read < header.size) {
                    val count = input.read(header, read, header.size - read)
                    require(count > 0) { "En-tête GGUF incomplet." }
                    read += count
                }
                validateHeader(header, read)
            }
        }

        private fun validateHeader(header: ByteArray, size: Int) {
            // A stream may return fewer than eight bytes initially; final file validation covers that case.
            if (size < 8) return
            require(header[0] == 'G'.code.toByte() && header[1] == 'G'.code.toByte() &&
                header[2] == 'U'.code.toByte() && header[3] == 'F'.code.toByte()) {
                "Ce fichier n’est pas un modèle GGUF."
            }
            require(header[4].toInt() in 2..3 && header.slice(5..7).all { it == 0.toByte() }) {
                "Version GGUF non prise en charge (versions 2 et 3 attendues)."
            }
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
