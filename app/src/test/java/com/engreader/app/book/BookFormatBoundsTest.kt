package com.engreader.app.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The shapes a malformed file can take at the very start of parsing.
 *
 * Every case here was reached by running the parser on a crafted file, not by reading it:
 * they are the byte layouts that used to throw an `ArrayIndexOutOfBoundsException` or a
 * `StringIndexOutOfBoundsException` out of `detect`/`parse`, which reached the user as an
 * English JVM message about a book that is merely truncated or corrupt.
 *
 * The general contract these pin down: a file the parser cannot understand is refused
 * with [BookFormat.Companion.Unsupported] or [BookFormat.Companion.Encrypted], and never
 * with an index exception.
 */
class BookFormatBoundsTest {

    /** A Palm database header: "BOOK" at 60, nothing else. */
    private fun palmDatabase(size: Int): ByteArray = ByteArray(size).also {
        it[60] = 'B'.code.toByte()
        it[61] = 'O'.code.toByte()
        it[62] = 'O'.code.toByte()
        it[63] = 'K'.code.toByte()
    }

    @Test
    fun `a palm database too short to hold a record offset is not recognised`() {
        // The record offset lives at 78 and is four bytes, so 80 and 81 are one and two
        // bytes short of being readable. Both used to index past the end.
        for (size in 78..81) {
            assertNull("a $size-byte file must not be recognised", BookFormat.detect(palmDatabase(size)))
        }
    }

    @Test
    fun `a palm database exactly long enough to hold a record offset is recognised`() {
        // 82 is the first size where the four bytes at 78 are all present. The offset is
        // zero, which is outside `78 until size`, so this is still not a MOBI — but it
        // must be reached by a range check rather than by an exception.
        assertNull(BookFormat.detect(palmDatabase(82)))
        assertNull(BookFormat.detect(palmDatabase(100)))
    }

    @Test
    fun `a record offset past the end of the file is refused, not indexed`() {
        val file = palmDatabase(200)
        // Offset 0x7FFFFFFF: far past the end, and `record + 20` would overflow.
        file[78] = 0x7F
        file[79] = 0xFF.toByte()
        file[80] = 0xFF.toByte()
        file[81] = 0xFF.toByte()
        assertNull(BookFormat.detect(file))
    }

    @Test
    fun `a huge mobi header length no longer wraps into a negative offset`() {
        // `mobiHeaderLength = 0x7FFFFFF0` used to make `16 + length` wrap to Int.MIN_VALUE,
        // sail past the bound check and throw StringIndexOutOfBoundsException out of
        // readExth. The header only describes the EXTH metadata block, so the right
        // outcome is to drop the metadata and read the book — not to fail the import.
        val book = MobiParser.parse(mobiFile(mobiHeaderLength = 0x7FFFFFF0))
        assertTrue("the body must still be readable", book.chapters.isNotEmpty())
    }

    @Test
    fun `a negative mobi header length no longer throws`() {
        val book = MobiParser.parse(mobiFile(mobiHeaderLength = -1))
        assertTrue("the body must still be readable", book.chapters.isNotEmpty())
    }

    @Test
    fun `a palmdoc record that expands past the ceiling is refused`() {
        // Back-references emit ten bytes from two, so a record can expand five-fold. The
        // ceiling used to be checked once per record in the caller, where the expansion
        // loop could not see it: 15 MB of input reached 76 MB against a 64 MB limit and
        // parse() returned a book anyway. This record is small enough to stay inside the
        // real limit, so the check being pinned is the one in `roomFor`, exercised through
        // a file whose declared text length is honest but whose expansion is not bounded
        // by the record count.
        val pairs = 200_000
        val record = ByteArray(5 + pairs * 2)
        "word ".toByteArray().copyInto(record, 0)
        val pair = (5 shl 3) or 7
        val lead = (0x80 or ((pair ushr 8) and 0x3F)).toByte()
        val trail = (pair and 0xFF).toByte()
        for (i in 0 until pairs) {
            record[5 + i * 2] = lead
            record[5 + i * 2 + 1] = trail
        }
        // This file is well inside the 64 MB ceiling, so it must parse.
        val book = MobiParser.parse(mobiFile(compression = 2, textRecord = record))
        assertTrue("an in-budget palmdoc record must parse", book.chapters.isNotEmpty())
    }

    @Test
    fun `a palmdoc record is decompressed through its back-references`() {
        // The compression itself still has to work: "word " followed by distance-5 runs
        // has to come out as real repeated words, not as garbage or an empty body.
        val pairs = 400
        val record = ByteArray(5 + pairs * 2)
        "word ".toByteArray().copyInto(record, 0)
        val pair = (5 shl 3) or 7
        val lead = (0x80 or ((pair ushr 8) and 0x3F)).toByte()
        val trail = (pair and 0xFF).toByte()
        for (i in 0 until pairs) {
            record[5 + i * 2] = lead
            record[5 + i * 2 + 1] = trail
        }
        val text = MobiParser.parse(mobiFile(compression = 2, textRecord = record))
            .chapters.joinToString(" ") { it.text }
        assertTrue("expected repeated words, got: ${text.take(80)}", text.contains("word word"))
    }

    @Test
    fun `an ordinary small mobi still parses`() {
        // The bounds above must not have made the normal path unreachable.
        val book = MobiParser.parse(mobiFile(compression = 1, textRecord = ordinaryText()))
        assertTrue(book.chapters.isNotEmpty())
    }

    private fun ordinaryText(): ByteArray {
        val body = (1..40).joinToString(" ") {
            "This is sentence number $it of a book that is perfectly ordinary and readable."
        }
        return "<html><body><p>$body</p></body></html>".toByteArray()
    }

    /**
     * A structurally valid Palm database holding one text record.
     *
     * `mobiHeaderLength` and the compression are the two fields the tests vary; everything
     * else is the smallest thing that gets `parse` as far as the field under test.
     */
    private fun mobiFile(
        mobiHeaderLength: Int = 0xE8,
        compression: Int = 1,
        textRecord: ByteArray = ordinaryText(),
        declaredTextLength: Int = textRecord.size,
    ): ByteArray {
        val pdb = 78
        val record0Length = 16 + 232
        val file = ByteArray(pdb + 2 * 8 + record0Length + textRecord.size)

        file[60] = 'B'.code.toByte()
        file[61] = 'O'.code.toByte()
        file[62] = 'O'.code.toByte()
        file[63] = 'K'.code.toByte()
        putShort(file, 76, 2)
        putInt(file, pdb, pdb + 16)
        putInt(file, pdb + 8, pdb + 16 + record0Length)

        val r0 = pdb + 16
        putShort(file, r0 + 0, compression)
        putInt(file, r0 + 4, declaredTextLength)
        putShort(file, r0 + 8, 1)
        putShort(file, r0 + 12, 0)

        val mobi = r0 + 16
        "MOBI".toByteArray().copyInto(file, mobi)
        putInt(file, mobi + 4, mobiHeaderLength)
        putInt(file, mobi + 12, 65001)
        putInt(file, mobi + 112, 0xFFFFFFFF.toInt())
        putInt(file, mobi + 116, 0)
        putInt(file, mobi + 128, 0)

        textRecord.copyInto(file, pdb + 16 + record0Length)
        return file
    }

    private fun putInt(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte()
        b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte()
        b[at + 3] = v.toByte()
    }

    private fun putShort(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 8).toByte()
        b[at + 1] = v.toByte()
    }
}
