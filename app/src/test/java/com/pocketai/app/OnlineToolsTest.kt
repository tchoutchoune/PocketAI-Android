package com.pocketai.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OnlineToolsTest {
    @Test fun authenticatedFalFollowupsCannotForwardTheKeyToAnotherHost() {
        assertEquals("queue.fal.run", OnlineTools.falQueueUrl("https://queue.fal.run/fal-ai/wan/requests/123/status").host)
        for (value in listOf(
            "https://evil.example/fal-ai/wan/requests/123/status",
            "https://queue.fal.run.evil.example/fal-ai/wan/requests/123/status",
            "https://queue.fal.run:8443/fal-ai/wan/requests/123/status",
            "https://user:secret@queue.fal.run/fal-ai/wan/requests/123/status",
            "http://queue.fal.run/fal-ai/wan/requests/123/status",
            "https://queue.fal.run/other-provider/requests/123/status"
        )) assertThrows(OnlineToolException::class.java) { OnlineTools.falQueueUrl(value) }
    }

    @Test fun providerBaseUrlRequiresHttpsAndNoEmbeddedCredentials() {
        assertEquals("https://api.openai.com/v1/images/generations", OnlineTools.imageEndpoint("https://api.openai.com/v1/").toString())
        for (value in listOf("http://example.com/v1", "https://user:key@example.com/v1", "https://localhost/v1", "https://example.local/v1", "https://example.com/v1?key=secret", "https://example.com/v1#fragment")) {
            assertThrows(OnlineToolException::class.java) { OnlineTools.imageEndpoint(value) }
        }
    }

    @Test fun untrustedSearchResultsCannotCreateJavascriptLinks() {
        val response = JSONObject("""{"web":{"results":[
          {"title":"Bad","url":"javascript:alert(1)","description":"unsafe"},
          {"title":"Bad","url":"https://key:secret@example.org/","description":"unsafe"},
          {"title":"<b>Title &amp; source</b>","url":"https://example.org/page","description":"Some <strong>facts</strong>"}
        ]}}""")
        val sources = OnlineTools.parseWebSources(response)
        assertEquals(1, sources.size)
        assertEquals("Title & source", sources[0].title)
        assertEquals("Some facts", sources[0].snippet)
    }

    @Test fun blankOrOversizedPromptsFailBeforeCallingProvider() {
        assertThrows(OnlineToolException::class.java) { OnlineTools.imagePayload("  ", "gpt-image-1") }
        assertThrows(OnlineToolException::class.java) { OnlineTools.videoPayload("x".repeat(8001), "fal-ai/wan/v2.2-a14b/text-to-video") }
    }

    @Test fun gptImageDoesNotReceiveUnsupportedResponseFormat() {
        val gpt = OnlineTools.imagePayload("Un paysage", "gpt-image-1")
        assertFalse(gpt.has("response_format"))
        assertEquals("b64_json", OnlineTools.imagePayload("Un paysage", "dall-e-3").getString("response_format"))
    }

    @Test fun wanUsesDocumentedShortVideoSchemaAndGenericFalDoesNotReceiveWanOptions() {
        val wan = OnlineTools.videoPayload("Un paysage", "fal-ai/wan/v2.2-a14b/text-to-video")
        assertEquals(81, wan.getInt("num_frames"))
        assertEquals(16, wan.getInt("frames_per_second"))
        assertEquals("480p", wan.getString("resolution"))
        assertTrue(wan.getBoolean("enable_safety_checker"))
        assertEquals(setOf("prompt"), OnlineTools.videoPayload("Un paysage", "fal-ai/another-model").keys().asSequence().toSet())
    }

    @Test fun mediaChecksRejectHtmlAndMislabeledImageContainers() {
        assertNull(OnlineTools.detectedMediaType("<html>Access denied</html>".toByteArray(), false))
        assertEquals("image/png", OnlineTools.detectedMediaType(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a), false))
        assertNull(OnlineTools.detectedMediaType(byteArrayOf(0,0,0,20) + "ftypavif".toByteArray(), true))
        assertEquals("video/mp4", OnlineTools.detectedMediaType(byteArrayOf(0,0,0,20) + "ftypisom".toByteArray(), true))
    }

    @Test fun webCitationsCannotBreakMarkdownWithTitlesOrParenthesesInPaths() {
        val source = WebSource("A [title]\n<script>*", "https://example.org/a(b)c", "Description")
        val citation = source.markdownCitation(1)
        assertEquals("[1. A \\[title\\] &lt;script&gt;\\*](<https://example.org/a%28b%29c>)", citation)
        assertFalse(citation.contains('\n'))
    }
}
