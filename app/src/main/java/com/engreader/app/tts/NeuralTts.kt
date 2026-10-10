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
 * library. The espeak-ng data is different: espeak-ng opens it with ordinary file
 * calls, so it must exist on disk, and [ensureEspeakData] copies it out of assets
 * once. The copy is shared by both voices and keyed by an asset fingerprint, so an
 * app upgrade refreshes it and nothing else touches it.
 */
class NeuralTts(private val context: Context) {

    @Volatile
    private var tts: OfflineTts? = null

    /** The voice the loaded engine was built for, so a switch forces a reload. */
    @Volatile
    private var loadedVoiceId: String = ""

    /**
     * Speaker index the loaded engine speaks with.
     *
     * One model file can hold many speakers — the VCTK graph holds 109 — and the
     * speaker is chosen per utterance rather than at load time, so switching between
     * two speakers of the same model still reloads and the two voices never share an
     * engine by accident.
     */
    @Volatile
    private var loadedSid: Int = 0

    /**
     * Owns the native engine: loading, synthesis and release all run here.
     *
     * One thread is what keeps the engine safe — `generate` and `release` must never
     * overlap, and sherpa's engine is not documented as reentrant.
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
     * shared flag that a later utterance could clear by accident.
     */
    private val generation = AtomicInteger(0)

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
     * a voice that has since been replaced is never played.
     */
    private class Clip(
        val text: String,
        val rate: Float,
        val sid: Int,
        val samples: FloatArray,
        val sampleRate: Int,
        val generation: Int,
    ) {
        /** True when this clip is the audio for exactly this request. */
        fun matches(text: String, rate: Float, sid: Int, engineGen: Int): Boolean =
            this.text == text && this.rate == rate && this.sid == sid && generation == engineGen
    }

    /** The one-sentence look-ahead, or null. See [prefetch]. */
    @Volatile
    private var prefetched: Clip? = null

    /** Text of the clip currently being generated, so a repeat is not queued twice. */
    private var prefetchingText: String? = null

    /** Guards [prefetched] and [prefetchingText], which two threads touch. */
    private val prefetchLock = Any()

    /** The track currently playing, so [stop] can silence it. */
    @Volatile
    private var track: AudioTrack? = null

