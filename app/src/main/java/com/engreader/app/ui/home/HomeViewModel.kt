package com.engreader.app.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.engreader.app.data.AppContainer
import com.engreader.app.data.ProgressSnapshot
import com.engreader.app.model.Article
import com.engreader.app.model.Book
import com.engreader.app.nlp.AdaptiveReader
import com.engreader.app.nlp.Readiness
import com.engreader.app.nlp.VocabularyProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The article the reader should read next, and the reason to show beside it. */
data class Recommendation(
    val article: Article,
    val readiness: Readiness,
    val reason: String,
)

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
    /**
     * The article nearest the reader's i+1, with the reason to show on its card.
     *
     * Null until the profiling pass has run, and null when no listed article is long
     * enough to judge: a recommendation drawn from a twenty-word caption would be
     * arithmetic rather than advice.
     */
    val recommendation: Recommendation? = null,
    /**
     * Set when the last load failed, so the screen can say what happened and offer a
     * retry. Without it a repository throw left the home screen with no content, no
     * explanation and no way to ask again.
     */
    val error: String? = null,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    var state by mutableStateOf(HomeState())
        private set

    suspend fun load() {
        state = state.copy(loading = true, error = null)
        try {
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
            val profile = profile(list)
            state = state.copy(unknownCounts = profile.counts, recommendation = profile.recommendation)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // None of the reads above was guarded. The throw escaped the screen's
            // `LaunchedEffect` as an uncaught coroutine exception, and the screen — which
            // has no `runCatching` of its own — had no error branch to fall into.
            // Keeping the previous state means a failed *refresh* still shows the list
            // the reader was looking at, with the failure reported above it.
            state = state.copy(loading = false, error = e.message ?: "无法读取本机数据。")
        }
    }

    /**
     * The unknown-word counts for the rows, and the i+1 pick, from one dictionary pass.
     *
     * Capped at the first few articles and wrapped in `runCatching`: a profiled article
     * is one dictionary pass, and the home list is a browsing surface where a missing
     * badge is much better than a stalled screen. The recommendation rides along on the
     * same pass, so it costs nothing beyond what the badges already pay.
     *
     * The pass itself runs on `Dispatchers.Default` rather than on the caller's. `load`
     * is called from a `LaunchedEffect`, so the caller's dispatcher is the main thread —
     * and a dictionary lookup per lemma over several articles is enough work to drop
     * frames while the list is being drawn. Only the body is moved: the two suspend calls
     * above already pick their own dispatcher, and moving them would nest the hops.
     */
    private suspend fun profile(articles: List<Article>): Profile {
        val known = runCatching { container.wordbook.knownLemmas() }.getOrDefault(emptySet())
        // The vocabulary estimate is the only thing that knows anything about a reader
        // who has not built a wordbook yet; without it a fresh install has no signal at
        // all, and the pick below would be "shortest" dressed up as i+1.
        val estimate = runCatching { container.vocabTest.latest()?.estimate }.getOrNull()
        val hasSignal = known.isNotEmpty() || estimate != null
        return withContext(Dispatchers.Default) {
            val counts = HashMap<Long, Int>()
            val readiness = ArrayList<Readiness>()
            val byId = HashMap<Long, Article>()
            for (article in articles.take(MAX_PROFILED_ROWS)) {
                runCatching {
                    val lemmas = container.articles.contentLemmas(article)
                    if (lemmas.size < VocabularyProfile.MIN_PROFILED_LEMMAS) return@runCatching
                    val baseline = AdaptiveReader.baselineKnown(lemmas, estimate) { lemma ->
                        container.dictionary.lookup(lemma)?.frq ?: 0
                    }
                    val result = container.vocabulary.assess(lemmas, known + baseline)
                    // A zero-unknown article keeps no badge, but it is still a candidate for
                    // the recommendation — it is the right pick when everything else is hard.
                    if (result.unknown.isNotEmpty()) counts[article.id] = result.unknown.size
                    readiness += Readiness(article.id, result.unknown.size, lemmas.size)
                    byId[article.id] = article
                }
            }
            val pick = AdaptiveReader.pick(readiness)
            val article = pick?.let { byId[it.id] }
            Profile(
                counts = counts,
                recommendation = if (!hasSignal || pick == null || article == null) {
                    null
                } else {
                    Recommendation(article, pick, AdaptiveReader.reason(pick))
                },
            )
        }
    }

    private data class Profile(val counts: Map<Long, Int>, val recommendation: Recommendation?)

    /** Installs the bundled passages on first launch so there is always something to read. */
    suspend fun installSeedIfEmpty() {
        val hasAny = withContext(Dispatchers.IO) { container.articles.hasAny() }
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
