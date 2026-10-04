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
        assertEquals(1, engine.cpuValidations)
        assertEquals(0, model.gpuBlacklistCount)
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
        assertEquals(7, engine.benchmarks)
        assertEquals(13, engine.gpuValidations)
        assertEquals(1, engine.cpuValidations)
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

    @Test fun failedGpuMetricsSurviveFinalCpuReloadAndPreventGpuBenchmark() = runBlocking {
        val engine = FakeEngine(gpuFailureReason = "numeric_mismatch")
        inject(engine)
        model.compareVulkanBackends(file)
        assertEquals(1, engine.benchmarks)
        assertEquals("cpu-performance", model.performanceMode)
        assertEquals(1, model.gpuBlacklistCount)
        assertTrue(model.state.value.diagnostics.contains("raison=numeric_mismatch"))
        assertTrue(model.state.value.diagnostics.contains("JS=0.08"))
        assertTrue(model.state.value.diagnostics.contains("RMS relatif=0.11"))
        assertTrue(model.state.value.backendComparison.contains("Contrôle CPU contre CPU : validé"))
    }

    @Test fun temporaryAllocationFailureDoesNotPersistGpuBlacklist() = runBlocking {
        val engine = FakeEngine(gpuFailureReason = "context_allocation_failed")
        inject(engine)
        model.compareVulkanBackends(file)
        assertEquals(0, model.gpuBlacklistCount)
        assertEquals(1, engine.benchmarks)
        assertTrue(model.state.value.diagnostics.contains("raison=context_allocation_failed"))
        assertFalse(app.getSharedPreferences("pocketai", 0).all.keys.any { it.startsWith("vulkan_recipe_") })
    }

    @Test fun failedCpuSelfControlStopsBeforeAnyGpuAttempt() = runBlocking {
        val engine = FakeEngine(cpuSelfFailure = true)
        inject(engine)
        val history = model.state.value.messages
        try { model.compareVulkanBackends(file); fail("CPU self-control must stop the comparison") }
        catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("GPU non évalué")) }
        assertEquals(0, engine.gpuLoads)
        assertEquals(0, engine.benchmarks)
        assertEquals(0, model.gpuBlacklistCount)
        assertEquals(history, model.state.value.messages)
        assertTrue(model.state.value.backendComparison.contains("Contrôle CPU contre CPU : refusé"))
    }

    @Test fun singleTokenRescueCanBeSelectedAndCachedAfterValidation() = runBlocking {
        val engine = FakeEngine(gpuFailureReason = "numeric_mismatch", rescueOnly = true)
        inject(engine)
        model.compareVulkanBackends(file)
        assertEquals(3, engine.benchmarks)
        assertEquals("performance", model.performanceMode)
        assertEquals(0, model.gpuBlacklistCount)
        val recipe = app.getSharedPreferences("pocketai", 0).all.entries.single { it.key.startsWith("vulkan_recipe_") }
        assertEquals(1, JSONObject(recipe.value.toString()).getInt("microBatch"))
        assertTrue(model.state.value.backendComparison.contains("GPU 16 couches · lot 1 après mesure : validé"))
    }

    @Test fun slowerValidatedGpuIsNotCachedForFutureLoading() = runBlocking {
        val engine = FakeEngine(slowerGpu = true)
        inject(engine)
        model.compareVulkanBackends(file)
        assertEquals("cpu-performance", model.performanceMode)
        assertEquals(0, model.gpuBlacklistCount)
        assertFalse(app.getSharedPreferences("pocketai", 0).all.keys.any { it.startsWith("vulkan_recipe_") })
    }

    @Test fun finalAllocationFailureDoesNotBlacklistEarlierValidatedRescue() = runBlocking {
        val engine = FakeEngine(gpuFailureReason = "numeric_mismatch", rescueOnly = true,
            finalFailureReason = "context_allocation_failed")
        inject(engine)
        model.compareVulkanBackends(file)
        assertEquals("cpu-performance", model.performanceMode)
        assertEquals(0, model.gpuBlacklistCount)
        assertTrue(model.state.value.backendComparison.contains("GPU retenu · contrôle final : refusé"))
        assertFalse(app.getSharedPreferences("pocketai", 0).all.keys.any { it.startsWith("vulkan_recipe_") })
    }

    @Test fun adrenoProbesAlignPoliciesAndMeasureStandardCpuDriftSeparately() = runBlocking {
        val engine = FakeEngine(alignedPolicy = true, standardCpuMismatch = true)
        inject(engine)
        model.compareVulkanBackends(file)
        assertEquals(listOf(true, true, false), engine.cpuProbePolicies)
        assertTrue(engine.gpuProbePolicies.all { it })
        assertEquals(13, engine.gpuProbePolicies.size)
        assertEquals(2, engine.cpuValidations)
        assertEquals("performance", model.performanceMode)
        assertEquals(0, model.gpuBlacklistCount)
        assertTrue(model.state.value.backendComparison.contains("CPU standard contre CPU au profil GPU : refusé"))
    }

    @Test fun alignedGpuMismatchStillPreventsBenchmarkAndCaching() = runBlocking {
        val engine = FakeEngine(alignedPolicy = true, gpuFailureReason = "numeric_mismatch")
        inject(engine)
        model.compareVulkanBackends(file)
        assertEquals(listOf(true, true, false), engine.cpuProbePolicies)
        assertEquals(1, engine.benchmarks)
        assertEquals("cpu-performance", model.performanceMode)
        assertEquals(1, model.gpuBlacklistCount)
        assertFalse(app.getSharedPreferences("pocketai", 0).all.keys.any { it.startsWith("vulkan_recipe_") })
    }

    private class FakeEngine(val fallback: Boolean = false, val cancelValidation: Boolean = false,
        val gpuFailureReason: String? = null, val cpuSelfFailure: Boolean = false,
        val rescueOnly: Boolean = false, val slowerGpu: Boolean = false,
        val finalFailureReason: String? = null, val alignedPolicy: Boolean = false,
        val standardCpuMismatch: Boolean = false) : InferenceEngine {
        override val state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.ModelReady)
        private var options = InferenceOptions()
        var benchmarks = 0
        var gpuValidations = 0
        var cpuValidations = 0
        var gpuLoads = 0
        var cleanups = 0
        val cpuProbePolicies = mutableListOf<Boolean>()
        val gpuProbePolicies = mutableListOf<Boolean>()
        override suspend fun configure(options: InferenceOptions) { this.options = options }
        private fun layers() = if (fallback) 0 else options.gpuLayers.coerceAtMost(36)
        override suspend fun diagnostics() = "Vulkan driver: Adreno test driver\nGPU layers: ${layers()}\nContext: ${options.contextSize}\nMicro-batch: ${options.microBatchSize}"
        override fun fastMetrics() = ""
        override fun cancelGeneration() { }
        override fun setThreadLimit(threads: Int, batchThreads: Int) { }
        override suspend fun loadModel(pathToModel: String) { if (layers() > 0) gpuLoads++ }
        override suspend fun setSystemPrompt(systemPrompt: String) { }
        override fun sendUserPrompt(message: String, predictLength: Int) = emptyFlow<String>()
        override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String {
            benchmarks++
            if (layers() > 0 && slowerGpu) return "Prompt: 20 tokens/s\nGeneration: 10 tokens/s"
            return if (layers() > 0) "Prompt: 80 tokens/s\nGeneration: ${if (layers() == 36) 24 else 20} tokens/s" else "Prompt: 50 tokens/s\nGeneration: 12 tokens/s"
        }
        override suspend fun validateBackend(captureCpuReference: Boolean, comparableContext: Boolean): String {
            if (layers() > 0) gpuProbePolicies += comparableContext else cpuProbePolicies += comparableContext
            if (!captureCpuReference) {
                if (layers() > 0) {
                    gpuValidations++
                    if (cancelValidation) throw CancellationException("Test cancellation")
                    if (finalFailureReason != null && gpuLoads == 7) {
                        return JSONObject().put("passed", false).put("samples", 0)
                            .put("reason", finalFailureReason).put("stage", "context_allocation").toString()
                    }
                    if (gpuFailureReason != null && (!rescueOnly || options.microBatchSize != 1)) {
                        return JSONObject().put("passed", false).put("samples", if (gpuFailureReason == "numeric_mismatch") 12 else 0)
                            .put("reason", gpuFailureReason).put("stage", "logit_comparison")
                            .put("maxJs", 0.08).put("maxRelativeRmse", 0.11).put("failureMask", 12).toString()
                    }
                } else {
                    cpuValidations++
                    if (standardCpuMismatch && !comparableContext) return "{\"passed\":false,\"samples\":12,\"reason\":\"numeric_mismatch\"}"
                    if (cpuSelfFailure) return "{\"passed\":false,\"samples\":12,\"reason\":\"numeric_mismatch\"}"
                }
            }
            return JSONObject().put("passed", true).put("samples", 12)
                .put("probePolicy", if (alignedPolicy) "adreno840-f32-cpu-attention" else "standard").toString()
        }
        override suspend fun cleanUp() { cleanups++ }
        override suspend fun destroy() { }
    }
}
