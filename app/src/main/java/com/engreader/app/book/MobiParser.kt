package com.engreader.app.book

import java.io.ByteArrayOutputStream

/**
 * Reads a MOBI, AZW3 or plain PalmDOC file into chapters.
 *
 * The container is a Palm database: a 78-byte header, one 8-byte directory entry
 * per record, then the records themselves. Record 0 holds two headers — the 16-byte
 * PalmDOC header and the MOBI header — and the text lives in records 1..recordCount,
 * each compressed independently.
 *
 * Three compression schemes appear in the wild and all three are handled: none (1),
 * PalmDOC LZ77 (2), and HUFF/CDIC (17480). AZW3 in particular is always PalmDOC.
 * Encrypted files are refused up front rather than half-decoded, since the key is
 * derived from a device PID the app has no way to obtain.
 *
 * The chapter structure comes from `<mbp:pagebreak>` markers, which is what
 * kindlegen inserts between chapters, with headings as the fallback.
 *
 * Pure JVM, no Android types, so this is unit-testable against real files.
 */
object MobiParser {

    data class Book(
        val title: String,
        val author: String,
        val language: String,
        val chapters: List<EpubParser.Chapter>,
        val cover: ByteArray?,
        val coverExtension: String,
    )

    /** Palm database header. */
    private const val PDB_HEADER_LENGTH = 78
    private const val PDB_RECORD_COUNT_OFFSET = 76

    /** PalmDOC header, at the start of record 0. */
    private const val PALMDOC_COMPRESSION = 0
    private const val PALMDOC_TEXT_LENGTH = 4
    private const val PALMDOC_RECORD_COUNT = 8
    private const val PALMDOC_ENCRYPTION = 12
    private const val PALMDOC_HEADER_LENGTH = 16

    /** MOBI header, immediately after the PalmDOC header. */
    private const val MOBI_TYPE = 24
    private const val MOBI_ENCODING = 28
    private const val MOBI_FULL_NAME_OFFSET = 84
    private const val MOBI_FULL_NAME_LENGTH = 88
    private const val MOBI_FIRST_IMAGE = 108
    private const val MOBI_HUFF_RECORD = 112
    private const val MOBI_HUFF_COUNT = 116
    private const val MOBI_EXTH_FLAGS = 128
    private const val MOBI_EXTRA_DATA_FLAGS = 0xF2
    /** In a KF8 file these hold the FDST record's index and its piece count. */
    private const val MOBI_FDST_INDEX = 0xC0
    private const val MOBI_FDST_COUNT = 0xC4

    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_PALMDOC = 2
    private const val COMPRESSION_HUFF = 17480

    private const val ENCODING_UTF8 = 65001

    private const val EXTH_MAGIC = "EXTH"
    private const val EXTH_AUTHOR = 100
    private const val EXTH_TITLE = 503
    private const val EXTH_LANGUAGE = 524
    private const val EXTH_COVER_OFFSET = 201

