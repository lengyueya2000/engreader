package com.engreader.app.ui.vocab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.engreader.app.data.AppContainer
import com.engreader.app.data.DayKey
import com.engreader.app.data.VocabResult
import com.engreader.app.nlp.VocabEstimate
import com.engreader.app.nlp.VocabProbe
import com.engreader.app.nlp.VocabularySize
import com.engreader.app.ui.components.HairLine
import com.engreader.app.ui.components.LoadingBlock
import com.engreader.app.ui.containerViewModel
import com.engreader.app.ui.theme.Palette
import kotlinx.coroutines.launch

/** Which face of the vocabulary-size test is on screen. */
enum class VocabPhase { Intro, Running, Result }

data class VocabState(
    val phase: VocabPhase = VocabPhase.Intro,
    val loading: Boolean = false,
    val probes: List<VocabProbe> = emptyList(),
    val index: Int = 0,
    /** Answers so far, per band. Indexed by [VocabularySize.BANDS]. */
    val knownPerBand: List<Int> = List(VocabularySize.BANDS.size) { 0 },
    val sampledPerBand: List<Int> = List(VocabularySize.BANDS.size) { 0 },
    val result: VocabEstimate? = null,
    val history: List<VocabResult> = emptyList(),
)

/**
 * Runs the frequency-band test and keeps the estimate over time.
 *
 * The answers are counted here rather than recomputed from a list of booleans, because
 * the per-band counts are what the result screen shows and what the estimate is
 * computed from — one representation, so the table and the number cannot disagree.
 */
class VocabTestViewModel(private val container: AppContainer) : ViewModel() {

    var state by mutableStateOf(VocabState())
        private set

    suspend fun load() {
        state = state.copy(loading = true, history = container.vocabTest.history())
        state = state.copy(loading = false)
    }

    /**
     * Starts a test seeded by the clock.
     *
     * The seed is the start time, so a retest draws different words: the point of a
     * second sitting is to measure vocabulary, not memory of the first.
     */
    suspend fun start() {
        state = state.copy(loading = true)
        val probes = container.vocabTest.sample(seed = System.currentTimeMillis())
        state = VocabState(phase = VocabPhase.Running, probes = probes)
    }

    suspend fun answer(known: Boolean) {
        val probe = state.probes.getOrNull(state.index) ?: return
        val knownCounts = state.knownPerBand.toMutableList()
        val sampledCounts = state.sampledPerBand.toMutableList()
        sampledCounts[probe.band] = sampledCounts[probe.band] + 1
        if (known) knownCounts[probe.band] = knownCounts[probe.band] + 1

        val next = state.index + 1
        if (next < state.probes.size) {
            state = state.copy(
                index = next,
                knownPerBand = knownCounts,
                sampledPerBand = sampledCounts,
            )
            return
        }
        val estimate = VocabularySize.estimate(knownCounts, sampledCounts)
        state = state.copy(
            index = next,
            knownPerBand = knownCounts,
            sampledPerBand = sampledCounts,
            result = estimate,
            phase = VocabPhase.Result,
        )
        container.vocabTest.record(estimate)
        state = state.copy(history = container.vocabTest.history())
    }

    fun restart() {
        state = VocabState(history = state.history)
    }
}

/**
 * The vocabulary-size test: 64 words in frequency bands, and a growth curve of past
 * estimates.
 *
 * The number is presented as an interval rather than a point throughout, because 64
 * yes/no answers cannot support a point. The screen also names what the number covers
 * — the 50,000 most frequent words — rather than calling it a vocabulary size outright.
 */
@Composable
fun VocabTestScreen(onBack: () -> Unit) {
    val viewModel = containerViewModel(key = "vocab:test") { VocabTestViewModel(it) }
    val scope = rememberCoroutineScope()
    val state = viewModel.state

    LaunchedEffect(Unit) { viewModel.load() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
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
                text = "词汇量测试",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        if (state.loading) {
            LoadingBlock(label = "正在抽题…")
            return@Column
        }

        when (state.phase) {
            VocabPhase.Intro -> IntroPane(
                history = state.history,
                onStart = { scope.launch { viewModel.start() } },
            )
            VocabPhase.Running -> RunningPane(
                state = state,
                onAnswer = { known -> scope.launch { viewModel.answer(known) } },
            )
            VocabPhase.Result -> ResultPane(
                state = state,
                onRetest = viewModel::restart,
            )
        }
    }
}

