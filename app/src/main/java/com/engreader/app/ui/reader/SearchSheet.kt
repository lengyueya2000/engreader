package com.engreader.app.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.engreader.app.model.SearchHit

/**
 * Search over the open piece, or over the whole book when the piece is a chapter.
 *
 * Results are paragraphs rather than matches: a paragraph is what the reader can be
 * taken to, and one row per paragraph keeps a repeated word from filling the list with
 * the same line over and over.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
    query: String,
    results: List<SearchHit>,
    running: Boolean,
    bookTitle: String?,
    onQuery: (String) -> Unit,
    onOpen: (SearchHit) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("查找词或句子") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) {
                            Icon(Icons.Outlined.Clear, contentDescription = "清空")
                        }
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (bookTitle.isNullOrBlank()) {
                    "在本篇内查找。"
                } else {
                    "在《$bookTitle》全书中查找。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            when {
                query.trim().length < 2 -> Text(
                    text = "至少输入两个字符。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                running && results.isEmpty() -> Text(
                    text = "正在查找…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                results.isEmpty() -> Text(
                    text = "没有找到「${query.trim()}」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> {
                    Text(
                        text = "${results.size} 处",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(results, key = { "${it.articleId}:${it.paragraphIndex}" }) { hit ->
                            SearchResultRow(
                                hit = hit,
                                query = query.trim(),
                                showChapter = bookTitle != null,
                                onClick = { onOpen(hit) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    hit: SearchHit,
    query: String,
    showChapter: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 6.dp),
    ) {
        if (showChapter) {
            Text(
                text = hit.chapterTitle.ifBlank { "第 ${hit.chapterIndex + 1} 章" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = highlight(hit.snippet(query), query),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Draws [query] inside [text] in the accent colour.
 *
 * Offsets are recomputed here rather than carried from the database: the snippet is cut
 * around the match, so the position in the snippet is not the position in the paragraph.
 */
@Composable
private fun highlight(text: String, query: String): AnnotatedString {
    val style = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
    if (query.isBlank()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        while (cursor < text.length) {
            val at = text.indexOf(query, cursor, ignoreCase = true)
            if (at < 0) {
                append(text.substring(cursor))
                return@buildAnnotatedString
            }
            append(text.substring(cursor, at))
            withStyle(style) { append(text.substring(at, at + query.length)) }
            cursor = at + query.length
        }
    }
}
