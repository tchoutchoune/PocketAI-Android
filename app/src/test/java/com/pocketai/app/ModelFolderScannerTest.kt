package com.pocketai.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelFolderScannerTest {
    private lateinit var context: Context
    private lateinit var provider: FolderProvider
    private lateinit var scanner: ModelFolderScanner
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("model_folder", 0).edit().putString("uri", "content://test.models/tree/root").commit()
        provider = FolderProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "test.models" })
        ShadowContentResolver.registerProviderInternal("test.models", provider)
        scanner = ModelFolderScanner(context)
    }
    @Test fun folderScanFindsNestedGgufFilesAndIgnoresOtherFilesAndCycles() = runBlocking {
        provider.children["root"] = listOf(row("a", "model.GGUF"), row("b", "notes.txt"), directory("sub"))
        provider.children["sub"] = listOf(row("c", "nested.gguf"), directory("root"))
        val result = scanner.scan()
        assertEquals(listOf("model.GGUF", "nested.gguf"), result.files.map { it.name })
        assertEquals(setOf("root", "sub"), provider.queried.toSet())
        assertEquals(2, provider.queried.size)
        assertTrue(result.note.isBlank())
    }
    @Test fun oversizedDirectoryIsBoundedAndReportsTheLimit() = runBlocking {
        provider.children["root"] = (0..600).map { row("id$it", "model$it.gguf") }
        val result = scanner.scan()
        assertEquals(512, result.files.size)
        assertTrue(result.note.contains("512"))
    }
    @Test fun revokedFolderAccessProducesARecoverableMessage() = runBlocking {
        provider.denied = true
        val result = scanner.scan()
        assertTrue(result.files.isEmpty())
        assertTrue(result.note.contains("accès"))
    }
    private fun row(id: String, name: String) = arrayOf<Any>(id, name, "application/octet-stream", 128L)
    private fun directory(id: String) = arrayOf<Any>(id, id, DocumentsContract.Document.MIME_TYPE_DIR, 0L)

    class FolderProvider : ContentProvider() {
        val children = mutableMapOf<String, List<Array<Any>>>()
        val queried = mutableListOf<String>()
        var denied = false
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            if (denied) throw SecurityException("Revoked")
            val id = DocumentsContract.getDocumentId(uri)
            queried += id
            return MatrixCursor(projection!!).apply { children[id].orEmpty().forEach { addRow(it) } }
        }
        override fun getType(uri: Uri) = "vnd.android.document/directory"
        override fun insert(uri: Uri, values: ContentValues?) = throw UnsupportedOperationException()
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    }
}
