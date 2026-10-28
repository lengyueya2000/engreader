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

    /**
     * The same letters as [WORD], as a character class for word-boundary lookarounds.
     *
     * Exposed because [Cloze] has to decide whether a match is a whole word, and its
     * own ASCII-only class let an accented letter through: `cole` matched inside
     * `École` and the blank cut the word in half.
     */
    internal val BOUNDARY_CLASS = "[$LETTER'\\u2019-]"

    fun words(text: String): List<String> = WORD.findAll(text).map { it.value }.toList()

    /** Word matches with their offsets, for callers that need to rewrite the text. */
    fun matches(text: String): List<MatchResult> = WORD.findAll(text).toList()

    /**
     * Strips surrounding punctuation and possessive endings, lowercases the rest.
     *
     * The possessive `'s` is only removed when the stem is not a contraction. Stripping
     * it off `it's`/`he's`/`that's`/`let's` left the pronoun `it`/`he`/`that`, so every
     * lookup keyed on the result — the dictionary, the quiz's part-of-speech pass, the
     * vocabulary grader — asked about a different word from the one on screen. A real
     * possessive (`minister's`, `John's`) still reduces to its noun, which is the case
     * the stripping exists for.
     */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
            .trim('"', '\'', '\u201C', '\u201D', '\u2018', '\u2019', '(', ')', '[', ']', ',', '.', ';', ':')
            .lowercase()
        val stem = when {
            trimmed.endsWith("'s") -> trimmed.dropLast(2)
            trimmed.endsWith("\u2019s") -> trimmed.dropLast(2)
            else -> return trimmed
        }
        return if (stem in CONTRACTION_STEMS) trimmed else stem
    }

    /**
     * Stems whose `'s` is `is`/`has`/`us` rather than a possessive.
     *
     * Kept as the closed set of words that can precede the contracted verb; anything
     * else ending in `'s` is read as a possessive, which is the useful direction to be
     * wrong in — `minister's` has to reach `minister`, while a contraction split into
     * its stem has no reading at all.
     */
    private val CONTRACTION_STEMS = setOf(
        "it", "he", "she", "that", "this", "there", "what", "who", "how",
        "when", "where", "here", "let",
    )

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
