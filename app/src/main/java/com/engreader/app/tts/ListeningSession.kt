package com.engreader.app.tts

import com.engreader.app.data.AppContainer
import com.engreader.app.nlp.Paragraphs
import com.engreader.app.nlp.Sentences
import com.engreader.app.translate.SentenceAlignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which sleep timer the listener picked.
 *
 * The last one is not a clock: [EndOfPiece] ends when the article or chapter runs out,
 * which is a different promise from "in thirty minutes" and is why it is not expressed
 * as a deadline.
 */
enum class SleepOption(val label: String) {
    Off("关"),
    Fifteen("15 分钟"),
    Thirty("30 分钟"),
    Sixty("60 分钟"),
    EndOfPiece("读完本篇"),
}

/** Turns a sleep choice into the instant playback should stop, or 0 when it is not a clock. */
object SleepTimer {

    fun deadline(option: SleepOption, now: Long): Long = when (option) {
        SleepOption.Fifteen -> now + 15L * 60_000
        SleepOption.Thirty -> now + 30L * 60_000
        SleepOption.Sixty -> now + 60L * 60_000
        SleepOption.Off, SleepOption.EndOfPiece -> 0L
    }

    /**
     * How long is left, for the label under the sleep button.
     *
     * Rounded up, so a timer with half a minute left says "1 分钟后停止" rather than
     * "0 分钟后停止" — the latter reads as "it has already stopped" while the voice is
     * still going.
     */
    fun remainingLabel(until: Long, now: Long): String {
        if (until <= 0L) return ""
        val left = until - now
        if (left <= 0L) return "即将停止"
        val minutes = (left + 59_999L) / 60_000L
        return "$minutes 分钟后停止"
    }
}

/**
 * The read-aloud queue, owned by the app instead of by a screen.
 *
 * This used to live in the reader, which meant the voice stopped the moment the reader
 * left the screen — the queue and the highlight were the reader's, so a service left
 * running would have had nothing to advance it. Listening is the one thing in this app
 * meant to continue with the phone in a pocket, so the queue moved here, next to the
 * [Speaker] it drives. The reader is now a view onto it rather than its owner, and the
 * listening screen is a second view onto the same session.
 *
 * One piece at a time. [start] on a different article replaces whatever was playing;
 * finishing a chapter of a book rolls into the next one, which is what makes a novel
 * listenable in one sitting.
 *
 * The flat sentence list is rebuilt here from the same [Paragraphs] and [Sentences]
 * split the reader uses, so an index means the same sentence on both sides. That is the
 * only thing tying the reader's highlight to this queue, and it holds because the split
 * is a pure function of the body.
 */
class ListeningSession(private val container: AppContainer) {

    /** One thing to say: an English sentence, and its Chinese when there is one. */
    private class Spoken(val text: String, val chinese: String?, val paragraph: Int)

