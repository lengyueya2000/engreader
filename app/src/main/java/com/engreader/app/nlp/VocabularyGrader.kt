package com.engreader.app.nlp

import com.engreader.app.dict.DifficultyBand
import com.engreader.app.dict.Lexicon

/**
 * Grades a passage by how much of it sits above a B1 vocabulary.
 *
 * Corpus-frequency banding is the signal a learner actually feels when they open a
 * text, and it separates authors that sentence length cannot: Austen's long
 * sentences are built from common words, Darwin's from technical ones.
 */
class VocabularyGrader(private val dictionary: Lexicon) {

    /** Returns a 1..5 grade and the share of words at B2 or above. */
    fun grade(text: String): Result {
        val words = Tokenizer.words(text).map { Tokenizer.normalize(it) }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return Result(3, 0f, 0)

        var advanced = 0
        var graded = 0
        val seen = HashSet<String>(words.size)
        for (word in words) {
            if (!seen.add(word)) continue
            val entry = dictionary.lookup(word) ?: continue
            graded++
            // `Unknown` is the last enum constant, so a bare `>= B2` comparison would
            // count an ungraded word as the hardest kind. It means "no frequency data",
            // not "rare": counting it inflates the ratio on exactly the texts whose
            // vocabulary the corpus cannot describe. `isStudyWorthy` guards the same
            // way for the same reason.
            if (entry.band >= DifficultyBand.B2 && entry.band != DifficultyBand.Unknown) advanced++
        }
        // Ungraded tokens are proper nouns and coinages; ignoring them avoids
        // inflating the ratio on a text full of names.
        if (graded < 10) return Result(3, 0f, graded)

        val ratio = advanced.toFloat() / graded
        val grade = when {
            ratio < 0.04f -> 1
            ratio < 0.09f -> 2
            ratio < 0.16f -> 3
            ratio < 0.24f -> 4
            else -> 5
        }
        return Result(grade, ratio, graded)
    }

    data class Result(val grade: Int, val advancedRatio: Float, val gradedWords: Int)
}
