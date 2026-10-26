package com.lapseclock.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lapseclock.ui.theme.ClockTheme
import kotlinx.coroutines.delay
import java.util.Locale

private const val FOCUS_SEC = 25 * 60
private const val BREAK_SEC = 5 * 60

enum class PomodoroPhase { Focus, Break }

/**
 * 番茄钟的计时状态放在上层（LapseApp），不放在页面里：
 * 用户可以右滑回主页看时间，专注倒计时必须继续走。
 */
class PomodoroUi(
    val phase: PomodoroPhase,
    val remainingSec: Int,
    val running: Boolean,
    val finished: Int,
    val toggle: () -> Unit,
    val reset: () -> Unit,
) {
    val label: String get() = if (phase == PomodoroPhase.Focus) "专注" else "休息"
    val clockText: String
        get() = String.format(Locale.US, "%02d:%02d", remainingSec / 60, remainingSec % 60)
    val progress: Float
        get() {
            val total = if (phase == PomodoroPhase.Focus) FOCUS_SEC else BREAK_SEC
            return if (total <= 0) 0f else 1f - remainingSec.toFloat() / total
        }
}

@Composable
fun rememberPomodoro(): PomodoroUi {
    var phase by remember { mutableStateOf(PomodoroPhase.Focus) }
    var remaining by remember { mutableIntStateOf(FOCUS_SEC) }
    var running by remember { mutableStateOf(false) }
    var finished by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    LaunchedEffect(running) {
        while (running) {
            delay(1_000L)
            if (remaining > 1) {
                remaining -= 1
            } else {
                // 阶段结束：震动提醒后自动进入下一阶段，工位上不用再点一次
                vibrate(context)
                if (phase == PomodoroPhase.Focus) {
                    finished += 1
                    phase = PomodoroPhase.Break
                    remaining = BREAK_SEC
                } else {
                    phase = PomodoroPhase.Focus
                    remaining = FOCUS_SEC
                }
            }
        }
    }

    return PomodoroUi(
        phase = phase,
        remainingSec = remaining,
        running = running,
        finished = finished,
        toggle = {
            // 结束后点开始，从当前阶段的剩余时间继续（已自动重置过）
            running = !running
        },
        reset = {
            running = false
            phase = PomodoroPhase.Focus
            remaining = FOCUS_SEC
        },
    )
}

private fun vibrate(context: Context) {
    val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
    if (!v.hasVibrator()) return
    runCatching {
        // 两个短震：够醒目，又不会在安静的工位上吵到人
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 120, 180), -1))
    }
}

/**
 * 番茄钟全屏页。点击中间的大数字开始 / 暂停，右滑返回。
 * 返回只是收起页面，倒计时继续在后台走。
 */
@Composable
fun PomodoroScreen(
    pomodoro: PomodoroUi,
    theme: ClockTheme,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mainColor = theme.main
    val accent = if (pomodoro.phase == PomodoroPhase.Focus) theme.lead else theme.accent

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
                        if (accumulated > threshold) onDismiss()
                        accumulated = 0f
                    },
                    onDragCancel = { accumulated = 0f },
                ) { _, dragAmount -> accumulated += dragAmount }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = pomodoro.label,
                color = accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 6.sp,
            )

            // 点大数字开始/暂停：整个屏幕最显眼的东西就是按钮，不用另找控件
            Text(
                text = pomodoro.clockText,
                color = mainColor,
                fontSize = 132.sp,
                fontWeight = FontWeight.Thin,
                modifier = Modifier
                    .clickable { pomodoro.toggle() }
                    .padding(horizontal = 16.dp),
            )

            Text(
                text = when {
                    pomodoro.running -> "点击数字暂停"
                    pomodoro.remainingSec == FOCUS_SEC && pomodoro.finished == 0 -> "点击数字开始"
                    else -> "已暂停 · 点击继续"
                },
                color = theme.dim,
                fontSize = 13.sp,
            )

            Row(
                modifier = Modifier.padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pomodoro.finished > 0) {
                    Text(
                        text = "今天完成 ${pomodoro.finished} 轮",
                        color = theme.dim,
                        fontSize = 13.sp,
                    )
                }
                Box(
                    modifier = Modifier
                        .background(Color(0xFF1C1C1C), RoundedCornerShape(8.dp))
                        .clickable { pomodoro.reset() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text("重置", color = theme.main, fontSize = 13.sp)
                }
            }
        }

        Text(
            text = "右滑返回 · 计时不会停",
            color = theme.dim.copy(alpha = 0.6f),
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp),
        )
    }
}
