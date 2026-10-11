package com.engreader.app.book

import com.engreader.app.source.Html

/**
 * Turns a book chapter's markup into the plain text the reader stores.
 *
 * The reader keeps article bodies as paragraphs separated by blank lines, so the
 * only thing that matters here is that block boundaries survive as line breaks.
 * [com.engreader.app.source.Html.text] collapses everything to one line, which is
 * right for a feed summary and wrong for a chapter: the whole book would arrive as
 * a single paragraph and lose the sentence structure the grammar panel works on.
 *
 * Pure JVM, so the parsers that use it can be tested without a device.
 */
object BookText {

    /** Elements whose content is never prose. Dropped whole, not flattened. */
    private val DROPPED = setOf(
        "script", "style", "head", "noscript", "svg", "math", "object", "iframe",
        "video", "audio",
    )

    /** Block-level openers: a line break *before* the element's text. */
    private val BLOCK_OPEN = Regex(
        "(?is)<(p|div|section|article|blockquote|li|dd|dt|tr|h[1-6]|pre|figure|figcaption|" +
            "td|th|table|ul|ol|dl|header|footer|main|aside|nav|hr)\\b${Html.ATTRS}>"
    )

    /** Block-level closers, and `<br>`, which is a break on its own. */
    private val BLOCK_CLOSE = Regex(
        "(?is)</(p|div|section|article|blockquote|li|dd|dt|tr|h[1-6]|pre|figure|figcaption|" +
            "td|th|table|ul|ol|dl|header|footer|main|aside|nav)\\s*>|<br\\b${Html.ATTRS}/?>"
    )

    private val ANY_TAG = Regex("(?is)</?[A-Za-z]${Html.ATTRS}>")

    /**
     * XML declarations and the doctype, which are not elements.
     *
     * An XHTML chapter file opens with `<?xml version="1.0" encoding="utf-8"?>` and
     * `<!DOCTYPE html>`. [ANY_TAG] requires a letter after the optional `/`, so neither
     * matched and both were left in the chapter text — every imported book opened with
     * a line of markup, which the translator then dutifully turned into Chinese.
     */
    private val DECLARATION = Regex("(?is)<\\?.*?\\?>|<!DOCTYPE\\b[^>]*>")

    private val COMMENT = Regex("(?s)<!--.*?-->")
    private val SENTENCE_END = Regex("[.!?][\"'\\u201D\\u2019)]*\\s*$")

    /** Runs of any whitespace, including the non-breaking spaces conversions leave behind. */
    private val ALL_SPACE = Regex("[\\s\\u00A0\\u2007\\u202F]+")

    /** The same, but stopping at a line break, which is a paragraph boundary. */
    private val LINE_SPACE = Regex("[ \\t\\u00A0\\u2007\\u202F]+")

    /** Plain whitespace, for flattening an already line-broken label. */
    private val WHITESPACE = Regex("\\s+")

    /**
     * Removes each [DROPPED] element with its content.
     *
     * Shared with the feed path, which needs the same protection: the obvious regex,
     * `<(script|…)\b[^>]*>.*?</\1\s*>`, is quadratic on markup that opens the element
     * repeatedly without closing it. A book with a thousand stray `<script>` tags — a
     * corrupt conversion, or a file crafted to stall the importer — would take minutes.
     */
    private fun dropElements(html: String): String = Html.dropElements(html, DROPPED)

    /**
     * Paragraph break sentinel.
     *
     * A control character no book contains, so a break inserted for a block element
     * cannot be confused with a line break the source itself wrote — the source's
     * own newlines are flattened to spaces and the sentinel is the only thing left
     * standing when the text is split into paragraphs.
     */
    private const val BREAK = "\u0000"

    /**
     * Chapter markup to paragraph-separated text.
     *
     * Entities are decoded after tags are stripped, so `&lt;p&gt;` in the source
     * becomes a literal `<p>` in the text rather than being re-read as markup.
     *
     * Only block boundaries become paragraph breaks. A converted book wraps its
     * source lines at column 72, so a paragraph is spread over several lines of
     * markup; treating those as breaks would cut a sentence in half and hand the
     * grammar panel two fragments to analyse. The block markers are therefore
     * rewritten to a sentinel before any other whitespace is flattened, which is
     * what keeps the author's line wrapping out of the paragraph structure.
     */
    fun fromHtml(html: String): String {
        var s = COMMENT.replace(html, " ")
        s = DECLARATION.replace(s, " ")
        s = dropElements(s)
        s = BLOCK_OPEN.replace(s, BREAK)
        s = BLOCK_CLOSE.replace(s, BREAK)
        s = ANY_TAG.replace(s, "")
        s = s.replace(ALL_SPACE, " ")
        s = s.replace(BREAK, "\n")
        return fromPlain(s)
    }

    /**
     * Normalises text that has already had its markup removed.
     *
     * Runs of spaces become one, lines are trimmed, and blank lines are dropped —
     * the reader re-joins paragraphs with exactly one blank line, so leaving the
     * source's own spacing in place would produce ragged gaps between paragraphs.
     * A line break in the input is taken as a paragraph break, which is what a
     * plain-text body means by one.
     */
    fun fromPlain(text: String): String {
        // Single-pass decoding: a book body is well-formed XML, so `&amp;amp;` really
        // does mean the five characters `&amp;`. The feed path decodes twice because
        // feeds double-encode; doing that here would rewrite the author's text.
        val decoded = com.engreader.app.source.Html.decodeOnce(text)
        val lines = decoded.split('\n', '\r')
            .map { line -> line.replace(LINE_SPACE, " ").trim() }
            .filter { it.isNotEmpty() }
        return lines.joinToString("\n\n")
    }

