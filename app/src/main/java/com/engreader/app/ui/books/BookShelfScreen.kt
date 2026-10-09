package com.engreader.app.ui.books

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.engreader.app.model.Book
import com.engreader.app.ui.components.EmptyState
import com.engreader.app.ui.components.HairLine
import com.engreader.app.ui.containerViewModel
import com.engreader.app.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The imported-book shelf.
 *
 * Kept as its own destination rather than mixed into the home list: a book is not an
 * article, and the two are read differently — one is a sitting, the other is a
 * chapter a night. The home screen links here and shows the book being read.
 */
@Composable
fun BookShelfScreen(
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onImport: () -> Unit,
    /**
     * A file the user picked before this screen existed.
     *
     * The system picker's result can arrive while nothing is composed to receive it,
     * so the URI is parked by the caller and delivered here; the screen imports it
     * once and reports back so it is not imported again on recomposition.
     */
    pendingImport: android.net.Uri? = null,
    onImportHandled: () -> Unit = {},
) {
    val viewModel = containerViewModel(key = "bookshelf") { BookShelfViewModel(it) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val state = viewModel.state
    var pendingDelete by remember { mutableStateOf<Book?>(null) }

    LaunchedEffect(Unit) { viewModel.load() }

    LaunchedEffect(pendingImport) {
        val uri = pendingImport ?: return@LaunchedEffect
        viewModel.import(uri)
        onImportHandled()
    }

    LaunchedEffect(state.message, state.error) {
        val text = state.error ?: state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        viewModel.consumeMessage()
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                bottom = 24.dp,
            ),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = "我的书",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onImport, enabled = state.importing == null) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = "导入书籍",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            item {
                Text(
                    text = "导入 EPUB / MOBI / AZW3，按章阅读，同样可以划词和朗读",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 22.dp),
                )
                Spacer(Modifier.height(12.dp))
            }

            state.importing?.let { name ->
                item { ImportingRow(name) }
            }

            if (state.books.isEmpty() && state.importing == null) {
                item {
                    EmptyState(
                        title = "还没有导入的书",
                        detail = "点右上角的加号，从手机里选一个 EPUB、MOBI 或 AZW3 文件。" +
                            "整本书会被拆成章节存进本机，之后不需要网络也能读。",
                        icon = Icons.Outlined.AutoStories,
                        actionLabel = "选择文件",
                        onAction = onImport,
                        modifier = Modifier.height(340.dp),
                    )
                }
            }

            items(state.books, key = { it.id }) { book ->
                BookRow(
                    book = book,
                    cover = rememberCover(book),
                    onOpen = { onOpenBook(book.id) },
                    onDelete = { pendingDelete = book },
                )
                HairLine(Modifier.padding(horizontal = 20.dp))
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        ) { data -> Snackbar(snackbarData = data) }
    }

    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这本书？") },
            text = {
                Text(
                    "《${book.title}》和它的 ${book.chapterCount} 个章节会从本机删除，" +
                        "生词本里从这本书查过的词会保留。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch { viewModel.delete(book) }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/**
 * The cover image for a book, decoded off the main thread.
 *
 * The app carries no image-loading library, and these are small files already on
 * disk, so this decodes the bitmap directly. `produceState` runs the decode on the
 * IO dispatcher and drops the result when the row scrolls away, which is what an
 * image loader would do for this case anyway.
 */
@Composable
internal fun rememberCover(book: Book): android.graphics.Bitmap? {
    val context = LocalContext.current
    return produceState<android.graphics.Bitmap?>(null, book.id, book.coverFile) {
        value = if (book.coverFile.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                val file = File(File(context.filesDir, "covers"), book.coverFile)
                if (!file.isFile) null else runCatching {
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                }.getOrNull()
            }
        }
    }.value
}

@Composable
private fun ImportingRow(name: String) {
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.FileOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "正在导入",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.size(18.dp),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/**
 * One book on the shelf: cover, title, author, and how far through it the reader is.
 *
 * The progress bar is by chapter rather than by word: chapters are what the reader
 * navigates by, and a 4,000-word chapter would otherwise make the bar jump a tenth
 * of its length at a time.
 */
@Composable
internal fun BookRow(
    book: Book,
    cover: android.graphics.Bitmap?,
    onOpen: () -> Unit,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        BookCover(cover = cover, title = book.title)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (book.author.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${book.chapterCount} 章 · ${book.wordCount} 词",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (book.format.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = book.format,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (book.lastReadAt > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { book.progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Palette.Pine,
                    trackColor = MaterialTheme.colorScheme.surfaceContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "读到第 ${book.lastChapter + 1} 章",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onDelete != null) {
            TextButton(
                onClick = onDelete,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    "删除",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A book's cover, or a title card when it has none.
 *
 * The fallback is deliberately a styled box rather than a generic icon: most books
 * have no cover in their metadata, and a shelf of identical placeholders is harder
 * to scan than a shelf of titles.
 */
@Composable
internal fun BookCover(
    cover: android.graphics.Bitmap?,
    title: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    if (cover != null) {
        Image(
            bitmap = cover.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(width = 56.dp, height = 80.dp).clip(shape),
        )
    } else {
        Box(
            modifier
                .size(width = 56.dp, height = 80.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title.take(12),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
