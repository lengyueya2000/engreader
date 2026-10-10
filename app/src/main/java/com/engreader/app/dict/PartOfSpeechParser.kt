package com.engreader.app.dict

import com.engreader.app.nlp.Tokenizer

/**
 * Maps ECDICT's leading part-of-speech abbreviation onto [PartOfSpeech].
 *
 * ECDICT glosses start with markers such as `n.`, `vt.`, `a.`, `adv.`, `prep.`
 * and sometimes nothing at all (proper nouns, phrases). When there is no marker we
 * fall back to suffix heuristics so the grammar panel still has something useful.
 */
object PartOfSpeechParser {

    /**
     * ECDICT's leading part-of-speech marker.
     *
     * The two-part abbreviations are listed before the one-part ones they start with:
     * the marker is followed by a literal dot, and with `aux` ahead of `aux\.v` the
     * engine matched `aux` + `.` and never reached the longer branch, so `aux.v` was
     * dead. The same ordering made `pl.` unreachable behind `pl`.
     */
    private val PREFIX = Regex(
        "^\\s*(aux\\.v|pl\\.|n|u|c|vt|vi|v|adj|ad|adv|a|prep|conj|pron|art|num|int|" +
            "aux|abbr|pl|pref|suf|comb)\\.\\s*",
        RegexOption.IGNORE_CASE,
    )

    fun parse(gloss: String): PartOfSpeech =
        when (PREFIX.find(gloss)?.groupValues?.get(1)?.lowercase()) {
            "n", "u", "c" -> PartOfSpeech.Noun
            "v", "vt", "vi", "aux", "aux.v" -> PartOfSpeech.Verb
            "a", "adj" -> PartOfSpeech.Adjective
            "ad", "adv" -> PartOfSpeech.Adverb
            "prep" -> PartOfSpeech.Preposition
            "conj" -> PartOfSpeech.Conjunction
            "pron" -> PartOfSpeech.Pronoun
            "art" -> PartOfSpeech.Determiner
            "num" -> PartOfSpeech.Numeral
            "int" -> PartOfSpeech.Interjection
            "abbr", "pl", "pref", "suf", "comb" -> PartOfSpeech.Unknown
            else -> PartOfSpeech.Unknown
        }

    fun stripPrefix(gloss: String): String = PREFIX.replaceFirst(gloss, "").trim()

    /**
     * Last-resort guess from the word's shape, used for words ECDICT does not tag
     * and for context highlighting in the sentence panel.
     *
     * Shape alone cannot decide: `morning`, `red` and `family` have the same endings
     * as `walking`, `walked` and `quickly`. The frequent misfires are listed in
     * [SHAPE_EXCEPTIONS] rather than guessed at, because a wrong part of speech here
     * changes what the grammar panel highlights.
     */
    fun guess(word: String): PartOfSpeech {
        val w = word.lowercase()
        return when {
            w in FUNCTION_WORDS -> FUNCTION_WORDS.getValue(w)
            w in SHAPE_EXCEPTIONS -> SHAPE_EXCEPTIONS.getValue(w)
            w.endsWith("ly") && w.length > 4 -> PartOfSpeech.Adverb
            // Hyphenated compounds are almost always adjectives in news prose:
            // "good-looking", "gentleman-like", "state-owned".
            '-' in w && COMPOUND_ADJECTIVE_SUFFIXES.any { w.endsWith(it) } -> PartOfSpeech.Adjective
            '-' in w -> PartOfSpeech.Adjective
            w.endsWith("ing") || w.endsWith("ed") || w.endsWith("ize") || w.endsWith("ise") ->
                PartOfSpeech.Verb
            w.endsWith("ous") || w.endsWith("ful") || w.endsWith("ive") || w.endsWith("able") ||
                w.endsWith("al") || w.endsWith("ic") || w.endsWith("like") -> PartOfSpeech.Adjective
            w.endsWith("tion") || w.endsWith("sion") || w.endsWith("ment") ||
                w.endsWith("ness") || w.endsWith("ity") || w.endsWith("ance") ||
                w.endsWith("ence") || w.endsWith("ship") || w.endsWith("ism") -> PartOfSpeech.Noun
            else -> PartOfSpeech.Unknown
        }
    }

