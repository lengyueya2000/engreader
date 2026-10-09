package com.engreader.app.ui.books

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.engreader.app.data.AppContainer
import com.engreader.app.model.Book
import com.engreader.app.model.ChapterRef

data class BookDetailState(
    val loading: Boolean = true,
    val book: Book? = null,
    val chapters: List<ChapterRef> = emptyList(),
    val error: String? = null,
)

/** One book's page: its metadata and its table of contents. */
class BookDetailViewModel(
    private val container: AppContainer,
    private val bookId: Long,
) : ViewModel() {

    var state by mutableStateOf(BookDetailState())
        private set

    suspend fun load() {
        state = state.copy(loading = true)
        val book = container.books.get(bookId)
        if (book == null) {
            state = BookDetailState(loading = false, error = "这本书可能已经被删除。")
            return
        }
        state = BookDetailState(loading = false, book = book, chapters = container.books.chapters(bookId))
    }
}
