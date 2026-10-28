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
    fun `two requests for one sentence are two ids`() {
        // The deterministic dropped sentence: sentence N was playing under one request, the
        // reader tapped sentence N to hear it again, and the second request carried the same
        // id — so the first request's completion was taken for the second's and the queue
        // advanced to N+1 instead of replaying N. The serial is what tells the two apart.
        val first = UtteranceId.english(3, 7)
        val second = UtteranceId.english(3, 8)
        assertNotEquals(first, second)
        assertEquals(SpokenUtterance(3, SpeechRole.Reading, 7), UtteranceId.parse(first))
        assertEquals(SpokenUtterance(3, SpeechRole.Reading, 8), UtteranceId.parse(second))
    }

    @Test
    fun `the Chinese id carries the serial too`() {
        assertEquals("sentence:3#7:zh", UtteranceId.translation(3, 7))
        assertEquals(
            SpokenUtterance(3, SpeechRole.Translation, 7),
            UtteranceId.parse(UtteranceId.translation(3, 7)),
        )
        // And the suffix is still what tells the two roles apart with a serial present.
        assertEquals(SpeechRole.Translation, UtteranceId.parse("sentence:3#7:zh")?.role)
        assertEquals(SpeechRole.Reading, UtteranceId.parse("sentence:3#7")?.role)
    }

    @Test
    fun `an id with no serial still parses`() {
        // Kept for the callers that have no request to name; the serial is optional so the
        // shape of an id never became a second thing to agree on.
        assertEquals(SpokenUtterance(4, SpeechRole.Reading), UtteranceId.parse(UtteranceId.english(4)))
        assertEquals(
            SpokenUtterance(4, SpeechRole.Translation),
            UtteranceId.parse(UtteranceId.translation(4)),
        )
    }

    @Test
    fun `a malformed serial is rejected rather than guessed at`() {
        // A missing or non-numeric serial would otherwise make the rest of the id the index.
        listOf("sentence:3#", "sentence:#7", "sentence:3#x", "sentence:3#7#8", "sentence:3#7:zh#1")
            .forEach { assertNull("$it should not parse", UtteranceId.parse(it)) }
    }
}
