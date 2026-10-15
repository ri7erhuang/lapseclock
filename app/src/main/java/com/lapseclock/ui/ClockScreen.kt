package com.lapseclock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lapseclock.capture.CaptureStatus
import com.lapseclock.isSleepHour
import com.lapseclock.settings.AppSettings
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val pureWhite = Color(0xFFF2F2F2)
private val softWhite = Color(0xFFD8D8D8)
private val clockDim = Color(0xFF8A8A8A)

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

@Composable
fun ClockScreen(
    settings: AppSettings,
    status: CaptureStatus,
    permissionNeeded: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    val tickMs = if (settings.showSeconds) 200L else 1000L
    LaunchedEffect(settings.showSeconds) {
        while (true) {
            now = LocalDateTime.now()
            delay(tickMs)
        }
    }

    val sleeping = isSleepHour(now.hour)
    var sleepPeek by remember { mutableStateOf(false) }
    LaunchedEffect(sleeping, sleepPeek) {
        if (!sleeping) {
            sleepPeek = false
        } else if (sleepPeek) {
            delay(6000)
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

    var shiftSeed by remember { mutableIntStateOf(0x9E3779B9.toInt()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(120_000)
            shiftSeed = xorshift(shiftSeed)
        }
    }
    val (shiftX, shiftY) = antiBurnOffset(shiftSeed, settings.burnShiftLevel)

    var colonAlpha by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            val start = System.nanoTime()
            while (true) {
                val tMs = (System.nanoTime() - start) / 1_000_000.0
                if (tMs >= 4000.0) break
                val phase = (tMs / 4000.0).toFloat()
                val tri = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
                colonAlpha = 1f - 0.4f * tri
                delay(50)
            }
        }
    }

    val mainColor = if (settings.softBrightness) softWhite else pureWhite
    val showUi = !sleeping || sleepPeek

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(sleeping) {
                detectTapGestures {
                    if (sleeping) sleepPeek = true
                    else lastInteraction = System.nanoTime()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (!showUi) return@Box

        ClockFace(
            now = now,
            settings = settings,
            mainColor = mainColor,
            colonAlpha = colonAlpha,
            shiftX = shiftX,
            shiftY = shiftY,
            sleepNote = if (sleeping) "休眠中 · 08:00 恢复" else null,
        )

        if (!sleeping) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 12.dp)
                    .alpha(if (controlsVisible) 1f else 0f),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CaptureBadge(status = status, settings = settings, mainColor = mainColor)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "设置",
                            tint = mainColor,
                        )
                    }
                    IconButton(onClick = onOpenExport) {
                        Icon(
                            imageVector = Icons.Filled.Movie,
                            contentDescription = "合成",
                            tint = mainColor,
                        )
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

        if (permissionNeeded) {
            PermissionCard(onRequestPermission)
        }
    }
}

@Composable
private fun ClockFace(
    now: LocalDateTime,
    settings: AppSettings,
    mainColor: Color,
    colonAlpha: Float,
    shiftX: Dp,
    shiftY: Dp,
    sleepNote: String?,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val bigSize = with(androidx.compose.ui.platform.LocalDensity.current) {
            minOf(maxWidth * 0.30f, maxHeight * 0.52f).toSp()
        }
        val hour = if (settings.use24h) now.hour else ((now.hour + 11) % 12 + 1)
        val amPm = if (settings.use24h) "" else if (now.hour < 12) "上午 " else "下午 "
        val twoDigit = String.format(Locale.getDefault(), "%02d", hour)
        val minuteTwo = String.format(Locale.getDefault(), "%02d", now.minute)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                translationX = shiftX.toPx()
                translationY = shiftY.toPx()
            },
        ) {
            Text(
                text = buildAnnotatedString {
                    if (amPm.isNotEmpty()) {
                        withStyle(SpanStyle(color = clockDim, fontSize = bigSize * 0.16f)) {
                            append(amPm)
                        }
                    }
                    withStyle(SpanStyle(color = mainColor, fontSize = bigSize)) {
                        append(twoDigit)
                    }
                    withStyle(
                        SpanStyle(color = mainColor.copy(alpha = colonAlpha), fontSize = bigSize)
                    ) {
                        append(":")
                    }
                    withStyle(SpanStyle(color = mainColor, fontSize = bigSize)) {
                        append(minuteTwo)
                    }
                    if (settings.showSeconds) {
                        withStyle(SpanStyle(color = clockDim, fontSize = bigSize * 0.30f)) {
                            append(String.format(Locale.getDefault(), ":%02d", now.second))
                        }
                    }
                },
                fontWeight = FontWeight.Thin,
                letterSpacing = 2.sp,
            )
            val dateFmt = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)
            Text(
                text = now.format(dateFmt),
                color = clockDim,
                fontSize = bigSize * 0.13f,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(top = 6.dp),
            )
            sleepNote?.let {
                Text(
                    text = it,
                    color = Color(0xFF4A4A4A),
                    fontSize = bigSize * 0.07f,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun CaptureBadge(status: CaptureStatus, settings: AppSettings, mainColor: Color) {
    val sleeping = status.sleeping
    val dotColor = when {
        sleeping -> Color(0xFF4A4A4A)
        status.lastError != null -> Color(0xFFFF6B6B)
        !status.bound -> Color(0xFFFFC857)
        settings.paused -> Color(0xFF8A8A8A)
        status.loopActive -> Color(0xFF5BD97A)
        else -> Color(0xFFFFC857)
    }
    val stateLabel = when {
        sleeping -> "休眠中"
        settings.paused -> "已暂停"
        status.lastError != null -> "出错"
        !status.bound -> "等待相机"
        status.loopActive -> if (status.capturing) "拍摄中" else "延时中"
        else -> "启动中"
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1A1A1A),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Canvas(modifier = Modifier.size(8.dp)) { drawCircle(color = dotColor) }
            Text(text = stateLabel, color = mainColor, fontSize = 12.sp)
            Text(
                text = "今日 ${status.todayCount} · 共 ${status.totalCount}",
                color = clockDim,
                fontSize = 12.sp,
            )
            if (status.skipped > 0) {
                Text(text = "跳过 ${status.skipped}", color = clockDim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PermissionCard(onRequestPermission: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF161616),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "需要相机权限才能开始延时拍照",
                color = pureWhite,
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
