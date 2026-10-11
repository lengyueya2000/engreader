package com.engreader.app.source

/** HTML entity decoding and tag stripping, shared by the feed parser and extractor. */
object Html {

    /**
     * The inside of a start tag: everything up to the `>` that really closes it.
     *
     * `[^>]*` stops at the first `>`, including one inside a quoted attribute value:
     * `<p title="5 > 3">alpha</p>` lost its tag and leaked `3">alpha` into the text.
     * A quoted run is stepped over as a unit so the `>` inside it cannot end the tag.
     */
    internal const val ATTRS = "(?:[^>\"']|\"[^\"]*\"|'[^']*')*"

    /**
     * A tag, requiring a tag name.
     *
     * `<[^>]+>` also matched prose comparisons: "x < y > z" lost everything between
     * the angle brackets. A name character after the `<` (or a closing `/`) is what
     * separates markup from arithmetic.
     */
    private val TAGS = Regex("</?[A-Za-z]$ATTRS>")

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
            "td|textarea|tfoot|th|thead|tr|ul|video)\\b$ATTRS>",
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

    private val COMMENT = Regex("(?s)<!--.*?-->")

    /** Elements dropped whole by [stripNoise], before anything else is rewritten. */
    private val SCRIPT_ELEMENTS = setOf("script", "style", "noscript", "svg", "form", "nav", "aside")

    /**
     * Elements dropped whole by [stripNoise], after hidden text has gone.
     *
     * A `<figcaption>` is a caption and a `<footer>` is page chrome, but both are
     * written with ordinary `<p>` elements, so the extractor kept them: the BBC's
     * footer paragraph arrived as a final "Copyright © 2026 BBC…" paragraph and its
     * image captions arrived as stray sentences in the middle of the prose.
     */
    private val CHROME_ELEMENTS = setOf("figure", "figcaption", "footer", "template")

