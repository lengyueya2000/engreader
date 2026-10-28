package com.engreader.app.ui.progress

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.engreader.app.data.AppContainer
import com.engreader.app.data.ProgressSnapshot
import com.engreader.app.data.VocabResult
import com.engreader.app.dict.DifficultyBand
import com.engreader.app.ui.components.EmptyState
import com.engreader.app.ui.components.ErrorState
import com.engreader.app.ui.components.HairLine
import com.engreader.app.ui.components.LoadingBlock
import com.engreader.app.ui.components.SectionHeader
import com.engreader.app.ui.components.StatBlock
import com.engreader.app.ui.containerViewModel
import com.engreader.app.ui.home.formatMinutes
import com.engreader.app.ui.theme.Palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class ProgressViewModel(private val container: AppContainer) : ViewModel() {

    var snapshot by mutableStateOf<ProgressSnapshot?>(null)
        private set
    var loading by mutableStateOf(true)
        private set

    /** The last vocabulary-size test, so the entry card can show its number. */
    var vocab by mutableStateOf<VocabResult?>(null)
        private set

    val goalMinutes: Int get() = container.settings.dailyGoalMinutes

    /** Set when the last load failed; the screen shows it with a retry. */
    var error by mutableStateOf<String?>(null)
        private set

    suspend fun load() {
        loading = true
        error = null
        try {
            snapshot = container.progress.snapshot()
            vocab = container.vocabTest.latest()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Neither read was guarded. The throw escaped the screen's `LaunchedEffect`
            // as an uncaught coroutine exception — and since nothing here sets `loading`
            // back on the failure path, the alternative was "正在统计…" forever with no
            // data, no message and no retry.
            error = e.message ?: "无法读取学习记录。"
        } finally {
            loading = false
        }
    }
}

/**
 * Learning statistics.
 *
 * The order is deliberate: today's goal first (the one number that drives the next
 * action), then the streak and volume, then the wordbook composition, then the
 * words the user keeps looking up.
 */
