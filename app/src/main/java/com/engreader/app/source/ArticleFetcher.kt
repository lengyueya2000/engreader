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

    suspend fun feed(source: Source): List<FeedItem>? =
        feed(source.feedUrl, source.id, source.name)

    /**
     * Fetches and parses a feed, or returns null when it has not changed.
     *
     * Null means the server answered `304 Not Modified`: the caller should keep the list
     * it already has. This is what makes a pull-to-refresh on an unchanged feed cost one
     * round trip and no parsing, no allocation and no database writes.
     */
    suspend fun feed(url: String, sourceId: String, sourceName: String): List<FeedItem>? =
        withContext(Dispatchers.IO) {
            val response = getConditional(url, accept = FEED_ACCEPT) ?: return@withContext null
            response.stream.use { RssParser.parse(it, sourceId, sourceName, url) }
        }

    /**
     * Fetches an article page and returns its extracted body. Falls back to the
     * feed summary when the page cannot be read, so a paywalled or JS-rendered
     * article still yields a short passage instead of an error screen.
     */
    suspend fun article(item: FeedItem): ArticleExtractor.Extracted = withContext(Dispatchers.IO) {
        try {
            val response = get(item.url, accept = "text/html,application/xhtml+xml")
            val bytes = response.stream.use { it.readCapped() }
            val html = PageCharset.decode(bytes, response.charset)
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

    private fun get(url: String, accept: String): Response =
        request(url, accept, redirectsLeft = 5, conditional = false)
            // Unreachable: only a conditional request can be answered with 304, and this
            // one sends no validator. Spelled out so the non-null return type is honest.
            ?: throw FetchException("服务器没有返回内容：$url")

    private fun getConditional(url: String, accept: String): Response? =
        request(url, accept, redirectsLeft = 5, conditional = true)

    /** A response body together with the charset its `Content-Type` named, if any. */
    private class Response(
        val stream: java.io.InputStream,
        val charset: java.nio.charset.Charset?,
    )

    /**
     * Requests [url], or returns null when the server says it has not changed.
     *
     * Null is only ever returned for a conditional request: it means "keep what you
     * have", and a caller that asked unconditionally has nothing to keep.
     */
    private fun request(
        url: String,
        accept: String,
        redirectsLeft: Int,
        conditional: Boolean = false,
    ): Response? {
        // A feed is remote input, so the link may be anything at all. Only http(s) can
        // be cast to `HttpURLConnection`; a `file:` or `ftp:` link would fail with a
        // `ClassCastException`, which reads as a crash rather than as an unreachable page.
        val target = runCatching { URL(url) }.getOrElse {
            throw FetchException("链接无法解析：$url", it)
        }
        val scheme = target.protocol?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw FetchException("不支持的链接协议（$scheme）：$url")
        }
        if (target.host.isNullOrBlank()) throw FetchException("链接没有主机名：$url")
        val connection = (target.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Language", "en-GB,en;q=0.9")
            // Asked for explicitly because `HttpURLConnection` only decompresses on its
            // own when the caller leaves this header alone; naming it makes the choice
            // visible and lets the size cap be applied to the decompressed bytes.
            setRequestProperty("Accept-Encoding", "gzip")
            if (conditional) {
                HttpCache.headersFor(url).forEach { (name, value) ->
                    setRequestProperty(name, value)
                }
            }
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
                    return request(next, accept, redirectsLeft - 1, conditional)
                }
                // Only reachable for a conditional request, which is the only kind that
                // sends a validator; without one the server has no way to answer this.
                code == 304 -> {
                    connection.disconnect()
                    return null
                }
                code in 200..299 -> {
                    if (conditional) {
                        HttpCache.remember(
                            url,
                            etag = connection.getHeaderField("ETag"),
                            lastModified = connection.getHeaderField("Last-Modified"),
                        )
                    }
                    val body = connection.inputStream.let { raw ->
                        if (HttpCache.isGzip(connection.getHeaderField("Content-Encoding"))) {
                            java.util.zip.GZIPInputStream(raw)
                        } else {
                            raw
                        }
                    }
                    val response = Response(body, charsetOf(connection))
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
     * The charset named by `Content-Type`, or null when the header named none.
     *
     * Null rather than a UTF-8 default so the caller can tell "the server said nothing"
     * from "the server said UTF-8": in the first case the document's own declaration is
     * worth more than a guess.
     */
    private fun charsetOf(connection: HttpURLConnection): java.nio.charset.Charset? {
        val header = connection.contentType ?: return null
        val name = CHARSET_PARAM.find(header)?.groupValues?.get(1) ?: return null
        return runCatching { java.nio.charset.Charset.forName(name) }.getOrNull()
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
     * Reads at most [MAX_BODY_BYTES] and returns the raw bytes.
     *
     * `readBytes()` would buffer the whole response, so a very large or hostile body
     * allocates until `OutOfMemoryError` — which is an `Error`, not an `Exception`,
     * so the caller's `catch` would not turn it into the summary fallback. Article
     * pages are far below this cap; the tail of a larger document is markup the
     * extractor would discard anyway.
     *
     * Bytes rather than text: the charset cannot be decided from the header alone, and
     * the document's own declaration has to be read before the body can be decoded.
     */
    private fun java.io.InputStream.readCapped(): ByteArray {
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
        return buffer.toByteArray()
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36 EngReader/1.0"

        private const val FEED_ACCEPT = "application/rss+xml, application/xml, text/xml, */*"

        /** 4 MB is far above any real article page and bounds the worst case. */
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024

        private val CHARSET_PARAM =
            Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9._-]+)", RegexOption.IGNORE_CASE)
    }
}

/**
 * The `ETag` and `Last-Modified` values of the last response per URL.
 *
 * In memory and per process: a validator is only useful while the app is running, since
 * the point of sending it is to avoid a parse and a database write on a refresh, and a
 * fresh launch has nothing cached to skip. A feed URL is a handful of entries, so the
 * map is bounded by how many sources the reader has opened rather than by anything the
 * network can do.
 */
internal object HttpCache {

    private class Validators(val etag: String?, val lastModified: String?)

    private val entries = java.util.concurrent.ConcurrentHashMap<String, Validators>()

    /** The conditional headers to send for [url], empty when nothing is known yet. */
    fun headersFor(url: String): Map<String, String> {
        val known = entries[url] ?: return emptyMap()
        val out = LinkedHashMap<String, String>(2)
        known.etag?.let { out["If-None-Match"] = it }
        known.lastModified?.let { out["If-Modified-Since"] = it }
        return out
    }

    /**
     * Remembers what a `200` said, so the next request can be conditional.
     *
     * A response carrying neither header replaces the entry with an empty one: keeping
     * a stale validator after a server stopped sending them would make every later
     * request ask about a version the server no longer tracks.
     */
    fun remember(url: String, etag: String?, lastModified: String?) {
        if (etag == null && lastModified == null) {
            entries.remove(url)
            return
        }
        entries[url] = Validators(etag, lastModified)
    }

    /** Whether a `Content-Encoding` header means the body is gzipped. */
    fun isGzip(contentEncoding: String?): Boolean =
        contentEncoding?.contains("gzip", ignoreCase = true) == true
}

/**
 * Decides how to decode a fetched page.
 *
 * Split out of [ArticleFetcher] because it needs no connection and no `Context`, and
 * getting it wrong is invisible until a reader sees mojibake in the middle of a
 * sentence.
 */
internal object PageCharset {

    /**
     * Decodes a page body with the charset the page itself implies.
     *
     * The `Content-Type` header is not enough. Plenty of pages declare their encoding
     * only in a `<meta charset>` tag, and assuming UTF-8 for an ISO-8859-1 or GBK page
     * turns every accented letter into a replacement character, which the extractor
     * then keeps as if it were the author's text. Precedence follows the HTML sniffing
     * order: a byte-order mark, then the transport header, then the document's own
     * declaration, then UTF-8.
     */
    fun decode(bytes: ByteArray, header: java.nio.charset.Charset?): String {
        val bom = bomCharset(bytes)
        if (bom != null) {
            // The mark is part of the encoding, not of the document.
            val skip = if (bom == Charsets.UTF_8) 3 else 2
            return String(bytes, skip, bytes.size - skip, bom)
        }
        val charset = header ?: declaredCharset(bytes) ?: Charsets.UTF_8
        return String(bytes, charset)
    }

    private fun bomCharset(bytes: ByteArray): java.nio.charset.Charset? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte() -> Charsets.UTF_8
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
        else -> null
    }

    /**
     * The charset from a `<meta charset>` or `<meta http-equiv>` tag, or null.
     *
     * Read as Latin-1 rather than UTF-8: the whole point is that the bytes are not
     * known to be UTF-8, and Latin-1 maps every byte to a character without failing.
     * The declaration is required to appear near the start of the document, so only
     * the opening is searched — and a long comment above it cannot hide it.
     */
    private fun declaredCharset(bytes: ByteArray): java.nio.charset.Charset? {
        val head = String(bytes, 0, minOf(bytes.size, SCAN_BYTES), Charsets.ISO_8859_1)
        val name = META_CHARSET.find(head)?.groupValues?.get(1) ?: return null
        return runCatching { java.nio.charset.Charset.forName(name) }.getOrNull()
    }

    /** How much of the document is searched for a `<meta charset>` declaration. */
    private const val SCAN_BYTES = 4096

    /** A `<meta charset>` or the `charset=` inside a `<meta http-equiv>` content. */
    private val META_CHARSET =
        Regex("(?i)<meta[^>]+charset\\s*=\\s*[\"']?([A-Za-z0-9._:-]+)")
}
