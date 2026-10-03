package com.pocketai.app

import android.content.Context
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.graphics.Bitmap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RemoteInferenceClientTest {
    private lateinit var context: Context
    private lateinit var settings: OnlineSettings
    private lateinit var artifacts: ArtifactStore
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(OnlineSettings.PREFERENCES_NAME, 0).edit().clear().commit()
        settings = OnlineSettings(context)
        artifacts = ArtifactStore(context)
    }
    private fun configure(id: String, alias: String = "test-$id") {
        settings.configureInference(id, "https://test.example/v1", alias, null)
    }

    @Test fun chatUsesConfiguredModelAndDoesNotSilentlySubstituteAnotherModel() = runBlocking {
        configure("qwen35-2b")
        val client = RemoteInferenceClient(context, settings, artifacts, InferenceTransport { url, key, body, _, _ ->
            assertEquals("https://test.example/v1/chat/completions", url.toString())
            assertEquals("", key)
            assertEquals("test-qwen35-2b", JSONObject(String(body!!)).getString("model"))
            """{"choices":[{"message":{"content":"Bonjour"}}],"usage":{"completion_tokens":2}}""".toByteArray()
        })
        assertEquals("Bonjour" to 2, client.chat(ModelHub.find("qwen35-2b"), emptyList(), "salut", "system", 512, null))
    }

    @Test fun missingConfigurationFailsBeforeNetworkAndDoesNotEnableDocumentUploads() = runBlocking {
        var called = false
        val client = RemoteInferenceClient(context, settings, artifacts, InferenceTransport { _, _, _, _, _ ->
            called = true; byteArrayOf()
        })
        assertTrue(runCatching { client.checkModel(ModelHub.find("kokoro")) }.isFailure)
        assertFalse(called)
        assertFalse(settings.embeddingEnabled)
    }

    @Test fun semanticRetrievalUsesReturnedVectorsToSelectTheRelevantPassage() = runBlocking {
        configure("qwen3-embedding")
        val client = RemoteInferenceClient(context, settings, artifacts, InferenceTransport { url, _, body, _, _ ->
            assertEquals("/v1/embeddings", url.path)
            val input = JSONObject(String(body!!)).getJSONArray("input")
            val data = JSONArray()
            for (i in 0 until input.length()) {
                val relevant = input.getString(i).contains("TARGET")
                data.put(JSONObject().put("index", i).put("embedding", JSONArray(if (relevant) listOf(1, 0) else listOf(0, 1))))
            }
            JSONObject().put("data", data).toString().toByteArray()
        })
        val document = "a ".repeat(6000) + "\nTARGET : configuration utile.\n" + "z ".repeat(6000)
        val selected = client.retrieve(document, "TARGET")
        assertTrue(selected.contains("TARGET : configuration utile."))
        assertTrue(selected.length < document.length)
    }

    @Test fun speechCreatesWavArtifactAndRejectsJsonErrorsMasqueradingAsAudio() = runBlocking {
        configure("kokoro")
        val wav = ByteArray(44).apply { "RIFF".toByteArray().copyInto(this); "WAVE".toByteArray().copyInto(this, 8) }
        val client = RemoteInferenceClient(context, settings, artifacts, InferenceTransport { url, _, body, _, _ ->
            assertEquals("/v1/audio/speech", url.path)
            val payload = JSONObject(String(body!!))
            assertEquals("ff_siwis", payload.getString("voice"))
            assertEquals("wav", payload.getString("response_format"))
            wav
        })
        val artifact = client.speak("Bonjour")
        assertEquals("audio/wav", artifact.mimeType)
        assertArrayEquals(wav, artifact.file.readBytes())
        val invalid = RemoteInferenceClient(context, settings, artifacts, InferenceTransport { _, _, _, _, _ ->
            "{\"error\":\"bad request\"}".toByteArray()
        })
        assertTrue(runCatching { invalid.speak("Bonjour") }.isFailure)
    }

    @Test fun transcriptionSendsAudioAsMultipartAndPersistsTheReturnedText() = runBlocking {
        configure("whisper-small", "whisper-small")
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "audio/wav"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        }
        provider.attachInfo(context, ProviderInfo().apply { authority = "test-audio" })
        ShadowContentResolver.registerProviderInternal("test-audio", provider)
        val uri = Uri.parse("content://test-audio/clip")
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStream(uri,
            ByteArrayInputStream("RIFFfixture-audio".toByteArray()))
        val client = RemoteInferenceClient(context, settings, artifacts, InferenceTransport { url, _, body, type, _ ->
            assertEquals("/v1/audio/transcriptions", url.path)
            assertTrue(type.startsWith("multipart/form-data; boundary="))
            val text = String(body!!)
            assertTrue(text.contains("name=\"model\"\r\n\r\nwhisper-small"))
            assertTrue(text.contains("name=\"file\"; filename=\"audio.wav\""))
            assertTrue(text.contains("RIFFfixture-audio"))
            "{\"text\":\"Bonjour Laurent\"}".toByteArray()
        })
        val artifact = client.transcribe(uri)
        assertEquals("text/plain", artifact.mimeType)
        assertEquals("Bonjour Laurent", artifact.file.readText())
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun imageGenerationUsesDiffusionEndpointAndSavesAnActualImage() = runBlocking {
        configure("qwen-image")
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val client = RemoteInferenceClient(context, settings, artifacts, InferenceTransport { url, _, body, _, _ ->
            assertEquals("/v1/images/generations", url.path)
            val payload = JSONObject(String(body!!))
            assertEquals("test-qwen-image", payload.getString("model"))
            assertEquals("b64_json", payload.getString("response_format"))
            JSONObject().put("data", JSONArray().put(JSONObject().put("b64_json",
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))).toString().toByteArray()
        })
        val artifact = client.image("Un paysage")
        assertEquals("image/png", artifact.mimeType)
        assertArrayEquals(bytes, artifact.file.readBytes())
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun visionUploadsTheResizedPhotoAndImageEditingUsesItsDedicatedEndpoint() = runBlocking {
        configure("smolvlm2"); configure("qwen-image")
        val photo = Bitmap.createBitmap(1800, 900, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().also { photo.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        photo.recycle()
        val file = java.io.File(context.cacheDir, "vision-test.png").apply { writeBytes(png) }
        val uri = Uri.fromFile(file)
        val client = RemoteInferenceClient(context, settings, artifacts, InferenceTransport { url, _, body, type, _ ->
            when (url.path) {
                "/v1/chat/completions" -> {
                    val messages = JSONObject(String(body!!)).getJSONArray("messages")
                    val image = messages.getJSONObject(messages.length() - 1).getJSONArray("content")
                        .getJSONObject(1).getJSONObject("image_url").getString("url")
                    val decoded = android.util.Base64.decode(image.substringAfter(','), android.util.Base64.DEFAULT)
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                    assertEquals(1280, bitmap.width); assertEquals(640, bitmap.height); bitmap.recycle()
                    "{\"choices\":[{\"message\":{\"content\":\"Photo reçue\"}}]}".toByteArray()
                }
                "/v1/images/edits" -> {
                    assertTrue(type.startsWith("multipart/form-data;"))
                    assertTrue(String(body!!).contains("name=\"image\"; filename=\"image.jpg\""))
                    JSONObject().put("data", JSONArray().put(JSONObject().put("b64_json",
                        android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)))).toString().toByteArray()
                }
                else -> throw AssertionError("Endpoint inattendu : ${url.path}")
            }
        })
        assertEquals("Photo reçue", client.chat(ModelHub.find("smolvlm2"), emptyList(), "Analyse", "system", 512, uri).first)
        assertEquals("image/png", client.image("Modifier", uri).mimeType)
    }
}