    data class State(
        /** True once a piece is loaded, whether or not the voice is currently going. */
        val active: Boolean = false,
        val articleId: Long = 0L,
        val title: String = "",
        val subtitle: String = "",
        val bookId: Long = 0L,
        val chapterIndex: Int = 0,
        val chapterCount: Int = 0,
        val index: Int = 0,
        val total: Int = 0,
        /** The sentence being read, so the listening screen has something to show. */
        val currentText: String = "",
        val currentChinese: String = "",
        val rate: Float = 0.95f,
        val playing: Boolean = false,
        val loading: Boolean = false,
        val error: String? = null,
        val sleep: SleepOption = SleepOption.Off,
        /** Epoch millis the clock-based sleep option fires, or 0 when there is none. */
        val sleepUntil: Long = 0L,
        /** False when the listener asked to stop at the end of this piece instead. */
        val advanceChapter: Boolean = true,
    ) {
        val hasNextChapter: Boolean get() = chapterCount > 1 && chapterIndex < chapterCount - 1
        val hasPreviousChapter: Boolean get() = chapterCount > 1 && chapterIndex > 0
        val positionLabel: String get() = if (total == 0) "" else "${index + 1} / $total"
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var queue: List<Spoken> = emptyList()
    private var collectJob: Job? = null
    private var timerJob: Job? = null
    private var lastIndex = -1

    /**
     * Where a pause left off, so resuming continues instead of restarting.
     *
     * Reset by every path that ends the session outright — unlike [lastIndex], which is
     * cleared by a pause as well.
     */
    private var pausedIndex = -1

    /** Set while the end of a piece is being handled, so a second event cannot repeat it. */
    private var ending = false

    private var startedAt = 0L

    init {
        // Installed once for the life of the app rather than by whichever screen is on
        // top: the lock screen outlives every screen, and a callback that went away with
        // the reader is exactly the bug this class exists to fix.
        PlaybackService.Hooks.resume = { resume() }
        PlaybackService.Hooks.pause = { pause() }
        PlaybackService.Hooks.stop = { stop() }
        PlaybackService.Hooks.next = { next() }
        PlaybackService.Hooks.previous = { previous() }
    }

    // ------------------------------------------------------------------ controls

    /** Starts [articleId], or picks it back up when it is the piece already loaded. */
    fun start(articleId: Long, fromIndex: Int = 0) {
        val current = _state.value
        if (current.active && current.articleId == articleId) {
            resume()
            return
        }
        open(articleId, fromIndex)
    }

    /**
     * The reader's listen button.
     *
     * Pauses rather than stops when this article is what is playing, so it matches the
     * lock screen: pressing it again carries on from the same sentence instead of
     * starting the article over.
     */
    fun toggle(articleId: Long, fromIndex: Int = 0) {
        val current = _state.value
        when {
            !current.active || current.articleId != articleId -> open(articleId, fromIndex)
            current.playing -> pause()
            else -> resume()
        }
    }

    fun pause() {
        if (!_state.value.active) return
        val at = lastIndex
        container.speaker.stop()
        lastIndex = -1
        pausedIndex = at
        _state.update { it.copy(playing = false) }
        writeProgress(_state.value)
        PlaybackService.setPlaying(
            container.appContext,
            playing = false,
            title = _state.value.title,
            subtitle = _state.value.subtitle,
        )
    }

    fun resume() {
        val current = _state.value
        if (!current.active || queue.isEmpty()) return
        val from = if (pausedIndex >= 0) pausedIndex else current.index
        pausedIndex = -1
        speakAt(from)
        openService()
    }

    fun stop() {
        val current = _state.value
        cancelJobs()
        container.speaker.stop()
        lastIndex = -1
        pausedIndex = -1
        ending = false
        writeProgress(current)
        queue = emptyList()
        _state.value = State()
        PlaybackService.stop(container.appContext)
    }

    /** Next sentence, or the end of the piece when this was the last one. */
    fun next() {
        if (!_state.value.active) return
        speakAt(_state.value.index + 1)
    }

    fun previous() {
        if (!_state.value.active) return
        speakAt((_state.value.index - 1).coerceAtLeast(0))
    }

    /** Jumps playback to a sentence — a tap on the text, or the queue's own advance. */
    fun seek(index: Int) {
        if (!_state.value.active || queue.isEmpty()) return
        speakAt(index.coerceIn(0, queue.size - 1))
    }

    fun setSleep(option: SleepOption) {
        _state.update { it.copy(sleep = option, sleepUntil = 0L) }
        timerJob?.cancel()
        timerJob = null
        if (_state.value.active) startSleepTimer()
    }

    fun setAdvanceChapter(value: Boolean) {
        _state.update { it.copy(advanceChapter = value) }
    }

    /**
     * Playback speed, shared with the reader's own setting.
     *
     * Listening is where it matters most — a commute is not the place to sit through a
     * slow read — so the control is here as well as in the typography sheet, and the two
     * write the same value rather than keeping separate ones.
     */
    fun setRate(rate: Float) {
        container.settings.speechRate = rate
        container.speaker.setRate(rate)
        _state.update { it.copy(rate = rate) }
    }

    // -------------------------------------------------------------------- queue

    private fun open(articleId: Long, fromIndex: Int) {
        cancelJobs()
        container.speaker.stop()
        lastIndex = -1
        pausedIndex = -1
        ending = false
        queue = emptyList()
        // The sleep choice survives a chapter change — it was a promise about the
        // sitting, not about the piece — so it is carried into the fresh state.
        _state.update {
            State(
                active = true,
                articleId = articleId,
                loading = true,
                sleep = it.sleep,
                sleepUntil = it.sleepUntil,
                advanceChapter = it.advanceChapter,
            )
        }
        container.appScope.launch {
            if (!load(articleId)) {
                _state.update { it.copy(active = false, loading = false, error = "这篇内容读不出来") }
                return@launch
            }
            // Started before the first utterance: the event flow has no replay, so a
            // collector that arrived late would miss the end of the sentence it came for.
            collectJob = container.appScope.launch { observeEvents() }
            startSleepTimer()
            speakAt(fromIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0)))
            openService()
        }
    }

    private suspend fun load(articleId: Long): Boolean {
        val article = container.articles.get(articleId) ?: return false
        val book = if (article.bookId > 0L) container.books.bookOfChapter(articleId) else null
        val chapters = if (article.bookId > 0L) container.books.chapters(article.bookId) else emptyList()
        val translation = article.translation
        // Splitting and aligning a chapter is CPU work over the whole body, and this
        // runs while the listener is waiting for the first sentence.
        val spoken = withContext(Dispatchers.Default) {
            buildList {
                Paragraphs.split(article.body).forEachIndexed { paragraphIndex, paragraph ->
                    val english = Sentences.split(paragraph.text).map { it.text }
                    val chinese = translation.getOrNull(paragraphIndex)
                        ?.let { SentenceAlignment.align(english, it) }
                    english.forEachIndexed { sentenceIndex, text ->
                        add(Spoken(text, chinese?.getOrNull(sentenceIndex), paragraphIndex))
                    }
                }
            }
        }
        queue = spoken
        startedAt = System.currentTimeMillis()
        _state.update {
            it.copy(
                active = true,
                loading = false,
                error = null,
                articleId = article.id,
                title = article.title,
                subtitle = book?.title ?: article.subtitle.ifBlank { article.originLabel },
                bookId = article.bookId,
                chapterIndex = article.chapterIndex,
                chapterCount = chapters.size,
                index = 0,
                total = spoken.size,
                currentText = spoken.firstOrNull()?.text.orEmpty(),
                currentChinese = "",
                rate = container.settings.speechRate,
            )
        }
        runCatching { container.articles.markOpened(article.id) }
        return spoken.isNotEmpty()
    }

    private fun speakAt(index: Int) {
        if (index !in queue.indices) {
            endOfPiece()
            return
        }
        ending = false
        lastIndex = index
        val spoken = queue[index]
        _state.update {
            it.copy(
                index = index,
                playing = true,
                currentText = spoken.text,
                currentChinese = if (container.settings.speakTranslation) {
                    spoken.chinese.orEmpty()
                } else {
                    ""
                },
            )
        }
        // The Chinese voice is only needed once playback starts, and it is wanted on
        // every path in — the reader's button, the lock screen, the listening screen —
        // so it is asked for here rather than at each entry point. Idempotent.
        if (container.settings.speakTranslation) container.speaker.prepareTranslation()
        container.speaker.speak(queue[index].text, UtteranceId.english(index))
        prefetchAfter(index)
    }

    private fun speakTranslationAt(index: Int, chinese: String) {
        container.speaker.speak(chinese, UtteranceId.translation(index), SpeechRole.Translation)
        queue.getOrNull(index + 1)?.let {
            container.speaker.prefetch(it.text, SpeechRole.Translation)
        }
    }

    /**
     * Synthesises whatever will be played after sentence [index] finishes.
     *
     * The next thing heard is this sentence's Chinese when it is going to be read, and
     * the English of the next one otherwise. Synthesis takes a sizeable fraction of the
     * sentence's own duration, and the engine generates on its own thread, so this
     * overlaps with playback instead of delaying it.
     */
    private fun prefetchAfter(index: Int) {
        if (container.settings.speakTranslation) {
            queue.getOrNull(index)?.chinese?.let { chinese ->
                container.speaker.prefetch(chinese, SpeechRole.Translation)
                return
            }
        }
        queue.getOrNull(index + 1)?.let { container.speaker.prefetch(it.text, SpeechRole.Reading) }
    }

    private suspend fun observeEvents() {
        container.speaker.events.collect { event ->
            if (!_state.value.playing) return@collect
            when (event) {
                is SpeechEvent.Finished -> onFinished(event.utteranceId)
                is SpeechEvent.Failed -> stop()
            }
        }
    }

    /**
     * Advances the queue by one utterance.
     *
     * The English of sentence N is followed by its Chinese when the listener asked for
     * it, and by the English of N+1 otherwise; the Chinese of N is always followed by
     * the English of N+1.
     */
    private fun onFinished(utteranceId: String) {
        val spoken = UtteranceId.parse(utteranceId) ?: return
        // Ignore a late callback from a sentence the listener already skipped.
        if (spoken.index != lastIndex) return
        val next = spoken.index + 1
        if (spoken.role == SpeechRole.Translation) {
            if (next < queue.size) speakAt(next) else endOfPiece()
            return
        }
        val chinese = if (container.settings.speakTranslation) {
            queue.getOrNull(spoken.index)?.chinese
        } else {
            null
        }
        if (chinese != null) {
            speakTranslationAt(spoken.index, chinese)
            return
        }
        if (next < queue.size) speakAt(next) else endOfPiece()
    }

    /**
     * What happens when the last sentence of a piece has been read.
     *
     * A chapter of a book rolls into the next one, which is the whole reason listening
     * is not just the reader with the screen off: twenty minutes of a novel should not
     * need a hand on the phone. A standalone article, the last chapter, and a listener
     * who asked to stop at the end all end the session instead.
     */
    private fun endOfPiece() {
        if (ending) return
        ending = true
        val current = _state.value
        if (current.sleep == SleepOption.EndOfPiece) {
            // The promise has been kept, so the choice goes back to 关. Left armed it
            // would fire again the moment the listener pressed play — replaying the last
            // sentence and stopping on it, which reads as the play button being broken.
            _state.update { it.copy(sleep = SleepOption.Off) }
            pause()
            return
        }
        if (!current.advanceChapter || current.bookId <= 0L) {
            stop()
            return
        }
        container.appScope.launch {
            val next = runCatching {
                container.books.chapterIdAt(current.bookId, current.chapterIndex + 1)
            }.getOrNull()
            if (next == null) {
                stop()
                return@launch
            }
            writeProgress(current)
            open(next, 0)
        }
    }

    // ------------------------------------------------------------------- support

    private fun startSleepTimer() {
        val current = _state.value
        if (current.sleep == SleepOption.Off || current.sleep == SleepOption.EndOfPiece) return
        // A chapter change re-arms the timer; an existing deadline is kept rather than
        // recomputed, or rolling into the next chapter would keep pushing it back.
        val until = current.sleepUntil.takeIf { it > System.currentTimeMillis() }
            ?: SleepTimer.deadline(current.sleep, System.currentTimeMillis())
        _state.update { it.copy(sleepUntil = until) }
        timerJob?.cancel()
        timerJob = container.appScope.launch {
            while (true) {
                val left = until - System.currentTimeMillis()
                if (left <= 0L) break
                delay(minOf(left, 1_000L))
            }
            if (_state.value.active) pause()
        }
    }

    private fun openService() {
        val current = _state.value
        PlaybackService.start(container.appContext, current.title, current.subtitle)
    }

    private fun cancelJobs() {
        collectJob?.cancel()
        collectJob = null
        timerJob?.cancel()
        timerJob = null
    }

    /**
     * Writes where listening has got to, so "continue reading" opens at the same place.
     *
     * Listening is reading: without this, an article heard all the way through would
     * still be offered from the top, and the time spent would not count towards the
     * daily goal.
     */
    private fun writeProgress(current: State) {
        val articleId = current.articleId
        if (articleId <= 0L || !current.active) return
        val paragraph = queue.getOrNull(current.index)?.paragraph
        val seconds = ((System.currentTimeMillis() - startedAt) / 1000L).toInt()
        startedAt = System.currentTimeMillis()
        if (paragraph == null && seconds <= 0) return
        container.appScope.launch {
            if (paragraph != null) {
                runCatching { container.articles.setReadParagraph(articleId, paragraph) }
            }
            if (seconds > 0) {
                runCatching { container.articles.addReadSeconds(articleId, seconds) }
                if (current.bookId > 0L) {
                    runCatching { container.books.addReadSeconds(current.bookId, seconds) }
                }
            }
        }
    }
}
