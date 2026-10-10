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

    /** Serialises synthesis; sherpa's generate is blocking and CPU-bound. */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "neural-tts").apply { priority = Thread.NORM_PRIORITY + 1 }
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
        val task = worker.submit<Boolean> {
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
     * Returns immediately; synthesis and playback both run on [worker]. [onDone]
     * fires only after the audio has finished playing, which is what keeps the
     * reader's sentence highlight in step with the voice. Callbacks arrive on a
     * background thread, so a caller that touches UI must hop to the main thread.
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
        worker.execute { runUtterance(engine, text, rate, sid, token, onStart, onDone, onError) }
    }

    private fun runUtterance(
        engine: OfflineTts,
        text: String,
        rate: Float,
        sid: Int,
        token: Int,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: () -> Unit,
    ) {
        val audio = try {
            engine.generate(text = text, sid = sid, speed = rate)
        } catch (error: Throwable) {
            Log.w(TAG, "synthesis failed", error)
            onError()
            return
        }
        val samples = audio.samples
        if (samples.isEmpty()) {
            Log.w(TAG, "synthesis produced no audio for ${text.length} chars")
            onError()
            return
        }
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
            output = openTrack(audio.sampleRate)
            track = output
            output.play()
            onStart()
            writeInChunks(output, samples, token)
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
        // Pause and flush rather than release: the worker owns the track and releases
        // it, so releasing here would leave that thread writing to a freed handle.
        runCatching { track?.pause() }
        runCatching { track?.flush() }
    }

    /**
     * Releases the engine, waiting for any utterance in flight to leave it.
     *
     * `release` on the caller's thread used to free the native `OfflineTts` while the
     * worker could still be inside its blocking `generate`, which is a native
     * use-after-free — a crash no `runCatching` can catch. Queueing the release behind
     * the worker means the engine is only freed once the worker has finished with it.
     */
    fun release() {
        stop()
        runCatching { worker.submit { releaseLocked() }.get() }
    }

    /** Frees the engine. Must run on [worker] only. */
    private fun releaseLocked() {
        runCatching { tts?.release() }
        tts = null
        loadedVoiceId = ""
        loadedSid = 0
    }

    fun shutdown() {
        stop()
        runCatching { worker.submit { releaseLocked() }.get() }
        worker.shutdown()
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
