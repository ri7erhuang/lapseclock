# LapseClock 实施方案

横屏桌面时钟 + 前置摄像头延时摄影（无预览，每 10s 一张），App 内一键合成延时视频。

> ## 现状更新（2026-10-20）
>
> 主题系统落地，时钟不再是「一套写死的配色」：
>
> - 新增 `ui/theme/ClockTheme.kt`：`ClockTheme` 数据类 + `resolveClockTheme()` 三层解析器。
>   基础皮肤（手选 / 按时段自动）→ 天气覆盖 accent → 节日覆盖 accent（自动模式下再改底色），
>   三种信号互不打架。5 套固定皮肤：墨白 / 暖夜 / 青瓷 / 胶片 / 赛博。
> - **秒数功能整体删除**（不是隐藏）：桌面钟带秒只会加重焦虑，代码路径一并移除。
> - 时钟手势：单击唤控件、双击切皮肤、长按进设置、左滑进第二屏（数据）、右滑返回。
> - 第一屏底部新增 **2dp 今日进度细线**（已过分钟 / 1440）。长度每分钟都在变，本身不可能烧屏；
>   年进度等其余数据全部移到第二屏，第一屏保持极简。
> - 新增第二屏 `ui/InfoScreen.kt`：今日/累计统计、近 14 天柱状图（补零让断档可见）、
>   最近一天首帧与末帧并排对比、日/年进度条。
>
> ## 现状更新（2026-10-19）
>
> 本文是最初的设计方案，实施过程中有以下偏离，以此为准：
>
> - 默认间隔改为 **60s**（设置可调），默认分辨率 1600×1200
> - **画面带水印**（右下角自定义文字 + 时间，烧进 JPEG，视频继承），与原「画面无水印」相反
> - JPEG 质量由固定 100 调整为 **90**，水印路径解码改为 `inMutable` 免一次全尺寸拷贝
> - 左上不再是占位面板：已接 **和风天气**（定位优先 + 手动城市回退）与 **工行积存金** 现价
> - 日期行附农历/节日节气（`cn.6tail:lunar`）
> - 相机改为**持续绑定**，仅在休眠/暂停/停止时解绑；夜间 22:00–08:00 自动休眠
> - 照片计数改为单次目录遍历（`PhotoStore.counts()`），并移到 IO 线程执行


## 已确认需求

- Android 原生（Kotlin + Jetpack Compose），横屏、前台常亮、数字时钟
- 前置摄像头、无预览画面、每 10 秒拍一张，仅前台运行（熄屏/切走即暂停）
- 照片存 App 私有目录（不进相册），只有成片视频写入系统相册
- App 内合成延时 mp4，画面无水印
- 不考虑功耗；屏幕前台常亮
- minSdk 31；调试机为三星 S22（Android 16，ADB 已连接），正式运行机也是 Android 16

## 技术选型

| 项 | 选择 | 说明 |
|---|---|---|
| 工具链 | AGP 8.2.2 / Kotlin 1.9.22 / compileSdk 34 / Compose BOM 2024.02.00 | 沿用 pocket-oracle 已验证的本机缓存工具链 |
| 相机 | CameraX（camera-camera2 + camera-lifecycle），仅绑定 ImageCapture | 不绑 Preview，天然无预览 |
| 视频合成 | MediaCodec(H.264, Surface 输入) + OpenGL ES + MediaMuxer | 硬件编码、零额外依赖；ffmpeg-kit 已停止维护，仅作 Plan B |
| 设置存储 | DataStore Preferences | 间隔/分辨率/画质/时制等 |
| UI | 单 Activity + Compose，状态驱动切换（时钟/设置/合成） | 不引 Navigation 也行，保持简单 |

## 项目结构

新建于 `~/code/lapseclock`：

```
lapseclock/
├── PLAN.md
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/wrapper/            # 复制自 pocket-oracle
└── app/
    ├── build.gradle.kts       # compose + camerax + datastore + exifinterface
    └── src/main/
        ├── AndroidManifest.xml    # landscape、CAMERA 权限、单实例
        └── java/com/lapseclock/
            ├── MainActivity.kt        横屏 + FLAG_KEEP_SCREEN_ON + 权限申请
            ├── ui/
            │   ├── ClockScreen.kt     大字时钟、状态角标、控件自动隐藏
            │   ├── SettingsSheet.kt   间隔/分辨率/画质/24h/秒数/存储统计
            │   └── ExportScreen.kt    日期范围、帧率分辨率、进度、结果
            ├── capture/
            │   ├── CaptureLoop.kt     CameraX 无头拍照循环
            │   └── PhotoStore.kt      文件命名、按日期检索、统计、清理
            ├── settings/
            │   └── SettingsRepo.kt    DataStore 封装
            └── video/
                ├── TimelapseEncoder.kt  GL + MediaCodec + MediaMuxer 管线
                └── ExportState.kt       进度/取消/结果 StateFlow
```

