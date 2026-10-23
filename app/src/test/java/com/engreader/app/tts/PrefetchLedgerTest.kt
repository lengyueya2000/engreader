package com.engreader.app.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the look-ahead bookkeeping.
 *
 * Every case here is a way the read-aloud can sound wrong without anything being
 * visibly broken: the reader just hears a pause, and no log says why. The two caps are
 * the ones that bit hardest — conflating them left the engine idle while a long
 * sentence was being waited for, which is the exact symptom this whole area exists to
 * remove — so the sizes below are the *shipped* ones, read from the production constants
 * rather than copied, because the previous pair drifted out of step with the window they
 * were supposed to cover and nothing failed.
 */
class PrefetchLedgerTest {

    private val reading = PrefetchLedger.Key(SpeechRole.Reading, "a")
    private val readingB = PrefetchLedger.Key(SpeechRole.Reading, "b")
    private val readingC = PrefetchLedger.Key(SpeechRole.Reading, "c")
    private val readingD = PrefetchLedger.Key(SpeechRole.Reading, "d")
    private val readingE = PrefetchLedger.Key(SpeechRole.Reading, "e")
    private val translation = PrefetchLedger.Key(SpeechRole.Translation, "a-zh")

    /** Payloads are plain strings; "usable" means "not marked stale". */
    private fun usable(payload: String) = !payload.endsWith("!")

    /** The shipped shape, taken from the production constants so the two cannot drift. */
    private fun ledger(
        hold: Int = NeuralTts.PREFETCH_HOLD,
        queue: Int = NeuralTts.PREFETCH_QUEUE,
    ) = PrefetchLedger<String>(holdPerRole = hold, queuePerRole = queue)

    /** Admits [key], failing the test with the refusal when it is not admitted. */
    private fun PrefetchLedger<String>.admit(key: PrefetchLedger.Key) {
        assertNull("expected $key to be admitted", request(key) { usable(it) })
    }

    @Test
    fun `the shipped caps cover the look-ahead window so nothing is discarded on arrival`() {
        // The window and the caps are three numbers that only work together: the hold has
        // to cover every sentence the reader asks for in one go, and the queue has to
        // admit that whole window *plus* another in flight. Asserted against the window
        // constant rather than a copy, because the previous set was copied into a comment
        // and then quietly stopped matching what shipped.
        assertTrue(
            "the hold must cover the look-ahead window",
            NeuralTts.PREFETCH_HOLD >= ListeningSession.LOOK_AHEAD_SENTENCES,
        )
        assertTrue(
            "the queue must admit a full window held plus another in flight",
            NeuralTts.PREFETCH_QUEUE >=
                NeuralTts.PREFETCH_HOLD + ListeningSession.LOOK_AHEAD_SENTENCES,
        )
    }

    @Test
    fun `a request reserves work and a second one for the same text does not`() {
        val ledger = ledger()
        ledger.admit(reading)
        assertEquals(
            "the same text must not be queued twice",
            PrefetchLedger.Refusal.INFLIGHT,
            ledger.request(reading, ::usable),
        )
        assertEquals(setOf(reading), ledger.inflightKeys())
    }

    @Test
    fun `the three refusals are told apart because the log names them`() {
        // NeuralTts logs the reason on every refusal, and those log lines are the only
        // trace a refused look-ahead leaves. If the three collapsed into one `false`
        // there would be no way to tell "already built" from "the engine was kept idle
        // on purpose" — and those two want opposite fixes.
        val ledger = ledger(hold = 2, queue = 2)
        ledger.admit(reading)
        ledger.complete(reading, "held")
        assertEquals(PrefetchLedger.Refusal.HELD, ledger.request(reading, ::usable))
        ledger.admit(readingB)
        assertEquals(PrefetchLedger.Refusal.INFLIGHT, ledger.request(readingB, ::usable))
        assertEquals(PrefetchLedger.Refusal.BUDGET, ledger.request(readingC, ::usable))
    }

    @Test
    fun `a reservation is released when the clip is delivered`() {
        val ledger = ledger(hold = 1, queue = 1)
        ledger.admit(reading)
        ledger.complete(reading, "audio")
        assertTrue(ledger.inflightKeys().isEmpty())
        assertEquals(listOf("audio"), ledger.heldPayloads())
        // The budget of one is now full because of the held clip, not of a leak.
        assertEquals(PrefetchLedger.Refusal.BUDGET, ledger.request(readingB, ::usable))
    }

