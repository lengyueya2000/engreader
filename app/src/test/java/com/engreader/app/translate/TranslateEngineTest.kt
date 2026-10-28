package com.engreader.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-segment response shape, exercised without an HTTP call.
 *
 * `GoogleTranslateEngine.single` is private and talks to the network, so the parsing was
 * untested and a malformed segment was silently skipped: a three-sentence paragraph that
 * came back as one sentence was reported as a success even though the engine contract
 * promises a throw rather than a partial result.
 */
class GoogleSegmentParsingTest {

    @Test
    fun `segments join in order`() {
        val body = """[[["译文一。","Source one."],["译文二。","Source two."]],null,"en"]"""
        assertEquals("译文一。译文二。", joinSegments(body))
    }

    @Test
    fun `an empty slot contributes nothing`() {
        val body = """[[["译文一。",null],[null,null]],null,"en"]"""
        assertEquals("译文一。", joinSegments(body))
    }

    @Test
    fun `a segment that is not a string fails instead of dropping its text`() {
        val body = """[[["译文一。","Source one."],[42,null],[null]]]"""
        val error = runCatching { joinSegments(body) }.exceptionOrNull()
        assertTrue("expected TranslationException, got $error", error is TranslationException)
    }

    @Test
    fun `a body that is not the expected shape fails`() {
        assertTrue(runCatching { joinSegments("""{"error":"quota"}""") }.exceptionOrNull() is TranslationException)
        assertTrue(runCatching { joinSegments("not json") }.exceptionOrNull() is TranslationException)
    }

    @Test
    fun `an answer with no text at all fails`() {
        val error = runCatching { joinSegments("""[[[null,null]],null,"en"]""") }.exceptionOrNull()
        assertTrue("expected TranslationException, got $error", error is TranslationException)
    }
}