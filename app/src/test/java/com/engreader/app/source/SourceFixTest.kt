package com.engreader.app.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions for the source-layer defects found in review. Every test here fails
 * against the code as it was before the fix.
 */
class SourceFixTest {

    // --- C2: named HTML entities in a feed crashed the repair path -------------------

    @Test
    fun `a named entity is rewritten instead of throwing`() {
        // `sanitize` is the repair path for the entities a strict XML parser rejects, so
        // it is handed exactly the input that used to blow up in it: the regex had no
        // capture group while the code read `groupValues[1]`.
        assertEquals("&#32;", RssParser.sanitize("&nbsp;"))
        assertEquals("&#8212;", RssParser.sanitize("&mdash;"))
        assertEquals("&#8217;", RssParser.sanitize("&rsquo;"))
        assertEquals("&#8230;", RssParser.sanitize("&hellip;"))
    }

    @Test
    fun `entities inside a sentence keep the words around them`() {
        assertEquals(
            "a&#32;b&#8212;c",
            RssParser.sanitize("a&nbsp;b&mdash;c"),
        )
    }

    @Test
    fun `the references XML already defines are left alone`() {
        // Rewriting these would double-escape text that a strict parser accepts as-is.
        assertEquals("&amp;", RssParser.sanitize("&amp;"))
        assertEquals("&#160;", RssParser.sanitize("&#160;"))
        assertEquals("&#xA0;", RssParser.sanitize("&#xA0;"))
    }

    @Test
    fun `an unknown entity is escaped rather than dropped`() {
        assertEquals("&amp;foo;", RssParser.sanitize("&foo;"))
    }

    @Test
    fun `a bare ampersand is escaped`() {
        assertEquals("Tom &amp; Jerry", RssParser.sanitize("Tom & Jerry"))
    }

    // --- I40: paragraphs that are never closed ---------------------------------------

    @Test
    fun `a page whose paragraphs are never closed still yields them`() {
        // Legal HTML5, and what a truncated or JSON-rendered page looks like. The old
        // pattern demanded `</p>`, matched nothing, and the extractor fell through to
        // `fallbackBlocks`, which found no container `<p>` sits in — so the caller got
        // an unusable body and silently downgraded to the two-line feed summary.
        val html = """
            <html><body><article>
            <p>Parliament voted on Tuesday to send the bill back to committee for a second reading.
            <p>The opposition said the delay would cost another six months of preparation.
            <p>Ministers insisted the timetable was unchanged and the vote would be held in spring.
            </article></body></html>
        """.trimIndent()

        val paragraphs = ArticleExtractor.extract(html).paragraphs
        assertTrue("expected the unclosed paragraphs to survive", paragraphs.size >= 3)
        assertTrue(paragraphs.first().startsWith("Parliament voted on Tuesday"))
    }

    @Test(timeout = 5_000)
    fun `a long page with unclosed paragraphs is read in linear time`() {
        // Measured before the fix at 512 MB of heap: 324 KB took 3.56 s and 648 KB took
        // 14.9 s — the lazy group scanned to the end of the document for every `<p>`,
        // found no closing tag, and started over. Bounding the group at the next `<p`
        // makes the pass linear, so this input is a fraction of a second.
        val html = buildString {
            append("<html><body><article>")
            repeat(8_000) {
                append("<p>Paragraph number ").append(it)
                append(" carries enough words to look like prose in a news article body.")
            }
            append("</article></body></html>")
        }

        val paragraphs = ArticleExtractor.extract(html).paragraphs
        assertTrue("expected a body, not a fallback", paragraphs.size >= 3)
    }

    // --- I24: a 304 answer is not a redirect -----------------------------------------

    @Test
    fun `304 is classified as not modified rather than as a redirect`() {
        // 304 sits inside 300..399, and the ordered `when` this replaced sent it down the
        // redirect arm, where the absent `Location` header raised a FetchException. Every
        // conditional refresh of an unchanged feed therefore failed, and the caller
        // cleared the list it had already shown.
        assertEquals(HttpOutcome.NotModified, statusOutcome(304))
    }

    @Test
    fun `the other status classes keep their meaning`() {
        assertEquals(HttpOutcome.Redirect, statusOutcome(301))
        assertEquals(HttpOutcome.Redirect, statusOutcome(302))
        assertEquals(HttpOutcome.Redirect, statusOutcome(399))
        assertEquals(HttpOutcome.Body, statusOutcome(200))
        assertEquals(HttpOutcome.Body, statusOutcome(206))
        assertEquals(HttpOutcome.Error, statusOutcome(404))
        assertEquals(HttpOutcome.Error, statusOutcome(100))
        assertEquals(HttpOutcome.Error, statusOutcome(400))
        assertEquals(HttpOutcome.Error, statusOutcome(500))
    }
}