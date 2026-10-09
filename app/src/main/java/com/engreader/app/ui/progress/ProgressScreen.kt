package com.engreader.app.ui.progress

import androidx.compose.foundation.background
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.engreader.app.data.AppContainer
import com.engreader.app.data.ProgressSnapshot
import com.engreader.app.dict.DifficultyBand
import com.engreader.app.ui.components.EmptyState
import com.engreader.app.ui.components.HairLine
import com.engreader.app.ui.components.LoadingBlock
import com.engreader.app.ui.components.SectionHeader
import com.engreader.app.ui.components.StatBlock
import com.engreader.app.ui.containerViewModel
import com.engreader.app.ui.home.formatMinutes
import com.engreader.app.ui.theme.Palette

class ProgressViewModel(private val container: AppContainer) : ViewModel() {

    var snapshot by mutableStateOf<ProgressSnapshot?>(null)
        private set
    var loading by mutableStateOf(true)
        private set

    val goalMinutes: Int get() = container.settings.dailyGoalMinutes

    suspend fun load() {
        loading = true
        snapshot = container.progress.snapshot()
        loading = false
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
fun ProgressScreen(onOpenSettings: () -> Unit) {
    val viewModel = containerViewModel(key = "progress") { ProgressViewModel(it) }
    val snapshot = viewModel.snapshot

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
        } else if (snapshot == null) {
            item {
                EmptyState(
                    title = "还没有学习记录",
                    detail = "读一篇文章，查几个词，这里就会有数据。",
                    modifier = Modifier.height(300.dp),
                )
            }
        } else {
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
                            value = "${snapshot.wordsRead}",
                            label = "累计词量",
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
                            value = "${snapshot.articlesStarted}",
                            label = "读过的文章",
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
 * Reading speed, with the change against the previous fortnight.
 *
 * Shown as a range rather than a precise number: the session table records the
 * article's full word count against the time spent, so a piece left half-read
 * reads fast, and presenting "247 wpm" would claim an accuracy the data does not
 * have. The trend is the point — that is what improves as vocabulary grows.
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
            text = "这是估算：文章读到一半也会按全文词数计入，所以偏快。看趋势比看绝对值有意义。",
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
