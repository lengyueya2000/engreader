package com.engreader.app.dict

/**
 * One related form of a headword.
 *
 * [note] is a short Chinese gloss when the dictionary has one, and a grammatical
 * label ("过去式") when it does not — a family list is read to find out which form
 * means what, so a bare spelling would not be enough.
 */
data class WordForm(val word: String, val note: String)

/**
 * The slice of dictionary access [WordFamily] needs.
 *
 * Declared separately from [Lexicon] so the family logic is testable without a
 * SQLite asset, and so implementations that have no use for a prefix query are not
 * forced to grow one.
 */
interface FamilyLexicon {
    /** Headwords starting with [prefix], each with a short gloss. */
    fun wordsStartingWith(prefix: String, limit: Int): List<WordForm>

    /** The gloss to show for one headword, or "" when it has none. */
    fun shortGloss(headword: String): String
}

/**
 * Groups the forms of a word a learner keeps meeting separately.
 *
 * `derive`, `derived`, `deriving`, `derivation` and `derivative` are five look-ups
 * and five wordbook entries as far as the reader is concerned, but one word to
 * learn. Two sources are combined: ECDICT's `exchange` column, which lists a
 * headword's own inflections, and a stem search, which catches the derivations that
 * are headwords in their own right and therefore appear in nobody's `exchange`.
 */
object WordFamily {

    /** Codes used by ECDICT's `exchange` column, in the order they are shown. */
    private val CODES = linkedMapOf(
        "p" to "过去式",
        "d" to "过去分词",
        "i" to "现在分词",
        "3" to "第三人称单数",
        "s" to "复数",
        "r" to "比较级",
        "t" to "最高级",
    )

    /** `0:` marks the lemma a form belongs to and `1:` its forms; neither describes this word. */
    private val SKIP = setOf("0", "1")

    /** How many stem-derived relatives to add on top of the inflections. */
    private const val MAX_DERIVED = 6

    /**
     * Derivational endings a stem search is allowed to have matched.
     *
     * A prefix query for `govern` also returns `governess`, which is a different word
     * to a learner even though it shares an etymology. Requiring the remainder to
     * begin with an ending English actually derives with keeps those out.
     *
     * Plurals are deliberately absent: a regular plural is already in the entry's
     * own `exchange` column, and including `s`/`es` here would let `ess` through.
     */
    private val DERIVATIONAL_SUFFIXES = listOf(
        "ation", "ition", "ative", "able", "ible", "ance", "ence", "ment", "ness",
        "ion", "ive", "ing", "ity", "ous", "ful", "ate", "acy", "ism", "ist",
        "ed", "al", "ly", "er", "or", "y",
    )

    /**
     * The endings [stemOf] is allowed to strip — a strict subset of
     * [DERIVATIONAL_SUFFIXES].
     *
     * Stripping is only safe when the remainder is a fragment rather than a word.
     * `er`, `or`, `y` and `al` are the four that break that: `number` minus `er` is
     * `numb`, so a prefix search for `number` returned `numbed`, `numbly` and
     * `numbness`; `final` minus `al` is `fin`, which listed `fined`. Nothing is lost
     * by leaving them out, because a word genuinely built on a short stem is still
     * found — `teach` is its own stem, and `teacher` matches on `er` from the accept
     * list above.
     */
    private val STRIPPABLE = listOf(
        "ation", "ition", "ative", "able", "ible", "ance", "ence", "ment", "ness",
        "ion", "ive", "ing", "ity", "ous", "ful", "ate", "acy", "ism", "ist",
        "ed", "ly",
    )