    @Test
    fun `a reservation is released when the generation is abandoned`() {
        // The rule this pins: a mark that is never cleared blocks that sentence's
        // look-ahead for the rest of the process, so every later visit pays the whole
        // synthesis again — a pause with nothing in the log to explain it. It is also
        // the path a look-ahead takes when it yields to the reader's own request.
        val ledger = ledger()
        ledger.admit(reading)
        ledger.abandon(reading)
        assertTrue("the text must be requestable again", ledger.request(reading, ::usable) == null)
    }

    @Test
    fun `a failed generation frees its slot instead of holding it`() {
        val ledger = ledger(hold = 1, queue = 1)
        ledger.admit(reading)
        ledger.abandon(reading)
        assertNull(
            "a failed generation must not consume the budget",
            ledger.request(readingB, ::usable),
        )
    }

    @Test
    fun `a held clip that is not playable lets the text be generated again`() {
        val ledger = ledger()
        ledger.admit(reading)
        ledger.complete(reading, "stale!")
        // The payload no longer matches (a changed rate or voice), so a fresh
        // generation is wanted even though this key was asked for before.
        assertNull(ledger.request(reading, ::usable))
    }

    @Test
    fun `taking a clip hands out the one that is usable and removes it`() {
        val ledger = ledger()
        ledger.admit(reading); ledger.complete(reading, "first")
        ledger.admit(readingB); ledger.complete(readingB, "second")
        assertEquals("first", ledger.take(::usable))
        assertEquals(listOf("second"), ledger.heldPayloads())
        assertNull(ledger.take { it == "first" })
    }

    @Test
    fun `the queue cap is counted per role so the two halves of a pair both fit`() {
        // The whole reason the ledger counts by role: a shared budget would let the
        // Chinese clip squeeze out the second English one, which is the clip the queue
        // is about to ask for, and the reader would hear a gap every pair.
        val ledger = ledger(hold = 2, queue = 2)
        ledger.admit(reading)
        ledger.admit(readingB)
        assertEquals("the reading queue is full", PrefetchLedger.Refusal.BUDGET,
            ledger.request(readingC, ::usable))
        assertNull("translation has its own budget", ledger.request(translation, ::usable))
    }

    @Test
    fun `banked clips do not spend the queue budget`() {
        // The regression this file exists for. Counting finished clips against the same
        // cap as queued work means "two clips are banked" reads as "no more work may be
        // admitted", and the engine sits idle through exactly the long sentence the
        // reader is waiting for. Two caps, not one: the hold bounds memory, the queue
        // bounds how far ahead the engine may run.
        val ledger = ledger(hold = 2, queue = 4)
        ledger.complete(reading, "A")
        ledger.complete(readingB, "B")
        assertEquals("two clips are banked", 2, ledger.heldPayloads().size)
        ledger.admit(readingC)
        ledger.admit(readingD)
        assertEquals(
            "held plus in-flight is the cap, so a fifth is refused",
            PrefetchLedger.Refusal.BUDGET,
            ledger.request(readingE, ::usable),
        )
    }

    @Test
    fun `the hold cap is smaller than the queue cap and drops the late arrival`() {
        // Two caps only work if the hold one is the stricter, otherwise the queue could
        // never be deeper than the memory it is allowed to keep.
        val ledger = ledger(hold = 2, queue = 4)
        ledger.complete(reading, "A")
        ledger.complete(readingB, "B")
        ledger.complete(readingC, "C")
        assertEquals(
            "the clips nearest the play head are kept and the surplus dropped",
            listOf("A", "B"),
            ledger.heldPayloads(),
        )
    }

