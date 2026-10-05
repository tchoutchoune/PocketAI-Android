package com.arm.aichat

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in device smoke test. Supply modelPath through instrumentation arguments. */
class DeviceInferenceTest {
    @Test(timeout = 300_000)
    fun cpuAndRequestedVulkanProduceTextAcrossTurns() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = InstrumentationRegistry.getArguments().getString("modelPath")
        assumeTrue("Supply a readable GGUF modelPath on the real device", path != null && File(path).canRead())
        val engine = AiChat.getInferenceEngine(context)
        try {
            for (layers in listOf(0, 128)) {
                engine.cleanUp()
                engine.configure(InferenceOptions(threads = 4, contextSize = 2048, batchSize = 256, gpuLayers = layers, temperature = 0f))
                engine.loadModel(path!!)
                engine.setSystemPrompt("Réponds en français, clairement et brièvement.")
                val first = engine.sendUserPrompt("Calcule 2 + 2 et écris le résultat.", 64).toList().joinToString("")
                assertTrue("Empty first response at layers=$layers", first.isNotBlank())
                val second = engine.sendUserPrompt("Écris une phrase sur Paris.", 64).toList().joinToString("")
                assertTrue("Empty second response at layers=$layers", second.isNotBlank())
                val diagnostics = engine.diagnostics()
                assertTrue(diagnostics, diagnostics.contains("last decode code: 0"))
                assertTrue(diagnostics, diagnostics.contains("Output placement policy:"))
                // CPU fallback is permitted: this is a reliability smoke test,
                // never a claim that the requested GPU offload actually ran.
            }
        } finally {
            engine.cleanUp()
            engine.destroy()
        }
    }
}
