package com.engreader.app.book

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The book parsers against input a user did not write.
 *
 * The fixtures in `book/` are well-formed: they prove the parsers read a book, not that
 * they survive a file built to make them allocate. These are the review's
 * untrusted-input findings — C9 (`parsePlain` returning a zero-chapter book), I1-I8
 * (size caps, decompression budgets, chapter fan-out, out-of-bounds offsets, quadratic
 * heading search, an overflowing ratio) and the book-side Minor items (D13 UTF-16
 * chapters, D15 signed escapes, D17 PalmDOC arithmetic) — each with a synthetic fixture
 * small enough to run in a unit test except where the defect's own threshold forces a
 * large one (I8 starts at 21.4 M characters; the case below uses 21.5 MB).
 */
class BookParserHardeningTest {

    // ------------------------------------------------------------------ C9

    @Test
    fun `a text file that is nothing but headings is refused`() {
        // A table of contents saved on its own. Every paragraph is consumed as a heading,
        // so `splitOnHeadings` never fills a body and returns no chapter at all — and the
        // zero-chapter book used to be stored and reported as "已导入《x》，共 0 章".
        val headings = listOf(
            "ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN", "EIGHT", "NINE", "TEN",
        )
        val bytes = (headings.joinToString("\n\n") { "CHAPTER $it" } + "\n\n").toByteArray(Charsets.UTF_8)
        assertEquals("the fixture is the 139-byte one from the review", 139, bytes.size)
        try {
            TextParser.parsePlain(bytes)
            fail("a heading-only file must not import as a book")
        } catch (e: BookFormat.Companion.Unsupported) {
            assertTrue("the refusal must say why", e.message!!.isNotBlank())
        }
    }

    @Test
    fun `a gutenberg text whose end marker comes first keeps its body`() {
        // A file with no START marker: the strip kept the header and cut everything
        // before the END marker, which was the first paragraph, so `subList(0, 0)`
        // discarded the whole book and returned zero chapters with no error.
        val prose = (1..40).joinToString("\n\n") { paragraph("Sentence number $it of the recovered text.") }
        val bytes = ("*** END OF THE PROJECT GUTENBERG EBOOK X ***\n\n$prose").toByteArray(Charsets.UTF_8)
        assertTrue("the fixture must be sizeable, got ${bytes.size} bytes", bytes.size > 7_000)

        val book = TextParser.parsePlain(bytes)
        val body = book.chapters.joinToString("\n") { it.text }
        assertTrue("the prose must survive the strip", body.contains("Sentence number 40 of the recovered text"))
    }

    // ------------------------------------------------------------------ I1

    @Test
    fun `a picked file past its size cap is refused before it is read`() {
        // `readBytes()` grew a ByteArrayOutputStream internally, so a file larger than
        // the free heap was held twice before the format was even detected. The cap has
        // to stop the read, not the parse that comes after it.
        try {
            BookPicker.readLimited(ByteArrayInputStream(ByteArray(1_000)), maxBytes = 100)
            fail("a file past the cap must be refused")
        } catch (e: BookFormat.Companion.Unsupported) {
            assertTrue("the message should name the limit", e.message!!.contains("太大"))
        }

        // A size the provider declared over the cap is refused without reading at all.
        try {
            BookPicker.readLimited(ByteArrayInputStream(ByteArray(0)), maxBytes = 100, knownSize = 5_000)
            fail("a declared-oversized file must be refused")
        } catch (e: BookFormat.Companion.Unsupported) {
            assertTrue(e.message!!.isNotBlank())
        }

        // Under the cap the bytes come back whole, including one byte past the chunk
        // boundary, which is where an off-by-one in the growable buffer would show.
        val bytes = ByteArray(64 * 1024 + 1) { (it and 0xFF).toByte() }
        assertArrayEquals(bytes, BookPicker.readLimited(ByteArrayInputStream(bytes), maxBytes = 1_000_000))
    }

    @Test
    fun `the size cap follows the kind of the picked file`() {
        assertEquals(BookPicker.MAX_CONTAINER_BYTES, BookPicker.maxImportBytes("pride.epub"))
        assertEquals(BookPicker.MAX_CONTAINER_BYTES, BookPicker.maxImportBytes("book.AZW3"))
        assertEquals(BookPicker.MAX_TEXT_BYTES, BookPicker.maxImportBytes("novel.txt"))
        assertEquals(BookPicker.MAX_TEXT_BYTES, BookPicker.maxImportBytes("saved.xhtml"))
        // An extension that says nothing gets the smaller ceiling rather than none.
        assertEquals(BookPicker.MAX_TEXT_BYTES, BookPicker.maxImportBytes("download"))
    }

    // ------------------------------------------------------------------ I2