    /** Any run of whitespace, for collapsing markup-produced gaps. */
    private val WHITESPACE = Regex("\\s+")

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
            "$ATTRS class\\s*=\\s*(\"[^\"]*(?:visually-?hidden|sr-only|screen-?reader|" +
            "a11y-hidden|hidden-text)[^\"]*\"|'[^']*(?:visually-?hidden|sr-only|" +
            "screen-?reader|a11y-hidden|hidden-text)[^']*')$ATTRS>.{0,240}?</\\1\\s*>",
    )

    private val ENTITIES = mapOf(
        "nbsp" to " ", "amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">",
        "mdash" to "—", "ndash" to "–", "lsquo" to "‘", "rsquo" to "’",
        "ldquo" to "“", "rdquo" to "”", "hellip" to "…", "middot" to "·",
        "bull" to "•", "deg" to "°", "pound" to "£", "euro" to "€",
        "copy" to "©", "reg" to "®", "trade" to "™", "times" to "×",
        "laquo" to "«", "raquo" to "»", "prime" to "′", "Prime" to "″",
        // Latin-1 letters that reach the app through real pages: a Guardian story
        // about a café or a Reuters byline with an accent is otherwise left as
        // literal `&eacute;` in the middle of the sentence.
        "agrave" to "à", "aacute" to "á", "acirc" to "â", "atilde" to "ã",
        "auml" to "ä", "aring" to "å", "aelig" to "æ", "ccedil" to "ç",
        "egrave" to "è", "eacute" to "é", "ecirc" to "ê", "euml" to "ë",
        "igrave" to "ì", "iacute" to "í", "icirc" to "î", "iuml" to "ï",
        "ntilde" to "ñ", "ograve" to "ò", "oacute" to "ó", "ocirc" to "ô",
        "otilde" to "õ", "ouml" to "ö", "oslash" to "ø", "ugrave" to "ù",
        "uacute" to "ú", "ucirc" to "û", "uuml" to "ü", "yacute" to "ý",
        "yuml" to "ÿ", "szlig" to "ß",
        "Agrave" to "À", "Aacute" to "Á", "Acirc" to "Â", "Auml" to "Ä",
        "Aring" to "Å", "AElig" to "Æ", "Ccedil" to "Ç", "Egrave" to "È",
        "Eacute" to "É", "Ecirc" to "Ê", "Euml" to "Ë", "Iacute" to "Í",
        "Ntilde" to "Ñ", "Ograve" to "Ò", "Oacute" to "Ó", "Ocirc" to "Ô",
        "Ouml" to "Ö", "Oslash" to "Ø", "Ugrave" to "Ù", "Uacute" to "Ú",
        "Uuml" to "Ü", "Yacute" to "Ý",
        "sect" to "§", "para" to "¶", "dagger" to "†", "Dagger" to "‡",
        "permil" to "‰", "frac12" to "½", "frac14" to "¼", "sup2" to "²",
        "sup3" to "³", "ordf" to "ª", "ordm" to "º", "not" to "¬",
        "shy" to "", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
        "minus" to "−", "plusmn" to "±", "divide" to "÷", "micro" to "µ",
        "cent" to "¢", "curren" to "¤", "yen" to "¥", "brvbar" to "¦",
        "iexcl" to "¡", "iquest" to "¿", "uml" to "¨", "acute" to "´",
        "cedil" to "¸", "macr" to "¯", "circ" to "ˆ", "tilde" to "˜",
    )

    /**
     * One entity, numeric or named, matched in a single pass.
     *
     * Both forms have to be replaced together: decoding `&#38;lt;` with a numeric
     * pass and then a named pass over the result turned the literal text `&lt;` into
     * `<`, which is a second decode of the author's own characters.
     */
    private val ENTITY = Regex("&(#(?:[xX][0-9a-fA-F]+|[0-9]+)|[a-zA-Z][a-zA-Z0-9]{1,31});")

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
        return ENTITY.replace(input) { m ->
            val body = m.groupValues[1]
            if (body.startsWith("#")) decodeNumeric(body) ?: m.value else named(body) ?: m.value
        }
    }

    private fun decodeNumeric(body: String): String? {
        val hex = body.length > 1 && (body[1] == 'x' || body[1] == 'X')
        val digits = body.substring(if (hex) 2 else 1)
        val code = digits.toIntOrNull(if (hex) 16 else 10) ?: return null
        // A lone surrogate has no UTF-8 encoding and is not a character: the range
        // 0xD800..0xDFFF would produce a String that later breaks encoding, so the
        // reference is left as written rather than turned into an unpaired half.
        if (code !in 0x1..0x10FFFF) return null
        if (code in 0xD800..0xDFFF) return null
        return String(Character.toChars(code))
    }

    private fun named(name: String): String? =
        ENTITIES[name] ?: ENTITIES[name.lowercase()]

    /** The character a named entity stands for, for callers that must rewrite markup. */
    internal fun entity(name: String): String? = named(name)

    /** Decodes entities, drops markup, and collapses whitespace into single spaces. */
    fun text(html: String): String {
        // Block elements first: their boundary is real text separation. Whatever tag
        // is left is inline and is deleted outright, so a word and the punctuation
        // after it stay together.
        val separated = BLOCK.replace(html, " ")
        val bare = TAGS.replace(separated, "")
        val flat = decode(bare).replace(WHITESPACE, " ").trim()
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
        val noComments = COMMENT.replace(html, " ")
        val noScripts = dropElements(noComments, SCRIPT_ELEMENTS)
        val noHidden = HIDDEN.replace(noScripts, " ")
        return dropElements(noHidden, CHROME_ELEMENTS)
    }

    /**
     * Removes each named element together with its content, one linear pass per name.
     *
     * The obvious regex, `<(a|b)\b[^>]*>.*?</\1>`, is quadratic when an element opens
     * repeatedly without ever closing: every opening makes the engine scan to the end
     * of the document before giving up. A page with a thousand stray `<script>` tags —
     * a broken template, or one crafted to stall the extractor — would take minutes.
     * Scanning with `indexOf`-backed regexes instead keeps each pass linear, and an
     * element that never closes is given up on once rather than searched for per
     * opening.
     */
    internal fun dropElements(html: String, names: Collection<String>): String {
        var out = html
        for (name in names) {
            if (!out.contains("</$name", ignoreCase = true)) continue
            out = dropElement(out, name)
        }
        return out
    }

    internal fun dropElement(html: String, name: String): String {
        val open = Regex("(?is)<$name\\b$ATTRS>")
        val close = Regex("(?is)</$name\\s*>")
        val out = StringBuilder(html.length)
        var at = 0
        while (true) {
            val start = open.find(html, at) ?: break
            val end = close.find(html, start.range.last + 1) ?: break
            out.append(html, at, start.range.first).append(' ')
            // Past the closing tag, so the next search cannot re-find this element.
            at = end.range.last + 1
        }
        out.append(html, at, html.length)
        return out.toString()
    }
}