    /** Absolute path of the unpacked espeak data; blank until [prepare] succeeds. */
    private var espeakDir: String = ""

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
        if (tts != null && loadedVoiceId == voice.id) return true
        // Interrupt whatever is being spoken, then do the load on the worker.
        // Releasing the engine from the caller's thread would free the native object
        // while `generate` is executing inside it, and the worker would then crash the
        // process rather than throw. Queueing behind the worker means the load cannot
        // start until the previous utterance has left the engine alone.
        stop()
        // Invalidates anything recorded for the voice being replaced. `stop` clears the
        // clip already held, but a prefetch queued before this call is still ahead of
        // the load on the worker, so it would otherwise finish and store a clip of the
        // old voice that a later `speak` would happily play.
        engineGeneration.incrementAndGet()
        val task = engineWorker.submit<Boolean> {
            releaseLocked()
            runCatching {
                espeakDir = ensureEspeakData()
                val engine = OfflineTts(
                    assetManager = context.assets,
                    config = OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            vits = OfflineTtsVitsModelConfig(
                                model = VoiceCatalog.modelPath(voice),
                                tokens = VoiceCatalog.tokensPath(voice),
                                dataDir = espeakDir,
                            ),
                            numThreads = threadCount(),
                            debug = false,
                            provider = "cpu",
                        ),
                        maxNumSentences = 1,
                        silenceScale = 0.2f,
                    ),
                )
                tts = engine
                loadedVoiceId = voice.id
                loadedSid = voice.sid
                true
            }.getOrElse { error ->
                Log.w(TAG, "neural voice ${voice.id} unavailable", error)
                false
            }
        }
        return runCatching { task.get() }.getOrDefault(false)
    }

    /** True once [prepare] has succeeded. */
    val ready: Boolean get() = tts != null

    /**
     * Speaks [text], reporting progress through the callbacks.
     *
     * Returns immediately; synthesis runs on [engineWorker] and playback on
     * [playbackWorker]. [onDone] fires only after the audio has finished playing,
     * which is what keeps the reader's sentence highlight in step with the voice.
     * Callbacks arrive on a background thread, so a caller that touches UI must hop
     * to the main thread.
     *
     * A clip recorded by [prefetch] is played straight away; without that, the wait
     * between two sentences would be the whole synthesis time.
     */
    fun speak(
        text: String,
        rate: Float,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: () -> Unit,
    ) {
        val engine = tts ?: run { onError(); return }
        if (text.isBlank()) {
            onDone()
            return
        }
        val token = generation.incrementAndGet()
        val sid = loadedSid
        val engineGen = engineGeneration.get()
        takePrefetched(text, rate, sid, engineGen)?.let { clip ->
            playbackWorker.execute { play(clip, token, onStart, onDone, onError) }
            return
        }
        engineWorker.execute {
            // A prefetch for this very sentence may have been queued ahead of this
            // task; the FIFO worker means it has finished by now, so look again
            // before paying for a second synthesis of the same text.
            takePrefetched(text, rate, sid, engineGen)?.let { clip ->
                playbackWorker.execute { play(clip, token, onStart, onDone, onError) }
                return@execute
            }
            // `release` runs on this same worker, so an engine that is no longer the
            // current one has already been freed and must not be touched.
            if (tts !== engine) {
                onError()
                return@execute
            }
            val audio = try {
                engine.generate(text = text, sid = sid, speed = rate)
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
            val clip = Clip(text, rate, sid, audio.samples, audio.sampleRate, engineGen)
            playbackWorker.execute { play(clip, token, onStart, onDone, onError) }
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
     * so a deeper queue would buy nothing and hold megabytes of samples. A request
     * that arrives while the engine is busy with an earlier one is dropped rather
     * than queued: the next [speak] will synthesise it if it is still wanted.
     */
    fun prefetch(text: String, rate: Float) {
        val engine = tts ?: return
        if (text.isBlank()) return
        val sid = loadedSid
        val engineGen = engineGeneration.get()
        synchronized(prefetchLock) {
            if (prefetched?.matches(text, rate, sid, engineGen) == true) return
            if (prefetchingText == text) return
            prefetchingText = text
        }
        engineWorker.execute {
            // The engine was replaced while this was queued; generating now would
            // either use a freed native handle or record a clip for the wrong voice.
            if (engineGeneration.get() != engineGen || tts !== engine) {
                synchronized(prefetchLock) { if (prefetchingText == text) prefetchingText = null }
                return@execute
            }
            val audio = runCatching { engine.generate(text = text, sid = sid, speed = rate) }.getOrNull()
            synchronized(prefetchLock) {
                if (prefetchingText == text) prefetchingText = null
                val samples = audio?.samples
                // A clip nobody asked for is harmless — `speak` only takes the one
                // whose text and voice match — so no utterance token is checked here.
                // Checking one would drop the clip in the normal case, because the
                // next `speak` bumps the token while this generation is still running.
                if (samples != null && samples.isNotEmpty() && engineGeneration.get() == engineGen) {
                    prefetched = Clip(text, rate, sid, samples, audio.sampleRate, engineGen)
                }
            }
        }
    }

    /** Removes and returns the held clip when it is the one asked for. */
    private fun takePrefetched(text: String, rate: Float, sid: Int, engineGen: Int): Clip? =
        synchronized(prefetchLock) {
            val held = prefetched ?: return@synchronized null
            if (!held.matches(text, rate, sid, engineGen)) return@synchronized null
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
        onError: () -> Unit,
    ) {
        // Superseded before a single sample played: this is a skip, not a failure.
        // Reporting it as one stopped playback outright, because the reader treats a
        // failure as "the voice is gone" while a skip is just the next sentence
        // arriving first.
        if (stale(token)) {
            onDone()
            return
        }
        var output: AudioTrack? = null
        try {
            output = openTrack(clip.sampleRate)
            track = output
            output.play()
            onStart()
            writeInChunks(output, clip.samples, token)
            if (!stale(token)) waitForDrain(output)
            // Reported as finished either way. A superseded utterance is not a
            // failure — the reader's `Finished` handler already ignores an id it has
            // moved past, while its `Failed` handler stops listening altogether, so
            // calling `onError` here is what made a sentence skip kill playback.
            onDone()
        } catch (error: Throwable) {
            Log.w(TAG, "playback failed", error)
            onError()
        } finally {
            if (track === output) track = null
            runCatching { output?.pause() }
            runCatching { output?.flush() }
            runCatching { output?.release() }
        }
    }

    /**
     * Silences playback and abandons the utterance being synthesised.
     *
     * Only ever called from the caller's thread; the engine itself is not touched, so
     * this cannot free memory a worker is still using.
     */
    fun stop() {
        generation.incrementAndGet()
        // Pause and flush rather than release: the playback worker owns the track and
        // releases it, so releasing here would leave that thread writing to a freed
        // handle.
        runCatching { track?.pause() }
        runCatching { track?.flush() }
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
        runCatching { engineWorker.submit { releaseLocked() }.get() }
    }

    /** Frees the engine. Must run on [engineWorker] only. */
    private fun releaseLocked() {
        runCatching { tts?.release() }
        tts = null
        loadedVoiceId = ""
        loadedSid = 0
    }

    fun shutdown() {
        stop()
        engineGeneration.incrementAndGet()
        runCatching { engineWorker.submit { releaseLocked() }.get() }
        engineWorker.shutdown()
        playbackWorker.shutdown()
    }

    /** True once this utterance has been superseded by a stop or a newer one. */
    private fun stale(token: Int): Boolean = generation.get() != token

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
        val base = if (requested > 0) requested else rate / 2 * BYTES_PER_FRAME
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
            val written = output.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
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
        runCatching { output.write(FloatArray(frames), 0, frames, AudioTrack.WRITE_BLOCKING) }
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
        val fingerprint = assetFingerprint()
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
     * Identifies the bundled espeak payload.
     *
     * File count and total size are enough to notice a voice pack that changed
     * between app versions without hashing megabytes on every launch.
     */
    private fun assetFingerprint(): String {
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
        runCatching { walk(VoiceCatalog.ESPEAK_ASSET_DIR) }
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

        /** ~0.2 s of audio per write, small enough that a skip is felt immediately. */
        const val CHUNK_SAMPLES = 4096

        const val DRAIN_TIMEOUT_MS = 10_000L
        const val DRAIN_POLL_MS = 10L
        const val DEFAULT_SAMPLE_RATE = 22050

        /** Mono float: four bytes per frame. */
        const val BYTES_PER_FRAME = 4
    }
}
