package com.engreader.app.nlp

import com.engreader.app.dict.DifficultyBand
import com.engreader.app.dict.Lexicon
import com.engreader.app.dict.PartOfSpeech
import com.engreader.app.dict.WordEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions for bugs found in review. Each test fails against the code as it was
 * before the fix, so the bug cannot come back unnoticed.
 */
class SentencesRegressionTest {

    @Test
    fun `a sentence may end in a word that is also an abbreviation`() {
        // "no" used to sit in ABBREVIATIONS, so the whole paragraph became one sentence.
        val out = Sentences.split("He said no. She left the room.")
        assertEquals(2, out.size)
        assertEquals("He said no.", out[0].text)
        assertEquals("She left the room.", out[1].text)
    }

    @Test
    fun `other ambiguous abbreviations no longer swallow the next sentence`() {
        assertEquals(2, Sentences.split("Tell us. Then go home.").size)
        assertEquals(2, Sentences.split("I am. You are not.").size)
        assertEquals(2, Sentences.split("It is the maximum. Nothing more.").size)
    }

    @Test
    fun `an ambiguous abbreviation still joins a continued phrase`() {
        // "no. 5" and "the US government" are not sentence boundaries.
        assertEquals(1, Sentences.split("See no. 5 for the details.").size)
        assertEquals(1, Sentences.split("The US government replied today.").size)
    }

    @Test
    fun `an ellipsis ends a sentence`() {
        // '\u2026' was in isTerminator but missing from the outer scan, so it could
        // never start a boundary.
        val out = Sentences.split("It was over\u2026 Nobody spoke again.")
        assertEquals(2, out.size)
        assertEquals("It was over\u2026", out[0].text)
    }

    @Test
    fun `the ordinary cases still hold`() {
        assertEquals(1, Sentences.split("Mr. Smith met Dr. Jones at 5 p.m. on Tuesday.").size)
        assertEquals(1, Sentences.split("The rate rose to 3.5 per cent.").size)
        assertEquals(1, Sentences.split("J. R. R. Tolkien wrote the books.").size)
        assertEquals(2, Sentences.split("\"I will not go.\" She left.").size)
    }

    @Test
    fun `etc and Inc can end a sentence`() {
        // Both sit in ABBREVIATIONS, which suppressed the boundary unconditionally, so
        // the next sentence was swallowed into this one.
        assertEquals(2, Sentences.split("Bring pens, etc. She had none.").size)
        assertEquals(2, Sentences.split("It was made by Acme Inc. Nobody complained.").size)
        // Still suppressed when the phrase continues.
        assertEquals(1, Sentences.split("See chapter 3, etc. for the details.").size)
    }

    @Test
    fun `a one-letter word ending a sentence is not read as an initial`() {
        // Any single capital suppressed the boundary, so "The grade is A." merged with
        // whatever followed.
        val out = Sentences.split("The grade is A. She was pleased.")
        assertEquals(2, out.size)
        assertEquals("The grade is A.", out[0].text)
        // A real initial is still suppressed: it follows a capitalised word.
        assertEquals(1, Sentences.split("J. R. R. Tolkien wrote the books.").size)
    }
}

class VocabularyGraderRegressionTest {

    /** Every word resolves to an entry with no frequency data at all. */
    private val ungraded = object : Lexicon {
        override fun lookup(raw: String) = WordEntry(
            lemma = raw, queried = raw, translation = "x", phonetic = "",
            definition = "", collins = 0, oxford = false, frq = 0, bnc = 0, tag = "",
        )
        override fun suggest(prefix: String, limit: Int) = emptyList<Pair<String, String>>()
        override fun glosses(translation: String) = emptyList<Pair<PartOfSpeech, String>>()
        override fun distractors(entry: WordEntry, pos: PartOfSpeech, count: Int) = emptyList<String>()
        override fun englishDistractors(entry: WordEntry, pos: PartOfSpeech, count: Int) = emptyList<String>()
        override fun firstGloss(translation: String) = "x"
        override fun definitions(headword: String) = emptyList<Pair<PartOfSpeech, String>>()
    }

    @Test
    fun `words with no frequency data are not counted as advanced`() {
        // Unknown is the last enum constant, so `band >= B2` was true for it and a
        // text of ungraded words graded 5 with a ratio of 1.0.
        val words = listOf(
            "quixotic", "zeppelin", "mnemonic", "kestrel", "obsidian", "labyrinth",
            "nimbus", "quarry", "sable", "tundra", "vellum", "wicket", "yarrow",
        )
        val result = VocabularyGrader(ungraded).grade(words.joinToString(" "))
        assertEquals(words.size, result.gradedWords)
        assertEquals(0f, result.advancedRatio, 0.0001f)
        assertEquals(1, result.grade)
    }

    @Test
    fun `the unknown band really does compare above B2`() {
        // The guard is needed because of this ordering, not in spite of it.
        assertTrue(DifficultyBand.Unknown > DifficultyBand.B2)
        assertTrue(DifficultyBand.C2 > DifficultyBand.B1)
    }
}
