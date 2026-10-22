# LapseClock

横屏桌面时钟 + 前置摄像头延时摄影。

手机横放当钟用，屏幕常亮；App 在前台时按固定间隔用前置摄像头无预览拍一张。照片留在 App 私有目录，可按日期范围合成延时视频，也会在每天休眠结束后自动把昨天的照片合成好。

适合拍窗外、工地、植物、天空，或者单纯把旧手机变成一台会记录时间的桌面钟。

- 平台：Android 12+（minSdk 31）
- 形态：单 Activity、横屏、全屏沉浸
- 默认：60 秒一张 / 1600×1200 / JPEG 90 / 保留 7 天

## 功能

### 时钟

- 大字时间，12/24 小时制；日期行附带农历、节日与节气
- 5 套皮肤：墨白 / 暖夜 / 青瓷 / 胶片 / 赛博，另有自动模式
- 小时首位数字与分隔符用皮肤挑好的强调色，其余数字保持主色；自动模式的黎明 / 黄昏 / 夜蓝沿用各自的强调色
- 分隔符不用「:」字形，自绘两个圆点并对准数字的视觉中线，不随系统字体的字形偏移
- 自动模式按时段换肤（黎明 / 白天 / 黄昏 / 夜晚），并随天气、节日调整强调色
- 单击唤出控件（10 秒无操作自动隐藏）、双击切换主题、长按进入设置、左滑进入数据屏、右滑返回
- 底边一条 4dp 今日进度线，长度随时间推进
- 防烧屏：时钟内容每 2 分钟随机平移一次，强度可调（关 / 低 / 中 / 高）；位移按屏幕剩余空白夹紧，不会压到底边进度线、主题名牌或左上信息组；进度线自己在 0/4/8dp 三档间往返；左上信息组每 2 分钟水平漂移 10dp
- 22:00–08:00 自动休眠：停止拍照、屏幕纯黑；休眠中轻点屏幕可显示 15 秒唤醒按钮。夜间手动唤醒后，当晚不再自动休眠
- 左上信息组：和风天气（定位优先，可改手动城市）+ 工行积存金现价（红涨绿跌）

### 拍摄

- CameraX 只绑定 `ImageCapture`，不绑 Preview，所以没有取景画面；用前置摄像头按固定时刻表拍摄
- 默认 60 秒一张，可选 2 / 5 / 10 / 15 / 30 / 60 秒
- 默认 1600×1200，可选 640×480 / 1280×720 / 1600×1200 / 1920×1080；JPEG 质量 90
- 错过拍次或上一张未完成时直接跳过，不补拍，保证节奏均匀
- 相机绑定失败时指数退避重试：2 秒起，最长 30 秒
- 设置页「取景 / 试拍」：立即拍一张、显示缩略图、点图全屏查看；不会改变定时时刻表
- 水印默认开启：右下角白字黑边，自定义文字 + 可选拍摄时间，烧进 JPEG；视频会继承水印
- 剩余空间低于 500MB 时自动停拍并释放相机，角标显示「存储不足」；空间恢复后自动继续
- 相机权限必需；定位权限只在配置了天气且开启自动定位时申请

> 拍照依赖 Activity 处于前台。`onStop()` 会停止拍照循环；本版没有引入前台服务，所以离开 App 后不会继续拍。桌面钟的使用方式就是让它常亮停在前台。

### 照片与存储

- 照片目录：`Android/data/com.lapseclock/files/Pictures/captures/`
- 文件名：`capture_yyyyMMdd_HHmmssSSS.jpg`，按文件名即可得到时间顺序
- 存在 App 私有目录，不进入系统相册，也不需要存储权限
- 启动时在后台建一次内存索引，之后拍照、清理时增量维护；统计、日期列表、区间查询不再反复遍历目录
- 自动清理：新装默认保留最近 7 天（含今天），可改为 30 / 90 天或关闭；拍照循环运行时每天最多清理一次，修改保留天数会立即按新值重跑
- 清理是永久删除，不经过回收站
- Android 11+ 的文件管理器可能看不到 `Android/data/`，属系统限制

### 视频合成

- 手动合成：选择日期范围（起 / 止 / 全部 / 今天）、帧率（24 / 30 / 60）、输出分辨率（1080p / 720p）
- 编码管线：OpenGL ES + MediaCodec(H.264) + MediaMuxer，无额外视频库依赖
- 实时进度、可取消；完成后可播放、分享，或「再合成一次」
- 成片命名：
  - 单日：`timelapse_2026-10-20_30fps.mp4`
  - 多日：`timelapse_2026-10-01_2026-10-20_30fps.mp4`
