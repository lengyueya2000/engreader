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
    fun `an inline tag does not push punctuation away from its word`() {
        // Every tag used to be replaced by a space, so a link or emphasis that
        // wrapped a word detached whatever followed it: this read "report ," and
        // "Gaza ." in the reader. Real pages wrap words in <a> constantly.
        assertEquals(
            "The report, published today, was clear.",
            Html.text("""The <a href="/x">report</a>, published today, was clear."""),
        )
        assertEquals(
            "It concluded Israel was guilty of genocide in Gaza.",
            Html.text("""It concluded Israel was guilty of genocide in <a href="/gaza">Gaza</a>."""),
        )
        assertEquals(
            "Nature (IUCN) Red List designation",
            Html.text("""Nature (<a href="/iucn">IUCN</a>) Red List designation"""),
        )
        assertEquals("It said \u201cno\u201d.", Html.text("It said <em>\u201cno\u201d</em>."))
    }

    @Test
    fun `a space before a closing mark or after an opening one is closed up`() {
        // Some pages really do write the space in the markup ("word <em>,</em>"),
        // and a non-breaking space decodes to one as well.
        assertEquals("one, two", Html.text("one&nbsp;, two"))
        assertEquals("(\u201cquoted\u201d)", Html.text("( \u201cquoted\u201d )"))
    }

    @Test
    fun `block tags still separate the text around them`() {
        // An inline tag is deleted, but a block element marks a real boundary: the
        // two sides must not run together into one word.
        assertEquals("one two", Html.text("<p>one</p><p>two</p>"))
        assertEquals("a b", Html.text("a<br/>b"))
    }

    @Test
    fun `screen-reader-only text does not reach the body`() {
        // The BBC hides ", external" after every outbound link, so a finished
        // sentence arrived as "…in a post on X., external".
        val html = """
            <html><body><p>Democratic Senator Chris Murphy told CNN that the idea was
            disgusting and reminiscent of executions carried out by the Islamic State
            group, he added<a href="/x"> in a post on X.
            <span class="visually-hidden">, external</span></a></p>
            <p>${"Officials said more details would follow. ".repeat(4)}</p>
            <p>${"The public will be able to watch the execution. ".repeat(4)}</p>
            </body></html>
        """.trimIndent()
        val body = ArticleExtractor.extract(html).body
        assertFalse(body.contains("external"))
        assertTrue(body.contains("in a post on X."))
    }

    @Test
    fun `a caption is not a body paragraph`() {
        // Both sites wrap captions in <figcaption> with an ordinary <p> inside, and
        // the extractor used to keep them as if they were prose.
        val html = """
            <html><body>
            <figure><img src="a.jpg"><figcaption><p>An undated handout image of Hasan
            that was released by authorities in 2012</p></figcaption></figure>
            <p>${"The public will be able to watch the execution. ".repeat(4)}</p>
            <p>${"Officials said more details would follow. ".repeat(4)}</p>
            <p>${"The decision was unprecedented in the modern era. ".repeat(4)}</p>
            </body></html>
        """.trimIndent()
        val body = ArticleExtractor.extract(html).body
        assertFalse(body.contains("undated handout image"))
        assertTrue(body.contains("watch the execution"))
    }

    @Test
    fun `the page footer is not a body paragraph`() {
        val html = """
            <html><body>
            <p>${"The public will be able to watch the execution. ".repeat(4)}</p>
            <p>${"Officials said more details would follow. ".repeat(4)}</p>
            <p>${"The decision was unprecedented in the modern era. ".repeat(4)}</p>
            <footer><p>Copyright © 2026 BBC. The BBC is not responsible for the content
            of external sites. Read about our approach to external linking.</p></footer>
            </body></html>
        """.trimIndent()
        val body = ArticleExtractor.extract(html).body
        assertFalse(body.contains("Copyright"))
    }

    @Test
    fun `a related-story card headline is not a body paragraph`() {
        // "Read next" blocks are ordinary <p> inside <a>, so they read as prose to a
        // length filter: the body carried them as if they were sentences.
        val html = """
            <html><body>
            <p>${"The public will be able to watch the execution. ".repeat(4)}</p>
            <p>${"Officials said more details would follow. ".repeat(4)}</p>
            <p>${"The decision was unprecedented in the modern era. ".repeat(4)}</p>
            <ul><li><a href="/other"><p>Fort Hood attacker to be executed by firing squad
            - a first for US military since World War Two</p></a></li></ul>
            </body></html>
        """.trimIndent()
        val body = ArticleExtractor.extract(html).body
        assertFalse(body.contains("a first for US military"))
        assertTrue(body.contains("watch the execution"))
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

    @Test
    fun `an attribute value containing a closing angle bracket does not end the tag`() {
        // `[^>]*` stopped at the `>` inside the quoted value, so the tag was never
        // matched and `3">alpha` leaked into the text.
        assertEquals("alpha beta", Html.text("""<p title="5 > 3">alpha beta</p>"""))
        assertEquals("gamma delta", Html.text("""<p data-x='a>b'>gamma delta</p>"""))
    }

    @Test
    fun `a lone surrogate or an out-of-range code point is left as written`() {
        // Neither can be encoded as UTF-8; decoding them produces a String that
        // breaks the moment it is written back out.
        assertEquals("&#xD800;", Html.decode("&#xD800;"))
        assertEquals("&#x110000;", Html.decode("&#x110000;"))
        assertEquals("&#0;", Html.decode("&#0;"))
    }

    @Test
    fun `latin-1 named entities are decoded`() {
        assertEquals("café ß ½", Html.decode("caf&eacute; &szlig; &frac12;"))
        assertEquals("a b", Html.decode("a&ensp;b"))
    }

    @Test
    fun `a namespaced element is matched by its local name`() {
        // Namespaces are not processed, so the raw QName arrives with its prefix;
        // matching that against bare names dropped every media:, dc: and content:
        // element in the live feeds.
        assertEquals("content", RssParser.localNameOf("media:content"))
        assertEquals("date", RssParser.localNameOf("dc:date"))
        assertEquals("title", RssParser.localNameOf("title"))
    }

    @Test
    fun `a nested container is not cut short by its inner closing tag`() {
        // The lazy `<(div|section|…)>.*?</\1>` ended at the first closing tag of any
        // of the names, so `<div><div>a</div>b</div>` yielded `a` and dropped `b` —
        // and on a page with no usable `<p>`, that is the body text.
        val prose = "Officials said the decision was unprecedented and would be reviewed again. "
        val html = """
            <html><body><div><div>$prose</div>$prose$prose</div></body></html>
        """.trimIndent()
        val body = ArticleExtractor.extract(html).body
        // Three copies of the sentence, not one.
        assertEquals(3, Regex("unprecedented").findAll(body).count())
    }
}
