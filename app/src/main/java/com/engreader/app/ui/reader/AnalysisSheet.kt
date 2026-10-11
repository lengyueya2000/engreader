package com.engreader.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.engreader.app.nlp.ChunkRole
import com.engreader.app.nlp.ClauseKind
import com.engreader.app.nlp.ConnectedSpeech
import com.engreader.app.nlp.Kind
import com.engreader.app.nlp.Paragraphs
import com.engreader.app.nlp.SentenceAnalysis
import com.engreader.app.nlp.SpeechFeature
import com.engreader.app.ui.components.HairLine
import com.engreader.app.ui.components.Tag
import com.engreader.app.ui.theme.Palette

/**
 * Long-sentence breakdown.
 *
 * Order matters: the sentence first (so the user can re-read it), then how it is said,
 * then the backbone, then clause by clause, then the notes. Reading it top to bottom
 * walks from "what does this sentence say" to "how is it built".
 *
 * [onSpeakPhrase] plays a phrase with the sheet still open — `true` for the 0.6×
 * comparison pace — so a pattern can be heard without leaving the breakdown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisSheet(
    analysis: SentenceAnalysis,
    onSpeakPhrase: (String, Boolean) -> Unit,
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
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "句子拆解",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (analysis.isLong) Tag("长难句", Palette.Clay, Palette.ClaySoft)
                Spacer(Modifier.width(6.dp))
                Tag("${analysis.clauseCount} 个分句", Palette.Pine, Palette.PineSoft)
            }

            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(14.dp)
            ) {
                Text(
                    text = analysis.sentence,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Serif,
                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.15,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.height(16.dp))
            ConnectedSpeechSection(analysis.sentence, onSpeakPhrase)

            Spacer(Modifier.height(16.dp))
            Label("句子主干")
            Spacer(Modifier.height(6.dp))
            Text(
                text = analysis.backbone,
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "先只读这一层，把「谁做了什么」确定下来，再看其余部分怎么补充它。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (analysis.clauses.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                HairLine()
                Spacer(Modifier.height(14.dp))
                Label("分句结构")
                Spacer(Modifier.height(8.dp))
                analysis.clauses.forEach { clause ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (clause.kind == ClauseKind.Main) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer
                            )
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Tag(
                                text = clause.kind.label,
                                tint = if (clause.kind == ClauseKind.Main) MaterialTheme.colorScheme.primary
                                else Palette.Amber,
                                fill = if (clause.kind == ClauseKind.Main) MaterialTheme.colorScheme.surface
                                else Palette.AmberSoft,
                            )
                            if (clause.marker.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (clause.marker.length == 1 && !clause.marker[0].isLetter()) {
                                        "由「${clause.marker}」引出"
                                    } else {
                                        "引导词 ${clause.marker}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = clause.text,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (clause.subject.isNotBlank() || clause.verb.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Row {
                                if (clause.subject.isNotBlank()) {
                                    MiniField("主语", clause.subject)
                                }
                                if (clause.verb.isNotBlank()) {
                                    Spacer(Modifier.width(14.dp))
                                    MiniField("谓语", clause.verb)
                                }
                                if (clause.rest.isNotBlank()) {
                                    Spacer(Modifier.width(14.dp))
                                    MiniField("其余", clause.rest.take(60))
                                }
                            }
                        }
                    }
                }
            }

            if (analysis.chunks.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                HairLine()
                Spacer(Modifier.height(14.dp))
                Label("主句成分切分")
                Spacer(Modifier.height(8.dp))
                Text(
                    text = buildAnnotatedString {
                        analysis.chunks.forEachIndexed { index, chunk ->
                            if (index > 0) append("  ")
                            withStyle(
                                SpanStyle(
                                    color = roleColor(chunk.role),
                                    fontWeight = FontWeight.Medium,
                                )
                            ) { append(chunk.text) }
                            append(" ")
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                append("〔${chunk.role.label}〕")
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(
                        ChunkRole.Subject, ChunkRole.Predicate, ChunkRole.Object, ChunkRole.Adverbial,
                    ).forEach { role ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(roleColor(role))
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                role.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (analysis.notes.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                HairLine()
                Spacer(Modifier.height(14.dp))
                Label("为什么这样理解")
                Spacer(Modifier.height(8.dp))
                analysis.notes.forEach { note ->
                    Row(Modifier.padding(bottom = 10.dp)) {
                        Icon(
                            Icons.Outlined.Lightbulb,
                            contentDescription = null,
                            tint = Palette.Amber,
                            modifier = Modifier.size(15.dp).padding(top = 2.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = note.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = note.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "以上拆解由规则引擎按词性与引导词判定，用于帮助定位主干；遇到歧义句请结合上下文判断。",
                style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun MiniField(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Serif),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun roleColor(role: ChunkRole) = when (role) {
    ChunkRole.Subject -> Palette.Pine
    ChunkRole.Predicate -> Palette.Clay
    ChunkRole.Object -> Palette.PineBright
    ChunkRole.Complement -> Palette.PineBright
    ChunkRole.Adverbial -> Palette.Amber
    ChunkRole.Attributive -> Palette.Amber
    ChunkRole.Appositive -> Palette.InkMuted
    ChunkRole.Connector -> Palette.InkMuted
    ChunkRole.Other -> Palette.InkMuted
}

/**
 * How the sentence is actually said — the patterns the spelling hides.
 *
 * A rule table rather than a transcription of the recording (see [ConnectedSpeech]):
 * each row is "this is the rule that applies here", and can be played at the
 * comparison pace so the join is heard rather than only described.
 */
@Composable
private fun ConnectedSpeechSection(sentence: String, onSpeakPhrase: (String, Boolean) -> Unit) {
    val features = remember(sentence) {
        val tokens = Paragraphs.split(sentence).firstOrNull()?.tokens ?: emptyList()
        ConnectedSpeech.explain(sentence, tokens)
    }
    HairLine()
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label("怎么读")
        Spacer(Modifier.weight(1f))
        PhraseButton("正常") { onSpeakPhrase(sentence, false) }
        Spacer(Modifier.width(6.dp))
        PhraseButton("0.6×") { onSpeakPhrase(sentence, true) }
    }
    Spacer(Modifier.height(8.dp))
    if (features.isEmpty()) {
        Text(
            text = "这句没有明显的连读、缩读或弱读，按字面读就可以。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Text(
        text = "英语说快时词和词会粘在一起，下面是这句里会发生的几处。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    features.forEach { feature ->
        FeatureRow(feature, onPlay = { onSpeakPhrase(feature.written, true) })
    }
}

@Composable
private fun FeatureRow(feature: SpeechFeature, onPlay: () -> Unit) {
    val (tint, fill) = kindColor(feature.kind)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(feature.kind.label, tint, fill)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Medium,
                            )
                        ) { append(feature.written) }
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            append("  →  ")
                        }
                        withStyle(
                            SpanStyle(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        ) { append(feature.sounds) }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = feature.tip,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = "慢速播放",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(20.dp)
                .clickable(onClick = onPlay),
        )
    }
}

@Composable
private fun PhraseButton(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun kindColor(kind: Kind): Pair<Color, Color> = when (kind) {
    Kind.Reduction -> Palette.Clay to Palette.ClaySoft
    Kind.Assimilation -> Palette.Pine to Palette.PineSoft
    Kind.Flap -> Palette.Amber to Palette.AmberSoft
    Kind.Elision -> Palette.InkMuted to Palette.PineSoft
    Kind.Linking -> Palette.PineBright to Palette.PineSoft
    Kind.WeakForm -> Palette.Clay to Palette.AmberSoft
}
