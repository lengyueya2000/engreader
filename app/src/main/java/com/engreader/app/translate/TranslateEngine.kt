package com.engreader.app.translate

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Raised when no engine could translate the text; the message is user-facing. */
class TranslationException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A translation backend. Implementations translate paragraph-for-paragraph. */
interface TranslateEngine {

    val id: String

    /** Shown to the user when the engine chain is reported on. */
    val label: String

    /**
     * Translates [paragraphs] in order, returning the same number of entries.
     *
     * Blank entries are passed through untouched. Implementations throw
     * [TranslationException] rather than returning a partial or padded list, so a
     * caller can move on to the next engine without inspecting the result.
     */
    fun translate(paragraphs: List<String>): List<String>
}

/**
 * Shared HTTP plumbing for the engines.
 *
 * The app already talks to the network with `HttpURLConnection` for feeds, and
 * these endpoints need nothing more than a form POST, so no HTTP client dependency
 * is added for two request shapes.
 */
internal object TranslateHttp {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0 Mobile Safari/537.36 EngReader/1.0"

    /** Form POST; returns the response body decoded as UTF-8. */
    fun postForm(url: String, form: String, timeoutMs: Int = 20_000): String {
        val bytes = form.toByteArray(Charsets.UTF_8)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Accept-Encoding", "identity")
            setFixedLengthStreamingMode(bytes.size)
        }
        return try {
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = runCatching { connection.errorStream?.readAll() }.getOrNull()
                throw TranslationException("翻译服务返回 HTTP $code${detail?.let { "：$it" }.orEmpty()}")
            }
            connection.inputStream.readAll()
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException("连不上翻译服务（${e.javaClass.simpleName}）", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readAll(): String = use { it.readBytes().toString(Charsets.UTF_8) }

    /**
     * `q=a&q=b` with each value percent-encoded.
     *
     * `URLEncoder` renders a space as `+`, which is only a space under the form
     * decoder. The endpoints accept both, but `%20` is unambiguous, so the `+` is
     * rewritten rather than relying on the server's parser being lenient.
     */
    fun multiQuery(values: List<String>): String =
        values.joinToString("&") { "q=" + encode(it) }

    fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/**
 * Google's endpoint, which accepts several `q` parameters in one request and
 * answers with one string per paragraph.
 *
 * This is the primary engine: it is the only one of the two that translates a whole
 * article in a single round trip, which keeps the wait down to a couple of seconds
 * and stays well inside the free service's per-request limits.
 */
class GoogleTranslateEngine : TranslateEngine {

    override val id = "google"
    override val label = "Google 翻译"

    override fun translate(paragraphs: List<String>): List<String> {
        val bodies = paragraphs.map { it.trim() }
        val results = arrayOfNulls<String>(bodies.size)
        for (range in chunkRanges(bodies)) {
            val texts = range.map { bodies[it] }
            // A long article can exceed the per-request budget, and the endpoint
            // occasionally refuses a batch outright. One paragraph at a time through
            // the sibling host is slower but almost always succeeds.
            val translated = try {
                batch(texts)
            } catch (_: TranslationException) {
                texts.map { if (it.isEmpty()) it else single(it) }
            }
            range.forEachIndexed { offset, index -> results[index] = translated[offset] }
        }
        return bodies.mapIndexed { index, text ->
            if (text.isEmpty()) text
            else results[index] ?: throw TranslationException("翻译服务没有返回译文")
        }
    }

    /** Groups paragraph indices so a request stays inside the endpoint's limits. */
    private fun chunkRanges(bodies: List<String>): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var bytes = 0
        bodies.forEachIndexed { index, text ->
            val size = text.toByteArray(Charsets.UTF_8).size + 8
            if (current.isNotEmpty() && (bytes + size > MAX_BATCH_BYTES || current.size >= MAX_BATCH_ITEMS)) {
                out += current
                current = mutableListOf()
                bytes = 0
            }
            current += index
            bytes += size
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    private fun batch(texts: List<String>): List<String> {
        val form = TranslateHttp.multiQuery(texts)
        val body = TranslateHttp.postForm("$BATCH_URL?client=dict-chrome-ex&sl=en&tl=zh-CN", form)
        val parsed = runCatching { MiniJson.parse(body) }.getOrElse {
            throw TranslationException("翻译服务返回了无法解析的内容", it)
        }
        val array = parsed as? List<*> ?: throw TranslationException("翻译服务返回了意外的格式")
        val strings = array.map { it as? String ?: throw TranslationException("翻译服务返回了意外的条目") }
        if (strings.size != texts.size) {
            throw TranslationException("翻译服务返回了 ${strings.size} 段，期望 ${texts.size} 段")
        }
        return strings
    }

    /** One paragraph through the per-segment endpoint, joining its sentences. */
    private fun single(text: String): String {
        val body = TranslateHttp.postForm(
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=zh-CN&dt=t",
            "q=" + TranslateHttp.encode(text),
        )
        val parsed = runCatching { MiniJson.parse(body) }.getOrElse {
            throw TranslationException("翻译服务返回了无法解析的内容", it)
        }
        val segments = (parsed as? List<*>)?.firstOrNull() as? List<*>
            ?: throw TranslationException("翻译服务返回了意外的格式")
        val joined = segments.mapNotNull { segment ->
            (segment as? List<*>)?.firstOrNull() as? String
        }.joinToString("")
        if (joined.isBlank()) throw TranslationException("翻译服务没有返回译文")
        return joined
    }

    private companion object {
        const val BATCH_URL = "https://clients5.google.com/translate_a/t"
        const val MAX_BATCH_BYTES = 4000
        const val MAX_BATCH_ITEMS = 12
    }
}

/**
 * Splits text into pieces no longer than [maxChars], preferring sentence ends.
 *
 * Needed by any engine with a per-request character cap: the pieces are translated
 * separately and concatenated, so the cut points should fall where a reader would
 * pause rather than mid-phrase.
 */
internal fun splitForLimit(text: String, maxChars: Int): List<String> {
    if (text.length <= maxChars) return listOf(text)
    val out = mutableListOf<String>()
    var buffer = StringBuilder()
    val sentences = text.split(Regex("(?<=[.!?。！？])\\s+"))
    for (sentence in sentences) {
        if (buffer.isNotEmpty() && buffer.length + sentence.length + 1 > maxChars) {
            out += buffer.toString()
            buffer = StringBuilder()
        }
        if (sentence.length > maxChars) {
            // One sentence longer than the cap: fall back to the last space before it.
            var rest = sentence
            while (rest.length > maxChars) {
                val cut = rest.lastIndexOf(' ', maxChars).takeIf { it > 0 } ?: maxChars
                out += rest.substring(0, cut)
                rest = rest.substring(cut).trimStart()
            }
            if (rest.isNotEmpty()) buffer.append(rest)
        } else {
            if (buffer.isNotEmpty()) buffer.append(' ')
            buffer.append(sentence)
        }
    }
    if (buffer.isNotEmpty()) out += buffer.toString()
    return out
}

/**
 * MyMemory's public API.
 *
 * It is the fallback rather than the primary because it caps a request at 500
 * characters and throttles anonymous callers, so a paragraph has to be sent in
 * sentence-sized pieces. It earns its place by being reachable where Google's
 * endpoints are not.
 */
class MyMemoryEngine : TranslateEngine {

    override val id = "mymemory"
    override val label = "MyMemory"

    override fun translate(paragraphs: List<String>): List<String> {
        val bodies = paragraphs.map { it.trim() }
        return bodies.map { text ->
            if (text.isEmpty()) text else joinPieces(splitForLimit(text, MAX_CHARS).map { request(it) })
        }
    }

    /**
     * Puts the pieces of one paragraph back together.
     *
     * Chinese needs no separator, so the pieces are joined directly. The exception is
     * a boundary where the service echoed the English back untranslated: the split
     * consumed the space between the two words, and gluing them together corrupts the
     * sentence rather than merely spacing it oddly.
     */
    internal fun joinPieces(pieces: List<String>): String {
        val out = StringBuilder()
        for (piece in pieces) {
            val last = out.lastOrNull()
            val first = piece.firstOrNull()
            if (last != null && first != null && isAsciiWordChar(last) && isAsciiWordChar(first)) {
                out.append(' ')
            }
            out.append(piece)
        }
        return out.toString()
    }

    private fun isAsciiWordChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    private fun request(text: String): String {
        val form = "q=" + TranslateHttp.encode(text) + "&langpair=en%7Czh-CN"
        val body = TranslateHttp.postForm(URL, form)
        val parsed = runCatching { MiniJson.parse(body) }.getOrElse {
            throw TranslationException("翻译服务返回了无法解析的内容", it)
        }
        val root = parsed as? Map<*, *> ?: throw TranslationException("翻译服务返回了意外的格式")
        val data = root["responseData"] as? Map<*, *>
        val translated = data?.get("translatedText") as? String
        if (translated.isNullOrBlank()) throw TranslationException("翻译服务没有返回译文")
        // MyMemory answers from its translation memory even after the anonymous daily
        // quota is spent, but flags those answers with a non-200 status. The payload is
        // still the real translation, so only the warning text itself is treated as a
        // failure; the status code alone is not.
        if (WARNING.containsMatchIn(translated)) throw TranslationException("翻译服务额度已用完")
        return translated
    }

    private companion object {
        const val URL = "https://api.mymemory.translated.net/get"
        const val MAX_CHARS = 450
        val WARNING = Regex(
            "LIMIT EXCEEDED|MYMEMORY WARNING|INVALID|QUERY LENGTH|NO QUERY SPECIFIED",
            RegexOption.IGNORE_CASE,
        )
    }
}
