package com.engreader.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.engreader.app.model.Book
import com.engreader.app.model.ChapterRef
import com.engreader.app.ui.components.HairLine
import com.engreader.app.ui.theme.Palette

/**
 * The table of contents, opened from the reader's top bar.
 *
 * Chapters only: no bodies are loaded to draw this, so a six-hundred-chapter novel
 * opens as fast as a pamphlet. The list scrolls to the chapter being read the moment
 * it appears, because a table of contents that starts at chapter one is useless in
 * chapter forty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentsSheet(
    book: Book,
    chapters: List<ChapterRef>,
    currentIndex: Int,
    onOpen: (ChapterRef) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()

    LaunchedEffect(currentIndex, chapters.size) {
        val at = chapters.indexOfFirst { it.index == currentIndex }
        if (at >= 0) listState.scrollToItem(at)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(
                    bottom = WindowInsets.navigationBars.asPaddingValues()
                        .calculateBottomPadding() + 8.dp
                )
        ) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "第 ${currentIndex + 1} / ${chapters.size} 章",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
        ) {
            items(chapters, key = { it.articleId }) { chapter ->
                ContentsRow(
                    chapter = chapter,
                    current = chapter.index == currentIndex,
                    onOpen = { onOpen(chapter) },
                )
                HairLine(Modifier.padding(horizontal = 22.dp))
            }
        }
    }
}

@Composable
private fun ContentsRow(chapter: ChapterRef, current: Boolean, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 22.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${chapter.index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = if (current) Palette.Pine else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(30.dp),
        )
        Text(
            text = chapter.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (current) Palette.Pine else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (current) {
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .background(Palette.Pine, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "在读",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}
