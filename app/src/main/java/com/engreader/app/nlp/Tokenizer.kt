package com.engreader.app.nlp

/** Word-shape helpers shared by the dictionary, the grammar panel and the reader. */
object Tokenizer {

    /**
     * A word: a letter, then letters, apostrophes and hyphens.
     *
     * The letter class covers Latin-1 and Latin Extended, not just `A-Za-z`: the
     * corpus is English but a name or a loanword carries accents — `café`, `Zürich`,
     * `École`, `naïve` — and an ASCII-only class tokenised those as `caf` and `cole`,
     * so tapping the accented character matched nothing and the word count came out
     * short. Scripts outside Latin are deliberately still excluded, so a Chinese
     * gloss in the text is not counted as an English word.
     */
    private val LETTER = "A-Za-z\\u00C0-\\u024F"

    /** Shared with [Paragraphs] and [Sentences] so every layer splits words the same. */
    internal val WORD = Regex("[$LETTER][$LETTER'\\u2019-]*")

    fun words(text: String): List<String> = WORD.findAll(text).map { it.value }.toList()

    /** Word matches with their offsets, for callers that need to rewrite the text. */
    fun matches(text: String): List<MatchResult> = WORD.findAll(text).toList()

    /** Strips surrounding punctuation and possessive endings, lowercases the rest. */
    fun normalize(raw: String): String =
        raw.trim()
            .trim('"', '\'', '\u201C', '\u201D', '\u2018', '\u2019', '(', ')', '[', ']', ',', '.', ';', ':')
            .removeSuffix("'s")
            .removeSuffix("\u2019s")
            .lowercase()

    fun countWords(text: String): Int = WORD.findAll(text).count()

    /**
     * Naive syllable count, good enough to flag "long words" for the difficulty
     * estimate. Vowel groups minus a silent trailing `e`, floored at one.
     */
    fun syllables(word: String): Int {
        val w = word.lowercase().filter { it.isLetter() }
        if (w.isEmpty()) return 0
        if (w.length <= 3) return 1
        var groups = 0
        var prevVowel = false
        for (c in w) {
            val isVowel = c in "aeiouy"
            if (isVowel && !prevVowel) groups++
            prevVowel = isVowel
        }
        if (w.endsWith("e") && !w.endsWith("le") && groups > 1) groups--
        return groups.coerceAtLeast(1)
    }
}
