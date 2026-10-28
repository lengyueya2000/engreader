package com.engreader.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks with the neural voice that ships inside the APK.
 *
 * ## Why the app carries its own voice
 *
 * System TTS is a lottery. The same code produces a crisp narrator on one phone and
 * a satnav drone on another, because the engine and the installed voice data belong
 * to the device rather than the app; on a bare emulator image there is no engine at
 * all. A reading app whose headline feature is listening to prose cannot leave its
 * voice to whichever phone the reader happens to own, so the voice is bundled.
 *
 * ## What is bundled
 *
 * Three Piper VITS voices — two British, one American, about 19-22 MB each — plus a
 * trimmed espeak-ng pronunciation dictionary, driven by sherpa-onnx. The two British
 * voices share one multi-speaker graph and differ by speaker index. Synthesis runs on
 * the CPU at roughly a seventh of real time, so a six-second sentence is ready in
 * under a second.
 *
 * A fourth voice reads the Chinese translation, and it is not an English voice with a
 * different speaker: it is a Mandarin graph phonemised by a pinyin lexicon rather than
 * by espeak-ng. The two front ends are mutually exclusive and cannot share an engine,
 * which is why the Chinese voice lives in its own slot (see [SpeechRole]).
 *
 * ## Two engines at once
 *
 * When the reader asks for the translation to be read out, each English sentence is
 * followed by its Chinese, and the two come from different engines. Both are therefore
 * held resident. Reloading a graph between the halves of every pair would cost about a
 * second of silence in the middle of each pair — far more noticeable than the memory
 * the second engine occupies, which is the same order as the first.
 *
 * ## How it speaks
 *
 * [speak] synthesises a whole utterance on [worker], then streams the samples to an
 * [AudioTrack] in small chunks. Chunked writes are what make [stop] responsive: the
 * loop checks whether it has been superseded between chunks, so skipping a sentence
 * silences it within a few tens of milliseconds. The sherpa callback API would allow
 * the same thing, but it hands the buffer to native code that looks up an exact
 * method signature on the lambda class — a signature Kotlin 2.0's `invokedynamic`
 * lambdas do not have, which aborts the process. Generating whole sentences and
 * playing them here avoids that interface entirely.
 *
 * ## Where the files live
 *
 * The ONNX graph and the token table are read straight out of the APK by the native
 * library, and so is the Chinese voice's pinyin lexicon and its rule FSTs — every path
 * the engine is given is relative to the asset root, because sherpa-onnx refuses to mix
 * that mode with absolute paths. The espeak-ng data is the one exception: espeak-ng
 * opens it with ordinary file calls, so [ensureEspeakData] copies it out of assets
 * once, keyed by an asset fingerprint, and nothing else touches it.
 */
class NeuralTts(private val context: Context) {

    /**
     * One loaded engine, with what it takes to speak through it.
     *
     * [voiceId] and [sid] are captured at load time rather than read from a shared
     * field, so a later voice change cannot make a synthesis that is already running
     * speak with the new voice's speaker index.
     */
    private class Engine(val tts: OfflineTts, val voiceId: String, val sid: Int)

    /**
     * The voice English is read with, and the one Chinese is read with.
     *
     * Both are held at once on purpose. The read-aloud alternates between them sentence
     * by sentence, and reloading a 19 MB graph between every English sentence and its
     * Chinese would put a second of silence in the middle of every pair — a far worse
     * cost than the memory. So [readingEngine] is replaced when the reader picks a
     * different accent, and [translationEngine] is loaded once and kept.
     */
    @Volatile
    private var readingEngine: Engine? = null

    @Volatile
    private var translationEngine: Engine? = null

