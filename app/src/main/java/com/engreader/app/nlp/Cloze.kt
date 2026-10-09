package com.engreader.app.nlp

/**
 * Replaces a word with a blank inside a sentence, keeping the sentence readable.
 *
 * Lives outside [QuizBuilder] because review cards need the same behaviour on a
 * stored sentence rather than on a freshly tokenised one, and the two must not
 * drift: a blank that eats the following space reads as a typo, and one that cuts
 * a longer word in half (`reform` inside `reforms`) teaches the wrong spelling.
 */
object Cloze {

    /** The gap itself. Underscores rather than a box glyph so it survives any font. */
    const val BLANK = "______"

    /**
     * Blanks the [occurrence]-th whole-word match of [word] in [sentence].
     *
     * Matching is case-insensitive and anchored to word boundaries, so blanking
     * `derive` does not touch `derived`. When [word] is not present the sentence is
     * returned unchanged, which the caller treats as "no card context".
     */
    fun blank(sentence: String, word: String, occurrence: Int = 0): String {
        if (sentence.isBlank() || word.isBlank()) return sentence
        val matches = Regex(
            "(?<![A-Za-z'\\u2019-])" + Regex.escape(word) + "(?![A-Za-z'\\u2019-])",
            RegexOption.IGNORE_CASE,
        ).findAll(sentence).toList()
        val match = matches.getOrNull(occurrence.coerceAtLeast(0)) ?: return sentence
        return replace(sentence, match.range.first, match.range.last + 1)
    }

    /**
     * Blanks a word whose position is already known.
     *
     * Used where the caller tokenised the sentence itself and has exact offsets, so
     * there is no ambiguity about which occurrence is meant.
     */
    fun blankAt(sentence: String, start: Int, end: Int): String {
        if (start < 0 || end > sentence.length || start >= end) return sentence
        return replace(sentence, start, end)
    }

    /**
     * Writes the gap over `[start, end)` and absorbs the punctuation that belonged
     * to the word, but never the space after it.
     *
     * A blank that swallows the trailing space runs into the next word
     * (`______is`); one that leaves the word's comma behind reads as a typo
     * (`______, is` for `however, is`). Trailing punctuation goes with the word and
     * the separating space stays.
     */
    private fun replace(sentence: String, start: Int, end: Int): String {
        var after = end
        while (after < sentence.length && isWordTrailing(sentence[after])) after++
        val tail = sentence.substring(after)
        val separator = if (tail.isNotEmpty() && !tail.first().isWhitespace()) " " else ""
        return sentence.substring(0, start) + BLANK + separator + tail
    }

    /**
     * Punctuation that sticks to the word rather than starting the next one.
     *
     * An apostrophe is deliberately absent: `minister's` is one token to the
     * tokeniser, so a blank never lands before one, and absorbing it would turn a
     * possessive into a bare plural.
     */
    private fun isWordTrailing(c: Char): Boolean =
        c == ',' || c == '.' || c == ';' || c == ':' || c == '!' || c == '?' ||
            c == ')' || c == ']' || c == '"' || c == '\u201D'
}
