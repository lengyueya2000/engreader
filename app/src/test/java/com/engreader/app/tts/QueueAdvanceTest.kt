package com.engreader.app.tts

import com.engreader.app.tts.QueueAdvance.Step
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The read-aloud queue's advance rule, which is the half of [ListeningSession] that
 * decides whether a sentence is heard.
 *
 * `ListeningSession` itself cannot be built without the application container, a database
 * and a 19 MB voice, so the decision was moved next to the ids it turns on and is pinned
 * down here. Both defects it has had were silent: a request the queue had already replaced
 * reported its sentence finished, and the queue acted on it.
 */
class QueueAdvanceTest {

    @Test
    fun `the completion of a replaced request does not advance the queue`() {
        // The deterministic failure, with no race in it: sentence 3 is playing under
        // request 7, the reader taps sentence 3 to hear it again, which issues request 8 for
        // the same index. Request 7 is superseded and reports its ending, and a filter that
        // compared the sentence index alone took that for request 8's completion: the queue
        // moved to sentence 4 and the replay the reader asked for was never heard.
        assertEquals(
            Step.Ignore,
            QueueAdvance.after(
                utteranceId = UtteranceId.english(3, 7),
                issued = UtteranceId.english(3, 8),
                total = 10,
                translationPending = false,
            ),
        )
    }

    @Test
    fun `the completion of the request the queue issued advances it`() {
        val issued = UtteranceId.english(3, 8)
        assertEquals(
            Step.English(4),
            QueueAdvance.after(issued, issued, total = 10, translationPending = false),
        )
    }

    @Test
    fun `the last sentence of the piece ends it`() {
        val issued = UtteranceId.english(9, 2)
        assertEquals(
            Step.EndOfPiece,
            QueueAdvance.after(issued, issued, total = 10, translationPending = false),
        )
    }

    @Test
    fun `a sentence with a Chinese line is followed by that Chinese`() {
        val issued = UtteranceId.english(3, 1)
        assertEquals(
            Step.Translation(3),
            QueueAdvance.after(issued, issued, total = 10, translationPending = true),
        )
    }

    @Test
    fun `the Chinese is followed by the next English sentence`() {
        val issued = UtteranceId.translation(3, 4)
        assertEquals(
            Step.English(4),
            QueueAdvance.after(issued, issued, total = 10, translationPending = false),
        )
        val last = UtteranceId.translation(9, 5)
        assertEquals(
            Step.EndOfPiece,
            QueueAdvance.after(last, last, total = 10, translationPending = false),
        )
    }

    @Test
    fun `a completion for a sentence the queue has moved past is ignored`() {
        // The racing variant: 下一句 is tapped in the same instant the current sentence's
        // genuine completion arrives, both ask for sentence 4, and the loser's report is not
        // the one the queue is waiting on. Acting on it advanced the queue twice and sentence
        // 4 was never heard.
        assertEquals(
            Step.Ignore,
            QueueAdvance.after(
                utteranceId = UtteranceId.english(2, 5),
                issued = UtteranceId.english(3, 6),
                total = 10,
                translationPending = false,
            ),
        )
    }

    @Test
    fun `an id that is not the queue's is ignored even if nothing is awaited`() {
        // The preview and the word look-up put their own ids on the same stream, and a queue
        // that is waiting on nothing must not advance on them.
        assertEquals(Step.Ignore, QueueAdvance.after("preview", "preview", total = 10, translationPending = false))
        assertEquals(Step.Ignore, QueueAdvance.after("word:hello", "word:hello", total = 10, translationPending = false))
        assertEquals(Step.Ignore, QueueAdvance.after("sentence:3#7", null, total = 10, translationPending = false))
    }
}