@Composable
fun ProgressScreen(onOpenSettings: () -> Unit, onOpenVocabTest: () -> Unit) {
    val viewModel = containerViewModel(key = "progress") { ProgressViewModel(it) }
    val snapshot = viewModel.snapshot
    val error = viewModel.error
    val scope = rememberCoroutineScope()
    val retry: () -> Unit = { scope.launch { viewModel.load() } }

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
                            text = "学习进度",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "用阅读时长、生词量和正确率衡量真实输入",
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
                Spacer(Modifier.height(18.dp))
            }
        }

        if (viewModel.loading) {
            item { LoadingBlock(label = "正在统计…") }
        } else if (snapshot == null && error != null) {
            // Nothing to show and the read failed: the empty state would claim there is
            // no history, which is not what happened.
            item { ErrorState(message = error, onRetry = retry) }
        } else if (snapshot == null) {
            item {
                EmptyState(
                    title = "还没有学习记录",
                    detail = "读一篇文章，查几个词，这里就会有数据。",
                    modifier = Modifier.height(300.dp),
                )
            }
        } else {
            if (error != null) {
                // Statistics already on screen: report the failed refresh above them
                // rather than throwing away what the reader was looking at.
                item { ErrorState(message = error, onRetry = retry) }
            }
            item { GoalCard(snapshot, viewModel.goalMinutes) }
            item { Spacer(Modifier.height(18.dp)) }
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        StatBlock(
                            value = "${snapshot.streakDays}",
                            label = "连续天数",
                            modifier = Modifier.weight(1f),
                            accent = Palette.Amber,
                        )
                        StatBlock(
                            value = formatMinutes(snapshot.weekSeconds),
                            label = "本周阅读",
                            modifier = Modifier.weight(1f),
                        )
                        StatBlock(
                            value = "${snapshot.articlesStarted}",
                            label = "读过的文章",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    HairLine()
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth()) {
                        StatBlock(
                            value = "${snapshot.lookups}",
                            label = "查词次数",
                            modifier = Modifier.weight(1f),
                        )
                        StatBlock(
                            value = if (snapshot.quizCount == 0) "—"
                            else "${(snapshot.quizAccuracy * 100).toInt()}%",
                            label = "答题正确率",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(22.dp))
                }
            }

            item {
                SectionHeader("近 14 天", Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                ActivityChart(snapshot)
                Spacer(Modifier.height(20.dp))
            }

            snapshot.speed?.let { speed ->
                item {
                    SectionHeader("阅读速度", Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                    SpeedCard(speed)
                    Spacer(Modifier.height(20.dp))
                }
            }

            item {
                SectionHeader("生词构成", Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                BandBreakdown(snapshot)
                Spacer(Modifier.height(20.dp))
            }

            item {
                SectionHeader("词汇量", Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                VocabCard(viewModel.vocab, onOpenVocabTest)
                Spacer(Modifier.height(20.dp))
            }

            if (snapshot.topWords.isNotEmpty()) {
                item {
                    SectionHeader("反复查的词", Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                }
                items(snapshot.topWords) { (lemma, count) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = lemma,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "$count 次",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (count >= 3) Palette.Clay else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HairLine(Modifier.padding(horizontal = 20.dp))
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

/**
 * Entry point to the vocabulary-size test, and the last result if there is one.
 *
 * The interval travels with the number even here: the card is the only place most
 * readers will ever see this figure, and a bare "8200 词" on a stats screen reads as a
 * measurement rather than as the estimate it is.
 */
@Composable
private fun VocabCard(vocab: VocabResult?, onOpen: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onOpen)
            .padding(16.dp),
    ) {
        if (vocab == null) {
            Text(
                text = "还没测过",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "64 个词，约 2 分钟，估一个词汇量。之后每隔一段时间再测，能看到增长曲线。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "${vocab.estimate}",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "词",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "区间 ${vocab.low}–${vocab.high} · 最高频 5 万词范围内 · 上次 ${vocab.takenAt.let(::shortDate)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (vocab == null) "开始测试 →" else "再测一次 →",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** `yyyy-MM-dd` from the shared day key, shortened to `MM-dd` for a card line. */
private fun shortDate(millis: Long): String = com.engreader.app.data.DayKey.of(millis).drop(5)

/**
 * Reading speed, with the change against the previous fortnight.
 *
 * The number is an estimate and the card says so: the words are counted from how far
 * into each article the reader got, so the screenful they stopped on is not counted and
 * skimming reads fast. The trend is the point — that is what improves as vocabulary
 * grows — so the change against the previous fortnight leads and the caveat follows.
 */
@Composable
private fun SpeedCard(speed: com.engreader.app.data.ReadingSpeed) {
    val faster = speed.change > 0
    val tint = when {
        !speed.hasTrend -> MaterialTheme.colorScheme.onSurfaceVariant
        faster -> Palette.Pine
        else -> Palette.Amber
    }
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "${speed.wordsPerMinute}",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "词/分钟",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            if (speed.hasTrend) {
                Text(
                    text = if (faster) "↑ ${speed.change}" else "↓ ${-speed.change}",
                    style = MaterialTheme.typography.labelLarge,
                    color = tint,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (speed.hasTrend) {
                "比前两周${if (faster) "快" else "慢"}了 ${kotlin.math.abs(speed.change)} 词/分钟，" +
                    "基于最近两周 ${speed.sampledMinutes} 分钟的阅读。"
            } else {
                "基于最近两周 ${speed.sampledMinutes} 分钟的阅读。再读一段时间就能看出变化。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "这是估算：词数按每篇读到的位置算，停下的那一屏不计入，跳读会让数字偏高。看趋势比看绝对值有意义。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun GoalCard(snapshot: ProgressSnapshot, goalMinutes: Int) {    val goalSeconds = goalMinutes * 60
    val progress = (snapshot.todaySeconds.toFloat() / goalSeconds).coerceIn(0f, 1f)
    val done = snapshot.todaySeconds >= goalSeconds
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (done) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.LocalFireDepartment,
                contentDescription = null,
                tint = if (done) MaterialTheme.colorScheme.primary else Palette.Amber,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (done) "今日目标已完成" else "今日目标",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${formatMinutes(snapshot.todaySeconds)} / ${goalMinutes} 分",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surface,
        )
        if (!done) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "还差 ${((goalSeconds - snapshot.todaySeconds) / 60).coerceAtLeast(1)} 分钟，" +
                    "大约再读 ${((goalSeconds - snapshot.todaySeconds) / 60 / 2).coerceAtLeast(1)} 篇文章。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Daily reading-minutes bars. Pure Compose so no chart dependency is needed. */
@Composable
private fun ActivityChart(snapshot: ProgressSnapshot) {
    // The session table only has rows for days with activity, so the window is
    // rebuilt here: a gap day must render as an empty slot, not be skipped.
    val byDay = snapshot.recentDays.associateBy { it.day }
    val window = remember(snapshot.recentDays) {
        val cal = java.util.Calendar.getInstance()
        (SLOTS - 1 downTo 0).map { back ->
            cal.add(java.util.Calendar.DAY_OF_MONTH, if (back == SLOTS - 1) -(SLOTS - 1) else 1)
            val key = com.engreader.app.data.DayKey.of(cal.timeInMillis)
            key to (byDay[key]?.seconds ?: 0)
        }
    }

    val hasAny = window.any { it.second > 0 }
    if (!hasAny) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "还没有阅读记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val maxSeconds = window.maxOf { it.second }.coerceAtLeast(60)
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            window.forEach { (day, seconds) ->
                val fraction = (seconds.toFloat() / maxSeconds).coerceIn(0f, 1f)
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        // Minutes only: the column is too narrow for a unit suffix,
                        // and the caption below the chart states the unit. A session
                        // under a minute shows nothing rather than a misleading 0.
                        text = if (seconds >= 60) "${seconds / 60}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((8 + (fraction * 72)).dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (seconds == 0) MaterialTheme.colorScheme.surfaceContainer
                                else MaterialTheme.colorScheme.primary
                            )
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = day.takeLast(2),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "柱高为当天阅读分钟数，日期为月/日。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Days shown in the activity chart. */
private const val SLOTS = 14

@Composable
private fun BandBreakdown(snapshot: ProgressSnapshot) {
    val total = snapshot.bands.values.sum().coerceAtLeast(1)
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth()) {
            StatBlock(
                value = "${snapshot.wordbookTotal}",
                label = "生词总数",
                modifier = Modifier.weight(1f),
            )
            StatBlock(
                value = "${snapshot.wordbookMastered}",
                label = "已掌握",
                modifier = Modifier.weight(1f),
                accent = Palette.Pine,
            )
            StatBlock(
                value = "${snapshot.dueNow}",
                label = "今日待复习",
                modifier = Modifier.weight(1f),
                accent = Palette.Amber,
            )
        }
        Spacer(Modifier.height(16.dp))
        val order = listOf(
            DifficultyBand.A1A2, DifficultyBand.B1, DifficultyBand.B2,
            DifficultyBand.C1, DifficultyBand.C2, DifficultyBand.Unknown,
        )
        order.forEach { band ->
            val count = snapshot.bands[band] ?: 0
            if (count == 0) return@forEach
            val fraction = count.toFloat() / total
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text(
                    text = band.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(44.dp),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(8.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(bandColor(band))
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(30.dp),
                )
            }
        }
    }
}

private fun bandColor(band: DifficultyBand): Color = when (band) {
    DifficultyBand.A1A2, DifficultyBand.B1 -> Palette.Pine
    DifficultyBand.B2 -> Palette.Amber
    DifficultyBand.C1, DifficultyBand.C2 -> Palette.Clay
    DifficultyBand.Unknown -> Palette.InkMuted
}
