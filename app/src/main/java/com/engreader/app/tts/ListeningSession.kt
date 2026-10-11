package com.engreader.app.tts

import com.engreader.app.data.AppContainer
import com.engreader.app.nlp.Paragraphs
import com.engreader.app.nlp.Sentences
import com.engreader.app.nlp.Tokenizer
import com.engreader.app.translate.SentenceAlignment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

    /**
     * One thing to say: an English sentence, and its Chinese when there is one.
     *
     * [words] is the running total of English words up to and including this sentence,
     * not this sentence's own count. A flush has to report how much of the piece has
     * been covered, and summing the queue on every flush would be O(n) work on the
     * main thread for a number that only ever grows by one sentence's worth.
     */
    private class Spoken(val text: String, val chinese: String?, val paragraph: Int, val words: Int)

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
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var queue: List<Spoken> = emptyList()
    private var collectJob: Job? = null
    private var timerJob: Job? = null
    private var loadJob: Job? = null
    private var advanceJob: Job? = null
    private var lastIndex = -1

    /**
     * Bumped by every [open] and [stop].
     *
     * Loading a piece suspends — reading the article, splitting the body — and so does
     * looking up the next chapter. The session can be replaced or stopped across any of
     * those suspensions, so a continuation captures the value it started under and
     * checks it before touching the queue or the voice. Without it, opening B while A
     * was still loading ended with A's sentences being spoken under B's title, and
     * stopping while a chapter lookup was in flight started the next chapter of a
     * session the listener had already dismissed.
     */
    private var sessionGeneration = 0

    /**
     * Where a pause left off, so resuming continues instead of restarting.
     *
     * Reset by every path that ends the session outright — unlike [lastIndex], which is
     * cleared by a pause as well.
     */
    private var pausedIndex = -1

    /** Set while the end of a piece is being handled, so a second event cannot repeat it. */
    private var ending = false

    /**
     * True between asking for a piece and its first sentence actually being handed over.
     *
     * Loading a piece takes a while — the body is split, then the 19 MB voice is read out
     * of the APK — and nothing is audible for the whole of it. That silence is what makes
     * a listener tap again, and without this flag the second tap went to [resume], which
     * starts from the sentence the state happens to hold rather than the one the piece was
     * opened at: tapping twice threw the listener back to the top of the article. A pause
     * arriving in the same window is honoured by clearing the flag, so the pending start
     * does not play on after the listener asked for quiet.
     */
    private var starting = false

    /** The sentence [starting] is heading for, so a pause in that window resumes there. */
    private var startIndex = 0

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
            // Already on its way: the load starts playback at the requested sentence
            // itself, so a second tap in that window is not a new instruction. It used to
            // fall through to `resume` and restart the piece from the sentence the state
            // happened to hold — the top of the article — which is exactly what a
            // listener does when the first tap has not made a sound yet.
            starting -> Unit
            current.playing -> pause()
            else -> resume()
        }
    }

    fun pause() {
        if (!_state.value.active) return
        val at = lastIndex
        container.speaker.stop()
        lastIndex = -1
        // A pause before the first sentence is a pause on the start itself. Nothing has
        // been spoken, so `at` is -1 and there would be nothing to resume; the sentence
        // the start was heading for is the one to come back to. Left unrecorded the load
        // would also speak into the quiet the listener just asked for, which is why the
        // flag is cleared here rather than only checked when the load finishes.
        pausedIndex = if (starting) startIndex else at
        starting = false
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
        if (!current.active) return
        // Nothing to resume: the piece has not started yet and is already on its way.
        if (starting) return
        if (queue.isEmpty()) {
            // Active but with nothing to say: the piece's body never arrived, or a
            // previous load left the session holding an empty queue. Doing nothing here
            // is what a dead play button looks like, so the piece is fetched again.
            if (!current.loading) open(current.articleId, current.index)
            return
        }
        val from = if (pausedIndex >= 0) pausedIndex else current.index
        pausedIndex = -1
        // The clock starts again here rather than at pause(): time spent paused is not
        // listening time, and `startedAt` is what the next flush measures against.
        startedAt = System.currentTimeMillis()
        speakAt(from)
    }

    fun stop() {
        val current = _state.value
        sessionGeneration++
        starting = false
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
        // The outgoing piece is flushed first, while the queue and the clock that
        // describe it are still in place. Rolling into the next chapter and switching
        // straight from one article to another both come through here, and without this
        // the time spent on the piece being left would be dropped.
        if (_state.value.active && _state.value.articleId != articleId) writeProgress(_state.value)
        cancelJobs()
        container.speaker.stop()
        lastIndex = -1
        pausedIndex = -1
        ending = false
        startedAt = 0L
        queue = emptyList()
        val generation = ++sessionGeneration
        starting = true
        startIndex = fromIndex
        // The voice is asked for before the body is read, so the model loads while the
        // text is being split instead of after it. Loading it lazily on the first
        // `speak` would drop that first sentence: `Speaker.speak` is a no-op until the
        // engine reports Ready, and nothing retries it.
        container.speaker.prepare(
            rate = container.settings.speechRate,
            localeTag = container.settings.speechLocale,
            voiceId = container.settings.speechVoice,
        )
        // The sleep choice survives a chapter change — it was a promise about the
        // sitting, not about the piece — so it is carried into the fresh state.
        _state.update {
            State(
                active = true,
                articleId = articleId,
                loading = true,
                sleep = it.sleep,
                sleepUntil = it.sleepUntil,
            )
        }
        loadJob = container.appScope.launch {
            if (!load(articleId, generation)) {
                if (generation != sessionGeneration) return@launch
                _state.update { it.copy(active = false, loading = false, error = "这篇内容读不出来") }
                return@launch
            }
            // The session was replaced or stopped while the body was being read.
            if (generation != sessionGeneration) return@launch
            // The voice is still loading its model when the body is already split, and
            // `Speaker.speak` before it is Ready reports the utterance as failed rather
            // than queueing it. Waiting here is what makes the first sentence audible
            // when listening is started from a screen that never prepared the voice —
            // the book shelf's chapter row is the one that does that.
            awaitVoice()
            if (generation != sessionGeneration) return@launch
            // Paused while the voice was still loading. The piece is loaded and ready,
            // but the listener asked for quiet after asking to listen, and the answer is
            // the later of the two.
            if (!starting) return@launch
            // Started before the first utterance: the event flow has no replay, so a
            // collector that arrived late would miss the end of the sentence it came for.
            //
            // Subscribing is not the same as having subscribed, and the queue advances
            // only on that report. A sentence whose text is blank after rewriting — an
            // apparatus note such as `[Illustration: ...]` — reports completion the
            // instant it is handed over, so a first sentence of that kind could finish
            // before this collector existed, losing the report and stranding the queue on
            // a sentence that never ends. Waiting for the subscription closes the window.
            val subscribed = CompletableDeferred<Unit>()
            collectJob = container.appScope.launch { observeEvents(subscribed) }
            withTimeoutOrNull(SUBSCRIBE_WAIT_MS) { subscribed.await() }
            if (generation != sessionGeneration) return@launch
            startSleepTimer()
            speakAt(fromIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0)))
        }
    }

    /**
     * Waits for the voice to finish loading, or for it to give up.
     *
     * A bounded wait rather than an unbounded one: the model is 19 MB and a device with
     * no usable voice reports [SpeechState.Unavailable], and a queue that waited for a
     * voice that never arrives would sit on a loading spinner forever with no way to
     * find out why. On timeout the first [Speaker.speak] reports the failure and the
     * session stops with a reason.
     */
    private suspend fun awaitVoice(timeoutMs: Long = VOICE_WAIT_MS) {
        if (container.speaker.state.value != SpeechState.Preparing) return
        withTimeoutOrNull(timeoutMs) {
            container.speaker.state.first { it != SpeechState.Preparing }
        }
    }

    private suspend fun load(articleId: Long, generation: Int): Boolean {
        val article = container.articles.get(articleId) ?: return false
        val book = if (article.bookId > 0L) container.books.bookOfChapter(articleId) else null
        val chapters = if (article.bookId > 0L) container.books.chapters(article.bookId) else emptyList()
        val translation = article.translation
        // Splitting and aligning a chapter is CPU work over the whole body, and this
        // runs while the listener is waiting for the first sentence.
        val spoken = withContext(Dispatchers.Default) {
            buildList {
                var cumulative = 0
                Paragraphs.split(article.body).forEachIndexed { paragraphIndex, paragraph ->
                    val english = Sentences.split(paragraph.text).map { it.text }
                    val chinese = translation.getOrNull(paragraphIndex)
                        ?.let { SentenceAlignment.align(english, it) }
                    english.forEachIndexed { sentenceIndex, text ->
                        cumulative += Tokenizer.countWords(text)
                        add(Spoken(text, chinese?.getOrNull(sentenceIndex), paragraphIndex, cumulative))
                    }
                }
            }
        }
        if (generation != sessionGeneration) return false
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
        starting = false
        if (index !in queue.indices) {
            endOfPiece()
            return
        }
        ending = false
        // Any sentence spoken from here on supersedes a pause, whichever way the
        // listener got here. Left set, a resume() after a skip would jump back to the
        // sentence the pause had left off on instead of the one now playing.
        pausedIndex = -1
        val wasPlaying = _state.value.playing
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
        // The notification's middle button and the lock screen both read `playing`, and
        // a skip or a seek from a paused state starts audio without going through
        // resume(). Without this the shade would still offer 继续 while the voice was
        // already going. Starting the service and updating it are the same call: an
        // ACTION_START on a service that is already up only rewrites its notification.
        if (!wasPlaying) openService()
    }

    private fun speakTranslationAt(index: Int, chinese: String) {
        container.speaker.speak(chinese, UtteranceId.translation(index), SpeechRole.Translation)
        // What follows this Chinese line is the next sentence's English, so that is what
        // is worth synthesising now. Prefetching the next *Chinese* line here asked the
        // Mandarin engine for a sentence that would not be wanted for another two
        // utterances, and left the English one — the one actually about to play —
        // to be synthesised from scratch after the Chinese finished.
        queue.getOrNull(index + 1)?.let { container.speaker.prefetch(it.text, SpeechRole.Reading) }
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

    private suspend fun observeEvents(subscribed: CompletableDeferred<Unit>) {
        container.speaker.events
            // Signalled after the subscription is registered and before any event is
            // delivered, which is what lets `open` know the queue will hear its sentence.
            .onSubscription { subscribed.complete(Unit) }
            .collect { event ->
                if (!_state.value.playing) return@collect
                when (event) {
                    is SpeechEvent.Finished -> onFinished(event.utteranceId)
                    // Only a failure of the sentence being read is this session's
                    // failure. `speak` reports one for a request it could not hand to the
                    // voice, and by the time that report arrives the queue may already be
                    // on a newer sentence; stopping on it would end a session that is
                    // playing fine.
                    is SpeechEvent.Failed -> if (isCurrent(event.utteranceId)) stop()
                }
            }
    }

    /** True when [utteranceId] names the sentence the queue is on right now. */
    private fun isCurrent(utteranceId: String): Boolean =
        UtteranceId.parse(utteranceId)?.index == lastIndex

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
     *
     * "Stop at the end of this piece" is [SleepOption.EndOfPiece] rather than a flag of
     * its own: it is one of the sleep choices the listener picks from, and a second
     * switch for the same promise could disagree with it.
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
        if (current.bookId <= 0L) {
            stop()
            return
        }
        val generation = sessionGeneration
        advanceJob = container.appScope.launch {
            val next = runCatching {
                container.books.chapterIdAt(current.bookId, current.chapterIndex + 1)
            }.getOrNull()
            // The session was stopped, or replaced with another piece, while the next
            // chapter was being looked up.
            if (generation != sessionGeneration) return@launch
            if (next == null) {
                stop()
                return@launch
            }
            // `open` flushes the piece being left itself, so there is no second call here.
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
            if (_state.value.active) {
                // The clock has run out, so the choice goes back to 关 and the label
                // clears. Left armed, `sleepUntil` stayed in the past: the screen would
                // read 即将停止 for the rest of the sitting, and a chapter change would
                // re-arm on a deadline that had already passed and stop instantly.
                _state.update { it.copy(sleep = SleepOption.Off, sleepUntil = 0L) }
                pause()
            }
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
        loadJob?.cancel()
        loadJob = null
        advanceJob?.cancel()
        advanceJob = null
    }

    /**
     * Writes where listening has got to, so "continue reading" opens at the same place.
     *
     * Listening is reading: without this, an article heard all the way through would
     * still be offered from the top, and the time spent would not count towards the
     * daily goal. The session row is what the goal, the streak and the speed chart are
     * built from, so it is written here as well as the article's own running total.
     */
    private fun writeProgress(current: State) {
        val articleId = current.articleId
        if (articleId <= 0L || !current.active) return
        val spoken = queue.getOrNull(current.index)
        val paragraph = spoken?.paragraph
        // `stop()` reaches here on a piece that never finished loading, when there is no
        // start time to measure from — the difference would be the whole epoch.
        val now = System.currentTimeMillis()
        val seconds = if (startedAt <= 0L) 0 else ((now - startedAt) / 1000L).toInt()
        startedAt = if (startedAt <= 0L) 0L else now
        if (paragraph == null && seconds <= 0) return
        // Coverage, not words gained: it repeats across the flushes of one sitting and
        // the speed figure takes the maximum per article. Reported only alongside real
        // seconds, because a row with time and no position would drag the average down.
        val words = if (seconds > 0) spoken?.words ?: 0 else 0
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
            runCatching { container.wordbook.recordSession(articleId, seconds, words, 0) }
        }
    }

    private companion object {
        /**
         * How long [awaitVoice] waits for the bundled model before giving up.
         *
         * Generous: the graph is 19 MB and a cold load on a slow device takes a few
         * seconds, and the cost of waiting too long is only that the first sentence
         * starts late. The cost of not waiting long enough is the session stopping on
         * its first line, which reads as the feature being broken.
         */
        const val VOICE_WAIT_MS = 15_000L

        /**
         * How long `open` waits for its event collector to be subscribed.
         *
         * Not a timeout that is expected to fire — subscribing is a scheduling hop, not
         * work — it only keeps a collector that never started from hanging the session
         * before its first sentence.
         */
        const val SUBSCRIBE_WAIT_MS = 2_000L
    }
}
