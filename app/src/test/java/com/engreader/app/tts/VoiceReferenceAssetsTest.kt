package com.engreader.app.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cross-checks the shipped reference clip against the parser.
 *
 * The unit tests above pin the parser against bytes this test file builds, which
 * proves the parser agrees with itself. This one reads the asset that actually ships
 * and checks it against numbers measured outside the app — so a clip replaced with a
 * different format, or a parser change that only shows up on real audio, fails here
 * rather than on a phone.
 *
 * The expected values come from decoding the same file with numpy:
 *
 *     format 1 (PCM), 1 channel, 24000 Hz, 16 bits, 163200 samples
 *
 * The clip is what the voice is cloned from, so its duration and level are part of
 * the voice: too short and the clone has too little to copy, silent and the clone has
 * nothing to copy at all. The length is also a cost — a cloning voice re-encodes its
 * prompt on every call, so it is paid once per sentence — which is why the shipped
 * clip is measured in `tools/voice/` rather than eyeballed.
 */
class VoiceReferenceAssetsTest {

    private val assetsDir = File("src/main/assets")

    private fun reference(voice: VoiceCatalog.BundledVoice): VoicePrompt {
        val wav = File(assetsDir, VoiceCatalog.referencePath(voice)).readBytes()
        val text = File(assetsDir, VoiceCatalog.referenceTextPath(voice)).readText().trim()
        val prompt = VoicePrompt.parse(wav, text)
        assertNotNull("${voice.id}'s reference clip did not parse", prompt)
        return requireNotNull(prompt)
    }

    @Test
    fun `the cloning voice ships a reference clip and its text`() {
        val voice = VoiceCatalog.ATTENBOROUGH
        assertTrue(
            "missing ${VoiceCatalog.referencePath(voice)}",
            File(assetsDir, VoiceCatalog.referencePath(voice)).isFile,
        )
        assertTrue(
            "missing ${VoiceCatalog.referenceTextPath(voice)}",
            File(assetsDir, VoiceCatalog.referenceTextPath(voice)).isFile,
        )
    }

    @Test
    fun `the reference clip decodes to the measured format`() {
        val prompt = reference(VoiceCatalog.ATTENBOROUGH)
        assertEquals(24000, prompt.sampleRate)
        assertEquals(163200, prompt.samples.size)
    }

    @Test
    fun `the reference clip is long enough to clone from and short enough to be one`() {
        // ZipVoice truncates a prompt past its limit, and a truncated prompt no longer
        // matches its text — which is the failure that makes the voice drift instead of
        // break. Six seconds was chosen as enough to carry the timbre while staying
        // well inside that limit.
        val prompt = reference(VoiceCatalog.ATTENBOROUGH)
        val seconds = prompt.samples.size.toDouble() / prompt.sampleRate
        assertTrue("prompt is only $seconds s", seconds >= 4.0)
        assertTrue("prompt is $seconds s, too long to be safe", seconds <= 12.0)
    }

    @Test
    fun `the reference clip is audible rather than silent or clipped`() {
        // A silent clip still parses, and the clone would then have no voice to copy.
        // A clipped one would teach the model a distortion.
        val prompt = reference(VoiceCatalog.ATTENBOROUGH)
        val peak = prompt.samples.maxOf { kotlin.math.abs(it) }
        val rms = kotlin.math.sqrt(
            prompt.samples.fold(0.0) { acc, s -> acc + s.toDouble() * s.toDouble() } /
                prompt.samples.size,
        )
        assertTrue("prompt is silent (rms $rms)", rms > 0.01)
        assertTrue("prompt is clipped (peak $peak)", peak <= 0.999f)
        assertTrue("prompt is barely audible (peak $peak)", peak > 0.05f)
    }

    @Test
    fun `the reference text is the words the clip says`() {
        // Not a transcription check — that needs an ASR model and lives in
        // tools/voice. What is checked here is that the text is a real sentence rather
        // than a placeholder or a fragment, because the engine aligns it against the
        // audio and a stub would misalign every synthesis.
        val text = File(assetsDir, VoiceCatalog.referenceTextPath(VoiceCatalog.ATTENBOROUGH))
            .readText().trim()
        assertTrue("reference text is empty", text.isNotEmpty())
        assertTrue("reference text looks like a placeholder: $text", text.length > 40)
        assertTrue("reference text has no sentence end: $text", text.endsWith("."))
        assertEquals(
            "reference text should be one line",
            1,
            text.lines().size,
        )
    }

    @Test
    fun `every cloning voice's assets are present`() {
        // Generalised so a second cloned voice cannot be added with only some of its
        // files: the failure mode is a voice that loads and then speaks in the wrong
        // voice, which is worse than one that refuses to load.
        //
        // This is also the check that catches a clone which never fetched the model. The
        // ZipVoice graphs are 186 MB and live as an asset on the GitHub Release rather
        // than in the repository, so a fresh checkout builds and speaks perfectly well
        // without them — by silently falling back to the system voice, which looks like
        // "the voice changed" rather than "the download is missing". Failing here, with
        // the command in the message, is the difference between a five-second fix and an
        // afternoon of wondering why the clone sounds like a satnav.
        val required = listOf(
            VoiceCatalog::tokensPath,
            VoiceCatalog::encoderPath,
            VoiceCatalog::decoderPath,
            VoiceCatalog::vocoderPath,
            VoiceCatalog::lexiconPath,
            VoiceCatalog::referencePath,
            VoiceCatalog::referenceTextPath,
        )
        val fetch = "install them with: python tools/voice/fetch_attenborough.py"
        VoiceCatalog.all
            .filter { it.engine == VoiceCatalog.VoiceEngine.ZipVoice }
            .forEach { voice ->
                required.forEach { path ->
                    val file = File(assetsDir, path(voice))
                    assertTrue("${voice.id} is missing ${path(voice)} — $fetch", file.isFile)
                    assertTrue("${voice.id}'s ${path(voice)} is empty", file.length() > 0)
                }
            }
    }
}
