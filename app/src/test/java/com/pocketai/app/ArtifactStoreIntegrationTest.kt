package com.pocketai.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArtifactStoreIntegrationTest {
    @Test fun creationPreservesTextWithoutOverwritingAnExistingDocument() {
        val context = RuntimeEnvironment.getApplication()
        val store = ArtifactStore(context)
        val first = store.createDocument("../reponse.txt", "text/plain", "Bonjour 👋")
        val second = store.createDocument("../reponse.txt", "text/plain", "Deuxième réponse")
        try {
            assertEquals(File(context.filesDir, "generated").canonicalFile, first.file.canonicalFile.parentFile)
            assertNotEquals(first.file, second.file)
            assertEquals("Bonjour 👋", first.file.readText())
            assertEquals("Deuxième réponse", second.file.readText())
        } finally {
            store.delete(first)
            store.delete(second)
        }
    }

    @Test fun documentLimitPreventsWritingOversizedArtifacts() {
        val store = ArtifactStore(RuntimeEnvironment.getApplication())
        assertThrows(IllegalArgumentException::class.java) {
            store.createDocument("large.txt", "text/plain", "x".repeat(8 * 1024 * 1024 + 1))
        }
    }

    @Test fun successfulMediaAdoptionRemovesTemporaryFileAndKeepsPrivateMedia() {
        val context = RuntimeEnvironment.getApplication()
        val store = ArtifactStore(context)
        val part = store.temporaryFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val artifact = store.adoptDownloaded(part, "image/png", "image.png")
        try {
            assertFalse(part.exists())
            assertTrue(artifact.file.isFile)
            assertArrayEquals(byteArrayOf(1, 2, 3), artifact.file.readBytes())
            assertEquals("image.png", artifact.displayName)
        } finally {
            store.delete(artifact)
        }
    }

    @Test fun artifactHistoryRestoresMimeTypeAcrossNewStoreInstancesAndSupportsDeletion() {
        val context = RuntimeEnvironment.getApplication()
        val store = ArtifactStore(context)
        val artifact = store.createDocument("export.custom", "application/json", "{\"answer\":42}")
        try {
            val restored = ArtifactStore(context).list().single { it.file == artifact.file }
            assertEquals("export.custom", restored.displayName)
            assertEquals("application/json", restored.mimeType)
            assertEquals("{\"answer\":42}", restored.file.readText())
            assertTrue(store.delete(restored))
            assertFalse(ArtifactStore(context).list().any { it.file == artifact.file })
        } finally {
            store.delete(artifact)
        }
    }

    @Test fun historyDoesNotExposeTemporaryFilesOrMetadataSidecars() {
        val context = RuntimeEnvironment.getApplication()
        val store = ArtifactStore(context)
        val part = store.temporaryFile()
        val artifact = store.createDocument("document.meta.json", "text/plain", "test")
        try {
            val files = store.list().map { it.file }
            assertTrue(files.contains(artifact.file))
            assertFalse(files.contains(part))
            assertFalse(files.any { it.name.startsWith('.') })
        } finally {
            part.delete()
            store.delete(artifact)
        }
    }

    @Test fun aFullHistoryRequiresExplicitDeletionAndNeverDeletesAnExistingArtifact() {
        val store = ArtifactStore(RuntimeEnvironment.getApplication())
        store.list().forEach { store.delete(it) }
        val created = mutableListOf<GeneratedArtifact>()
        try {
            repeat(100) { created += store.createDocument("file-$it.txt", "text/plain", "kept") }
            assertThrows(java.io.IOException::class.java) { store.createDocument("overflow.txt", "text/plain", "overflow") }
            assertTrue(created.all { it.file.isFile && it.file.readText() == "kept" })
            assertTrue(store.delete(created.first()))
            created += store.createDocument("after-deletion.txt", "text/plain", "ready")
            assertEquals(100, store.list().size)
        } finally {
            created.forEach { store.delete(it) }
        }
    }
}
