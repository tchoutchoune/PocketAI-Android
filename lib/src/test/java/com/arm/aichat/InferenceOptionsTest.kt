package com.arm.aichat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InferenceOptionsTest {
    @Test fun invalidMemoryAndComputeLimitsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { InferenceOptions(contextSize = 0) }
        assertThrows(IllegalArgumentException::class.java) { InferenceOptions(contextSize = 512, batchSize = 1024) }
        assertThrows(IllegalArgumentException::class.java) { InferenceOptions(threads = 0) }
        assertThrows(IllegalArgumentException::class.java) { InferenceOptions(gpuLayers = -1) }
        assertThrows(IllegalArgumentException::class.java) { InferenceOptions(batchSize = 64, microBatchSize = 128) }
        assertThrows(IllegalArgumentException::class.java) { InferenceOptions(microBatchSize = 0) }
        assertThrows(IllegalArgumentException::class.java) { InferenceOptions(temperature = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { InferenceOptions(temperature = Float.POSITIVE_INFINITY) }
    }

    @Test fun smallCpuProfileAndLargeGpuProfileAreSupported() {
        assertEquals(0, InferenceOptions(threads = 1, contextSize = 512, batchSize = 32).gpuLayers)
        assertEquals(256, InferenceOptions(contextSize = 32768, batchSize = 1024, gpuLayers = 256).gpuLayers)
    }
}
