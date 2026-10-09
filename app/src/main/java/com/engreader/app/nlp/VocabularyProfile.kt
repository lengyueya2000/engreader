package com.engreader.app.nlp

import com.engreader.app.dict.DifficultyBand
import com.engreader.app.dict.Lexicon

/**
 * One word from an article's unknown list, resolved for display.
 *
 * Carried through the state rather than looked up per row: the sheet can be
 * scrolled through a hundred words, and a dictionary query per recomposition is
 * both slower and easier to get wrong than resolving once.
 */
data class NewWord(
    val lemma: String,
    val gloss: String,
    val band: DifficultyBand,
)

/**
 * Which words in a passage the reader does not yet know.
 *
 * [VocabularyGrader] grades a text against corpus frequency, which describes the
 * text; this describes the text *relative to one learner*, which is what decides
 * whether it is worth reading. A passage tagged 中高 may be entirely known to
 * someone who reads science writing weekly, and the same passage may be half new
 * to someone who does not.
 *
 * "Known" is defined conservatively — a word counts as known only when the reader
 * has explicitly marked it mastered, or has looked it up and then answered it
 * correctly at least once. Merely having saved a word does not count: saving is
 * what you do *because* you do not know it.
 */
class VocabularyProfile(private val dictionary: Lexicon) {

    /**
     * Distinct study-worthy lemmas in [text], in first-appearance order.
     *
     * Stored per article so the unknown rate can be recomputed as the wordbook
     * changes without re-tokenising and re-grading the body on every screen.
     * Function words and proper nouns are excluded: they are not what makes a text
     * hard, and counting them would drown the signal.
     */
    fun contentLemmas(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (match in Tokenizer.matches(text)) {
            val raw = match.value
            val key = Tokenizer.normalize(raw)
            if (key.length < 3) continue
            if (key in STOP) continue
            val entry = dictionary.lookup(key) ?: continue
            // A capitalised word that is not at the start of a sentence is a name in
            // news prose — `Rose`, `Baker`, `March` — and names are not what makes a
            // passage hard. Only the sentence-initial position is exempt, because
            // there the capital is orthography rather than a signal.
            if (raw.first().isUpperCase() && !isSentenceInitial(text, match.range.first)) continue
            if (!entry.isStudyWorthy) continue
            out += entry.lemma.lowercase()
        }
        return out.toList()
    }

    /** True when nothing but whitespace and opening quotes precedes [offset]. */
    private fun isSentenceInitial(text: String, offset: Int): Boolean {
        var i = offset - 1
        while (i >= 0) {
            val c = text[i]
            if (c.isWhitespace() || c == '"' || c == '\'' || c == '\u201C' || c == '\u2018' ||
                c == '(' || c == '[' || c == '-' || c == '\u2014'
            ) {
                i--
                continue
            }
            return c == '.' || c == '!' || c == '?' || c == '\u2026' || c == ':' || c == ';'
        }
        return true
    }

    /**
     * Compares [lemmas] against the words the reader knows.
     *
     * [known] is the set of lemmas to treat as known; the caller builds it from the
     * wordbook, so this stays free of storage concerns and can be tested directly.
     */
    fun assess(lemmas: List<String>, known: Set<String>): Result {
        if (lemmas.isEmpty()) return Result(0, emptyList(), 0f)
        val unknown = lemmas.filterNot { it.lowercase() in known }
        val rate = unknown.size.toFloat() / lemmas.size
        return Result(lemmas.size, unknown, rate)
    }

    /**
     * How the unknown rate reads as a recommendation.
     *
     * The thresholds follow the extensive-reading convention rather than a
     * statistical one: below roughly 2% new words a text can be read for flow
     * without a dictionary, and past about 10% the reader spends more time looking
     * words up than reading, which is the point at which graded material is the
     * better choice.
     *
     * The rate is a share of the passage's *content* lemmas, not of every word in
     * it — function words are excluded before the count — so the wording says
     * "生词" rather than claiming a share of the whole text.
     */
    fun verdict(rate: Float, unknownCount: Int): Verdict = when {
        unknownCount == 0 -> Verdict.Comfortable
        rate <= 0.02f -> Verdict.Comfortable
        rate <= 0.05f -> Verdict.Suitable
        rate <= 0.10f -> Verdict.Stretch
        else -> Verdict.TooHard
    }

