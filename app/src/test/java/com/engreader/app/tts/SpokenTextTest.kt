package com.engreader.app.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the voice is handed, as opposed to what the reader sees.
 *
 * Every expectation here was checked against the bundled British voice rather than
 * reasoned about: each case was synthesised, transcribed back with an ASR model, and
 * the written form compared with the spoken one. The failing shapes are the ones where
 * the two disagreed.
 */
class SpokenTextTest {

    @Test
    fun `a year in a date frame is spoken as a year`() {
        assertEquals(
            "It occurred to me, in eighteen thirty-seven, that something might be made out.",
            SpokenText.of("It occurred to me, in 1837, that something might be made out."),
        )
    }

    @Test
    fun `a four-digit quantity is left as a number`() {
        // Same shape as a year, no date frame: the voice reads this correctly already,
        // and turning it into "twelve thirty-four" would be the wrong fix.
        assertEquals(
            "There were 1234 people at the fair.",
            SpokenText.of("There were 1234 people at the fair."),
        )
    }

    @Test
    fun `round centuries take the hundred form`() {
        assertEquals("in eighteen hundred,", SpokenText.of("in 1800,"))
        assertEquals("in nineteen hundred,", SpokenText.of("in 1900,"))
    }

    @Test
    fun `years before the eleventh century use the ten form`() {
        assertEquals("in ten sixty-six,", SpokenText.of("in 1066,"))
    }

    @Test
    fun `the twenty-first century is read in pairs from 2010`() {
        assertEquals("in twenty ten,", SpokenText.of("in 2010,"))
        assertEquals("in twenty twenty-six,", SpokenText.of("in 2026,"))
    }

    @Test
    fun `the first decade of the twenty-first century is two thousand`() {
        assertEquals("in two thousand,", SpokenText.of("in 2000,"))
        assertEquals("in two thousand and five,", SpokenText.of("in 2005,"))
    }

    @Test
    fun `a year with a zero tens digit gets oh`() {
        assertEquals("in eighteen oh five,", SpokenText.of("in 1805,"))
    }

    @Test
    fun `a span of years is left alone`() {
        // The voice already reads the dash between two numbers as "to", and spelling
        // only one end would leave the two halves of the span disagreeing.
        assertEquals("from 1837-1901 he reigned", SpokenText.of("from 1837-1901 he reigned"))
    }

    @Test
    fun `a chapter numeral is a cardinal`() {
        assertEquals("CHAPTER SIX.", SpokenText.of("CHAPTER VI."))
        assertEquals("CHAPTER ONE.", SpokenText.of("CHAPTER I."))
        assertEquals("Part two of it", SpokenText.of("Part II of it"))
        assertEquals("chapter ten was read", SpokenText.of("chapter X was read"))
    }

    @Test
    fun `a heading set in capitals keeps its case`() {
        // "CHAPTER SIX." and "chapter six." are not the same utterance: the voice drops
        // its pitch range on the mixed-case one, which is audible in a heading.
        assertEquals("CHAPTER SIX.", SpokenText.of("CHAPTER VI."))
        assertEquals("chapter six.", SpokenText.of("chapter VI."))
    }

    @Test
    fun `a lowercase roman numeral is left alone`() {
        // Prose that uses `vi.` for six is rare, and the same three letters appear in
        // ordinary lowercase words. Uppercase is the only shape a numeral reliably has.
        assertEquals("chapter vi.", SpokenText.of("chapter vi."))
    }

    @Test
    fun `a regnal numeral is an ordinal`() {
        assertEquals("Henry the eighth was king.", SpokenText.of("Henry VIII was king."))
        assertEquals("Elizabeth the second reigned.", SpokenText.of("Elizabeth II reigned."))
    }

    @Test
    fun `the pronoun i is left alone`() {
        assertEquals(
            "the part I liked best was the end",
            SpokenText.of("the part I liked best was the end"),
        )
    }

    @Test
    fun `a roman-looking word is left alone`() {
        assertEquals("MIX the DIV with it", SpokenText.of("MIX the DIV with it"))
    }

    @Test
    fun `a dotted meridiem with a space is spelled as letters`() {
        assertEquals(
            "Left Munich at 8:35 p m, on the first of May.",
            SpokenText.of("Left Munich at 8:35 P. M., on the first of May."),
        )
        assertEquals("at 6:46 a m tomorrow", SpokenText.of("at 6:46 A. M. tomorrow"))
    }

