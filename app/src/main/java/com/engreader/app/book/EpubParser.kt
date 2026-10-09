package com.engreader.app.book

import java.util.zip.ZipInputStream

/**
 * Reads an EPUB into chapters.
 *
 * EPUB is a zip holding XHTML plus two index files: `META-INF/container.xml` names
 * the OPF package document, and the OPF's `spine` lists the reading order while its
 * `manifest` maps each id to a file. Chapters come from the spine rather than from
 * the file listing, because the zip's own order is arbitrary and includes the cover,
 * the stylesheet and every image.
 *
 * The NCX (or the EPUB 3 nav document) is used when present to split a spine item
 * that contains several chapters — plenty of public-domain conversions put the
 * whole book in one XHTML file with anchors — and to label the ones it covers. A
 * book without navigation still imports: it falls back to one chapter per spine
 * item, and then to `<h1>`/`<h2>` headings inside each item.
 *
 * Pure JVM: it reads bytes, not a `Context`, so the whole parser is unit-testable.
 */
object EpubParser {

    /** A book as it comes out of the file, before anything is stored. */
    data class Book(
        val title: String,
        val author: String,
        val language: String,
        val chapters: List<Chapter>,
        /** Raw bytes of the cover image, or null when the book has none. */
        val cover: ByteArray?,
        val coverExtension: String,
    )

    data class Chapter(val title: String, val text: String) {
        val wordCount: Int get() = BookText.wordCount(text)
    }

    /** One spine item, before it is split into chapters. */
    private data class Section(val id: String, val path: String, val html: String)

    /** A navigation target: which file, which anchor, and what to call it. */
    private data class NavPoint(val path: String, val anchor: String, val title: String)

    fun parse(bytes: ByteArray): Book {
        val entries = readEntries(bytes)
        if (entries.isEmpty()) throw BookFormat.Companion.Unsupported("这个 EPUB 是空的")

        val opfPath = containerRootFile(entries)
            ?: throw BookFormat.Companion.Unsupported("EPUB 缺少 META-INF/container.xml")
        val opf = entries[opfPath]?.toString(Charsets.UTF_8)
            ?: throw BookFormat.Companion.Unsupported("EPUB 里找不到 $opfPath")

        val base = opfPath.substringBeforeLast('/', "")
        val manifest = parseManifest(opf)
        val spine = parseSpine(opf)
        val metadata = parseMetadata(opf)

        val sections = spine.mapNotNull { id ->
            val item = manifest[id] ?: return@mapNotNull null
            if (item.mediaType.isNotEmpty() && !item.mediaType.contains("html")) return@mapNotNull null
            val path = resolve(base, item.href)
            val html = entries[path]?.toString(Charsets.UTF_8) ?: return@mapNotNull null
            Section(id, path, html)
        }
        if (sections.isEmpty()) {
            throw BookFormat.Companion.Unsupported("这个 EPUB 里没有可读的正文")
        }

        val nav = readNavigation(entries, base, manifest, opf)
        val chapters = if (nav.isEmpty()) {
            sections.flatMap { splitByHeadings(it) }
        } else {
            splitByNavigation(sections, nav)
        }
        val kept = dropEmpty(chapters)
        if (kept.isEmpty()) throw BookFormat.Companion.Unsupported("这个 EPUB 的正文是空的")

        val cover = readCover(entries, base, manifest, opf)
        return Book(
            title = metadata.title.ifBlank { opfPath.substringBeforeLast('.').substringAfterLast('/') },
            author = metadata.author,
            language = metadata.language,
            chapters = kept,
            cover = cover?.first,
            coverExtension = cover?.second.orEmpty(),
        )
    }

    // ------------------------------------------------------------------ zip

