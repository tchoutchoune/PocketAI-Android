package com.arm.aichat

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in real-device smoke test.
 *
 * Supply a GGUF through the modelPath instrumentation argument. The test deliberately
 * checks deterministic semantics as well as non-empty text so finite-but-corrupted
 * Vulkan logits (for example the repeated '@' output observed on Adreno 840) cannot
 * be mistaken for a successful inference.
 */
class DeviceInferenceTest {
    private companion object {
        const val TAG = "PocketAI.DeviceTest"
    }

    private fun longestRepeatedCharRun(text: String): Int {
        var best = 0
        var run = 0
        var previous: Char? = null
        for (char in text) {
            if (char == previous) {
                run++
            } else {
                previous = char
                run = 1
            }
            best = maxOf(best, run)
        }
        return best
    }

    private fun assertHealthy(label: String, text: String) {
        val visible = text.filterNot(Char::isWhitespace)
        assertTrue("$label returned an empty response", visible.isNotEmpty())
        assertFalse("$label returned invalid UTF-8 replacement characters: $text", text.contains('\uFFFD'))
        assertTrue("$label contains no letter or digit: $text", visible.any(Char::isLetterOrDigit))
        assertTrue(
            "$label looks degenerate (repeated character run >= 4): $text",
            longestRepeatedCharRun(visible) < 4,
        )
    }

    private fun assertContainsNumber(label: String, text: String, expected: Int) {
        val number = Regex("(^|\\D)${expected}(\\D|$)")
        assertTrue("$label did not contain the expected number $expected: $text", number.containsMatchIn(text))
    }

    @Test(timeout = 300_000)
    fun cpuAndRequestedVulkanProduceCoherentTextAcrossTurns() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val path = InstrumentationRegistry.getArguments().getString("modelPath")
        assumeTrue("Supply a readable GGUF modelPath on the real device", path != null && File(path).canRead())

        val engine = AiChat.getInferenceEngine(context)
        try {
            for (layers in listOf(0, 128)) {
                engine.cleanUp()
                engine.configure(
                    InferenceOptions(
                        threads = 4,
                        contextSize = 2048,
                        batchSize = 256,
                        gpuLayers = layers,
                        temperature = 0f,
                    ),
                )
                engine.loadModel(path!!)
                engine.setSystemPrompt("Réponds en français. Pour les tests numériques, suis exactement le format demandé.")

                val first = engine.sendUserPrompt(
                    "Calcule 2 + 2. Réponds uniquement par le nombre obtenu.",
                    32,
                ).toList().joinToString("")
                assertHealthy("first response at layers=$layers", first)
                assertContainsNumber("first response at layers=$layers", first, 4)

                val second = engine.sendUserPrompt(
                    "Ajoute 1 au résultat que tu viens de donner. Réponds uniquement par le nombre obtenu.",
                    32,
                ).toList().joinToString("")
                assertHealthy("second response at layers=$layers", second)
                assertContainsNumber("second response at layers=$layers", second, 5)

                val diagnostics = engine.diagnostics()
                assertTrue(diagnostics, diagnostics.contains("last decode code: 0"))
                assertTrue(diagnostics, diagnostics.contains("Output placement policy:"))
                if (layers == 0) {
                    assertTrue(diagnostics, diagnostics.contains("Requested: CPU; active: CPU"))
                } else {
                    assertTrue(diagnostics, diagnostics.contains("Requested: Vulkan; active:"))
                }

                // Diagnostics contain backend state only, never prompts/model paths.
                // Logcat is captured by scripts/device-smoke.sh for auditable device evidence.
                Log.i(TAG, "requested_layers=$layers\n$diagnostics")
            }
        } finally {
            engine.cleanUp()
            engine.destroy()
        }
    }
}
