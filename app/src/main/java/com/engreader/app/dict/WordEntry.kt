package com.engreader.app.dict

/** One dictionary entry, resolved to its lemma. */
data class WordEntry(
    val lemma: String,
    /** The surface form the user actually tapped, e.g. "sacrificed". */
    val queried: String,
    val phonetic: String,
    /** Chinese gloss, one line per part of speech. */
    val translation: String,
    /** English definition, used for the in-English mode. */
    val definition: String,
    val collins: Int,
    val oxford: Boolean,
    val frq: Int,
    val bnc: Int,
    val tag: String,
    /**
     * ECDICT's packed inflection list, e.g. `d:derived/3:derives/i:deriving/p:derived`.
     * Kept raw because [WordFamily] is the only thing that knows how to read it.
     */
    val exchange: String = "",
    /**
     * Readings of the same spelling that ECDICT files under a different headword.
     *
     * `found` is both 建立 and the past tense of `find`; the entry shown is the more
     * frequent reading, and this carries the other one so the sheet can list both
     * instead of silently picking. Each pair is `(headword, gloss lines)`.
     */
    val otherReadings: List<Pair<String, List<String>>> = emptyList(),
) {
    val isInflected: Boolean get() = !lemma.equals(queried, ignoreCase = true)

    /**
     * Rough CEFR-ish band derived from corpus frequency and Collins star rating.
     * Used to colour vocabulary difficulty and to decide what is worth quizzing.
     */
    val band: DifficultyBand
        get() {
            val rank = listOf(frq, bnc).filter { it > 0 }.minOrNull() ?: Int.MAX_VALUE
            return when {
                collins >= 5 || rank <= 1000 -> DifficultyBand.A1A2
                collins >= 4 || rank <= 3000 -> DifficultyBand.B1
                collins >= 2 || rank <= 9000 -> DifficultyBand.B2
                collins >= 1 || rank <= 20000 -> DifficultyBand.C1
                rank == Int.MAX_VALUE && collins == 0 -> DifficultyBand.Unknown
                else -> DifficultyBand.C2
            }
        }

    /** True when the word is a plausible "worth learning" target for a learner. */
    val isStudyWorthy: Boolean
        get() = band >= DifficultyBand.B2 && band != DifficultyBand.Unknown

    /**
     * English definitions as `(part of speech, definition)` rows.
     *
     * ECDICT writes one definition per line, each prefixed with the same `v.` /
     * `n.` markers the Chinese side uses, so the same parser strips them. The lines
     * are also used by the in-English mode, which is why they are exposed rather
     * than flattened to a single string.
     */
    val definitionLines: List<Pair<PartOfSpeech, String>>
        get() {
            if (definition.isBlank()) return emptyList()
            return definition.split("\\n", "\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                // ECDICT sometimes repeats a sense verbatim ("v. make easier" twice);
                // the sheet would then look like it had a rendering bug.
                .distinct()
                .map { line ->
                    PartOfSpeechParser.parse(line) to PartOfSpeechParser.stripPrefix(line)
                }
                .filter { (_, text) -> text.isNotBlank() }
        }

    /** True when the entry can be shown in English at all. */
    val hasEnglishDefinition: Boolean get() = definitionLines.isNotEmpty()
}

enum class DifficultyBand(val label: String) {
    A1A2("基础"),
    B1("进阶"),
    B2("中高"),
    C1("高阶"),
    C2("难词"),
    Unknown("未分级");

    val ordinalValue: Int get() = ordinal
}

/** Grammar role guessed for a tapped word, used by the sentence breakdown panel. */
enum class PartOfSpeech(val label: String) {
    Noun("名词"),
    Verb("动词"),
    Adjective("形容词"),
    Adverb("副词"),
    Preposition("介词"),
    Conjunction("连词"),
    Pronoun("代词"),
    Determiner("限定词"),
    Numeral("数词"),
    Interjection("感叹词"),
    Phrase("短语"),
    Unknown("未标注"),
}
