package com.engreader.app.tts

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a request that arrived before the voice was up should do.
 *
 * Whether the voice is up yet is a property of the voice, not of whoever is asking for
 * it, so the question is asked in one place and answered the same way for the reading
 * queue, a tapped headword and the settings preview.
 */
internal enum class VoiceGate {
    /** The voice is up: speak now. */
    Go,

    /** The model is still loading: wait for it, but not forever. */
    Wait,

    /** No voice was ever asked for, or there will never be one: report it and stay quiet. */
    Unavailable,
}

internal fun gateFor(state: SpeechState): VoiceGate = when (state) {
    SpeechState.Ready -> VoiceGate.Go
    SpeechState.Preparing -> VoiceGate.Wait
    // Idle means nothing has asked for a voice yet, so there is no load to wait on;
    // Unavailable means the asking is over.
    else -> VoiceGate.Unavailable
}

/**
 * How long a request may wait for [voiceId] to load.
 *
 * A function rather than a constant, and here rather than in [Speaker], because the
 * answer has to come from the voice — see [VoiceCatalog.BundledVoice.loadWaitMs] — and
 * the wiring that reads it is the part that was wrong: one window, sized in its comment
 * for the 19 MB Piper graph, was applied to a cloning voice that reads 186 MB out of the
 * APK. On a device where that load ran past the window, pressing listen was answered
 * "this voice is unavailable" and the read-aloud stopped, with nothing in the log to say
 * so. Pure, so the choice itself can be tested without a device.
 *
 * @param voiceId the voice being waited on; blank means the accent's default, which
 *   [VoiceCatalog.resolve] turns into a real voice.
 */
internal fun waitBudgetMs(localeTag: String, voiceId: String?): Long =
    VoiceCatalog.resolve(localeTag, voiceId.orEmpty()).loadWaitMs

/** How a request that had to wait for the voice ended. */
internal enum class VoiceOutcome {
    /** The voice is up; the caller may speak. */
    Ready,

    /** There is no voice, and none is coming. */
    Unavailable,

    /** The reader asked for quiet while the model was loading; the request is abandoned. */
    Dropped,
}

/**
 * Waits for the voice to finish loading, if it has to.
 *
 * `prepare` is asynchronous — the bundled model is 19 MB read out of the APK — so every
 * caller that speaks immediately after asking for a voice is racing the load. These
 * paths used to drop the request silently unless the voice happened to be `Ready` at
 * that instant, which is the reader's whole first interaction with the feature: tapping
 * a word in the wordbook, the review card or the look-up sheet, the slow replay in the
 * connected-speech panel, the voice preview in settings. On a cold start that window is
 * seconds wide, and what the reader got was a tap with no sound, no error, and nothing
 * to retry.
 *
 * The wait is bounded rather than open-ended: a device with no usable voice reports
 * `Unavailable` and is answered at once, and a load that somehow never settles ends in
 * a report instead of a request that hangs.
 *
 * @param stopGeneration the stop counter when the request was made.
 * @param currentStopGeneration reads that counter again now, so a request made before a
 *   [Speaker.stop] can tell that the reader asked for quiet while it waited — a pause
 *   arriving a second before the audio would otherwise be played anyway, which reads as
 *   the button having been ignored.
 */
internal suspend fun awaitVoiceThen(
    state: StateFlow<SpeechState>,
    timeoutMs: Long,
    stopGeneration: Int,
    currentStopGeneration: () -> Int,
): VoiceOutcome = when (gateFor(state.value)) {
    VoiceGate.Go -> VoiceOutcome.Ready
    VoiceGate.Unavailable -> VoiceOutcome.Unavailable
    VoiceGate.Wait -> {
        val settled = withTimeoutOrNull(timeoutMs) {
            state.first { it != SpeechState.Preparing }
        }
        when {
            currentStopGeneration() != stopGeneration -> VoiceOutcome.Dropped
            settled == SpeechState.Ready -> VoiceOutcome.Ready
            else -> VoiceOutcome.Unavailable
        }
    }
}
