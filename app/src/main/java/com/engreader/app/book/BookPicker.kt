package com.engreader.app.book

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream

/**
 * What the system file picker hands back about a chosen document.
 *
 * The picker returns a `content://` URI whose last path segment is an opaque id, so
 * the real file name has to be queried from the provider. That name is what the
 * shelf shows and what a re-import is matched against, so it is worth the extra
 * round trip — falling back to the path segment would put `document/1234` on screen.
 */
data class PickedFile(val uri: Uri, val name: String, val size: Long)

object BookPicker {

    /**
     * MIME types the picker should offer.
     *
     * `application/epub+zip` and `application/x-mobipocket-ebook` are the correct
     * types, but plenty of providers report an EPUB as `application/octet-stream` or
     * as nothing at all, so the wildcard is included as well — the file is sniffed
     * from its bytes after it is chosen, and a wrong MIME type would otherwise hide a
     * perfectly good book.
     */
    val MIME_TYPES = arrayOf(
        "application/epub+zip",
        "application/x-mobipocket-ebook",
        "application/vnd.amazon.ebook",
        "application/x-fictionbook+xml",
        "text/plain",
        "text/html",
        "application/octet-stream",
        "*/*",
    )

    /** True for a name the app is willing to try, used only to warn early. */
    fun looksSupported(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in setOf(
            "epub", "mobi", "azw", "azw3", "prc",
            "fb2", "txt", "text", "html", "htm", "xhtml", "md",
        )
    }

    fun describe(context: Context, uri: Uri): PickedFile {
        var name = uri.lastPathSegment.orEmpty().substringAfterLast('/')
        var size = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (_: Exception) {
            // A provider that refuses the query still gives a usable URI; the name is
            // only a label and the import does not depend on it.
        }
        return PickedFile(uri, name.ifBlank { "未命名书籍" }, size)
    }

    /**
     * Largest file the importer reads whole, by the kind of file [name] looks like.
     *
     * A container is a zip that carries its own images; a plain document is decoded
     * into a `String` (two bytes per non-Latin-1 character), split into paragraphs and
     * copied into chapter builders, so it gets the smaller ceiling. Both are far above
     * a real book — the largest public-domain EPUB in the app's own test set is 25 MB
     * — and both are below what the heap can hold once the parser has a copy of the
     * bytes and the text is built on top.
     *
     * The name, not the bytes, because the whole point is to refuse the file *before*
     * it is read. A file whose extension says nothing gets the smaller ceiling.
     */
    fun maxImportBytes(name: String): Long =
        if (name.substringAfterLast('.', "").lowercase() in CONTAINER_EXTENSIONS) {
            MAX_CONTAINER_BYTES
        } else {
            MAX_TEXT_BYTES
        }

    /** Cap for EPUB, MOBI, AZW and AZW3, which hold images as well as text. */
    const val MAX_CONTAINER_BYTES = 64L * 1024 * 1024

    /** Cap for TXT, HTML and FB2, which are read into a `String` whole. */
    const val MAX_TEXT_BYTES = 32L * 1024 * 1024

    private val CONTAINER_EXTENSIONS = setOf("epub", "mobi", "azw", "azw3", "prc")

    /**
     * Reads the picked document, refusing anything past [maxBytes] instead of
     * allocating it first.
     *
     * `readBytes()` on the provider's stream grew a `ByteArrayOutputStream` internally,
     * so a file larger than the free heap was held twice before `BookFormat.detect` had
     * even run, and the import died with an `OutOfMemoryError` rather than a message
     * about the file. [knownSize] is the `OpenableColumns.SIZE` the picker already read,
     * used to refuse a declared-oversized file immediately and to size the buffer; the
     * stream is what actually decides the cap.
     */
    fun readLimited(context: Context, uri: Uri, maxBytes: Long, knownSize: Long = 0L): ByteArray {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw BookFormat.Companion.Unsupported("读不到这个文件")
        return stream.use { readLimited(it, maxBytes, knownSize) }
    }

    /**
     * The stream half of [readLimited], split out so the cap itself is testable
     * off-device.
     *
     * The bytes are read in chunks into a growable array: the buffer keeps its size
     * when the declared size is honest, and it is returned as it is when it ends up
     * exactly full, so nothing is copied twice. A chunk that would cross [maxBytes]
     * ends the read with the import's own error type instead of growing the array.
     */
    internal fun readLimited(stream: InputStream, maxBytes: Long, knownSize: Long = 0L): ByteArray {
        if (knownSize > maxBytes) throw tooLarge(maxBytes)
        var out = ByteArray(
            if (knownSize in 1..maxBytes) knownSize.toInt() else minOf(maxBytes, INITIAL_BYTES).toInt()
        )
        var length = 0
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            if (length + read > maxBytes) throw tooLarge(maxBytes)
            val needed = length + read
            if (needed > out.size) {
                var grown = out.size.toLong() * 2
                if (grown < needed) grown = needed.toLong()
                out = out.copyOf(minOf(grown, maxBytes).toInt())
            }
            System.arraycopy(chunk, 0, out, length, read)
            length = needed
        }
        return if (length == out.size) out else out.copyOf(length)
    }

    private fun tooLarge(maxBytes: Long) = BookFormat.Companion.Unsupported(
        "这个文件太大，最多支持 ${maxBytes / (1024 * 1024)} MB"
    )

    /** Starting size of the buffer when the provider declared no usable size. */
    private const val INITIAL_BYTES = 256 * 1024L
}
