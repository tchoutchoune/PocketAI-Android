package com.arm.aichat

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capability probe only. It does not need a model and does not initialize OpenCL.
 * The native engine only checks whether Android's linker namespace exposes an
 * OpenCL entry point to this application package.
 */
class OpenClRuntimeProbeTest {
    private companion object {
        const val TAG = "PocketAI.OpenCLProbe"
    }

    @Test(timeout = 30_000)
    fun reportsOpenClRuntimeVisibilityWithoutModel() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = AiChat.getInferenceEngine(context)
        try {
            val diagnostics = engine.diagnostics()
            assertTrue(diagnostics, diagnostics.contains("OpenCL runtime probe:"))
            assertTrue(diagnostics, diagnostics.contains("libOpenCL.so"))
            assertTrue(diagnostics, diagnostics.contains("libOpenCL_adreno.so"))
            Log.i(TAG, diagnostics)
        } finally {
            engine.destroy()
        }
    }
}
