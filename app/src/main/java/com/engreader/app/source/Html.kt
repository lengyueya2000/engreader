package com.engreader.app.source

/** HTML entity decoding and tag stripping, shared by the feed parser and extractor. */
object Html {

    /**
     * A tag, requiring a tag name.
     *
     * `<[^>]+>` also matched prose comparisons: "x < y > z" lost everything between
     * the angle brackets. A name character after the `<` (or a closing `/`) is what
     * separates markup from arithmetic.
     */
    private val TAGS = Regex("</?[A-Za-z][^>]*>")

    /**
     * Elements that end the text on either side of them.
     *
     * Anything outside this set is inline — it wraps a word or phrase *inside* a
     * sentence — and is deleted rather than replaced by a space. The old rule
     * ("every tag becomes a space") detached whatever followed a link or an emphasis
     * from the word it belonged to: `<a …>Gaza</a>.` reached the reader as `Gaza .`.
     * Deleting an inline tag is also what a browser does: with no whitespace between
     * two inline elements there is no gap on screen either.
     */
    private val BLOCK = Regex(
        "(?is)</?(?:address|article|aside|audio|blockquote|br|button|canvas|caption|center|" +
            "col|colgroup|dd|details|dialog|dir|div|dl|dt|embed|fieldset|figcaption|figure|" +
            "footer|form|h[1-6]|header|hgroup|hr|iframe|img|input|legend|li|main|menu|nav|" +
            "noframes|noscript|ol|optgroup|option|p|pre|select|section|summary|table|tbody|" +
            "td|textarea|tfoot|th|thead|tr|ul|video)\\b[^>]*>",
    )

    /**
     * A gap before a closing mark, and one after an opening mark.
     *
     * Markup that writes the space itself (`word <em>,</em>`) and a `&nbsp;` both leave
     * a gap no English sentence wants. Only marks whose direction is unambiguous are
     * listed: `'` and `"` open as often as they close, a spaced dash is house style at
     * the Guardian, and a spaced ellipsis is ordinary English, so text is not moved
     * across any of those.
     */
    private val SPACE_BEFORE = Regex("\\s+([,.;:!?%\u201D\u2019\\]\\)\\}])")
    private val SPACE_AFTER = Regex("([\u201C\u2018\\[\\(\\{])\\s+")

    private val SCRIPT = Regex("(?is)<(script|style|noscript|svg|form|nav|aside)\\b.*?</\\1>")
    private val COMMENT = Regex("(?s)<!--.*?-->")

    /**
     * Containers whose text is never the article body.
     *
     * A `<figcaption>` is a caption and a `<footer>` is page chrome, but both are
     * written with ordinary `<p>` elements, so the extractor kept them: the BBC's
     * footer paragraph arrived as a final "Copyright © 2026 BBC…" paragraph and its
     * image captions arrived as stray sentences in the middle of the prose.
     */
    private val CHROME = Regex("(?is)<(figure|figcaption|footer|template)\\b[^>]*>.*?</\\1\\s*>")

    /**
     * An element whose class marks its text as visible only to a screen reader.
     *
     * That text is on the page for assistive technology, not for a reader: the BBC
     * hides `, external` after every outbound link and `Figure caption, ` before every
     * caption this way, and both leaked into the extracted body — the first turned a
     * finished sentence into `…in a post on X., external`. The body is bounded so an
     * unclosed element cannot swallow the rest of the page.
     */
    private val HIDDEN = Regex(
        "(?is)<(span|div|p|em|strong|i|b|a|small|sup|sub|label|td|li|h[1-6])\\b" +
            "[^>]*class\\s*=\\s*(\"[^\"]*(?:visually-?hidden|sr-only|screen-?reader|" +
            "a11y-hidden|hidden-text)[^\"]*\"|'[^']*(?:visually-?hidden|sr-only|" +
            "screen-?reader|a11y-hidden|hidden-text)[^']*')[^>]*>.{0,240}?</\\1\\s*>",
    )

    private val ENTITIES = mapOf(
        "nbsp" to " ", "amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">",
        "mdash" to "—", "ndash" to "–", "lsquo" to "‘", "rsquo" to "’",
        "ldquo" to "“", "rdquo" to "”", "hellip" to "…", "middot" to "·",
        "bull" to "•", "deg" to "°", "pound" to "£", "euro" to "€",
        "copy" to "©", "reg" to "®", "trade" to "™", "times" to "×",
        "laquo" to "«", "raquo" to "»", "prime" to "′", "Prime" to "″",
    )

    private val NAMED = Regex("&([a-zA-Z][a-zA-Z0-9]{1,31});")

    /** `&#39;` and `&#x27;`; the `x` may be either case, as HTML allows. */
    private val NUMERIC = Regex("&#([xX]?)([0-9a-fA-F]+);")

    /**
     * Decodes entities, twice when the first pass exposes another layer.
     *
     * Feeds routinely double-encode (`&amp;#39;`), and a single pass turns that into
     * a literal `&#39;` that is then shown to the reader. The second pass only runs
     * when the first actually produced an `&`, so ordinary text is decoded once.
     */
    fun decode(input: String): String {
        var out = decodeOnce(input)
        if ("&#" in out || "&amp;" in out) out = decodeOnce(out)
        return out
    }

    /**
     * Decodes entities exactly once.
     *
     * [decode] deliberately runs a second pass, because feeds double-encode. Book
     * text must not: EPUB and MOBI bodies are well-formed XML, where `&amp;amp;`
     * means the literal characters `&amp;`, and decoding twice would silently change
     * what the author wrote.
     */
    fun decodeOnce(input: String): String {
        if ('&' !in input) return input
        var out = NUMERIC.replace(input) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            code?.takeIf { it in 0x1..0x10FFFF }?.let { String(Character.toChars(it)) } ?: m.value
        }
        out = NAMED.replace(out) { m ->
            ENTITIES[m.groupValues[1]] ?: ENTITIES[m.groupValues[1].lowercase()] ?: m.value
        }
        return out
    }

    /** Decodes entities, drops markup, and collapses whitespace into single spaces. */
    fun text(html: String): String {
        // Block elements first: their boundary is real text separation. Whatever tag
        // is left is inline and is deleted outright, so a word and the punctuation
        // after it stay together.
        val separated = BLOCK.replace(html, " ")
        val bare = TAGS.replace(separated, "")
        val flat = decode(bare).replace(Regex("\\s+"), " ").trim()
        return SPACE_AFTER.replace(SPACE_BEFORE.replace(flat, "$1"), "$1")
    }

    /**
     * Same as [text] but for a summary: feeds often double-encode their markup
     * (`&lt;p&gt;...`), so the tags only appear after entities are decoded. Runs the
     * strip twice to catch both layers.
     */
    fun summary(html: String): String {
        var out = text(html)
        if ('<' in out) out = text(out)
        return out
    }

    fun stripNoise(html: String): String {
        val noScripts = SCRIPT.replace(COMMENT.replace(html, " "), " ")
        val noHidden = HIDDEN.replace(noScripts, " ")
        return CHROME.replace(noHidden, " ")
    }
}
