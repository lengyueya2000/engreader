package com.engreader.app.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conditional-request bookkeeping.
 *
 * Each test uses its own URL: the cache is process-wide, and a shared key would let one
 * test's validators be read by the next.
 */
class HttpCacheTest {

    @Test
    fun `nothing is sent before a response has been seen`() {
        assertTrue(HttpCache.headersFor("https://example.test/feed-a").isEmpty())
    }

    @Test
    fun `both validators are sent once they are known`() {
        HttpCache.remember(
            "https://example.test/feed-b",
            etag = "\"abc\"",
            lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
        )
        val headers = HttpCache.headersFor("https://example.test/feed-b")
        assertEquals("\"abc\"", headers["If-None-Match"])
        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", headers["If-Modified-Since"])
    }

    @Test
    fun `a response with only one validator sends only that one`() {
        HttpCache.remember("https://example.test/feed-c", etag = "W/\"1\"", lastModified = null)
        val headers = HttpCache.headersFor("https://example.test/feed-c")
        assertEquals("W/\"1\"", headers["If-None-Match"])
        assertFalse(headers.containsKey("If-Modified-Since"))
    }

    @Test
    fun `a server that stops sending validators forgets the old ones`() {
        // Keeping a stale validator would make every later request ask about a version
        // the server no longer tracks, and a 304 for the wrong version loses an update.
        HttpCache.remember("https://example.test/feed-d", etag = "\"old\"", lastModified = null)
        HttpCache.remember("https://example.test/feed-d", etag = null, lastModified = null)
        assertTrue(HttpCache.headersFor("https://example.test/feed-d").isEmpty())
    }

    @Test
    fun `gzip is recognised however the header is cased`() {
        assertTrue(HttpCache.isGzip("gzip"))
        assertTrue(HttpCache.isGzip("GZIP"))
        assertTrue(HttpCache.isGzip("gzip, br"))
        assertFalse(HttpCache.isGzip("identity"))
        assertFalse(HttpCache.isGzip(""))
        assertFalse(HttpCache.isGzip(null))
    }
}
