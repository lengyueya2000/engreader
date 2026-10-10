package com.engreader.app.source

import android.util.Xml
import com.engreader.app.model.FeedItem
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Minimal RSS 2.0 / Atom reader.
 *
 * The stream is buffered before parsing, because a feed that is not well-formed XML
 * has to be read twice: once as written, once with the references a strict parser
 * rejects escaped. A feed is tens of kilobytes, so holding one costs nothing next to
 * the model assets in the package.
 */
object RssParser {

    private val DATE_FORMATS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm Z",
        "EEE, dd MMM yyyy HH:mm:ss z",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd HH:mm:ss",
    )

    /** A feed larger than this is not one worth holding in memory. */
    private const val MAX_FEED_BYTES = 8 * 1024 * 1024

    private class Result(val items: List<FeedItem>, val aborted: Boolean)

    fun parse(stream: InputStream, sourceId: String, sourceName: String): List<FeedItem> {
        val raw = readAll(stream)
        val strict = attempt(raw, sourceId, sourceName, decodeText = false)
        if (!strict.aborted) return strict.items

        // A bare `&` in a headline, or an HTML-only entity such as `&nbsp;`, is not
        // well-formed XML and stops the parser where it stands, losing every item
        // after it. Re-reading the same bytes with those references escaped recovers
        // them; whichever pass read more items is the one returned.
        val lenient = attempt(raw, sourceId, sourceName, decodeText = true)
        return if (lenient.items.size > strict.items.size) lenient.items else strict.items
    }

    private fun attempt(
        raw: ByteArray,
        sourceId: String,
        sourceName: String,
        decodeText: Boolean,
    ): Result {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        if (decodeText) {
            parser.setInput(StringReader(sanitize(decode(raw))))
        } else {
            // A null encoding lets the parser honour the XML declaration or the BOM.
            parser.setInput(ByteArrayInputStream(raw), null)
        }
        return collect(parser, sourceId, sourceName)
    }

    private fun collect(parser: XmlPullParser, sourceId: String, sourceName: String): Result {
        val items = mutableListOf<FeedItem>()
        var inItem = false
        var title = ""
        var link = ""
        var description = ""
        var pubDate = ""
        var image = ""
        var aborted = false

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (localName(parser)) {
                            "item", "entry" -> {
                                inItem = true
                                title = ""; link = ""; description = ""; pubDate = ""; image = ""
                            }
                            "title" -> if (inItem) title = readText(parser)
                            "link" -> if (inItem) {
                                val href = parser.getAttributeValue(null, "href")
                                link = href ?: readText(parser)
                            }
                            "description", "summary" -> if (inItem && description.isEmpty()) {
                                description = readText(parser)
                            }
                            "encoded" -> if (inItem && description.isEmpty()) {
                                description = readText(parser)
                            }
                            "pubdate", "published", "updated", "date" -> if (inItem && pubDate.isEmpty()) {
                                pubDate = readText(parser)
                            }
                            "content", "thumbnail" -> if (inItem && image.isEmpty()) {
                                image = parser.getAttributeValue(null, "url").orEmpty()
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (localName(parser) in setOf("item", "entry") && inItem) {
                            inItem = false
                            val cleanLink = link.trim()
                            val cleanTitle = Html.text(title)
                            if (cleanTitle.isNotEmpty() && cleanLink.isNotEmpty()) {
                                items += FeedItem(
                                    sourceId = sourceId,
                                    sourceName = sourceName,
                                    title = cleanTitle,
                                    // Feeds routinely embed escaped markup in the
                                    // summary ("<p>Leader says...</p>"), so strip tags
                                    // as well as decoding entities.
                                    summary = Html.summary(description),
                                    url = cleanLink,
                                    publishedAt = parseDate(pubDate),
                                    imageUrl = image,
                                )
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            // Malformed markup is common in the wild. Everything read before the
            // offending byte is still usable, so the items go back instead of an
            // exception the caller has no way to act on.
            aborted = true
        }
        return Result(items, aborted)
    }

    /**
     * The element name without its namespace prefix.
     *
     * Namespaces are deliberately not processed, so `parser.name` is the raw QName.
     * Matching it against bare names meant `media:content`, `media:thumbnail`,
     * `dc:date` and `content:encoded` never matched anything: the images and dates the
     * live catalog feeds carry in those elements were dropped without a trace.
     */
    private fun localName(parser: XmlPullParser): String = localNameOf(parser.name)

    /** The prefix-stripping half of [localName], split out so it can be tested. */
    internal fun localNameOf(qname: String): String =
        qname.substringAfterLast(':').lowercase()

    /**
     * Text content of the current element, or "" when it has none.
     *
     * `nextText()` requires the element to hold a single text node and throws when it
     * holds markup instead — an Atom `<summary type="xhtml"><p>…</p></summary>` did
     * that, and the exception escaped `parse` and lost the whole feed. Reading the
     * text node by node keeps the plain-text part and skips the markup.
     */
    private fun readText(parser: XmlPullParser): String {
        val depth = parser.depth
        val out = StringBuilder()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            when (event) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> out.append(parser.text)
                // Markup is stripped later, but the words on either side of a block
                // tag still need separating: without this, a summary written as
                // `<p>Hello</p><p>World</p>` came out as `HelloWorld`.
                XmlPullParser.START_TAG, XmlPullParser.END_TAG -> out.append(' ')
                XmlPullParser.END_DOCUMENT -> break
                else -> Unit
            }
            event = parser.next()
        }
        return out.toString()
    }

    private fun readAll(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (total <= MAX_FEED_BYTES) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** XML's five predefined entities and the numeric references, which stay as written. */
    private val XML_REFERENCE = Regex("&(?:amp|lt|gt|quot|apos|#[0-9]+|#[xX][0-9a-fA-F]+);")

    private val ENTITY_LIKE = Regex("&[A-Za-z][A-Za-z0-9]{1,31};")

    private val BARE_AMP = Regex("&(?!(?:amp|lt|gt|quot|apos|#[0-9]+|#[xX][0-9a-fA-F]+);)")

    /**
     * Rewrites the references a strict XML parser rejects.
     *
     * A known HTML entity becomes the numeric reference for the same character
     * (`&nbsp;` becomes `&#160;`), so the text survives intact; anything else opening
     * with an ampersand is escaped, which is what a bare `&` in a headline needs.
     */
    private fun sanitize(text: String): String {
        val mapped = ENTITY_LIKE.replace(text) { match ->
            val value = match.value
            if (XML_REFERENCE.matches(value)) {
                value
            } else {
                Html.entity(match.groupValues[1])?.let { numeric(it) } ?: value
            }
        }
        return BARE_AMP.replace(mapped, "&amp;")
    }

    private fun numeric(value: String): String =
        value.codePoints().toArray().joinToString("") { "&#$it;" }

    /** The encoding named in the XML declaration, falling back to UTF-8. */
    private fun decode(raw: ByteArray): String {
        val head = String(raw, 0, minOf(raw.size, 200), Charsets.ISO_8859_1)
        val declared = Regex("encoding\\s*=\\s*[\"']([A-Za-z0-9._-]+)[\"']")
            .find(head)?.groupValues?.get(1)
        val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() }
        return String(raw, charset ?: Charsets.UTF_8)
    }

    /**
     * Parses a feed date, rejecting values no feed could mean.
     *
     * The patterns are applied leniently, because feeds do write the wrong weekday
     * name. That tolerance also normalises nonsense — `2026-99-99` becomes a date in
     * 2034 — so the result is checked against a plausible range rather than trusted.
     */
    fun parseDate(raw: String): Long {
        val value = raw.trim()
        if (value.isEmpty()) return 0L
        val limit = System.currentTimeMillis() + 2L * 365 * 24 * 60 * 60 * 1000
        for (pattern in DATE_FORMATS) {
            val format = SimpleDateFormat(pattern, Locale.US)
            // A pattern with no zone means the feed is writing UTC; letting it default
            // to the device zone shifts every date by the reader's offset.
            if (!pattern.contains('Z') && !pattern.contains('X') && !pattern.endsWith(" z")) {
                format.timeZone = TimeZone.getTimeZone("UTC")
            }
            val time = try {
                format.parse(value)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
            if (time in 1..limit) return time
        }
        return 0L
    }
}