    @Test
    fun `an oversized epub entry is refused`() {
        // The total ceiling alone left the first entry free to claim all of it, and
        // `readCapped` then held that entry twice (a 250 MB entry measured 511 MB of a
        // 512 MB heap).
        val bomb = ByteArray(9 * 1024 * 1024)
        try {
            EpubParser.parse(epub(chapterBytes = CHAPTER, extra = listOf("OEBPS/appendix.xhtml" to bomb)))
            fail("an entry past the per-entry cap must be refused")
        } catch (e: BookFormat.Companion.Unsupported) {
            assertTrue("the message should name the entry", e.message!!.contains("条目"))
        }
    }

    @Test
    fun `an oversized epub image is dropped instead of the whole book`() {
        // A page scan can be tens of megabytes and the reader only ever shows the cover,
        // so refusing the import over one of them would throw away the text as well.
        val book = EpubParser.parse(
            epub(
                chapterBytes = CHAPTER,
                extra = listOf("OEBPS/images/huge.png" to ByteArray(9 * 1024 * 1024)),
            ),
        )
        assertTrue("the text must still be there", book.chapters.isNotEmpty())
        assertNull("the oversized image is not a cover", book.cover)
    }

    // ------------------------------------------------------------------ I3

    @Test
    fun `palmdoc stops at the caller's budget instead of expanding past it`() {
        // Two bytes become ten: forty literal `w`s, then two hundred back-references at
        // distance 40 with a run of 10. The budget is what the caller has left of
        // MAX_TEXT_LENGTH, and it used to be consulted only *before* each record, so a
        // 15 MB record produced 76 MB of text against a 64 MB ceiling.
        val seeds = 40
        val references = 200
        val data = ByteArray(seeds) { 'w'.code.toByte() } +
            ByteArray(references * 2) { if (it % 2 == 0) 0x81.toByte() else 0x47.toByte() }

        val decoded = MobiParser.palmDoc(data, budget = 10_000)
        assertEquals("5× expansion of the 400 back-reference bytes", seeds + references * 10, decoded.size)
        assertTrue("every byte comes from the seed run", decoded.all { it == 'w'.code.toByte() })

        try {
            MobiParser.palmDoc(data, budget = 1_000)
            fail("output past the budget must be refused, not returned")
        } catch (e: BookFormat.Companion.Unsupported) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    // ------------------------------------------------------------------ I4

    @Test
    fun `the html chapter split stops at the cap`() {
        // Each heading became a slice, a `fromHtml` pass and a `Chapter` object before
        // the forty-word filter ran: 2 MB measured 277 MB of live heap.
        val html = StringBuilder()
        for (i in 1..5_000) {
            html.append("<h1>Heading $i</h1><p>${paragraph("Text of part $i.")}</p>")
        }
        val book = TextParser.parseHtml(html.toString().toByteArray(Charsets.UTF_8))
        assertTrue("the split must be capped, got ${book.chapters.size} chapters", book.chapters.size <= 4_001)
        assertTrue("the tail must stay in the last chapter", book.chapters.last().text.contains("Heading 5000"))
    }

    @Test
    fun `the epub heading split stops at the cap`() {
        val chapter = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            .append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>")
        for (i in 1..5_000) {
            chapter.append("<h1>Heading $i</h1><p>${paragraph("Text of part $i.")}</p>")
        }
        chapter.append("</body></html>")

        val book = EpubParser.parse(epub(chapterBytes = chapter.toString().toByteArray(Charsets.UTF_8)))
        assertTrue("the split must be capped, got ${book.chapters.size} chapters", book.chapters.size <= 4_001)
        assertTrue("the tail must stay in the last chapter", book.chapters.last().text.contains("Heading 5000"))
    }

    @Test
    fun `the mobi pagebreak split stops at the cap`() {
        val markup = StringBuilder()
        for (i in 1..5_000) {
            if (i > 1) markup.append("<mbp:pagebreak>")
            markup.append("<p>${paragraph("Part $i of the fixture text.")}</p>")
        }
        val text = markup.toString().toByteArray(Charsets.UTF_8)

        val book = MobiParser.parse(palmDatabase(mobiHeader(text.size), text))
        assertTrue("the split must be capped, got ${book.chapters.size} chapters", book.chapters.size <= 4_001)
        assertTrue("the tail must stay in the last chapter", book.chapters.last().text.contains("Part 5000"))
    }

    // ------------------------------------------------------------------ I5

    @Test
    fun `an eighty byte palm database header does not crash detection`() {
        // `firstRecordOffset` read bytes 78..81 after only checking `size < 80`, so a
        // file of exactly 80 or 81 bytes whose bytes 60..63 are `BOOK` threw
        // `ArrayIndexOutOfBoundsException: Index 80 out of bounds for length 80` out of
        // `detect` — before the import path could even report a bad file.
        for (size in 78..82) {
            val b = ByteArray(size)
            "BOOK".toByteArray().copyInto(b, 60)
            assertNull("a $size-byte header must not be read as a MOBI", BookFormat.detect(b))
        }
        // The full 82-byte header still reaches the record directory.
        val b = ByteArray(100)
        "BOOK".toByteArray().copyInto(b, 60)
        writeInt(b, 78, 78)
        assertNull("no MOBI signature at the record, so still not a MOBI", BookFormat.detect(b))
    }

    // ------------------------------------------------------------------ I6

    @Test
    fun `a negative mobi header length is refused rather than throwing`() {
        // `16 + mobiHeaderLength` is negative for every field below -16, and the
        // `at + 12 > header.size` guard cannot see a negative offset, so `String(header,
        // at, 4, …)` threw `StringIndexOutOfBoundsException` on line 85 — before any
        // text was read. 0x7FFFFFF0 is the wrap case the same family contains.
        for (length in listOf(-1, -17, -20, -256, 0x7FFFFFF0, Int.MAX_VALUE)) {
            val text = paragraph("The recovered text continues from here.").toByteArray(Charsets.UTF_8)
            val book = MobiParser.parse(
                palmDatabase(mobiHeader(text.size, mobiHeaderLength = length, withExth = true), text),
            )
            assertTrue(
                "mobiHeaderLength $length must not stop the parse, got ${book.chapters.size} chapters",
                book.chapters.isNotEmpty(),
            )
        }
    }

    // ------------------------------------------------------------------ I7

    @Test
    fun `the heading search does not rescan the document per unclosed heading`() {
        // A lazy `(.*?)</h\1>` searched to the end of the document from every opening tag
        // when the heading never closed: 32 000 unclosed `<h1>` measured 14 s, and the
        // cost is quadratic, so a chapter file of a megabyte stalls the importer for
        // minutes. 80 kB of unclosed headings is ~15 s before the fix and milliseconds
        // after it; the bound is loose enough not to be a benchmark.
        val html = "<h1>".repeat(20_000)
        val started = System.nanoTime()
        assertNull("an unclosed heading is not a heading", BookText.firstHeading(html))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("80 kB of unclosed headings took ${ms} ms; the search must not rescan", ms < 2_000)
    }

    @Test
    fun `a closed heading after unclosed ones is still found`() {
        // Same semantics as the lazy pattern: skip the openings that never close and take
        // the first one that does, with that heading's own text.
        val html = "<h1>".repeat(2_000) + "<h2>Chapter One</h2>"
        assertEquals("Chapter One", BookText.firstHeading(html))
        // And the checks on the text are unchanged.
        assertNull(BookText.firstHeading("<h3>This is a whole sentence. And another follows.</h3>"))
    }

    // ------------------------------------------------------------------ I8

    @Test
    fun `a legacy text past the ratio overflow still falls back to gb18030`() {
        // `broken * 100 <= utf8.length` wraps negative past 21,474,836 replacement
        // characters, and the negative product passes the test — so a >21 MB GB18030
        // novel was returned as a screen of U+FFFD without the fallback ever being
        // tried, which is the exact case the fallback exists for. 0x81 is a GB18030
        // lead byte and invalid as UTF-8.
        val bytes = ByteArray(21_500_000)
        java.util.Arrays.fill(bytes, 0x81.toByte())

        val utf8 = String(bytes, Charsets.UTF_8)
        assertEquals("every byte is invalid UTF-8", bytes.size, utf8.count { it == '\uFFFD' })

        val text = TextParser.decode(bytes)
        assertTrue(
            "the fallback must be taken once 100 * broken overflows: " +
                "${text.count { it == '\uFFFD' }} replacements in ${text.length} chars",
            text.count { it == '\uFFFD' } < utf8.count { it == '\uFFFD' },
        )
    }

    // ------------------------------------------------ book-side Minor items

    @Test
    fun `a utf16 chapter is decoded rather than becoming nul interleaved markup`() {
        // XHTML 1.1 permits UTF-16, and its declaration cannot be read from a Latin-1
        // preview (every character is interleaved with a NUL), so the BOM has to decide.
        // Untreated, the chapter arrived as raw markup with a NUL between every
        // character: no tag pattern matched, so the markup itself became the text.
        val xhtml = "<?xml version=\"1.0\" encoding=\"utf-16\"?>" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>Chapter One</h1>" +
            "<p>${paragraph("The snow lay deep on the fields.")}</p></body></html>"
        val bytes = ("\uFEFF" + xhtml).toByteArray(Charsets.UTF_16LE)

        val book = EpubParser.parse(epub(chapterBytes = bytes))
        val body = book.chapters.joinToString("\n") { it.text }
        assertTrue("the prose must survive", body.contains("The snow lay deep on the fields"))
        assertFalse("decoded markup must not keep NULs", body.contains('\u0000'))
    }

    @Test
    fun `a signed percent escape is left as literal text`() {
        // `toIntOrNull(16)` accepts a leading sign, so `%+1` decoded to byte 0x01 and
        // `%-1` to 0xFF: a href carrying one silently resolved to a different name.
        assertEquals("%+1", EpubParser.percentDecode("%+1"))
        assertEquals("%-1", EpubParser.percentDecode("%-1"))
        assertEquals("%zz", EpubParser.percentDecode("%zz"))
        assertEquals("%", EpubParser.percentDecode("%"))
        // Real escapes still decode.
        assertEquals("a b/c.xhtml", EpubParser.percentDecode("a%20b%2Fc.xhtml"))
        assertEquals("café.xhtml", EpubParser.percentDecode("caf%C3%A9.xhtml"))
    }

    // ---------------------------------------------------------------- fixtures

    /** A paragraph long enough that a chapter holding it clears the minimum word count. */
    private fun paragraph(sentence: String): String = sentence.repeat(12).trim()

    /**
     * A minimal EPUB whose single spine item is [chapterBytes], plus any [extra] entries.
     *
     * [extra] is written last so an oversized entry is read after the ones the parser
     * needs, which is the order that makes the "dropped, not refused" case observable.
     */
    private fun epub(chapterBytes: ByteArray, extra: List<Pair<String, ByteArray>> = emptyList()): ByteArray {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { out ->
            fun put(name: String, body: ByteArray) {
                out.putNextEntry(ZipEntry(name))
                out.write(body)
                out.closeEntry()
            }
            put("mimetype", "application/epub+zip".toByteArray())
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                   <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                   </container>""".toByteArray(),
            )
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="utf-8"?>
                   <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
                     <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                       <dc:title>Fixture Book</dc:title><dc:creator>Fixture Author</dc:creator>
                       <dc:language>en</dc:language>
                     </metadata>
                     <manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest>
                     <spine><itemref idref="c1"/></spine>
                   </package>""".toByteArray(),
            )
            put("OEBPS/c1.xhtml", chapterBytes)
            for ((name, body) in extra) put(name, body)
        }
        return zip.toByteArray()
    }

