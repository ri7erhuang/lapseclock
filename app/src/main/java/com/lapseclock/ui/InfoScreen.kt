package com.lapseclock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lapseclock.calendar.dayInfoOf
import com.lapseclock.calendar.jieQiInYear
import com.lapseclock.calendar.nextJieQi
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
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 第二屏要展示的东西：整屏讲「时间」这一个尺度——今天已过在时钟页的进度线上，
 * 这里接着讲本月、本周、今年，以及一年 365 格。照片只留一个落点：最近一张。
 */
private class YearView(
    val latest: File?,
    val latestTime: String,
    val todayCount: Int,
    val todaySpan: String,
    val jieQi: Map<LocalDate, String>,
    val nextJieQi: Pair<LocalDate, String>?,
)

/**
 * 第二屏：左滑进入。
 *
 * 第一屏是「今天」这个尺度（时间 + 一条当日进度线），第二屏接着往长了讲：
 * 本周 / 本月 / 今年已过、一年 365 格（已过填色、今天标红、节气打亮）、下一个节气倒计时，
 * 末尾放一张「最近一张」照片——只作记录感的落点，不做任何统计。
 *
 * 拍摄统计（今日张数、累计帧数、占用空间、上次自动合成）不在这屏：它们是「App 干了什么」，
 * 和「时间」不是一个层级，放在设置页的存储区块与合成页里。
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
    val today = now.toLocalDate()
    val dayInfo = remember { dayInfoOf(today) }
    val theme = remember(settings.themeId) {
        resolveClockTheme(settings.themeId, now, weather?.text, dayInfo.specialText)
    }

    var view by remember { mutableStateOf<YearView?>(null) }
    // 拍照会更新 lastShotAt 与 totalCount；第二屏停在前台时也要跟着刷新，
    // 否则「最近一张」会一直是进入这一屏时的那张
    LaunchedEffect(status.lastShotAt, status.totalCount) {
        val todayCount = status.todayCount
        view = withContext(Dispatchers.IO) {
            store.ensureIndex()
            val latestDay = store.recentDayStats(1).firstOrNull()
            YearView(
                latest = latestDay?.last,
                latestTime = latestDay?.lastTime.orEmpty(),
                todayCount = todayCount,
                todaySpan = if (latestDay != null && latestDay.date == today && latestDay.count > 1) {
                    "${latestDay.firstTime} — ${latestDay.lastTime}"
                } else {
                    "—"
                },
                jieQi = jieQiInYear(today.year),
                nextJieQi = nextJieQi(today),
            )
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
                    text = "岁月",
                    color = theme.main,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "  右滑返回",
                    color = theme.dim,
                    fontSize = 12.sp,
                )
                Box(Modifier.weight(1f))
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.Close, contentDescription = "返回", tint = theme.dim)
                }
            }

            val data = view
            // 不用 return 提前退出：composable lambda 里的 return 会让 Compose 组结构失衡
            if (data == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("统计中…", color = theme.dim, fontSize = 13.sp)
                }
            } else {
                TimeHeader(now = now, dayInfo = dayInfo, next = data.nextJieQi, theme = theme)
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(26.dp),
                ) {
                    YearPanel(
                        year = today.year,
                        today = today,
                        jieQi = data.jieQi,
                        theme = theme,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    LatestPhoto(
                        latest = data.latest,
                        latestTime = data.latestTime,
                        todayCount = data.todayCount,
                        todaySpan = data.todaySpan,
                        theme = theme,
                        modifier = Modifier.weight(0.4f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/** 上半屏：本周 / 本月 / 今年已过，以及农历、节气倒计时 */