    /**
     * Unpacks the archive into memory.
     *
     * A whole-book map rather than a streaming pass: chapters are visited in spine
     * order, which is unrelated to the zip's order, and the cover has to be found
     * after the OPF is parsed. Books are a few megabytes, so holding them is cheaper
     * than the seek-and-reopen a `ZipFile` would need — and `ZipFile` needs a real
     * path, which a content URI does not provide.
     */
    private fun readEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                out[name] = zip.readBytes()
            }
        }
        return out
    }

    /** `META-INF/container.xml` → the OPF's path inside the archive. */
    private fun containerRootFile(entries: Map<String, ByteArray>): String? {
        val xml = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8)
            ?: entries.entries.firstOrNull { it.key.endsWith("container.xml") }?.value
                ?.toString(Charsets.UTF_8)
            ?: return null
        val full = Regex("<rootfile\\b[^>]*full-path\\s*=\\s*\"([^\"]+)\"").find(xml)?.groupValues?.get(1)
        return full?.takeIf { it.isNotBlank() }?.replace('\\', '/')
    }

    // ------------------------------------------------------------------ OPF

    private data class Item(val href: String, val mediaType: String)

    private fun parseManifest(opf: String): Map<String, Item> {
        val out = HashMap<String, Item>()
        val manifest = Regex("(?is)<manifest\\b.*?</manifest>").find(opf)?.value ?: return out
        for (tag in Regex("(?is)<item\\b[^>]*>").findAll(manifest)) {
            val attrs = tag.value
            val id = attr(attrs, "id") ?: continue
            val href = attr(attrs, "href") ?: continue
            out[id] = Item(href, attr(attrs, "media-type").orEmpty())
        }
        return out
    }

    private fun parseSpine(opf: String): List<String> {
        val spine = Regex("(?is)<spine\\b.*?</spine>").find(opf)?.value ?: return emptyList()
        return Regex("(?is)<itemref\\b[^>]*>").findAll(spine)
            .mapNotNull { attr(it.value, "idref") }
            .toList()
    }

    private data class Metadata(val title: String, val author: String, val language: String)

    private fun parseMetadata(opf: String): Metadata {
        val block = Regex("(?is)<metadata\\b.*?</metadata>").find(opf)?.value ?: opf
        return Metadata(
            title = elementText(block, "dc:title"),
            author = elementText(block, "dc:creator"),
            language = elementText(block, "dc:language"),
        )
    }

    private fun elementText(xml: String, name: String): String =
        Regex("(?is)<$name\\b[^>]*>(.*?)</$name\\s*>").find(xml)
            ?.groupValues?.get(1)
            ?.let { com.engreader.app.source.Html.decode(it).trim() }
            .orEmpty()

    // ----------------------------------------------------------- navigation

    /**
     * Reading-order targets from the NCX or the EPUB 3 nav document.
     *
     * Both are tried because a book has one or the other depending on which spec it
     * was built against, and many have both. The result is a flat list in document
     * order; nesting is dropped deliberately, since the reader's table of contents
     * is a single list and an indent level would only add noise.
     */
    private fun readNavigation(
        entries: Map<String, ByteArray>,
        base: String,
        manifest: Map<String, Item>,
        opf: String,
    ): List<NavPoint> {
        // EPUB 3 nav: the manifest item declaring properties="nav".
        val navHref = Regex("(?is)<item\\b[^>]*>").findAll(opf)
            .firstOrNull { attr(it.value, "properties")?.split(' ')?.contains("nav") == true }
            ?.let { attr(it.value, "href") }
        if (navHref != null) {
            val path = resolve(base, navHref)
            entries[path]?.toString(Charsets.UTF_8)?.let { html ->
                val points = parseNavDoc(html, path)
                if (points.isNotEmpty()) return points
            }
        }
        // EPUB 2 NCX, named by the spine's `toc` attribute or by media type.
        val ncxHref = manifest.entries
            .firstOrNull { it.value.mediaType.contains("ncx") }
            ?.value?.href
            ?: entries.keys.firstOrNull { it.endsWith(".ncx") }
        if (ncxHref != null) {
            val path = if (ncxHref.startsWith(base)) ncxHref else resolve(base, ncxHref)
            entries[path]?.toString(Charsets.UTF_8)?.let { xml ->
                return parseNcx(xml, path)
            }
        }
        return emptyList()
    }

    private fun parseNcx(xml: String, ncxPath: String): List<NavPoint> {
        val dir = ncxPath.substringBeforeLast('/', "")
        val out = mutableListOf<NavPoint>()
        for (point in Regex("(?is)<navPoint\\b.*?</navPoint>").findAll(xml)) {
            val body = point.value
            val label = Regex("(?is)<navLabel\\b.*?</navLabel>").find(body)?.value
                ?.let { elementText(it, "text") }
                ?: continue
            val src = Regex("(?is)<content\\b[^>]*>").find(body)
                ?.let { attr(it.value, "src") }
                ?: continue
            out += toNavPoint(src, label, dir)
        }
        return out
    }

    private fun parseNavDoc(html: String, navPath: String): List<NavPoint> {
        val dir = navPath.substringBeforeLast('/', "")
        // The toc nav specifically: a nav document may also hold a landmarks list.
        val nav = Regex("(?is)<nav\\b[^>]*>").findAll(html)
            .firstOrNull { attr(it.value, "epub:type") == "toc" || attr(it.value, "type") == "toc" }
            ?.value
            ?: html
        return Regex("(?is)<a\\b[^>]*>").findAll(nav).mapNotNull { tag ->
            val href = attr(tag.value, "href") ?: return@mapNotNull null
            val end = nav.indexOf("</a>", tag.range.last)
            if (end < 0) return@mapNotNull null
            val label = BookText.fromHtml(nav.substring(tag.range.last + 1, end))
                .replace("\n", " ").trim()
            if (label.isEmpty()) null else toNavPoint(href, label, dir)
        }.toList()
    }

    private fun toNavPoint(src: String, label: String, dir: String): NavPoint {
        val path = resolve(dir, src.substringBefore('#'))
        val anchor = src.substringAfter('#', "")
        return NavPoint(path, anchor, label)
    }

    /**
     * Splits the spine by the navigation targets that fall inside it.
     *
     * A target without an anchor names a whole file and starts a chapter at its top.
     * A target with one starts a chapter at the element carrying that id — which is
     * why the anchor's offset is looked up in the raw markup rather than in the
     * extracted text: extraction has already discarded the attribute.
     */
    private fun splitByNavigation(sections: List<Section>, nav: List<NavPoint>): List<Chapter> {
        val byPath = nav.groupBy { it.path }
        val out = mutableListOf<Chapter>()
        for (section in sections) {
            val targets = byPath[section.path].orEmpty()
                .mapNotNull { point ->
                    val at = if (point.anchor.isEmpty()) 0 else anchorOffset(section.html, point.anchor)
                    if (at == null) null else point to at
                }
                .sortedBy { it.second }
            if (targets.isEmpty()) {
                out += splitByHeadings(section)
                continue
            }
            // Anything before the first target (a cover, a title page) is kept as its
            // own chapter rather than discarded: it is the front matter, and losing it
            // would silently drop part of the book.
            if (targets.first().second > 0) {
                out += headingChapter(section, 0, targets.first().second)
            }
            targets.forEachIndexed { index, (point, at) ->
                val end = targets.getOrNull(index + 1)?.second ?: section.html.length
                out += headingChapter(
                    section, at, end,
                    BookText.cleanContentsLabel(point.title),
                )
            }
        }
        return out
    }

    /**
     * Offset of the element carrying `id="anchor"` (or `name="anchor"`), or null.
     *
     * The returned offset is the element's opening `<`, not the attribute: a chapter
     * that began mid-tag would keep the tag's tail (`id="pre">`) as its first line of
     * text, which then shows up in the reader as junk before the heading.
     */
    private fun anchorOffset(html: String, anchor: String): Int? {
        val escaped = Regex.escape(anchor)
        val match = Regex("(?is)\\b(?:id|name)\\s*=\\s*[\"']" + escaped + "[\"']").find(html)
            ?: return null
        val open = html.lastIndexOf('<', match.range.first)
        return if (open < 0) match.range.first else open
    }

    /**
     * Fallback split for a section with no navigation entry: one chapter per
     * top-level heading, or the whole section when it has none.
     */
    private fun splitByHeadings(section: Section): List<Chapter> {
        val headings = Regex("(?is)<h([12])\\b[^>]*>").findAll(section.html).map { it.range.first }.toList()
        if (headings.isEmpty()) return listOf(headingChapter(section, 0, section.html.length))
        val out = mutableListOf<Chapter>()
        if (headings.first() > 0) out += headingChapter(section, 0, headings.first())
        headings.forEachIndexed { index, at ->
            val end = headings.getOrNull(index + 1) ?: section.html.length
            out += headingChapter(section, at, end)
        }
        return out
    }

    private fun headingChapter(
        section: Section,
        start: Int,
        end: Int,
        overrideTitle: String? = null,
    ): Chapter {
        val slice = section.html.substring(start.coerceIn(0, section.html.length), end.coerceIn(start, section.html.length))
        val title = overrideTitle?.takeIf { it.isNotBlank() }
            ?: BookText.chapterHeading(slice)
            ?: ""
        return Chapter(title = title, text = BookText.fromHtml(slice))
    }

    /** Drops chapters that carry no prose, and titles the ones left untitled. */
    private fun dropEmpty(chapters: List<Chapter>): List<Chapter> {
        val kept = chapters.filter { it.wordCount >= MIN_CHAPTER_WORDS }
        return kept.mapIndexed { index, chapter ->
            if (chapter.title.isBlank()) chapter.copy(title = "第 ${index + 1} 节") else chapter
        }
    }

    // --------------------------------------------------------------- cover

    private fun readCover(
        entries: Map<String, ByteArray>,
        base: String,
        manifest: Map<String, Item>,
        opf: String,
    ): Pair<ByteArray, String>? {
        // EPUB 2 names it with `<meta name="cover" content="id">`; EPUB 3 marks the
        // manifest item with properties="cover-image". Books use one or the other.
        val metaId = Regex("(?is)<meta\\b[^>]*>").findAll(opf)
            .firstOrNull { attr(it.value, "name") == "cover" }
            ?.let { attr(it.value, "content") }
        val candidates = buildList {
            metaId?.let { id -> manifest[id]?.href?.let { add(it) } }
            manifest.entries
                .firstOrNull { it.value.mediaType.startsWith("image") && it.key.contains("cover", true) }
                ?.value?.href?.let { add(it) }
            Regex("(?is)<item\\b[^>]*>").findAll(opf)
                .firstOrNull { attr(it.value, "properties")?.split(' ')?.contains("cover-image") == true }
                ?.let { attr(it.value, "href") }?.let { add(it) }
        }
        for (href in candidates) {
            val bytes = entries[resolve(base, href)] ?: continue
            if (bytes.isEmpty()) continue
            val ext = href.substringAfterLast('.', "").lowercase().takeIf { it in IMAGE_EXTENSIONS }
                ?: sniffImage(bytes)
            if (ext != null) return bytes to ext
        }
        return null
    }

    /** Magic-byte check, for covers whose file name has no usable extension. */
    private fun sniffImage(b: ByteArray): String? = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpg"
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "png"
        b.size >= 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() -> "gif"
        b.size >= 12 && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() -> "webp"
        else -> null
    }

    // --------------------------------------------------------------- helpers

    /**
     * Resolves a manifest href against the OPF's directory.
     *
     * Hrefs are relative and may climb (`../images/x.jpg`) or be percent-encoded, so
     * this normalises both. The result is a key into the zip's entry map, which uses
     * plain `/`-separated names.
     */
    private fun resolve(base: String, href: String): String {
        val decoded = try {
            java.net.URLDecoder.decode(href, "UTF-8")
        } catch (_: Exception) {
            href
        }
        val raw = if (base.isEmpty()) decoded else "$base/$decoded"
        val out = ArrayDeque<String>()
        for (part in raw.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeLast()
                else -> out.addLast(part)
            }
        }
        return out.joinToString("/")
    }

    /** One attribute out of a tag's attribute text, quote-aware. */
    private fun attr(tag: String, name: String): String? =
        Regex("(?is)\\b$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").find(tag)?.let {
            it.groupValues[2].ifEmpty { it.groupValues[3] }
        }?.takeIf { it.isNotBlank() }

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp")

    /**
     * Fewest words a chapter needs to be worth listing.
     *
     * Front matter is a title page and a copyright notice; keeping those as chapters
     * makes the table of contents unusable, and nothing is lost because they are
     * reproduced inside the chapters they precede when a book has real navigation.
     */
    private const val MIN_CHAPTER_WORDS = 40
}
