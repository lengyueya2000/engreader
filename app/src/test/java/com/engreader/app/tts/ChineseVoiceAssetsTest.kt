package com.engreader.app.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The Chinese voice's files as they are actually packaged.
 *
 * [VoiceCatalogTest] pins the names; this pins the bytes behind them. The failure it
 * exists for is a silent one: a missing or misnamed file leaves the engine loading
 * nothing, `NeuralTts` logs a warning and the read-aloud falls back to English only —
 * the app still works, so nothing else in the suite would notice.
 *
 * It also checks that the front end is the pinyin one. The English voices are
 * phonemised by espeak-ng and their token table is an IPA inventory; the Chinese voice
 * needs the pinyin inventory and a character-to-pinyin lexicon instead. Swapping in an
 * espeak voice's files would leave the model reading nonsense that no assertion about
 * file sizes could catch.
 */
class ChineseVoiceAssetsTest {

    /**
     * The packaged asset directory, found by walking up from the test's working
     * directory.
     *
     * Gradle runs unit tests with the module as the working directory, but that is a
     * convention rather than a guarantee, so the search covers both layouts. A run that
     * finds nothing skips instead of failing: the assets are not on the test classpath,
     * and a checkout without them is a different problem from a broken voice.
     */
    private val voiceDir: File? = run {
        val relative = "assets/tts/${VoiceCatalog.translation.assetDir}"
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, "app/src/main/$relative").takeIf { it.isDirectory }?.let { return@run it }
            File(dir, "src/main/$relative").takeIf { it.isDirectory }?.let { return@run it }
            dir = dir.parentFile
        }
        null
    }

    private fun requireDir(): File {
        assumeTrue("the packaged assets are not in this checkout", voiceDir != null)
        return voiceDir!!
    }

    @Test
    fun `every file the Chinese engine opens is packaged and non-empty`() {
        val dir = requireDir()
        val expected = listOf("model.onnx", "tokens.txt", "lexicon.txt") + VoiceCatalog.ruleFstNames
        expected.forEach { name ->
            val file = File(dir, name)
            assertTrue("$name is missing from ${dir.path}", file.isFile)
            assertTrue("$name is empty", file.length() > 0L)
        }
        // Nothing else may be added without a decision: `NeuralTts` unpacks the whole
        // directory, so a stray file is dead weight in every APK.
        assertEquals(expected.toSet(), dir.listFiles().orEmpty().map { it.name }.toSet())
    }

    @Test
    fun `the token table is the pinyin inventory and not an IPA one`() {
        val tokens = File(requireDir(), "tokens.txt").readLines()
            .mapNotNull { it.trim().takeIf { line -> line.isNotEmpty() } }
            .map { it.substringBefore(' ') }
            .toSet()
        // The initials and finals a Mandarin syllable is built from. An espeak voice's
        // table holds IPA letters (`ð`, `ʃ`, `ə`) and none of these.
        listOf("zh", "ch", "sh", "ang", "eng", "iong", "uan").forEach { symbol ->
            assertTrue("the pinyin symbol $symbol is not in tokens.txt", symbol in tokens)
        }
        // `ü` is written `v` in this scheme, which is the giveaway that the table is
        // the pinyin one rather than the phoneme set the English voices share.
        assertTrue("the ü final is not spelled v", "v" in tokens)
    }

    @Test
    fun `the lexicon maps Chinese characters to pinyin`() {
        val lines = File(requireDir(), "lexicon.txt").bufferedReader().useLines { stream ->
            stream.filter { it.isNotBlank() }.take(200).toList()
        }
        assertTrue("the lexicon is too small to be a Chinese one", lines.isNotEmpty())
        // `一 y i 4 _`: the character, its initials and finals, the tone, then the word
        // boundary marker the engine's phoneme set ends with.
        val first = lines.first().trim().split(' ')
        assertTrue(
            "a lexicon line does not start with a Chinese character: $first",
            first[0].firstOrNull()?.isCjk() == true,
        )
        assertTrue("a lexicon line carries no pinyin: $first", first.size >= 4)
        assertTrue("a lexicon line carries no tone digit: $first", first[first.size - 2].toIntOrNull() in 1..5)
        assertEquals("the line does not end with the word-boundary marker", "_", first.last())
        assertTrue(
            "no line of the lexicon is a Chinese character",
            lines.count { line -> line.trim().firstOrNull()?.isCjk() == true } > lines.size / 2,
        )
    }

    @Test
    fun `the Chinese voice ships no espeak data of its own`() {
        // The two front ends are mutually exclusive: the engine is given either a
        // `dataDir` holding espeak-ng's pronunciation data or a pinyin lexicon, never
        // both. An espeak directory here would be unpacked on every install and never
        // opened.
        val dir = requireDir()
        assertTrue(
            "the Chinese voice carries an espeak-ng-data directory it cannot use",
            !File(dir, "espeak-ng-data").exists(),
        )
    }

    private fun Char.isCjk(): Boolean = this in '\u4E00'..'\u9FFF'
}
