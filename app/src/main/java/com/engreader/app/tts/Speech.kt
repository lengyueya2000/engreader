package com.engreader.app.tts

/** Engine lifecycle, so the UI can tell "no voice available" apart from "still loading". */
enum class SpeechState { Idle, Preparing, Ready, Unavailable }

/** Emitted when a queued utterance finishes, so the reader can advance its highlight. */
sealed interface SpeechEvent {
    data class Finished(val utteranceId: String) : SpeechEvent
    data class Failed(val utteranceId: String) : SpeechEvent
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

    /** Asset directory holding the espeak-ng pronunciation data shared by all voices. */
    const val ESPEAK_ASSET_DIR = "tts/espeak-ng-data"
}
