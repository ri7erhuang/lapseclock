package com.lapseclock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lapseclock.capture.CaptureStatus
import com.lapseclock.capture.PhotoStore
import com.lapseclock.settings.AppSettings
import com.lapseclock.ui.theme.Themes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private val panelBg = Color(0xFF0B0B0B)
private val panelText = Color(0xFFF2F2F2)
private val panelDim = Color(0xFF8A8A8A)
private val panelAccent = Color(0xFF2E6BFF)
private val panelErr = Color(0xFFFF6B6B)

private data class StoreStats(
    val today: Int,
    val total: Int,
    val bytes: Long,
    val free: Long,
)

@Composable
fun SettingsSheet(
    settings: AppSettings,
    status: CaptureStatus,
    store: PhotoStore,
    onDismiss: () -> Unit,
    onTakeShot: () -> Unit,
    onSetInterval: (Int) -> Unit,
    onSetResolution: (Int, Int) -> Unit,
    onSetUse24h: (Boolean) -> Unit,
    onSetPaused: (Boolean) -> Unit,
    onSetAutoClean: (Int) -> Unit,
    onSetSoftBrightness: (Boolean) -> Unit,
    onSetBurnShiftLevel: (Int) -> Unit,
    onSetWatermarkEnabled: (Boolean) -> Unit,
    onSetWatermarkText: (String) -> Unit,
    onSetWatermarkShowTime: (Boolean) -> Unit,
    onSetWeatherCity: (String) -> Unit,
    onSetUseLocation: (Boolean) -> Unit,
    onSetThemeId: (String) -> Unit,
    onSetAutoExport: (Boolean) -> Unit,
) {
    var stats by remember { mutableStateOf<StoreStats?>(null) }
    var latest by remember { mutableStateOf<File?>(null) }
    var fullPreview by remember { mutableStateOf(false) }

    // 索引查询是内存操作，但仍然放到 IO，避免首帧建索引卡在组合里
    LaunchedEffect(status.lastShotAt, settings.autoCleanDays) {
        stats = withContext(Dispatchers.IO) {
            store.ensureIndex()
            StoreStats(
                today = store.countToday(),
                total = store.totalCount(),
                bytes = store.totalBytes(),
                free = store.freeBytes(),
            )
        }
        latest = withContext(Dispatchers.IO) {
            store.ensureIndex()
            store.latest()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(panelBg)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "返回", tint = panelText)
            }
            Text(
                text = "设置",
                color = panelText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SectionTitle("取景 / 试拍")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhotoImage(
                    file = latest,
                    modifier = Modifier
                        .width(150.dp)
                        .aspectRatio(4f / 3f)
                        .background(Color(0xFF141414), RoundedCornerShape(8.dp))
                        .clickable(enabled = latest != null) { fullPreview = true },
                    contentScale = ContentScale.Crop,
                    maxWidth = 480,
                    maxHeight = 480,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "无预览拍照，装好之前先拍一张确认取景。",
                        color = panelDim,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                    Button(
                        onClick = onTakeShot,
                        enabled = !status.storageLow,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = panelAccent,
                            disabledContainerColor = Color(0xFF1C1C1C),
                        ),
                    ) {
                        Text("立即拍一张", color = Color.White, fontSize = 13.sp)
                    }
                    Text(
                        text = latest?.let { "最近一张 ${storeTimeLabel(it)} · 点图放大" } ?: "还没有照片",
                        color = panelDim,
                        fontSize = 11.sp,
                    )
                }
            }
            status.lastError?.let {
                Text(text = it, color = panelErr, fontSize = 12.sp, lineHeight = 18.sp)
            }

            SectionTitle("拍照间隔")
            OptionRow(
                options = listOf("2s" to 2, "5s" to 5, "10s" to 10, "15s" to 15, "30s" to 30, "60s" to 60),
                selected = settings.intervalSec,
                onSelect = onSetInterval,
            )

            SectionTitle("目标分辨率")
            OptionRow(
                options = listOf(
                    "640×480" to (640 to 480),
                    "1280×720" to (1280 to 720),
                    "1600×1200" to (1600 to 1200),
                    "1920×1080" to (1920 to 1080),
                ),
                selected = settings.targetWidth to settings.targetHeight,
                onSelect = { onSetResolution(it.first, it.second) },
            )

            SectionTitle("时钟")
            SwitchRow("24 小时制", settings.use24h, onSetUse24h)
            SwitchRow("柔和亮度（防烧屏）", settings.softBrightness, onSetSoftBrightness)

            SectionTitle("时钟主题")
            val choices = Themes.choices
            OptionRow(
                options = choices.take(3).map { it.name to it.id },
                selected = settings.themeId,
                onSelect = onSetThemeId,
            )
            OptionRow(
                options = choices.drop(3).map { it.name to it.id },
                selected = settings.themeId,
                onSelect = onSetThemeId,
            )
            Text(
                text = "自动：按时段换肤（黎明 / 白天 / 黄昏 / 夜晚），并随天气与节日调整强调色。" +
                    "双击时钟可在各主题间快速切换。",
                color = panelDim,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )

            SectionTitle("防烧屏偏移")
            OptionRow(
                options = listOf(
                    "关闭" to -1,
                    "低 30/20" to 0,
                    "中 60/40" to 1,
                    "高 80/60" to 2,
                ),
                selected = settings.burnShiftLevel,
                onSelect = onSetBurnShiftLevel,
            )
            Text(
                text = "数字区域随机偏移范围（dp），档位越高防烧屏效果越好。",
                color = panelDim,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )

            SectionTitle("拍照")
            SwitchRow("暂停拍照", settings.paused, onSetPaused)
            Text(
                text = "休眠时段 22:00–08:00：自动暂停拍照，屏幕纯黑（防烧屏）；点按屏幕可临时查看时间。\n" +
                    "时钟右上角 🌙 可随时手动进入休眠，休眠中轻点屏幕后点 ☀ 退出。\n" +
                    "建议系统亮度不超过 50%，并保持柔和亮度开启。",
                color = panelDim,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )

            SectionTitle("水印")
            SwitchRow("照片与视频带水印", settings.watermarkEnabled, onSetWatermarkEnabled)
            if (settings.watermarkEnabled) {
                OutlinedTextField(
                    value = settings.watermarkText,
                    onValueChange = onSetWatermarkText,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("水印文字", color = panelDim, fontSize = 12.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = panelText,
                        unfocusedTextColor = panelText,
                        cursorColor = panelAccent,
                        focusedBorderColor = panelAccent,
                        unfocusedBorderColor = Color(0xFF333333),
                    ),
                )
                SwitchRow("附加拍摄时间", settings.watermarkShowTime, onSetWatermarkShowTime)
                Text(
                    text = "水印在拍照时烧进 JPEG，导出的延时视频会自动带上。开启后照片会多一次压缩（画质已自动补偿）。",
                    color = panelDim,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }

            SectionTitle("天气")
            SwitchRow("自动定位城市", settings.useLocation, onSetUseLocation)
            if (!settings.useLocation) {
                OutlinedTextField(
                    value = settings.weatherCity,
                    onValueChange = onSetWeatherCity,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("城市名（如 西安）", color = panelDim, fontSize = 12.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = panelText,
                        unfocusedTextColor = panelText,
                        cursorColor = panelAccent,
                        focusedBorderColor = panelAccent,
                        unfocusedBorderColor = Color(0xFF333333),
                    ),
                )
            }
            Text(
                text = if (settings.useLocation) {
                    "根据定位自动获取所在城市；定位失败或未授权时使用备用城市。"
                } else {
                    "定位关闭，按城市名查询；留空则隐藏天气面板。"
                },
                color = panelDim,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )

            SectionTitle("自动清理")
            OptionRow(
                options = listOf("关闭" to 0, "7 天" to 7, "30 天" to 30, "90 天" to 90),
                selected = settings.autoCleanDays,
                onSelect = onSetAutoClean,
            )

            SectionTitle("自动合成")
            SwitchRow("每天自动合成昨日", settings.autoExport, onSetAutoExport)
            Text(
                text = "休眠结束后自动把昨天的照片合成 30fps / 1080p 视频并写入相册；" +
                    "失败会隔 30 分钟重试。上次自动合成：" +
                    settings.lastAutoExportDate.ifBlank { "—" },
                color = panelDim,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )

            SectionTitle("存储")
            val s = stats
            Text(
                text = if (s == null) {
                    "统计中…"
                } else {
                    "今日 ${s.today} 张 · ${formatBytes(s.bytes)}\n" +
                        "累计 ${s.total} 张\n" +
                        "剩余空间 ${formatBytes(s.free)}\n" +
                        "目录 ${store.capturesDir().absolutePath}"
                },
                color = if (status.storageLow) panelErr else panelDim,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            if (status.storageLow) {
                Text(
                    text = "存储空间不足，拍照已暂停；清理旧照片或释放空间后会自动恢复。",
                    color = panelErr,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    if (fullPreview && latest != null) {
        Dialog(
            onDismissRequest = { fullPreview = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { fullPreview = false },
                contentAlignment = Alignment.Center,
            ) {
                PhotoImage(
                    file = latest,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    contentScale = ContentScale.Fit,
                    maxWidth = 2048,
                    maxHeight = 2048,
                )
                Text(
                    text = "点按关闭",
                    color = Color(0xFF8A8A8A),
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                )
            }
        }
    }
}

/** 文件名里的 HH:mm，比文件修改时间更接近拍摄时刻 */
private fun storeTimeLabel(file: File): String {
    val raw = file.name.removePrefix("capture_")
    return try {
        raw.substring(9, 11) + ":" + raw.substring(11, 13)
    } catch (_: Exception) {
        ""
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, color = panelDim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun <T> OptionRow(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (label, value) ->
            val active = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        color = if (active) panelAccent else Color(0xFF1C1C1C),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable { onSelect(value) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (active) Color.White else panelDim,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = panelText, fontSize = 15.sp)
        Switch(
            checked = value,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = panelAccent,
                checkedThumbColor = Color.White,
            ),
        )
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
