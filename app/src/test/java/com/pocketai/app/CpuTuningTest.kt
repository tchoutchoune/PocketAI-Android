package com.pocketai.app

import org.junit.Assert.*
import org.junit.Test

class CpuTuningTest {
    @Test fun promptAndGenerationCanSelectDifferentWorkers() {
        val chosen = CpuTuning.select(listOf(CpuSample(3, 20.0, 10.0), CpuSample(6, 45.0, 9.0), CpuSample(8, 44.0, 8.0)))
        assertEquals(CpuThreadSelection(3, 6), chosen)
    }
    @Test fun equivalentSpeedsPreferFewerWorkers() {
        val chosen = CpuTuning.select(listOf(CpuSample(3, 29.0, 9.6), CpuSample(6, 30.0, 10.0)))
        assertEquals(CpuThreadSelection(3, 3), chosen)
    }
    @Test fun invalidSamplesNeverWinOrHideAnEmptyBenchmark() {
        val chosen = CpuTuning.select(listOf(CpuSample(2, 12.0, 8.0), CpuSample(8, Double.NaN, 90.0), CpuSample(4, 0.0, 20.0)))
        assertEquals(CpuThreadSelection(2, 2), chosen)
        assertThrows(IllegalArgumentException::class.java) { CpuTuning.select(listOf(CpuSample(3, 0.0, 0.0))) }
    }
}
