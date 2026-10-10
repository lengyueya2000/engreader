package com.engreader.app.nlp

import com.engreader.app.dict.DifficultyBand
import com.engreader.app.dict.PartOfSpeechParser
import com.engreader.app.dict.FamilyLexicon
import com.engreader.app.dict.Lexicon
import com.engreader.app.dict.PartOfSpeech
import com.engreader.app.dict.WordEntry
import com.engreader.app.dict.WordForm

/**
 * In-memory stand-in for the bundled dictionary.
 *
 * Tests need the analyser's part-of-speech lookups to be deterministic and
 * inspectable, which a real 15 MB SQLite asset cannot offer on the JVM.
 */
class FakeLexicon(
    /** lemma -> (part of speech, gloss) */
    private val entries: Map<String, Pair<PartOfSpeech, String>> = emptyMap(),
    /** lemma -> English definition, for the in-English mode and the family panel. */
    private val englishDefinitions: Map<String, String> = emptyMap(),
    /** headwords sharing a stem, for the word-family tests. */
    private val family: Map<String, List<String>> = emptyMap(),
) : Lexicon, FamilyLexicon {

    override fun lookup(raw: String): WordEntry? {
        val key = Tokenizer.normalize(raw)
        val hit = entries[key] ?: return null
        return WordEntry(
            lemma = key,
            queried = raw,
            phonetic = "",
            translation = "${posMarker(hit.first)} ${hit.second}",
            definition = englishDefinitions[key].orEmpty(),
            collins = 2,
            oxford = false,
            frq = 12000,
            bnc = 12000,
            tag = "",
        )
    }

    override fun definitions(headword: String): List<Pair<PartOfSpeech, String>> {
        val key = Tokenizer.normalize(headword)
        val text = englishDefinitions[key] ?: return emptyList()
        return text.split("\\n", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { PartOfSpeechParser.parse(it) to PartOfSpeechParser.stripPrefix(it) }
    }

    /**
     * Mirrors the real query's `translation <> ''` filter: a headword the dictionary
     * cannot gloss is not offered, so the family panel never links to an empty sheet.
     */
    override fun wordsStartingWith(prefix: String, limit: Int): List<WordForm> =
        family.entries
            .filter { it.key.startsWith(prefix) }
            .flatMap { (_, words) -> words }
            .distinct()
            .filter { entries[it]?.second?.isNotBlank() == true }
            .take(limit)
            .map { WordForm(it, entries[it]?.second.orEmpty()) }

    override fun shortGloss(headword: String): String =
        entries[Tokenizer.normalize(headword)]?.second.orEmpty()

    override fun glosses(translation: String): List<Pair<PartOfSpeech, String>> =
        listOf(PartOfSpeech.Unknown to firstGloss(translation))

    override fun firstGloss(translation: String): String =
        translation.substringAfter(' ').trim()

    override fun suggest(prefix: String, limit: Int): List<Pair<String, String>> =
        entries.keys.filter { it.startsWith(prefix) }.take(limit)
            .map { it to entries.getValue(it).second }

    /** Deterministic stand-in distractors, so quiz tests do not depend on a corpus. */
    override fun distractors(entry: WordEntry, pos: PartOfSpeech, count: Int): List<String> =
        entries.entries
            .filter { it.value.first == pos && it.key != entry.lemma }
            .map { it.value.second }
            .distinct()
            .take(count)

    /**
     * Headwords, not glosses, mirroring the real dictionary's split: a reading-quiz
     * option has to be an English word because the blank is in an English sentence.
     */
    override fun englishDistractors(entry: WordEntry, pos: PartOfSpeech, count: Int): List<String> =
        entries.entries
            .filter { it.value.first == pos && it.key != entry.lemma }
            .map { it.key }
            .distinct()
            .take(count)

    private fun posMarker(pos: PartOfSpeech): String = when (pos) {
        PartOfSpeech.Noun -> "n."
        PartOfSpeech.Verb -> "v."
        PartOfSpeech.Adjective -> "a."
        PartOfSpeech.Adverb -> "adv."
        PartOfSpeech.Preposition -> "prep."
        PartOfSpeech.Conjunction -> "conj."
        PartOfSpeech.Pronoun -> "pron."
        PartOfSpeech.Determiner -> "art."
        else -> "?"
    }

    companion object {
        val band: DifficultyBand = DifficultyBand.B2
    }
}
