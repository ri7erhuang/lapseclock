package com.lapseclock.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lapseclock.capture.PhotoStore
import com.lapseclock.video.ExportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val expBg = Color(0xFF0B0B0B)
private val expText = Color(0xFFF2F2F2)
private val expDim = Color(0xFF8A8A8A)
private val expAccent = Color(0xFF2E6BFF)
private val expOk = Color(0xFF5BD97A)
private val expErr = Color(0xFFFF6B6B)

private val chipFmt = DateTimeFormatter.ofPattern("MM/dd", Locale.US)

@Composable
fun ExportScreen(
    store: PhotoStore,
    state: ExportState,
    onDismiss: () -> Unit,
    onStart: (LocalDate, LocalDate, Int, Int, Int) -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
) {
    // 日期列表在 IO 线程读索引，不阻塞 UI；加载完成前不提前 return，避免 Compose 组结构变化
    var dates by remember { mutableStateOf<List<LocalDate>?>(null) }
    LaunchedEffect(Unit) {
        dates = withContext(Dispatchers.IO) {
            store.ensureIndex()
            store.dates()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(expBg)) {
        when (val loaded = dates) {
            null -> LoadingState()
            else -> if (loaded.isEmpty()) {
                EmptyState(onDismiss)
            } else {
                ExportContent(
                    store = store,
                    dates = loaded,
                    state = state,
                    onDismiss = onDismiss,
                    onStart = onStart,
                    onCancel = onCancel,
                    onReset = onReset,
                )
            }
        }
    }
}

@Composable
private fun ExportContent(
    store: PhotoStore,
    dates: List<LocalDate>,
    state: ExportState,
    onDismiss: () -> Unit,
    onStart: (LocalDate, LocalDate, Int, Int, Int) -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current

    var from by remember(dates) { mutableStateOf(dates.first()) }
    var to by remember(dates) { mutableStateOf(dates.last()) }
    var fps by remember { mutableStateOf(30) }
    var outWidth by remember { mutableStateOf(1920) }
    var outHeight by remember { mutableStateOf(1080) }

    // 用局部夹取代替「组合期写 state」：dates 变化（如清理掉最旧一天）时
    // from/to 可能越界，这里只夹取用于计算，不触发额外重组
    val minDate = dates.first()
    val maxDate = dates.last()
    val clampedFrom = from.coerceIn(minDate, maxDate)
    val clampedTo = to.coerceIn(minDate, maxDate)
    val realFrom = if (clampedFrom.isAfter(clampedTo)) clampedTo else clampedFrom
    val realTo = if (clampedFrom.isAfter(clampedTo)) clampedFrom else clampedTo

    // 导出过程中不刷新；空闲时每 2 秒补一次，今天新拍的片子能立刻算进去
    var liveTick by remember { mutableIntStateOf(0) }
    val estimating = state !is ExportState.Running
    LaunchedEffect(estimating, realFrom, realTo) {
        while (estimating) {
            liveTick++
            delay(2000)
        }
    }
    val selectedFrames = remember(liveTick, realFrom, realTo) {
        store.countBetween(realFrom, realTo)
    }
    val estimateSec = if (fps > 0) selectedFrames.toFloat() / fps else 0f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "返回", tint = expText)
            }
            Text("合成延时视频", color = expText, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionLabel("日期范围")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DatePickerButton(store, "起 " + realFrom.format(chipFmt), dates) { from = it }
                DatePickerButton(store, "止 " + realTo.format(chipFmt), dates) { to = it }
                OutlineChip("全部") {
                    from = dates.first()
                    to = dates.last()
                }
                OutlineChip("今天") {
                    val today = LocalDate.now()
                    val d = if (dates.contains(today)) today else dates.last()
                    from = d
                    to = d
                }
            }

            SectionLabel("帧率")
            OptionRow(
                options = listOf("24" to 24, "30" to 30, "60" to 60),
                selected = fps,
                onSelect = { fps = it },
            )

            SectionLabel("输出分辨率")
            OptionRow(
                options = listOf("1920×1080" to (1920 to 1080), "1280×720" to (1280 to 720)),
                selected = outWidth to outHeight,
                onSelect = { outWidth = it.first; outHeight = it.second },
            )

            Text(
                text = "选中 $selectedFrames 张 · 预计成片 ${formatDuration(estimateSec)} · 码率估算约 " +
                    formatBytes(estimateSec * outWidth * outHeight * fps / 10f / 8f),
                color = expDim,
                fontSize = 13.sp,
            )

            when (state) {
                is ExportState.Idle -> StartButton(frames = selectedFrames) {
                    onStart(realFrom, realTo, fps, outWidth, outHeight)
                }

                is ExportState.Running -> {
                    if (state.total <= 0) {
                        Text("正在读取照片…", color = expDim, fontSize = 13.sp)
                    } else {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = expAccent,
                            trackColor = Color(0xFF1C1C1C),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "编码中 ${state.done}/${state.total}",
                                color = expDim,
                                fontSize = 13.sp,
                            )
                            Text(
                                text = "点击取消",
                                color = expErr,
                                fontSize = 13.sp,
                                modifier = Modifier.clickable { onCancel() },
                            )
                        }
                    }
                }

                is ExportState.Done -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = if (state.auto) {
                                "✓ 昨日延时已自动合成 · ${state.from} · ${state.frames} 帧 · " +
                                    formatDuration(state.durationMs / 1000f)
                            } else {
                                "✓ 已保存到相册 Movies/Timelapse · ${state.frames} 帧 · " +
                                    formatDuration(state.durationMs / 1000f)
                            },
                            color = expOk,
                            fontSize = 14.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    val view = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(state.uri, "video/mp4")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    runCatching {
                                        context.startActivity(Intent.createChooser(view, "播放视频"))
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = expAccent),
                            ) { Text("播放", color = Color.White) }

                            Button(
                                onClick = {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "video/mp4"
                                        putExtra(Intent.EXTRA_STREAM, state.uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    runCatching {
                                        context.startActivity(Intent.createChooser(send, "分享视频"))
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C1C1C)),
                            ) { Text("分享", color = expText) }

                            OutlinedButton(onClick = onReset) {
                                Text("再合成一次", color = expText)
                            }
                        }
                    }
                }

                is ExportState.Failed -> {
                    Text(text = "合成失败：${state.message}", color = expErr, fontSize = 13.sp)
                    StartButton(frames = selectedFrames) {
                        onStart(realFrom, realTo, fps, outWidth, outHeight)
                    }
                }

                is ExportState.Cancelled -> {
                    Text(text = "已取消", color = expDim, fontSize = 13.sp)
                    StartButton(frames = selectedFrames) {
                        onStart(realFrom, realTo, fps, outWidth, outHeight)
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("正在读取照片…", color = expDim, fontSize = 14.sp)
    }
}