@Composable
private fun IntroPane(history: List<VocabResult>, onStart: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
            .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp),
    ) {
        Text(
            text = "从最高频的 5 万个英语词里，按词频分段各抽 8 个，一共 64 个。凭第一反应回答认不认识，据此估算你的词汇量。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Bullet("大约 2 分钟，中途可以退出，退出就作废。")
        Bullet("不确定的词请答「不认识」——认识但一时想不起意思，按不认识算更准。")
        Bullet("两次测试的题目不同，所以间隔一段时间再测，看的是真实增长。")

        if (history.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            HairLine()
            Spacer(Modifier.height(16.dp))
            val latest = history.last()
            Text(
                text = "上次结果",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${latest.estimate} 词",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "区间 ${latest.low}–${latest.high} · ${DayKey.of(latest.takenAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (history.size >= 2) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "增长曲线",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                GrowthChart(history)
            }
        }

        Spacer(Modifier.height(24.dp))
        PrimaryButton(text = if (history.isEmpty()) "开始测试" else "再测一次", onClick = onStart)
    }
}

@Composable
private fun RunningPane(state: VocabState, onAnswer: (Boolean) -> Unit) {
    val probe = state.probes.getOrNull(state.index)
    if (probe == null) {
        LoadingBlock(label = "正在抽题…")
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp)
            .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp),
    ) {
        Text(
            text = "第 ${state.index + 1} / ${state.probes.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { state.index.toFloat() / state.probes.size.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.weight(1f))
        Text(
            text = probe.word,
            style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Serif),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "认识这个词的意思吗？凭第一反应，不要推理词根。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AnswerButton("不认识", Palette.Clay, Modifier.weight(1f)) { onAnswer(false) }
            AnswerButton("认识", Palette.Pine, Modifier.weight(1f)) { onAnswer(true) }
        }
    }
}

@Composable
private fun ResultPane(state: VocabState, onRetest: () -> Unit) {
    val result = state.result ?: return
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
            .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp),
    ) {
        Text(
            text = "估算结果",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${result.estimate} 词",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "区间 ${result.low}–${result.high}　答对 ${result.known} / ${result.sampled}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "这是最高频 5 万词范围内的估计。自我报告会把「看着眼熟」算成认识，" +
                "区间又假设各词频段互相独立，所以真实值可能比这个偏保守。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))
        HairLine()
        Spacer(Modifier.height(16.dp))
        Text(
            text = "分段表现",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        VocabularySize.BANDS.forEachIndexed { index, band ->
            val sampled = state.sampledPerBand.getOrElse(index) { 0 }
            if (sampled == 0) return@forEachIndexed
            val known = state.knownPerBand.getOrElse(index) { 0 }
            BandRow(band.label, known, sampled)
        }

        if (state.history.size >= 2) {
            Spacer(Modifier.height(20.dp))
            HairLine()
            Spacer(Modifier.height(16.dp))
            Text(
                text = "增长曲线",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            GrowthChart(state.history)
        }

        Spacer(Modifier.height(24.dp))
        PrimaryButton(text = "再测一次", onClick = onRetest)
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(bottom = 8.dp)) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .size(5.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Palette.Pine)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BandRow(label: String, known: Int, sampled: Int) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(112.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(known.toFloat() / sampled.coerceAtLeast(1))
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Palette.PineBright),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "$known / $sampled",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
        )
    }
}

/**
 * Bars of the past estimates, oldest on the left.
 *
 * Bars rather than a line: the y-axis starts at zero and the point of the chart is the
 * gap between tests, which a line chart of a few points exaggerates. The interval is
 * left off the bars — it would overlap between neighbouring tests — and stays in the
 * text under the last result.
 */
@Composable
private fun GrowthChart(history: List<VocabResult>) {
    val max = history.maxOf { it.estimate }.coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().height(120.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        history.forEach { result ->
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    text = "${result.estimate}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((88f * result.estimate / max).dp.coerceAtLeast(6.dp))
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(Palette.PineBright),
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = DayKey.of(history.first().takenAt).drop(5),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = DayKey.of(history.last().takenAt).drop(5),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun AnswerButton(text: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(tint)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
    }
}
