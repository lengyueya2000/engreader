package com.engreader.app.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule table behind the 连读/弱读 sheet.
 *
 * Every case here is a boundary a learner meets in the first few pages of a graded
 * reader, plus the two guards that keep the table from over-reporting: the stress
 * exception on flapping and the "one pattern per boundary" rule.
 */
class ConnectedSpeechTest {

    private fun tokensOf(sentence: String) = Paragraphs.split(sentence).single().tokens

    private fun explain(sentence: String) = ConnectedSpeech.explain(sentence, tokensOf(sentence))

    @Test
    fun `want to reduces to wanna across both words`() {
        val sentence = "I want to go home."
        val features = explain(sentence)
        assertEquals(1, features.size)
        val feature = features.single()
        assertEquals(Kind.Reduction, feature.kind)
        assertEquals("want to", feature.written)
        assertEquals("wanna", feature.sounds)
        // The offsets have to land on the words, not merely be present: the sheet
        // underlines this range in the sentence above the explanation.
        assertEquals("want to", sentence.substring(feature.start, feature.end))
    }

    @Test
    fun `the triple a lot of wins over of alone`() {
        val features = explain("There were a lot of people there.")
        val reduction = features.single { it.kind == Kind.Reduction }
        assertEquals("a lot of", reduction.written)
        assertEquals("a lotta", reduction.sounds)
    }

    @Test
    fun `did you assimilates to didja`() {
        val features = explain("Did you see it?")
        val feature = features.single { it.kind == Kind.Assimilation }
        assertEquals("Did you", feature.written)
        assertEquals("didja", feature.sounds)
    }

    @Test
    fun `this year assimilates with a sh`() {
        val features = explain("This year was hard.")
        val feature = features.single { it.kind == Kind.Assimilation }
        assertEquals("This year", feature.written)
        assertEquals("thish year", feature.sounds)
    }

    @Test
    fun `water flaps to wader`() {
        val features = explain("The water is cold.")
        val feature = features.single { it.kind == Kind.Flap }
        assertEquals("water", feature.written)
        assertEquals("wader", feature.sounds)
    }

    /**
     * The false-positive guard: /t/ only flaps when the stress is in front of it.
     * `hotel` and `return` carry the stress on the second syllable, so the /t/ stays.
     */
    @Test
    fun `hotel does not flap`() {
        val features = explain("The hotel is full.")
        assertTrue(features.none { it.kind == Kind.Flap })
    }

    @Test
    fun `next day holds the stop instead of releasing it`() {
        val features = explain("The next day was worse.")
        val feature = features.single { it.kind == Kind.Elision }
        assertEquals("next day", feature.written)
    }

    @Test
    fun `an apple links across the boundary`() {
        val features = explain("She ate an apple.")
        assertEquals(1, features.size)
        val feature = features.single()
        assertEquals(Kind.Linking, feature.kind)
        assertEquals("an apple", feature.written)
    }

    /**
     * A function word only reduces when it is not the stressed one. Sentence-final
     * `to` is the stressed reading — `Where are you going?` — `To.` — so it must not
     * be offered as a weak form.
     */
    @Test
    fun `a final to is not weak`() {
        val features = explain("Where are you going to")
        assertTrue(features.none { it.kind == Kind.WeakForm && it.written == "to" })
        assertTrue(features.any { it.kind == Kind.WeakForm && it.written == "you" })
    }

    /**
     * One pattern per boundary. `going to` is reported as the reduction it is; the
     * weak form of `to` and the unreleased `g` inside the same two words are dropped
     * rather than piling three notes onto one place in the sentence.
     */
    @Test
    fun `one pattern per boundary`() {
        val features = explain("Where are you going to")
        assertEquals(1, features.count { it.kind == Kind.Reduction })
        assertTrue(features.none { it.kind == Kind.Elision })
        assertEquals(1, features.count { it.kind == Kind.WeakForm && it.written == "you" })
    }

    @Test
    fun `a single token has nothing to explain`() {
        val sentence = "Go."
        val tokens = tokensOf(sentence)
        assertEquals(1, tokens.size)
        assertTrue(ConnectedSpeech.explain(sentence, tokens).isEmpty())
    }

    @Test
    fun `a sentence of plain content words yields nothing`() {
        assertTrue(explain("Cats sleep").isEmpty())
    }
}
