package com.engreader.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsonTest {

    @Test
    fun `parses the batch response shape`() {
        val body = """["第一段。","第二段。"]"""
        val parsed = MiniJson.parse(body) as List<*>
        assertEquals(2, parsed.size)
        assertEquals("第一段。", parsed[0])
    }

    @Test
    fun `parses the per-segment response shape`() {
        val body = """[[["译文一。","Source one. ",null,null,3]],null,"en"]"""
        val outer = MiniJson.parse(body) as List<*>
        val segments = outer[0] as List<*>
        val first = segments[0] as List<*>
        assertEquals("译文一。", first[0])
        assertEquals("Source one. ", first[1])
    }

    @Test
    fun `decodes escapes and surrogate pairs`() {
        val parsed = MiniJson.parse("""["a\nb\t\"c\" \\ \u4e2d"]""") as List<*>
        assertEquals("a\nb\t\"c\" \\ 中", parsed[0])
    }

    @Test
    fun `decodes a surrogate pair from two escapes`() {
        // U+1F600 arrives as \uD83D\uDE00 and must survive as one code point.
        val parsed = MiniJson.parse("""["\uD83D\uDE00"]""") as List<*>
        val text = parsed[0] as String
        assertEquals(1, text.codePointCount(0, text.length))
    }

    @Test
    fun `parses the mymemory response and its numbers`() {
        val body = """{"responseData":{"translatedText":"你好","match":0.85},"responseStatus":200}"""
        val root = MiniJson.parse(body) as Map<*, *>
        val data = root["responseData"] as Map<*, *>
        assertEquals("你好", data["translatedText"])
        assertEquals(0.85, data["match"] as Double, 0.0001)
        assertEquals(200.0, root["responseStatus"] as Double, 0.0001)
    }

    @Test
    fun `parses literals and nested empties`() {
        val root = MiniJson.parse("""{"a":null,"b":true,"c":false,"d":[],"e":{}}""") as Map<*, *>
        assertEquals(null, root["a"])
        assertEquals(true, root["b"])
        assertEquals(false, root["c"])
        assertEquals(emptyList<Any>(), root["d"])
        assertEquals(emptyMap<String, Any>(), root["e"])
    }

    @Test(expected = MiniJson.JsonException::class)
    fun `rejects a truncated array`() {
        MiniJson.parse("""["one","two"""")
    }

    @Test(expected = MiniJson.JsonException::class)
    fun `rejects a bare word`() {
        MiniJson.parse("error")
    }
}

class SplitForLimitTest {

    @Test
    fun `short text passes through untouched`() {
        assertEquals(listOf("A short line."), splitForLimit("A short line.", 100))
    }

    @Test
    fun `splits on sentence ends under the cap`() {
        val text = "First sentence here. Second sentence here. Third sentence here."
        val pieces = splitForLimit(text, 45)
        assertTrue("expected more than one piece", pieces.size > 1)
        pieces.forEach { assertTrue("piece too long: ${it.length}", it.length <= 45) }
        assertEquals(text.replace(" ", ""), pieces.joinToString("") { it.replace(" ", "") })
    }

    @Test
    fun `breaks a single over-long sentence on a space`() {
        val text = "word ".repeat(60).trim()
        val pieces = splitForLimit(text, 40)
        assertTrue(pieces.size > 1)
        pieces.forEach { assertTrue("piece too long: ${it.length}", it.length <= 40) }
        assertTrue("no piece should cut a word", pieces.none { it.startsWith(" ") || it.endsWith(" ") })
    }

    @Test
    fun `never returns an empty piece`() {
        val pieces = splitForLimit("Sentence one. Sentence two. ", 12)
        assertTrue(pieces.all { it.isNotBlank() })
    }
}

/**
 * Rejoining the pieces MyMemory answers in.
 *
 * The service caps a request at 500 characters, so one paragraph is sent as several
 * sentence-sized pieces. Chinese needs no separator between them, but a boundary
 * where the service echoed the English back untranslated does: the split consumed the
 * space between the two words, and gluing them produced `thegovernment`.
 */
class MyMemoryJoinTest {

    @Test
    fun `chinese pieces are joined without a separator`() {
        assertEquals("中国政府宣布了这一决定", MyMemoryEngine().joinPieces(listOf("中国政府", "宣布了", "这一决定")))
    }

    @Test
    fun `two ascii words split apart are not glued together`() {
        assertEquals("the government", MyMemoryEngine().joinPieces(listOf("the", "government")))
    }

    @Test
    fun `an ascii word before chinese needs no separator`() {
        // Chinese sets no space between words, so a boundary that is only half ASCII
        // is left alone; adding one here would put a gap inside a Chinese phrase.
        assertEquals("the政府", MyMemoryEngine().joinPieces(listOf("the", "政府")))
    }

    @Test
    fun `punctuation does not attract a separator`() {
        assertEquals("政府，宣布", MyMemoryEngine().joinPieces(listOf("政府", "，宣布")))
        assertEquals("end.开始", MyMemoryEngine().joinPieces(listOf("end.", "开始")))
    }

    @Test
    fun `a single piece is returned unchanged`() {
        assertEquals("中国政府", MyMemoryEngine().joinPieces(listOf("中国政府")))
    }
}
