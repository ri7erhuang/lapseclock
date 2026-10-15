# LapseClock

横屏桌面时钟 + 前置摄像头延时摄影，App 内一键合成延时视频。

时钟界面全屏常亮，后台按固定间隔（默认 10 秒）用前置摄像头拍一张照片（无预览画面），
积累的照片可在 App 内按日期范围合成 mp4 延时视频并写入系统相册。

## 功能

- **横屏数字时钟**：黑底白字大字显示，支持 12/24 小时制、秒数开关、日期星期
- **屏幕常亮 + 防烧屏**：前台保持不熄屏，按分钟整体平移画面若干像素（OLED 友好），可调强度
- **无预览延时拍照**：CameraX 仅绑定 `ImageCapture`（不绑 Preview），前置摄像头按固定时刻表拍摄；上一张未完成则跳过该拍次，只统计不补拍，节奏均匀
- **夜间免打扰**：22:00 – 08:00 自动暂停拍照
- **照片水印**：右下角白字黑边烧进 JPEG（自定义文字 + 拍摄时间），成片视频自动继承
- **照片管理**：存 App 私有目录（不进相册、无存储权限），按文件名时间序组织；可设置自动清理 N 天前旧照
- **App 内合成视频**：选日期范围 → 帧率（24/30/60）与输出分辨率（1080p/720p）→ GL + MediaCodec 硬件编码 + MediaMuxer 合成 mp4，实时进度、可取消，成片写入 `Movies/Timelapse/` 相册可见
- **状态角标**：拍照状态点、今日/累计张数、跳过数、最近错误；控件 10 秒无操作自动隐藏，点按屏幕唤出

## 技术栈

| 项 | 选择 |
|---|---|
| 语言 / UI | Kotlin 1.9.22 + Jetpack Compose（单 Activity，状态驱动切换） |
| 相机 | CameraX 1.3.1（仅 ImageCapture，无预览） |
| 视频合成 | OpenGL ES + MediaCodec(H.264) + MediaMuxer，零额外依赖 |
| 设置存储 | DataStore Preferences |
| 工具链 | AGP 8.2.2 / compileSdk 34 / minSdk 31 / Compose BOM 2024.02.00 |

## 构建

需要 JDK 17 与 Android SDK。

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接的设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.lapseclock android.permission.CAMERA
```

首次启动会申请相机运行时权限。

## 使用

1. 打开 App，横屏时钟界面，屏幕保持常亮，拍照循环自动开始（右上角状态点 + 今日张数）
2. 点按屏幕唤出控件：⚙ 设置（间隔 / 分辨率 / 时制 / 水印 / 防烧屏 / 自动清理）、⏸ 暂停
3. 🎬 合成：选日期范围与帧率、分辨率，开始合成，进度完成后分享或查看
4. 照片位于 `Android/data/com.lapseclock/files/Pictures/captures/`，成片位于系统相册 `Timelapse` 目录

存储参考：720p 约 100–200KB/张 → 约 0.9–1.7 GB/天（可开自动清理）。

## 项目结构

```
app/src/main/java/com/lapseclock/
├── MainActivity.kt          横屏 + 常亮 + 权限申请
├── MainViewModel.kt         全局状态装配
├── NightSleep.kt            夜间免打扰时段
├── ui/
│   ├── LapseApp.kt          界面路由（时钟/设置/合成）
│   ├── ClockScreen.kt       大字时钟、状态角标、控件自动隐藏
│   ├── SettingsSheet.kt     间隔/分辨率/时制/水印/防烧屏/清理
│   └── ExportScreen.kt      日期范围、帧率分辨率、进度、结果
├── capture/
│   ├── CaptureController.kt 无预览拍照循环（固定时刻表、跳过不堆积）
│   ├── PhotoStore.kt        文件命名、按日期检索、统计、清理
│   └── Watermark.kt         JPEG 水印烧录
├── settings/
│   └── SettingsRepo.kt      DataStore 封装
└── video/
    ├── TimelapseEncoder.kt  GL + MediaCodec + MediaMuxer 编码管线
    └── ExportState.kt       进度/取消/结果 StateFlow
```

设计方案与实施细节见 [PLAN.md](PLAN.md)。
