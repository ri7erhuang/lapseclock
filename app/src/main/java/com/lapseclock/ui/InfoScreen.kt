package com.lapseclock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lapseclock.calendar.dayInfoOf
import com.lapseclock.capture.CaptureStatus
import com.lapseclock.capture.PhotoStore
import com.lapseclock.settings.AppSettings
import com.lapseclock.ui.theme.ClockTheme
import com.lapseclock.ui.theme.resolveClockTheme
import com.lapseclock.weather.WeatherUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private class Overview(
    val total: Int,
    val bytes: Long,
    val days: Int,
    val recent: List<PhotoStore.DayStat>,
)

/**
 * 第二屏：左滑进入。
 *
 * 第一屏保持极简（只有时间和一条进度线），所有「数字型」的信息都放这里：
 * 今日/累计统计、近 14 天拍摄量、最近一天首帧与末帧的对比、日与年的进度。
 * 首末帧并排是这一屏最有意思的部分 —— 一眼就能看出这一天「变了什么」。
 */
@Composable
fun InfoScreen(
    store: PhotoStore,
    settings: AppSettings,
    status: CaptureStatus,
    weather: WeatherUi?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = remember { LocalDateTime.now() }
    val dayInfo = remember { dayInfoOf(now.toLocalDate()) }
    val theme = remember(settings.themeId) {
        resolveClockTheme(settings.themeId, now, weather?.text, dayInfo.specialText)
    }

    var data by remember { mutableStateOf<Overview?>(null) }
    // 拍照/清理会更新 lastShotAt 与 totalCount；第二屏停留在前台时也要跟着刷新，
    // 否则「今日/累计」会一直是进入这一屏时的旧数字
    LaunchedEffect(status.lastShotAt, status.totalCount) {
        data = withContext(Dispatchers.IO) {
            store.ensureIndex()
            val (total, bytes, days) = store.overview()
            Overview(total, bytes, days, store.recentDayStats(14))
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.bg)
            .pointerInput(Unit) {
                val threshold = 70.dp.toPx()
                var accumulated = 0f
                detectHorizontalDragGestures(
                    onDragStart = { accumulated = 0f },
                    onDragEnd = {
                        if (accumulated > threshold) onBack()
                        accumulated = 0f
                    },
                    onDragCancel = { accumulated = 0f },
                ) { _, dragAmount -> accumulated += dragAmount }
            },
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "数据",
                    color = theme.main,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "  共 ${data?.total ?: status.totalCount} 帧 · 右滑返回",
                    color = theme.dim,
                    fontSize = 12.sp,
                )
                Box(Modifier.weight(1f))
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.Close, contentDescription = "返回", tint = theme.dim)
                }
            }

            val overview = data
            // 不用 return 提前退出：composable lambda 里的 return 会让 Compose 组结构失衡
            when {
                overview == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("统计中…", color = theme.dim, fontSize = 13.sp)
                }

                overview.total == 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有拍到照片", color = theme.dim, fontSize = 14.sp)
                }

                else -> Row(
                    modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    SummaryColumn(
                        overview = overview,
                        theme = theme,
                        status = status,
                        now = now,
                        modifier = Modifier.weight(1.05f).fillMaxHeight(),
                    )
                    TrendColumn(
                        recent = overview.recent,
                        theme = theme,
                        modifier = Modifier.weight(1.35f).fillMaxHeight(),
                    )
                    FrameColumn(
                        recent = overview.recent,
                        theme = theme,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryColumn(
    overview: Overview,
    theme: ClockTheme,
    status: CaptureStatus,
    now: LocalDateTime,
    modifier: Modifier = Modifier,
) {
    // recent 只包含有照片的日期；今天没拍时不能拿昨天的时间段冒充「今日」
    val todayStat = overview.recent.firstOrNull { it.date == LocalDate.now() }
    val todaySpan = if (todayStat != null && todayStat.count > 1) {
        "${todayStat.firstTime} — ${todayStat.lastTime}"
    } else {
        "—"
    }
    val videoMinutes = overview.total / 30 / 60
    val videoLength = when {
        videoMinutes >= 1 -> "约 $videoMinutes 分钟"
        else -> "约 ${String.format(Locale.US, "%.1f", overview.total / 30f)} 秒"
    }
    val dayProgress = (now.hour * 60 + now.minute) / 1440f
    val dayOfYear = now.dayOfYear
    val yearProgress = dayOfYear / if (now.toLocalDate().isLeapYear) 366f else 365f

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column {
            Text("今日", color = theme.dim, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "${status.todayCount}",
                    color = theme.main,
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Thin,
                )
                Text(
                    text = " 张  $todaySpan",
                    color = theme.dim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }

        Column {
            Text("累计", color = theme.dim, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "${overview.total}",
                    color = theme.main,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Thin,
                )
                Text(
                    text = " 帧 · ${overview.days} 天",
                    color = theme.dim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 7.dp),
                )
            }
            Text(
                text = "${formatBytes(overview.bytes)} · 折算成片 $videoLength",
                color = theme.dim.copy(alpha = 0.8f),
                fontSize = 12.sp,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProgressBar("今天已过", dayProgress, theme)
            ProgressBar("今年已过", yearProgress, theme)
        }
    }
}

@Composable
private fun ProgressBar(label: String, progress: Float, theme: ClockTheme) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = theme.dim, fontSize = 11.sp)
            Text(
                text = String.format(Locale.US, "%.0f%%", progress * 100),
                color = theme.accent,
                fontSize = 11.sp,
            )
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(2.dp)) {
            drawRect(color = theme.dim.copy(alpha = 0.15f), size = size)
            drawRect(color = theme.accent.copy(alpha = 0.7f), size = Size(size.width * progress, size.height))
        }
    }
}

