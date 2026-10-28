package com.engreader.app.nlp

import com.engreader.app.dict.Lexicon
import com.engreader.app.dict.PartOfSpeech
import com.engreader.app.dict.WordEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two `QuizBuilder` entry points the review found were only reachable through
 * `GrammarAnalyzerTest`'s fixture: a negative question count, and an option pool the
 * dictionary can fill with duplicates of the answer.
 *
 * Kept in their own file rather than beside [QuizBuilderTest] because that class lives in
 * `GrammarAnalyzerTest.kt`, which another task owns.
 */
class QuizBuilderGuardTest {

    /**
     * A lexicon whose only entry is the answer, and whose distractor list repeats it —
     * the shape a dictionary produces when it answers with the headword itself.
     */
    private class RepeatedDistractorLexicon : Lexicon {
        private val entry = WordEntry(
            lemma = "derived", queried = "derived", translation = "n. 派生的", phonetic = "",
            definition = "", collins = 2, oxford = false, frq = 5000, bnc = 0, tag = "",
        )

        override fun lookup(raw: String): WordEntry? =
            entry.takeIf { raw.equals(it.lemma, ignoreCase = true) }

        override fun suggest(prefix: String, limit: Int) = emptyList<Pair<String, String>>()
        override fun glosses(translation: String) = emptyList<Pair<PartOfSpeech, String>>()
        override fun distractors(entry: WordEntry, pos: PartOfSpeech, count: Int) =
            listOf(entry.lemma, entry.lemma)

        override fun englishDistractors(entry: WordEntry, pos: PartOfSpeech, count: Int) =
            listOf(entry.lemma, entry.lemma, entry.lemma)

        override fun firstGloss(translation: String) = "派生的"
        override fun definitions(headword: String) = emptyList<Pair<PartOfSpeech, String>>()
    }

    private val text = "The plan was derived from earlier work and then derived again."

    @Test
    fun `a negative question count returns nothing instead of throwing`() {
        // `take(-1)` threw `IllegalArgumentException: Requested element count -1 is less
        // than zero` out of a UI action.
        val builder = QuizBuilder(RepeatedDistractorLexicon())
        assertTrue(builder.build(text, maxQuestions = -1).isEmpty())
        assertTrue(builder.build(text, maxQuestions = 0).isEmpty())
    }

    /**
     * The old loop compared the de-duplicated `picked` against the raw `pool.size`, so a
     * pool of three copies of one word could never reach three picks and the loop spun
     * forever — the quiz sheet hung. The pool is now de-duplicated and answer-filtered
     * before the size test, which is what this pins: the call returns, and it does not
     * build a question out of a pool that cannot fill one.
     */
    @Test
    fun `a distractor pool of duplicates returns instead of spinning`() {
        val questions = QuizBuilder(RepeatedDistractorLexicon()).build(text, maxQuestions = 3)
        assertTrue("a one-word pool must not yield a question: $questions", questions.isEmpty())
    }
}