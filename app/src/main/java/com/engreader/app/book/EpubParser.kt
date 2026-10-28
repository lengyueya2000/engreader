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
        /**
         * Tokenised once and remembered: the import path filters on it and then
         * reports it, and recounting re-tokenised every chapter of the book.
         */
        val wordCount: Int by lazy { BookText.wordCount(text) }
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
        val opf = entries[opfPath]?.let { decodeXml(it) }
            ?: throw BookFormat.Companion.Unsupported("EPUB 里找不到 $opfPath")

        val base = opfPath.substringBeforeLast('/', "")
        val manifest = parseManifest(opf)
        val spine = parseSpine(opf)
        val metadata = parseMetadata(opf)

        val sections = spine.mapNotNull { id ->
            val item = manifest[id] ?: return@mapNotNull null
            if (item.mediaType.isNotEmpty() && !item.mediaType.contains("html")) return@mapNotNull null
            val path = resolve(base, item.href)
            val html = entries[path]?.let { decodeXml(it) } ?: return@mapNotNull null
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
     *
     * The total is capped because a zip can expand far beyond its own size: a few
     * hundred kilobytes of highly repetitive deflate streams become gigabytes, and
     * the `OutOfMemoryError` that follows is an `Error`, so it escapes the import
     * path's `catch (Exception)` and takes the app down instead of reporting a bad
     * file. Real books are far below this. Each entry is capped as well, because the
     * total alone left the first entry free to be as large as the whole budget, and
     * an image that overflows the per-entry cap is dropped rather than refused: a
     * scans-heavy EPUB is still worth its text, and the reader only shows the cover.
     */
    private fun readEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        var total = 0
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                val remaining = MAX_UNPACKED_BYTES - total
                if (remaining <= 0) {
                    throw BookFormat.Companion.Unsupported("这个 EPUB 解压后过大，可能不是电子书")
                }
                val image = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
                val limit = minOf(if (image) MAX_IMAGE_BYTES else MAX_ENTRY_BYTES, remaining)
                // Read through the cap rather than with `readBytes()`: the whole point
                // is to stop before the allocation happens, and a single entry used to
                // be enough to exhaust the heap on its own.
                val content = zip.readCapped(limit, entry.size)
                if (content == null) {
                    if (!image) {
                        throw BookFormat.Companion.Unsupported("这个 EPUB 里有一个条目过大，可能不是电子书")
                    }
                    // Still charged against the archive's budget: a bomb of oversized
                    // images would otherwise be decompressed for as long as it wanted.
                    total += limit
                    continue
                }
                total += content.size
                out[name] = content
            }
        }
        return out
    }

    /**
     * The entry's bytes, or null once [limit] is passed.
     *
     * A growable array rather than a `ByteArrayOutputStream`: `toByteArray()` holds the
     * stream's buffer and its own full copy at the same time, so one large entry needed
     * over twice its size at once — a single 250 MB entry measured 511 MB of a 512 MB
     * heap although the archive's own ceiling was 256 MB. [declared] is the entry's
     * size field, which sizes the array up front when the generator wrote one.
     */
    private fun ZipInputStream.readCapped(limit: Int, declared: Long): ByteArray? {
        var out = ByteArray(if (declared in 1..limit.toLong()) declared.toInt() else minOf(limit, INITIAL_ENTRY_BYTES))
        var length = 0
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val read = read(chunk)
            if (read < 0) break
            if (length + read > limit) return null
            val needed = length + read
            if (needed > out.size) {
                var grown = out.size.toLong() * 2
                if (grown < needed) grown = needed.toLong()
                out = out.copyOf(minOf(grown, limit.toLong()).toInt())
            }
            System.arraycopy(chunk, 0, out, length, read)
            length = needed
        }
        return if (length == out.size) out else out.copyOf(length)
    }

    /** `META-INF/container.xml` → the OPF's path inside the archive. */
    private fun containerRootFile(entries: Map<String, ByteArray>): String? {
        val xml = entries["META-INF/container.xml"]?.let { decodeXml(it) }
            ?: entries.entries.firstOrNull { it.key.endsWith("container.xml") }?.value
                ?.let { decodeXml(it) }
            ?: return null
        // Single quotes are as legal as double ones here, and a generator that emits
        // them used to make the whole book unreadable for want of one character.
        val full = Regex("<rootfile\\b[^>]*>").findAll(xml)
            .mapNotNull { attr(it.value, "full-path") }
            .firstOrNull()
        return full?.takeIf { it.isNotBlank() }?.replace('\\', '/')
    }

    /**
     * Decodes an XML file using the charset its declaration names.
     *
     * An OPF or XHTML part is not necessarily UTF-8 — books from older converters are
     * windows-1252 or UTF-16, and their declaration says so. Reading those as UTF-8
     * mangles every accented character before the extractor ever sees it.
     *
     * A byte-order mark is decided from the bytes, before the declaration is read. Java's
     * UTF-8 decoder does not remove one, so a BOM'd file used to keep the character and
     * it opened the first word of the first paragraph — `\uFEFFCHAPTER` is not a word
     * the tokenizer or the dictionary recognises. The Latin-1 preview cannot be used to
     * find it either: the three UTF-8 bytes decode to `ï»¿`, not to U+FEFF, which is why
     * the test that was here never matched anything.
     */
    private fun decodeXml(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        // UTF-16 is legal in XHTML 1.1, and its declaration cannot be read from the
        // Latin-1 preview below because every character is interleaved with a NUL
        // (`<\0?\0x\0m\0l\0`), so the BOM is the only thing that can decide. Without
        // this the bytes decoded as UTF-8, the markup became text full of U+FFFD and
        // NUL, and the chapter imported as unreadable gibberish.
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        val declared = Regex("encoding\\s*=\\s*[\"']([A-Za-z0-9._-]+)[\"']")
            .find(head)?.groupValues?.get(1) ?: return String(bytes, Charsets.UTF_8)
        val charset = runCatching { java.nio.charset.Charset.forName(declared) }.getOrNull()
            ?: return String(bytes, Charsets.UTF_8)
        return String(bytes, charset)
    }

    // ------------------------------------------------------------------ OPF

    private data class Item(val href: String, val mediaType: String)

    private fun parseManifest(opf: String): Map<String, Item> {
        val out = HashMap<String, Item>()
        val manifest = elementBody(opf, "manifest") ?: return out
        for (tag in Regex("(?is)<item\\b[^>]*>").findAll(manifest)) {
            val attrs = tag.value
            val id = attr(attrs, "id") ?: continue
            val href = attr(attrs, "href") ?: continue
            out[id] = Item(href, attr(attrs, "media-type").orEmpty())
        }
        return out
    }

    private fun parseSpine(opf: String): List<String> {
        val spine = elementBody(opf, "spine") ?: return emptyList()
        return Regex("(?is)<itemref\\b[^>]*>").findAll(spine)
            .mapNotNull { attr(it.value, "idref") }
            .toList()
    }

    private data class Metadata(val title: String, val author: String, val language: String)

    private fun parseMetadata(opf: String): Metadata {
        val block = elementBody(opf, "metadata") ?: opf
        return Metadata(
            title = elementText(block, "dc:title"),
            author = elementText(block, "dc:creator"),
            language = elementText(block, "dc:language"),
        )
    }

    private fun elementText(xml: String, name: String): String =
        elementBody(xml, name)
            ?.let { com.engreader.app.source.Html.decode(it).trim() }
            .orEmpty()

    /**
     * The content of the first `<name>` element, or null when it never closes.
     *
     * Written as two scans rather than `(?s)<name\b.*?</name>`: a lazy dot makes the
     * engine search to the end of the document from every opening tag when the element
     * is unclosed, which a crafted OPF can arrange thousands of times.
     */
    private fun elementBody(xml: String, name: String): String? {
        val open = Regex("(?is)<$name\\b[^>]*>").find(xml) ?: return null
        val close = Regex("(?is)</$name\\s*>").find(xml, open.range.last + 1) ?: return null
        return xml.substring(open.range.last + 1, close.range.first)
    }

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
            entries[path]?.let { decodeXml(it) }?.let { html ->
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
            entries[path]?.let { decodeXml(it) }?.let { xml ->
                return parseNcx(xml, path)
            }
        }
        return emptyList()
    }

    /**
     * Navigation patterns, compiled once.
     *
     * These were built inside the loops that used them, so a book with a thousand
     * navPoints compiled a thousand pairs of `Regex` objects to read one index file.
     */
    private val NAV_POINT_TAG = Regex("(?is)</?navPoint\\b[^>]*>")
    private val NAV_LABEL = Regex("(?is)<navLabel\\b.*?</navLabel>")
    private val CONTENT_TAG = Regex("(?is)<content\\b[^>]*>")
    private val NAV_LINK = Regex("(?is)<a\\b[^>]*>")
    private val NAV_OPEN = Regex("(?is)<nav\\b[^>]*>")

    /**
     * Every navPoint, including the ones nested inside another.
     *
     * A lazy `<navPoint>.*?</navPoint>` stops at the *first* closing tag, so for a
     * book whose NCX nests parts and chapters it read only the outer entries: a
     * "Part One" that contained "Chapter 1" and "Chapter 2" yielded the part and the
     * second chapter, and the first chapter disappeared from the book. A stack of
     * open tags gives every point, each with its own label.
     */
    private fun parseNcx(xml: String, ncxPath: String): List<NavPoint> {
        val dir = ncxPath.substringBeforeLast('/', "")
        val out = mutableListOf<NavPoint>()
        val open = ArrayDeque<Int>()
        for (tag in NAV_POINT_TAG.findAll(xml)) {
            if (tag.value.startsWith("</")) {
                val start = open.removeLastOrNull() ?: continue
                // Searched within the navPoint's own span rather than in a copy of it:
                // a crafted NCX nests its points so that one span reaches the end of the
                // document, and `substring` copied every one of those spans.
                val end = tag.range.first
                val label = NAV_LABEL.findTextBetween(xml, start, end)
                    ?.let { elementText(it, "text") }
                    ?: continue
                val src = CONTENT_TAG.findTextBetween(xml, start, end)
                    ?.let { attr(it, "src") }
                    ?: continue
                out += toNavPoint(src, label, dir)
                // A reference book's NCX can hold thousands of points, and each one
                // becomes a chapter, so the list stops at the same cap the heading
                // split uses.
                if (out.size >= MAX_CHAPTERS) break
            } else {
                open.addLast(tag.range.last + 1)
            }
        }
        return out
    }

    private fun parseNavDoc(html: String, navPath: String): List<NavPoint> {
        val dir = navPath.substringBeforeLast('/', "")
        // The toc nav specifically: a nav document may also hold a landmarks list.
        // The *body* of the element is what is wanted — the links are between the
        // opening and closing tags. Matching only the opening tag and then searching
        // it for `<a>` found nothing, so every EPUB 3 nav document parsed as empty and
        // the book fell through to NCX or to heading detection.
        val nav = tocNavBody(html) ?: html
        return NAV_LINK.findAll(nav).mapNotNull { tag ->
            val href = attr(tag.value, "href") ?: return@mapNotNull null
            val end = nav.indexOf("</a>", tag.range.last)
            if (end < 0) return@mapNotNull null
            val label = BookText.fromHtml(nav.substring(tag.range.last + 1, end))
                .replace("\n", " ").trim()
            if (label.isEmpty()) null else toNavPoint(href, label, dir)
        }.take(MAX_CHAPTERS).toList()
    }

    /**
     * The inside of the `epub:type="toc"` nav element, or null when the document has
     * no such nav.
     *
     * Scanned rather than matched with a lazy `.*?</nav>`: an unclosed `<nav` makes
     * that pattern rescan to the end of the document once per opening. Nav elements
     * cannot nest, so the first closing tag after an opening one belongs to it.
     */
    private fun tocNavBody(html: String): String? {
        var at = 0
        while (true) {
            val open = NAV_OPEN.find(html, at) ?: return null
            val close = html.indexOf("</nav", open.range.last + 1)
            if (close < 0) return null
            val type = attr(open.value, "epub:type") ?: attr(open.value, "type")
            if (type == "toc") return html.substring(open.range.last + 1, close)
            at = open.range.last + 1
        }
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
            // Built once per section, and only when a target in it carries an anchor:
            // looking each anchor up with its own regex re-scanned the whole section per
            // target, and a crafted NCX can name thousands of targets in one file.
            var offsets: Map<String, Int>? = null
            val targets = byPath[section.path].orEmpty()
                .mapNotNull { point ->
                    val at = if (point.anchor.isEmpty()) 0 else {
                        val index = offsets ?: elementOffsets(section.html).also { offsets = it }
                        index[point.anchor]
                    }
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
     * Every `id`/`name` in [html], mapped to the offset of its element's opening `<`.
     *
     * The offset is the element's opening `<`, not the attribute: a chapter that began
     * mid-tag would keep the tag's tail (`id="pre">`) as its first line of text, which
     * then shows up in the reader as junk before the heading.
     */
    private fun elementOffsets(html: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (match in ELEMENT_ANCHOR.findAll(html)) {
            val open = html.lastIndexOf('<', match.range.first)
            out.putIfAbsent(match.groupValues[1], if (open < 0) match.range.first else open)
        }
        return out
    }

    private val ELEMENT_ANCHOR = Regex("(?is)\\b(?:id|name)\\s*=\\s*[\"']([^\"']+)[\"']")

    /**
     * Fallback split for a section with no navigation entry: one chapter per
     * top-level heading, or the whole section when it has none.
     */
    private fun splitByHeadings(section: Section): List<Chapter> {
        // Bounded, and stopped early: every heading becomes a `fromHtml` pass and a
        // `Chapter` before the forty-word filter runs, so an uncapped split of a crafted
        // section multiplies the input many times over. The last slice runs to the end
        // of the section, so the tail is kept rather than dropped.
        val headings = ArrayList<Int>()
        for (match in HEADING.findAll(section.html)) {
            if (headings.size > MAX_CHAPTERS) break
            headings.add(match.range.first)
        }
        if (headings.isEmpty()) return listOf(headingChapter(section, 0, section.html.length))
        val out = mutableListOf<Chapter>()
        if (headings.first() > 0) out += headingChapter(section, 0, headings.first())
        headings.forEachIndexed { index, at ->
            val end = if (index == headings.lastIndex) section.html.length else headings[index + 1]
            out += headingChapter(section, at, end)
        }
        return out
    }

    private val HEADING = Regex("(?is)<h([12])\\b[^>]*>")

    private fun headingChapter(
        section: Section,
        start: Int,
        end: Int,
        overrideTitle: String? = null,
    ): Chapter {
        val slice = section.html.substring(start.coerceIn(0, section.html.length), end.coerceIn(start, section.html.length))
        val text = BookText.fromHtml(slice)
        val title = overrideTitle?.takeIf { it.isNotBlank() }
            ?: BookText.chapterHeading(slice, text)
            ?: ""
        return Chapter(title = title, text = text)
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
            // Either half may carry the name: one converter writes
            // `<item id="cover-image" href="images/1.jpg">`, another
            // `<item id="img7" href="images/cover.jpg">`. Testing only the id missed
            // the second, and the book imported with no cover.
            manifest.entries
                .firstOrNull { entry ->
                    entry.value.mediaType.startsWith("image") &&
                        (entry.key.contains("cover", true) || entry.value.href.contains("cover", true))
                }
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
        val decoded = percentDecode(href)
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

    /**
     * Decodes `%XX` escapes without touching `+`.
     *
     * `URLDecoder` is form decoding, not path decoding: it turns `+` into a space,
     * so a file genuinely named `chapter+1.xhtml` became `chapter 1.xhtml` and the
     * lookup failed. In a URI path a `+` is just a plus.
     */
    internal fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                // Only two hex digits are an escape: `toIntOrNull(16)` also accepts a
                // leading sign, so `%+1` decoded to byte 0x01 and `%-1` to 0xFF, and a
                // href carrying one resolved to a different, unprintable name instead
                // of failing the lookup.
                val digits = value.substring(i + 1, i + 3)
                // `Character.digit` answers -1 for anything that is not a hex digit,
                // which is what rules out the sign `toIntOrNull(16)` happily accepted.
                val high = Character.digit(digits[0], 16)
                val low = Character.digit(digits[1], 16)
                if (high >= 0 && low >= 0) {
                    bytes.write(high shl 4 or low)
                    i += 3
                    continue
                }
            }
            // Everything that is not an escape is already text; re-encode it so the
            // byte buffer holds one consistent stream.
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /**
     * One attribute out of a tag's attribute text, quote-aware.
     *
     * The lookbehind rules out a hyphen, colon or word character before the name: a
     * plain `\b` is a boundary between `-` and `i`, so asking for `id` matched the
     * `id` inside `data-id`, and asking for `href` matched the one in `xlink:href`.
     */
    private fun attr(tag: String, name: String): String? =
        attrPattern(name).find(tag)?.let {
            it.groupValues[2].ifEmpty { it.groupValues[3] }
        }?.takeIf { it.isNotBlank() }

    private val ATTR_PATTERNS = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    /**
     * The pattern for one attribute name, built once per name.
     *
     * [attr] is called for every tag of every chapter and the names asked for are a
     * small fixed set, so recompiling per call was pure repeated work.
     */
    private fun attrPattern(name: String): Regex = ATTR_PATTERNS.computeIfAbsent(name) {
        Regex("(?is)(?<![\\w:.-])$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
    }

    /**
     * The text of this pattern's first match between [from] and [to], or null.
     *
     * `find(input, from)` searches to the end of the document, so using it to look
     * inside one element re-scanned everything after that element once per call. The
     * matcher's region is what bounds it, and it is what keeps a document with many
     * elements linear overall. The matched text is returned rather than a `MatchResult`
     * because that is all either caller needs.
     */
    private fun Regex.findTextBetween(input: String, from: Int, to: Int): String? {
        val end = to.coerceIn(0, input.length)
        val start = from.coerceIn(0, end)
        // A `Matcher` limited to the region: the pattern cannot see past [to], which is
        // what makes this equivalent to matching a copy of the span without building one.
        val matcher = toPattern().matcher(input)
        matcher.region(start, end)
        return if (matcher.find()) matcher.group() else null
    }

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp")

    /**
     * Cap on everything the archive expands to.
     *
     * A real book is a few megabytes. This is high enough that no genuine EPUB is
     * refused and low enough that a zip bomb is reported as an unsupported file
     * instead of exhausting the heap: the unpacked bytes live on beside the parsed
     * text, and one measured entry cost 2.1-2.6× its own size in live heap, so 64 MB
     * here is already ~160 MB before a chapter is extracted.
     */
    private const val MAX_UNPACKED_BYTES = 64 * 1024 * 1024

    /**
     * Cap on a single entry, whatever the total has left.
     *
     * Without it the first entry could claim the whole 64 MB, and it was the entry
     * that ran out of heap first.
     */
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024

    /**
     * Cap on a single image entry. Past this the image is dropped, not the book.
     *
     * A page scan can be tens of megabytes and the reader never shows anything but the
     * cover, so refusing the whole import over one of them would lose the text too.
     */
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

    /** Starting size of an entry buffer whose declared size is unknown. */
    private const val INITIAL_ENTRY_BYTES = 256 * 1024

    /**
     * Most chapters one section may be cut into.
     *
     * The heading and navigation splits are both fan-out points: each heading becomes a
     * slice, a `fromHtml` pass and a `Chapter` object before the forty-word filter runs,
     * so an uncapped split is worth tens of megabytes of heap per megabyte of input.
     */
    private const val MAX_CHAPTERS = 4_000

    /**
     * Fewest words a chapter needs to be worth listing.
     *
     * Front matter is a title page and a copyright notice; keeping those as chapters
     * makes the table of contents unusable, and nothing is lost because they are
     * reproduced inside the chapters they precede when a book has real navigation.
     */
    private const val MIN_CHAPTER_WORDS = 40
}
