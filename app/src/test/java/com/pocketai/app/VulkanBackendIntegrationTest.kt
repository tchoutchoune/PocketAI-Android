package com.pocketai.app

import android.app.ActivityManager
import android.app.Application
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.lifecycle.ViewModelStore
import com.arm.aichat.InferenceEngine
import com.arm.aichat.InferenceOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VulkanBackendIntegrationTest {
    private lateinit var app: Application
    private lateinit var model: ChatViewModel
    private lateinit var file: File
    private val store = ViewModelStore()

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("pocketai", 0).edit().clear().commit()
        shadowOf(app.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo().apply {
            totalMem = 12L * 1024 * 1024 * 1024
            availMem = 8L * 1024 * 1024 * 1024
        })
        shadowOf(app.getSystemService(PowerManager::class.java)).apply {
            setIsPowerSaveMode(false)
            setCurrentThermalStatus(PowerManager.THERMAL_STATUS_NONE)
        }
        shadowOf(app.packageManager).addSystemAvailableFeature(FeatureInfo().apply {
            name = PackageManager.FEATURE_VULKAN_HARDWARE_VERSION
            version = (1 shl 22) or (4 shl 12)
        })
        ConversationStore(app).save(listOf(ChatMessage(content = "Conversation à conserver", isUser = true)))
        file = File(app.cacheDir, "vulkan-test.gguf").apply { writeBytes(ByteArray(8)) }
        model = ChatViewModel(app)
        store.put("model", model)
    }

    @After fun close() {
        store.clear()
        file.delete()
        app.getSharedPreferences("pocketai", 0).edit().clear().commit()
        ConversationStore(app).clear()
    }

    private fun inject(engine: FakeEngine) {
        ChatViewModel::class.java.getDeclaredField("engine").apply { isAccessible = true }.set(model, engine)
    }

    @Test fun silentCpuFallbackIsNeverBenchmarkedOrCachedAsGpu() = runBlocking {
        val engine = FakeEngine(fallback = true)
        inject(engine)
        val history = model.state.value.messages
        model.compareVulkanBackends(file)
        assertEquals(1, engine.benchmarks)
        assertEquals(0, engine.gpuValidations)
        assertEquals("cpu-performance", model.performanceMode)
        assertEquals(history, model.state.value.messages)
        assertTrue(model.state.value.status.startsWith("CPU conservé"))
        assertFalse(app.getSharedPreferences("pocketai", 0).all.keys.any { it.startsWith("vulkan_recipe_") })
    }

    @Test fun validatedFastGpuRecipeRecordsActualLayerCountAndPreservesHistory() = runBlocking {
        val engine = FakeEngine()
        inject(engine)
        val history = model.state.value.messages
        model.compareVulkanBackends(file)
        assertEquals(5, engine.benchmarks)
        assertEquals(9, engine.gpuValidations)
        assertEquals("performance", model.performanceMode)
        assertEquals(history, model.state.value.messages)
        val recipe = app.getSharedPreferences("pocketai", 0).all.entries.single { it.key.startsWith("vulkan_recipe_") }
        val data = JSONObject(recipe.value.toString())
        assertTrue(data.getBoolean("validated"))
        assertEquals(12, data.getInt("samples"))
        assertEquals(36, data.getInt("layers"))
        assertTrue(model.state.value.diagnostics.contains("GPU layers: 36"))
    }

    @Test fun cancellationDoesNotPublishOrCacheAnUnvalidatedGpu() = runBlocking {
        val engine = FakeEngine(cancelValidation = true)
        inject(engine)
        val history = model.state.value.messages
        try { model.compareVulkanBackends(file); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        assertNull(model.state.value.modelName)
        assertEquals(history, model.state.value.messages)
        assertFalse(app.getSharedPreferences("pocketai", 0).all.keys.any { it.startsWith("vulkan_recipe_") })
        assertTrue(engine.cleanups >= 3)
    }

    private class FakeEngine(val fallback: Boolean = false, val cancelValidation: Boolean = false) : InferenceEngine {
        override val state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.ModelReady)
        private var options = InferenceOptions()
        var benchmarks = 0
        var gpuValidations = 0
        var cleanups = 0
        override suspend fun configure(options: InferenceOptions) { this.options = options }
        private fun layers() = if (fallback) 0 else options.gpuLayers.coerceAtMost(36)
        override suspend fun diagnostics() = "Vulkan driver: Adreno test driver\nGPU layers: ${layers()}\nContext: ${options.contextSize}\nMicro-batch: ${options.microBatchSize}"
        override fun fastMetrics() = ""
        override fun cancelGeneration() { }
        override fun setThreadLimit(threads: Int, batchThreads: Int) { }
        override suspend fun loadModel(pathToModel: String) { }
        override suspend fun setSystemPrompt(systemPrompt: String) { }
        override fun sendUserPrompt(message: String, predictLength: Int) = emptyFlow<String>()
        override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String {
            benchmarks++
            return if (layers() > 0) "Prompt: 80 tokens/s\nGeneration: ${if (layers() == 36) 24 else 20} tokens/s" else "Prompt: 50 tokens/s\nGeneration: 12 tokens/s"
        }
        override suspend fun validateBackend(captureCpuReference: Boolean): String {
            if (!captureCpuReference) {
                gpuValidations++
                if (cancelValidation) throw CancellationException("Test cancellation")
            }
            return "{\"passed\":true,\"samples\":12}"
        }
        override suspend fun cleanUp() { cleanups++ }
        override suspend fun destroy() { }
    }
}
