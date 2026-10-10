package com.engreader.app.dict

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ECDICT part-of-speech marker, and the shape guess that stands in for it.
 *
 * Both feed the grammar panel's highlighting and the quiz's option pool, so a wrong
 * answer here colours the wrong word in every article.
 */
class PartOfSpeechParserTest {

    @Test
    fun `a two-part marker is not truncated to its first part`() {
        // `aux` sat ahead of `aux\.v` in the alternation, so the engine matched `aux`
        // plus the dot and never reached the longer branch: `aux.v` was dead, and so
        // was `pl.` behind `pl`.
        assertEquals(PartOfSpeech.Verb, PartOfSpeechParser.parse("aux.v 能够"))
        assertEquals(PartOfSpeech.Unknown, PartOfSpeechParser.parse("pl. 复数"))
        assertEquals("v 能够", PartOfSpeechParser.stripPrefix("aux.v 能够"))
        assertEquals("复数", PartOfSpeechParser.stripPrefix("pl. 复数"))
    }

    @Test
    fun `the ordinary markers still map`() {
        assertEquals(PartOfSpeech.Noun, PartOfSpeechParser.parse("n. 计划"))
        assertEquals(PartOfSpeech.Verb, PartOfSpeechParser.parse("vt. 宣布"))
        assertEquals(PartOfSpeech.Adjective, PartOfSpeechParser.parse("a. 迟的"))
        assertEquals(PartOfSpeech.Adverb, PartOfSpeechParser.parse("adv. 很少"))
        assertEquals(PartOfSpeech.Unknown, PartOfSpeechParser.parse("计划"))
    }

    @Test
    fun `a shape that points the wrong way is listed rather than guessed`() {
        // Every one of these ends like a different part of speech than it is.
        assertEquals(PartOfSpeech.Noun, PartOfSpeechParser.guess("morning"))
        assertEquals(PartOfSpeech.Noun, PartOfSpeechParser.guess("red"))
        assertEquals(PartOfSpeech.Noun, PartOfSpeechParser.guess("family"))
        assertEquals(PartOfSpeech.Noun, PartOfSpeechParser.guess("capital"))
        assertEquals(PartOfSpeech.Adjective, PartOfSpeechParser.guess("likely"))
    }

    @Test
    fun `a determiner is not overwritten by a later list`() {
        // `each`, `some` and `all` are determiners, prepositions and pronouns, and a
        // plain `put` let whichever list came last win.
        assertEquals(PartOfSpeech.Determiner, PartOfSpeechParser.guess("each"))
        assertEquals(PartOfSpeech.Determiner, PartOfSpeechParser.guess("some"))
        assertEquals(PartOfSpeech.Determiner, PartOfSpeechParser.guess("all"))
        assertEquals(PartOfSpeech.Determiner, PartOfSpeechParser.guess("both"))
    }
}

/**
 * The `LIKE` prefix a dictionary search is built from.
 *
 * `%` and `_` are wildcards in a pattern: unescaped, a search for `a_b` matched `axb`
 * and a prefix of `%` matched the whole dictionary.
 */
class DictionaryLikePrefixTest {

    @Test
    fun `the like wildcards are escaped`() {
        assertEquals("a\\_b", Dictionary.escapeLikePrefix("a_b"))
        assertEquals("50\\%", Dictionary.escapeLikePrefix("50%"))
        assertEquals("a\\\\b", Dictionary.escapeLikePrefix("a\\b"))
    }

    @Test
    fun `an ordinary prefix is unchanged`() {
        assertEquals("govern", Dictionary.escapeLikePrefix("govern"))
        assertEquals("", Dictionary.escapeLikePrefix(""))
    }
}
