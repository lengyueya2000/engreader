package com.engreader.app.source

import android.util.Xml
import com.engreader.app.model.FeedItem
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Minimal RSS 2.0 / Atom reader.
 *
 * Pull parsing is used instead of a DOM so a 150 KB feed costs no more memory than
 * the items we keep. Only the fields the UI shows are extracted.
 */
object RssParser {

    private val DATE_FORMATS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm Z",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd HH:mm:ss",
    )

    fun parse(stream: InputStream, sourceId: String, sourceName: String): List<FeedItem> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)

        val items = mutableListOf<FeedItem>()
        var inItem = false
        var title = ""
        var link = ""
        var description = ""
        var pubDate = ""
        var image = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase()) {
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
                    if (parser.name.lowercase() in setOf("item", "entry") && inItem) {
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
        return items
    }

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
                XmlPullParser.TEXT -> out.append(parser.text)
                XmlPullParser.CDSECT -> out.append(parser.text)
                XmlPullParser.END_DOCUMENT -> break
                else -> Unit
            }
            event = parser.next()
        }
        return out.toString()
    }

    fun parseDate(raw: String): Long {
        val value = raw.trim()
        if (value.isEmpty()) return 0L
        for (pattern in DATE_FORMATS) {
            try {
                return SimpleDateFormat(pattern, Locale.US).parse(value)?.time ?: 0L
            } catch (_: Exception) {
                // try the next pattern
            }
        }
        return 0L
    }
}
