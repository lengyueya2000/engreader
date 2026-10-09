package com.engreader.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

/** Engine lifecycle, so the UI can tell "no voice installed" apart from "still loading". */
enum class SpeechState { Idle, Preparing, Ready, Unavailable }

/** Emitted when a queued utterance finishes, so the reader can advance its highlight. */
sealed interface SpeechEvent {
    data class Finished(val utteranceId: String) : SpeechEvent
    data class Failed(val utteranceId: String) : SpeechEvent
}

/**
 * Wraps the platform [TextToSpeech] engine.
 *
 * The engine is created lazily on first use and released with the app. Speech is
 * queued one sentence at a time with a stable utterance id (`sentence:<index>`),
 * which is what lets the reader highlight the sentence currently being spoken and
 * stop exactly at a boundary when the user taps a different one.
 */
class Speaker(private val context: Context) {

    private var engine: TextToSpeech? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(SpeechState.Idle)
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SpeechEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<SpeechEvent> = _events.asSharedFlow()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** Voices offered in settings, filtered to English. */
    private val _voices = MutableStateFlow<List<VoiceOption>>(emptyList())
    val voices: StateFlow<List<VoiceOption>> = _voices.asStateFlow()

    private var rate: Float = 0.95f
    private var locale: Locale = Locale.UK

    data class VoiceOption(val label: String, val localeTag: String)

    /** Idempotent; safe to call from `LaunchedEffect`. Never throws. */
    fun prepare(rate: Float, localeTag: String) {
        this.rate = rate
        this.locale = localeFrom(localeTag)
        if (engine != null) {
            applyConfig()
            return
        }
        if (_state.value == SpeechState.Preparing) return
        _state.value = SpeechState.Preparing
        // An engine that fails to construct at all (no TTS service on the device)
        // must leave the reader usable rather than take the app down with it.
        engine = runCatching {
            TextToSpeech(context) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    _state.value = SpeechState.Unavailable
                    return@TextToSpeech
                }
                val tts = engine ?: return@TextToSpeech
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { scope.launch { _events.emit(SpeechEvent.Finished(it)) } }
                    }

                    @Deprecated("Superseded by onError(String, Int)")
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { scope.launch { _events.emit(SpeechEvent.Failed(it)) } }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        utteranceId?.let { scope.launch { _events.emit(SpeechEvent.Failed(it)) } }
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        utteranceId?.let { scope.launch { _events.emit(SpeechEvent.Finished(it)) } }
                    }
                })
                applyConfig()
                _voices.value = collectVoices(tts)
                if (_state.value != SpeechState.Unavailable) _state.value = SpeechState.Ready
            }
        }.getOrElse {
            _state.value = SpeechState.Unavailable
            null
        }
    }

    private fun applyConfig() {
        val tts = engine ?: return
        val result = runCatching { tts.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Fall back to any available English voice before declaring failure.
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

    private fun collectVoices(tts: TextToSpeech): List<VoiceOption> =
        runCatching { tts.voices.orEmpty() }.getOrDefault(emptyList())
            .filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
            .map { VoiceOption("${it.locale.displayName} · ${it.name.substringAfterLast('-')}", it.locale.toLanguageTag()) }
            .distinctBy { it.localeTag }
            .sortedBy { it.localeTag }

    fun selectVoice(localeTag: String) {
        locale = localeFrom(localeTag)
        applyConfig()
    }

    fun setRate(rate: Float) {
        this.rate = rate
        engine?.setSpeechRate(rate)
    }

    /** Speaks one sentence. Replaces anything already queued. */
    fun speak(text: String, utteranceId: String, flush: Boolean = true) {
        val tts = engine ?: return
        if (_state.value != SpeechState.Ready) return
        _speaking.value = true
        tts.speak(
            text,
            if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            null,
            utteranceId,
        )
    }

    /** Speaks a short word in isolation, used by the look-up sheet. */
    fun sayWord(word: String, rate: Float = 0.8f) {
        val tts = engine ?: return
        if (_state.value != SpeechState.Ready) return
        tts.setSpeechRate(rate)
        tts.speak(word, TextToSpeech.QUEUE_FLUSH, null, "word:${word.lowercase()}")
        tts.setSpeechRate(this.rate)
    }

    fun stop() {
        engine?.stop()
        _speaking.value = false
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        _state.value = SpeechState.Idle
        _speaking.value = false
    }

    /** Rough duration estimate so the reader can pre-highlight before `onStart` fires. */
    fun estimateMillis(text: String): Long {
        val words = text.split(' ').size.coerceAtLeast(1)
        val msPerWord = (600 / rate).toLong()
        return (words * msPerWord).coerceAtLeast(600L)
    }

    private fun localeFrom(tag: String): Locale =
        Locale.forLanguageTag(tag).takeIf { it.language == "en" } ?: Locale.UK
}
