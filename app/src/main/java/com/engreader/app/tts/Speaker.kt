package com.engreader.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

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
 */
class Speaker(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val neural = NeuralTts(context)

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

    /** Set while [preview] or [sayWord] speaks, so the reading queue does not advance. */
    private var oneShot = false

    /**
     * Loads the voice, or reconfigures it when it is already up.
     *
     * The first call reads a 19 MB model, so it is done off the main thread and the
     * state flow reports progress. Idempotent; safe to call from `LaunchedEffect`.
     */
    fun prepare(rate: Float, localeTag: String, voiceId: String = "") {
        this.rate = rate
        this.localeTag = localeTag
        this.voiceId = voiceId
        if (_state.value == SpeechState.Preparing) return
        val voice = VoiceCatalog.resolve(localeTag, voiceId)
        if (neural.ready && _backend.value == SpeechBackend.Bundled && _activeVoice.value?.id == voice.id) {
            _state.value = SpeechState.Ready
            return
        }
        _state.value = SpeechState.Preparing
        scope.launch(Dispatchers.Default) {
            val loaded = neural.prepare(localeTag, voiceId)
            scope.launch {
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
     * Speaks one sentence.
     *
     * [utteranceId] comes back on the matching [SpeechEvent], which is how the reader
     * learns which sentence finished and highlights the next one.
     */
    fun speak(text: String, utteranceId: String, flush: Boolean = true) {
        if (_state.value != SpeechState.Ready) return
        oneShot = false
        _speaking.value = true
        if (_backend.value == SpeechBackend.Bundled) {
            neural.speak(
                text = text,
                rate = rate,
                onStart = { scope.launch { _speaking.value = true } },
                onDone = { emit(SpeechEvent.Finished(utteranceId)) },
                onError = { emit(SpeechEvent.Failed(utteranceId)) },
            )
        } else {
            val tts = engine ?: run { emit(SpeechEvent.Failed(utteranceId)); return }
            tts.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
    }

    /**
     * Speaks a sample so the reader can hear a voice before committing to it.
     *
     * Marked as a one-shot so its completion is swallowed: the reading queue is
     * advanced by the same event stream, and a preview firing mid-article would
     * otherwise skip the reader to the next sentence.
     */
    fun preview(text: String = DEFAULT_PREVIEW) {
        if (_state.value != SpeechState.Ready) return
        oneShot = true
        if (_backend.value == SpeechBackend.Bundled) {
            neural.speak(
                text = text,
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
    fun sayWord(word: String, rate: Float = 0.8f) {
        if (_state.value != SpeechState.Ready || word.isBlank()) return
        oneShot = true
        if (_backend.value == SpeechBackend.Bundled) {
            neural.speak(
                text = word,
                rate = rate,
                onStart = {},
                onDone = { oneShot = false },
                onError = { oneShot = false },
            )
        } else {
            val tts = engine ?: run { oneShot = false; return }
            tts.setSpeechRate(rate)
            tts.speak(word, TextToSpeech.QUEUE_FLUSH, null, "word:${word.lowercase()}")
            tts.setSpeechRate(this.rate)
        }
    }

    fun stop() {
        oneShot = false
        neural.stop()
        engine?.stop()
        _speaking.value = false
    }

    fun shutdown() {
        stop()
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
            _speaking.value = event is SpeechEvent.Finished
            _events.emit(event)
        }
    }

    // ------------------------------------------------------------ system fallback

    /**
     * Brings up the device's TTS engine.
     *
     * Reached only when the bundled voice fails to load, so it aims for "something
     * audible" rather than the best available voice: it sets the locale and accepts
     * whatever voice the engine answers with.
     */
    private fun startSystemEngine() {
        if (engine != null) return
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

        /** Neutral sentence: covers the sounds a newsreader voice has to get right. */
        const val DEFAULT_PREVIEW =
            "The quick brown fox jumps over the lazy dog, and the world turns slowly on."
    }
}
