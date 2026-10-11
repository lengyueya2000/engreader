package com.engreader.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Which engine is actually producing the voice. */
enum class SpeechBackend {
    /** The neural voice bundled in the APK. */
    Bundled,

    /** The device's own TTS engine, used only when the bundled one cannot load. */
    System,
}

/**
 * The reader's voice.
 *
 * Speaks with the neural voice packaged inside the app, and falls back to the
 * device's own TTS engine only if that voice cannot be loaded. The fallback is there
 * so a broken asset degrades the app instead of silencing it — it is not a choice
 * the reader makes, because the whole reason the voice is bundled is that the
 * device's own is unpredictable.
 *
 * Everything above this class — the reading queue, the sentence highlight, the
 * preview button — is written in terms of [speak], [stop] and the [events] stream,
 * so which engine sits behind it makes no difference to them.
 *
 * There are two voices behind it, not one: the English voice the reader picks, and the
 * Mandarin voice that reads the translation when the reader asks for it. They are
 * addressed by [SpeechRole] rather than by the text, because the two run on different
 * front ends and a Chinese line handed to the English voice would be spelled out letter
 * by letter. Both are loaded at once, so alternating between them costs no reload.
 */
class Speaker(private val context: Context) {

    /**
     * Kept as a handle so [shutdown] can cancel it. A bare `CoroutineScope(SupervisorJob())`
     * is never cancelled, so every `launch` on it outlived the speaker and kept this
     * object — and the native engine it holds — reachable for the rest of the process.
     */
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)

    private val neural = NeuralTts(context)

    /** Bumped on every [prepare]; a load whose generation is stale is discarded. */
    @Volatile private var loadGeneration = 0

    /** The voice the in-flight load is for, so a repeat request is not a reload. */
    @Volatile private var loadingVoiceId: String? = null

    /** Set by [shutdown], so a callback arriving after it cannot revive the state. */
    @Volatile private var closed = false

    private var engine: TextToSpeech? = null

    private val _state = MutableStateFlow(SpeechState.Idle)
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    private val _backend = MutableStateFlow(SpeechBackend.Bundled)
    val backend: StateFlow<SpeechBackend> = _backend.asStateFlow()

    private val _events = MutableSharedFlow<SpeechEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<SpeechEvent> = _events.asSharedFlow()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** The bundled voices, for the picker. Fixed, so the flow never changes. */
    private val _voices = MutableStateFlow(VoiceCatalog.all)
    val voices: StateFlow<List<VoiceCatalog.BundledVoice>> = _voices.asStateFlow()

    /** The voice in use, shown so the reader can see what is loaded. */
    private val _activeVoice = MutableStateFlow<VoiceCatalog.BundledVoice?>(null)
    val activeVoice: StateFlow<VoiceCatalog.BundledVoice?> = _activeVoice.asStateFlow()

    private var rate: Float = 0.95f
    private var localeTag: String = "en-GB"
    private var voiceId: String = ""

    /**
     * Set while [preview] or [sayWord] speaks, so the reading queue does not advance.
     *
     * Written from the caller's thread and read from the engine's callback thread, so
     * a plain `Boolean` would let the callback miss the flag and report a preview as
     * a finished sentence, skipping the reader forward.
     */
    @Volatile private var oneShot = false

    /**
     * The one-shot waiting for the voice to load, so a newer one can replace it.
     *
     * A headword tapped twice in a row should be heard once, and heard as the second
     * word — not as the first one arriving late on top of it.
     */
    private var pendingOneShot: Job? = null

    /**
     * Bumped by every [stop], so a request parked on the voice loading can tell that the
     * reader asked for quiet while it waited.
     *
     * Without it, tapping the speaker and then pausing played the sentence anyway the
     * moment the model finished loading — the pause arriving a second before the audio
     * did, which reads as the button having been ignored.
     */
    @Volatile private var stopGeneration = 0

    /**
     * True while the Chinese voice is loading.
     *
     * An atomic rather than a plain flag because [prepareTranslation] is called from a
     * composable, which can recompose many times while the 19 MB graph is being read;
     * the compare-and-set is what keeps that to one load.
     */
    private val translationPreparing = AtomicBoolean(false)

    /**
     * Loads the voice, or reconfigures it when it is already up.
     *
     * The first call reads a 19 MB model, so it is done off the main thread and the
     * state flow reports progress. Idempotent; safe to call from `LaunchedEffect`.
     *
     * A request that arrives while a load is in flight is not dropped: it is either
     * recognised as the same voice (nothing to do) or starts a fresh load whose
     * generation supersedes the one running, so picking a new voice mid-load ends on
     * the voice last picked rather than the one that happened to be loading.
     */
    fun prepare(rate: Float, localeTag: String, voiceId: String = "") {
        this.rate = rate
        this.localeTag = localeTag
        this.voiceId = voiceId
        if (closed) return
        val voice = VoiceCatalog.resolve(localeTag, voiceId)
        if (neural.ready && _backend.value == SpeechBackend.Bundled && _activeVoice.value?.id == voice.id) {
            _state.value = SpeechState.Ready
            return
        }
        // Already loading this exact voice: the in-flight load will finish it.
        if (_state.value == SpeechState.Preparing && loadingVoiceId == voice.id) return
        val generation = ++loadGeneration
        loadingVoiceId = voice.id
        _state.value = SpeechState.Preparing
        scope.launch(Dispatchers.Default) {
            val loaded = neural.prepare(localeTag, voiceId)
            scope.launch {
                // A newer request superseded this one while the model was loading.
                if (generation != loadGeneration || closed) return@launch
                loadingVoiceId = null
                if (loaded) {
                    _backend.value = SpeechBackend.Bundled
                    _activeVoice.value = voice
                    _state.value = SpeechState.Ready
                } else {
                    // The bundled voice is the product; if it will not load, a plain
                    // platform voice still beats a silent reading app.
                    _backend.value = SpeechBackend.System
                    _activeVoice.value = null
                    startSystemEngine()
                }
            }
        }
    }

    /**
     * Switches accent or voice.
     *
     * Unlike the platform engine this means rebuilding synthesis around a different
     * model, so it reloads rather than reconfiguring.
     */
    fun selectVoice(localeTag: String, voiceId: String) {
        val changed = localeTag != this.localeTag || voiceId != this.voiceId
        this.localeTag = localeTag
        this.voiceId = voiceId
        if (!changed && neural.ready) return
        stop()
        prepare(rate, localeTag, voiceId)
    }

    fun setRate(rate: Float) {
        this.rate = rate
        engine?.setSpeechRate(rate)
    }

    /**
     * Prepares [text] so the next [speak] for it starts without a wait.
     *
     * Called for the sentence after the one being spoken. Synthesis runs at roughly a
     * seventh of real time, so without this the reader hears the whole synthesis as a
     * pause between sentences.
     */
    fun prefetch(text: String, role: SpeechRole = SpeechRole.Reading) {
        if (_state.value != SpeechState.Ready || oneShot) return
        if (_backend.value != SpeechBackend.Bundled) return
        if (role == SpeechRole.Translation && !neural.translationReady) return
        neural.prefetch(spoken(text, role), role, rate)
    }

    /**
     * Loads the Chinese voice, once, off the main thread.
     *
     * Called when the reader turns on reading the translation aloud. It is a second
     * resident engine rather than a swap: the read-aloud alternates English and Chinese
     * sentence by sentence, and reloading a model between the two halves of every pair
     * would put a second of silence in the middle of each. Until it lands, [speak] with
     * [SpeechRole.Translation] reports the utterance as finished so the queue moves on
     * to the next English sentence instead of stalling on a Chinese line that will
     * never arrive.
     */
    fun prepareTranslation() {
        if (closed || neural.translationReady) return
        if (!translationPreparing.compareAndSet(false, true)) return
        scope.launch(Dispatchers.Default) {
            val loaded = neural.prepareTranslation()
            translationPreparing.set(false)
            if (!loaded) Log.w(TAG, "Chinese voice unavailable; reading English only")
        }
    }

    /**
     * Speaks one sentence.
     *
     * [utteranceId] comes back on the matching [SpeechEvent], which is how the reader
     * learns which sentence finished and highlights the next one.
     *
     * [role] picks the voice. For [SpeechRole.Reading] the text handed to the voice is
     * [SpokenText.of] the sentence, not the sentence itself: years, roman numerals and
     * markup are written for the eye, and the bundled voice reads them literally. That
     * rewrite is English-only — applying it to a Chinese line would spell `1837` as
     * English words — so a [SpeechRole.Translation] line goes through untouched, with
     * the rule FSTs in the Chinese engine doing the equivalent job. The reader's own
     * copy is untouched either way, so the highlight and the tap targets still refer to
     * the original.
     *
     * That rewrite can leave nothing behind, when the sentence is only an apparatus note
     * such as `[Illustration: ...]`. The queue is advanced here rather than by handing
     * empty text to the engine: the bundled voice does report completion for blank text,
     * but the platform engine's callback for it is not something to rely on, and a
     * missing completion would strand the reader on the note forever.
     */
    fun speak(
        text: String,
        utteranceId: String,
        role: SpeechRole = SpeechRole.Reading,
        flush: Boolean = true,
    ) {
        whenVoiceReady(
            onReady = { speakNow(text, utteranceId, role, flush) },
            // Nothing was handed to a voice, so nothing will ever report this utterance
            // finished. The read-aloud queue advances on that report and waits for it
            // indefinitely, so staying silent here stranded the reader on one sentence
            // with the button still reading "pause" and no sound. Reporting the failure
            // is what lets the queue stop and say so.
            onUnavailable = { emit(SpeechEvent.Failed(utteranceId)) },
        )
    }

    /** The body of [speak], run once the voice is up. */
    private fun speakNow(
        text: String,
        utteranceId: String,
        role: SpeechRole,
        flush: Boolean,
    ) {
        // Re-checked rather than assumed: this can run a moment after the wait, and a
        // `stop` or `shutdown` in between leaves no voice to hand the sentence to.
        if (_state.value != SpeechState.Ready) {
            emit(SpeechEvent.Failed(utteranceId))
            return
        }
        oneShot = false
        _speaking.value = true
        // The Chinese half is an addition to the English read-aloud, never a
        // replacement for it. When its voice is missing — not loaded yet, or the
        // bundled engine failed and the platform one is in its place with an English
        // locale set — reporting the utterance finished is what lets the next English
        // sentence start. A failure here would be read as "the voice is gone" and stop
        // the whole read-aloud.
        if (role == SpeechRole.Translation &&
            (_backend.value != SpeechBackend.Bundled || !neural.translationReady)
        ) {
            emit(SpeechEvent.Finished(utteranceId))
            return
        }
        val spoken = spoken(text, role)
        if (spoken.isBlank()) {
            emit(SpeechEvent.Finished(utteranceId))
            return
        }
        if (_backend.value == SpeechBackend.Bundled) {
            neural.speak(
                text = spoken,
                role = role,
                rate = rate,
                onStart = { scope.launch { _speaking.value = true } },
                onDone = { emit(SpeechEvent.Finished(utteranceId)) },
                // A failure of the Chinese half is reported as finished, not failed.
                // The reader treats `Failed` as "the voice is gone" and stops the whole
                // read-aloud, which is right for the English voice and wrong for an
                // addition to it: a Chinese engine that is missing or cannot pronounce
                // this line should cost the reader that one line's translation and
                // nothing more.
                onError = {
                    emit(
                        if (role == SpeechRole.Translation) SpeechEvent.Finished(utteranceId)
                        else SpeechEvent.Failed(utteranceId)
                    )
                },
            )
        } else {
            val tts = engine ?: run { emit(SpeechEvent.Failed(utteranceId)); return }
            tts.speak(spoken, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
    }

    /** What the voice is handed: the spoken form for English, the line itself for Chinese. */
    private fun spoken(text: String, role: SpeechRole): String =
        if (role == SpeechRole.Translation) text else SpokenText.of(text)

    /**
     * Speaks a sample so the reader can hear a voice before committing to it.
     *
     * Marked as a one-shot so its completion is swallowed: the reading queue is
     * advanced by the same event stream, and a preview firing mid-article would
     * otherwise skip the reader to the next sentence.
     */
    fun preview(text: String = DEFAULT_PREVIEW) {
        pendingOneShot?.cancel()
        pendingOneShot = whenVoiceReady(onReady = { previewNow(text) }, onUnavailable = {})
    }

    /** The body of [preview], run once the voice is up. */
    private fun previewNow(text: String) {
        if (_state.value != SpeechState.Ready) return
        oneShot = true
        if (_backend.value == SpeechBackend.Bundled) {
            neural.speak(
                text = text,
                role = SpeechRole.Reading,
                rate = rate,
                onStart = {},
                onDone = { oneShot = false },
                onError = { oneShot = false },
            )
        } else {
            engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, PREVIEW_ID)
        }
    }

    /** Speaks a short word in isolation, used by the look-up sheet and the wordbook. */
    fun sayWord(word: String, rate: Float = WORD_RATE) {
        // The raw form, not `spoken`: a dictionary headword is spelled as it is listed,
        // and the rewrite that helps a sentence would spell `Dr` as `Doctor` here.
        sayOneShot(word, rate)
    }

    /**
     * Speaks a phrase or a whole sentence at a walking pace, for the connected-speech
     * panel's slow half.
     *
     * Goes through the same preparation as read-aloud rather than [sayWord]'s raw path,
     * because the caller hands it a sentence as often as a two-word phrase and that
     * sentence may carry a number or an abbreviation.
     */
    fun saySlow(text: String) = sayOneShot(spoken(text, SpeechRole.Reading), SLOW_RATE)

    /** The same phrase at the reader's own rate, so the two can be compared. */
    fun sayPhrase(text: String) = sayOneShot(spoken(text, SpeechRole.Reading), rate)

    /**
     * One utterance that must not advance the reading queue, at a rate of its own.
     *
     * Marked as a one-shot so its completion is swallowed: the queue is advanced by the
     * same event stream, and a look-up or a slow replay firing mid-article would
     * otherwise skip the reader to the next sentence.
     */
    private fun sayOneShot(text: String, rate: Float) {
        if (text.isBlank()) return
        pendingOneShot?.cancel()
        pendingOneShot = whenVoiceReady(onReady = { sayOneShotNow(text, rate) }, onUnavailable = {})
    }

    /** The body of [sayOneShot], run once the voice is up. */
    private fun sayOneShotNow(text: String, rate: Float) {
        if (_state.value != SpeechState.Ready) return
        oneShot = true
        if (_backend.value == SpeechBackend.Bundled) {
            neural.speak(
                text = text,
                role = SpeechRole.Reading,
                rate = rate,
                onStart = {},
                onDone = { oneShot = false },
                onError = { oneShot = false },
            )
        } else {
            val tts = engine ?: run { oneShot = false; return }
            tts.setSpeechRate(rate)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "one:${text.take(24).lowercase()}")
            tts.setSpeechRate(this.rate)
        }
    }

    /**
     * Runs [onReady] once the voice can actually speak, or [onUnavailable] when it cannot.
     *
     * The decision itself lives in [awaitVoiceThen], which is where the reasoning is;
     * this only arranges for it to happen on the speaker's own scope and hands back the
     * job so a one-shot can replace a request that is still waiting.
     *
     * @return the job doing the waiting, when there is one, so a one-shot can replace it.
     */
    private fun whenVoiceReady(onReady: () -> Unit, onUnavailable: () -> Unit): Job? {
        // Answered synchronously in the two cases that need no waiting, so the ordinary
        // path — the voice is already up — behaves exactly as it did before any of this.
        when (gateFor(_state.value)) {
            VoiceGate.Go -> {
                onReady()
                return null
            }

            VoiceGate.Unavailable -> {
                onUnavailable()
                return null
            }

            VoiceGate.Wait -> Unit
        }
        val generation = stopGeneration
        return scope.launch {
            when (awaitVoiceThen(state, READY_WAIT_MS, generation, { stopGeneration })) {
                VoiceOutcome.Ready -> onReady()
                VoiceOutcome.Unavailable -> onUnavailable()
                // Dropped without a word: the queue reads `Failed` as "the voice is
                // gone" and tears the session down, so reporting a pause would kill a
                // session that had already moved on to a newer sentence. Nothing is left
                // waiting either — `stop` is only reached from the queue's own pause,
                // stop and piece-change, all of which issue a fresh speak or are done
                // with the queue.
                VoiceOutcome.Dropped -> Unit
            }
        }
    }

    fun stop() {
        oneShot = false
        // Bumped before the voice is silenced, so a request parked on the voice loading
        // sees itself superseded when the load finishes instead of speaking into the
        // quiet the reader just asked for.
        stopGeneration++
        pendingOneShot?.cancel()
        pendingOneShot = null
        neural.stop()
        engine?.stop()
        _speaking.value = false
    }

    /**
     * Releases the engine and stops accepting work.
     *
     * Cancelling the scope is the part that was missing: without it the native voice
     * and this object stayed alive for the life of the process, and a load that was
     * still in flight would land afterwards and set [state] back to `Ready` on a
     * speaker nobody owns any more.
     */
    fun shutdown() {
        if (closed) return
        closed = true
        loadGeneration++
        stop()
        job.cancel()
        neural.shutdown()
        engine?.shutdown()
        engine = null
        _state.value = SpeechState.Idle
        _activeVoice.value = null
    }

    /**
     * Rough duration estimate, used only to keep the highlight honest before the
     * first audio arrives.
     */
    fun estimateMillis(text: String): Long {
        val words = text.split(' ').size.coerceAtLeast(1)
        val msPerWord = (600 / rate).toLong()
        return (words * msPerWord).coerceAtLeast(600L)
    }

    private fun emit(event: SpeechEvent) {
        scope.launch {
            // `emit` is only ever reached to report an utterance ending, so the voice has
            // stopped by the time this runs. The flag used to be set the other way round,
            // which reported "speaking" for exactly the finished case and "not speaking"
            // for a failure — the opposite of both.
            _speaking.value = false
            _events.emit(event)
        }
    }

    // ------------------------------------------------------------ system fallback

    /**
     * Brings up the device's TTS engine.
     *
     * Reached only when the bundled voice fails to load, so it aims for "something
     * audible" rather than the best available voice: it sets the locale and accepts
     * whatever voice the engine answers with. Idempotent, but not a no-op on a repeat
     * call: see the state it settles below.
     */
    private fun startSystemEngine() {
        val existing = engine
        if (existing != null) {
            // Brought up by an earlier failed load. Nothing to start, but the state has
            // to be settled here: the early return used to leave `Preparing` behind, and
            // every `speak` after that reported a failure, so the second time the bundled
            // voice failed to load the app went silent for good. Re-applying the config
            // is also what carries a locale change through to the platform engine.
            applySystemConfig(existing)
            if (_state.value == SpeechState.Preparing) _state.value = SpeechState.Ready
            return
        }
        val listener = TextToSpeech.OnInitListener { status ->
            if (status != TextToSpeech.SUCCESS) {
                _state.value = SpeechState.Unavailable
                return@OnInitListener
            }
            val tts = engine ?: return@OnInitListener
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (oneShot) {
                        oneShot = false
                        return
                    }
                    utteranceId?.let { emit(SpeechEvent.Finished(it)) }
                }

                @Deprecated("Superseded by onError(String, Int)")
                override fun onError(utteranceId: String?) {
                    if (oneShot) {
                        oneShot = false
                        return
                    }
                    utteranceId?.let { emit(SpeechEvent.Failed(it)) }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (oneShot) {
                        oneShot = false
                        return
                    }
                    utteranceId?.let { emit(SpeechEvent.Failed(it)) }
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (oneShot) {
                        oneShot = false
                        return
                    }
                    utteranceId?.let { emit(SpeechEvent.Finished(it)) }
                }
            })
            applySystemConfig(tts)
            if (_state.value != SpeechState.Unavailable) _state.value = SpeechState.Ready
        }
        engine = runCatching { TextToSpeech(context, listener) }.getOrElse {
            Log.w(TAG, "no usable platform TTS engine", it)
            _state.value = SpeechState.Unavailable
            null
        }
    }

    private fun applySystemConfig(tts: TextToSpeech) {
        val locale = Locale.forLanguageTag(localeTag).takeIf { it.language == "en" } ?: Locale.UK
        val result = runCatching { tts.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // `availableLanguages` is null on an engine with no voice data installed
            // (a bare emulator image), so it has to be read defensively.
            val fallback = runCatching { tts.availableLanguages.orEmpty() }.getOrDefault(emptySet())
                .firstOrNull { it.language == "en" }
            if (fallback != null) {
                runCatching { tts.setLanguage(fallback) }
            } else {
                _state.value = SpeechState.Unavailable
            }
        }
        runCatching {
            tts.setSpeechRate(rate)
            tts.setPitch(1.0f)
        }
    }

    private companion object {
        const val TAG = "Speaker"
        const val PREVIEW_ID = "preview"

        /**
         * How long a request waits for a voice that is still loading.
         *
         * Long enough for the 19 MB model on a slow device, short enough that a voice
         * which never arrives ends in a report instead of a request that hangs.
         */
        const val READY_WAIT_MS = 15_000L

        /** A headword on its own is easier to catch a shade slower than running prose. */
        const val WORD_RATE = 0.8f

        /** The connected-speech comparison rate: slow enough to hear the joins. */
        const val SLOW_RATE = 0.6f

        /** Neutral sentence: covers the sounds a newsreader voice has to get right. */
        const val DEFAULT_PREVIEW =
            "The quick brown fox jumps over the lazy dog, and the world turns slowly on."
    }
}