    /**
     * A two-record Palm database: [header] as record 0, [text] as record 1.
     *
     * The records are laid out the way a real PDB is — 78-byte header, one 8-byte
     * directory entry per record, then the records — so `splitRecords` reads them with
     * its own offsets rather than a shortcut.
     */
    private fun palmDatabase(header: ByteArray, text: ByteArray): ByteArray {
        val recordsAt = 78 + 8 * 2
        val out = ByteArray(recordsAt + header.size + text.size)
        "BOOK".toByteArray().copyInto(out, 60)
        writeShort(out, 76, 2)
        writeInt(out, 78, recordsAt)
        writeInt(out, 86, recordsAt + header.size)
        header.copyInto(out, recordsAt)
        text.copyInto(out, recordsAt + header.size)
        return out
    }

    /**
     * A PalmDOC/MOBI header for an uncompressed file of [textLength] bytes.
     *
     * `mobiHeaderLength` is the field at offset 20, which is the one the EXTH block is
     * found through; [withExth] sets the flag bit at 128 that sends `readExth` looking
     * for it.
     */
    private fun mobiHeader(textLength: Int, mobiHeaderLength: Int = 0, withExth: Boolean = false): ByteArray {
        val header = ByteArray(if (withExth) 200 else 40)
        writeShort(header, 0, 1)                       // compression: none
        writeInt(header, 4, textLength)                // declared text length
        writeShort(header, 8, 1)                       // one text record
        writeShort(header, 12, 0)                      // no encryption
        writeInt(header, 20, mobiHeaderLength)         // MOBI header length
        writeInt(header, 28, 65001)                    // UTF-8
        if (withExth) writeInt(header, 128, 0x40)      // EXTH present
        return header
    }

    private fun writeShort(b: ByteArray, at: Int, value: Int) {
        b[at] = (value ushr 8).toByte()
        b[at + 1] = value.toByte()
    }

    private fun writeInt(b: ByteArray, at: Int, value: Int) {
        for (i in 0 until 4) b[at + i] = (value ushr (24 - 8 * i)).toByte()
    }

    private val CHAPTER: ByteArray =
        ("<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>Chapter One</h1>" +
            "<p>${paragraph("The snow lay deep on the fields.")}</p></body></html>")
            .toByteArray(Charsets.UTF_8)
}