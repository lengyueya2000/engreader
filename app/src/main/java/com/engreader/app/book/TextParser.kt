package com.engreader.app.book

import java.nio.charset.Charset

/**
 * Books that are not a container of chapter files: plain text, FictionBook, and a
 * single HTML file.
 *
 * These are the formats a reader is most likely to have lying around — a Gutenberg
 * `.txt`, an `.fb2` from a Russian library, a chapter saved as `.html` — and none of
 * them needs a container reader. Chapters are found in the text itself: a heading
 * line for plain text, a `<section>` for FB2, an `<h1>` for HTML. When no structure
 * can be found at all, the text is cut into fixed-size chapters rather than being
 * left as one enormous one, which would have the reader tokenise a whole novel in a
 * single pass.
 *
 * Pure JVM: the parsers read bytes and return [EpubParser.Chapter]s, so the import
 * path and the reader need no format-specific code at all.
 */
object TextParser {

    /** Same bar as the container parsers: below this a piece is not a chapter. */
    private const val MIN_CHAPTER_WORDS = 40

    /**
     * How large an unstructured chapter may get before it is cut.
     *
     * A novel with no chapter markers is otherwise a single article, and the reader
     * splits the whole thing into sentences before it can show the first line.
     */
    private const val MAX_UNSTRUCTURED_WORDS = 6_000

    /** Plain text: the whole file, split on its own headings. */
    fun parsePlain(bytes: ByteArray): EpubParser.Book {
        val paragraphs = BookText.fromPlain(decode(bytes))
            .split("\n\n")
            .filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) throw BookFormat.Companion.Unsupported("这个文本文件是空的")

