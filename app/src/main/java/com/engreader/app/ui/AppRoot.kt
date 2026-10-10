package com.engreader.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.engreader.app.book.BookPicker
import com.engreader.app.data.AppContainer
import com.engreader.app.ui.books.BookDetailScreen
import com.engreader.app.ui.books.BookShelfScreen
import com.engreader.app.ui.discover.DiscoverScreen
import com.engreader.app.ui.home.HomeScreen
import com.engreader.app.ui.progress.ProgressScreen
import com.engreader.app.ui.reader.ReaderScreen
import com.engreader.app.ui.settings.SettingsScreen
import com.engreader.app.ui.shell.AppShell
import com.engreader.app.ui.shell.Tab
import com.engreader.app.ui.wordbook.WordbookScreen

/** Where the app is, outside the tab bar: an open article, or a full-screen tool. */
sealed interface Overlay {
    data class Reading(val articleId: Long) : Overlay
    data object Settings : Overlay
    data object Review : Overlay
    data object BookShelf : Overlay
    data class BookDetail(val bookId: Long) : Overlay
}

/**
 * Top-level navigation.
 *
 * A tab bar plus a small overlay stack rather than a nav graph: the app has four
 * destinations and one full-screen detail, and the reader needs to keep its own
 * scroll and playback state while a word sheet is open, which is easier to reason
 * about with explicit state than with route arguments.
 */
@Composable
fun AppRoot(container: AppContainer) {
    CompositionLocalProvider(LocalContainer provides container) {
        val overlays = remember { mutableStateListOf<Overlay>() }
        var tab by remember { mutableStateOf(Tab.Home) }
        // Bumping this key asks the visible tab to reload, e.g. after saving an article.
        var refreshKey by remember { mutableStateOf(0) }
        // Due-review count for the wordbook tab's badge. `null` means "not known yet",
        // which is what hides the dot rather than claiming there is nothing to review.
        var dueCount by remember { mutableStateOf<Int?>(null) }
        // A document the user picked, on its way to the shelf. The picker's callback
        // cannot reach the shelf's ViewModel directly — the shelf may not even be
        // composed yet — so the URI waits here until the shelf is on screen to take it.
        var pendingImport by remember { mutableStateOf<Uri?>(null) }

        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                pendingImport = uri
                if (overlays.lastOrNull() !is Overlay.BookShelf) {
                    overlays.add(Overlay.BookShelf)
                }
            }
        }
        val pickBook: () -> Unit = { importLauncher.launch(BookPicker.MIME_TYPES) }

        // Re-read on every tab switch and refresh: reviewing words changes the count,
        // and the tab bar is the only place it is shown.
        LaunchedEffect(tab, refreshKey, overlays.size) {
            if (overlays.isEmpty()) {
                dueCount = runCatching { container.wordbook.dueCount() }.getOrNull()
            }
        }

        val openArticle: (Long) -> Unit = { id -> overlays.add(Overlay.Reading(id)) }
        val pop: () -> Unit = { if (overlays.isNotEmpty()) overlays.removeAt(overlays.lastIndex) }

        // Replaces the chapter on screen rather than stacking another one: walking from
        // chapter one to chapter twelve should still leave the reader one back-press
        // from the shelf, not twelve. The reader is keyed by article id, so the swap
        // builds a fresh ViewModel for the new chapter.
        val openChapter: (Long) -> Unit = { id ->
            if (overlays.lastOrNull() is Overlay.Reading) {
                overlays[overlays.lastIndex] = Overlay.Reading(id)
            } else {
                overlays.add(Overlay.Reading(id))
            }
            refreshKey++
        }

        BackHandler(enabled = overlays.isNotEmpty()) { pop() }

        val current = overlays.lastOrNull()
        if (current == null) {
            AppShell(
                selected = tab,
                onSelect = { tab = it },
                dueCount = dueCount,
            ) { innerModifier ->                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        fadeIn(tween(160)) togetherWith fadeOut(tween(120))
                    },
                    label = "tab",
                    modifier = innerModifier,
                ) { target ->
                    when (target) {
                        Tab.Home -> HomeScreen(
                            refreshKey = refreshKey,
                            onOpenArticle = openArticle,
                            onBrowse = { tab = Tab.Discover },
                            onOpenBook = { overlays.add(Overlay.BookDetail(it)) },
                            onOpenShelf = { overlays.add(Overlay.BookShelf) },
                            onImportBook = pickBook,
                        )
                        Tab.Discover -> DiscoverScreen(
                            onOpenArticle = openArticle,
                        )
                        Tab.Wordbook -> WordbookScreen(
                            onReview = { overlays.add(Overlay.Review) },
                            onOpenSettings = { overlays.add(Overlay.Settings) },
                        )
                        Tab.Progress -> ProgressScreen(
                            onOpenSettings = { overlays.add(Overlay.Settings) },
                        )
                    }
                }
            }
        } else {
            val overlay = current
            Box(Modifier.fillMaxSize()) {
                when (overlay) {
                    is Overlay.Reading -> key(overlay.articleId) {
                        ReaderScreen(
                            articleId = overlay.articleId,
                            onBack = pop,
                            onSavedChanged = { refreshKey++ },
                            onOpenChapter = openChapter,
                        )
                    }
                    Overlay.Review -> com.engreader.app.ui.wordbook.ReviewScreen(onBack = pop)
                    Overlay.Settings -> SettingsScreen(onBack = pop)
                    Overlay.BookShelf -> BookShelfScreen(
                        onBack = pop,
                        onOpenBook = { overlays.add(Overlay.BookDetail(it)) },
                        onImport = pickBook,
                        pendingImport = pendingImport,
                        onImportHandled = {
                            pendingImport = null
                            refreshKey++
                        },
                    )
                    is Overlay.BookDetail -> BookDetailScreen(
                        bookId = overlay.bookId,
                        onBack = pop,
                        onOpenChapter = openArticle,
                    )
                }
            }
        }
    }
}
