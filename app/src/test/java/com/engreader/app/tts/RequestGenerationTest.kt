package com.engreader.app.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The token that says whether a request to speak has been superseded.
 *
 * [NeuralTts] cannot be instantiated without an Android context and the native
 * sherpa-onnx engine, so the rule the whole cancellation design rests on is pinned down at
 * the counter that decides it: a request whose token has been superseded reports its ending
 * as superseded, never as finished. Reporting it as finished is what let the completion of a
 * request the queue had already replaced be taken for the current one — the queue advanced
 * and the replacement was never heard.
 */
class RequestGenerationTest {

    @Test
    fun `a newer request supersedes the one in flight`() {
        val generation = RequestGeneration()
        val first = generation.next()
        val second = generation.next()
        assertTrue("the replaced request must know it is stale", generation.isStale(first))
        assertFalse("the newest request is the current one", generation.isStale(second))
    }

    @Test
    fun `a stop supersedes everything in flight without issuing a request`() {
        // `stop` is what a pause, a skip and a voice change all funnel through, so the token
        // of whatever was playing has to stop matching even though no new request is made.
        val generation = RequestGeneration()
        val token = generation.next()
        assertFalse(generation.isStale(token))
        generation.supersede()
        assertTrue(generation.isStale(token))
    }

    @Test
    fun `a token is unique per request`() {
        // Two requests for the same sentence are two requests: a counter that repeated a
        // token would make the older one look current again.
        val generation = RequestGeneration()
        val tokens = List(50) { generation.next() }
        assertTrue(tokens.distinct().size == tokens.size)
    }
}