- 写入系统相册 `Movies/Timelapse/`；使用 `IS_PENDING`，写入完成前不会在相册里出现半成品
- MediaStore 写入失败时回退为 FileProvider 临时文件分享
- 启动时会清理 10 分钟前遗留在 `cache/export` 的导出残片

**每日自动合成**

- 运行期间每分钟检查一次，休眠中跳过
- 条件：昨天有照片、昨天尚未成功自动合成过、当前不在休眠/导出中、剩余空间至少 800MB
- 参数固定为 30fps / 1080p，与手动导出默认值一致
- 成功后记录日期，同一天不重复出片；失败或空间不足时 30 分钟后再试
- 手动导出不会影响每日自动合成的记录
- 设置页可关闭，并可查看上次自动合成的日期
- 自动合成是 ViewModel 内的定时检查，不依赖 WorkManager。App 进程被杀后不会准点执行，下次打开时会补做**昨日**；更早的日期需要到合成页手动选择

### 数据屏

左滑进入，右滑返回：

- 今日张数与当天首末拍摄时间
- 累计帧数、拍摄天数、占用空间、按 30fps 折算的成片时长
- 今天与今年的进度
- 近 14 天柱状图，没拍摄的日期也占位，断档一眼可见
- 最近一天首帧与末帧并排对比，缩略图按 EXIF 纠正方向

## 技术栈

| 项 | 选择 |
|---|---|
| 语言 / UI | Kotlin 1.9.22 + Jetpack Compose（单 Activity，状态驱动切换） |
| 相机 | CameraX 1.3.1（仅 ImageCapture，无预览） |
| 视频 | OpenGL ES + MediaCodec(H.264) + MediaMuxer |
| 设置存储 | DataStore Preferences |
| 网络 | HttpURLConnection + org.json（和风天气 now/geo、工行积存金页面解析） |
| 农历 | cn.6tail:lunar 1.7.7 |
| 图片方向 | androidx.exifinterface 1.3.7 |
| 工具链 | AGP 8.2.2 / Gradle 8.5 / JDK 17 / compileSdk 34 / targetSdk 34 / minSdk 31 / Compose BOM 2024.02.00 |
| 单元测试 | JUnit 4.13.2 |

## 配置

天气面板是可选的。在项目根目录的 `local.properties` 中填写（该文件已被 gitignore）：

```properties
# 已设置 ANDROID_HOME 时可省略
sdk.dir=<你的 Android SDK 路径>

qweatherKey=<和风天气 API Key>
qweatherHost=<和风天气专属 API Host，不带 https://>
qweatherCity=<回退城市名，如 北京>
```

说明：

- `qweatherKey` 或 `qweatherHost` 为空时，天气面板不显示，也不会申请定位权限；其余功能不受影响
- `qweatherCity` 是定位失败或关闭自动定位时的回退城市
- 积存金直接抓工行公开行情页，无需配置；网络不可用时面板静默隐藏
- 权限：`CAMERA`（必需）、`INTERNET`、`ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION`（可选，仅天气定位）

## 版本号

设置页右上角显示的 `vX.Y.Z` 与 `app/build.gradle.kts` 里的 `versionName` 一致。

**每次提交后由 AI 判断该升哪一位，只加一，不跳号**；判断依据是"这次改动对用户可见的行为变了多少"，不是代码行数。

| 位 | 什么时候升 | 例子 |
|---|---|---|
| 修订位（第三位） | 小改动：修 bug、调文案、微调样式、补日志 | `1.0.0` → `1.0.1` |
| 次版本（第二位） | 功能变化：新增或移除功能、改界面结构、改数据模型 | `1.0.1` → `1.1.0`（修订位归零） |
| 主版本（第一位） | 形态转变或大重构，基本不动 | `1.1.0` → `2.0.0` |

配套规则：

- 升某一位时，比它低的位归零（`1.0.7` 升次版本 → `1.1.0`，不是 `1.1.7`）
- `versionCode`（整数，用于覆盖安装判断）每次同步 `+1`，与 `versionName` 在同一处修改
- 改完两处才算改完版本：`app/build.gradle.kts` 的 `versionCode` 和 `versionName`
- `1.0.0` 是引入本规则时的基线版本

## 构建与安装

