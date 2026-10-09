package com.engreader.app.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions for bugs found in review. Each test fails against the code as it was
 * before the fix.
 */
class SourceRegressionTest {

    @Test
    fun `a headline ending in the letters live is not a live blog`() {
        // `endsWith("live")` flagged any headline whose last word merely ended in
        // those letters.
        assertFalse(LiveBlog.isLive("The will to live"))
        assertFalse(LiveBlog.isLive("How to live"))
        assertFalse(LiveBlog.isLive("Can we learn to live"))
    }

    @Test
    fun `real live markers are still recognised`() {
        assertTrue(LiveBlog.isLive("Protests continue across France \u2013 live"))
        assertTrue(LiveBlog.isLive("Election results live"))
        assertTrue(LiveBlog.isLive("Storm tracker: live updates"))
        assertTrue(LiveBlog.isLive("Some headline", "https://example.com/live/2026/oct/07/thing"))
    }

    @Test
    fun `a comparison in prose is not stripped as markup`() {
        // `Regex("<[^>]+>")` deleted everything between the angle brackets, so the
        // comparison silently vanished from the article text.
        assertEquals("The rule is x < y > z in maths.", Html.text("The rule is x < y > z in maths."))
    }

    @Test
    fun `real tags are still stripped`() {
        assertEquals("Hello world", Html.text("<p>Hello <b>world</b></p>"))
        assertEquals("a b", Html.text("a<br/>b"))
    }

    @Test
    fun `uppercase hex numeric entities are decoded`() {
        assertEquals("it's here", Html.decode("it&#X27;s here"))
        assertEquals("it's here", Html.decode("it&#x27;s here"))
        assertEquals("A", Html.decode("&#X41;"))
    }

    @Test
    fun `double-encoded entities are decoded`() {
        assertEquals("it's here", Html.decode("it&amp;#39;s here"))
        assertEquals("a & b", Html.decode("a &amp;amp; b"))
    }

    @Test
    fun `a meta tag is found whatever order its attributes are in`() {
        val contentFirst =
            """<html><head><meta content="Jane Doe" name="author"></head><body></body></html>"""
        val nameFirst =
            """<html><head><meta name="author" content="Jane Doe"></head><body></body></html>"""
        assertEquals("Jane Doe", ArticleExtractor.extract(contentFirst).author)
        assertEquals("Jane Doe", ArticleExtractor.extract(nameFirst).author)
    }

    @Test
    fun `an apostrophe inside a meta value does not truncate it`() {
        // The old pattern ended the capture at the first quote of either kind, so
        // this title came back as "Britain".
        val html =
            """<html><head><meta property="og:title" content="Britain's economy slows"></head></html>"""
        assertEquals("Britain's economy slows", ArticleExtractor.extract(html).title)
    }

    @Test
    fun `itemprop dates are read`() {
        val html =
            """<html><head><meta itemprop="datePublished" content="2026-10-07T12:00:00Z"></head></html>"""
        // 2026-10-07T12:00:00Z, in UTC.
        assertEquals(1_791_374_400_000L, ArticleExtractor.extract(html).publishedAt)
    }

    @Test
    fun `an ISO timestamp with a Z suffix is parsed as UTC`() {
        // The `'Z'` pattern treated it as a literal, so the value was read as local
        // time and shifted by the device's UTC offset.
        assertEquals(1_791_374_400_000L, RssParser.parseDate("2026-10-07T12:00:00Z"))
        assertEquals(1_791_374_400_000L, RssParser.parseDate("2026-10-07T12:00:00+00:00"))
        assertEquals(1_791_374_400_123L, RssParser.parseDate("2026-10-07T12:00:00.123Z"))
        assertEquals(1_791_374_400_000L, RssParser.parseDate("Tue, 07 Oct 2026 12:00:00 +0000"))
    }

    @Test
    fun `an explicit offset is respected`() {
        // 12:00 at +08:00 is 04:00 UTC.
        assertEquals(1_791_345_600_000L, RssParser.parseDate("2026-10-07T12:00:00+08:00"))
    }

    @Test
    fun `an unparseable date is zero rather than a wrong instant`() {
        assertEquals(0L, RssParser.parseDate(""))
        assertEquals(0L, RssParser.parseDate("not a date"))
    }
}