    @Test
    fun `a clip the reader has passed holds its slot until the caller clears it`() {
        // Pins the hazard that makes `NeuralTts.discardLookAhead` necessary, so nobody
        // "simplifies" it away later: `take` only matches the text being spoken and the
        // trim only discards the newest arrival, so a clip whose sentence the reader has
        // jumped past can never be taken *and* can never become the surplus. Fill every
        // hold slot that way and each later clip is dropped the moment it lands — the
        // look-ahead dies, and the reader is back to a synthesis-length pause between
        // sentences with nothing in the log to say the feature had stopped working.
        val ledger = ledger(hold = 2, queue = 4)
        ledger.complete(reading, "skipped")
        ledger.complete(readingB, "skipped")
        ledger.complete(readingC, "fresh")
        ledger.complete(readingD, "fresh")
        assertEquals(
            "the skipped clips keep their slots and every fresh one is discarded",
            listOf("skipped", "skipped"),
            ledger.heldPayloads(),
        )
        assertNull("nothing usable was banked", ledger.take { it != "skipped" })
        // What the caller does about it on a jump: clearing lets the next clip bank again.
        ledger.clearAll()
        ledger.complete(readingC, "fresh")
        assertEquals(listOf("fresh"), ledger.heldPayloads())
    }

    @Test
    fun `trimming the hold drops the newest of its own role`() {
        // The direction matters: dropping the *oldest* would throw away the sentence
        // about to be heard and re-synthesise it while the reader waits, which is the
        // opposite of what a look-ahead is for. `complete` is fed directly here — what
        // is pinned is the trim rule, not the reservation that precedes it in `prefetch`.
        val ledger = ledger(hold = 2, queue = 4)
        ledger.complete(readingB, "B")
        ledger.complete(readingC, "C")
        ledger.complete(readingD, "D")
        assertEquals(listOf("B", "C"), ledger.heldPayloads())
    }

    @Test
    fun `an English delivery never trims the Chinese clip`() {
        val ledger = ledger(hold = 2, queue = 4)
        ledger.complete(reading, "A")
        ledger.complete(readingB, "B")
        ledger.complete(translation, "中文")
        ledger.complete(readingC, "C")
        ledger.complete(readingD, "D")
        assertTrue(
            "the Chinese clip must not be trimmed by an English delivery",
            ledger.heldPayloads().contains("中文"),
        )
    }

    @Test
    fun `a delivered clip replaces an older one for the same text`() {
        val ledger = ledger()
        ledger.admit(reading)
        ledger.complete(reading, "v1")
        // Held and usable, so a second generation of the same text is refused.
        assertEquals(PrefetchLedger.Refusal.HELD, ledger.request(reading, ::usable))
        assertEquals("v1", ledger.take(::usable))
        ledger.admit(reading)
        ledger.complete(reading, "v2")
        assertEquals(listOf("v2"), ledger.heldPayloads())
    }

    @Test
    fun `a stop keeps reservations but drops held clips`() {
        // A reader who pauses and resumes on the same sentence wants the work already
        // in flight to land; a clip recorded for an abandoned utterance does not.
        val ledger = ledger()
        ledger.admit(reading); ledger.complete(reading, "held")
        ledger.admit(readingB)
        ledger.clearHeld()
        assertTrue(ledger.heldPayloads().isEmpty())
        assertEquals(setOf(readingB), ledger.inflightKeys())
        // The in-flight task can still deliver, and is not refused by the freed hold.
        ledger.complete(readingB, "arrived")
        assertEquals(listOf("arrived"), ledger.heldPayloads())
    }

    @Test
    fun `a voice change drops everything including reservations`() {
        val ledger = ledger()
        ledger.admit(reading); ledger.complete(reading, "old voice")
        ledger.admit(readingB)
        ledger.clearAll()
        assertTrue(ledger.heldPayloads().isEmpty())
        assertTrue(ledger.inflightKeys().isEmpty())
    }

    @Test
    fun `taking a clip frees the hold for the next one of that role`() {
        // The hold cap is about RAM, so it is released by the reader actually playing the
        // clip, not by the queue moving on.
        val ledger = ledger(hold = 1, queue = 4)
        ledger.admit(reading); ledger.complete(reading, "A")
        ledger.admit(readingB)
        assertEquals("the hold is full while the queue is not", 1, ledger.heldPayloads().size)
        ledger.take { it == "A" }
        assertTrue(ledger.heldPayloads().isEmpty())
        ledger.admit(readingC)
        ledger.complete(readingC, "C")
        assertEquals(listOf("C"), ledger.heldPayloads())
    }
}