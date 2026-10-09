package com.engreader.app.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.engreader.app.data.AppContainer
import com.engreader.app.data.ProgressSnapshot
import com.engreader.app.model.Article
import com.engreader.app.model.Book
import com.engreader.app.nlp.VocabularyProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the home screen needs to render, resolved in one repository pass. */
data class HomeState(
    val loading: Boolean = true,
    val continueReading: Article? = null,
    val recent: List<Article> = emptyList(),
    val saved: List<Article> = emptyList(),
    /**
     * Imported books, the one being read first.
     *
     * Books are kept out of [recent] — a novel would swamp a list of articles — but
     * they are what a reader returns to night after night, so the home screen shows
     * the one in progress rather than hiding it behind another screen.
     */
    val books: List<Book> = emptyList(),
    val progress: ProgressSnapshot? = null,
    val dueWords: Int = 0,
    /**
     * How many words each listed article would teach the reader, by article id.
     *
     * A count rather than a percentage: the denominator is the passage's
     * study-worthy lemmas, not its length, so "生词 42" says what pre-studying
     * would cost while "生词 100%" only alarms. Articles too short to profile are
     * absent, which is why a row sometimes has no badge at all.
     *
     * Only computed for the articles actually listed, and only after the screen has
     * something to show: the profile needs a dictionary pass per article, and a
     * list of twenty would delay the first frame for a decoration.
     */
    val unknownCounts: Map<Long, Int> = emptyMap(),
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    var state by mutableStateOf(HomeState())
        private set

    suspend fun load() {
        state = state.copy(loading = true)
        val opened = container.articles.recent(limit = 20)
        // Before the user has opened anything, the library is still worth showing:
        // the bundled passages are already readable, so fall back to stored articles.
        val list = opened.ifEmpty { container.articles.all().take(20) }
        val saved = container.articles.saved()
        val progress = container.progress.snapshot()
        state = HomeState(
            loading = false,
            continueReading = list.firstOrNull(),
            recent = list,
            saved = saved,
            books = container.books.all(),
            progress = progress,
            dueWords = progress.dueNow,
        )
        state = state.copy(unknownCounts = unknownCounts(list))
    }

    /**
     * Unknown study-worthy words per article, for the rows on screen.
     *
     * Capped at the first few articles and wrapped in `runCatching`: a profiled
     * article is one dictionary pass, and the home list is a browsing surface where
     * a missing badge is much better than a stalled screen.
     */
    private suspend fun unknownCounts(articles: List<Article>): Map<Long, Int> {
        val known = runCatching { container.wordbook.knownLemmas() }.getOrDefault(emptySet())
        val out = HashMap<Long, Int>()
        for (article in articles.take(MAX_PROFILED_ROWS)) {
            runCatching {
                val lemmas = container.articles.contentLemmas(article)
                if (lemmas.size < VocabularyProfile.MIN_PROFILED_LEMMAS) return@runCatching
                val result = container.vocabulary.assess(lemmas, known)
                if (result.unknown.isNotEmpty()) out[article.id] = result.unknown.size
            }
        }
        return out
    }

    /** Installs the bundled passages on first launch so there is always something to read. */
    suspend fun installSeedIfEmpty() {        val hasAny = withContext(Dispatchers.IO) { container.articles.all().isNotEmpty() }
        if (hasAny) return
        val seeds = container.seedLibrary.load()
        seeds.forEach { seed ->
            runCatching {
                container.articles.save(seed.item, seed.extracted)
                // The pack ships its own translations, so the bundled passages read
                // with Chinese available and no network at all.
                container.articles.seedTranslation(seed.item.url, seed.translation)
            }
        }
    }

    private companion object {
        /** Rows to profile on the home list before the pass stops being worth it. */
        const val MAX_PROFILED_ROWS = 8
    }
}