    enum class Verdict(val label: String, val detail: String) {
        Comfortable("轻松", "要学的词基本都认识，适合直接读，不用频繁查词。"),
        Suitable("合适", "少量生词，正是靠上下文猜词的好区间。"),
        Stretch("有挑战", "生词偏多，建议先预习生词再读。"),
        TooHard("偏难", "要学的词大多不认识，读起来会很吃力；先预习生词或换一篇更划算。"),
    }

    data class Result(
        /** Distinct study-worthy lemmas in the passage. */
        val total: Int,
        val unknown: List<String>,
        val rate: Float,
    ) {
        /**
         * The unknown words resolved for display, hardest first.
         *
         * Ordering is by band then by length: the C2 word is what makes a passage
         * unreadable, and among equals the longer word is the more likely blocker.
         */
        fun asNewWords(dictionary: Lexicon): List<NewWord> = unknown
            .map { lemma ->
                val entry = dictionary.lookup(lemma)
                NewWord(
                    lemma = entry?.lemma ?: lemma,
                    gloss = entry?.let { dictionary.firstGloss(it.translation) }
                        .orEmpty()
                        .substringBefore(',')
                        .substringBefore('；')
                        .substringBefore(';')
                        .trim(),
                    band = entry?.band ?: DifficultyBand.Unknown,
                )
            }
            .sortedWith(compareByDescending<NewWord> { it.band.ordinal }.thenByDescending { it.lemma.length })
    }

    companion object {
        /**
         * Fewest study-worthy lemmas a passage needs before its rate means anything.
         *
         * The denominator is study-worthy lemmas, not words, and a 500-word news
         * story yields only about thirty of them — so a high floor would silently
         * hide the feature on ordinary articles. Twenty is roughly a 350-word text,
         * which is long enough that one or two new words do not swing the verdict.
         */
        const val MIN_PROFILED_LEMMAS = 20

        /** Function words and high-frequency verbs that never make a text hard. */
        private val STOP = setOf(
            "the", "and", "but", "for", "not", "you", "all", "any", "can", "her",
            "was", "one", "our", "out", "day", "get", "has", "him", "his", "how",
            "man", "new", "now", "old", "see", "two", "way", "who", "boy", "did",
            "its", "let", "put", "say", "she", "too", "use", "that", "this", "with",
            "have", "from", "they", "will", "would", "there", "their", "what", "about",
            "which", "when", "make", "like", "time", "just", "know", "take", "into",
            "year", "your", "good", "some", "could", "them", "than", "then", "only",
            "come", "over", "also", "back", "after", "other", "many", "most", "such",
            "even", "because", "through", "between", "still", "being", "under",
            "while", "where", "before", "against", "during", "without", "within",
        )

        /**
         * Encodes a lemma list for the article row.
         *
         * Newline-joined rather than JSON: lemmas are dictionary headwords, which
         * may contain hyphens and apostrophes but never a newline, so a separator
         * that cannot occur in the data is enough — and it keeps this class on the
         * JVM side of the Android boundary, where it can be tested directly.
         */
        fun encode(lemmas: List<String>): String =
            lemmas.filter { it.isNotBlank() && '\n' !in it }.joinToString("\n")

        fun decode(encoded: String): List<String> =
            if (encoded.isBlank()) emptyList()
            else encoded.split('\n').filter { it.isNotBlank() }

        /** Band of a lemma, used to order the pre-study list hardest-first. */
        fun bandOf(dictionary: Lexicon, lemma: String): DifficultyBand =
            dictionary.lookup(lemma)?.band ?: DifficultyBand.Unknown
    }
}