## 关键设计

### 1. 时钟界面（横屏常亮）

- `MainActivity`：`screenOrientation="landscape"`，`window.addFlags(FLAG_KEEP_SCREEN_ON)`
- 黑底白字，`HH:mm` 超大字号，秒 + 日期星期小字，可选 12/24h、是否显示秒
- 右上角隐藏式按钮：⚙ 设置、🎬 合成、拍照状态点 + 今日张数；3 秒无操作自动隐藏，点按屏幕唤出
- 防烧屏：每分钟整体平移几个像素（OLED 常亮必需，成本极低）

### 2. 拍照循环（无预览）

- 首次启动申请 `CAMERA` 运行时权限；`ProcessCameraProvider.bindToLifecycle(owner, FRONT_CAMERA, imageCapture)`，不绑 Preview
- 固定时刻表触发：`t0 + n×interval`；若上一张未完成则跳过该拍，保证间隔均匀不堆积
- `takePicture` 回调驱动下一次；绑定 Activity 生命周期，`onStop` 暂停循环
- 输出：`ImageCapture.OutputFileOptions` 直写 JPEG 到
  `getExternalFilesDir(DIRECTORY_PICTURES)/captures/capture_yyyyMMdd_HHmmssSSS.jpg`
  （文件名即时间序，无需数据库；App 私有目录不进相册、无存储权限）
- 状态上报：是否在拍、今日/累计张数、占用空间、最近错误（相机被占用/存储满）
- 设置项：间隔（默认 10s）、目标分辨率（默认 1280×720）、JPEG 画质（默认 70）、暂停开关

### 3. 延时合成管线

1. 选日期范围（或全部）→ 统计张数、按所选帧率估算成片时长
2. 帧率默认 30（可选 24/60），输出默认 1920×1080
3. 单后台线程顺序处理：
   - `BitmapFactory` 解码（inSampleSize 降到目标分辨率）+ `ExifInterface` 纠正方向
   - GL 纹理贴全屏四边形，shader 做居中裁切（4:3 → 16:9 cover）
   - 渲染到 `MediaCodec.createInputSurface()`，PTS = 帧号 × 1_000_000/fps
   - 输出回调写入 `MediaMuxer`（MP4），首帧 `INFO_OUTPUT_FORMAT_CHANGED` 后启动 muxer
4. 进度按帧上报（StateFlow），支持取消；结束后 `MediaMetadataRetriever` 读取时长展示
5. 成片写 `MediaStore Movies/Timelapse/`，相册可见，提供分享入口
6. 性能预估：1080p 硬编约 30–60fps，一天 8640 张约 3–5 分钟

### 4. 存储量级（设置可调）

- 720p@画质70 ≈ 100–200KB/张 → 360 张/时、约 0.9–1.7 GB/天
- 可选：自动清理 N 天前旧照片（默认关闭）

## 实施步骤

1. **脚手架**：复制 pocket-oracle 工具链，MainActivity 横屏 + 常亮 + 时钟 UI
2. **相机**：CAMERA 权限 + 无头拍照循环 + PhotoStore + 设置面板 + 状态显示
3. **合成**：选择界面 + GL/MediaCodec/Muxer 编码器 + 进度/取消 + 视频入库
4. **打磨与真机验证**

## 验证方案（S22）

- `adb install` + `pm grant ... android.permission.CAMERA`
- 时钟横屏显示、屏幕持续不熄（`dumpsys power` 检查）
- 照片文件时间戳间隔 ≈ 10s，App 内计数与实际一致
- 临时调 2s 间隔快速积累样本 → 合成 100+ 帧 → 校验成片时长 = 张数/帧率，相册可播放
- 30–60 分钟浸泡：无漏拍堆积、内存/发热正常

## 风险与对策

| 风险 | 对策 |
|---|---|
| 个别机型无 Preview 绑定异常 | 退回 Camera2 直连（仅 ImageCapture 场景） |
| 合成期间进程被杀 | 屏幕常亮 + 前台合成基本可保稳定；必要时后续加前台 Service（非本期） |
| EXIF 方向 / 裁切变形 | 解码统一纠向 + shader center-crop |
| 存储写满导致拍照失败 | IOException 捕获 + 状态栏警示 |
