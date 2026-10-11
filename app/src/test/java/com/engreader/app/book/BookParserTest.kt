package com.engreader.app.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The book parsers, run against real files.
 *
 * The fixtures in `src/test/resources/book` are built by
 * `tools/book/build_fixtures.py`: they are genuine Palm databases and zips, not
 * mocks, so a wrong byte offset fails here rather than on a phone. The two large
 * public-domain files used during development (Pride and Prejudice as EPUB, MOBI
 * and AZW3) are not committed — they are 25 MB each — but the same assertions were
 * run against them, and the counts in the comments below are from those runs.
 */
class BookParserTest {

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("book/$name")!!.use { it.readBytes() }

    // ------------------------------------------------------------- detection

    @Test
    fun `an epub is recognised by its mimetype entry`() {
        assertEquals(BookFormat.Epub, BookFormat.detect(resource("two-chapters.epub")))
    }

    @Test
    fun `a mobi is recognised from its palm database header`() {
        assertEquals(BookFormat.Mobi, BookFormat.detect(resource("plain.mobi")))
        assertEquals(BookFormat.Mobi, BookFormat.detect(resource("palmdoc.mobi")))
    }

    @Test
    fun `an arbitrary zip is not mistaken for an epub`() {
        // A zip whose first entry is not `mimetype` is some other container; reading
        // it as a book would produce a confusing failure much later.
        val zip = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(zip).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("hello.txt"))
            out.write("not a book".toByteArray())
            out.closeEntry()
        }
        assertNull(BookFormat.detect(zip.toByteArray()))
    }

    @Test
    fun `random bytes are not a book`() {
        assertNull(BookFormat.detect(ByteArray(512) { (it * 7).toByte() }))
        assertNull(BookFormat.detect(ByteArray(0)))
    }

    // ----------------------------------------------------------------- EPUB

    @Test
    fun `an epub3 nav document names the chapters`() {
        // The nav is this book's only navigation — there is no NCX — and its body has
        // no headings. The parser matched just the opening `<nav>` tag and then
        // searched *it* for links, which found nothing, so the nav parsed as empty and
        // the book fell back to heading detection: one unnamed chapter instead of
        // three named ones. The landmarks list comes first, so the toc has to be
        // picked by its `epub:type` rather than by position.
        val book = EpubParser.parse(resource("nav-only.epub"))
        assertEquals("Nav Only Book", book.title)
        assertEquals("Fixture Author", book.author)
        assertEquals(
            listOf("Preface", "Chapter One", "Chapter Two"),
            book.chapters.map { it.title },
        )
    }

    @Test
    fun `an epub is split by its navigation document`() {
        val book = EpubParser.parse(resource("two-chapters.epub"))
        assertEquals("Two Chapters & a Preface", book.title)
        assertEquals("Test Author", book.author)
        assertEquals("en", book.language)
        // The NCX names three targets: a preface and two chapters inside two spine
        // items, so the split has to follow the anchors rather than the files.
        assertEquals(3, book.chapters.size)
        assertEquals("Preface", book.chapters[0].title)
        assertEquals("Chapter One", book.chapters[1].title)
        assertEquals("Chapter Two", book.chapters[2].title)
    }

    @Test
    fun `an epub chapter keeps its paragraph breaks`() {
        val book = EpubParser.parse(resource("two-chapters.epub"))
        val preface = book.chapters.first { it.title == "Preface" }
        // The heading, then the two body paragraphs. Joining them would be the classic
        // bug here, and it would also break sentence splitting in the grammar panel.
        val blocks = preface.text.split("\n\n")
        assertEquals(3, blocks.size)
        assertEquals("Preface", blocks[0])
        assertTrue(blocks[1].startsWith("word"))
    }

    @Test
    fun `a chapter that starts at an anchor does not keep the tag's tail as text`() {
        // Splitting at the offset of `id="c1"` rather than at the element's `<` would
        // leave `id="c1">` as the first line of the chapter.
        val book = EpubParser.parse(resource("two-chapters.epub"))
        val text = book.chapters.joinToString("\n") { it.text }
        assertFalse(text.contains("id=\"c1\""))
        assertFalse(text.contains(">"))
    }

    @Test
    fun `markup is stripped and entities are decoded`() {
        val book = EpubParser.parse(resource("two-chapters.epub"))
        val text = book.chapters.joinToString("\n") { it.text }
        assertFalse("tags must be gone", text.contains("<"))
        assertFalse("the stylesheet must not leak in", text.contains("p{}"))
    }

    @Test
    fun `a script in the body does not become text`() {
        val book = EpubParser.parse(resource("two-chapters.epub"))
        val chapter = book.chapters.first { it.title == "Chapter Two" }
        assertFalse(chapter.text.contains("var x"))
    }

    @Test
    fun `an epub cover is found through the metadata reference`() {
        val book = EpubParser.parse(resource("two-chapters.epub"))
        assertNotNull("the fixture declares a cover", book.cover)
        assertEquals("png", book.coverExtension)
        // PNG magic, so the bytes really are the image rather than a path.
        assertEquals(0x89.toByte(), book.cover!![0])
        assertEquals('P'.code.toByte(), book.cover!![1])
    }

    // ----------------------------------------------------------------- MOBI

    @Test
    fun `an uncompressed mobi is read`() {
        val book = MobiParser.parse(resource("plain.mobi"))
        assertEquals("Fixture Book", book.title)
        assertEquals("Fixture Author", book.author)
        assertEquals("en", book.language)
        assertEquals(2, book.chapters.size)
        assertEquals("The First Chapter", book.chapters[0].title)
    }

    @Test
    fun `palmdoc compressed text decodes to the same bytes as the original`() {
        val expected = String(resource("plain.txt"), Charsets.UTF_8)
        val book = MobiParser.parse(resource("palmdoc.mobi"))
        val text = book.chapters.joinToString("\n") { it.text }
        assertTrue(text.contains("It was a bright cold day in April"))
        assertTrue(text.contains("Victory Mansions"))
        // The uncompressed fixture carries the same body, so the two parsers must
        // agree word for word after extraction.
        val plain = MobiParser.parse(resource("plain.mobi")).chapters.joinToString("\n") { it.text }
        assertEquals(plain, text)
        assertTrue(expected.contains("Victory Mansions"))
    }

    @Test
    fun `palmdoc back references that overlap the output cursor are copied byte at a time`() {
        // The fixture is a phrase repeated twelve times, which the encoder turns into
        // matches that overlap what they are still writing.
        val expected = String(resource("palmdoc-only.txt"), Charsets.UTF_8)
        val book = MobiParser.parse(resource("palmdoc-only.mobi"))
        val text = book.chapters.joinToString("\n") { it.text }
        assertEquals(expected.trim(), text.trim())
    }

    @Test
    fun `huff cdic compressed text is decoded`() {
        // The fixture uses a fixed 8-bit code, so any error in the bit window, the
        // term bit, the CDIC offset table or the phrase length word shows up here.
        val book = MobiParser.parse(resource("huffcdic.mobi"))
        assertEquals(2, book.chapters.size)
        val text = book.chapters.joinToString("\n") { it.text }
        assertTrue("decoded text must contain the body", text.contains("Victory Mansions"))
        val plain = MobiParser.parse(resource("plain.mobi")).chapters.joinToString("\n") { it.text }
        assertEquals(plain, text)
    }

    @Test
    fun `trailing entries are stripped before decompression`() {
        // Six bytes of trailer plus a length byte per record: leaving them in shifts
        // every later byte, and PalmDOC then decodes to noise.
        val book = MobiParser.parse(resource("trailers.mobi"))
        val text = book.chapters.joinToString("\n") { it.text }
        assertTrue(text.contains("It was a bright cold day in April"))
        val plain = MobiParser.parse(resource("plain.mobi")).chapters.joinToString("\n") { it.text }
        assertEquals(plain, text)
    }

    @Test
    fun `chapters are split on the pagebreak marker`() {
        val book = MobiParser.parse(resource("palmdoc.mobi"))
        assertEquals(2, book.chapters.size)
        assertEquals("The First Chapter", book.chapters[0].title)
        assertEquals("The Second Chapter", book.chapters[1].title)
        assertTrue(book.chapters[0].text.contains("striking thirteen"))
        assertTrue(book.chapters[1].text.contains("seven flights up"))
    }

    @Test
    fun `a mobi cover resolves through the first image index`() {
        val book = MobiParser.parse(resource("cover.mobi"))
        assertNotNull("the fixture declares a cover", book.cover)
        assertEquals("png", book.coverExtension)
        assertEquals(0x89.toByte(), book.cover!![0])
    }

    @Test
    fun `a book with no cover declares none`() {
        assertNull(MobiParser.parse(resource("plain.mobi")).cover)
    }

    @Test
    fun `an encrypted book is refused rather than half decoded`() {
        try {
            MobiParser.parse(resource("encrypted.mobi"))
            fail("a DRM file must not parse")
        } catch (e: BookFormat.Companion.Encrypted) {
            assertTrue("the message should say why", e.message!!.contains("DRM"))
        }
    }

    @Test
    fun `a truncated file fails cleanly`() {
        val full = resource("palmdoc.mobi")
        val cut = full.copyOfRange(0, full.size / 3)
        try {
            MobiParser.parse(cut)
            fail("a truncated file must not parse")
        } catch (e: Exception) {
            // Any of the format exceptions is fine; what matters is that it does not
            // return a book, and that it does not take the process down.
            assertFalse(e is OutOfMemoryError)
        }
    }

    // ----------------------------------------------------------- text shape

    @Test
    fun `mobi specific attributes are stripped from the text`() {
        val book = MobiParser.parse(resource("plain.mobi"))
        val text = book.chapters.joinToString("\n") { it.text }
        assertFalse("filepos is meaningless outside the file", text.contains("filepos"))
        assertFalse(text.contains("recindex"))
    }

    @Test
    fun `a heading is not mistaken for the opening sentence of a chapter`() {
        // `firstHeading` rejects anything with a sentence terminator or over 90
        // characters, which is what keeps a paragraph out of the table of contents.
        assertNull(BookText.firstHeading("<h1>This is a whole sentence. And another one follows here.</h1>"))
        assertNull(BookText.firstHeading("<h1>${"x".repeat(120)}</h1>"))
        assertEquals("Chapter IV", BookText.firstHeading("<h1>Chapter IV</h1><p>body</p>"))
        assertNull(BookText.firstHeading("<p>no heading at all</p>"))
    }

    @Test
    fun `a chapter marker in a paragraph becomes the contents label`() {
        // kindlegen writes chapter headings as centred paragraphs, so a converted
        // novel has no <h2> to find and every entry would otherwise read the same.
        assertEquals(
            "CHAPTER II",
            BookText.chapterHeading("<p align=\"center\">I hope Mr. Bingley will like it.</p><p>CHAPTER II.</p><p>Mr. Bennet...</p>"),
        )
        // A run-together marker, which bad conversions do produce.
        assertEquals("CHAPTER XXVII", BookText.chapterHeading("<p>\"On the Stairs.\" CHAPTERXXVII. With no greater events</p>"))
        // `Chapter: I.,` from a table of contents.
        assertEquals("Chapter I", BookText.chapterHeading("<p>Chapter: I., II., III.</p>"))
    }

    @Test
    fun `an illustration caption is not a chapter title`() {
        // Plates are captioned "Heading to Chapter XIV." and name a different chapter
        // than the one they sit in, so they must not win over the real marker.
        assertEquals(
            "CHAPTER XIV",
            BookText.chapterHeading("<p>Heading to Chapter XIV.</p><p>CHAPTER XIV. During dinner</p>"),
        )
        assertNull(BookText.chapterHeading("<p>Tailpiece to Chapter V.</p><p>the rest of the page</p>"))
    }

    @Test
    fun `a navigation label carrying an illustration caption is reduced to the chapter`() {
        // Project Gutenberg labels its EPUB chapters with whatever plate sits on the
        // page: `I hope Mr. Bingley will like it. CHAPTER II.` The marker is the
        // chapter's name; the caption before it is not.
        assertEquals(
            "CHAPTER II",
            BookText.cleanContentsLabel("I hope Mr. Bingley will like it. CHAPTER II."),
        )
        // A label that is genuinely a title is left as it is.
        assertEquals("Preface", BookText.cleanContentsLabel("Preface"))
        assertEquals("List of Illustrations", BookText.cleanContentsLabel("List of Illustrations"))
    }

    @Test
    fun `a real heading beats a marker in the prose`() {
        assertEquals(
            "The Assembly",
            BookText.chapterHeading("<h1>The Assembly</h1><p>Chapter two of the story</p>"),
        )
    }

    @Test
    fun `source line wrapping does not split a paragraph`() {
        // Converted books wrap their markup at a fixed column, so one paragraph spans
        // several source lines. Splitting there would hand the grammar panel half a
        // sentence to analyse and break the sentence boundaries in the reader.
        val text = BookText.fromHtml(
            "<p class=\"calibre1\">IT is a truth universally acknowledged, that a single\n" +
                "man in possession of a good fortune must be in want\n" +
                "of a wife.</p>"
        )
        assertEquals(1, text.split("\n\n").size)
        assertTrue(text.contains("a single man in possession"))
    }

    @Test
    fun `a comparison in prose survives text extraction`() {
        // `<[^>]+>` would eat "1 < 2"; the shared tag pattern requires a name.
        val text = BookText.fromHtml("<p>if 1 < 2 then stop</p>")
        assertEquals("if 1 < 2 then stop", text)
    }

    @Test
    fun `entities are decoded once, not twice`() {
        assertEquals("a & b", BookText.fromHtml("<p>a &amp; b</p>"))
        assertEquals("&amp;", BookText.fromHtml("<p>&amp;amp;</p>"))
    }

    @Test
    fun `an xml declaration and doctype are not left in the chapter text`() {
        // Every XHTML chapter file opens with these, and neither is an element, so the
        // tag pattern missed them: each imported chapter began with a line of markup,
        // which the translator then turned into Chinese.
        val text = BookText.fromHtml(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!DOCTYPE html>\n" +
                "<html><body><p>Mr. Bennet was among the earliest.</p></body></html>"
        )
        assertEquals("Mr. Bennet was among the earliest.", text)
    }

    @Test
    fun `a processing instruction is dropped wherever it appears`() {
        assertEquals(
            "before after",
            BookText.fromHtml("<p>before <?php echo 1 ?> after</p>"),
        )
    }

    // ---------------------------------------------------- review regressions

    @Test
    fun `a plus in a manifest path is not decoded as a space`() {
        // `URLDecoder` is form decoding: it turned `chapter+1.xhtml` into
        // `chapter 1.xhtml`, and the zip entry lookup then found nothing.
        assertEquals("chapter+1.xhtml", EpubParser.percentDecode("chapter+1.xhtml"))
        assertEquals("a b/c.xhtml", EpubParser.percentDecode("a%20b%2Fc.xhtml"))
        assertEquals("café.xhtml", EpubParser.percentDecode("caf%C3%A9.xhtml"))
    }

    @Test
    fun `windows-1252 control bytes become their real characters`() {
        // Latin-1 leaves 0x80..0x9F as C1 controls, so the curly quotes, dashes and
        // ellipsis a converter wrote arrived as boxes.
        assertEquals("a“b”c—d", MobiParser.fromWindows1252("a\u0093b\u0094c\u0097d"))
        assertEquals("it\u2019s", MobiParser.fromWindows1252("it\u0092s"))
        assertEquals("plain", MobiParser.fromWindows1252("plain"))
    }

    @Test
    fun `an attribute is not matched inside a longer attribute name`() {
        // A plain `\b` is a boundary between `-` and `i`, so asking for `id` matched
        // the tail of `data-id` — which is what `epub:type`-style generators emit —
        // and the manifest item was filed under the wrong id, so the spine's `idref`
        // found nothing and the whole book came back empty.
        val epub = singleChapterEpub(
            """<item id="c1" data-id="decoy" href="c1.xhtml" media-type="application/xhtml+xml"/>""",
        )
        val book = EpubParser.parse(epub)
        assertTrue("the spine item must still resolve", book.chapters.isNotEmpty())
    }

    // ------------------------------------------- plain text, FB2 and HTML

    @Test
    fun `a plain text book is split on its chapter headings`() {
        val text = buildString {
            append("Title: A Short Book\n\nAuthor: Test Author\n\n")
            append("*** START OF THE PROJECT GUTENBERG EBOOK A SHORT BOOK ***\n\n")
            append("CHAPTER I.\n\n")
            append(paragraph("The first chapter begins here."))
            append("\n\nCHAPTER II.\n\n")
            append(paragraph("The second chapter follows on."))
            append("\n\n*** END OF THE PROJECT GUTENBERG EBOOK A SHORT BOOK ***\n")
        }
        val book = TextParser.parsePlain(text.toByteArray(Charsets.UTF_8))
        assertEquals("A Short Book", book.title)
        assertEquals("Test Author", book.author)
        assertEquals(listOf("CHAPTER I", "CHAPTER II"), book.chapters.map { it.title })
        assertTrue(book.chapters.all { it.wordCount >= 40 })
    }

    @Test
    fun `the gutenberg licence text is not part of the book`() {
        val text = "*** START OF THE PROJECT GUTENBERG EBOOK X ***\n\n" +
            paragraph("Real prose begins here.") + "\n\n" +
            "*** END OF THE PROJECT GUTENBERG EBOOK X ***\n" +
            "Most people start at our website which has the main PG search facility."
        val book = TextParser.parsePlain(text.toByteArray(Charsets.UTF_8))
        val body = book.chapters.joinToString(" ") { it.text }
        assertTrue(body.contains("Real prose begins here."))
        assertFalse(body.contains("PG search facility"))
    }

    @Test
    fun `text with no headings is cut into readable chapters`() {
        // One enormous chapter would have the reader split the whole book into
        // sentences before it could show the first line.
        val text = (1..2000).joinToString("\n\n") {
            paragraph("Paragraph number $it says the same thing again.")
        }
        val book = TextParser.parsePlain(text.toByteArray(Charsets.UTF_8))
        assertTrue("expected more than one chapter, got ${book.chapters.size}", book.chapters.size > 1)
        assertTrue(book.chapters.all { it.wordCount <= 6_000 })
        assertEquals("第 1 节", book.chapters.first().title)
    }

    @Test
    fun `a chapter number written in words still starts a chapter`() {
        // The shared marker rule wants a numeral, so a plain-text edition that spells it
        // out imported as one enormous chapter with no table of contents.
        val text = buildString {
            append("Chapter One: The Beginning\n\n")
            append(paragraph("The morning was cold and the streets were empty."))
            append("\n\nChapter Two: The Journey\n\n")
            append(paragraph("The journey took eleven hours by train."))
        }
        val book = TextParser.parsePlain(text.toByteArray(Charsets.UTF_8))
        assertEquals(
            listOf("Chapter One: The Beginning", "Chapter Two: The Journey"),
            book.chapters.map { it.title },
        )
    }

    @Test
    fun `a sentence that mentions a chapter is not a heading`() {
        // Only a line that *starts* with the marker counts; otherwise ordinary prose
        // about a chapter would cut the text in half.
        val text = paragraph("Chapter one of the story was written last year.") + "\n\n" +
            paragraph("The rest of the book continues from here.")
        val book = TextParser.parsePlain(text.toByteArray(Charsets.UTF_8))
        assertEquals(1, book.chapters.size)
        assertTrue(book.chapters.first().text.contains("was written last year"))
    }

    @Test
    fun `a legacy chinese encoding is not turned into replacement characters`() {
        val text = "第一章\n\n" + "这是正文的第一段内容。".repeat(10)
        val bytes = text.toByteArray(java.nio.charset.Charset.forName("GB18030"))
        val book = TextParser.parsePlain(bytes)
        val body = book.chapters.joinToString(" ") { it.text }
        assertFalse(body, body.contains('\uFFFD'))
        assertTrue(body, body.contains("这是正文的第一段内容"))
    }

    @Test
    fun `an fb2 book takes its metadata and chapters from its sections`() {
        val fb2 = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
              <description><title-info>
                <book-title>The Test Novel</book-title>
                <author><first-name>Jane</first-name><last-name>Austen</last-name></author>
                <lang>en</lang>
              </title-info></description>
              <body>
                <section><title><p>Chapter One</p></title>
                  <p>${paragraph("The opening section of the book.")}</p></section>
                <section><title><p>Chapter Two</p></title>
                  <p>${paragraph("The closing section of the book.")}</p></section>
              </body>
              <body name="notes"><section><title><p>Notes</p></title>
                <p>${paragraph("A footnote that is not part of the story.")}</p></section></body>
            </FictionBook>
        """.trimIndent()
        val book = TextParser.parseFb2(fb2.toByteArray(Charsets.UTF_8))
        assertEquals("The Test Novel", book.title)
        assertEquals("Jane Austen", book.author)
        assertEquals("en", book.language)
        assertEquals(listOf("Chapter One", "Chapter Two"), book.chapters.map { it.title })
    }

    @Test
    fun `an html file is split on its headings`() {
        val html = """
            <html><head><title>A Saved Page</title></head><body>
            <h1>First Part</h1><p>${paragraph("Text of the first part.")}</p>
            <h1>Second Part</h1><p>${paragraph("Text of the second part.")}</p>
            </body></html>
        """.trimIndent()
        val book = TextParser.parseHtml(html.toByteArray(Charsets.UTF_8))
        assertEquals("A Saved Page", book.title)
        assertEquals(listOf("First Part", "Second Part"), book.chapters.map { it.title })
    }

    @Test
    fun `the new formats are detected from their bytes`() {
        val fb2 = "<?xml version=\"1.0\"?><FictionBook><body/></FictionBook>"
        assertEquals(BookFormat.Fb2, BookFormat.detect(fb2.toByteArray()))
        val html = "<!DOCTYPE html><html><body><p>hi</p></body></html>"
        assertEquals(BookFormat.Html, BookFormat.detect(html.toByteArray()))
        assertEquals(BookFormat.Txt, BookFormat.detect("Just some prose, no markup.".toByteArray()))
        // A UTF-16 text file is full of NULs but is still text.
        val utf16 = "\uFEFFChapter One\n\nSome prose here.".toByteArray(Charsets.UTF_16LE)
        assertEquals(BookFormat.Txt, BookFormat.detect(utf16))
        // A binary format with no NUL in its opening is not text.
        val binary = ByteArray(600) { if (it % 3 == 0) 0x01 else 0x41 }
        assertNull(BookFormat.detect(binary))
    }

    /** A paragraph long enough that a chapter holding it clears the minimum. */
    private fun paragraph(sentence: String): String = sentence.repeat(12).trim()

    /** A minimal EPUB whose manifest carries [itemTag] and whose content is one file. */
    private fun singleChapterEpub(itemTag: String): ByteArray {
        val opf = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Attr Book</dc:title><dc:language>en</dc:language>
              </metadata>
              <manifest>
                $itemTag
              </manifest>
              <spine><itemref idref="c1"/></spine>
            </package>
        """.trimIndent()
        val chapter = """
            <?xml version="1.0" encoding="utf-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><body>
            <p>${"Officials said more details would follow. ".repeat(4)}</p>
            <p>${"The decision was unprecedented in the modern era. ".repeat(4)}</p>
            <p>${"The public will be able to watch the execution. ".repeat(4)}</p>
            </body></html>
        """.trimIndent()
        val zip = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(zip).use { out ->
            fun put(name: String, body: String) {
                out.putNextEntry(java.util.zip.ZipEntry(name))
                out.write(body.toByteArray())
                out.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                   <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                   </container>""",
            )
            put("OEBPS/content.opf", opf)
            put("OEBPS/c1.xhtml", chapter)
        }
        return zip.toByteArray()
    }
}
