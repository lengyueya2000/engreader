package com.engreader.app.book

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

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
}