    /**
     * The chapter's own heading, when it has one.
     *
     * Used as the table-of-contents label for books that ship no navigation
     * document — plenty of conversions have a `<h1>` per chapter and no NCX. A
     * heading is only accepted when it is short and has no sentence terminator,
     * which is what keeps the opening paragraph of a chapter out of the title.
     */
    fun firstHeading(html: String): String? {
        val match = Regex("(?is)<h([1-3])\\b${Html.ATTRS}>(.*?)</h\\1\\s*>").find(html) ?: return null
        val text = fromHtml(match.groupValues[2]).replace("\n", " ").trim()
        if (text.isEmpty() || text.length > 90) return null
        if (SENTENCE_END.containsMatchIn(text)) return null
        return text
    }

    /**
     * A label for a chapter in the table of contents.
     *
     * Falls back to a chapter marker in the opening lines when there is no real
     * heading. Kindlegen writes `CHAPTER II.` as a centred paragraph rather than an
     * `<h2>`, so without this a converted novel's table of contents is eighty
     * identical entries and the chapter number is the only thing telling them apart.
     *
     * The scan is limited to the opening of the chapter, and the marker has to be a
     * word like *chapter* followed by a numeral, which is what keeps a sentence that
     * merely mentions "chapter three" from becoming the title. Markers preceded by
     * *heading to* or *tailpiece to* are skipped: those are captions on an
     * illustration plate, and they name the chapter the plate belongs to rather than
     * the one being read.
     *
     * [text] is the chapter's already-extracted text when the caller has it; the
     * import path extracts it anyway to store the chapter, and converting the same
     * markup a second time here was a full extra pass per chapter.
     */
    fun chapterHeading(html: String, text: String? = null): String? {
        firstHeading(html)?.let { return it }
        val head = (text ?: fromHtml(html)).take(HEADING_SCAN_CHARS)
        // Every marker in the opening, not just the first: an illustration plate is
        // captioned before the chapter it precedes, so the first match is often the
        // rejected one and the real title sits a line further down.
        for (match in CHAPTER_MARKER.findAll(head)) {
            if (PLATE_CAPTION.containsMatchIn(head.substring(0, match.range.first))) continue
            return normaliseHeading(match.value)
        }
        return null
    }

    /**
     * `Chapter: I.` reads as `Chapter I`.
     *
     * Shared with the plain-text parser, which has its own reasons for spotting a
     * heading and must still produce the same title for a marker the shared rule missed.
     */
    internal fun normaliseHeading(raw: String): String {
        val trimmed = raw.trim().trimEnd('.', ',', ':', ';').replace(WHITESPACE, " ")
        val parts = MARKER_PARTS.matchEntire(trimmed) ?: return trimmed
        return "${parts.groupValues[1]} ${parts.groupValues[2]}"
    }

    /**
     * Tidies a table-of-contents label taken from the book's own navigation.
     *
     * Project Gutenberg's EPUBs label each chapter with the illustration caption that
     * happens to sit on the same page — `I hope Mr. Bingley will like it. CHAPTER
     * II.` — which is the publisher's own wording but reads as noise in a list of
     * seventy entries. When a label contains a chapter marker, the marker is the
     * chapter's name and the rest is the caption, so the marker wins. A label with no
     * marker is left alone: `Preface` and `List of Illustrations` are real titles.
     */
    fun cleanContentsLabel(label: String): String {
        val flat = label.replace(WHITESPACE, " ").trim()
        val match = CHAPTER_MARKER.find(flat) ?: return flat.take(MAX_LABEL_CHARS)
        if (PLATE_CAPTION.containsMatchIn(flat.substring(0, match.range.first))) return flat.take(MAX_LABEL_CHARS)
        return normaliseHeading(match.value)
    }

    /** A label long enough to be a paragraph is truncated rather than shown whole. */
    private const val MAX_LABEL_CHARS = 80

    /** Word count using the reader's own token rule, so counts agree everywhere. */
    fun wordCount(text: String): Int = com.engreader.app.nlp.Tokenizer.countWords(text)

    /** How far into a chapter a heading is still plausible as its title. */
    private const val HEADING_SCAN_CHARS = 400

    /**
     * A chapter or part marker.
     *
     * The separator is optional because a bad conversion can run the two words
     * together — `CHAPTERXXVII.` occurs in the wild — and the numeral is required, so
     * a sentence that happens to start with the word "chapter" is not a match.
     */
    private val CHAPTER_MARKER = Regex(
        "(?i)\\b(?:(?:chapter|part|book|section)\\s*:?\\s*(?:[IVXLC]+|\\d+)\\b" +
            "|prologue|epilogue|introduction|preface|foreword|afterword|appendix)\\b\\.?"
    )

    private val MARKER_PARTS = Regex("(?i)^(chapter|part|book|section)\\s*:?\\s*(.+)$")

    private val PLATE_CAPTION = Regex(
        "(?i)(?:heading|tailpiece|frontispiece|illustration|plate)s?\\s+to\\s+$"
    )
}
