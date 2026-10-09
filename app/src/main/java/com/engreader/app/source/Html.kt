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
    private val SCRIPT = Regex("(?is)<(script|style|noscript|svg|form|nav|aside)\\b.*?</\\1>")
    private val COMMENT = Regex("(?s)<!--.*?-->")

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

    private fun decodeOnce(input: String): String {
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
    fun text(html: String): String =
        decode(TAGS.replace(html, " ")).replace(Regex("\\s+"), " ").trim()

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

    fun stripNoise(html: String): String =
        SCRIPT.replace(COMMENT.replace(html, " "), " ")
}
