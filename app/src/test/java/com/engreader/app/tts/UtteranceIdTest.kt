package com.engreader.app.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The read-aloud queue's utterance ids.
 *
 * The ids are the only thing joining the sentence that is being spoken to the callback
 * that says it has finished. A suffix that one end writes and the other does not strip
 * is not a loud failure: the queue simply stops at the end of the first pair and the
 * reader hears one sentence where the article has hundreds. So the round trip is worth
 * pinning down on its own.
 */
class UtteranceIdTest {

    @Test
    fun `the English id round-trips`() {
        assertEquals(SpokenUtterance(0, SpeechRole.Reading), UtteranceId.parse(UtteranceId.english(0)))
        assertEquals(SpokenUtterance(7, SpeechRole.Reading), UtteranceId.parse(UtteranceId.english(7)))
        assertEquals(SpokenUtterance(1234, SpeechRole.Reading), UtteranceId.parse(UtteranceId.english(1234)))
    }

    @Test
    fun `the Chinese id round-trips and is told apart from the English one`() {
        // The whole point of the suffix: both ids name the same sentence, and the
        // queue does something different for each.
        assertEquals(
            SpokenUtterance(3, SpeechRole.Translation),
            UtteranceId.parse(UtteranceId.translation(3)),
        )
        assertEquals(
            SpokenUtterance(3, SpeechRole.Reading),
            UtteranceId.parse(UtteranceId.english(3)),
        )
        assertEquals(UtteranceId.english(3) + ":zh", UtteranceId.translation(3))
    }

    @Test
    fun `a Chinese id is not read as an English one`() {
        // The failure this guards: `5:zh` does not parse as a number, so a parser that
        // looked for the bare form first would drop every Chinese utterance — and with
        // it the advance to the next sentence.
        val parsed = UtteranceId.parse(UtteranceId.translation(5))
        assertEquals(SpeechRole.Translation, parsed?.role)
        assertEquals(5, parsed?.index)
    }

    @Test
    fun `ids that are not ours are rejected rather than guessed at`() {
        // The preview and the word look-up put their own ids on the same event stream.
        // Reading one of those as a sentence index would skip the reader forward.
        listOf("", "preview", "word:hello", "sentence:", "sentence:abc", "sentence:1:en", ":zh")
            .forEach { assertNull("$it should not parse", UtteranceId.parse(it)) }
    }

    @Test
    fun `the index is read from the whole id and not from a prefix of it`() {
        // `12` must not be read out of `120`, and the suffix must not be mistaken for
        // part of the number.
        assertEquals(12, UtteranceId.parse("sentence:12")?.index)
        assertEquals(120, UtteranceId.parse("sentence:120")?.index)
        assertEquals(120, UtteranceId.parse("sentence:120:zh")?.index)
    }

    @Test
    fun `a request number is stripped before the sentence is read`() {
        // The queue only cares which sentence was heard; the request number exists so two
        // outstanding requests for the same sentence can be told apart, not so the
        // sentence index changes.
        assertEquals(SpokenUtterance(7, SpeechRole.Reading), UtteranceId.parse(UtteranceId.english(7, 3)))
        assertEquals(
            SpokenUtterance(7, SpeechRole.Translation),
            UtteranceId.parse(UtteranceId.translation(7, 3)),
        )
        // `120` must still not be read as `12`, with a request number present or not.
        assertEquals(120, UtteranceId.parse(UtteranceId.english(120, 9))?.index)
    }

    @Test
    fun `the request number round-trips and tells two requests apart`() {
        // This is what stops an abandoned request from being reported as the sentence
        // having been heard: the two ids differ, so the queue can reject the old one.
        val first = UtteranceId.english(7, 1)
        val second = UtteranceId.english(7, 2)
        assertNotEquals(first, second)
        assertEquals(1L, UtteranceId.request(first))
        assertEquals(2L, UtteranceId.request(second))
        assertEquals(1L, UtteranceId.request(UtteranceId.translation(7, 1)))
    }

    @Test
    fun `an id without a request number carries none`() {
        // Ids built by callers that do not need the distinction keep working unchanged.
        assertNull(UtteranceId.request(UtteranceId.english(7)))
        assertNull(UtteranceId.request(UtteranceId.translation(7)))
        assertNull(UtteranceId.request("sentence:abc"))
    }

    @Test
    fun `a request number does not make a foreign id parse`() {
        listOf("preview@1", "word:hello@2", "sentence:@1", "sentence:abc@1")
            .forEach { assertNull("$it should not parse", UtteranceId.parse(it)) }
    }
}
