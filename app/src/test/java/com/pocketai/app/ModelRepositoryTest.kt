package com.pocketai.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        repository = ModelRepository(context)
    }

    @Test fun importingSameNamePreservesBothFiles() = runBlocking {
        val source = File(context.cacheDir, "test-model.gguf").apply { writeBytes(validGguf()) }
        val first = repository.import(Uri.fromFile(source))
        val second = repository.import(Uri.fromFile(source))
        assertNotEquals(first.name, second.name)
        assertArrayEquals(source.readBytes(), first.readBytes())
        assertArrayEquals(source.readBytes(), second.readBytes())
        assertEquals(2, repository.installed().size)
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
        assertEquals(3, ModelRepository.catalogue.size)
        ModelRepository.catalogue.forEach { entry ->
            assertTrue(entry.url.startsWith("https://huggingface.co/Qwen/"))
            assertTrue(Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(entry.url))
            assertTrue(entry.sha256.orEmpty().matches(Regex("[a-f0-9]{64}")))
            assertTrue(entry.sizeBytes > 400_000_000L)
            assertTrue(entry.licenseUrl.endsWith("/LICENSE"))
        }
    }

    private fun validGguf() = ByteArray(128).apply {
        "GGUF".toByteArray().copyInto(this)
        this[4] = 3
    }
}