    @Test
    fun `meridiem spellings the voice already reads are left alone`() {
        // Measured on the bundled voice, these all transcribe as "five pm"; only the
        // dotted-and-spaced form comes out as "five p, M".
        for (text in listOf(
            "He arrived at 5 p.m. yesterday.",
            "He arrived at 5 P.M. yesterday.",
            "He arrived at 5 pm yesterday.",
            "He arrived at 5 PM yesterday.",
            "I am here at 5 pm",
        )) {
            assertEquals(text, SpokenText.of(text))
        }
    }

    @Test
    fun `an illustration note is dropped`() {
        assertEquals(
            "This was invitation enough.",
            SpokenText.of("[Illustration: \u201CHe came down to see the place\u201D] This was invitation enough."),
        )
    }

    @Test
    fun `a nested note is dropped whole`() {
        assertEquals(
            "This was invitation enough.",
            SpokenText.of("[Illustration: \u201CHe came down\u201D [Copyright 1894 by George Allen.]] This was invitation enough."),
        )
    }

    @Test
    fun `a note that is the whole sentence is spoken as nothing`() {
        // Gutenberg puts the note on a line of its own, and the sentence splitter hands
        // it over as its own sentence. Measured on the bundled voice the note is 7.2 s of
        // audio; the reader is meant to skip it, so the rewrite has to be able to answer
        // with nothing at all. An earlier version fell back to the original text here,
        // which put the whole note back into the reading.
        assertEquals("", SpokenText.of("[Illustration: \u201CHe came down to see the place\u201D [Copyright 1894 by George Allen.]]"))
        assertEquals("", SpokenText.of("[Illustration]"))
        assertEquals("", SpokenText.of("[Footnote: see above.]"))
    }

    @Test
    fun `a citation marker is dropped`() {
        assertEquals("She waited and then left.", SpokenText.of("She waited [1] and then left."))
    }

    @Test
    fun `a square bracket that is part of the prose survives`() {
        assertEquals("the [sic] was in the original", SpokenText.of("the [sic] was in the original"))
    }

    @Test
    fun `asterisk emphasis is removed but its words are kept`() {
        assertEquals("he did admire her", SpokenText.of("he *did* admire her"))
        assertEquals("he did admire her", SpokenText.of("he **did** admire her"))
    }

    @Test
    fun `underscore emphasis is removed but its words are kept`() {
        assertEquals("he did admire her", SpokenText.of("he _did_ admire her"))
    }

    @Test
    fun `an underscore inside a word survives`() {
        assertEquals("the snake_case_name is here", SpokenText.of("the snake_case_name is here"))
    }

    @Test
    fun `contractions and abbreviations are left as written`() {
        // All of these are already spoken correctly, so rewriting them would only add a
        // way to be wrong. Measured against the bundled voice, "Mr." and "mister" are
        // within 20 ms of each other, and "It's" is shorter than "It is".
        for (text in listOf(
            "It's a matter of no importance.",
            "I don't know what to say.",
            "Mr. Bennet was among the earliest.",
            "Mrs. Hurst and Miss Bingley were there.",
            "Dr. Hooker knew of my work.",
            "Bring pens, etc.",
            "It was 5 p.m. on Tuesday.",
        )) {
            assertEquals(text, SpokenText.of(text))
        }
    }

    @Test
    fun `a blank input is returned unchanged`() {
        assertEquals("", SpokenText.of(""))
        assertEquals("   ", SpokenText.of("   "))
    }

    @Test
    fun `spacing left by a dropped note is tidied`() {
        assertEquals(
            "He came down. This was enough.",
            SpokenText.of("He came down. [Footnote: see above.] This was enough."),
        )
    }

    @Test
    fun `a real paragraph of the seed corpus survives the rewrite intact`() {
        val text = "You and the girls may go--or you may send them by themselves, " +
            "which perhaps will be still better; for as you are as handsome as any of them, " +
            "Mr. Bennet, you might have a fine time."
        assertEquals(text, SpokenText.of(text))
    }

    @Test
    fun `no rewrite invents or loses words`() {
        // The voice must never be handed a sentence that says something the page does
        // not. Every case is a rewrite of markup or of a numeral, so the alphabetic
        // words that come out have to be the ones that went in, plus the spelled
        // numbers the page implies.
        val text = "In 1837, CHAPTER VI. [Illustration: a plate] he _did_ admire her."
        val out = SpokenText.of(text)
        assertTrue(out, out.contains("eighteen thirty-seven"))
        assertTrue(out, out.contains("SIX"))
        assertTrue(out, out.contains("did"))
        assertTrue(out, !out.contains("1837"))
        assertTrue(out, !out.contains("VI"))
        assertTrue(out, !out.contains("Illustration"))
    }
}