    fun parse(bytes: ByteArray): Book {
        val records = splitRecords(bytes)
        if (records.isEmpty()) throw BookFormat.Companion.Unsupported("这个 MOBI 文件是空的")
        val header0 = records[0]
        if (header0.size < PALMDOC_HEADER_LENGTH + 24) {
            throw BookFormat.Companion.Unsupported("MOBI 头不完整")
        }

        // A joint MOBI6+KF8 file carries two complete books, each with its own record 0
        // and its own PalmDOC/MOBI header. The first one describes the MOBI6 half; the
        // text this parser reads is the KF8 half, so every field has to come from the
        // KF8 header. Reading the record count and text length from the MOBI6 header
        // truncated the book and made the FDST cut look for its pieces in the wrong
        // stream.
        val kf8Offset = kf8RecordOffset(header0, readInt(header0, PALMDOC_HEADER_LENGTH + 4), records)
        val header = if (kf8Offset > 0) records.getOrNull(kf8Offset) ?: header0 else header0
        if (header.size < PALMDOC_HEADER_LENGTH + 24) {
            throw BookFormat.Companion.Unsupported("MOBI 头不完整")
        }
        val textStart = if (kf8Offset > 0) kf8Offset + 1 else 1

        val compression = readShort(header, PALMDOC_COMPRESSION)
        val textLength = readInt(header, PALMDOC_TEXT_LENGTH)
        val recordCount = readShort(header, PALMDOC_RECORD_COUNT)
        val encryption = readShort(header, PALMDOC_ENCRYPTION)
        if (encryption != 0) {
            throw BookFormat.Companion.Encrypted(
                "这本书带 DRM 加密，应用无法解开；请先去掉 DRM 再导入。"
            )
        }
        if (recordCount <= 0 || textStart + recordCount > records.size) {
            throw BookFormat.Companion.Unsupported("MOBI 的正文记录数不对（$recordCount）")
        }

        val mobiHeaderLength = readInt(header, PALMDOC_HEADER_LENGTH + 4)
        val encoding = readInt(header, MOBI_ENCODING)
        val extraFlags = if (mobiHeaderLength >= 0xE4) readShort(header, MOBI_EXTRA_DATA_FLAGS) else 0

        val huffRecord = readInt(header, MOBI_HUFF_RECORD)
        val huffCount = readInt(header, MOBI_HUFF_COUNT)
        val huffman = if (compression == COMPRESSION_HUFF && huffCount >= 2) {
            HuffCdic(records, huffRecord, huffCount)
        } else {
            null
        }
        if (compression == COMPRESSION_HUFF && huffman == null) {
            throw BookFormat.Companion.Unsupported("MOBI 用了 HUFF/CDIC 压缩，但压缩表缺失")
        }

        // A modest initial capacity: `ByteArrayOutputStream(n)` allocates n bytes up
        // front, so a header claiming a huge length would reserve it before a single
        // record has been read. The stream grows on its own as the text arrives.
        val initial = textLength.coerceIn(0, MAX_TEXT_LENGTH).coerceAtMost(1 shl 20)
        val text = ByteArrayOutputStream(initial)
        for (i in textStart until textStart + recordCount) {
            val raw = records.getOrNull(i) ?: break
            val trimmed = trimTrailingEntries(raw, extraFlags)
            val expanded = when {
                huffman != null -> huffman.decode(trimmed)
                compression == COMPRESSION_PALMDOC -> palmDoc(trimmed)
                compression == COMPRESSION_NONE -> trimmed
                else -> throw BookFormat.Companion.Unsupported("不支持的压缩方式（$compression）")
            }
            text.write(expanded)
        }

        val raw = kf8Body(text.toByteArray(), header, records)
        val decoded = decodeText(raw, textLength, encoding)
        val markup = stripMobiMarkup(decoded)
        val chapters = splitChapters(markup)
        if (chapters.isEmpty()) throw BookFormat.Companion.Unsupported("这本书的正文是空的")

        val name = fullName(header) ?: ""
        val exth = readExth(header, mobiHeaderLength)
        val cover = readCover(records, header, exth, records.size)

        return Book(
            title = exth[EXTH_TITLE]?.toString(Charsets.UTF_8)?.trim().orEmpty()
                .ifBlank { name }.ifBlank { "未命名书籍" },
            author = exth[EXTH_AUTHOR]?.toString(Charsets.UTF_8)?.trim().orEmpty(),
            language = exth[EXTH_LANGUAGE]?.toString(Charsets.UTF_8)?.trim().orEmpty(),
            chapters = chapters,
            cover = cover?.first,
            coverExtension = cover?.second.orEmpty(),
        )
    }

    // ------------------------------------------------------------ container

    /** The PDB directory as a list of record slices. */
    private fun splitRecords(bytes: ByteArray): List<ByteArray> {
        if (bytes.size < PDB_HEADER_LENGTH) return emptyList()
        val count = readShort(bytes, PDB_RECORD_COUNT_OFFSET)
        if (count <= 0 || count > MAX_RECORDS) return emptyList()
        val starts = IntArray(count + 1)
        for (i in 0 until count) {
            val at = PDB_HEADER_LENGTH + i * 8
            if (at + 4 > bytes.size) return emptyList()
            starts[i] = readInt(bytes, at)
        }
        starts[count] = bytes.size
        val out = ArrayList<ByteArray>(count)
        for (i in 0 until count) {
            val from = starts[i]
            val to = starts[i + 1]
            if (from < 0 || to > bytes.size || from > to) return emptyList()
            out += bytes.copyOfRange(from, to)
        }
        return out
    }

