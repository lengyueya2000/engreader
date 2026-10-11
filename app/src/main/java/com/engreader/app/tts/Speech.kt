package com.engreader.app.tts

/** Engine lifecycle, so the UI can tell "no voice available" apart from "still loading". */
enum class SpeechState { Idle, Preparing, Ready, Unavailable }

/**
 * Which of the two resident voices an utterance is for.
 *
 * The read-aloud speaks an English sentence and then, when the reader has asked for
 * it, that sentence's Chinese. The two go through different engines and different
 * front ends, so the role travels with the request rather than being inferred from
 * the text — a Chinese line and an English line are never interchangeable.
 */
enum class SpeechRole { Reading, Translation }

/** Emitted when a queued utterance finishes, so the reader can advance its highlight. */
sealed interface SpeechEvent {
    data class Finished(val utteranceId: String) : SpeechEvent
    data class Failed(val utteranceId: String) : SpeechEvent
}

/** A sentence, and which of the two voices reads it. */
data class SpokenUtterance(val index: Int, val role: SpeechRole)

/**
 * The ids the read-aloud queue tags its utterances with.
 *
 * One English sentence produces two utterances — `sentence:N` for the English and
 * `sentence:N:zh` for the Chinese that follows it — and the reader advances its queue by
 * decoding whichever one comes back. Building and reading the ids in one place is what
 * keeps the two ends in agreement: if the suffix a producer writes is not the suffix a
 * consumer strips, the queue stops at the end of the first pair and the reader hears
 * nothing after it, which is a silent failure rather than a visible one.
 */
object UtteranceId {

    private const val PREFIX = "sentence:"

    /** Marks the utterance that reads a sentence's Chinese, after its English. */
    const val TRANSLATION_SUFFIX = ":zh"

    fun english(index: Int): String = "$PREFIX$index"

    fun translation(index: Int): String = "$PREFIX$index$TRANSLATION_SUFFIX"

    /**
     * The sentence and role an id names, or null when it is not one of ours.
     *
     * The Chinese has to be recognised before the English: `5:zh` does not parse as a
     * number, so a reader that checked for the bare form first would drop every Chinese
     * utterance and stop the queue.
     */
    fun parse(id: String): SpokenUtterance? {
        if (!id.startsWith(PREFIX)) return null
        val body = id.removePrefix(PREFIX)
        if (body.endsWith(TRANSLATION_SUFFIX)) {
            val index = body.removeSuffix(TRANSLATION_SUFFIX).toIntOrNull() ?: return null
            return SpokenUtterance(index, SpeechRole.Translation)
        }
        val index = body.toIntOrNull() ?: return null
        return SpokenUtterance(index, SpeechRole.Reading)
    }
}

/**
 * The voices this app ships with.
 *
 * The reading voice is part of the APK rather than the device. System TTS varies
 * wildly between phones — some ship a single compact voice that reads like a
 * satnav, some have no English voice at all — and a reading app whose whole point is
 * listening to prose cannot leave that to chance. Piper VITS voices are small
 * enough to bundle (about 19-22 MB each) and sound like a narrator instead of a
 * synthesizer.
 *
 * Kept free of Android imports so the catalogue and the accent grouping can be
 * unit-tested; the engine that consumes it lives in [NeuralTts].
 */
object VoiceCatalog {

    /**
     * One bundled voice, with everything the picker needs to describe it.
     *
     * [assetDir] and [sid] exist because one model file can carry many speakers. The
     * VCTK graph holds 109, and the reader picks one of them by [sid]; the other two
     * voices are single-speaker models that leave [sid] at zero and [assetDir] equal
     * to their own [id].
     */
    data class BundledVoice(
        /** Stable handle stored in settings; also the default asset directory. */
        val id: String,
        /** `en-GB`, `en-US`. */
        val localeTag: String,
        /** `英式`, `美式`. */
        val accent: String,
        /** `男声`, `女声`. */
        val gender: String,
        /** Who the voice is, in the reader's language. */
        val name: String,
        /** What the voice is for, so two voices of one accent are told apart. */
        val blurb: String = "标准朗读",
        /** Directory under `assets/tts` holding the model, when it differs from [id]. */
        val assetDir: String = id,
        /** Speaker index inside a multi-speaker model. */
        val sid: Int = 0,
    ) {
        /** What the picker shows: `英式 · 男声`. */
        val title: String get() = "$accent · $gender"

        /** The second line, naming the voice and what makes it worth choosing. */
        val detail: String get() = "$name · $blurb · 22kHz 神经网络语音"
    }

