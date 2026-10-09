package com.engreader.app.book

/**
 * What kind of file an imported book is.
 *
 * Detected from the bytes rather than the file name: Android's document picker
 * happily hands back a `.epub` that is really a zip of something else, and files
 * downloaded from a browser often arrive with no extension at all. The sniffing
 * order matters — an EPUB is a zip, so the zip signature alone is not enough.
 */
enum class BookFormat(val label: String) {
    Epub("EPUB"),
    Mobi("MOBI"),
    Azw3("AZW3"),
    ;

    companion object {

        /** Raised for a file whose bytes are not a book this app can read. */
        class Unsupported(message: String) : Exception(message)

        /** Raised for an encrypted book, which is detected rather than half-read. */
        class Encrypted(message: String) : Exception(message)

        /**
         * Identifies [bytes] from its leading signature.
         *
         * Only the first few hundred bytes are needed, so callers should pass a
         * prefix rather than a whole multi-megabyte file.
         */
        fun detect(bytes: ByteArray): BookFormat? = when {
            isZip(bytes) -> if (looksLikeEpub(bytes)) Epub else null
            isPalmDatabase(bytes) -> if (isMobi(bytes)) {
                if (mobiVersion(bytes) >= 8) Azw3 else Mobi
            } else {
                null
            }
            else -> null
        }

        private fun isZip(b: ByteArray): Boolean =
            b.size >= 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() &&
                b[2] == 0x03.toByte() && b[3] == 0x04.toByte()

        /**
         * A zip is an EPUB when it carries the `mimetype` entry the OCF spec requires
         * as its first, uncompressed member.
         *
         * Checking for the marker rather than parsing the local file header keeps this
         * to a few bytes of the prefix: the entry name sits at a fixed offset from the
         * start, and the media type it must contain follows immediately.
         */
        private fun looksLikeEpub(b: ByteArray): Boolean {
            val marker = "mimetype".toByteArray(Charsets.US_ASCII)
            val epub = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            // Local header: 30 bytes, then the name, then the stored payload. The name
            // is always at offset 30 for the first entry; the payload starts right
            // after it because `mimetype` must be stored uncompressed.
            if (b.size < 30 + marker.size + epub.size) return false
            val nameAt = indexOf(b, marker)
            if (nameAt !in 0..64) return false
            return indexOf(b, epub) >= 0
        }

        private fun isPalmDatabase(b: ByteArray): Boolean =
            b.size >= 78 && b[60] == 'B'.code.toByte() && b[61] == 'O'.code.toByte() &&
                b[62] == 'O'.code.toByte() && b[63] == 'K'.code.toByte()

        private fun isMobi(b: ByteArray): Boolean {
            val record = firstRecordOffset(b) ?: return false
            return record + 20 <= b.size &&
                b[record + 16] == 'M'.code.toByte() && b[record + 17] == 'O'.code.toByte() &&
                b[record + 18] == 'B'.code.toByte() && b[record + 19] == 'I'.code.toByte()
        }

        /** MOBI header offset 0x24; 8 and above is KF8, which is what `.azw3` holds. */
        private fun mobiVersion(b: ByteArray): Int {
            val record = firstRecordOffset(b) ?: return 0
            return if (record + 40 <= b.size) readInt(b, record + 36) else 0
        }

        private fun firstRecordOffset(b: ByteArray): Int? {
            if (b.size < 80) return null
            val offset = readInt(b, 78)
            return offset.takeIf { it in 78 until b.size }
        }

        private fun readInt(b: ByteArray, at: Int): Int =
            ((b[at].toInt() and 0xFF) shl 24) or
                ((b[at + 1].toInt() and 0xFF) shl 16) or
                ((b[at + 2].toInt() and 0xFF) shl 8) or
                (b[at + 3].toInt() and 0xFF)

        private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
            if (needle.isEmpty() || haystack.size < needle.size) return -1
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) {
                    if (haystack[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return -1
        }
    }
}
