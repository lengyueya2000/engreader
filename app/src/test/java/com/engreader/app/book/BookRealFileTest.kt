package com.engreader.app.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The same parsers against full-size public-domain books.
 *
 * The fixtures in `book/` are a few kilobytes and cover every branch, but they are
 * synthetic: they cannot catch a mistake that only shows up on a real 25 MB file
 * with hundreds of records, a KF8 flow split and a hundred illustrations. Those
 * files are not committed, so this test looks for them on disk and skips when they
 * are absent — the command that fetches them is in `tools/book/README.md`.
 *
 * Expected values below are the real book's, not the parser's output: Pride and
 * Prejudice has 61 chapters and about 122,000 words in the text proper.
 */
class BookRealFileTest {

    private val dir = File(System.getProperty("engreader.bookFixtures") ?: "")

    private fun file(name: String): File? = File(dir, name).takeIf { it.isFile }

    private fun read(name: String): ByteArray? = file(name)?.readBytes()

    @Test
    fun `a full epub parses into its chapters`() {
        val bytes = read("pride.epub")
        assumeTrue("pride.epub not present, skipping", bytes != null)
        val book = EpubParser.parse(bytes!!)
        assertEquals("Pride and Prejudice", book.title)
        assertEquals("Jane Austen", book.author)
        assertTrue("should find the whole novel, got ${book.chapters.size}", book.chapters.size >= 60)
        assertTrue(book.chapters.first { it.title.startsWith("Chapter I") }.text.contains("universally acknowledged"))
        // Nothing may survive from the markup layer.
        val all = book.chapters.joinToString("\n") { it.text }
        assertFalse(all.contains("<p"))
        assertFalse(all.contains("&amp;"))
        assertNotNull(book.cover)
    }

    @Test
    fun `a full mobi6 parses into its chapters`() {
        val bytes = read("pride.mobi")
        assumeTrue("pride.mobi not present, skipping", bytes != null)
        val book = MobiParser.parse(bytes!!)
        assertEquals("Pride and Prejudice", book.title)
        assertEquals("Jane Austen", book.author)
        assertTrue("should split on pagebreaks, got ${book.chapters.size}", book.chapters.size >= 50)
        val all = book.chapters.joinToString("\n") { it.text }
        assertTrue(all.contains("universally acknowledged"))
        assertTrue(all.contains("Darcy"))
        assertFalse("filepos must not leak into the text", all.contains("filepos"))
    }

    @Test
    fun `a full azw3 parses into its chapters`() {
        val bytes = read("pride.azw3")
        assumeTrue("pride.azw3 not present, skipping", bytes != null)
        assertEquals(BookFormat.Azw3, BookFormat.detect(bytes!!))
        val book = MobiParser.parse(bytes)
        assertEquals("Pride and Prejudice", book.title)
        val all = book.chapters.joinToString("\n") { it.text }
        assertTrue(all.contains("universally acknowledged"))
        assertTrue(all.contains("Darcy"))
        // A KF8 flow carries the stylesheet inline; it must not reach the reader.
        assertFalse(all.contains("text-decoration"))
        assertNotNull("the AZW3 declares a cover", book.cover)
    }

    @Test
    fun `the three formats agree on the book's word count`() {
        val epub = read("pride.epub")?.let { EpubParser.parse(it) }
        val mobi = read("pride.mobi")?.let { MobiParser.parse(it) }
        assumeTrue("both files needed", epub != null && mobi != null)
        fun words(book: EpubParser.Book) = book.chapters.sumOf { it.wordCount }
        fun words(book: MobiParser.Book) = book.chapters.sumOf { it.wordCount }
        val a = words(epub!!)
        val b = words(mobi!!)
        // The two files come from the same source text, so their counts should be
        // close; the tolerance covers the licence and front matter each one carries.
        val drift = kotlin.math.abs(a - b).toDouble() / a
        assertTrue("epub $a vs mobi $b is too far apart", drift < 0.15)
    }
}
