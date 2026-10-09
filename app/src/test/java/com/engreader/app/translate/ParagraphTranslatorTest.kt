package com.engreader.app.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An engine that records what it was asked for and answers from a script. */
private class FakeEngine(
    override val id: String,
    override val label: String = id,
    private val reply: (List<String>) -> List<String>,
) : TranslateEngine {
    var calls = 0
        private set
    var lastInput: List<String> = emptyList()
        private set

    override fun translate(paragraphs: List<String>): List<String> {
        calls++
        lastInput = paragraphs
        return reply(paragraphs)
    }
}

class ParagraphTranslatorTest {

    @Test
    fun `translates every paragraph and keeps the order`() = runBlocking {
        val engine = FakeEngine("fake") { it.map { p -> "译:$p" } }
        val translator = ParagraphTranslator(listOf(engine))
        val out = translator.translate(listOf("one", "two", "three"))
        assertEquals(listOf("译:one", "译:two", "译:three"), out)
        assertEquals("fake", translator.lastEngine)
    }

    @Test
    fun `falls back to the next engine when the first fails`() = runBlocking {
        val broken = FakeEngine("broken") { throw TranslationException("no route") }
        val working = FakeEngine("working") { it.map { p -> "好:$p" } }
        val out = ParagraphTranslator(listOf(broken, working)).translate(listOf("a", "b"))
        assertEquals(listOf("好:a", "好:b"), out)
        assertEquals(1, broken.calls)
        assertEquals(1, working.calls)
    }

    @Test
    fun `reuses cached translations and only sends the rest`() = runBlocking {
        val engine = FakeEngine("fake") { it.map { p -> "新:$p" } }
        val out = ParagraphTranslator(listOf(engine))
            .translate(listOf("one", "two", "three"), cached = listOf("旧:one", "", "旧:three"))
        assertEquals(listOf("旧:one", "新:two", "旧:three"), out)
        assertEquals(listOf("two"), engine.lastInput)
    }

    @Test
    fun `makes no request at all when the cache is complete`() = runBlocking {
        val engine = FakeEngine("fake") { error("should not be called") }
        val out = ParagraphTranslator(listOf(engine))
            .translate(listOf("one", "two"), cached = listOf("旧:one", "旧:two"))
        assertEquals(listOf("旧:one", "旧:two"), out)
        assertEquals(0, engine.calls)
    }

    @Test
    fun `discards a cache whose length no longer matches the body`() = runBlocking {
        val engine = FakeEngine("fake") { it.map { p -> "新:$p" } }
        val out = ParagraphTranslator(listOf(engine))
            .translate(listOf("one", "two"), cached = listOf("旧:one", "旧:two", "旧:three"))
        assertEquals(listOf("新:one", "新:two"), out)
        assertEquals(listOf("one", "two"), engine.lastInput)
    }

    @Test
    fun `keeps blank paragraphs blank and does not send them`() = runBlocking {
        val engine = FakeEngine("fake") { it.map { p -> "译:$p" } }
        val out = ParagraphTranslator(listOf(engine)).translate(listOf("one", "", "three"))
        assertEquals(listOf("译:one", "", "译:three"), out)
        assertEquals(listOf("one", "three"), engine.lastInput)
    }

    @Test
    fun `throws the last failure when every engine fails`() = runBlocking {
        val first = FakeEngine("first") { throw TranslationException("第一个挂了") }
        val second = FakeEngine("second") { throw TranslationException("第二个也挂了") }
        val error = runCatching {
            ParagraphTranslator(listOf(first, second)).translate(listOf("x"))
        }.exceptionOrNull()
        assertTrue(error is TranslationException)
        assertEquals("第二个也挂了", error!!.message)
    }

    @Test
    fun `treats an engine that returns the wrong count as a failure`() = runBlocking {
        val wrong = FakeEngine("wrong") { listOf("only one") }
        val good = FakeEngine("good") { it.map { p -> "好:$p" } }
        val out = ParagraphTranslator(listOf(wrong, good)).translate(listOf("a", "b"))
        assertEquals(listOf("好:a", "好:b"), out)
    }

    @Test
    fun `records no engine when nothing needed translating`() = runBlocking {
        val engine = FakeEngine("fake") { error("should not be called") }
        val translator = ParagraphTranslator(listOf(engine))
        val out = translator.translate(listOf("", "  "))
        assertEquals(listOf("", ""), out)
        assertNull(translator.lastEngine)
    }
}
