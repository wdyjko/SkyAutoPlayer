# Sky Auto Player（光遇自动弹琴）

Kotlin 原生 Android 应用：读取光遇（Sky）曲谱，通过**无障碍手势（`GestureDescription`）**
在游戏内自动弹琴。**只模拟输入** —— 不读内存、不注入、不修改游戏。

- `applicationId`：`com.skyautoplayer`
- `versionCode 10` / `versionName "0.9.0"`
- `minSdk 26` / `targetSdk 35` / `compileSdk 35`
- Kotlin 2.1.10 / AGP 8.9.2 / JDK 17

---

## 1. 模块结构

源码位于 `app/src/main/java/com/skyautoplayer/`，按职责分层：

| 包 | 职责 |
|---|---|
| `domain/` | 纯 Kotlin 模型：`timeline`（绝对时间线）、`playback`（播放状态） |
| `importer/` | 曲谱导入：`SkyJsonImporter`（SkyStudio JSON）、`SkyTextImporter`（TXT 简谱）、`ImporterDispatcher` 自动分派 |
| `storage/` | `SongStore` 曲库持久化、`BundledSheetSeeder` 内置曲库播种、`BundledTombstones` 删除墓碑、`CalibrationRepository` 校准存档 |
| `application/` | 用例层：`SongQueue` 播放队列、`PlaybackRuntime` 运行态 |
| `playback/` | `PlaybackEngine` 绝对时间线播放引擎、`PlaybackForegroundService` 前台服务 |
| `gesture/` | `GestureSink` 输入抽象 |
| `calibration/` | 15 键网格校准模型（旋转、内容区、安全区、系统栏 Insets） |
| `accessibility/` | `PlayerAccessibilityService` 无障碍手势派发 |
| `overlay/` | 悬浮控制条 / 曲目面板 / 悬浮球（`PlaybackOverlayService`）、校准浮层（`CalibrationOverlayService`） |
| `ui/` | `MainActivity`：曲库、演出、校准、设置四个页面 |

**设计要点**

- 播放采用**绝对微秒时间线**：以 `SystemClock.elapsedRealtimeNanos()` 计算绝对截止时间，
  单协程 Actor 串行派发，避免逐帧累加误差。
- 曲目定位一律用 `timelineId` / 队列索引，**不用标题匹配**（内置曲库存在 21 组重名）。
- 悬浮层为 `FLAG_NOT_FOCUSABLE`，保证不抢游戏焦点；仅在曲目面板展开期间临时取得焦点以支持搜歌，
  面板关闭立即还原。
- 内置曲库以 `BUNDLED_SHEETS_VERSION` + 删除墓碑实现幂等播种：升级只补新增，用户删过的不复活。

---

## 2. 构建

需要 **JDK 17**（AGP 8.9.2 与 Kotlin 2.1.10 的下限；JDK 8 会被工具链直接拒绝）。

```powershell
# 单元测试
.\gradlew.bat --offline testDebugUnitTest

# 构建 debug APK
.\gradlew.bat --offline assembleDebug
# 产物：app\build\outputs\apk\debug\app-debug.apk
```

> **`--offline` 是必需的**：本机无法访问 `dl.google.com`。依赖白名单固定在
> `app/build.gradle.kts`，仅 `androidx.core` / `androidx.activity` /
> `androidx.lifecycle` / `kotlinx-coroutines-android` 四项，加新依赖会导致构建失败。

`local.properties`（含本机 SDK 路径）**不入库**，首次构建时由 Android Studio 生成，
或自行写入 `sdk.dir=<Android SDK 路径>`。

---

## 3. 曲库（**不入库**）

内置曲库 `app/src/main/assets/bundled_sheets/`（707 首，23.5 MB）**已在 `.gitignore` 中排除**，
仅作本地备份。仓库 clone 后**没有曲库**，此时应用仍可构建运行，只是内置曲库为空
（`BundledSheetSeeder` 会记日志「assets/bundled_sheets 为空，无可导入内容」）。

要产出一份完整的曲库：把曲谱 `*.txt` 放到本地目录，再用打包脚本生成资产与清单：

```powershell
python tools\bundle_from_device_library.py --index <曲库索引> --songs <曲谱目录>
```

`tools/bundled_sheets_manifest.json` 是资产命名表（`assetName / title / durationUs / eventCount`），
**打包脚本靠它沿用既有 asset 名**，从而保住 `alreadyPresent` 幂等与 `deleted_bundled` 墓碑 ——
不要删除或改名。

---

## 4. 权限与使用

应用不会绕过 Android 权限或游戏服务条款。安装后需要用户手动完成：

1. 授予**无障碍服务**权限（实际手势派发依赖它）
2. 授予**悬浮窗**权限（控制条 / 校准网格）
3. 完成 **15 键网格校准**
4. 导入曲谱或恢复本地曲库后播放

---

## 5. 声明

本项目仅供个人学习与技术研究。请遵守游戏服务条款，不要用于破坏他人游戏体验的场景。