需要 JDK 17 与 Android SDK。

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接的真机：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.lapseclock android.permission.CAMERA
```

首次启动也会主动申请相机权限。调试时查看日志：

```bash
adb logcat -s LapseClock
```

运行单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

Lint：

```bash
./gradlew :app:lintDebug
```

## 使用

1. 打开 App，横屏时钟亮起，授权相机后自动开始拍照（右上角状态点 + 今日张数）
2. **手势**：单击显示控件 · 双击换主题 · 长按设置 · 左滑数据屏 · 右滑返回
3. 右上角控件：⚙ 设置 · 🎬 合成 · 🌙 休眠
4. 设置 →「取景 / 试拍」→「立即拍一张」，确认画面和角度；缩略图可点开全屏
5. 设置 →「自动清理」选择保留天数；设置 →「自动合成」控制每日自动出片
6. 合成页选择日期、帧率、分辨率 → 开始合成；完成后播放/分享，或再合成一次
7. 休眠中轻点屏幕，15 秒内点右上角 ☀ 可唤醒

### 设置项默认值

| 设置 | 默认 | 可选 |
|---|---|---|
| 拍照间隔 | 60 秒 | 2 / 5 / 10 / 15 / 30 / 60 秒 |
| 目标分辨率 | 1600×1200 | 640×480 / 1280×720 / 1600×1200 / 1920×1080 |
| 24 小时制 | 开 | 开 / 关 |
| 柔和亮度 | 开 | 开 / 关 |
| 防烧屏偏移 | 中 | 关 / 低 / 中 / 高 |
| 水印 | 开 | 开 / 关；文字可改；时间可关 |
| 自动定位城市 | 开 | 开 / 关；关闭后可手填城市 |
| 自动清理 | 7 天 | 关闭 / 7 / 30 / 90 天 |
| 自动合成昨日 | 开 | 开 / 关 |
| 暂停拍照 | 关 | 开 / 关 |
| 时钟主题 | 自动 | 自动 / 墨白 / 暖夜 / 青瓷 / 胶片 / 赛博 |

## 项目结构

```text
app/src/main/java/com/lapseclock/
├── MainActivity.kt          横屏、常亮、沉浸式全屏、相机/定位权限
├── MainViewModel.kt         全局状态装配、自动合成调度
├── NightSleep.kt            22:00–08:00 夜间休眠判定
├── capture/
│   ├── CaptureController.kt 无预览拍照循环、试拍、清理、存储保护
│   ├── PhotoStore.kt        照片内存索引、命名、日期检索、统计、清理
│   └── Watermark.kt         JPEG 水印烧录
├── video/
│   ├── TimelapseEncoder.kt  GL + MediaCodec + MediaMuxer 编码管线
│   ├── ExportState.kt       导出状态、进度、取消、MediaStore 发布
│   ├── AutoExportPolicy.kt  每日自动合成的纯判定逻辑
│   └── TimelapseNaming.kt   成片文件名
├── settings/
│   └── SettingsRepo.kt      DataStore 设置封装
├── weather/
│   ├── WeatherRepo.kt       和风天气 now / geo 接口
│   └── LocationProvider.kt  网络定位优先、GPS 兜底
├── gold/
│   └── GoldRepo.kt          工行积存金现价抓取
├── calendar/
│   └── LunarInfo.kt         农历、节日与节气
└── ui/
    ├── LapseApp.kt          时钟 / 设置 / 合成路由，数据屏滑入
    ├── ClockScreen.kt       时钟、手势、角标、休眠、防烧屏
    ├── InfoScreen.kt        数据屏：统计、14 天柱状图、首末帧
    ├── SettingsSheet.kt     设置页（含试拍与存储统计）
    ├── ExportScreen.kt      合成页
    ├── WeatherPanel.kt      天气 + 积存金胶囊
    ├── PhotoImage.kt        按 EXIF 纠向的缩略图/全屏解码
    └── theme/
        └── ClockTheme.kt    主题数据、5 套皮肤、时段/天气/节日解析

app/src/test/java/com/lapseclock/
├── NightSleepTest.kt
├── capture/PhotoStoreTest.kt
└── video/
    ├── AutoExportPolicyTest.kt
    └── TimelapseNamingTest.kt
```

## 已知限制

- **无后台服务**：离开 App（Activity `onStop`）后拍照停止；没有前台服务，也不做开机自启
- **自动合成依赖进程存活**：进程被杀后不会准点执行，下次打开只补做昨日；没有使用 WorkManager，也不会补更早的日期
- **无预览**：这是省电和隐私上的取舍，用设置页试拍确认取景
- **夜间时段固定**：22:00–08:00，不能改时间；可手动休眠/唤醒
- **前置摄像头必需**：Manifest 声明了前置相机硬件要求
- **照片不公开**：只存在 App 私有目录，卸载 App 会一起删除；需要保留请先合成视频或自行导出
- **清理不可恢复**：超过保留天数的照片会被直接删除
- **编码能力取决于设备**：使用系统 H.264 编码器；极老或编码器异常的设备可能合成失败，界面会显示错误信息
- **自动合成不删原片**：原片仍按保留策略清理
