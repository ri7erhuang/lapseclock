package com.lapseclock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 占位内容，数据源接入后替换 */
private const val PLACEHOLDER_TEXT = "多云 26°"

private val WEATHER_ACCENT = Color(0xFF9EC8E3)

/**
 * 左上角天气面板（占位数据）。
 * 防烧屏运动：每 2 分钟水平平移 10dp，右移至面板右缘到达屏幕中线后掉头，
 * 左移回初始位置后再掉头，往复如三角波；垂直方向不动。
 */
@Composable
fun WeatherPanel(
    mainColor: Color,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        var panelWidth by remember { mutableStateOf(0.dp) }
        var driftX by remember { mutableStateOf(0.dp) }
        var direction by remember { mutableIntStateOf(1) }

        // 面板初始左缘 = 12dp；最大位移 = 屏幕中线 - 初始右缘
        val maxDrift = maxWidth / 2 - 12.dp - panelWidth

        LaunchedEffect(maxDrift) {
            while (true) {
                delay(120_000)
                if (maxDrift <= 0.dp) continue
                val next = driftX + 10.dp * direction
                driftX = next.coerceIn(0.dp, maxDrift)
                direction = when {
                    driftX >= maxDrift -> -1
                    driftX <= 0.dp -> 1
                    else -> direction
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF1A1A1A),
            modifier = Modifier
                .padding(start = 12.dp, top = 8.dp)
                .graphicsLayer { translationX = driftX.toPx() }
                .onSizeChanged { panelWidth = with(density) { it.width.toDp() } },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Canvas(modifier = Modifier.size(8.dp)) { drawCircle(color = WEATHER_ACCENT) }
                Text(text = PLACEHOLDER_TEXT, color = mainColor, fontSize = 12.sp)
            }
        }
    }
}