    /**
     * Words whose ending points the wrong way.
     *
     * Every one of these is a common word whose shape matches a suffix rule but whose
     * part of speech is different — `morning` and `red` are not verbs, `family` is not
     * an adverb, `capital` is not an adjective in the sense a news reader meets it.
     * The list is short because only frequent words matter: a rare misfire costs
     * nothing, a frequent one colours the wrong word in every article.
     */
    private val SHAPE_EXCEPTIONS: Map<String, PartOfSpeech> = buildMap {
        listOf(
            "morning", "evening", "thing", "things", "king", "ring", "spring", "string",
            "building", "wedding", "meeting", "clothing", "ceiling", "darling", "offering",
            "red", "bed", "shed", "sled", "wed", "hundred", "sacred", "naked", "wicked",
            "hatred", "kindred", "speed", "breed", "indeed", "feed", "need", "seed", "weed",
        ).forEach { put(it, PartOfSpeech.Noun) }
        listOf(
            "family", "supply", "apply", "reply", "multiply", "assembly", "ally", "rally",
            "bully", "folly", "jelly", "tally", "july", "italy", "monopoly", "holy", "ugly",
            "silly", "melancholy", "anomaly", "panoply", "statistically", "italy's",
        ).forEach { put(it, PartOfSpeech.Noun) }
        listOf("early", "likely", "only", "daily", "weekly", "monthly", "yearly", "friendly",
            "lovely", "lonely", "costly", "deadly", "elderly", "silly", "holy", "ugly")
            .forEach { put(it, PartOfSpeech.Adjective) }
        listOf("capital", "general", "local", "total", "final", "legal", "medical",
            "national", "personal", "several", "animal", "hospital", "material")
            .forEach { put(it, PartOfSpeech.Noun) }
    }

    private val COMPOUND_ADJECTIVE_SUFFIXES = listOf(
        "looking", "like", "shaped", "sized", "owned", "based", "led", "made",
        "known", "wearing", "going", "born", "driven", "backed", "funded",
        "related", "wide", "long", "term", "hand", "class", "year",
    )

    /**
     * Closed-class words, each filed under the reading that matters most.
     *
     * The lists overlap on purpose — `each`, `some`, `all`, `both`, `that` and `no` are
     * determiners *and* prepositions *and* pronouns — and a plain `put` let the last
     * list win, so `each`/`some`/`all` came back as prepositions. [putIfAbsent] keeps
     * the first (most specific) reading instead.
     */
    private val FUNCTION_WORDS: Map<String, PartOfSpeech> = buildMap {
        listOf(
            "a", "an", "the", "this", "that", "these", "those", "some", "any", "each",
            "every", "both", "either", "neither", "no", "all", "such",
        ).forEach { putIfAbsent(it, PartOfSpeech.Determiner) }
        listOf(
            "i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "us",
            "them", "my", "your", "his", "its", "our", "their", "mine", "yours",
            "hers", "ours", "theirs", "myself", "yourself", "himself", "herself",
            "itself", "ourselves", "themselves", "who", "whom", "whose", "which",
            "what", "that", "these", "those", "someone", "anyone", "everyone",
            "nobody", "something", "anything", "everything", "nothing",
        ).forEach { putIfAbsent(it, PartOfSpeech.Pronoun) }
        listOf(
            "and", "or", "but", "nor", "for", "yet", "so", "because", "although",
            "though", "while", "whereas", "if", "unless", "until", "since", "as",
            "whether", "than", "that", "when", "whenever", "where", "wherever",
            "after", "before", "once", "both", "either", "neither",
        ).forEach { putIfAbsent(it, PartOfSpeech.Conjunction) }
        listOf(
            "in", "on", "at", "by", "for", "with", "about", "against", "between",
            "into", "through", "during", "before", "after", "above", "below", "to",
            "from", "up", "down", "out", "off", "over", "under", "again", "further",
            "then", "once", "here", "there", "all", "any", "both", "each", "few",
            "more", "most", "other", "some", "such", "no", "nor", "not", "only",
            "own", "same", "so", "than", "too", "very", "of", "per", "via",
            "despite", "during", "among", "within", "without", "toward", "towards",
            "upon", "across", "along", "around", "behind", "beyond", "beside",
        ).forEach { putIfAbsent(it, PartOfSpeech.Preposition) }
        listOf(
            "be", "am", "is", "are", "was", "were", "been", "being", "have", "has",
            "had", "do", "does", "did", "will", "would", "shall", "should", "can",
            "could", "may", "might", "must", "ought", "need", "dare", "let",
        ).forEach { putIfAbsent(it, PartOfSpeech.Verb) }
        listOf("not", "never", "always", "often", "sometimes", "usually", "rarely",
            "already", "still", "just", "even", "also", "however", "therefore",
            "thus", "hence", "moreover", "furthermore", "nevertheless", "instead",
            "otherwise", "meanwhile", "eventually", "recently", "currently")
            .forEach { putIfAbsent(it, PartOfSpeech.Adverb) }
    }

