package com.lapseclock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lapseclock.GoldUi
import com.lapseclock.calendar.dayInfoOf
import com.lapseclock.capture.CaptureStatus
import com.lapseclock.isSleeping
import com.lapseclock.settings.AppSettings
import com.lapseclock.ui.theme.ClockTheme
import com.lapseclock.ui.theme.Themes
import com.lapseclock.ui.theme.resolveClockTheme
import com.lapseclock.weather.WeatherUi
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun xorshift(seed: Int): Int {
    var x = seed
    x = x xor (x shl 13)
    x = x xor (x ushr 17)
    x = x xor (x shl 5)
    return x
}

/**
 * 防烧屏随机游走：按档位返回 dp 偏移。
 * 档位 -1=关闭，0=低 x±30/y±20，1=中 x±60/y±40，2=高 x±80/y±60
 */
private fun antiBurnOffset(seed: Int, level: Int): Pair<Dp, Dp> {
    if (level < 0) return 0.dp to 0.dp
    val maxX = when (level) { 0 -> 30f; 1 -> 60f; else -> 80f }
    val maxY = when (level) { 0 -> 20f; 1 -> 40f; else -> 60f }
    val rx = (seed and 0xFFFF) / 65535f
    val ry = ((seed ushr 16) and 0xFFFF) / 65535f
    return ((rx - 0.5f) * 2f * maxX).dp to ((ry - 0.5f) * 2f * maxY).dp
}

/**
 * 时钟主屏。
 *
 * 手势约定：
 * - 单击：唤出右上角控件
 * - 双击：切下一套皮肤（自动 → 墨白 → 暖夜 → 青瓷 → 胶片 → 赛博 → 自动）
 * - 长按：打开设置
 * - 左滑：进入第二屏（数据）
 */
@Composable
fun ClockScreen(
    settings: AppSettings,
    status: CaptureStatus,
    permissionNeeded: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenExport: () -> Unit,
    onEnterSleep: () -> Unit,
    onWakeUp: () -> Unit,
    onCycleTheme: () -> Unit,
    onSwipeLeft: () -> Unit,
    weather: WeatherUi?,
    gold: GoldUi?,
    modifier: Modifier = Modifier,
) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    val manualSleeping = settings.manualSleep
    val sleeping = isSleeping(manualSleeping, settings.autoSleepSnoozeNight, now)

    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1000L)
        }
    }

    val dayInfo = remember(now.toLocalDate()) { dayInfoOf(now.toLocalDate()) }
    // 主题随时段/天气/节日变化。key 只取小时，避免每秒重算一遍解析器
    val theme = remember(settings.themeId, now.hour, weather?.text, dayInfo.specialText) {
        resolveClockTheme(settings.themeId, now, weather?.text, dayInfo.specialText)
    }
    // 柔和亮度：把主色往黑里压一点，长时间盯着更舒服（不影响主题本身的色相）
    val mainColor = if (settings.softBrightness) lerp(theme.main, Color.Black, 0.16f) else theme.main

    var sleepPeek by remember { mutableStateOf(false) }
    LaunchedEffect(sleeping, sleepPeek) {
        if (!sleeping) {
            sleepPeek = false
        } else if (sleepPeek) {
            // 留足时间让用户看清并点到右上角的唤醒按钮
            delay(15_000)
            sleepPeek = false
        }
    }

    var lastInteraction by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(lastInteraction) {
        controlsVisible = true
        delay(10_000)
        controlsVisible = false
    }

    // 切皮肤后短暂显示名字，否则用户不知道双击切到了哪一套
    var themeToast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(themeToast) {
        if (themeToast != null) {
            delay(1_600)
            themeToast = null
        }
    }

    var shiftSeed by remember { mutableIntStateOf(0x9E3779B9.toInt()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(120_000)
            shiftSeed = xorshift(shiftSeed)
        }
    }
    val (shiftX, shiftY) = antiBurnOffset(shiftSeed, settings.burnShiftLevel)

    val showUi = !sleeping || sleepPeek

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.bg)
            .pointerInput(sleeping) {
                detectTapGestures(
                    onTap = {
                        if (sleeping) sleepPeek = true else lastInteraction = System.nanoTime()
                    },
                    onDoubleTap = {
                        if (!sleeping) {
                            themeToast = Themes.nextChoice(settings.themeId).name
                            onCycleTheme()
                        }
                    },
                    onLongPress = {
                        if (!sleeping) onOpenSettings()
                    },
                )
            }
            .pointerInput(sleeping) {
                val threshold = 70.dp.toPx()
                var accumulated = 0f
                detectHorizontalDragGestures(
                    onDragStart = { accumulated = 0f },
                    onDragEnd = {
                        if (!sleeping && accumulated < -threshold) onSwipeLeft()
                        accumulated = 0f
                    },
                    onDragCancel = { accumulated = 0f },
                ) { _, dragAmount -> accumulated += dragAmount }
            },
        contentAlignment = Alignment.Center,
    ) {
        // 注意：composable lambda 里不能用 return 提前退出（会让 Compose 的组结构失衡，
        // 运行时抛 ComposerImpl.exitGroup IndexOutOfBounds）。条件包裹是唯一安全的写法。
        if (showUi) {
            ClockFace(
                now = now,
                theme = theme,
                mainColor = mainColor,
                shiftX = shiftX,
                shiftY = shiftY,
                use24h = settings.use24h,
                sleepNote = when {
                    !sleeping -> null
                    manualSleeping -> "休眠中 · 点右上角 ☀ 唤醒"
                    else -> "休眠中 · 08:00 自动恢复（☀ 可提前唤醒）"
                },
            )

            // 今日进度：一条细线，长度每分钟都在变，本身不可能烧屏
            DayProgressLine(
                now = now,
                theme = theme,
                shiftX = shiftX,
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            themeToast?.let {
                Text(
                    text = it,
                    color = theme.accent,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 22.dp),
                )
            }

            if (!sleeping) {
                if (weather != null || gold != null) {
                    TopInfoRow(
                        weather = weather,
                        gold = gold,
                        mainColor = mainColor,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 12.dp)
                        .alpha(if (controlsVisible) 1f else 0f),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CaptureBadge(status = status, theme = theme, settings = settings, mainColor = mainColor)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置", tint = mainColor)
                        }
                        IconButton(onClick = onOpenExport) {
                            Icon(Icons.Filled.Movie, contentDescription = "合成", tint = mainColor)
                        }
                        IconButton(onClick = onEnterSleep) {
                            Icon(Icons.Filled.DarkMode, contentDescription = "休眠", tint = mainColor)
                        }
                    }
                    status.lastError?.let {
                        Text(
                            text = it,
                            color = Color(0xFFFF6B6B),
                            fontSize = 11.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(240.dp),
                        )
                    }
                }
            }

            // 休眠中只有 peek 的几秒内露出唤醒按钮，避免黑屏上挂着控件
            if (sleeping && sleepPeek) {
                IconButton(
                    onClick = onWakeUp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 12.dp),
                ) {
                    Icon(Icons.Filled.LightMode, contentDescription = "唤醒", tint = mainColor)
                }
            }

            if (permissionNeeded) {
                PermissionCard(theme, onRequestPermission)
            }
        }
    }
}

