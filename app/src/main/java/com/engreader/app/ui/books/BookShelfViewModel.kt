package com.engreader.app.ui.books

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import android.net.Uri
import com.engreader.app.book.BookPicker
import com.engreader.app.data.AppContainer
import com.engreader.app.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the shelf needs: the imported books and the state of an import in flight. */
data class BookShelfState(
    val loading: Boolean = true,
    val books: List<Book> = emptyList(),
    /** Set while a file is being parsed and written, so the UI can block and explain. */
    val importing: String? = null,
    /** Result of the last import, shown once and then cleared. */
    val message: String? = null,
    val error: String? = null,
)

/**
 * The imported-book shelf.
 *
 * Import runs here rather than in the composable so it survives a rotation, and it
 * is serialised: two files dropped at once would otherwise interleave their inserts
 * into the same tables.
 */
class BookShelfViewModel(private val container: AppContainer) : ViewModel() {

    var state by mutableStateOf(BookShelfState())
        private set

    suspend fun load() {
        state = state.copy(loading = true)
        state = state.copy(loading = false, books = container.books.all())
    }

    /**
     * Reads the picked file as a book and adds it to the shelf.
     *
     * The name is only a label: what the file actually is comes from its bytes, so a
     * `.txt` holding an EPUB imports fine and a `.epub` holding something else is
     * refused with a message that says so.
     */
    suspend fun import(uri: Uri) {
        val picked = withContext(Dispatchers.IO) { BookPicker.describe(container.appContext, uri) }
        state = state.copy(importing = picked.name, message = null, error = null)
        try {
            val book = container.books.import(uri, picked.name)
            state = state.copy(
                importing = null,
                books = container.books.all(),
                message = "已导入《${book.title}》，共 ${book.chapterCount} 章",
            )
        } catch (e: Exception) {
            state = state.copy(
                importing = null,
                error = e.message ?: "导入失败",
            )
        }
    }

    suspend fun delete(book: Book) {
        container.books.delete(book.id)
        state = state.copy(books = container.books.all(), message = "已删除《${book.title}》")
    }

    fun consumeMessage() {
        state = state.copy(message = null, error = null)
    }
}