@Composable
private fun EmptyState(onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("还没有照片", color = expDim, fontSize = 15.sp)
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onDismiss) { Text("返回", color = expText) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text = text, color = expDim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun DatePickerButton(
    store: PhotoStore,
    label: String,
    dates: List<LocalDate>,
    onSelect: (LocalDate) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlineChip(label) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            dates.reversed().forEach { d ->
                DropdownMenuItem(
                    text = { Text("${d.format(chipFmt)} · ${store.countOn(d)} 张") },
                    onClick = {
                        onSelect(d)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun OutlineChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Color(0xFF1C1C1C), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(label, color = expText, fontSize = 13.sp)
    }
}

@Composable
private fun StartButton(frames: Int, onClick: () -> Unit) {
    val enabled = frames > 0
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = expAccent,
            disabledContainerColor = Color(0xFF1C1C1C),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("开始合成", color = if (enabled) Color.White else expDim, fontSize = 15.sp)
    }
}

fun formatDuration(seconds: Float): String {
    val total = seconds.toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

private fun formatBytes(bytes: Float): String = when {
    bytes >= 1 shl 30 -> String.format(Locale.US, "%.2f GB", bytes / 1024f / 1024f / 1024f)
    bytes >= 1 shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
    else -> String.format(Locale.US, "%.0f KB", bytes / 1024f)
}
