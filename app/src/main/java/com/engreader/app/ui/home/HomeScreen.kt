package com.engreader.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.engreader.app.model.Article
import com.engreader.app.model.Book
import com.engreader.app.ui.books.BookCover
import com.engreader.app.ui.books.rememberCover
import com.engreader.app.ui.components.DifficultyChip
import com.engreader.app.ui.components.EmptyState
import com.engreader.app.ui.components.HairLine
import com.engreader.app.ui.components.LoadingBlock
import com.engreader.app.ui.components.SectionHeader
import com.engreader.app.ui.containerViewModel
import com.engreader.app.ui.theme.Palette

@Composable
fun HomeScreen(
    refreshKey: Int,
    onOpenArticle: (Long) -> Unit,
    onBrowse: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onOpenShelf: () -> Unit,
    onImportBook: () -> Unit,
) {
    val viewModel = containerViewModel(key = "home") { HomeViewModel(it) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshKey) {
        viewModel.installSeedIfEmpty()
        viewModel.load()
    }

    val state = viewModel.state

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp,
            bottom = 24.dp,
        ),
    ) {
        item {
            Header(state, onOpenShelf, onImportBook)
        }

        if (state.loading) {
            item { LoadingBlock(label = "正在准备词库与文章…") }
        } else {
            if (state.books.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "在读的书",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        action = {
                            Text(
                                "全部 ${state.books.size} 本",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable(onClick = onOpenShelf),
                            )
                        },
                    )
                }
                items(state.books.take(3), key = { "book-${it.id}" }) { book ->
                    BookShelfRow(book) { onOpenBook(book.id) }
                    HairLine(Modifier.padding(start = 20.dp, end = 20.dp))
                }
                item { Spacer(Modifier.height(20.dp)) }
            }

            state.continueReading?.let { article ->
                item {
                    val started = article.lastReadAt > 0
                    SectionHeader(
                        title = if (started) "继续阅读" else "开始阅读",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                    ContinueCard(
                        article = article,
                        started = started,
                        unknownCount = state.unknownCounts[article.id],
                    ) { onOpenArticle(article.id) }
                }
            }

            if (state.recent.size > 1) {
                item {
                    Spacer(Modifier.height(20.dp))
                    SectionHeader(
                        title = if (state.recent.any { it.lastReadAt > 0 }) "最近读过" else "书架",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        action = {
                            Text(
                                "${state.recent.size} 篇",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
                items(state.recent.drop(1), key = { it.id }) { article ->
                    ArticleRow(article, state.unknownCounts[article.id]) { onOpenArticle(article.id) }
                    HairLine(Modifier.padding(start = 20.dp, end = 20.dp))
                }
            }

            if (state.saved.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(20.dp))
                    SectionHeader(
                        title = "已收藏",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        action = {
                            Text(
                                "${state.saved.size} 篇",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
                items(state.saved.take(6), key = { "saved-${it.id}" }) { article ->
                    ArticleRow(article, state.unknownCounts[article.id]) { onOpenArticle(article.id) }
                    HairLine(Modifier.padding(start = 20.dp, end = 20.dp))
                }
            }

            if (state.recent.isEmpty()) {
                item {
                    EmptyState(
                        title = "还没有文章",
                        detail = "去「发现」抓一篇外刊开始读，或者先读内置的公版短文。",
                        actionLabel = "打开发现",
                        onAction = onBrowse,
                        modifier = Modifier.height(320.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(state: HomeState, onOpenShelf: () -> Unit, onImportBook: () -> Unit) {
    val progress = state.progress
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "今天读点什么",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onImportBook) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = "导入书籍",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onOpenShelf) {
                Icon(
                    Icons.Outlined.AutoStories,
                    contentDescription = "我的书",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (progress == null || progress.streakDays == 0) {
                "每天 15 分钟，一年就是 90 小时的真实输入。"
            } else {
                "已连续阅读 ${progress.streakDays} 天，累计 ${progress.wordsRead} 词。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniStat(
                icon = { Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp), tint = Palette.Pine) },
                value = progress?.let { formatMinutes(it.todaySeconds) } ?: "0 分",
                label = "今日",
            )
            MiniStat(
                icon = { Icon(Icons.Outlined.LocalFireDepartment, null, Modifier.size(16.dp), tint = Palette.Amber) },
                value = "${progress?.streakDays ?: 0} 天",
                label = "连续",
            )
            MiniStat(
                icon = { Icon(Icons.Outlined.AutoStories, null, Modifier.size(16.dp), tint = Palette.PineBright) },
                value = "${state.dueWords}",
                label = "待复习",
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MiniStat(icon: @Composable () -> Unit, value: String, label: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(6.dp))
        Column {
            Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ContinueCard(
    article: Article,
    started: Boolean,
    /** Unknown study-worthy words, or null when the article is too short to profile. */
    unknownCount: Int?,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DifficultyChip(article.difficulty)
            Spacer(Modifier.width(8.dp))
            Text(
                text = article.originLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = article.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (article.subtitle.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = article.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${article.wordCount} 词 · 约 ${article.estimatedMinutes} 分钟" +
                    if (started && article.readSeconds > 0) " · 已读 ${formatMinutes(article.readSeconds)}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            // The personal unknown count, which is what decides whether this is the
            // right thing to read now — the corpus difficulty above only describes
            // the text, not the reader.
            unknownCount?.let {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "· 生词 $it 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/**
 * One imported book on the home screen.
 *
 * Deliberately thinner than the shelf's row: the cover is the only decoration, and
 * the progress line says which chapter rather than showing a bar, because on the home
 * screen the book is a link back into the text, not something to manage.
 */
@Composable
private fun BookShelfRow(book: Book, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(cover = rememberCover(book), title = book.title)
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
            Text(
                text = if (book.lastReadAt > 0) {
                    "读到第 ${book.lastChapter + 1} / ${book.chapterCount} 章"
                } else {
                    "未开始 · ${book.chapterCount} 章"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (book.lastReadAt > 0) Palette.Pine
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ArticleRow(article: Article, unknownCount: Int?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = article.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = article.originLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${article.wordCount} 词",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                unknownCount?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "生词 $it 个",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        DifficultyChip(article.difficulty)
    }
}

internal fun formatMinutes(seconds: Int): String = when {
    seconds < 60 -> "${seconds}秒"
    seconds < 3600 -> "${seconds / 60}分"
    else -> "%.1f时".format(seconds / 3600f)
}
