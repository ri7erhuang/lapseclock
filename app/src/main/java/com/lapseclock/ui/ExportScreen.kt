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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

private const val COLUMNS = 5

/**
 * 手动合成页：列出所有有照片的日期（带张数），用户多选后合成选中的日期。
 * 合成成功后弹窗询问是否删除这些日期的源照片 —— 删除是显式确认的动作，
 * 不做任何后台自动清理，避免"照片莫名消失"。
 */
@Composable
fun ExportScreen(
    store: PhotoStore,
    state: ExportState,
    onDismiss: () -> Unit,
    onStart: (Collection<LocalDate>, Int, Int, Int) -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    onDeleteSources: (Collection<LocalDate>) -> Unit,
) {
    // 日期与张数在 IO 线程读索引，不阻塞 UI；加载完成前不提前 return，避免 Compose 组结构变化
    var days by remember { mutableStateOf<List<PhotoStore.DayCount>?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(reloadKey) {
        days = withContext(Dispatchers.IO) {
            store.ensureIndex()
            store.dayCounts()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(expBg)) {
        when (val loaded = days) {
            null -> LoadingState()
            else -> if (loaded.isEmpty()) {
                EmptyState(onDismiss)
            } else {
                ExportContent(
                    days = loaded,
                    state = state,
                    onDismiss = onDismiss,
                    onStart = onStart,
                    onCancel = onCancel,
                    onReset = onReset,
                    onDeleteSources = onDeleteSources,
                    onSourcesDeleted = { reloadKey++ },
                )
            }
        }
    }
}

@Composable
private fun ExportContent(
    days: List<PhotoStore.DayCount>,
    state: ExportState,
    onDismiss: () -> Unit,
    onStart: (Collection<LocalDate>, Int, Int, Int) -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    onDeleteSources: (Collection<LocalDate>) -> Unit,
    onSourcesDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val today = remember { LocalDate.now() }
    val allDates = remember(days) { days.map { it.date } }

    // 默认全选：进来通常就是想把攒下的都合了
    var selected by remember(days) { mutableStateOf(allDates.toSet()) }
    var fps by remember { mutableStateOf(30) }
    var outWidth by remember { mutableStateOf(1920) }
    var outHeight by remember { mutableStateOf(1080) }

    // 合成完成后待确认的删除目标
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }

    LaunchedEffect(state) {
        if (state is ExportState.Done && !state.auto) {
            val count = days.filter { it.date in state.dates }.sumOf { it.count }
            pendingDelete = PendingDelete(state.dates.toList(), count)
        }
    }

    val selectedDays = days.filter { it.date in selected }
    val selectedFrames = selectedDays.sumOf { it.count }
    val estimateSec = if (fps > 0) selectedFrames.toFloat() / fps else 0f
    val running = state is ExportState.Running

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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("选择日期（可多选）")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineChip("全选") { selected = allDates.toSet() }
                    OutlineChip("清空") { selected = emptySet() }
                    OutlineChip("今天") {
                        selected = if (today in allDates) setOf(today) else setOf(allDates.last())
                    }
                }
            }

            days.chunked(COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item ->
                        DayChip(
                            item = item,
                            isToday = item.date == today,
                            selected = item.date in selected,
                            enabled = !running,
                            onClick = {
                                selected = if (item.date in selected) selected - item.date
                                else selected + item.date
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
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
                text = "已选 ${selected.size} 天 · $selectedFrames 张 · 预计成片 " +
                    "${formatDuration(estimateSec)}",
                color = if (selectedFrames > 0) expDim else expErr,
                fontSize = 13.sp,
            )

            when (state) {
                is ExportState.Idle -> StartButton(frames = selectedFrames) {
                    onStart(selected, fps, outWidth, outHeight)
                }

                is ExportState.Running -> {
                    if (state.total <= 0) {
                        Text("正在读取照片…", color = expDim, fontSize = 13.sp)
                    } else {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = expAccent,
                            trackColor = Color(0xFF1C1C1C),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("编码中 ${state.done}/${state.total}", color = expDim, fontSize = 13.sp)
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
                            text = "✓ 已保存到相册 Movies/Timelapse · ${state.frames} 帧 · " +
                                formatDuration(state.durationMs / 1000f),
                            color = expOk,
                            fontSize = 14.sp,
                        )
                        if (pendingDelete == null) {
                            Text(
                                text = "源照片已保留。",
                                color = expDim,
                                fontSize = 12.sp,
                            )
                        }
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

                            OutlinedButton(onClick = {
                                pendingDelete = null
                                onReset()
                            }) {
                                Text("再合成一次", color = expText)
                            }
                        }
                    }
                }

                is ExportState.Failed -> {
                    Text(text = "合成失败：${state.message}", color = expErr, fontSize = 13.sp)
                    StartButton(frames = selectedFrames) {
                        onStart(selected, fps, outWidth, outHeight)
                    }
                }

                is ExportState.Cancelled -> {
                    Text(text = "已取消", color = expDim, fontSize = 13.sp)
                    StartButton(frames = selectedFrames) {
                        onStart(selected, fps, outWidth, outHeight)
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    pendingDelete?.let { target ->
        DeleteSourcesDialog(
            target = target,
            onDelete = {
                onDeleteSources(target.dates)
                pendingDelete = null
                onSourcesDeleted()
                onReset()
            },
            onKeep = { pendingDelete = null },
        )
    }
}

private data class PendingDelete(val dates: List<LocalDate>, val count: Int)

@Composable
private fun DeleteSourcesDialog(
    target: PendingDelete,
    onDelete: () -> Unit,
    onKeep: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeep,
        containerColor = Color(0xFF161616),
        title = { Text("合成完成", color = expText, fontSize = 16.sp) },
        text = {
            Text(
                text = "视频已保存到相册。是否删除这 ${target.dates.size} 天的源照片？\n" +
                    "共 ${target.count} 张。删除后无法恢复（视频不受影响）。",
                color = expDim,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text("删除源照片", color = expErr, fontSize = 14.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep) {
                Text("保留", color = expText, fontSize = 14.sp)
            }
        },
    )
}

@Composable
private fun DayChip(
    item: PhotoStore.DayCount,
    isToday: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(
                color = if (selected) expAccent else Color(0xFF1C1C1C),
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (isToday) "今天" else item.date.format(chipFmt),
            color = if (selected) Color.White else expText,
            fontSize = 13.sp,
        )
        Text(
            text = "${item.count} 张",
            color = if (selected) Color.White.copy(alpha = 0.85f) else expDim,
            fontSize = 11.sp,
        )
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
        modifier = Modifier.fillMaxSize().padding(24.dp),
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
