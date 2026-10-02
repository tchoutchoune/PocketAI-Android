package com.pocketai.app

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DiagnosticsLogTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "diagnostics").deleteRecursively()
    }

    @Test fun redactsApiCredentialsAndUrlParameters() {
        val original = "Authorization: Bearer secretBearer api_key=secretKey " +
            "token=secretToken https://user:secretPassword@host.test?q=privatePrompt&key=secret " +
            "hf_abcdefghijk sk-abcdefghijk"
        val sanitized = DiagnosticsLog.redact(original)
        listOf("secretBearer", "secretKey", "secretToken", "secretPassword", "privatePrompt", "abcdefghijk")
            .forEach { assertFalse("Leaked $it", sanitized.contains(it)) }
        assertTrue(sanitized.contains("host.test"))
    }

    @Test fun exceptionMessagesAreExcludedFromDebugExport() {
        val log = DiagnosticsLog(context)
        log.failure("load", IllegalStateException("user prompt and private model response"))
        val export = log.snapshot()
        assertTrue(export.contains("IllegalStateException"))
        assertFalse(export.contains("user prompt"))
        assertFalse(export.contains("private model response"))
    }

    @Test fun rotatingLogsStayBoundedAndRetainRecentEvents() {
        val log = DiagnosticsLog(context)
        // Two 2 MiB generations are retained. Write enough data to force more than
        // one rotation so this test still verifies eviction of the oldest events.
        repeat(2400) { log.event("event-$it ${"x".repeat(2000)}") }
        val export = log.snapshot()
        assertTrue(export.length < 4_300_000)
        assertTrue(export.contains("event-2399"))
        assertFalse(export.contains("event-0 "))
        assertTrue(File(context.filesDir, "diagnostics").listFiles().orEmpty().size <= 2)
    }
}
