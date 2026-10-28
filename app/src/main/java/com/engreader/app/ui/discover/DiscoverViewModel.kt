package com.engreader.app.ui.discover

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.engreader.app.data.AppContainer
import com.engreader.app.model.FeedItem
import com.engreader.app.model.Source
import com.engreader.app.model.Topic
import com.engreader.app.source.Catalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DiscoverState(
    val loading: Boolean = false,
    val items: List<FeedItem> = emptyList(),
    val error: String? = null,
    val activeTopic: Topic? = null,
    val sourceFilter: String? = null,
    val openingUrl: String? = null,
    val message: String? = null,
    /** True while the bundled public-domain pack is being read and stored. */
    val loadingOffline: Boolean = false,
)

/**
 * Owns the Discover screen: loading feeds for the selected topic, and turning a
 * headline into a stored article when the user taps it.
 */
class DiscoverViewModel(private val container: AppContainer) : ViewModel() {

    var state by mutableStateOf(DiscoverState())
        private set

    /** Feed results are cached per topic so switching tabs does not refetch. */
    private val cache = mutableMapOf<String, List<FeedItem>>()

    suspend fun loadTopic(topic: Topic, force: Boolean = false) {
        state = state.copy(loading = true, error = null, activeTopic = topic, sourceFilter = null)
        if (!force) {
            cache[topic.id]?.let {
                state = state.copy(loading = false, items = it)
                return
            }
        }
        try {
            val fetched = container.fetcher.feed(topic.feedUrl, topic.id, topic.name)
            if (fetched == null) {
                // 304: the feed has not changed, so whatever is already on screen
                // stands. Treated as a success rather than an error, because that is
                // what it is.
                state = state.copy(loading = false, items = cache[topic.id] ?: state.items, error = null)
            } else {
                val items = fetched.sortedByDescending { it.publishedAt }.distinctBy { it.url }
                cache[topic.id] = items
                state = state.copy(loading = false, items = items, error = null)
            }
        } catch (e: CancellationException) {
            // Switching tabs cancels this scope. That is not a load failure, and
            // reporting it as one would replace a good list with an error screen.
            throw e
        } catch (e: Exception) {
            state = state.copy(
                loading = false,
                items = emptyList(),
                error = "无法加载「${topic.name}」：${e.message ?: "网络不可用"}。检查网络后重试。",
            )
        }
    }

    suspend fun loadSource(source: Source, force: Boolean = false) {
        state = state.copy(loading = true, error = null, sourceFilter = source.id, activeTopic = null)
        if (!force) {
            cache[source.id]?.let {
                state = state.copy(loading = false, items = it)
                return
            }
        }
        try {
            val fetched = container.fetcher.feed(source)
            if (fetched == null) {
                state = state.copy(loading = false, items = cache[source.id] ?: state.items, error = null)
            } else {
                val items = fetched.sortedByDescending { it.publishedAt }.distinctBy { it.url }
                cache[source.id] = items
                state = state.copy(loading = false, items = items)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state = state.copy(
                loading = false,
                items = emptyList(),
                error = "无法加载 ${source.name}：${e.message ?: "网络不可用"}。",
            )
        }
    }

    /** Fetches the full text and stores it, returning the new article id. */
    suspend fun open(item: FeedItem): Long? {
        state = state.copy(openingUrl = item.url, message = null)
        return try {
            val article = container.articles.open(item)
            state = state.copy(openingUrl = null)
            article.id
        } catch (e: CancellationException) {
            // The coroutine was cancelled — the reader left the tab mid-fetch. Clear the
            // marker before rethrowing: this ViewModel is activity-scoped, so it outlives
            // the composition, and a marker left behind pins that headline on
            // "正在抓取全文…" with `clickable(enabled = !opening)`, i.e. permanently dead.
            state = state.copy(openingUrl = null)
            throw e
        } catch (e: Exception) {
            state = state.copy(openingUrl = null, message = "这篇没能抓取成功：${e.message ?: "未知错误"}")
            null
        }
    }

    /**
     * Installs the bundled public-domain passages, reporting the outcome through
     * `state.message`.
     *
     * The count used to be returned to a caller that dropped it: the row looked identical
     * before and after the tap, so the only reasonable reading was "it did nothing" and
     * the reader tapped again — re-reading and re-parsing all eight seeds each time.
     */
    suspend fun loadOfflineLibrary() {
        if (state.loadingOffline) return
        state = state.copy(loadingOffline = true, message = null)
        try {
            val (added, failed) = withContext(Dispatchers.IO) {
                val seeds = container.seedLibrary.load()
                var added = 0
                var failed = 0
                seeds.forEach { seed ->
                    val existing = container.articles.findByUrl(seed.item.url)
                    if (existing == null) {
                        // `onFailure` was missing, so a seed that could not be stored was
                        // folded into "not added" and reported as nothing at all.
                        runCatching {
                            container.articles.save(seed.item, seed.extracted)
                            container.articles.seedTranslation(seed.item.url, seed.translation)
                        }.onSuccess { added++ }.onFailure { failed++ }
                    }
                }
                added to failed
            }
            state = state.copy(
                loadingOffline = false,
                message = when {
                    failed > 0 -> "载入完成：新增 $added 篇，$failed 篇失败"
                    added > 0 -> "已载入 $added 篇短文"
                    else -> "离线短文都已经载入过了"
                },
            )
        } catch (e: CancellationException) {
            state = state.copy(loadingOffline = false)
            throw e
        } catch (e: Throwable) {
            // Including `SeedLibrary.load` itself: without this the busy flag would stay
            // on and the button would be dead until the process restarted.
            state = state.copy(
                loadingOffline = false,
                message = "离线短文载入失败：${e.message ?: "未知错误"}",
            )
        }
    }

    fun consumeMessage() {
        state = state.copy(message = null)
    }

    val topics: List<Topic> get() = Catalog.topics
    val sources: List<Source> get() = Catalog.sources
}