    /**
     * Owns the native engines: loading, synthesis and release all run here.
     *
     * One thread is what keeps them safe — `generate` and `release` must never overlap,
     * and sherpa's engine is not documented as reentrant.
     */
    private val engineWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "neural-tts-engine").apply { priority = Thread.NORM_PRIORITY + 1 }
    }

    /**
     * Owns the [AudioTrack].
     *
     * Deliberately not [engineWorker]. When both lived on one thread the engine only
     * started on a sentence once the previous one had finished playing, so the
     * synthesis time — 1.5 s for a short sentence, over 5 s for a long one — was
     * heard as a pause between sentences. Splitting them lets [prefetch] generate the
     * next sentence while the current one is still coming out of the speaker.
     */
    private val playbackWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "neural-tts-play")
    }

    /**
     * Identifies the current utterance.
     *
     * Bumped by [stop] and by every new [speak], and captured by the job that owns
     * it. A job whose token no longer matches has been superseded and gives up at its
     * next checkpoint, which is what lets a skip cut a sentence short — without a
     * shared flag that a later utterance could clear by accident. The counter itself is
     * [RequestGeneration], which is where the rule is written down and tested: a
     * superseded request is reported as superseded, never as finished.
     */
    private val generation = RequestGeneration()

    /**
     * Bumped whenever the engine or the voice changes.
     *
     * A prefetch is generated without an utterance token of its own, so it cannot use
     * [generation]: it belongs to the voice, not to the sentence that happens to be
     * playing. This is what makes a voice switch discard a clip recorded for the old
     * one.
     */
    private val engineGeneration = AtomicInteger(0)

    /**
     * One synthesised sentence.
     *
     * [generation] is the [engineGeneration] it was made under, so a clip recorded for
     * a voice that has since been replaced is never played. [role] is in the match as
     * well: the English sentence and its Chinese are different text, but a clip held
     * for one must never be handed to the other's request.
     */
    private class Clip(
        val text: String,
        val role: SpeechRole,
        val rate: Float,
        val sid: Int,
        val samples: FloatArray,
        val sampleRate: Int,
        val generation: Int,
    ) {
        /** True when this clip is the audio for exactly this request. */
        fun matches(text: String, role: SpeechRole, rate: Float, sid: Int, engineGen: Int): Boolean =
            this.text == text && this.role == role && this.rate == rate &&
                this.sid == sid && generation == engineGen
    }

    /** The one-sentence look-ahead, or null. See [prefetch]. */
    @Volatile
    private var prefetched: Clip? = null

    /**
     * Text the newest [prefetch] asked for, so a task for anything older gives up at its
     * entry point instead of synthesising a sentence the reader has already left.
     */
    private var prefetchWanted: String? = null

    /** Guards [prefetched] and [prefetchWanted], which two threads touch. */
    private val prefetchLock = Any()

    /** The track currently playing, so [stop] can silence it. */
    @Volatile
    private var track: AudioTrack? = null

    /**
     * Serialises the two threads that touch the [AudioTrack].
     *
     * The playback worker is normally inside a blocking `write`, while [stop] — reached
     * from every skip, pause and voice change — pauses and flushes the same track from
     * the caller's thread. `track` is `@Volatile`, which buys visibility and no mutual
     * exclusion: a flush landing in the middle of a blocking write can make that write
     * return zero, dropping the rest of the sentence while the utterance is still
     * reported, and concurrent `pause`/`flush` during a blocking write is not a
     * documented-safe use of the class. The lock is held for one chunk at a time, so a
     * skip still silences the audio within tens of milliseconds.
     */
    private val trackLock = Any()

    /**
     * Loads a voice, or returns the one already loaded.
     *
     * Blocking: the first call reads a 19 MB model and takes a second or two even on
     * a fast phone, so callers run it off the main thread.
     *
     * @return true when the engine is usable. A false return means the caller should
     *   fall back to the platform engine; the failure is logged rather than thrown,
     *   because a missing voice should degrade the app, not take it down.
     */
    fun prepare(localeTag: String, voiceId: String): Boolean {
        val voice = VoiceCatalog.resolve(localeTag, voiceId)
        if (readingEngine?.voiceId == voice.id) return true
        return load(SpeechRole.Reading, voice)
    }

    /**
     * Loads the Chinese voice, once.
     *
     * Separate from [prepare] because it is not a choice: the reader never picks this
     * voice, and a failure to load it must leave the English read-aloud working rather
     * than take the whole feature down. Callers treat false as "skip the Chinese".
     */
    fun prepareTranslation(): Boolean {
        val voice = VoiceCatalog.translation
        if (translationEngine?.voiceId == voice.id) return true
        return load(SpeechRole.Translation, voice)
    }

    /**
     * Loads one voice into its slot, replacing whatever was there.
     *
     * Interrupts playback only when the reading voice is being replaced — the Chinese
     * one can arrive while an English sentence is being read, and silencing that
     * sentence to load an engine it does not use would be heard as the read-aloud
     * stuttering on the first pair. Releasing the engine from the caller's thread would
     * free the native object while `generate` is executing inside it, and the worker
     * would then crash the process rather than throw. Queueing behind the worker means
     * the load cannot start until the previous utterance has left the engine alone.
     */
    private fun load(role: SpeechRole, voice: VoiceCatalog.BundledVoice): Boolean {
        if (role == SpeechRole.Reading) stop()
        // Invalidates anything recorded for the voice being replaced. `stop` clears the
        // clip already held, but a prefetch queued before this call is still ahead of
        // the load on the worker, so it would otherwise finish and store a clip of the
        // old voice that a later `speak` would happily play.
        engineGeneration.incrementAndGet()
        val task = engineWorker.submit<Boolean> {
            releaseLocked(role)
            runCatching {
                val engine = build(role, voice)
                setEngine(role, Engine(engine, voice.id, voice.sid))
                true
            }.getOrElse { error ->
                Log.w(TAG, "neural voice ${voice.id} unavailable", error)
                false
            }
        }
        return runCatching { task.get() }.getOrDefault(false)
    }

    /**
     * Builds one engine.
     *
     * The two roles use different front ends and they are mutually exclusive. The
     * English voices are phonemised by espeak-ng, whose data has to be on disk. The
     * Chinese voice is phonemised by a pinyin lexicon instead, plus three rule FSTs
     * that turn written numbers and dates into something the lexicon can pronounce.
     *
     * Every path the Chinese engine is given is relative to the asset root, and the
     * `AssetManager` is what resolves it. sherpa-onnx treats the two modes as
     * exclusive — handed an absolute path while an asset manager is set, it logs
     * "you are using an absolute path ... but assetManager is NOT set to null" and
     * aborts the process — and the model graph cannot be moved out of the APK without
     * unpacking 19 MB per install. So the lexicon and the FSTs are read from inside
     * the APK too, which is also why none of them is copied to disk.
     */
    private fun build(role: SpeechRole, voice: VoiceCatalog.BundledVoice): OfflineTts {
        val vits: OfflineTtsVitsModelConfig
        var ruleFsts = ""
        if (role == SpeechRole.Translation) {
            ruleFsts = VoiceCatalog.ruleFstPaths(voice)
            vits = OfflineTtsVitsModelConfig(
                model = VoiceCatalog.modelPath(voice),
                tokens = VoiceCatalog.tokensPath(voice),
                lexicon = VoiceCatalog.lexiconPath(voice),
            )
        } else {
            vits = OfflineTtsVitsModelConfig(
                model = VoiceCatalog.modelPath(voice),
                tokens = VoiceCatalog.tokensPath(voice),
                dataDir = ensureEspeakData(),
            )
        }
        return OfflineTts(
            assetManager = context.assets,
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = vits,
                    numThreads = threadCount(),
                    debug = false,
                    provider = "cpu",
                ),
                ruleFsts = ruleFsts,
                maxNumSentences = 1,
                silenceScale = 0.2f,
            ),
        )
    }

    private fun engineFor(role: SpeechRole): Engine? =
        if (role == SpeechRole.Translation) translationEngine else readingEngine

    /** Must run on [engineWorker]. */
    private fun setEngine(role: SpeechRole, engine: Engine?) {
        if (role == SpeechRole.Translation) translationEngine = engine else readingEngine = engine
    }

    /** True once [prepare] has succeeded. */
    val ready: Boolean get() = readingEngine != null

    /** True once [prepareTranslation] has succeeded; false means "read English only". */
    val translationReady: Boolean get() = translationEngine != null

    /**
     * Speaks [text] in [role], reporting progress through the callbacks.
     *
     * Returns immediately; synthesis runs on [engineWorker] and playback on
     * [playbackWorker]. [onDone] fires only after the audio has finished playing,
     * which is what keeps the reader's sentence highlight in step with the voice.
     * Callbacks arrive on a background thread, so a caller that touches UI must hop
     * to the main thread.
     *
     * A request replaced before it was heard to its end reports [onSuperseded], not
     * [onDone]. The two are not interchangeable: the read-aloud queue advances on a
     * completion, so reporting a superseded request as finished made the queue consume a
     * completion that belonged to a request it had already replaced — the deterministic
     * "say this sentence again" that skipped to the next one instead of replaying, and
     * the loser of two requests for the same next sentence advancing the queue twice.
     *
     * A clip recorded by [prefetch] is played straight away; without that, the wait
     * between two sentences would be the whole synthesis time.
     */
    fun speak(
        text: String,
        role: SpeechRole,
        rate: Float,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onSuperseded: () -> Unit,
        onError: () -> Unit,
    ) {
        val engine = engineFor(role) ?: run { onError(); return }
        if (text.isBlank()) {
            onDone()
            return
        }
        val token = generation.next()
        val sid = engine.sid
        val engineGen = engineGeneration.get()
        takePrefetched(text, role, rate, sid, engineGen)?.let { clip ->
            playbackWorker.execute { play(clip, token, onStart, onDone, onSuperseded, onError) }
            return
        }
        engineWorker.execute {
            // Superseded while this sat in the queue behind another request's
            // synthesis. `generate` is a blocking native call that cannot be
            // interrupted, so a request that has already started is stuck paying for
            // audio nobody will hear; this is the one point where abandoned work can
            // still be dropped, and without it tapping one word and then another makes
            // the second wait for the first to be built and then thrown away.
            if (stale(token)) {
                onSuperseded()
                return@execute
            }
            // A prefetch for this very sentence may have been queued ahead of this
            // task; the FIFO worker means it has finished by now, so look again
            // before paying for a second synthesis of the same text.
            takePrefetched(text, role, rate, sid, engineGen)?.let { clip ->
                playbackWorker.execute { play(clip, token, onStart, onDone, onSuperseded, onError) }
                return@execute
            }
            // `release` runs on this same worker, so an engine that is no longer the
            // current one has already been freed and must not be touched.
            if (engineFor(role) !== engine) {
                onError()
                return@execute
            }
            val audio = try {
                engine.tts.generate(text = text, sid = sid, speed = rate)
            } catch (error: Throwable) {
                Log.w(TAG, "synthesis failed", error)
                onError()
                return@execute
            }
            if (audio.samples.isEmpty()) {
                Log.w(TAG, "synthesis produced no audio for ${text.length} chars")
                onError()
                return@execute
            }
            val clip = Clip(text, role, rate, sid, audio.samples, audio.sampleRate, engineGen)
            playbackWorker.execute { play(clip, token, onStart, onDone, onSuperseded, onError) }
        }
    }

    /**
     * Synthesises [text] ahead of time, so [speak] can start it without a wait.
     *
     * The reader calls this for the sentence after the one it just started, which is
     * what removes the silence between sentences: synthesis takes 0.3 s for a short
     * sentence and over 5 s for a long one, and that whole time used to sit between
     * two sentences as an audible pause.
     *
     * One sentence is held at a time — a novel's paragraph is a handful of sentences,
     * so a deeper queue would buy nothing and hold megabytes of samples — and which one
     * that is, is the *newest* one asked for: a request arriving while the worker is busy
     * with an earlier text replaces it, and the older task gives up at its entry point
     * instead of paying for a synthesis nobody will ever hear. [engineWorker] is a single
     * FIFO thread, so without that a burst of taps put a whole abandoned synthesis — 0.3 s
     * for a short sentence, over 5 s for a long one — in front of the sentence the reader
     * was actually waiting for.
     */
    fun prefetch(text: String, role: SpeechRole, rate: Float) {
        val engine = engineFor(role) ?: return
        if (text.isBlank()) return
        val sid = engine.sid
        val engineGen = engineGeneration.get()
        synchronized(prefetchLock) {
            if (prefetched?.matches(text, role, rate, sid, engineGen) == true) return
            if (prefetchWanted == text) return
            prefetchWanted = text
        }
        // `prefetchWanted` is set before the task is queued, so a rejection — the worker
        // is shut down once the voice is released — has to clear it. Left set, that text
        // would be refused by the dedupe check above for the rest of the process.
        val queued = runCatching {
            engineWorker.execute {
                // A newer text is already wanted: this task is ahead of it in the FIFO
                // queue, and running it would synthesise a sentence the reader has left.
                val stillWanted = synchronized(prefetchLock) { prefetchWanted == text }
                if (!stillWanted) return@execute
                // The engine was replaced while this was queued; generating now would
                // either use a freed native handle or record a clip for the wrong voice.
                if (engineGeneration.get() != engineGen || engineFor(role) !== engine) {
                    synchronized(prefetchLock) { if (prefetchWanted == text) prefetchWanted = null }
                    return@execute
                }
                val audio = runCatching { engine.tts.generate(text = text, sid = sid, speed = rate) }.getOrNull()
                synchronized(prefetchLock) {
                    if (prefetchWanted == text) prefetchWanted = null
                    val samples = audio?.samples
                    // A clip nobody asked for is harmless — `speak` only takes the one
                    // whose text and voice match — so no utterance token is checked here.
                    // Checking one would drop the clip in the normal case, because the
                    // next `speak` bumps the token while this generation is still running.
                    if (samples != null && samples.isNotEmpty() && engineGeneration.get() == engineGen) {
                        prefetched = Clip(text, role, rate, sid, samples, audio.sampleRate, engineGen)
                    }
                }
            }
        }
        if (queued.isFailure) {
            synchronized(prefetchLock) { if (prefetchWanted == text) prefetchWanted = null }
        }
    }

    /** Removes and returns the held clip when it is the one asked for. */
    private fun takePrefetched(
        text: String,
        role: SpeechRole,
        rate: Float,
        sid: Int,
        engineGen: Int,
    ): Clip? = synchronized(prefetchLock) {
        val held = prefetched ?: return@synchronized null
        if (!held.matches(text, role, rate, sid, engineGen)) return@synchronized null
        prefetched = null
        held
    }

    /**
     * Plays one synthesised clip to the end.
     *
     * Runs on [playbackWorker]; the token is checked between chunks so a skip cuts
     * the audio within tens of milliseconds.
     */
    private fun play(
        clip: Clip,
        token: Int,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onSuperseded: () -> Unit,
        onError: () -> Unit,
    ) {
        // Superseded before a single sample played: this is a skip, not a failure.
        // Reporting it as one stopped playback outright, because the reader treats a
        // failure as "the voice is gone" while a skip is just the next sentence
        // arriving first. It is not a completion either — see [onSuperseded].
        if (stale(token)) {
            onSuperseded()
            return
        }
        var output: AudioTrack? = null
        try {
            output = openTrack(clip.sampleRate)
            track = output
            output.play()
            onStart()
            writeInChunks(output, clip.samples, token)
            if (stale(token)) {
                // Cut off mid-sentence by a newer request or by a stop. The sentence was
                // not heard to its end, so it is reported as superseded: the queue's
                // `Finished` handler consumes a completion, and letting this one through
                // under the id the queue was waiting for is exactly how a superseded
                // request advanced the queue.
                onSuperseded()
            } else {
                waitForDrain(output)
                onDone()
            }
        } catch (error: Throwable) {
            Log.w(TAG, "playback failed", error)
            onError()
        } finally {
            // Under the same lock as the write loop and [stop]: pausing or releasing a
            // track another thread is inside a blocking `write` on is not a
            // documented-safe use of AudioTrack.
            synchronized(trackLock) {
                if (track === output) track = null
                runCatching { output?.pause() }
                runCatching { output?.flush() }
                runCatching { output?.release() }
            }
        }
    }

    /**
     * Silences playback and abandons the utterance being synthesised.
     *
     * Only ever called from the caller's thread; the engine itself is not touched, so
     * this cannot free memory a worker is still using.
     */
    fun stop() {
        generation.supersede()
        // Pause and flush rather than release: the playback worker owns the track and
        // releases it, so releasing here would leave that thread writing to a freed
        // handle. The lock is what keeps the flush from landing inside the worker's
        // blocking write; releasing the lock on the caller's thread is what keeps this
        // from being a foreign thread driving the track.
        synchronized(trackLock) {
            runCatching { track?.pause() }
            runCatching { track?.flush() }
        }
        // A held clip belongs to the utterance just abandoned; keeping it would play
        // the wrong sentence if the reader resumes on the same text.
        synchronized(prefetchLock) { prefetched = null }
    }

    /**
     * Releases the engine, waiting for any utterance in flight to leave it.
     *
     * `release` on the caller's thread used to free the native `OfflineTts` while the
     * engine worker could still be inside its blocking `generate`, which is a native
     * use-after-free — a crash no `runCatching` can catch. Queueing the release behind
     * the engine worker means the engine is only freed once that worker has finished
     * with it. The playback worker is not waited on: it touches samples that are
     * already a plain `FloatArray` in Kotlin, not the native engine.
     */
    fun release() {
        stop()
        engineGeneration.incrementAndGet()
        runCatching {
            engineWorker.submit {
                releaseLocked(SpeechRole.Reading)
                releaseLocked(SpeechRole.Translation)
            }.get()
        }
    }

    /** Frees one engine slot. Must run on [engineWorker] only. */
    private fun releaseLocked(role: SpeechRole) {
        val engine = engineFor(role) ?: return
        runCatching { engine.tts.release() }
        setEngine(role, null)
    }

    fun shutdown() {
        stop()
        engineGeneration.incrementAndGet()
        runCatching {
            engineWorker.submit {
                releaseLocked(SpeechRole.Reading)
                releaseLocked(SpeechRole.Translation)
            }.get()
        }
        engineWorker.shutdown()
        playbackWorker.shutdown()
    }

    /** True once this utterance has been superseded by a stop or a newer one. */
    private fun stale(token: Int): Boolean = generation.isStale(token)

    private fun openTrack(sampleRate: Int): AudioTrack {
        val rate = sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
        val requested = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        // A negative return is an error code, not a size; the fallback is half a
        // second. Either way the buffer has to be a whole number of frames, and a
        // mono float frame is four bytes — an unaligned size is rejected outright.
        //
        // `getMinBufferSize` is the smallest buffer the device will accept, not a
        // recommendation: it leaves about two [CHUNK_SAMPLES] writes of headroom, so a
        // playback thread descheduled for that long — a GC pause, or the engine worker
        // saturating the cores with a long synthesis — underruns and the sentence clicks.
        // Doubling it buys four chunks of headroom for ~90 ms of extra latency, which is
        // still far below the pause between two sentences.
        val minimum = if (requested > 0) requested else rate / 2 * BYTES_PER_FRAME
        val base = minimum * BUFFER_MULTIPLIER
        val bufferBytes = ((base + BYTES_PER_FRAME - 1) / BYTES_PER_FRAME) * BYTES_PER_FRAME
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /**
     * Writes the clip in small blocks, giving up as soon as it is superseded.
     *
     * A single blocking write of the whole clip would be simpler, but it would hold
     * the thread until every sample had been buffered, so tapping "next sentence"
     * mid-sentence would not take effect until the current one had finished.
     */
    private fun writeInChunks(output: AudioTrack, samples: FloatArray, token: Int) {
        var offset = 0
        while (offset < samples.size && !stale(token)) {
            val count = minOf(CHUNK_SAMPLES, samples.size - offset)
            // Under [trackLock]: [stop] pauses and flushes the same track from the
            // caller's thread, and a flush landing inside this blocking write can make it
            // return zero, which ends the loop with the rest of the sentence unplayed.
            val written = synchronized(trackLock) {
                output.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
            }
            if (written <= 0) return
            offset += written
        }
    }

    /**
     * Waits until the buffered audio has actually been heard.
     *
     * A blocking write returns once the data is in the buffer, not once it has
     * played, so without this the reader would advance its highlight — and start the
     * next sentence — a buffer's worth of audio early.
     */
    private fun waitForDrain(output: AudioTrack) {
        val frames = output.bufferSizeInFrames
        if (frames <= 0) return
        // One buffer of silence goes in after the speech; once the playback head has
        // crossed it, everything before it has been heard.
        val target = output.playbackHeadPosition + frames
        runCatching {
            synchronized(trackLock) {
                output.write(FloatArray(frames), 0, frames, AudioTrack.WRITE_BLOCKING)
            }
        }
        val deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (output.playbackHeadPosition >= target) return
            if (output.playState != AudioTrack.PLAYSTATE_PLAYING) return
            Thread.sleep(DRAIN_POLL_MS)
        }
    }

    /**
     * Copies the espeak-ng data out of assets, once per asset revision.
     *
     * espeak-ng opens its data with plain `fopen`, so unlike the ONNX model this
     * cannot stay inside the APK. The copy is about 840 KB.
     */
    private fun ensureEspeakData(): String {
        val root = File(context.filesDir, "tts")
        val target = File(root, "espeak-ng-data")
        val stamp = File(root, "espeak.stamp")
        val fingerprint = assetFingerprint(VoiceCatalog.ESPEAK_ASSET_DIR)
        if (target.isDirectory && stamp.takeIf { it.isFile }?.readText()?.trim() == fingerprint) {
            return target.absolutePath
        }
        target.deleteRecursively()
        target.mkdirs()
        copyAssetTree(VoiceCatalog.ESPEAK_ASSET_DIR, target)
        root.mkdirs()
        stamp.writeText(fingerprint)
        return target.absolutePath
    }

    /**
     * Identifies one bundled payload.
     *
     * File count and total size are enough to notice a voice pack that changed
     * between app versions without hashing megabytes on every launch.
     */
    private fun assetFingerprint(root: String): String {
        var files = 0
        var bytes = 0L
        fun walk(path: String) {
            val children = context.assets.list(path).orEmpty()
            if (children.isEmpty()) {
                files++
                bytes += runCatching { context.assets.open(path).available().toLong() }.getOrDefault(0L)
                return
            }
            children.forEach { walk("$path/$it") }
        }
        runCatching { walk(root) }
        return "$files:$bytes"
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        children.forEach { child -> copyAssetTree("$assetPath/$child", File(target, child)) }
    }

    /**
     * Threads for inference.
     *
     * Synthesis parallelises well across cores, but leaving one free keeps scrolling
     * smooth while the reader is listening.
     */
    private fun threadCount(): Int =
        (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)

    private companion object {
        const val TAG = "NeuralTts"

        /**
         * Audio per write: ~46 ms at the bundled voices' 22.05 kHz.
         *
         * This is the granularity of a skip. The write blocks until the track has room,
         * so the loop only notices that it has been superseded at a chunk boundary —
         * the reader taps a word while a sentence is playing and the sentence keeps
         * sounding for up to one chunk. At 4096 samples that was 186 ms, long enough to
         * be heard as the sentence stubbornly continuing under the new one.
         */
        const val CHUNK_SAMPLES = 1024

        /** Headroom over `getMinBufferSize`; see [openTrack]. */
        const val BUFFER_MULTIPLIER = 2

        const val DRAIN_TIMEOUT_MS = 10_000L
        const val DRAIN_POLL_MS = 10L
        const val DEFAULT_SAMPLE_RATE = 22050

        /** Mono float: four bytes per frame. */
        const val BYTES_PER_FRAME = 4
    }
}