    /**
     * Splits an `exchange` blob into `(form, code)` pairs, in [CODES] order.
     *
     * A spelling stored under two codes (ECDICT files `derived` as both the past
     * tense and the past participle of `derive`) is reported once, under the first
     * code that names it: the panel lists spellings, not slots.
     */
    fun parse(exchange: String): List<WordForm> {
        if (exchange.isBlank()) return emptyList()
        val byCode = HashMap<String, String>()
        exchange.split('/').forEach { part ->
            val code = part.substringBefore(':', "")
            val form = part.substringAfter(':', "").trim()
            if (code.isEmpty() || form.isEmpty()) return@forEach
            if (code in SKIP || code !in CODES) return@forEach
            byCode.putIfAbsent(code, form)
        }
        val seen = HashSet<String>()
        return CODES.keys.mapNotNull { code ->
            val form = byCode[code] ?: return@mapNotNull null
            if (!seen.add(form.lowercase())) null else WordForm(form, CODES.getValue(code))
        }
    }

    /**
     * The stem to search the dictionary by, or null when the word is too short for a
     * prefix search to mean anything.
     *
     * `derive` → `deriv`, which finds `derived`, `deriving`, `derivation` and
     * `derivative`; `procrastination` → `procrastin`, which finds the whole verb
     * family. A short word would match a large slice of the dictionary, so those
     * fall back to the `exchange` column alone.
     */
    fun stemOf(lemma: String): String? {
        val w = lemma.lowercase().filter { it.isLetter() }
        if (w.length < 6) return null
        // Strip one derivational suffix, by its own length — `ment` and `ness` are
        // four characters where `ing` is three, and a fixed cut leaves the tail of
        // the longer ones in the stem (`governm` for `government`). The guard keeps a
        // short word from being stripped to nothing: `derive` ends in `ive`, but
        // cutting there would leave three letters and match half the dictionary.
        val suffix = STRIPPABLE.firstOrNull { w.length - it.length >= 4 && w.endsWith(it) }
        val cut = when {
            suffix != null -> w.length - suffix.length
            // A silent `e` is what the derivational suffixes attach to (`derive` →
            // `derivation`), so it goes too; without this the prefix search for
            // `derive` would miss every derived noun.
            w.endsWith("e") -> w.length - 1
            // No suffix at all: the word is its own stem, and the prefix search finds
            // the family that was built on it (`govern` → governance, governor).
            else -> w.length
        }
        return w.substring(0, cut).takeIf { it.length >= 4 }
    }

    /**
     * Related forms worth showing for [entry], given what [lexicon] can resolve.
     *
     * The entry's own inflections come first, then other headwords sharing its stem.
     * Every candidate is resolved through the dictionary before it is offered, so
     * the panel never presents a link that opens an empty sheet.
     */
    fun related(lexicon: FamilyLexicon, entry: WordEntry): List<WordForm> {
        val out = LinkedHashMap<String, WordForm>()
        parse(entry.exchange).forEach { form ->
            val gloss = lexicon.shortGloss(form.word)
            out[form.word.lowercase()] = WordForm(form.word, gloss.ifBlank { form.note })
        }

        val stem = stemOf(entry.lemma)
        if (stem != null) {
            lexicon.wordsStartingWith(stem, limit = 40)
                // The dictionary holds multi-word entries (`number one`, `a bit`), and
                // they are never a form of the word being read.
                .filter { it.word.all { c -> c.isLetter() } }
                .filter { it.word.length > stem.length }
                .filter { it.word.length <= entry.lemma.length + 8 }
                .filter { !it.word.equals(entry.lemma, ignoreCase = true) }
                .filter { it.word.lowercase() !in out }
                .filter { sameStem(stem, it.word) }
                .take(MAX_DERIVED)
                .forEach { out[it.word.lowercase()] = it }
        }
        return out.values.toList()
    }

    /**
     * True when [word] is built on [stem] rather than merely sharing its first
     * letters.
     *
     * A prefix query for `deriv` also returns `derivation`, which is wanted, but one
     * for `govern` returns `governess`, which is a different word to a learner even
     * though it is etymologically related. Requiring the remainder to begin with an
     * ending English actually derives with keeps the coincidences out — `ess` is not
     * in the list, `ance`, `ment` and `or` are.
     */
    private fun sameStem(stem: String, word: String): Boolean {
        val rest = word.lowercase().removePrefix(stem)
        if (rest.isEmpty()) return false
        return DERIVATIONAL_SUFFIXES.any { rest.startsWith(it) }
    }
}