        // Read the metadata before the licence text is cut: Gutenberg writes its
        // `Title:` and `Author:` lines just inside the header it also tells readers to
        // ignore, and they are the only title the file has.
        val meta = gutenbergMetadata(paragraphs)
        val chapters = splitOnHeadings(stripGutenbergBoilerplate(paragraphs))
        return EpubParser.Book(
            title = meta.first,
            author = meta.second,
            language = "",
            chapters = chapters,
            cover = null,
            coverExtension = "",
        )
    }

    /** FB2: an XML document whose `<section>` elements are the chapters. */
    fun parseFb2(bytes: ByteArray): EpubParser.Book {
        val xml = decode(bytes)
        val chapters = bodyRegions(xml)
            .flatMap { splitSections(it) }
            .filter { it.wordCount >= MIN_CHAPTER_WORDS }
        if (chapters.isEmpty()) throw BookFormat.Companion.Unsupported("这个 FB2 里没有正文")

        return EpubParser.Book(
            title = elementText(xml, "book-title"),
            author = authorOf(xml),
            language = elementText(xml, "lang"),
            chapters = titleUntitled(chapters),
            cover = null,
            coverExtension = "",
        )
    }

    /** A single HTML file: its `<h1>`/`<h2>` headings are the chapters. */
    fun parseHtml(bytes: ByteArray): EpubParser.Book {
        val html = decode(bytes)
        val slices = splitOnHtmlHeadings(html)
        val chapters = slices
            .map { slice ->
                val text = BookText.fromHtml(slice)
                EpubParser.Chapter(BookText.chapterHeading(slice, text) ?: "", text)
            }
            .filter { it.wordCount >= MIN_CHAPTER_WORDS }
        if (chapters.isEmpty()) throw BookFormat.Companion.Unsupported("这个 HTML 里没有正文")

        return EpubParser.Book(
            title = firstTitle(html),
            author = "",
            language = "",
            chapters = titleUntitled(chapters),
            cover = null,
            coverExtension = "",
        )
    }

    // ------------------------------------------------------------------ text

    /**
     * Groups paragraphs into chapters at the heading lines.
     *
     * A heading is a paragraph the book itself marks as one: a `CHAPTER II.` line, or
     * a bare numeral on a line of its own. Front matter before the first heading stays
     * with the first chapter rather than becoming a chapter of its own, which is what
     * a table of contents would otherwise open with.
     */
    private fun splitOnHeadings(paragraphs: List<String>): List<EpubParser.Chapter> {
        val out = mutableListOf<EpubParser.Chapter>()
        var title = ""
        var body = StringBuilder()

        fun flush() {
            val text = body.toString().trim()
            if (text.isNotEmpty()) {
                out += EpubParser.Chapter(title, text)
            }
            body = StringBuilder()
        }

        for (paragraph in paragraphs) {
            val heading = headingOf(paragraph)
            if (heading != null && body.isNotEmpty()) {
                flush()
                title = heading
                continue
            }
            if (heading != null && body.isEmpty()) {
                // A heading at the very start of the file: it names this chapter.
                title = heading
                continue
            }
            if (body.isNotEmpty()) body.append("\n\n")
            body.append(paragraph)
        }
        flush()

        val kept = out.filter { it.wordCount >= MIN_CHAPTER_WORDS }
        // A very short text has no chapter long enough to keep, and it is still a book:
        // it goes in whole. Anything left over-long is cut by [chunk], which is also
        // what handles a text with no headings at all.
        val usable = kept.ifEmpty { out }
        return titleUntitled(usable.flatMap { chunk(it) })
    }

    /** The chapter title [paragraph] states, or null when it is ordinary prose. */
    private fun headingOf(paragraph: String): String? {
        val line = paragraph.trim()
        if (line.isEmpty() || line.length > 80 || '\n' in line) return null
        // Reuses the book parsers' own marker rule, including its rejection of the
        // illustration captions that read like one ("Heading to Chapter XIV.").
        BookText.chapterHeading("<p>$line</p>", line)?.let { return it }
        // That rule requires a numeral, which misses the plain-text editions that spell
        // the number out — "Chapter One", "Part Twenty-Three" — and those are common
        // enough in Gutenberg's own .txt files that the whole book would otherwise be
        // imported as a single chapter.
        if (SPELLED_MARKER.containsMatchIn(line)) {
            // Through the same normalisation as a numeral, so "Chapter One: The
            // Beginning" keeps its subtitle and the list of contents stays consistent.
            return BookText.normaliseHeading(line)
        }
        // A bare numeral is how plenty of plain-text editions number a chapter.
        return line.trimEnd('.', ')').takeIf { it.isNotEmpty() && BARE_NUMERAL.matches(it) }
    }

    private val BARE_NUMERAL = Regex("(?i)^(?:[IVXLC]{1,7}|\\d{1,3})$")

    /**
     * A heading that names its number in words.
     *
     * Anchored at the start of the line, so a sentence that merely mentions a chapter —
     * "Chapter one of the story was written last year" — is not mistaken for a heading
     * any more often than the numeral rule already mistakes it.
     */
    private val SPELLED_MARKER = Regex(
        "(?i)^\\s*(?:chapter|part|book|section)\\s*:?\\s*" +
            "(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|" +
            "fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|" +
            "fifty|sixty|seventy|eighty|ninety|hundred|thousand|" +
            "first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)\\b",
    )

    /** Cuts an over-long chapter at paragraph boundaries. */
    private fun chunk(chapter: EpubParser.Chapter): List<EpubParser.Chapter> {
        if (chapter.wordCount <= MAX_UNSTRUCTURED_WORDS) return listOf(chapter)
        val out = mutableListOf<EpubParser.Chapter>()
        var buffer = StringBuilder()
        var words = 0
        for (paragraph in chapter.text.split("\n\n")) {
            val size = BookText.wordCount(paragraph)
            if (buffer.isNotEmpty() && words + size > MAX_UNSTRUCTURED_WORDS) {
                out += EpubParser.Chapter("", buffer.toString().trim())
                buffer = StringBuilder()
                words = 0
            }
            if (buffer.isNotEmpty()) buffer.append("\n\n")
            buffer.append(paragraph)
            words += size
        }
        if (buffer.isNotEmpty()) out += EpubParser.Chapter("", buffer.toString().trim())
        return out
    }

    /**
     * Drops Project Gutenberg's licence header and footer.
     *
     * Every Gutenberg `.txt` opens with a page of legal text and closes with another,
     * and both would otherwise arrive as chapters of the book. Only files that carry
     * the markers are touched, so a text that merely mentions the project is left
     * whole.
     */
    private fun stripGutenbergBoilerplate(paragraphs: List<String>): List<String> {
        val start = paragraphs.indexOfFirst { START_MARKER.matches(it.trim()) }
        val end = paragraphs.indexOfFirst { END_MARKER.matches(it.trim()) }
        val from = if (start >= 0) start + 1 else 0
        val to = if (end >= from) end else paragraphs.size
        return paragraphs.subList(from, to)
    }

    private val START_MARKER =
        Regex("(?i)\\*{3}\\s*START OF (?:THE|THIS) PROJECT GUTENBERG.*", RegexOption.DOT_MATCHES_ALL)
    private val END_MARKER =
        Regex("(?i)\\*{3}\\s*END OF (?:THE|THIS) PROJECT GUTENBERG.*", RegexOption.DOT_MATCHES_ALL)

    /**
     * Title and author from the `Title:`/`Author:` lines Gutenberg writes.
     *
     * Without them a plain-text book would be titled after its file name, which is
     * `pg1342.txt` for Pride and Prejudice.
     */
    private fun gutenbergMetadata(paragraphs: List<String>): Pair<String, String> {
        var title = ""
        var author = ""
        for (paragraph in paragraphs.take(400)) {
            val line = paragraph.trim()
            if (title.isEmpty()) TITLE_LINE.find(line)?.let { title = it.groupValues[1].trim() }
            if (author.isEmpty()) AUTHOR_LINE.find(line)?.let { author = it.groupValues[1].trim() }
            if (title.isNotEmpty() && author.isNotEmpty()) break
        }
        return title to author
    }

    private val TITLE_LINE = Regex("(?i)^title\\s*:\\s*(.+)$")
    private val AUTHOR_LINE = Regex("(?i)^author\\s*:\\s*(.+)$")

    // ------------------------------------------------------------------- fb2

    /** The `<body>` regions of the document, skipping the footnote body by name. */
    private fun bodyRegions(xml: String): List<String> {
        val out = mutableListOf<String>()
        var at = 0
        while (true) {
            val open = OPEN_BODY.find(xml, at) ?: break
            val close = xml.indexOf("</body", open.range.last + 1)
            if (close < 0) break
            if (!open.value.contains("notes", ignoreCase = true)) {
                out += xml.substring(open.range.last + 1, close)
            }
            at = close + 1
        }
        return out
    }

    private val OPEN_BODY = Regex("(?is)<body\\b[^>]*>")

    /**
     * One chapter per `<section>`.
     *
     * Sections nest — a part contains chapters — and each level carries its own
     * `<title>`, so splitting on every opening tag yields the part and its chapters as
     * separate entries, which is what a reader expects to see in a table of contents.
     */
    private fun splitSections(body: String): List<EpubParser.Chapter> {
        val opens = OPEN_SECTION.findAll(body).toList()
        if (opens.isEmpty()) {
            val text = BookText.fromHtml(body)
            return if (text.isBlank()) emptyList() else listOf(EpubParser.Chapter("", text))
        }
        val out = mutableListOf<EpubParser.Chapter>()
        // Text before the first section is the body's own front matter.
        val lead = BookText.fromHtml(body.substring(0, opens.first().range.first))
        if (lead.isNotBlank()) out += EpubParser.Chapter("", lead)

        opens.forEachIndexed { index, open ->
            val end = opens.getOrNull(index + 1)?.range?.first ?: body.length
            val slice = body.substring((open.range.last + 1).coerceAtMost(body.length), end)
            val text = BookText.fromHtml(slice)
            if (text.isBlank()) return@forEachIndexed
            out += EpubParser.Chapter(sectionTitle(slice), text)
        }
        return out
    }

    /** The `<title>` of a section, flattened to one line. */
    private fun sectionTitle(section: String): String {
        val open = OPEN_TITLE.find(section) ?: return ""
        val close = section.indexOf("</title", open.range.last + 1)
        if (close < 0) return ""
        val raw = section.substring(open.range.last + 1, close)
        return BookText.fromHtml(raw).replace('\n', ' ').trim().take(80)
    }

    private val OPEN_SECTION = Regex("(?is)<section\\b[^>]*>")
    private val OPEN_TITLE = Regex("(?is)<title\\b[^>]*>")

    /** FB2 metadata, all of it inside `<description>`. */
    private fun elementText(xml: String, name: String): String {
        val open = Regex("(?is)<$name\\b[^>]*>").find(xml) ?: return ""
        val close = Regex("(?is)</$name\\s*>").find(xml, open.range.last + 1) ?: return ""
        return BookText.fromHtml(xml.substring(open.range.last + 1, close.range.first))
            .replace('\n', ' ')
            .trim()
    }

    private fun authorOf(xml: String): String {
        val descStart = Regex("(?is)<description\\b[^>]*>").find(xml)?.range?.last ?: return ""
        val descEnd = xml.indexOf("</description", descStart)
        val head = if (descEnd < 0) xml.substring(descStart) else xml.substring(descStart, descEnd)
        val first = elementText(head, "first-name")
        val last = elementText(head, "last-name")
        return listOf(first, last).filter { it.isNotBlank() }.joinToString(" ")
    }

    // ------------------------------------------------------------------ html

    /** Cuts the document at its top-level headings, so each becomes a chapter. */
    private fun splitOnHtmlHeadings(html: String): List<String> {
        val headings = HEADING.findAll(html).map { it.range.first }.toList()
        if (headings.size < 2) return listOf(html)
        val out = mutableListOf<String>()
        if (headings.first() > 0) out += html.substring(0, headings.first())
        headings.forEachIndexed { index, at ->
            out += html.substring(at, headings.getOrNull(index + 1) ?: html.length)
        }
        return out
    }

    private val HEADING = Regex("(?is)<h[12]\\b[^>]*>")

    /** The `<title>` of the page, for a book made of one HTML file. */
    private fun firstTitle(html: String): String {
        val open = Regex("(?is)<title\\b[^>]*>").find(html) ?: return ""
        val close = html.indexOf("</title", open.range.last + 1)
        if (close < 0) return ""
        return BookText.fromHtml(html.substring(open.range.last + 1, close)).trim().take(120)
    }

    // ---------------------------------------------------------------- shared

    /** Untitled chapters are numbered, so the table of contents has something to show. */
    private fun titleUntitled(chapters: List<EpubParser.Chapter>): List<EpubParser.Chapter> =
        chapters.mapIndexed { index, chapter ->
            if (chapter.title.isBlank()) chapter.copy(title = "第 ${index + 1} 节") else chapter
        }

    /**
     * Decodes a book file to text.
     *
     * A byte-order mark decides when there is one, and the document's own declaration
     * decides for FB2 and HTML. Plain text has neither, so UTF-8 is assumed — with a
     * fallback to GB18030, because a Chinese edition saved by an old editor is not
     * UTF-8 and decodes to a screen of replacement characters otherwise.
     */
    private fun decode(bytes: ByteArray): String {
        val utf8 = com.engreader.app.source.PageCharset.decode(bytes, null)
        if (hasByteOrderMark(bytes)) return utf8
        val broken = utf8.count { it == '\uFFFD' }
        if (broken == 0 || broken * 100 <= utf8.length) return utf8
        val legacy = runCatching { String(bytes, Charset.forName("GB18030")) }.getOrNull() ?: return utf8
        return if (legacy.count { it == '\uFFFD' } < broken) legacy else utf8
    }

    private fun hasByteOrderMark(bytes: ByteArray): Boolean =
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte() ||
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ||
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()
}