@Composable
private fun TrendColumn(
    recent: List<PhotoStore.DayStat>,
    theme: ClockTheme,
    modifier: Modifier = Modifier,
) {
    val byDate = remember(recent) { recent.associateBy { it.date } }
    val today = LocalDate.now()
    // 补齐没有拍摄的日期，否则柱状图的时间轴会被压缩，看不出「哪几天断了」
    val series = remember(recent, today) {
        (DAYS - 1 downTo 0).map { offset ->
            val d = today.minusDays(offset.toLong())
            d to (byDate[d]?.count ?: 0)
        }
    }
    val max = series.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("近 $DAYS 天", color = theme.dim, fontSize = 12.sp)
            Text("单日峰值 $max 张", color = theme.dim, fontSize = 12.sp)
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            val gap = size.width / series.size
            val barWidth = gap * 0.56f
            val radius = CornerRadius(3.dp.toPx())
            series.forEachIndexed { i, (_, count) ->
                val ratio = count / max.toFloat()
                val h = (size.height - 6.dp.toPx()) * ratio
                val left = i * gap + (gap - barWidth) / 2f
                // 空白天画一条极淡的底线，让断档也能被看见
                drawLine(
                    color = theme.dim.copy(alpha = 0.18f),
                    start = Offset(left, size.height),
                    end = Offset(left + barWidth, size.height),
                    strokeWidth = 2.dp.toPx(),
                )
                if (h > 0f) {
                    drawRoundRect(
                        color = if (count >= max) theme.accent.copy(alpha = 0.85f) else theme.accent.copy(alpha = 0.45f),
                        topLeft = Offset(left, size.height - h),
                        size = Size(barWidth, h),
                        cornerRadius = radius,
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = today.minusDays((DAYS - 1).toLong()).format(DAY_FMT),
                color = theme.dim.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
            Text("今天", color = theme.dim.copy(alpha = 0.7f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun FrameColumn(
    recent: List<PhotoStore.DayStat>,
    theme: ClockTheme,
    modifier: Modifier = Modifier,
) {
    val latest = recent.firstOrNull()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = if (latest != null) "最近一天 ${latest.date.format(DAY_FMT)}" else "最近一天",
            color = theme.dim,
            fontSize = 12.sp,
        )
        if (latest == null) {
            Text("—", color = theme.dim, fontSize = 12.sp)
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FrameCard(latest.first, latest.firstTime, "首帧", theme, Modifier.weight(1f))
            FrameCard(latest.last, latest.lastTime, "末帧", theme, Modifier.weight(1f))
        }
        Text(
            text = "${latest.count} 张 · ${formatBytes(latest.bytes)}",
            color = theme.dim.copy(alpha = 0.8f),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun FrameCard(
    file: File,
    time: String,
    label: String,
    theme: ClockTheme,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        PhotoImage(
            file = file,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .background(Color(0xFF141414), RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
            maxWidth = 640,
            maxHeight = 640,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = theme.accent, fontSize = 11.sp)
            Text(time, color = theme.dim, fontSize = 11.sp)
        }
    }
}

private const val DAYS = 14

private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d", Locale.CHINA)
