package com.engreader.app.ui.books

import androidx.compose.foundation.Image
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.engreader.app.model.ChapterRef
import com.engreader.app.ui.askForNotificationPermission
import com.engreader.app.ui.components.EmptyState
import com.engreader.app.ui.components.ErrorState
import com.engreader.app.ui.components.HairLine
import com.engreader.app.ui.components.LoadingBlock
import com.engreader.app.ui.containerScopedViewModel

/**
 * A book's own page: what it is, how far through it the reader is, and its chapters.
 *
 * The table of contents is the point of this screen. A chapter is opened by tapping
 * it, and the row for the chapter the reader left off in is marked, so coming back
 * to a book after a week is one tap rather than a scroll through sixty entries.
 */
@Composable
fun BookDetailScreen(
    bookId: Long,
    onBack: () -> Unit,
    onOpenChapter: (Long) -> Unit,
    onListenChapter: (Long) -> Unit = {},
) {
    val viewModel = containerScopedViewModel(key = "book:$bookId") { BookDetailViewModel(it, bookId) }
    val state = viewModel.state
    // Keyed on the book: this destination is reused when the reader opens another book,
    // so a plain `remember` would carry "显示全部章节" from the previous one — a 600-chapter
    // book would open with its table of contents already expanded.
    var showAllChapters by remember(bookId) { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val notifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    androidx.compose.runtime.LaunchedEffect(bookId, reload) { viewModel.load() }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingBlock(label = "正在打开这本书…")
            }

            state.book == null && state.retryable -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                ErrorState(
                    message = state.error ?: "打开这本书时出错。",
                    onRetry = { reload++ },
                )
            }

            state.book == null -> EmptyState(
                title = "这本书已不存在",
                detail = state.error ?: "它可能已经被删除。",
                modifier = Modifier.fillMaxSize(),
            )

            else -> {
                // Bound once to a local: `state.book` is a snapshot read, and the
                // deferred lambdas below run long after this frame, so asserting it
                // again inside them could see a book that has since been cleared.
                val book = state.book ?: return@Box
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
                    ),
                ) {
                    item { BookHeader(book = book, chapterCount = state.chapters.size, cover = rememberCover(book)) }

                    item {
                        val next = book.lastChapter.coerceIn(0, (state.chapters.size - 1).coerceAtLeast(0))
                        val nextChapter = state.chapters.getOrNull(next)
                        if (nextChapter != null) {
                            ContinueRow(
                                label = if (book.lastReadAt > 0) {
                                    "继续读第 ${next + 1} 章 · ${nextChapter.title}"
                                } else {
                                    "从第 1 章开始"
                                },
                                onOpen = { onOpenChapter(nextChapter.articleId) },
                            )
                            ListenRow(
                                label = if (state.chapters.size > 1) {
                                    "听这本书 · 从第 ${next + 1} 章开始"
                                } else {
                                    "听这一章"
                                },
                                onListen = {
                                    askForNotificationPermission(context) { notifications.launch(it) }
                                    onListenChapter(nextChapter.articleId)
                                },
                            )
                        }
                    }

                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "目录",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${state.chapters.size} 章",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    val shown = if (showAllChapters) state.chapters else state.chapters.take(30)
                    items(shown, key = { it.articleId }) { chapter ->
                        ChapterRow(
                            chapter = chapter,
                            current = chapter.index == book.lastChapter,
                            onOpen = { onOpenChapter(chapter.articleId) },
                        )
                        HairLine(Modifier.padding(start = 20.dp, end = 20.dp))
                    }

                    if (!showAllChapters && state.chapters.size > 30) {
                        item {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { showAllChapters = true }
                                    .padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    text = "显示全部 ${state.chapters.size} 章",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

                BookTopBar(title = book.title, onBack = onBack)
            }
        }
    }
}

@Composable
private fun BookTopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The top of the book page.
 *
 * [chapterCount] is the number of chapters actually loaded, not `book.chapterCount`:
 * the table of contents below lists that same list, and a header claiming a different
 * total from the one the reader can count in the list reads as a bug.
 */
@Composable
private fun BookHeader(book: Book, chapterCount: Int, cover: android.graphics.Bitmap?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 88.dp, height = 126.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Box(
                Modifier
                    .size(width = 88.dp, height = 126.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = book.title.take(20),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (book.author.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            // Under an hour, say minutes: `estimatedMinutes` bottoms out at 1, so the
            // old `estimatedMinutes / 60` rendered "约 0 小时" for every book below
            // 10,800 words — which is most of them.
            val duration = if (book.estimatedMinutes < 60) {
                "约 ${book.estimatedMinutes} 分钟"
            } else {
                "约 ${book.estimatedMinutes / 60} 小时"
            }
            Text(
                text = "$chapterCount 章 · ${book.wordCount} 词 · $duration",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${book.format} · ${book.fileName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (book.lastReadAt > 0) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { book.progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "已读到第 ${book.lastChapter + 1} / $chapterCount 章",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The one-tap way back in.
 *
 * Reads `继续读第 12 章 · Chapter XII`, which answers both "where was I" and "what is
 * it called" without opening the table of contents.
 */
@Composable
private fun ContinueRow(label: String, onOpen: () -> Unit) {
    ActionRow(
        icon = Icons.Filled.PlayArrow,
        label = label,
        background = MaterialTheme.colorScheme.primaryContainer,
        foreground = MaterialTheme.colorScheme.onPrimaryContainer,
        onClick = onOpen,
    )
}

/**
 * The listening counterpart of [ContinueRow]: same chapter, no screen to read.
 *
 * Deliberately the quieter of the two — reading is still the primary act, and this is the
 * one taken on a commute — so it is a tinted row rather than a filled button.
 */
@Composable
private fun ListenRow(label: String, onListen: () -> Unit) {
    ActionRow(
        icon = Icons.Outlined.Headphones,
        label = label,
        background = MaterialTheme.colorScheme.surfaceContainer,
        foreground = MaterialTheme.colorScheme.primary,
        onClick = onListen,
    )
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    background: androidx.compose.ui.graphics.Color,
    foreground: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = foreground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChapterRow(chapter: ChapterRef, current: Boolean, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${chapter.index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = if (current) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )
        Text(
            text = chapter.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (current) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "${chapter.wordCount} 词",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
