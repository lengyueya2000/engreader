package com.engreader.app.tts

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The policy for a speak request that arrives before the voice is up.
 *
 * This is the fix for "I tapped the speaker and heard nothing": the bundled voice takes
 * seconds to load on a cold start, and every one-shot path used to drop the request in
 * that window instead of waiting for it. It is tested here rather than on a device
 * because it is pure coroutine logic over a state flow — the Android half of [Speaker]
 * is on the other side of this seam.
 */
class VoiceGateTest {

    private val state = MutableStateFlow(SpeechState.Idle)

    /** The outcome with nothing else going on; the caller sets the state up first. */
    private fun await(timeoutMs: Long = 2_000L, stops: AtomicInteger = AtomicInteger(0)) =
        runBlocking { awaitVoiceThen(state, timeoutMs, 0) { stops.get() } }

    /**
     * Settles the state to [value] while the wait is running, which is the whole point:
     * a state set before the call would be answered by the gate without any waiting.
     */
    private fun awaitWhileSettlingTo(
        value: SpeechState,
        afterMs: Long,
        timeoutMs: Long = 2_000L,
    ): VoiceOutcome = runBlocking {
        launch(Dispatchers.Default) {
            delay(afterMs)
            state.value = value
        }
        awaitVoiceThen(state, timeoutMs, 0) { 0 }
    }

    @Test
    fun `a voice that is already up speaks at once`() {
        state.value = SpeechState.Ready
        assertEquals(VoiceOutcome.Ready, await())
    }

    @Test
    fun `a device with no voice is reported rather than waited on`() {
        state.value = SpeechState.Unavailable
        assertEquals(VoiceOutcome.Unavailable, await())
    }

    @Test
    fun `a voice that was never asked for is reported rather than waited on`() {
        state.value = SpeechState.Idle
        assertEquals(VoiceOutcome.Unavailable, await())
    }

    @Test
    fun `a request made while the model loads is spoken once it lands`() {
        state.value = SpeechState.Preparing
        assertEquals(VoiceOutcome.Ready, awaitWhileSettlingTo(SpeechState.Ready, afterMs = 30))
    }

    @Test
    fun `a load that ends unavailable is reported as unavailable`() {
        state.value = SpeechState.Preparing
        assertEquals(VoiceOutcome.Unavailable, awaitWhileSettlingTo(SpeechState.Unavailable, afterMs = 30))
    }

    @Test
    fun `a load that never settles gives up at the timeout`() {
        state.value = SpeechState.Preparing
        assertEquals(VoiceOutcome.Unavailable, await(timeoutMs = 60L))
    }

    @Test
    fun `a stop while the model loads abandons the request`() {
        state.value = SpeechState.Preparing
        val stops = AtomicInteger(0)
        val outcome = runBlocking {
            // The reader pauses mid-load; the voice then lands anyway, a moment later.
            launch(Dispatchers.Default) { delay(10); stops.incrementAndGet() }
            launch(Dispatchers.Default) { delay(30); state.value = SpeechState.Ready }
            awaitVoiceThen(state, 2_000L, 0) { stops.get() }
        }
        assertEquals(VoiceOutcome.Dropped, outcome)
    }

    @Test
    fun `a stop does not hold up a request made after the voice is up`() {
        // Pausing and then resuming is the ordinary case: `stop` bumps the counter, and
        // the speak that follows it must still go through.
        state.value = SpeechState.Ready
        assertEquals(VoiceOutcome.Ready, await(stops = AtomicInteger(7)))
    }

    /**
     * The wiring, not the policy: which window a given voice gets.
     *
     * `Speaker` used to pass one constant for every voice, and that constant's own
     * comment said it was sized for the 19 MB Piper model. The cloning voice reads 186 MB
     * out of the APK — 40 s cold on the emulator — so a press inside its load was past
     * the deadline, and the deadline's answer is what stops the read-aloud.
     */
    @Test
    fun `the cloning voice is waited for on its own budget, not a Piper voice's`() {
        val cloning = waitBudgetMs("en-GB", VoiceCatalog.ATTENBOROUGH.id)
        val piper = waitBudgetMs("en-GB", "en_GB-alan-medium")
        assertEquals(VoiceCatalog.CLONING_LOAD_WAIT_MS, cloning)
        assertEquals(VoiceCatalog.VITS_LOAD_WAIT_MS, piper)
        assertTrue(
            "a 186 MB load cannot be answered by a ${piper / 1000}s window",
            cloning >= piper * 4,
        )
    }

    @Test
    fun `a blank voice id takes the accent default's budget`() {
        val default = VoiceCatalog.resolve("en-GB", "")
        assertEquals(default.loadWaitMs, waitBudgetMs("en-GB", ""))
        assertEquals(default.loadWaitMs, waitBudgetMs("en-GB", null))
    }

    @Test
    fun `a request that lands inside a long budget is spoken`() {
        // The cloning voice's window is the one this pins: the same load that reports
        // `Unavailable` against 15 s has to end in a spoken sentence against 90 s.
        state.value = SpeechState.Preparing
        assertEquals(
            VoiceOutcome.Ready,
            awaitWhileSettlingTo(SpeechState.Ready, afterMs = 120, timeoutMs = VoiceCatalog.CLONING_LOAD_WAIT_MS),
        )
    }
}
