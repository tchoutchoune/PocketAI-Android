package com.pocketai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseTextTest {
    @Test fun hidesCompletedReasoningAndKeepsMarkdown() {
        assertEquals("## Résultat\n\n**Bonjour**", ResponseText.visible("<think>private reasoning</think>\n## Résultat\n\n**Bonjour**"))
    }

    @Test fun keepsTextBeforeReasoningDuringStreaming() {
        assertEquals("Bonjour", ResponseText.visible("Bonjour\n<think>reasoning not finished"))
        assertEquals("Bonjour\n\nAprès", ResponseText.visible("Bonjour\n<think>reasoning</think>\nAprès"))
    }

    @Test fun hidesPrefixWhenModelOnlyEmitsClosingReasoningTag() {
        assertEquals("Réponse", ResponseText.visible("Private reasoning from a chat template\n</think>\nRéponse"))
    }

    @Test fun handlesMultipleAndNestedReasoningSections() {
        assertEquals("A B C", ResponseText.visible("A <think>one<think>nested</think>end</think>B <think>two</think>C"))
    }

    @Test fun acceptsUppercaseReasoningTags() {
        assertEquals("Visible", ResponseText.visible("<THINK>hidden</THINK>Visible"))
    }

    @Test fun hidesPartialReasoningDelimitersWithoutHidingExistingAnswer() {
        for (partial in listOf("<", "<t", "<thi", "<think", "</", "</thi", "</think")) {
            assertEquals("Visible", ResponseText.visible("Visible$partial"))
        }
    }

    @Test fun doesNotRewriteUnknownTagsOrComparisonExpressions() {
        assertEquals("a < b and <thought>literal</thought>", ResponseText.visible("a < b and <thought>literal</thought>"))
        assertEquals("<think invalid>literal", ResponseText.visible("<think invalid>literal"))
    }

    @Test fun keepsReasoningTagsInsideCodeAndMarkdownFencesExact() {
        val text = "Example:\n```html\n<think>literal</think>\n<|im_end|>\n```\nEnd"
        assertEquals(text, ResponseText.visible(text))
        val tildes = "~~~xml\n</think>\n~~~\nVisible"
        assertEquals(tildes, ResponseText.visible(tildes))
    }

    @Test fun removesKnownTemplateDelimitersAndRoleHeadersOnly() {
        assertEquals("Hello", ResponseText.visible("<|begin_of_text|><|start_header_id|>assistant<|end_header_id|>\nHello<|eot_id|>"))
        assertEquals("Hello", ResponseText.visible("<|im_start|>assistant\nHello<|im_end|>"))
        assertEquals("<|custom|>Hello", ResponseText.visible("<|custom|>Hello"))
    }

    @Test fun exportsCompleteNamedCodeWithoutChangingItsContents() {
        val body = "print(\"<think>literal</think>\")\n\n"
        val files = ResponseText.extractFiles("Voici le fichier:\n```python filename=demo.py\n${body}```")
        assertEquals(1, files.size)
        assertEquals("demo.py", files.single().name)
        assertEquals("text/plain", files.single().mimeType)
        assertEquals(body, files.single().content)
    }

    @Test fun acceptsQuotedSafeFileNamesAndMimeTypes() {
        val files = ResponseText.extractFiles("```json file=\"mon fichier.json\"\n{\"ok\": true}\n```\n\n~~~svg filename=diagram.svg\n<svg/>\n~~~")
        assertEquals(listOf("mon fichier.json", "diagram.svg"), files.map { it.name })
        assertEquals(listOf("application/json", "image/svg+xml"), files.map { it.mimeType })
    }

    @Test fun neverExportsReasoningOrPartialFiles() {
        val raw = "<think>```json\n{\"secret\":true}\n```\n</think>```python\nunfinished"
        assertTrue(ResponseText.extractFiles(raw).isEmpty())
    }

    @Test fun refusesTraversalAndHiddenFileNames() {
        for (name in listOf("../../secret.py", "/private.py", "C:\\secret.py", ".hidden", "..")) {
            val file = ResponseText.extractFiles("```python file=$name\nprint(1)\n```").single()
            assertEquals("fichier-1.py", file.name)
            assertFalse(file.name.contains('/'))
            assertFalse(file.name.contains('\\'))
        }
    }

    @Test fun boundsFileCountAndSkipsOversizedFiles() {
        val many = (1..12).joinToString("\n") { "```text\n$it\n```" }
        assertEquals(8, ResponseText.extractFiles(many).size)
        assertTrue(ResponseText.extractFiles("```text\n${"x".repeat(512 * 1024 + 1)}\n```").isEmpty())
    }

    @Test fun longerOpeningFencesAllowLiteralShorterFences() {
        val text = "````markdown filename=readme.md\n```python\nprint(1)\n```\n````"
        assertEquals(text, ResponseText.visible(text))
        assertEquals("```python\nprint(1)\n```\n", ResponseText.extractFiles(text).single().content)
    }
}