@Composable
private fun TimeHeader(
    now: LocalDateTime,
    dayInfo: com.lapseclock.calendar.DayInfo,
    next: Pair<LocalDate, String>?,
    theme: ClockTheme,
) {
    val dayFraction = (now.hour * 60 + now.minute) / 1440f
    val daysInYear = if (now.toLocalDate().isLeapYear) 366 else 365
    val yearProgress = (now.dayOfYear - 1 + dayFraction) / daysInYear
    val monthProgress = (now.dayOfMonth - 1 + dayFraction) / YearMonth.of(now.year, now.monthValue).lengthOfMonth()
    val weekProgress = (now.dayOfWeek.value - 1 + dayFraction) / 7f
    val daysLeft = daysInYear - now.dayOfYear

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        // 左半（今年/本月/本周）与主区的点阵同宽，右半与「最近一张」同宽，上下两栏就对齐了
        Row(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.weight(0.9f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("今年已过", color = theme.dim, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = String.format(Locale.US, "%.1f", yearProgress * 100),
                        color = theme.main,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Thin,
                    )
                    Text(
                        text = "%",
                        color = theme.main,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(bottom = 7.dp),
                    )
                }
                Text("今年还剩 $daysLeft 天", color = theme.dim, fontSize = 12.sp)
            }

            Column(
                modifier = Modifier.weight(0.7f).padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProgressBar("本月已过", monthProgress, theme)
                ProgressBar("本周已过", weekProgress, theme)
            }
            // 两条进度条贴着年份数字站，别在中间拉出一大片空白
            Spacer(Modifier.weight(0.45f))
        }

        // 与主区两列同宽同间距：农历/节气块正好压在「最近一张」上方
        Spacer(Modifier.width(26.dp))

        Column(modifier = Modifier.weight(0.4f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = "${dayInfo.lunarText} · ${now.format(WEEKDAY_FMT)}",
                color = theme.main,
                fontSize = 14.sp,
            )
            Text(
                text = dayInfo.specialText ?: " ",
                color = theme.accent,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text("下一个节气", color = theme.dim, fontSize = 11.sp)
            Text(
                text = when {
                    next == null -> "—"
                    else -> {
                        val away = ChronoUnit.DAYS.between(now.toLocalDate(), next.first)
                        if (away <= 0L) "${next.second} · 就是今天" else "${next.second} · 还有 $away 天"
                    }
                },
                color = theme.accent,
                fontSize = 13.sp,
            )
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

/**
 * 一年 365 格：12 行（1–12 月）× 31 列（1–31 日）。
 * 已过填色、未到极淡、今天标强调色、节气日打亮——把「这一年」摊开看。
 */
@Composable
private fun YearPanel(
    year: Int,
    today: LocalDate,
    jieQi: Map<LocalDate, String>,
    theme: ClockTheme,
    modifier: Modifier = Modifier,
) {
    val labelStyle = remember(theme) { TextStyle(fontSize = 10.sp, color = theme.dim.copy(alpha = 0.8f)) }
    val measurer = rememberTextMeasurer()
    // 月份标签先量好：宽度用来给点阵留位，行高跟点阵共用同一套几何，行才对得上
    val labels = remember(labelStyle) { (1..12).map { measurer.measure("${it}月", labelStyle) } }
    val labelWidth = labels.maxOf { it.size.width }.toFloat()

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("一年", color = theme.dim, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Legend(theme)
        }
        Spacer(Modifier.height(6.dp))
        val textGap = 8.dp
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // 格子一律正方形（受宽高两边里更紧的一边限制），整块点阵在剩余空间里居中；
            // 月份标签和格子用同一个 cell/originY 排版，所以永远对得上行
            val labelW = labelWidth + textGap.toPx()
            val cell = minOf((size.width - labelW) / 31f, size.height / 12f)
            val originX = labelW + (size.width - labelW - cell * 31f) / 2f
            val originY = (size.height - cell * 12f) / 2f
            val gap = cell * 0.14f
            val radius = CornerRadius(cell * 0.22f)

            labels.forEachIndexed { i, layout ->
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(0f, originY + i * cell + (cell - layout.size.height) / 2f),
                )
            }

            for (month in 1..12) {
                val days = YearMonth.of(year, month).lengthOfMonth()
                for (day in 1..days) {
                    val date = LocalDate.of(year, month, day)
                    val color = when {
                        date == today -> theme.lead
                        date.isAfter(today) ->
                            if (jieQi.containsKey(date)) theme.accent.copy(alpha = 0.35f)
                            else theme.dim.copy(alpha = 0.14f)
                        jieQi.containsKey(date) -> theme.accent.copy(alpha = 0.95f)
                        else -> theme.accent.copy(alpha = 0.45f)
                    }
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(
                            originX + (day - 1) * cell + gap / 2f,
                            originY + (month - 1) * cell + gap / 2f,
                        ),
                        size = Size(cell - gap, cell - gap),
                        cornerRadius = radius,
                    )
                }
            }
        }
    }
}

@Composable
private fun Legend(theme: ClockTheme) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem(theme.lead, "今天", theme)
        LegendItem(theme.accent.copy(alpha = 0.45f), "已过", theme)
        LegendItem(theme.accent.copy(alpha = 0.95f), "节气", theme)
        LegendItem(theme.dim.copy(alpha = 0.14f), "未到", theme)
    }
}

@Composable
private fun LegendItem(color: Color, label: String, theme: ClockTheme) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(6.dp).background(color, CircleShape))
        Text(label, color = theme.dim.copy(alpha = 0.8f), fontSize = 10.sp)
    }
}

/** 唯一的照片元素：最近一张，只作记录感的落点，不做统计 */
@Composable
private fun LatestPhoto(
    latest: File?,
    latestTime: String,
    todayCount: Int,
    todaySpan: String,
    theme: ClockTheme,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("最近一张", color = theme.dim, fontSize = 12.sp)
        if (latest == null) {
            Text("还没有拍到照片", color = theme.dim, fontSize = 13.sp)
        } else {
            PhotoImage(
                file = latest,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .background(Color(0xFF141414), RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
                maxWidth = 640,
                maxHeight = 640,
            )
            Text(
                text = "$latestTime · 今天 $todayCount 张",
                color = theme.accent,
                fontSize = 11.sp,
            )
            Text(text = todaySpan, color = theme.dim.copy(alpha = 0.8f), fontSize = 11.sp)
        }
    }
}

private val WEEKDAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.CHINA)