    /**
     * Record offset of the KF8 half of a joint file, or 0.
     *
     * EXTH type 121 holds the record number of the KF8 part's own record 0, and the
     * record before it is the literal string `BOUNDARY`. Both are checked: a few
     * converters emit the EXTH entry without the marker, and reading text from the
     * wrong offset would silently produce an empty book.
     */
    private fun kf8RecordOffset(
        header: ByteArray,
        mobiHeaderLength: Int,
        records: List<ByteArray>,
    ): Int {
        val boundary = readExth(header, mobiHeaderLength)[121] ?: return 0
        if (boundary.size < 4) return 0
        val index = readInt(boundary, 0)
        val marker = records.getOrNull(index - 1) ?: return 0
        if (marker.size < 8 || String(marker, 0, 8, Charsets.US_ASCII) != "BOUNDARY") return 0
        return index
    }

    /** `PalmDOC`/`MOBI` full name, which lives inside record 0. */
    private fun fullName(header: ByteArray): String? {
        val offset = readInt(header, MOBI_FULL_NAME_OFFSET)
        val length = readInt(header, MOBI_FULL_NAME_LENGTH)
        // Both fields are attacker-controlled 32-bit values, so the sum is checked in
        // 64 bits: `offset + length` in `Int` wraps to a negative number for a large
        // length, sails past the bound check, and `String(…)` then throws.
        if (offset <= 0 || length <= 0) return null
        if (offset.toLong() + length > header.size) return null
        return String(header, offset, length, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------- EXTH

    private fun readExth(header: ByteArray, mobiHeaderLength: Int): Map<Int, ByteArray> {
        val flags = readInt(header, MOBI_EXTH_FLAGS)
        if (flags and 0x40 == 0) return emptyMap()
        var at = PALMDOC_HEADER_LENGTH + mobiHeaderLength
        if (at + 12 > header.size) return emptyMap()
        if (String(header, at, 4, Charsets.US_ASCII) != EXTH_MAGIC) return emptyMap()
        val length = readInt(header, at + 4)
        val count = readInt(header, at + 8)
        at += 12
        val end = (at + length - 12).coerceAtMost(header.size)
        val out = HashMap<Int, ByteArray>()
        var i = 0
        while (i < count && at + 8 <= end) {
            val type = readInt(header, at)
            val size = readInt(header, at + 4)
            // The declared size includes the 8 header bytes; a size below that, or one
            // that runs past the block, means the file is malformed. Stopping is right:
            // continuing would read the next record's bytes as metadata.
            if (size < 8 || at + size > end) break
            out[type] = header.copyOfRange(at + 8, at + size)
            at += size
            i++
        }
        return out
    }

    // ----------------------------------------------------------- decompress

    /**
     * Strips the per-record trailers MOBI appends.
     *
     * A trailing entry is a length-prefixed blob whose size is stored in a
     * Mobipocket variable-width integer at the very end of the record, written
     * backwards. Bit 0 of the flags field means one final byte whose low two bits
     * give the size of the overlap padding.
     */
    private fun trimTrailingEntries(record: ByteArray, flags: Int): ByteArray {
        if (flags == 0) return record
        var end = record.size
        for (bit in 1 until 16) {
            if (flags and (1 shl bit) == 0) continue
            val size = trailingEntrySize(record, end)
            if (size <= 0 || size > end) break
            end -= size
        }
        if (flags and 1 != 0 && end > 0) {
            end -= (record[end - 1].toInt() and 0x03) + 1
        }
        return if (end >= record.size) record else record.copyOfRange(0, end.coerceAtLeast(0))
    }

    private fun trailingEntrySize(record: ByteArray, end: Int): Int {
        var value = 0
        for (i in (end - 4).coerceAtLeast(0) until end) {
            val b = record[i].toInt() and 0xFF
            if (b and 0x80 != 0) value = 0
            value = (value shl 7) or (b and 0x7F)
        }
        return value
    }

    /**
     * PalmDOC LZ77.
     *
     * Four byte classes, in this order: `1..8` copy that many literals, `0x09..0x7F`
     * (and `0x00`) copy one, `0x80..0xBF` is a back-reference, `0xC0..0xFF` is a space
     * followed by the byte with its top bit cleared. A back-reference is a big-endian
     * 16-bit value holding an 11-bit distance and a 3-bit length minus three, and it
     * may overlap the output cursor, so it has to be copied one byte at a time.
     */
    private fun palmDoc(data: ByteArray): ByteArray {
        // A plain growable array rather than a stream: back-references are resolved by
        // index, and reading an element back out of a stream would mean copying the
        // whole buffer on every byte of every match.
        var out = ByteArray((data.size * 3).coerceAtLeast(64))
        var length = 0
        var p = 0
        while (p < data.size) {
            val c = data[p++].toInt() and 0xFF
            when {
                c in 1..8 -> {
                    val end = (p + c).coerceAtMost(data.size)
                    if (length + (end - p) > out.size) out = out.copyOf((out.size * 2).coerceAtLeast(length + end - p))
                    System.arraycopy(data, p, out, length, end - p)
                    length += end - p
                    p = end
                }
                c < 0x80 -> {
                    if (length + 1 > out.size) out = out.copyOf(out.size * 2)
                    out[length++] = c.toByte()
                }
                c < 0xC0 -> {
                    if (p >= data.size) break
                    val pair = (c shl 8) or (data[p++].toInt() and 0xFF)
                    val distance = (pair shr 3) and 0x07FF
                    val run = (pair and 0x07) + 3
                    if (distance <= 0 || distance > length) break
                    if (length + run > out.size) out = out.copyOf((out.size * 2).coerceAtLeast(length + run))
                    // Byte at a time: a match may overlap the output cursor, so the
                    // bytes being copied can include ones this loop just wrote.
                    repeat(run) {
                        out[length] = out[length - distance]
                        length++
                    }
                }
                else -> {
                    if (length + 2 > out.size) out = out.copyOf(out.size * 2)
                    out[length++] = ' '.code.toByte()
                    out[length++] = (c xor 0x80).toByte()
                }
            }
        }
        return out.copyOf(length)
    }

    /**
     * HUFF/CDIC decompression.
     *
     * The HUFF record holds two tables: `dict1` maps the top eight bits of the bit
     * stream to a code length and a candidate maximum code, and `dict2` gives the
     * minimum code for every length so a code whose length is not in `dict1` can be
     * resolved by walking upwards. The CDIC records hold the phrases themselves,
     * indexed by `(maxcode - code) >> (32 - codelen)`.
     *
     * Bits are read most-significant first from a 64-bit window that advances four
     * bytes at a time, so a code may straddle a 32-bit boundary. The stream is padded
     * with zeros so a read near the end is always in bounds, and decoding stops as
     * soon as a code would consume more bits than the record actually has.
     */
    private class HuffCdic(records: List<ByteArray>, huffRecord: Int, huffCount: Int) {

        private val dict1 = IntArray(256)
        private val codeLengths = IntArray(256)
        private val terms = BooleanArray(256)
        private val minCode = IntArray(33)
        private val maxCode = IntArray(33)

        /** Phrase bytes, paired with whether they are already plain text. */
        private val phrases = ArrayList<Pair<ByteArray, Boolean>>()

        init {
            val huff = records.getOrNull(huffRecord)
                ?: throw BookFormat.Companion.Unsupported("MOBI 的 HUFF 表缺失")
            if (huff.size < 24 || String(huff, 0, 4, Charsets.US_ASCII) != "HUFF") {
                throw BookFormat.Companion.Unsupported("MOBI 的 HUFF 表损坏")
            }
            val off1 = readInt(huff, 8)
            val off2 = readInt(huff, 12)
            if (off1 < 0 || off1 + 256 * 4 > huff.size || off2 < 0 || off2 + 64 * 4 > huff.size) {
                throw BookFormat.Companion.Unsupported("MOBI 的 HUFF 表越界")
            }
            for (i in 0 until 256) {
                val v = readInt(huff, off1 + i * 4)
                val length = v and 0x1F
                val term = v and 0x80 != 0
                val max = v ushr 8
                codeLengths[i] = length
                terms[i] = term
                dict1[i] = if (length == 0) 0 else (((max + 1) shl (32 - length)) - 1)
            }
            minCode[0] = 0
            maxCode[0] = -1
            for (i in 1..32) {
                minCode[i] = readInt(huff, off2 + (i - 1) * 8) shl (32 - i)
                maxCode[i] = ((readInt(huff, off2 + (i - 1) * 8 + 4) + 1) shl (32 - i)) - 1
            }
            for (i in 1 until huffCount) {
                val cdic = records.getOrNull(huffRecord + i)
                    ?: throw BookFormat.Companion.Unsupported("MOBI 的 CDIC 表缺失")
                loadCdic(cdic)
            }
            if (phrases.isEmpty()) throw BookFormat.Companion.Unsupported("MOBI 的 CDIC 表是空的")
        }

        private fun loadCdic(cdic: ByteArray) {
            if (cdic.size < 16 || String(cdic, 0, 4, Charsets.US_ASCII) != "CDIC") {
                throw BookFormat.Companion.Unsupported("MOBI 的 CDIC 表损坏")
            }
            val total = readInt(cdic, 8)
            val bits = readInt(cdic, 12)
            if (bits <= 0 || bits > 16) throw BookFormat.Companion.Unsupported("MOBI 的 CDIC 码长不对")
            val wanted = minOf(1 shl bits, total - phrases.size)
            for (k in 0 until wanted) {
                val entryAt = 16 + k * 2
                if (entryAt + 2 > cdic.size) break
                val offset = readShort(cdic, entryAt)
                val lengthAt = 16 + offset
                if (lengthAt + 2 > cdic.size) break
                val word = readShort(cdic, lengthAt)
                val length = word and 0x7FFF
                val plain = word and 0x8000 != 0
                val from = lengthAt + 2
                val to = (from + length).coerceAtMost(cdic.size)
                phrases += cdic.copyOfRange(from, to) to plain
            }
        }

        fun decode(data: ByteArray): ByteArray = decode(data, depth = 0)

        private fun decode(data: ByteArray, depth: Int): ByteArray {
            // A phrase is allowed to be compressed, but a table that refers to itself —
            // corrupt, or crafted — would recurse until the stack runs out. A
            // `StackOverflowError` is an `Error`, so it escapes the import path's
            // `catch (Exception)` and kills the app; the reference implementation stops
            // at a depth of 20, and no real book nests anywhere near that.
            if (depth > MAX_PHRASE_DEPTH) return ByteArray(0)
            val out = ByteArrayOutputStream(data.size * 4)
            // Eight zero bytes so the 64-bit window can be read four bytes past the end.
            val padded = data.copyOf(data.size + 8)
            var bitsLeft = data.size * 8
            var pos = 0
            var window = readLong(padded, pos)
            var available = 32
            while (true) {
                if (available <= 0) {
                    pos += 4
                    window = readLong(padded, pos)
                    available += 32
                }
                val code = ((window ushr available) and 0xFFFFFFFFL).toInt()
                var length = codeLengths[code ushr 24]
                if (length == 0) break
                var max = dict1[code ushr 24]
                if (!terms[code ushr 24]) {
                    while (length < 32 && code < minCode[length]) length++
                    if (length > 32) break
                    max = maxCode[length]
                }
                available -= length
                bitsLeft -= length
                if (bitsLeft < 0) break
                val index = (max - code) ushr (32 - length)
                if (index < 0 || index >= phrases.size) break
                val (bytes, plain) = phrases[index]
                if (plain) {
                    out.write(bytes)
                } else if (depth >= MAX_PHRASE_DEPTH) {
                    // Deeper than any real book nests, so the table refers to itself.
                    // Stopping here keeps the output bounded; the phrase is left
                    // uncached so a shallower occurrence can still expand it.
                    break
                } else {
                    val expanded = decode(bytes, depth + 1)
                    phrases[index] = expanded to true
                    out.write(expanded)
                }
            }
            return out.toByteArray()
        }
    }

    // ----------------------------------------------------------------- text

    /**
     * Cuts a KF8 flow down to its first piece.
     *
     * A KF8 file's decompressed stream is not one document: it is several *flow
     * pieces* concatenated, and the FDST record says where each begins. Piece 0 is
     * the XHTML body; the rest are the stylesheet, the SVG wrappers and the resource
     * map, none of which belongs in the text — without this the last chapter of every
     * AZW3 ends with a screenful of CSS.
     *
     * The cut is made on the *bytes*, not the decoded string: FDST offsets count
     * bytes of the decompressed stream, and a UTF-8 book has multibyte characters, so
     * slicing the string by those numbers lands in the wrong place.
     *
     * A file with no FDST record, or with only one piece, is returned unchanged, so
     * MOBI6 and ordinary MOBI go through here untouched.
     */
    private fun kf8Body(text: ByteArray, header: ByteArray, records: List<ByteArray>): ByteArray {
        val index = readInt(header, MOBI_FDST_INDEX)
        val count = readInt(header, MOBI_FDST_COUNT)
        if (index <= 0 || count <= 1 || index >= records.size) return text
        val fdst = records[index]
        if (fdst.size < 20 || String(fdst, 0, 4, Charsets.US_ASCII) != "FDST") return text
        val pieces = readInt(fdst, 8)
        if (pieces <= 1 || fdst.size < 12 + pieces * 8) return text
        val start = readInt(fdst, 12)
        val end = readInt(fdst, 16)
        if (start < 0 || end <= start || end > text.size) return text
        return text.copyOfRange(start, end)
    }

    /**
     * Concatenated records to a string.
     *
     * The PalmDOC header declares the exact uncompressed length, and the records
     * carry padding past it, so the result is truncated rather than trusted to end
     * cleanly. A UTF-8 byte-order mark is dropped: some converters emit one and it
     * would otherwise show up as the first character of the first chapter.
     */
    private fun decodeText(bytes: ByteArray, textLength: Int, encoding: Int): String {
        val usable = if (textLength in 1..bytes.size) bytes.copyOfRange(0, textLength) else bytes
        val text = if (encoding == ENCODING_UTF8) {
            String(usable, Charsets.UTF_8)
        } else {
            // Not plain ISO-8859-1: the encoding MOBI calls "1252" is windows-1252,
            // which fills the 0x80..0x9F range with the punctuation books actually
            // use. Decoding as Latin-1 leaves a curly apostrophe as a C1 control
            // character, which renders as a box and breaks word look-ups.
            fromWindows1252(String(usable, Charsets.ISO_8859_1))
        }
        return text.removePrefix("\uFEFF")
    }

    /**
     * Removes the markup that is specific to the MOBI format.
     *
     * `filepos` links and the `recindex`/`mediarecindex` attributes point into the
     * file's own byte layout and mean nothing once the text is extracted, so they go
     * before the generic tag stripping runs and the reader never sees them.
     */
    private fun stripMobiMarkup(text: String): String {
        var out = text.replace(Regex("(?is)<\\?xml[^>]*\\?>"), "")
        out = out.replace(Regex("(?is)<!DOCTYPE[^>]*>"), "")
        out = out.replace(Regex("(?i)\\s+filepos\\s*=\\s*[\"']?\\d+[\"']?"), "")
        out = out.replace(Regex("(?i)\\s+(media)?recindex\\s*=\\s*[\"']?\\d+[\"']?"), "")
        out = out.replace(Regex("(?i)\\s+aid\\s*=\\s*[\"'][^\"']*[\"']"), "")
        return out
    }

    /**
     * Splits the body on chapter boundaries.
     *
     * `<mbp:pagebreak>` is the marker kindlegen writes between chapters, and it is by
     * far the most reliable signal. Books without it fall back to `<h1>`/`<h2>`
     * headings. Either way the split happens on the raw markup, before the text is
     * extracted, so no text is lost between the markers.
     */
    private fun splitChapters(markup: String): List<EpubParser.Chapter> {
        val byBreak = Regex("(?i)<\\s*/?\\s*mbp:pagebreak[^>]*>").split(markup)
        val pieces = if (byBreak.count { BookText.wordCount(BookText.fromHtml(it)) >= MIN_CHAPTER_WORDS } >= 2) {
            byBreak
        } else {
            splitOnHeadings(markup)
        }
        val chapters = pieces.map { piece ->
            val text = BookText.fromHtml(piece)
            EpubParser.Chapter(BookText.chapterHeading(piece) ?: "", text)
        }.filter { it.wordCount >= MIN_CHAPTER_WORDS }

        return chapters.mapIndexed { index, chapter ->
            if (chapter.title.isBlank()) chapter.copy(title = "第 ${index + 1} 节") else chapter
        }
    }

    private fun splitOnHeadings(markup: String): List<String> {
        val headings = Regex("(?is)<h([12])\\b[^>]*>").findAll(markup).map { it.range.first }.toList()
        if (headings.size < 2) return listOf(markup)
        val out = mutableListOf<String>()
        if (headings.first() > 0) out += markup.substring(0, headings.first())
        headings.forEachIndexed { index, at ->
            val end = headings.getOrNull(index + 1) ?: markup.length
            out += markup.substring(at, end)
        }
        return out
    }

    // ---------------------------------------------------------------- cover

    /**
     * Cover image bytes, if the book declares one.
     *
     * EXTH 201 holds an offset *relative to the first image record*, which the MOBI
     * header names separately — so the record number is the sum of the two, not
     * either alone. The image's own magic bytes give the extension, since the record
     * carries no name.
     */
    private fun readCover(
        records: List<ByteArray>,
        header: ByteArray,
        exth: Map<Int, ByteArray>,
        recordCount: Int,
    ): Pair<ByteArray, String>? {
        val entry = exth[EXTH_COVER_OFFSET] ?: return null
        if (entry.size < 4) return null
        val firstImage = readInt(header, MOBI_FIRST_IMAGE)
        val index = firstImage + readInt(entry, 0)
        if (index <= 0 || index >= recordCount) return null
        val bytes = records.getOrNull(index) ?: return null
        if (bytes.size < 12) return null
        val ext = when {
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
            bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "gif"
            else -> return null
        }
        return bytes to ext
    }

    // -------------------------------------------------------------- helpers

    private fun readShort(b: ByteArray, at: Int): Int {
        if (at < 0 || at + 2 > b.size) return 0
        return ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    }

    private fun readInt(b: ByteArray, at: Int): Int {
        if (at < 0 || at + 4 > b.size) return 0
        return ((b[at].toInt() and 0xFF) shl 24) or
            ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or
            (b[at + 3].toInt() and 0xFF)
    }

    private fun readLong(b: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            val byte = if (at + i in b.indices) b[at + i].toInt() and 0xFF else 0
            value = (value shl 8) or byte.toLong()
        }
        return value
    }

    /**
     * Replaces the C1 characters Latin-1 leaves behind with their windows-1252 ones.
     *
     * The JDK does not ship windows-1252 everywhere, so the 0x80..0x9F half is mapped
     * by hand over a Latin-1 base: those are the bytes carrying the curly quotes,
     * dashes and ellipsis, and unmapped they render as boxes.
     */
    internal fun fromWindows1252(text: String): String {
        if (text.none { it.code in 0x80..0x9F }) return text
        val table = charArrayOf(
            '\u20AC', '\uFFFD', '\u201A', '\u0192', '\u201E', '\u2026', '\u2020', '\u2021',
            '\u02C6', '\u2030', '\u0160', '\u2039', '\u0152', '\uFFFD', '\u017D', '\uFFFD',
            '\uFFFD', '\u2018', '\u2019', '\u201C', '\u201D', '\u2022', '\u2013', '\u2014',
            '\u02DC', '\u2122', '\u0161', '\u203A', '\u0153', '\uFFFD', '\u017E', '\u0178',
        )
        return buildString(text.length) {
            for (c in text) append(if (c.code in 0x80..0x9F) table[c.code - 0x80] else c)
        }
    }

    /** A record count above this is a corrupt directory, not a book. */
    private const val MAX_RECORDS = 100_000

    /** How deep a compressed phrase may nest before the table is treated as broken. */
    private const val MAX_PHRASE_DEPTH = 20

    /** Upper bound on the declared text length, so a bad header cannot allocate GBs. */
    private const val MAX_TEXT_LENGTH = 64 * 1024 * 1024

    private const val MIN_CHAPTER_WORDS = 40
}
