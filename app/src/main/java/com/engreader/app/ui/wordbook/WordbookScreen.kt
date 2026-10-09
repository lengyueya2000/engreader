package com.engreader.app.ui.wordbook

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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.engreader.app.data.SavedWord
import com.engreader.app.dict.DifficultyBand
import com.engreader.app.ui.components.BandChip
import com.engreader.app.ui.components.EmptyState
import com.engreader.app.ui.components.HairLine
import com.engreader.app.ui.components.LoadingBlock
import com.engreader.app.ui.containerViewModel
import kotlinx.coroutines.launch

@Composable
fun WordbookScreen(
    onReview: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel = containerViewModel(key = "wordbook") { WordbookViewModel(it) }
    val scope = rememberCoroutineScope()
    val state = viewModel.state
    var expandedLemma by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.load() }

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
                            text = "生词本",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "共 ${state.total} 词，已掌握 ${state.mastered}，待复习 ${state.dueCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))

                if (state.dueCount > 0) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(onClick = onReview)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Bookmark,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "开始复习 ${state.dueCount} 个词",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Text(
                                text = "按记忆曲线安排，答对间隔会拉长",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text("搜索单词或释义") },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        item {
            Row(
                Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WordFilter.entries.forEach { filter ->
                    val selected = state.filter == filter
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainer
                            )
                            .clickable { viewModel.setFilter(filter) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = filter.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (state.loading) {
            item { LoadingBlock(label = "正在读取生词本…") }
        } else if (state.filtered.isEmpty()) {
            item {
                EmptyState(
                    title = if (state.words.isEmpty()) "生词本还是空的" else "没有符合条件的词",
                    detail = if (state.words.isEmpty()) {
                        "阅读时点一下单词，在卡片里点「加入生词本」，之后就能在这里复习。"
                    } else {
                        "换个筛选条件或者清空搜索词试试。"
                    },
                    modifier = Modifier.height(300.dp),
                )
            }
        } else {
            items(state.filtered, key = { it.lemma }) { word ->
                WordRow(
                    word = word,
                    band = state.bands[word.lemma.lowercase()] ?: DifficultyBand.Unknown,
                    expanded = expandedLemma == word.lemma,
                    onToggleExpand = {
                        expandedLemma = if (expandedLemma == word.lemma) null else word.lemma
                    },
                    onSpeak = { viewModel.speak(word.lemma) },
                    onRemove = { scope.launch { viewModel.remove(word.lemma) } },
                    onToggleMastered = { scope.launch { viewModel.setMastered(word.lemma, !word.mastered) } },
                    onSaveNote = { note -> scope.launch { viewModel.setNote(word.lemma, note) } },
                )
                HairLine(Modifier.padding(horizontal = 20.dp))
            }
        }
    }
}

@Composable
private fun WordRow(
    word: SavedWord,
    band: DifficultyBand,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onSpeak: () -> Unit,
    onRemove: () -> Unit,
    onToggleMastered: () -> Unit,
    onSaveNote: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = word.display,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    if (word.phonetic.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "/${word.phonetic}/",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = word.translation.lineSequence().firstOrNull { it.isNotBlank() }
                        ?.substringBefore("\\n").orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 8 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            IconButton(onClick = onSpeak) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "朗读",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BandChip(band)
            Spacer(Modifier.width(8.dp))
            StatusTag(word)
            if (word.correct + word.wrong > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "正确率 ${(word.accuracy * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "复习 ${word.correct + word.wrong} 次",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            Spacer(Modifier.height(12.dp))
            HairLine()
            Spacer(Modifier.height(12.dp))
            NoteEditor(
                lemma = word.lemma,
                note = word.note,
                onSave = onSaveNote,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallAction(
                    label = if (word.mastered) "取消已掌握" else "标记已掌握",
                    onClick = onToggleMastered,
                )
                SmallAction(label = "移出生词本", onClick = onRemove, danger = true)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "首次加入 " + formatDate(word.firstSeenAt) + " · 查词 ${word.seenCount} 次 · " +
                    if (word.mastered) "已掌握" else "下次复习 " + formatDate(word.dueAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A one-line note the reader writes against a word.
 *
 * Kept as a plain text field that saves on focus loss rather than a dialog: the
 * note is a memory hook ("考过两次"), not a document, and a dialog per word would
 * make the wordbook feel like data entry.
 */
@Composable
private fun NoteEditor(lemma: String, note: String, onSave: (String) -> Unit) {
    var draft by remember(lemma) { mutableStateOf(note) }
    var editing by remember(lemma) { mutableStateOf(false) }

    if (!editing) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable { editing = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.EditNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = note.ifBlank { "写点笔记，复习时会显示" },
                style = MaterialTheme.typography.bodySmall,
                color = if (note.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
            )
        }
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(200) },
            placeholder = { Text("例如：考过两次 / 和 xxx 易混", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        SmallAction(label = "保存", onClick = {
            editing = false
            if (draft != note) onSave(draft.trim())
        })
    }
}

@Composable
private fun StatusTag(word: SavedWord) {
    val (label, tint, fill) = when {
        word.mastered -> Triple("已掌握", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
        word.isDue -> Triple("待复习", com.engreader.app.ui.theme.Palette.Amber, com.engreader.app.ui.theme.Palette.AmberSoft)
        else -> Triple("已排期", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainer)
    }
    com.engreader.app.ui.components.Tag(label, tint, fill)
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit, danger: Boolean = false) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (danger) {
            Icon(
                Icons.Outlined.DeleteOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatDate(millis: Long): String {
    if (millis <= 0 || millis == Long.MAX_VALUE) return "—"
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = millis
    return "%d月%d日".format(cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
}
