package com.engreader.app.ui.discover

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.engreader.app.model.FeedItem
import com.engreader.app.source.LiveBlog
import com.engreader.app.ui.components.EmptyState
import com.engreader.app.ui.components.ErrorState
import com.engreader.app.ui.components.HairLine
import com.engreader.app.ui.components.LoadingBlock
import com.engreader.app.ui.components.SectionHeader
import com.engreader.app.ui.components.Tag
import com.engreader.app.ui.containerViewModel
import com.engreader.app.ui.theme.Palette
import kotlinx.coroutines.launch

@Composable
fun DiscoverScreen(onOpenArticle: (Long) -> Unit) {
    val viewModel = containerViewModel(key = "discover") { DiscoverViewModel(it) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val state = viewModel.state

    LaunchedEffect(Unit) {
        if (state.items.isEmpty()) viewModel.loadTopic(viewModel.topics.first())
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
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
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "发现",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "选取你感兴趣的话题，抓取全文后即可划词精读",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = {
                                val topic = state.activeTopic
                                val source = state.sourceFilter?.let { CatalogSource(it) }
                                scope.launch {
                                    when {
                                        topic != null -> viewModel.loadTopic(topic, force = true)
                                        source != null -> viewModel.loadSource(source, force = true)
                                        else -> viewModel.loadTopic(viewModel.topics.first(), force = true)
                                    }
                                }
                            }
                        ) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = "刷新",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }

            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(viewModel.topics, key = { it.id }) { topic ->
                        FilterChip(
                            label = topic.name,
                            selected = state.activeTopic?.id == topic.id,
                            onClick = { scope.launch { viewModel.loadTopic(topic) } },
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(viewModel.sources, key = { it.id }) { source ->
                        FilterChip(
                            label = source.shortName,
                            selected = state.sourceFilter == source.id,
                            onClick = { scope.launch { viewModel.loadSource(source) } },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (state.loading) {
                item { LoadingBlock(label = "正在抓取最新文章…") }
            } else if (state.error != null) {
                item {
                    ErrorState(
                        message = state.error,
                        onRetry = {
                            scope.launch {
                                state.activeTopic?.let { viewModel.loadTopic(it, force = true) }
                                    ?: state.sourceFilter?.let { viewModel.loadSource(CatalogSource(it), force = true) }
                            }
                        },
                    )
                }
            } else if (state.items.isEmpty()) {
                item {
                    EmptyState(
                        title = "这个栏目暂时没有内容",
                        detail = "换一个话题，或者刷新试试。离线时也可以先读内置的公版短文。",
                        icon = Icons.Outlined.WifiOff,
                        modifier = Modifier.height(300.dp),
                    )
                }
            } else {
                item {
                    SectionHeader(
                        title = state.activeTopic?.name?.let { "「$it」最新" }
                            ?: viewModel.sources.firstOrNull { it.id == state.sourceFilter }?.name.orEmpty(),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        action = {
                            Text(
                                "${state.items.size} 篇",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
                items(state.items, key = { it.url }) { item ->
                    FeedRow(
                        item = item,
                        opening = state.openingUrl == item.url,
                        onClick = {
                            scope.launch {
                                viewModel.open(item)?.let(onOpenArticle)
                            }
                        },
                    )
                    HairLine(Modifier.padding(horizontal = 20.dp))
                }
                item {
                    Spacer(Modifier.height(20.dp))
                    OfflinePackRow { scope.launch { viewModel.loadOfflineLibrary() } }
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        ) { data -> Snackbar(snackbarData = data) }
    }
}

private fun CatalogSource(id: String) =
    com.engreader.app.source.Catalog.source(id)
        ?: com.engreader.app.source.Catalog.sources.first()

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainer
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun FeedRow(item: FeedItem, opening: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !opening, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.summary.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = item.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.sourceName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // A live blog reads as disconnected updates; flag it so the user can
            // pick a normal report when they want to practise whole-text reading.
            if (LiveBlog.isLive(item.title, item.url)) {
                Spacer(Modifier.width(8.dp))
                Tag("滚动直播", Palette.Amber, Palette.AmberSoft)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (opening) "正在抓取全文…" else relativeTime(item.publishedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OfflinePackRow(onLoad: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.CloudDownload,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "离线公版短文",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "柯南·道尔、简·奥斯汀、达尔文等 8 篇分级选段，无需网络",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onLoad) { Text("载入") }
    }
}

/** Coarse relative time; the feed only needs "how fresh is this". */
internal fun relativeTime(millis: Long): String {
    if (millis <= 0) return ""
    val delta = System.currentTimeMillis() - millis
    val minutes = delta / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)} 天前"
        else -> "${minutes / (60 * 24 * 30)} 个月前"
    }
}
