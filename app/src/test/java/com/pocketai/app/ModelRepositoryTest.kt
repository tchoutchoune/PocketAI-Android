package com.pocketai.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: ModelRepository

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "models").deleteRecursively()
        context.getSharedPreferences("model_fingerprints_v1", 0).edit().clear().commit()
        repository = ModelRepository(context)
    }

    @Test fun importingIdenticalContentReusesTheInstalledFile() = runBlocking {
        val source = File(context.cacheDir, "test-model.gguf").apply { writeBytes(validGguf()) }
        val first = repository.import(Uri.fromFile(source))
        val second = repository.import(Uri.fromFile(source))
        assertEquals(first, second)
        assertArrayEquals(source.readBytes(), first.readBytes())
        assertArrayEquals(source.readBytes(), second.readBytes())
        assertEquals(1, repository.installed().size)
        assertFalse(File(context.filesDir, "models").listFiles().orEmpty().any { it.extension == "part" })
    }

    @Test fun differentContentWithSameNamePreservesBothFiles() = runBlocking {
        val source = File(context.cacheDir, "test-model.gguf").apply { writeBytes(validGguf()) }
        val first = repository.import(Uri.fromFile(source))
        source.writeBytes(validGguf().apply { this[40] = 7 })
        val second = repository.import(Uri.fromFile(source))
        assertNotEquals(first, second)
        assertEquals(0, first.readBytes()[40].toInt())
        assertEquals(7, second.readBytes()[40].toInt())
        assertEquals(2, repository.installed().size)
    }

    @Test fun renamedInstalledWeightsAreRecognizedAndNeverDownloadedAgain() = runBlocking {
        val bytes = validGguf()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val entry = ModelEntry("known", "Known model", "https://example.invalid/original.gguf", bytes.size.toLong(), "", digest)
        val renamed = File(context.filesDir, "models/renamed.gguf").apply { writeBytes(bytes) }
        assertEquals(setOf("known"), repository.inventory(listOf(entry)).single().catalogueIds)
        assertEquals(renamed, repository.download(entry) { _, _ -> })
        assertEquals(1, repository.installed().size)
    }

    @Test fun changedWeightsInvalidateTheStoredFingerprint() = runBlocking {
        val bytes = validGguf()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val entry = ModelEntry("known", "Known", "https://example.invalid/original.gguf", bytes.size.toLong(), "", digest)
        val file = File(context.filesDir, "models/changed.gguf").apply { writeBytes(bytes) }
        assertEquals(setOf("known"), repository.inventory(listOf(entry)).single().catalogueIds)
        val previousTime = file.lastModified()
        file.writeBytes(bytes.apply { this[40] = 9 })
        file.setLastModified(previousTime + 2000)
        assertTrue(repository.inventory(listOf(entry)).single().catalogueIds.isEmpty())
    }

    @Test fun completedPartialDownloadIsVerifiedAndCommittedWithoutNetwork() = runBlocking {
        val bytes = validGguf()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val entry = ModelEntry("complete", "Complete", "https://example.invalid/completed.gguf", bytes.size.toLong(), "", digest)
        File(context.filesDir, "models/.download-${digest.take(24)}.part").writeBytes(bytes)
        val result = repository.download(entry) { _, _ -> }
        assertArrayEquals(bytes, result.readBytes())
        assertEquals(1, repository.installed().size)
        assertFalse(File(context.filesDir, "models").listFiles().orEmpty().any { it.extension == "part" })
    }

    @Test fun malformedImportIsRejectedAndPartialFileIsRemoved() = runBlocking {
        val source = File(context.cacheDir, "bad-${UUID.randomUUID()}.gguf").apply {
            writeText("<!doctype html><title>Download failed</title>")
        }
        val failure = runCatching { repository.import(Uri.fromFile(source)) }
        assertTrue(failure.isFailure)
        assertTrue(repository.installed().isEmpty())
        assertTrue(File(context.filesDir, "models").listFiles().orEmpty().isEmpty())
    }

    @Test fun downloadWithoutOfficialChecksumFailsBeforeConnecting() = runBlocking {
        val unverified = ModelEntry("bad", "Unverified", "https://example.invalid/model.gguf", 100, "")
        val result = runCatching { repository.download(unverified) { _, _ -> } }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(repository.installed().isEmpty())
    }

    @Test fun filenamesCannotEscapePrivateModelStorage() {
        assertEquals("payload.gguf", ModelRepository.safeFilename("../../payload.gguf"))
        assertEquals("payload.gguf", ModelRepository.safeFilename("..\\..\\payload.gguf"))
        assertEquals("modele.gguf", ModelRepository.safeFilename("..."))
        assertFalse(ModelRepository.safeFilename("a/b/evil;name.gguf").contains(';'))
    }

    @Test fun catalogueUsesPinnedOfficialWeightsAndChecksums() {
        assertEquals(14, ModelRepository.catalogue.size)
        assertEquals(ModelRepository.catalogue.size, ModelRepository.catalogue.map { it.id }.distinct().size)
        ModelRepository.catalogue.forEach { entry ->
            assertTrue(entry.url.startsWith("https://huggingface.co/"))
            assertTrue(Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(entry.url))
            assertTrue(entry.sha256.orEmpty().matches(Regex("[a-f0-9]{64}")))
            assertTrue(entry.sizeBytes > 400_000_000L)
            assertTrue(entry.licenseUrl.startsWith("https://huggingface.co/"))
            assertFalse(entry.url.contains("mmproj"))
            assertFalse(entry.url.contains("/main/"))
        }
    }

    @Test fun specializedModelsCannotBeDownloadedIntoTheTextEngine() {
        assertEquals(21, ModelHub.models.size)
        ModelHub.models.filter { !it.supportsChat }.forEach { assertNull(it.local) }
        assertTrue(ModelHub.find("smolvlm2").vision)
        assertNull(ModelHub.find("smolvlm2").local)
        assertEquals(ModelUse.CODE, ModelHub.find("qwen25-coder-15b").use)
    }

    private fun validGguf() = ByteArray(128).apply {
        "GGUF".toByteArray().copyInto(this)
        this[4] = 3
    }
}
