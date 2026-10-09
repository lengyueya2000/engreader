package com.engreader.app.nlp

/** A sentence plus its character range inside the owning paragraph. */
data class Sentence(
    val text: String,
    val start: Int,
    val end: Int,
    val wordCount: Int,
)

/**
 * Splits a paragraph into sentences.
 *
 * A plain "split on period" breaks on abbreviations, decimals and initials, so a
 * terminator only ends a sentence when it is followed by whitespace and the next
 * word does not continue an abbreviation. Quotation marks are carried with the
 * sentence they close.
 */
object Sentences {

    /** Never end a sentence: titles, months, and forms that only exist dotted. */
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "mt", "vs", "etc",
        "eg", "ie", "fig", "inc", "ltd", "gov", "sen", "rep", "gen", "col",
        "capt", "lt", "sgt", "rev", "hon", "pres", "supt", "dept", "approx",
        "avg", "vol", "pp", "eds", "op", "ed", "al",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct",
        "nov", "dec", "mon", "tue", "tues", "wed", "thu", "thur", "thurs",
        "fri", "sat", "sun", "ad", "bc", "ce", "bce",
    )

    /**
     * Words that are ordinary English *and* an abbreviation.
     *
     * `no`, `us`, `am`, `pm`, `min`, `max`, `est`, `co`, `uk` and friends really do
     * end sentences ("He said no."), so they cannot sit in [ABBREVIATIONS]: that
     * silently merged whole paragraphs into one "sentence". They only suppress the
     * boundary when the next word is lowercase or a number, which is the shape of a
     * continued phrase ("the US government", "no. 5") rather than of a new sentence.
     */
    private val AMBIGUOUS = setOf(
        "no", "us", "usa", "uk", "eu", "un", "am", "pm", "min", "max", "est",
        "co", "who", "bbc", "cnn", "nato",
    )

    fun split(paragraph: String): List<Sentence> {
        if (paragraph.isBlank()) return emptyList()
        val out = mutableListOf<Sentence>()
        var start = 0
        var i = 0
        while (i < paragraph.length) {
            val c = paragraph[i]
            if (c == '.' || c == '!' || c == '?' || c == '\u2026' ||
                c == '\u3002' || c == '\uFF01' || c == '\uFF1F'
            ) {
                // Consume runs like "?!" or "..." as one terminator.
                var j = i
                while (j + 1 < paragraph.length && isTerminator(paragraph[j + 1])) j++
                val after = j + 1
                if (isBoundary(paragraph, i, after)) {
                    // Pull trailing closing quotes/brackets into this sentence.
                    var end = after
                    while (end < paragraph.length && isCloser(paragraph[end])) end++
                    push(out, paragraph, start, end)
                    start = end
                    i = end
                    continue
                }
                i = after
                continue
            }
            i++
        }
        if (start < paragraph.length) push(out, paragraph, start, paragraph.length)
        return out
    }

    private fun push(out: MutableList<Sentence>, source: String, start: Int, end: Int) {
        val raw = source.substring(start, end)
        val lead = raw.indexOfFirst { !it.isWhitespace() }
        if (lead < 0) return
        val text = raw.trim()
        if (text.isEmpty()) return
        val realStart = start + lead
        out += Sentence(
            text = text,
            start = realStart,
            end = realStart + text.length,
            wordCount = Regex("[A-Za-z][A-Za-z'\\u2019-]*").findAll(text).count(),
        )
    }

    private fun isTerminator(c: Char) = c == '.' || c == '!' || c == '?' || c == '\u2026'

    private fun isCloser(c: Char) = c == '"' || c == '\'' || c == '\u201D' || c == '\u2019' ||
        c == ')' || c == ']' || c == '\u300D' || c == '\u300F'

    private fun isBoundary(s: String, terminator: Int, after: Int): Boolean {
        // Must be followed by whitespace, or be the end of the paragraph.
        if (after >= s.length) return true
        if (!s[after].isWhitespace()) {
            // Allow `."` / `.)` before the whitespace.
            if (isCloser(s[after])) return after + 1 >= s.length || s[after + 1].isWhitespace()
            return false
        }
        if (s[terminator] != '.') return true

        val word = wordBefore(s, terminator)
        if (word.length == 1 && word[0].isUpperCase()) return false      // initial: "J. Smith"
        // Dotted abbreviations ("p.m.", "U.S.") only match once the dots are removed.
        val bare = word.replace(".", "").lowercase()
        if (bare in ABBREVIATIONS) return false
        if (bare in AMBIGUOUS && continuesPhrase(s, after)) return false
        // Decimal or version number: digit on both sides.
        if (terminator > 0 && terminator + 1 < s.length &&
            s[terminator - 1].isDigit() && s[terminator + 1].isDigit()
        ) return false
        return true
    }

    /**
     * True when the word after the period reads as a continuation rather than as the
     * start of a new sentence: lowercase ("no. 5", "the US government") or a number.
     */
    private fun continuesPhrase(s: String, after: Int): Boolean {
        var i = after
        while (i < s.length && s[i].isWhitespace()) i++
        if (i >= s.length) return false
        val c = s[i]
        if (c.isDigit()) return true
        // A leading quote or bracket belongs to the next word, not to the sentence.
        if (isCloser(c) || c == '(' || c == '\u201C' || c == '\u2018') {
            var j = i + 1
            while (j < s.length && (s[j].isWhitespace() || isCloser(s[j]))) j++
            return j < s.length && (s[j].isLowerCase() || s[j].isDigit())
        }
        return c.isLowerCase()
    }

    private fun wordBefore(s: String, index: Int): String {
        var i = index - 1
        while (i >= 0 && (s[i].isLetter() || s[i] == '.')) i--
        return s.substring(i + 1, index)
    }
}
