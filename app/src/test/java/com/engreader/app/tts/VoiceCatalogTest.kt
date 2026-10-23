package com.engreader.app.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue is small but it is the only thing standing between a stored setting
 * and a voice that does not exist, so the resolution rules are worth pinning down.
 */
class VoiceCatalogTest {

    @Test
    fun `every accent in the picker has at least one voice`() {
        VoiceCatalog.accents.forEach { (tag, label) ->
            assertTrue(
                "$label ($tag) is offered but has no voice",
                VoiceCatalog.forLocale(tag).isNotEmpty(),
            )
        }
    }

    @Test
    fun `every voice belongs to an accent the picker offers`() {
        val offered = VoiceCatalog.accents.map { it.first }.toSet()
        VoiceCatalog.all.forEach { voice ->
            assertTrue("${voice.id} is unreachable from the picker", voice.localeTag in offered)
        }
    }

    @Test
    fun `voice ids are unique`() {
        val ids = VoiceCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `asset paths are namespaced under the voice's asset directory`() {
        val voice = VoiceCatalog.all.first()
        assertEquals("tts/${voice.assetDir}/model.onnx", VoiceCatalog.modelPath(voice))
        assertEquals("tts/${voice.assetDir}/tokens.txt", VoiceCatalog.tokensPath(voice))
    }

    @Test
    fun `a voice with no separate asset directory uses its own id`() {
        val voice = VoiceCatalog.byId("en_GB-alan-medium")!!
        assertEquals(voice.id, voice.assetDir)
        assertEquals("tts/en_GB-alan-medium/model.onnx", VoiceCatalog.modelPath(voice))
    }

    @Test
    fun `voices that share a model are told apart by speaker index`() {
        // One model file can carry many speakers, and the engine picks the speaker
        // per utterance. If two voices pointed at the same graph with the same
        // index, the picker would offer the same voice twice and switching between
        // them would be a no-op.
        VoiceCatalog.all.groupBy { it.assetDir }.forEach { (dir, sharing) ->
            if (sharing.size > 1) {
                assertEquals(
                    "voices sharing $dir must use different speakers",
                    sharing.size,
                    sharing.map { it.sid }.toSet().size,
                )
            }
        }
    }

    @Test
    fun `a multi-speaker voice names its speaker within the model`() {
        val narrator = VoiceCatalog.byId("en_GB-vctk-p274")!!
        assertEquals(10, narrator.sid)
        assertEquals("en_GB-vctk-medium", narrator.assetDir)
        assertTrue(narrator.assetDir != narrator.id)
    }

    @Test
    fun `every voice's speaker index is a plausible non-negative index`() {
        VoiceCatalog.all.forEach { voice ->
            assertTrue("${voice.id} has a negative speaker index", voice.sid >= 0)
        }
    }

    @Test
    fun `the British accent offers more than one register`() {
        // The point of the second voice: same accent, different delivery, so the
        // reader can match the voice to the text.
        val british = VoiceCatalog.forLocale("en-GB")
        assertEquals(3, british.size)
        assertEquals(british.size, british.map { it.blurb }.toSet().size)
        assertEquals(british.size, british.map { it.name }.toSet().size)
    }

    @Test
    fun `an accent resolves to its first voice`() {
        VoiceCatalog.accents.forEach { (tag, label) ->
            assertEquals(
                "$label should default to the first voice it offers",
                VoiceCatalog.forLocale(tag).first(),
                VoiceCatalog.defaultFor(tag),
            )
        }
    }

    @Test
    fun `a stored voice wins over the accent default`() {
        // The narrator is not the first British voice, so this is the case that
        // would break if resolution ignored the stored id.
        val narrator = VoiceCatalog.byId("en_GB-vctk-p274")!!
        assertEquals(narrator, VoiceCatalog.resolve("en-GB", narrator.id))
        assertTrue(narrator != VoiceCatalog.defaultFor("en-GB"))
    }

    @Test
    fun `an unknown accent falls back instead of failing`() {
        // A settings file from a future version, or a tag we stopped shipping.
        assertEquals(VoiceCatalog.all.first(), VoiceCatalog.defaultFor("en-NZ"))
    }

    @Test
    fun `a stored voice wins when it matches the accent`() {
        val american = VoiceCatalog.forLocale("en-US").first()
        assertEquals(american, VoiceCatalog.resolve("en-US", american.id))
    }

    @Test
    fun `a stored voice from the other accent is ignored`() {
        // This is the state left behind by switching accent, and by an older build
        // that stored a voice under a different accent.
        val british = VoiceCatalog.forLocale("en-GB").first()
        val resolved = VoiceCatalog.resolve("en-US", british.id)
        assertEquals("en-US", resolved.localeTag)
        assertTrue(resolved.id != british.id)
    }

    @Test
    fun `a blank or unknown voice id falls back to the accent default`() {
        assertEquals(VoiceCatalog.defaultFor("en-US"), VoiceCatalog.resolve("en-US", ""))
        assertEquals(VoiceCatalog.defaultFor("en-GB"), VoiceCatalog.resolve("en-GB", "no-such-voice"))
    }

    @Test
    fun `voice ids are matched case-insensitively`() {
        val voice = VoiceCatalog.all.first()
        assertEquals(voice, VoiceCatalog.byId(voice.id.uppercase()))
    }

    @Test
    fun `an unknown id is reported as unknown`() {
        assertNull(VoiceCatalog.byId("en_XX-nobody-medium"))
    }

    // -------------------------------------------------------- the cloning voice

    @Test
    fun `the cloning voice is the only one that needs a reference clip`() {
        // Every other voice is a speaker inside a trained graph and is complete with
        // its model and tokens. A ZipVoice voice is speaker-independent: without the
        // clip it would still synthesise, in a voice nobody chose, which is why the
        // engine refuses it rather than falling back.
        val cloning = VoiceCatalog.all.filter { it.engine == VoiceCatalog.VoiceEngine.ZipVoice }
        assertEquals(1, cloning.size)
        assertEquals(VoiceCatalog.ATTENBOROUGH, cloning.first())
        assertTrue(VoiceCatalog.ATTENBOROUGH in VoiceCatalog.all)
    }

    @Test
    fun `the cloning voice ships its own graph rather than sharing one`() {
        // It cannot share: the VCTK narrator and the cloning voice are different
        // architectures, and two voices sharing an asset directory are told apart by
        // speaker index, which a cloning voice does not have.
        val voice = VoiceCatalog.ATTENBOROUGH
        assertEquals(voice.id, voice.assetDir)
        assertTrue(VoiceCatalog.all.none { it !== voice && it.assetDir == voice.assetDir })
    }

    @Test
    fun `the cloning voice points at all four files it needs`() {
        // The three graphs plus the lexicon. A missing one is not caught at load
        // time by the native library for every combination — the vocoder in
        // particular is accepted and then produces noise — so the paths are pinned
        // here instead.
        val voice = VoiceCatalog.ATTENBOROUGH
        val dir = "tts/${voice.assetDir}"
        assertEquals("$dir/encoder.onnx", VoiceCatalog.encoderPath(voice))
        assertEquals("$dir/decoder.onnx", VoiceCatalog.decoderPath(voice))
        assertEquals("$dir/vocoder.onnx", VoiceCatalog.vocoderPath(voice))
        assertEquals("$dir/lexicon.txt", VoiceCatalog.lexiconPath(voice))
        assertEquals("$dir/tokens.txt", VoiceCatalog.tokensPath(voice))
    }

    @Test
    fun `the cloning voice's reference clip and its text sit beside the graph`() {
        // The pair has to be replaceable together, and the text has to be the words
        // the audio actually says: the engine aligns one against the other, and a
        // mismatch makes the voice drift rather than fail.
        val voice = VoiceCatalog.ATTENBOROUGH
        assertEquals("tts/${voice.assetDir}/reference.wav", VoiceCatalog.referencePath(voice))
        assertEquals("tts/${voice.assetDir}/reference.txt", VoiceCatalog.referenceTextPath(voice))
        assertEquals(
            VoiceCatalog.referencePath(voice).substringBeforeLast('/'),
            VoiceCatalog.referenceTextPath(voice).substringBeforeLast('/'),
        )
    }

    @Test
    fun `every path the cloning engine is given is relative to the asset root`() {
        // Same reason as the Chinese voice: an absolute path with an asset manager set
        // aborts the process inside the native library. The reference clip is the one
        // exception in the other direction — it is read by this app, not by the
        // engine — but it is still stored as a relative asset path.
        val voice = VoiceCatalog.ATTENBOROUGH
        val paths = listOf(
            VoiceCatalog.tokensPath(voice),
            VoiceCatalog.encoderPath(voice),
            VoiceCatalog.decoderPath(voice),
            VoiceCatalog.vocoderPath(voice),
            VoiceCatalog.lexiconPath(voice),
            VoiceCatalog.referencePath(voice),
            VoiceCatalog.referenceTextPath(voice),
        )
        paths.forEach { path ->
            assertTrue("$path is absolute", !path.startsWith("/"))
            assertTrue(
                "$path is not namespaced under the voice's asset directory",
                path.startsWith("tts/${voice.assetDir}/"),
            )
        }
    }

    @Test
    fun `a Piper voice is not given cloning paths`() {
        // The picker lists both kinds side by side, and a Piper voice handed a
        // reference clip would be a silent no-op rather than an error.
        val piper = VoiceCatalog.byId("en_GB-alan-medium")!!
        assertEquals(VoiceCatalog.VoiceEngine.Vits, piper.engine)
        val cloning = VoiceCatalog.all.filter { it.engine == VoiceCatalog.VoiceEngine.Vits }
        assertTrue(cloning.all { it.engine != VoiceCatalog.VoiceEngine.ZipVoice })
    }

    @Test
    fun `the cloning voice reports the sample rate it actually synthesises at`() {
        // 24 kHz, not the 22.05 kHz of the Piper voices, and the picker prints this
        // number — a stale value would misdescribe the voice in the UI.
        assertEquals(24, VoiceCatalog.ATTENBOROUGH.sampleRateKhz)
        assertTrue(VoiceCatalog.ATTENBOROUGH.detail.contains("24kHz"))
        VoiceCatalog.all.filter { it.engine == VoiceCatalog.VoiceEngine.Vits }.forEach { voice ->
            assertEquals("${voice.id} should be a 22.05 kHz Piper voice", 22, voice.sampleRateKhz)
        }
    }

    @Test
    fun `the cloning voice is reachable and selectable like any other`() {
        // It has to resolve through the same path a stored setting takes, or picking
        // it would silently fall back to the accent default on the next launch.
        val voice = VoiceCatalog.ATTENBOROUGH
        assertEquals(voice, VoiceCatalog.byId(voice.id))
        assertEquals(voice, VoiceCatalog.resolve("en-GB", voice.id))
        assertTrue(voice in VoiceCatalog.forLocale("en-GB"))
        assertTrue(voice !== VoiceCatalog.defaultFor("en-GB"))
    }

    @Test
    fun `the cloning voice's diffusion step count is pinned`() {
        // Four is the cheapest setting whose output still matches the speaker under an
        // ECAPA embedding; two steps costs 46% less and falls below the same-speaker
        // floor, i.e. it stops sounding like him. See VoiceCatalog.CLONE_STEPS.
        assertEquals(4, VoiceCatalog.CLONE_STEPS)
    }

    @Test
    fun `titles name the accent and the gender`() {
        VoiceCatalog.all.forEach { voice ->
            assertTrue(voice.title.contains(voice.accent))
            assertTrue(voice.title.contains(voice.gender))
        }
    }

    // -------------------------------------------------------- the Chinese voice

    @Test
    fun `the Chinese voice is not offered in the accent picker`() {
        // It is not a choice the reader makes about the English text, it is the other
        // half of one utterance. Offering it would also let it be picked as the voice
        // the English is read with, which its front end cannot do.
        assertTrue(VoiceCatalog.translation !in VoiceCatalog.all)
        assertNull(VoiceCatalog.byId(VoiceCatalog.translation.id))
        VoiceCatalog.accents.forEach { (tag, _) ->
            assertTrue(VoiceCatalog.forLocale(tag).none { it.id == VoiceCatalog.translation.id })
        }
    }

    @Test
    fun `the Chinese voice is Mandarin and is a speaker of its own`() {
        assertEquals("zh-CN", VoiceCatalog.translation.localeTag)
        assertEquals(0, VoiceCatalog.translation.sid)
        assertEquals(VoiceCatalog.translation.id, VoiceCatalog.translation.assetDir)
    }

    @Test
    fun `the Chinese voice's front end is looked for beside its model`() {
        // sherpa-onnx is handed absolute paths at runtime, but the relative asset paths
        // are what `NeuralTts` unpacks from, so a drift between the two would leave the
        // engine loading a lexicon that does not exist and reading nothing.
        val voice = VoiceCatalog.translation
        assertEquals("tts/${voice.assetDir}/lexicon.txt", VoiceCatalog.lexiconPath(voice))
        assertTrue(VoiceCatalog.lexiconPath(voice).startsWith(VoiceCatalog.modelPath(voice).substringBeforeLast('/')))
    }

    @Test
    fun `the rule FSTs are named in the order the engine wants them`() {
        // Dates before numbers: `1837` has to be recognised as a year before the number
        // rule can turn it into a cardinal, and phone numbers last because their pattern
        // is the loosest of the three.
        assertEquals(listOf("date.fst", "number.fst", "phone.fst"), VoiceCatalog.ruleFstNames)
    }

    @Test
    fun `every path the Chinese engine is given is relative to the asset root`() {
        // sherpa-onnx reads a relative path through the AssetManager and an absolute one
        // from the filesystem, and refuses the mixture — an absolute path with an asset
        // manager set is a fatal error inside the native library, not an exception the
        // app can catch. So a leading slash here is a process abort on the first
        // sentence, which is worth a test.
        val voice = VoiceCatalog.translation
        val paths = listOf(
            VoiceCatalog.modelPath(voice),
            VoiceCatalog.tokensPath(voice),
            VoiceCatalog.lexiconPath(voice),
        ) + VoiceCatalog.ruleFstPaths(voice).split(',')
        paths.forEach { path ->
            assertTrue("$path is absolute", !path.startsWith("/"))
            assertTrue("$path is not namespaced under the voice's asset directory",
                path.startsWith("tts/${voice.assetDir}/"))
        }
        assertEquals(VoiceCatalog.ruleFstNames.size, VoiceCatalog.ruleFstPaths(voice).split(',').size)
    }
}
