package com.engreader.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
     *
     * [prompt] is the reference clip for a cloning voice and null for every other.
     * It is held rather than re-read per sentence: the clip is small, and decoding a
     * WAV inside the synthesis call would add its cost to the gap between sentences,
     * which is the one number this class works hardest to keep near zero.
     */
    private class Engine(
        val tts: OfflineTts,
        val voiceId: String,
        val sid: Int,
        val prompt: VoicePrompt? = null,
    )

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
        /** Which voice built this, so the log can say when a look-ahead missed. */
        val voiceId: String,
    ) {
        /** True when this clip is the audio for exactly this request. */
        fun matches(text: String, role: SpeechRole, rate: Float, sid: Int, engineGen: Int): Boolean =
            this.text == text && this.role == role && this.rate == rate &&
                this.sid == sid && generation == engineGen
    }

    /**
     * The look-ahead clips and the reservations beside them, at most
     * [PREFETCH_HOLD] clips and [PREFETCH_QUEUE] of held-plus-in-flight per role; the
     * rules and the reason there are two numbers live in [PrefetchLedger].
     *
     * One clip was enough for a Piper voice, whose synthesis runs at about a seventh
     * of real time: while a sentence played, its successor was long since built. A
     * cloning voice is different — measured on the emulator, a short sentence costs
     * more to synthesise than it takes to play (RTF above 1), because a large part of
     * the cost is the fixed work per call rather than the length of this sentence.
     * A single clip therefore hides nothing when the *current* sentence is short: the
     * look-ahead lands during it, the reader finishes hearing it, and the next
     * sentence is only now being generated. Two clips mean the engine keeps working
     * one sentence ahead of the look-ahead, so a short sentence at position N is
     * already finished by the time N-1 has played out.
     *
     * Counted per role rather than overall, because the two halves of a pair come from
     * different engines and are both wanted: a shared budget would have the Chinese
     * clip squeeze out the second English one whenever the translation is being read.
     */
    private val prefetchLedger = PrefetchLedger<Clip>(PREFETCH_HOLD, PREFETCH_QUEUE)

    /** Guards the ledger, which two threads touch. */
    private val prefetchLock = Any()

    /** The track currently playing, so [stop] can silence it. */
    @Volatile
    private var track: AudioTrack? = null

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
        // Every clip now held was built under the generation just retired, so not one
        // of them can ever be handed out again. Left on the ledger they would keep that
        // role's budget occupied and the new voice's first look-ahead would be refused
        // — a gap right after a voice switch. `stop` above clears the reading role when
        // that is the one being replaced, but the Chinese voice can be loaded while an
        // English sentence is playing and that path does not go through it.
        synchronized(prefetchLock) { prefetchLedger.clearHeld() }
        val task = engineWorker.submit<Boolean> {
            releaseLocked(role)
            runCatching {
                // Read once, and passed to both the build and the engine: the clip has
                // to be decoded before the graph is built — a cloning voice with no
                // usable prompt is refused there — and the same value then has to reach
                // every synthesis call.
                val prompt = promptFor(voice)
                val engine = build(role, voice, prompt)
                setEngine(role, Engine(engine, voice.id, voice.sid, prompt))
                true
            }.getOrElse { error ->
                Log.w(TAG, "neural voice ${voice.id} unavailable", error)
                false
            }
        }
        return runCatching { task.get() }.getOrDefault(false)
    }

    /**
     * The reference clip a cloning voice needs, or null for a voice that does not use
     * one.
     *
     * Read from the APK rather than copied to disk: the graph is already read straight
     * out of the assets by the native library, and 270 KB of prompt is not worth a
     * second unpacked copy. A missing or malformed pair is reported as null, and
     * [build] then refuses the voice — a clone without its prompt still synthesises,
     * but in an arbitrary voice, and a wrong voice presented as the right one is worse
     * than a voice that is visibly unavailable.
     */
    private fun promptFor(voice: VoiceCatalog.BundledVoice): VoicePrompt? {
        if (voice.engine != VoiceCatalog.VoiceEngine.ZipVoice) return null
        val audio = runCatching {
            context.assets.open(VoiceCatalog.referencePath(voice)).use { it.readBytes() }
        }.getOrElse { error ->
            Log.w(TAG, "reference clip for ${voice.id} unreadable", error)
            return null
        }
        val text = runCatching {
            context.assets.open(VoiceCatalog.referenceTextPath(voice)).use { input ->
                input.bufferedReader().readText().trim()
            }
        }.getOrElse { error ->
            Log.w(TAG, "reference text for ${voice.id} unreadable", error)
            return null
        }
        if (text.isEmpty()) {
            Log.w(TAG, "reference text for ${voice.id} is empty")
            return null
        }
        val prompt = VoicePrompt.parse(audio, text)
        if (prompt == null) Log.w(TAG, "reference clip for ${voice.id} is not a usable WAV")
        return prompt
    }

    /**
     * Builds one engine.
     *
     * Three front ends live here and they are mutually exclusive. The English Piper
     * voices are phonemised by espeak-ng, whose data has to be on disk. The Chinese
     * voice is phonemised by a pinyin lexicon instead, plus three rule FSTs that turn
     * written numbers and dates into something the lexicon can pronounce. A cloning
     * voice uses neither of those paths: ZipVoice carries its own character lexicon,
     * which is why it is handed a lexicon *and* the shared espeak data — the lexicon
     * covers English words, and espeak covers the letters it does not list.
     *
     * Every path the Chinese engine is given is relative to the asset root, and the
     * `AssetManager` is what resolves it. sherpa-onnx treats the two modes as
     * exclusive — handed an absolute path while an asset manager is set, it logs
     * "you are using an absolute path ... but assetManager is NOT set to null" and
     * aborts the process — and the model graph cannot be moved out of the APK without
     * unpacking 19 MB per install. So the lexicon and the FSTs are read from inside
     * the APK too, which is also why none of them is copied to disk.
     *
     * The one exception is espeak-ng data, which espeak opens with plain `fopen` and
     * which [ensureEspeakData] therefore unpacks once. The cloning voice's graphs are
     * read from the APK like the others, so a 119 MB decoder is never copied out.
     */
    private fun build(
        role: SpeechRole,
        voice: VoiceCatalog.BundledVoice,
        prompt: VoicePrompt?,
    ): OfflineTts {
        if (voice.engine == VoiceCatalog.VoiceEngine.ZipVoice) {
            requireNotNull(prompt) { "cloning voice ${voice.id} has no usable reference clip" }
            return OfflineTts(
                assetManager = context.assets,
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        zipvoice = OfflineTtsZipVoiceModelConfig(
                            tokens = VoiceCatalog.tokensPath(voice),
                            encoder = VoiceCatalog.encoderPath(voice),
                            decoder = VoiceCatalog.decoderPath(voice),
                            vocoder = VoiceCatalog.vocoderPath(voice),
                            dataDir = ensureEspeakData(),
                            lexicon = VoiceCatalog.lexiconPath(voice),
                        ),
                        numThreads = threadCount(),
                        debug = false,
                        provider = "cpu",
                    ),
                    maxNumSentences = 1,
                    silenceScale = 0.2f,
                ),
            ).also { Log.i(TAG, "cloning voice ${voice.id} ready (${prompt.samples.size} samples)") }
        }

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

    /**
     * How many syntheses the reader is waiting to hear.
     *
     * Bumped by any [speak] that has to be synthesised — the next sentence of the
     * read-aloud, a tapped word, a slow replay, a preview — and dropped when that
     * request's task has run. A *hit* against a banked clip never counts: nobody is
     * waiting for work that is already done.
     *
     * The engine worker is one FIFO queue and `generate` is a blocking native call that
     * cannot be interrupted, so a look-ahead task that is *ahead* of the reader's own
     * request makes them wait its whole synthesis for audio they will not reach for
     * another sentence or two. This counter is the signal a queued look-ahead checks
     * before it starts, and it gives up instead.
     *
     * That is not a micro-optimisation. Read off a 16-sentence run at 24 kHz, every one
     * of the six audible stalls ended with the reader's own request running at the exact
     * instant a *later* look-ahead's synthesis completed — the tell that the request had
     * been parked behind it in the queue. The two largest, 30.62 s and 18.88 s, break
     * down as `time needed to build the sentence the reader wants` plus, in the same
     * queue ahead of it, a wholly useless 14.07 s and 6.69 s building the sentence after
     * that. Only the second half is recoverable — `generate` cannot be stopped once it
     * has started — but it is the half that needs no new model or quality trade-off.
     *
     * An earlier version of this signal only counted requests the caller had flagged as
     * "hear it now" — tapped words and previews, via a `speak(immediate = true)` argument
     * — so the read-aloud's own next sentence had no standing and could wait behind a
     * look-ahead for a sentence two further on. The flag is gone rather than widened: a
     * sentence the queue has advanced to and a word the reader tapped impose the same
     * wait, and the only thing that distinguishes them is which caller asked. A session
     * that abandons a look-ahead this way re-asks for it on the following sentence, so
     * yielding costs nothing but does have to be re-asked: [prefetch] leaves the key
     * un-reserved so the next [ListeningSession.prefetchAfter] can admit it again.
     */
    private val waitingSpeech = AtomicInteger(0)

    /**
     * The text the reader's outstanding request is for, or null when there is none.
     *
     * A look-ahead consults this before stepping aside. Stepping aside is right when the
     * reader is waiting on *other* work — the look-ahead is ahead of their request in
     * the queue and would make them wait its whole synthesis for audio they will not
     * reach for another sentence or two. It is backwards when the text being waited for
     * is the very text about to be synthesised: there, finishing the synthesis *is* the
     * fastest way to end the wait, and yielding would only move the work later. That
     * case is common with the deeper look-ahead, because a sentence's own look-ahead is
     * often still queued when the reader arrives at it.
     *
     * Only the newest pending request is kept. If a word tap and the next sentence are
     * both queued, the word is built first and the sentence is built by its own request
     * — see the reservation release in [speak], which stops the look-ahead that was
     * standing aside from building the same text a second time.
     */
    private val pendingText = AtomicReference<String?>(null)

    /**
     * Runs one synthesis. Must be called on [engineWorker].
     *
     * The two engine kinds take different calls: a Piper voice names a speaker index,
     * while a cloning voice takes the reference clip and the words it says on every
     * request. Both live here so [speak] and [prefetch] cannot drift apart — a
     * prefetch that used the wrong call would produce a clip the matching `speak`
     * would reject, and the sentence would be synthesised twice with a silent gap
     * where the look-ahead should have been.
     */
    private fun synthesise(engine: Engine, text: String, rate: Float): GeneratedAudio {
        val prompt = engine.prompt
            ?: return engine.tts.generate(text = text, sid = engine.sid, speed = rate)
        return engine.tts.generateWithConfig(
            text = text,
            config = GenerationConfig(
                silenceScale = 0.2f,
                speed = rate,
                sid = engine.sid,
                referenceAudio = prompt.samples,
                referenceSampleRate = prompt.sampleRate,
                referenceText = prompt.text,
                numSteps = VoiceCatalog.CLONE_STEPS,
            ),
        )
    }

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
     * A clip recorded by [prefetch] is played straight away; without that, the wait
     * between two sentences would be the whole synthesis time.
     *
     * A request that has to be synthesised is one the reader is *waiting* on, and it
     * counts as such in [waitingSpeech] while its task sits in the engine queue — a
     * tapped word and the read-aloud's next sentence have the same standing, because
     * the wait they put the reader through is the same one. That, together with
     * [pendingText], is what makes a queued look-ahead stand aside; see [prefetch].
     */
    fun speak(
        text: String,
        role: SpeechRole,
        rate: Float,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: () -> Unit,
    ) {
        val engine = engineFor(role) ?: run { onError(); return }
        if (text.isBlank()) {
            onDone()
            return
        }
        val token = generation.incrementAndGet()
        val sid = engine.sid
        val engineGen = engineGeneration.get()
        takePrefetched(text, role, rate, sid, engineGen)?.let { clip ->
            logUtterance(role, text, clip, hit = true, queuedMs = 0L)
            handOff(clip, token, onStart, onDone, onError)
            return
        }
        // Counted here, before the task is queued, rather than inside it: a look-ahead
        // already sitting ahead of this request has to be able to see the count the
        // moment it starts, and it starts on the worker thread while this one is still
        // running.
        pendingText.set(text)
        waitingSpeech.incrementAndGet()
        val askedAt = SystemClock.elapsedRealtime()
        val submitted = runCatching {
            engineWorker.execute {
                // Taken at the start of the task, i.e. of the synthesis: what the log
                // calls `queuedMs` is how long this request waited for the engine, and
                // it is the number that separates "the clip was not built in time" from
                // "a look-ahead was holding the engine".
                val ranAt = SystemClock.elapsedRealtime()
                try {
                    // Superseded while this sat in the queue behind another request's
                    // synthesis. `generate` is a blocking native call that cannot be
                    // interrupted, so a request that has already started is stuck paying
                    // for audio nobody will hear; this is the one point where abandoned
                    // work can still be dropped, and without it tapping one word and then
                    // another makes the second wait for the first to be built and then
                    // thrown away.
                    if (stale(token)) {
                        onDone()
                        return@execute
                    }
                    // A prefetch for this very sentence may have been queued ahead of this
                    // task; the FIFO worker means it has finished by now, so look again
                    // before paying for a second synthesis of the same text.
                    takePrefetched(text, role, rate, sid, engineGen)?.let { clip ->
                        logUtterance(role, text, clip, hit = true, queuedMs = ranAt - askedAt)
                        handOff(clip, token, onStart, onDone, onError)
                        return@execute
                    }
                    // `release` runs on this same worker, so an engine that is no longer
                    // the current one has already been freed and must not be touched.
                    if (engineFor(role) !== engine) {
                        onError()
                        return@execute
                    }
                    // This request is about to build `text` itself, so a look-ahead that
                    // was admitted for the same text must not build it again afterwards.
                    // One that stepped aside for an earlier request is still holding this
                    // reservation and still queued; releasing it is what tells that task
                    // its work is no longer wanted — see the check [prefetch] makes on
                    // starting. Nothing is left un-reserved either way: this request is
                    // the one the reader is waiting for, and it is about to produce the
                    // audio.
                    synchronized(prefetchLock) {
                        prefetchLedger.abandon(PrefetchLedger.Key(role, text))
                    }
                    val started = SystemClock.elapsedRealtime()
                    val audio = try {
                        synthesise(engine, text, rate)
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
                    val clip = Clip(
                        text, role, rate, sid, audio.samples, audio.sampleRate, engineGen,
                        engine.voiceId,
                    )
                    logUtterance(
                        role, text, clip, hit = false,
                        synthMs = SystemClock.elapsedRealtime() - started,
                        queuedMs = started - askedAt,
                    )
                    handOff(clip, token, onStart, onDone, onError)
                } finally {
                    // The reader stops waiting on the engine the moment this task is done
                    // with it: the clip is banked and playing, or the request has failed.
                    // Held any longer, a look-ahead that should now run would yield to a
                    // request that has already been answered.
                    //
                    // Compare-and-set, not `set(null)`: a second request may already have
                    // taken over as the one being waited for.
                    pendingText.compareAndSet(text, null)
                    waitingSpeech.decrementAndGet()
                }
            }
        }.isSuccess
        if (!submitted) {
            // The worker was already shut down when this was handed over. Releasing the
            // count matters more than reporting the failure: left up, every later
            // look-ahead would yield to a request that no longer exists.
            pendingText.compareAndSet(text, null)
            waitingSpeech.decrementAndGet()
            onError()
        }
    }

    /**
     * One line per utterance about to be heard, for the voice that needs watching.
     *
     * The read-aloud's visible symptom — a long silence between two sentences — is a
     * timing fact, and until now nothing in the app measured time: the only logs here
     * were load and failure notices, so "is the look-ahead landing?" could only be
     * answered by reverse-engineering AudioFlinger's track history.
     *
     * Every role is logged, including the Piper voices. Leaving the Chinese half out
     * makes the silence arithmetic wrong: with the translation read aloud, an English
     * sentence is followed by its Mandarin, and a gap measured from the English lines
     * alone counts that speech as silence — which is how an earlier reading of this log
     * came out as "25 seconds of pause" between sentences that were actually being read.
     *
     * `hit` is the number that matters: an utterance that missed the look-ahead is one
     * the reader heard as a pause.
     *
     * `queuedMs` times the other half of that pause — how long the request sat in the
     * engine queue before its task ran, which is *not* the same as `synthMs` beside it.
     * `hit` says whether the look-ahead had banked the clip; `queuedMs` says whether the
     * *queue* was the reason it had not, which are different faults with different fixes
     * and used to look identical from outside. An earlier version of this log printed the
     * two added together and read as though every stall were a queueing fault.
     *
     * Formatted with template interpolation rather than a `%d`/`%.2f` string: this and
     * [prefetch] run on the engine worker, where a thrown exception reaches the thread's
     * uncaught handler and ends the process — an earlier `%d` fed a `Double` crashed the
     * first sentence the voice ever looked ahead, so a log line here is written to be
     * incapable of taking the read-aloud down.
     */
    private fun logUtterance(
        role: SpeechRole,
        text: String,
        clip: Clip,
        hit: Boolean,
        synthMs: Long = 0L,
        queuedMs: Long = 0L,
    ) {
        // Every role, not just the cloning one: the Mandarin half of each pair is what
        // fills the interval between two English lines, and leaving it out turns speech
        // into silence on the arithmetic.
        val audioS = clip.samples.size.toDouble() / clip.sampleRate
        val timing = if (synthMs > 0 && audioS > 0)
            " synthMs=$synthMs rtf=${"%.2f".format(synthMs / 1000.0 / audioS)}" else ""
        Log.i(
            TAG,
            "speak voice=${clip.voiceId} role=$role chars=${text.length} " +
                "audioS=${"%.2f".format(audioS)} " +
                (if (hit) "prefetch=hit" else "prefetch=MISS") +
                " queuedMs=$queuedMs$timing ${level(clip.samples)}",
        )
    }

    /**
     * What a clip actually contains, as peak and RMS in dBFS, for the utterance log.
     *
     * `audioS` alone cannot tell a sentence that was heard from one that was not: a clip
     * of digital silence and a clip of speech have the same length, the same sample rate
     * and the same effect on the clock — the reader advances on `waitForDrain` either
     * way, so "it reads but nothing comes out" leaves no trace at all in the log that
     * existed before this. One pass over samples the engine has just spent seconds
     * producing costs nothing by comparison, and it is the only measurement that
     * separates a voice that went quiet from a device that did not play what it was
     * handed. `nan` counts the samples that are neither: a non-finite sample is dropped
     * by `AudioTrack`, so a clip full of them is silent for a reason the peak alone
     * would hide.
     */
    private fun level(samples: FloatArray): String {
        if (samples.isEmpty()) return "peak=-inf rms=-inf"
        var peak = 0f
        var sum = 0.0
        var nan = 0
        for (sample in samples) {
            if (sample.isNaN()) {
                nan++
                continue
            }
            val magnitude = if (sample < 0f) -sample else sample
            if (magnitude > peak) peak = magnitude
            sum += sample.toDouble() * sample
        }
        val rms = kotlin.math.sqrt(sum / samples.size)
        fun db(value: Double): String =
            if (value <= 0.0) "-inf" else "%.1f".format(20.0 * kotlin.math.log10(value))
        val bad = if (nan > 0) " nan=$nan" else ""
        return "peak=${db(peak.toDouble())}dB rms=${db(rms)}dB$bad"
    }

    /**
     * Synthesises [text] ahead of time, so [speak] can start it without a wait.
     *
     * The reader calls this for the sentences after the one it just started, which is
     * what removes the silence between them: a cloning voice needs minutes of lead
     * time for a long sentence, so one sentence of look-ahead leaves the gap on screen
     * anyway — see [prefetchLedger].
     *
     * Requests are refused when the text is already held or already admitted, and the
     * queue is bounded by [PREFETCH_QUEUE] per role, so a listener who skips ten
     * sentences cannot pile up unbounded work for an abandoned stretch of the article.
     * A refusal is logged with its reason: it is the one outcome that leaves no other
     * trace, and "the engine sat idle while the reader waited" is what it looks like.
     *
     * ## Stepping aside without losing the work
     *
     * The task below re-queues *itself* when it finds the reader waiting on something
     * else. The engine is one FIFO queue and `generate` cannot be interrupted, so a
     * look-ahead that has already started is immovable; the only order this code can
     * choose is among the tasks that have not started, and it chooses the reader's
     * request. Read off the device log, that inversion was 46% of all the silence in a
     * 16-sentence run: every one of its six audible stalls ended at the instant a
     * *later* sentence's synthesis finished, with the clip the reader wanted already
     * banked and waiting.
     *
     * An earlier version gave up instead of re-queuing, and so lost the work: the
     * sentence it abandoned had to be requested again, and nothing requests again a
     * sentence the reader has already reached — [ListeningSession.prefetchAfter] only
     * ever asks for what is still ahead — so it was synthesised from scratch behind the
     * very request that had pushed it aside. Measured on the device as a 19.5 s stall on
     * a 300-character sentence. Re-queuing at the back of the same FIFO keeps the
     * reservation, puts the reader's request in front of it, and loses nothing.
     */
    fun prefetch(text: String, role: SpeechRole, rate: Float) {
        val engine = engineFor(role) ?: return
        if (text.isBlank()) return
        val sid = engine.sid
        val engineGen = engineGeneration.get()
        val key = PrefetchLedger.Key(role, text)
        // The payload test that decides whether a held clip can be played for this
        // request. A clip is only playable when it was built under the voice currently
        // loaded and at the rate now in force; anything else has to be generated again.
        val playable: (Clip) -> Boolean = { clip ->
            clip.matches(text, role, rate, sid, engineGen)
        }
        val refusal = synchronized(prefetchLock) { prefetchLedger.request(key, playable) }
        if (refusal != null) {
            Log.d(TAG, "prefetch-skip role=$role chars=${text.length} reason=$refusal")
            return
        }
        val askedAt = SystemClock.elapsedRealtime()
        // How many times this task has handed the engine to a request the reader is
        // waiting on. Bounded because the worker can only yield the engine to a request
        // that has actually been queued, and there is a window a few microseconds wide
        // where the reader has been counted but its task has not landed; unbounded, a
        // look-ahead could cycle inside that window and the engine would never run at
        // all. One pass is all a queued request needs.
        var deferrals = 0
        // Every path that does not deliver a clip has to release the reservation taken
        // just above, including a worker that has been shut down: a leaked reservation
        // blocks that sentence's look-ahead for the rest of the process.
        fun drop(reason: String) {
            synchronized(prefetchLock) { prefetchLedger.abandon(key) }
            Log.d(TAG, "prefetch-skip role=$role chars=${text.length} reason=$reason")
        }
        // A local function rather than a lambda, because the deferral below runs *this*
        // code a second time, from the back of the queue.
        fun generate() {
            // The engine was replaced while this was queued; generating now would either
            // use a freed native handle or record a clip for the wrong voice.
            if (engineGeneration.get() != engineGen || engineFor(role) !== engine) {
                drop("ENGINE")
                return
            }
            // `speak` releases this text's reservation when it builds the text itself,
            // which is what it does when the reader reaches a sentence whose look-ahead
            // is still queued behind them. The reservation still being here is what says
            // this work is still wanted; without the check the same text would be
            // synthesised a second time, in full, for nobody.
            if (!synchronized(prefetchLock) { prefetchLedger.isInflight(key) }) {
                Log.d(TAG, "prefetch-skip role=$role chars=${text.length} reason=SUPERSEDED")
                return
            }
            // The reader is waiting on something else, and this task is ahead of it in
            // the same FIFO queue, where `generate` cannot be interrupted. Building this
            // sentence now would make them wait its whole synthesis for audio they will
            // not reach for another sentence or two, so the engine is handed to their
            // request and this task goes to the back of the queue — still reserved, still
            // wanted, just no longer first. The one case that does not step aside is the
            // reader waiting for *this* text: then finishing it is the fastest way to end
            // the wait.
            val waiting = waitingSpeech.get()
            if (waiting > 0 && pendingText.get() != text) {
                if (deferrals < PREFETCH_DEFERRALS) {
                    deferrals++
                    Log.d(
                        TAG,
                        "prefetch-defer role=$role chars=${text.length} waiting=$waiting " +
                            "attempt=$deferrals",
                    )
                    val requeued = runCatching { engineWorker.execute { generate() } }.isSuccess
                    if (!requeued) drop("SHUTDOWN")
                    return
                }
                drop("DEFERRALS")
                return
            }
            val started = SystemClock.elapsedRealtime()
            val audio = runCatching { synthesise(engine, text, rate) }.getOrNull()
            val synthMs = SystemClock.elapsedRealtime() - started
            val samples = audio?.samples
            // A clip nobody asked for is harmless — `speak` only takes the one whose text
            // and voice match — so no utterance token is checked here. Checking one would
            // drop the clip in the normal case, because the next `speak` bumps the token
            // while this generation is still running.
            if (samples == null || samples.isEmpty() || engineGeneration.get() != engineGen) {
                drop("EMPTY")
                return
            }
            val clip = Clip(
                text, role, rate, sid, samples, audio.sampleRate, engineGen,
                engine.voiceId,
            )
            // Measured outside the lock: a pass over a long sentence's samples is real
            // work, and the ledger lock is held by `speak` for lookups.
            val levels = level(samples)
            synchronized(prefetchLock) {
                prefetchLedger.complete(key, clip)
                // Both roles, like `logUtterance`: which half of a pair stalled is the
                // question the two logs are read together to answer.
                val audioS = samples.size.toDouble() / audio.sampleRate
                val rtf = if (audioS > 0) synthMs / 1000.0 / audioS else 0.0
                val held = prefetchLedger.heldPayloads().count { it.role == role }
                // `queuedMs` is the wait before this synthesis even started, and it is
                // what tells a build that was simply late from one that was not begun
                // until the queue ahead of it drained; `deferred` says how much of that
                // wait this task spent standing aside for the reader.
                Log.i(
                    TAG,
                    "prefetch voice=${engine.voiceId} role=$role chars=${text.length} " +
                        "audioS=${"%.2f".format(audioS)} synthMs=$synthMs " +
                        "rtf=${"%.2f".format(rtf)} queuedMs=${started - askedAt} " +
                        "deferred=$deferrals held=$held $levels",
                )
            }
        }
        val queued = runCatching { engineWorker.execute { generate() } }.isSuccess
        if (!queued) drop("SHUTDOWN")
    }

    /**
     * Hands [clip] to the playback thread, reporting a worker that is already gone.
     *
     * Wrapped rather than enqueued directly because two of the three callers run on the
     * engine thread, where a thrown exception reaches the thread's uncaught handler and
     * ends the process — the same hazard [logUtterance] is written to avoid. A playback
     * worker that has been shut down is only reachable once the session is over, where
     * nothing more will be heard either way, but a crash is not an acceptable way to
     * report that.
     */
    private fun handOff(
        clip: Clip,
        token: Int,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: () -> Unit,
    ) {
        val queued = runCatching {
            playbackWorker.execute { play(clip, token, onStart, onDone, onError) }
        }.isSuccess
        if (!queued) onError()
    }

    /**
     * The samples to play for [clip], with the cloning voice's noise floor gated out.
     *
     * Only the cloning voice. Its floor was measured against the speaker's own room floor
     * and found 6.76 dB (p05) too high — a stationary wideband hiss, which is what
     * [NoiseGate] is a gain curve for. The Piper voices' floors were never measured and
     * they share neither model nor vocoder with the cloning voice, so they are left
     * alone: a change made on faith is still a change.
     *
     * Runs on the playback worker over a few hundred thousand floats, against the
     * seconds the engine has just spent producing them.
     */
    private fun toPlay(clip: Clip): FloatArray {
        if (VoiceCatalog.byId(clip.voiceId)?.engine != VoiceCatalog.VoiceEngine.ZipVoice) {
            return clip.samples
        }
        val gated = NoiseGate.apply(clip.samples, clip.sampleRate)
        if (gated.maxAttenuationDb < 0.0) {
            Log.d(
                TAG,
                "gate voice=${clip.voiceId} " +
                    "maxAttenuation=${"%.1f".format(gated.maxAttenuationDb)}dB",
            )
        }
        return gated.samples
    }

    /** Removes and returns the held clip when it is the one asked for. */
    private fun takePrefetched(
        text: String,
        role: SpeechRole,
        rate: Float,
        sid: Int,
        engineGen: Int,
    ): Clip? = synchronized(prefetchLock) {
        prefetchLedger.take { it.matches(text, role, rate, sid, engineGen) }
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
            writeInChunks(output, toPlay(clip), token)
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
        // the wrong sentence if the reader resumes on the same text. Reservations are
        // left alone — the work in flight still finishes and re-files its own clip,
        // which is what a resume on the same sentence wants.
        synchronized(prefetchLock) { prefetchLedger.clearHeld() }
    }

    /**
     * Drops every banked clip *and* every look-ahead in flight, because the reader moved.
     *
     * A jump — next, previous, a tap on another sentence — leaves the look-ahead aimed at
     * the sentences that were skipped, and those clips are worse than wasted: [take]
     * only ever matches the text about to be spoken and the trim only discards the
     * *newest* arrival, so a clip whose sentence the reader has passed can never be taken
     * and can never be the surplus that gets discarded either. It holds its slot for the
     * rest of the session, and once every slot is held that way each new clip is dropped
     * the instant it is finished: the look-ahead is dead and the reader hears the
     * un-prefetched pause this mechanism exists to remove.
     *
     * [stop] deliberately does not do this. A pause keeps its reservations so the work in
     * flight lands for the sentence the reader will resume on; a jump has no such
     * sentence.
     *
     * The engine generation is bumped as well, like [load] does, so a synthesis already
     * running for a skipped sentence abandons its result at its own generation check
     * instead of banking it after the clear.
     */
    fun discardLookAhead() {
        engineGeneration.incrementAndGet()
        synchronized(prefetchLock) { prefetchLedger.clearAll() }
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
        // Both engines are going, so no look-ahead can be served by what is already
        // queued: dropping the reservations as well as the clips keeps a later `prepare`
        // from refusing the very sentence it is about to read.
        synchronized(prefetchLock) { prefetchLedger.clearAll() }
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
        synchronized(prefetchLock) { prefetchLedger.clearAll() }
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

    /**
     * Visible to the tests on purpose: [PrefetchLedgerTest] pins the shipped caps against
     * these values, so a cap that moves without its relationship to the look-ahead window
     * moving too cannot pass unnoticed — which is exactly how the previous pair drifted
     * out of step with the window they were supposed to cover.
     */
    internal companion object {
        const val TAG = "NeuralTts"

        /**
         * Finished clips one role may hold, and how far one role may run ahead.
         *
         * These are two different limits because they bound two different things —
         * memory, and throughput — and conflating them is what made the read-aloud
         * stall. Measured on a 4-core emulator with this voice, translation off: the
         * reader heard 93.9 s of silence across a 16-sentence article while the engine
         * needed only 167 s to synthesise 161 s of audio. A single depth-of-two budget
         * stopped admitting work the moment two clips were banked, so the worker sat
         * idle through exactly the sentences that precede a long one, and the backlog
         * never got repaid.
         *
         * The first measurement of the split budget (hold two, queue four) cut that
         * silence to 56.6 s, and then showed what was left: replayed from the run's own
         * timestamps, the engine worker sat **idle for about 60 s of a 223 s run** — the
         * synthesis log of one task starting the instant the previous one ended, and then
         * nothing queued at all for tens of seconds while the reader waited. A queue cap
         * of four is only useful if four sentences are ever *asked for*, and the look-ahead
         * asks for two. So the depth stays at six and the request widens to four
         * sentences ahead (see [ListeningSession.prefetchAfter]), which is what keeps the
         * worker fed through a long sentence instead of idling through it.
         *
         * [PREFETCH_HOLD] matches the window instead of being one smaller. With four
         * sentences requested and three held, the fourth completion of every advance is
         * over cap and discarded the moment it lands — synthesis paid for and thrown
         * away, which is the one waste the deeper window exists to remove. Four clips of
         * a 28-second sentence is 11 MB at worst, against the 177 MB of model the voice
         * already holds. [PREFETCH_QUEUE] is the window twice over: enough for a full
         * window held *and* another in flight, which is as far ahead as the engine has
         * any business being.
         */
        const val PREFETCH_HOLD = 4
        const val PREFETCH_QUEUE = 8

        /**
         * How many times one look-ahead may step aside for a request the reader is
         * waiting on before it gives up and releases its reservation.
         *
         * Stepping aside has to be bounded: the worker thread can hand the engine to the
         * reader's request only if that request is actually queued, and there is a
         * microseconds-wide window where the reader has been counted but its task has not
         * landed. Without a bound, a look-ahead could in principle cycle for ever in that
         * window, and the engine would never run at all. Two attempts is already more than
         * the one that a queued request needs.
         */
        const val PREFETCH_DEFERRALS = 2

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