/** 今日进度细线：贴屏幕底边，长度 = 已过分钟 / 1440 */
@Composable
private fun DayProgressLine(
    now: LocalDateTime,
    theme: ClockTheme,
    shiftX: Dp,
    modifier: Modifier = Modifier,
) {
    val progress = (now.hour * 60 + now.minute) / 1440f
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .height(4.dp)
            .graphicsLayer { translationX = shiftX.toPx() },
    ) {
        drawRect(color = theme.dim.copy(alpha = 0.2f), size = size)
        drawRect(
            color = theme.accent.copy(alpha = 0.8f),
            size = Size(size.width * progress, size.height),
        )
    }
}

@Composable
private fun ClockFace(
    now: LocalDateTime,
    theme: ClockTheme,
    mainColor: Color,
    shiftX: Dp,
    shiftY: Dp,
    use24h: Boolean,
    sleepNote: String?,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val bigSize = with(density) {
            minOf(maxWidth * 0.30f, maxHeight * 0.52f).toSp()
        }
        val hour = if (use24h) now.hour else ((now.hour + 11) % 12 + 1)
        val amPm = if (use24h) "" else if (now.hour < 12) "上午 " else "下午 "
        val twoDigit = String.format(Locale.getDefault(), "%02d", hour)
        val minuteTwo = String.format(Locale.getDefault(), "%02d", now.minute)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                translationX = shiftX.toPx()
                translationY = shiftY.toPx()
            },
        ) {
            // 每个数字占固定宽度的格位：系统默认字体的数字宽度随字形变化（vivo 尤甚），
            // 直接拼一个长字符串会导致整块时钟左右跳动。格位宽度取 0-9 中最宽的字形。
            val measurer = rememberTextMeasurer()
            val bigStyle = remember(bigSize, theme.weight) {
                TextStyle(fontSize = bigSize, fontWeight = theme.weight, letterSpacing = 2.sp)
            }
            val bigDigitW = remember(bigStyle) {
                with(density) {
                    ('0'..'9').maxOf { measurer.measure(it.toString(), bigStyle).size.width }.toDp()
                }
            }

            Row {
                if (amPm.isNotEmpty()) {
                    Text(
                        text = amPm.trim(),
                        color = theme.dim,
                        fontSize = bigSize * 0.16f,
                        fontWeight = theme.weight,
                        modifier = Modifier
                            .padding(end = with(density) { (bigSize * 0.06f).toPx().toDp() })
                            .alignByBaseline(),
                    )
                }
                DigitRow(twoDigit, mainColor, bigSize, bigDigitW, theme.weight)
                Text(
                    text = ":",
                    color = mainColor,
                    fontSize = bigSize,
                    fontWeight = theme.weight,
                    letterSpacing = 2.sp,
                    modifier = Modifier.alignByBaseline(),
                )
                DigitRow(minuteTwo, mainColor, bigSize, bigDigitW, theme.weight)
            }
            val dayInfo = remember(now.toLocalDate()) { dayInfoOf(now.toLocalDate()) }
            val dateFmt = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
            val weekdayFmt = DateTimeFormatter.ofPattern("EEEE", Locale.CHINA)
            Text(
                text = buildAnnotatedString {
                    append(now.format(dateFmt))
                    withStyle(SpanStyle(color = theme.dim.copy(alpha = 0.75f))) {
                        append(" ${dayInfo.lunarText}")
                    }
                    append(" ${now.format(weekdayFmt)}")
                    dayInfo.specialText?.let {
                        withStyle(SpanStyle(color = theme.accent)) {
                            append(" $it")
                        }
                    }
                },
                color = theme.dim,
                fontSize = bigSize * 0.13f,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(top = 6.dp),
            )
            sleepNote?.let {
                Text(
                    text = it,
                    color = theme.dim.copy(alpha = 0.5f),
                    fontSize = bigSize * 0.07f,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

/**
 * 按固定宽度格位逐个渲染数字，格宽取 0-9 中最宽字形。
 * 系统默认字体多为比例数字（vivo/OriginOS 尤甚），整串渲染会让时钟总宽度随数字变化而抖动。
 */
@Composable
private fun RowScope.DigitRow(
    digits: String,
    color: Color,
    fontSize: TextUnit,
    slotWidth: Dp,
    weight: FontWeight,
) {
    digits.forEach { ch ->
        Text(
            text = ch.toString(),
            color = color,
            fontSize = fontSize,
            fontWeight = weight,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .width(slotWidth)
                .alignByBaseline(),
        )
    }
}

@Composable
private fun CaptureBadge(
    status: CaptureStatus,
    theme: ClockTheme,
    settings: AppSettings,
    mainColor: Color,
) {
    val sleeping = status.sleeping
    // 按需绑定模式下，拍与拍之间相机处于释放状态（bound=false），
    // 循环仍在跑时视为正常延时中，只有循环没起来才提示等待相机
    val waitingCamera = !status.bound && !status.loopActive
    val dotColor = when {
        sleeping -> theme.dim.copy(alpha = 0.5f)
        status.lastError != null -> Color(0xFFFF6B6B)
        waitingCamera -> Color(0xFFFFC857)
        settings.paused -> theme.dim
        status.loopActive -> Color(0xFF5BD97A)
        else -> Color(0xFFFFC857)
    }
    val stateLabel = when {
        sleeping -> "休眠中"
        settings.paused -> "已暂停"
        status.lastError != null -> "出错"
        waitingCamera -> "等待相机"
        status.loopActive -> if (status.capturing) "拍摄中" else "延时中"
        else -> "启动中"
    }

    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF1A1A1A)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Canvas(modifier = Modifier.size(8.dp)) { drawCircle(color = dotColor) }
            Text(text = stateLabel, color = mainColor, fontSize = 12.sp)
            Text(
                text = "今日 ${status.todayCount} · 共 ${status.totalCount}",
                color = theme.dim,
                fontSize = 12.sp,
            )
            if (status.idle && !sleeping) {
                Text(text = "闲时 ${settings.idleIntervalSec}s", color = theme.accent, fontSize = 12.sp)
            }
            if (status.skipped > 0) {
                Text(text = "跳过 ${status.skipped}", color = theme.dim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PermissionCard(theme: ClockTheme, onRequestPermission: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF161616)) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "需要相机权限才能开始延时拍照",
                color = theme.main,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E6BFF)),
            ) {
                Text(text = "授予权限", color = Color.White)
            }
        }
    }
}
