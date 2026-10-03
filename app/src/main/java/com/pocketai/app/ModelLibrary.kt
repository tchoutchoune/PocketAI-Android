package com.pocketai.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque

data class InstalledModel(val file: File, val catalogueIds: Set<String> = emptySet())
data class DiscoveredModel(val uri: Uri, val name: String, val sizeBytes: Long)
data class FolderModels(val files: List<DiscoveredModel> = emptyList(), val note: String = "")

/** Only traverse the folder explicitly selected through Android's document picker. */
class ModelFolderScanner(private val context: Context) {
    private val prefs = context.getSharedPreferences("model_folder", 0)
    val folder: String? get() = prefs.getString("uri", null)

    fun remember(uri: Uri) {
        require(DocumentsContract.isTreeUri(uri)) { "Choisis un dossier de modèles." }
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val previous = folder
        prefs.edit().putString("uri", uri.toString()).apply()
        if (previous != null && previous != uri.toString()) runCatching {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun forget() {
        folder?.let { runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        prefs.edit().remove("uri").apply()
    }

    suspend fun scan(): FolderModels = withContext(Dispatchers.IO) {
        val tree = folder?.let(Uri::parse) ?: return@withContext FolderModels()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(DocumentsContract.getTreeDocumentId(tree) to 0)
        val seen = mutableSetOf<String>()
        val found = mutableListOf<DiscoveredModel>()
        var visited = 0
        var limited = false
        try {
            while (queue.isNotEmpty() && visited < 512) {
                currentCoroutineContext().ensureActive()
                val (id, depth) = queue.removeFirst()
                if (!seen.add(id)) continue
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
                val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE)
                val cursor = context.contentResolver.query(children, columns, null, null, null)
                    ?: return@withContext FolderModels(found, "Le dossier n’est plus accessible. Sélectionne-le à nouveau.")
                cursor.use {
                    while (it.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        if (++visited > 512) { limited = true; break }
                        val child = it.getString(0) ?: continue
                        val name = it.getString(1).orEmpty()
                        if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (depth < 4) queue.add(child to depth + 1) else limited = true
                        } else if (name.endsWith(".gguf", true)) {
                            found += DiscoveredModel(DocumentsContract.buildDocumentUriUsingTree(tree, child),
                                name, if (it.isNull(3)) -1 else it.getLong(3))
                        }
                    }
                }
            }
            FolderModels(found.sortedBy { it.name.lowercase() },
                if (limited || queue.isNotEmpty()) "Recherche limitée à 512 éléments et 4 niveaux de sous-dossiers. Choisis un dossier plus précis si nécessaire." else "")
        } catch (error: SecurityException) {
            FolderModels(note = "L’accès au dossier a été retiré. Sélectionne-le à nouveau.")
        } catch (error: java.io.IOException) {
            FolderModels(note = "Le dossier n’est pas disponible. Réessaie lorsqu’il est connecté.")
        }
    }
}