    /**
     * Every voice in the APK.
     *
     * The British pair is deliberately two registers rather than two accents: one
     * neutral reading voice and one documentary narrator, so a reader can match the
     * voice to the text. Both come from the VCTK corpus or from single-speaker
     * models trained on public recordings — none of them is a real broadcaster. No
     * open model is trained on a living narrator's voice, so "documentary narrator"
     * here describes the measured register — pitch, pace, pausing — and not a
     * particular person.
     */
    val all: List<BundledVoice> = listOf(
        BundledVoice(
            id = "en_GB-alan-medium",
            localeTag = "en-GB",
            accent = "英式",
            gender = "男声",
            name = "Alan",
        ),
        BundledVoice(
            // VCTK speaker p274: male, 22, Essex. Chosen out of the corpus's
            // fifteen plain-English male speakers for having the most clause
            // pauses and the widest pitch movement of the group, which is what
            // separates a documentary read from a bulletin read.
            id = "en_GB-vctk-p274",
            localeTag = "en-GB",
            accent = "英式",
            gender = "男声",
            name = "Narrator",
            blurb = "纪录片解说",
            assetDir = "en_GB-vctk-medium",
            sid = 10,
        ),
        BundledVoice(
            id = "en_US-lessac-medium",
            localeTag = "en-US",
            accent = "美式",
            gender = "女声",
            name = "Lessac",
        ),
    )

    /** Accents offered in the picker, in display order. */
    val accents: List<Pair<String, String>> = listOf(
        "en-GB" to "英式",
        "en-US" to "美式",
    )

    /**
     * The voice the Chinese half of the read-aloud uses.
     *
     * Kept out of [all] on purpose: that list is what the accent picker offers, and
     * this voice is not a choice the reader makes about the English text — it is the
     * other half of one utterance, and it is fixed so the Chinese sounds the same
     * whichever English voice is picked.
     *
     * It is also the only voice that does not go through espeak-ng. sherpa-onnx's
     * Piper path hardcodes the espeak voice as `en-us` and offers no way to change it
     * — the native library contains no `cmn` string and no voice-name setter — so a
     * Chinese graph phonemised that way would be read with English rules. The models
     * built for this instead carry a pinyin lexicon and three rule FSTs (dates,
     * numbers, phone numbers), which is the front end [NeuralTts] sets up for it.
     */
    val translation = BundledVoice(
        id = "zh_CN-xiao_ya-medium",
        localeTag = "zh-CN",
        accent = "中文",
        gender = "女声",
        name = "晓雅",
        blurb = "中文朗读",
    )

    /** Voices for one accent; empty when the tag names an accent we do not ship. */
    fun forLocale(localeTag: String): List<BundledVoice> =
        all.filter { it.localeTag.equals(localeTag, ignoreCase = true) }

    fun byId(id: String): BundledVoice? =
        all.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /**
     * The voice to load for an accent.
     *
     * Falls back to the first voice rather than throwing: a settings file written by
     * a future version, or an accent tag with no bundled voice, should still read
     * aloud in some accent instead of failing.
     */
    fun defaultFor(localeTag: String): BundledVoice =
        forLocale(localeTag).firstOrNull() ?: all.first()

    /**
     * Resolves a stored voice id against the accent.
     *
     * The two are stored separately and can disagree after an accent change, so the
     * accent wins unless the stored voice actually belongs to it.
     */
    fun resolve(localeTag: String, voiceId: String): BundledVoice {
        val picked = byId(voiceId)
        return if (picked != null && picked.localeTag.equals(localeTag, ignoreCase = true)) {
            picked
        } else {
            defaultFor(localeTag)
        }
    }

    /** Relative asset path of a voice's ONNX graph. */
    fun modelPath(voice: BundledVoice): String = "tts/${voice.assetDir}/model.onnx"

    /** Relative asset path of a voice's phoneme-to-id table. */
    fun tokensPath(voice: BundledVoice): String = "tts/${voice.assetDir}/tokens.txt"

    /** Relative asset path of a voice's pinyin lexicon, for the voices that carry one. */
    fun lexiconPath(voice: BundledVoice): String = "tts/${voice.assetDir}/lexicon.txt"

    /**
     * The rule FSTs that belong beside a voice's lexicon, in the order sherpa-onnx
     * wants them: dates, numbers, phone numbers.
     *
     * They rewrite written forms into what the lexicon can pronounce — `1837` has to
     * become `一八三七` before the pinyin lookup can see it — which is the Chinese
     * counterpart of what [SpokenText] does for the English voices by hand.
     */
    val ruleFstNames: List<String> = listOf("date.fst", "number.fst", "phone.fst")

    /**
     * The rule FSTs as one comma-separated list of asset paths.
     *
     * Asset-relative rather than absolute for the same reason as [lexiconPath]: the
     * engine resolves them through the `AssetManager`, and a mix of the two modes is
     * rejected outright by sherpa-onnx.
     */
    fun ruleFstPaths(voice: BundledVoice): String =
        ruleFstNames.joinToString(",") { "tts/${voice.assetDir}/$it" }

    /** Asset directory holding the espeak-ng pronunciation data shared by all voices. */
    const val ESPEAK_ASSET_DIR = "tts/espeak-ng-data"
}