    /** A contraction read as the subject it hides plus the auxiliary it stands for. */
    data class Contraction(val subject: String?, val expansion: String)

    /**
     * Contractions, mapped to the subject they hide and the auxiliary they stand for.
     *
     * `Tokenizer` keeps the apostrophe inside the token, so `we're` arrives as one
     * word that is in no dictionary and matches no suffix rule — it used to come back
     * as Unknown, and a sentence opening with one reported no finite verb at all: the
     * breakdown panel showed an empty 主语 and 谓语 and quoted the first words of the
     * sentence as the backbone.
     *
     * Only pronoun, `there`, `that` and wh- stems are listed. `'s` after any other
     * noun is a possessive ("the minister's decision"), which must not read as `is`.
     * The negated forms carry no subject stem: their subject sits outside the word.
     */
    private val CONTRACTIONS: Map<String, Contraction> = buildMap {
        // stem -> suffix -> expansion of the auxiliary
        val stems = mapOf(
            "i" to mapOf("m" to "am", "ve" to "have", "ll" to "will", "d" to "would"),
            "you" to mapOf("re" to "are", "ve" to "have", "ll" to "will", "d" to "would"),
            "he" to mapOf("s" to "is", "ll" to "will", "d" to "would"),
            "she" to mapOf("s" to "is", "ll" to "will", "d" to "would"),
            "it" to mapOf("s" to "is", "ll" to "will", "d" to "would"),
            "we" to mapOf("re" to "are", "ve" to "have", "ll" to "will", "d" to "would"),
            "they" to mapOf("re" to "are", "ve" to "have", "ll" to "will", "d" to "would"),
            "that" to mapOf("s" to "is", "ll" to "will", "d" to "would"),
            "there" to mapOf("s" to "is", "re" to "are", "ll" to "will", "d" to "would"),
            "what" to mapOf("s" to "is", "re" to "are", "ll" to "will"),
            "who" to mapOf("s" to "is", "re" to "are", "ll" to "will"),
            "where" to mapOf("s" to "is"),
            "when" to mapOf("s" to "is"),
            "why" to mapOf("s" to "is"),
            "how" to mapOf("s" to "is"),
            "here" to mapOf("s" to "is"),
        )
        stems.forEach { (stem, suffixes) ->
            val display = if (stem == "i") "I" else stem
            suffixes.forEach { (suffix, expansion) ->
                val entry = Contraction(display, expansion)
                put("$stem'$suffix", entry)
                put("$stem\u2019$suffix", entry)
            }
        }
        put("let's", Contraction(null, "let us"))
        put("let\u2019s", Contraction(null, "let us"))

        // `won't` is `wo` + `n't`, `can't` is `ca` + `n't`, `ain't` is `ai` + `n't`.
        val negatives = mapOf(
            "is" to "is not", "are" to "are not", "was" to "was not", "were" to "were not",
            "do" to "do not", "does" to "does not", "did" to "did not",
            "ca" to "cannot", "could" to "could not", "wo" to "will not",
            "would" to "would not", "should" to "should not", "sha" to "shall not",
            "has" to "has not", "have" to "have not", "had" to "had not",
            "must" to "must not", "need" to "need not", "ai" to "is not",
        )
        negatives.forEach { (base, expansion) ->
            val entry = Contraction(null, expansion)
            put("${base}n't", entry)
            put("${base}n\u2019t", entry)
        }
        put("cannot", Contraction(null, "cannot"))
    }

    /** Reads [word] as a contraction, or null when it is not one we know. */
    fun contraction(word: String): Contraction? = CONTRACTIONS[word.trim().lowercase()]

    /** Part of speech for a contraction, or null when the word is not one we know. */
    fun contractionPos(word: String): PartOfSpeech? =
        if (contraction(word) != null) PartOfSpeech.Verb else null

    fun isContentWord(word: String): Boolean =
        guess(word) in setOf(
            PartOfSpeech.Noun, PartOfSpeech.Verb,
            PartOfSpeech.Adjective, PartOfSpeech.Adverb,
        ) && Tokenizer.normalize(word).isNotBlank()
}
