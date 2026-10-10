package com.engreader.app.source

import android.content.Context
import com.engreader.app.model.FeedItem
import com.engreader.app.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Network access to the public feeds and article pages.
 *
 * Uses `HttpURLConnection` from the platform rather than adding an HTTP client
 * dependency: the app makes two kinds of request (a feed, and one page) and needs
 * no interceptors, so the standard library is enough.
 */
class ArticleFetcher(private val context: Context) {

    class FetchException(message: String, cause: Throwable? = null) : IOException(message, cause)

    suspend fun feed(source: Source): List<FeedItem> = withContext(Dispatchers.IO) {
        val response = get(source.feedUrl, accept = "application/rss+xml, application/xml, text/xml, */*")
        response.stream.use { RssParser.parse(it, source.id, source.name) }
    }

    suspend fun feed(url: String, sourceId: String, sourceName: String): List<FeedItem> =
        withContext(Dispatchers.IO) {
            val response = get(url, accept = "application/rss+xml, application/xml, text/xml, */*")
            response.stream.use { RssParser.parse(it, sourceId, sourceName) }
        }

    /**
     * Fetches an article page and returns its extracted body. Falls back to the
     * feed summary when the page cannot be read, so a paywalled or JS-rendered
     * article still yields a short passage instead of an error screen.
     */
    suspend fun article(item: FeedItem): ArticleExtractor.Extracted = withContext(Dispatchers.IO) {
        try {
            val response = get(item.url, accept = "text/html,application/xhtml+xml")
            val html = response.stream.use { it.readCapped(response.charset) }
            val extracted = ArticleExtractor.extract(html, fallbackTitle = item.title)
            if (extracted.isUsable) {
                extracted.copy(
                    title = extracted.title.ifBlank { item.title },
                    publishedAt = extracted.publishedAt.takeIf { it > 0 } ?: item.publishedAt,
                    summary = extracted.summary.ifBlank { item.summary },
                )
            } else {
                fallback(item)
            }
        } catch (e: Exception) {
            fallback(item)
        }
    }

    /** Feed-summary-only article, used when the page body is unavailable. */
    fun fallback(item: FeedItem): ArticleExtractor.Extracted {
        val summary = Html.text(item.summary)
        val paragraphs = summary.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.length > 30 }
            .chunked(3)
            .map { it.joinToString(" ") }
        return ArticleExtractor.Extracted(
            title = item.title,
            author = item.sourceName,
            publishedAt = item.publishedAt,
            summary = summary,
            paragraphs = paragraphs,
        )
    }

    private fun get(url: String, accept: String) = get(url, accept, redirectsLeft = 5)

    /** A response body together with the charset its `Content-Type` named. */
    private class Response(val stream: java.io.InputStream, val charset: java.nio.charset.Charset)

    private fun get(url: String, accept: String, redirectsLeft: Int): Response {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Language", "en-GB,en;q=0.9")
            setRequestProperty("Accept-Encoding", "identity")
        }
        // Every path out of here has to release the connection, including the ones
        // that throw: `responseCode`, `getHeaderField` and the read of the header can
        // all fail on a dead socket, and an unclosed connection stays in the pool
        // holding its socket until the process ends.
        var handedOff = false
        try {
            val code = connection.responseCode
            when {
                code in 300..399 -> {
                    if (redirectsLeft <= 0) throw FetchException("Too many redirects for $url")
                    val location = connection.getHeaderField("Location")
                        ?: throw FetchException("Redirect without Location for $url")
                    connection.disconnect()
                    val next = resolve(url, location)
                    return get(next, accept, redirectsLeft - 1)
                }
                code in 200..299 -> {
                    val response = Response(connection.inputStream, charsetOf(connection))
                    handedOff = true
                    return response
                }
                else -> throw FetchException("HTTP $code for $url")
            }
        } finally {
            if (!handedOff) connection.disconnect()
        }
    }

    /**
     * The charset named by `Content-Type`, or UTF-8.
     *
     * Not every site sends UTF-8: a page served as `text/html; charset=ISO-8859-1`
     * decoded as UTF-8 turns every accented letter into a replacement character, and
     * the extractor then keeps the mojibake as if it were the author's text.
     */
    private fun charsetOf(connection: HttpURLConnection): java.nio.charset.Charset {
        val header = connection.contentType ?: return Charsets.UTF_8
        val name = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9._-]+)", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1) ?: return Charsets.UTF_8
        return runCatching { java.nio.charset.Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }

    /**
     * Turns a `Location` header into an absolute http(s) URL.
     *
     * Only http and https are followed: a `file:` or other scheme would reach
     * `openConnection() as HttpURLConnection` and fail with a `ClassCastException`,
     * which reads as a crash rather than as "this page could not be fetched".
     */
    private fun resolve(base: String, location: String): String {
        val next = if (location.startsWith("http://") || location.startsWith("https://")) {
            location
        } else {
            URL(URL(base), location).toString()
        }
        if (!next.startsWith("http://") && !next.startsWith("https://")) {
            throw FetchException("Unsupported redirect to $next")
        }
        return next
    }

    /**
     * Reads at most [MAX_BODY_BYTES] and decodes with [charset].
     *
     * `readBytes()` would buffer the whole response, so a very large or hostile body
     * allocates until `OutOfMemoryError` — which is an `Error`, not an `Exception`,
     * so the caller's `catch` would not turn it into the summary fallback. Article
     * pages are far below this cap; the tail of a larger document is markup the
     * extractor would discard anyway.
     */
    private fun java.io.InputStream.readCapped(
        charset: java.nio.charset.Charset = Charsets.UTF_8,
    ): String {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(chunk)
            if (read <= 0) break
            val keep = minOf(read, MAX_BODY_BYTES - total)
            if (keep > 0) buffer.write(chunk, 0, keep)
            total += read
            if (total >= MAX_BODY_BYTES) break
        }
        return buffer.toString(charset.name())
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36 EngReader/1.0"

        /** 4 MB is far above any real article page and bounds the worst case. */
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024
    }
}
