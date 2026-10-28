package com.engreader.app.ui.books

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.engreader.app.data.AppContainer
import com.engreader.app.model.Book
import com.engreader.app.model.ChapterRef
import kotlinx.coroutines.CancellationException

data class BookDetailState(
    val loading: Boolean = true,
    val book: Book? = null,
    val chapters: List<ChapterRef> = emptyList(),
    val error: String? = null,
    /**
     * True when [error] came from a failed read rather than from a book that is gone.
     *
     * The two look identical in [book], but they deserve different screens: a missing
     * book is final, while a storage failure is worth another attempt.
     */
    val retryable: Boolean = false,
)

/** One book's page: its metadata and its table of contents. */
class BookDetailViewModel(
    private val container: AppContainer,
    private val bookId: Long,
) : ViewModel() {

    var state by mutableStateOf(BookDetailState())
        private set

    suspend fun load() {
        state = state.copy(loading = true, error = null, retryable = false)
        try {
            val book = container.books.get(bookId)
            if (book == null) {
                state = BookDetailState(loading = false, error = "这本书可能已经被删除。")
                return
            }
            state = BookDetailState(loading = false, book = book, chapters = container.books.chapters(bookId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Both reads above can throw, and neither was guarded: the throw escaped the
            // screen's `LaunchedEffect` as an uncaught coroutine exception, and because
            // `loading` is only cleared on the success path, the only other outcome was
            // "正在打开这本书…" forever with no way to retry.
            state = BookDetailState(
                loading = false,
                error = e.message ?: "打开这本书时出错。",
                retryable = true,
            )
        }
    }
}
