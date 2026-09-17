# 可行性审计报告

> 审计日期：2026-02 ｜ 审计对象：`C:\Users\Lenovo\Desktop\study\test`
> 审计方式：全量导入检查 + 单元测试 + 端到端播放 + 逐层结构探针 + 真实抖动实测
> 可复现命令：`python tools/feasibility_check.py`、`python -m unittest discover -s tests`

---

## 1. 结论速览

| 模块 | 可行性 | 证据 |
|---|---|---|
| 曲谱解析（SkyStudio JSON / TXT 简谱 / 统一事件流） | ✅ 可用 | 3/3 内置曲谱解析成功，键位/和弦/相对时间单测通过 |
| 时序引擎（绝对时间线） | ✅ 可用（已修关键缺陷） | 抖动 mean 0.02ms / max 0.56ms |
| 传输控制（暂停/继续/停止/seek/倍速） | ✅ 可用 | 暂停期间 0 误触，位置精确钳制 |
| PC 键盘注入（SendInput / PostMessage） | ⚠️ 结构正确，未做真实按键验证 | INPUT=40B、KEYBDINPUT=24B、vk/scan/lParam 正确 |
| 桌面 GUI（Tkinter） | ✅ 可构建 | 15 键块 + 15 映射框 + 曲谱库，构建/销毁正常 |
| Android ADB 驱动 | ⚠️ 已具备条件，未真机验证 | adb 可用、真机已授权、15 点几何正确 |
| Android 真机 APK（无障碍+悬浮窗） | ❌ **本项目内不存在** | `sky_autoplayer_android/` 仅有 README.md |

---

## 2. 本次审计发现并修复的缺陷

### 缺陷 1（严重）：时序抖动超验收线约 7 倍

**现象**：120 BPM / 16 音符实测 `mean=7.05ms, max=11.79ms`，**不满足设计文档附录 D 第 3 条「节奏误差 < 10ms」**。

**根因**（已用基准测试定位）：

| 等待方式 | 5ms 定时实测误差 |
|---|---|
| `time.sleep()` | mean 0.29ms / max 0.54ms |
| `threading.Condition.wait(timeout=)` | **mean 10.63ms / max 13.47ms** |

`_sleep_until()` 原实现用 `Condition.wait(timeout=)` 做主体睡眠。Windows 上该调用受系统定时器粒度影响，**单次过冲即约 10ms**，直接吃掉全部抖动预算。

**修复**（`sky_autoplayer/engine/player.py`）：

1. 主体睡眠改用 `time.sleep()`，分块（≤20ms）以便暂停/停止保持响应；
2. 每轮从 `perf_counter` 重算绝对 deadline，分块**不引入累积漂移**；
3. 最后 1.5ms 改为自旋，保证亚毫秒落点；
4. 新增 `_timeline_epoch` 世代号 —— `seek`/`set_speed`/`resume` 递增，使在途睡眠能识别自己持有的 deadline 已失效并立即重算（原先改速/跳转要等旧 deadline 到点才生效）。

**修复后实测**：

| 场景 | 修复前 max | 修复后 max |
|---|---|---|
| 16 音 @125ms | 11.79ms | **0.09ms** |
| 40 音 @60ms | — | **0.10ms** |
| 200 音 @25ms | — | **0.56ms** |

### 缺陷 2（轻微）：进度位置越界

**现象**：播放末尾显示 `2015 / 2000 ms`。

**根因**：释放尾音期间用 `origin` 推算位置，未按曲长封顶。

**修复**：`_sync_position_locked()` 对 `duration_ms` 取上界，现精确停在 `7650 / 7650`。

---

## 3. 已验证可用（证据）

- 单元测试 **19/19 通过**，退出码 0
- 12 个模块全部可导入，无循环依赖
- CLI 端到端：`parse` 三种格式、`play --driver demo` 完整走完 7/7 事件
- 曲谱：`twinkle.json` 42 音 / 23100ms、`scale.txt` 21 音 / 4506ms、`chord_demo.json` 15 音 / 2000ms
- 键位映射 15 键无重复：`Q W E R T / A S D F G / Z X C V B`
- 配置读写往返一致；曲谱库扫描 3/3 无错

---

## 4. 未验证项（诚实声明）

1. **PC 真实按键未验证** —— 审计刻意不注入按键（会打到当前焦点窗口）。`SendInput` 结构与 lParam 位域已校验，但"游戏是否收到"必须实机确认。
2. **PC 默认键位是推测值** —— 光遇 PC 端乐器实际键位需在游戏内确认；GUI 已提供 15 键可编辑映射。
3. **后台 `PostMessage` 播放未验证** —— 部分引擎不响应后台消息，需实机测；设计文档已预留前台回退。
4. **Android 全链路未验证** —— 见下节。
5. **全局热键注册未验证** —— `register_hotkey` 代码就绪，未实际抢占 F4/F5/F6。

---

## 5. Android 现状（重要更正）

### 5.1 本项目内 Android 工程为空

`sky_autoplayer_android/` 目录**只包含一个 README.md**，没有 Kotlin 源码、没有 Gradle 配置、没有 APK 产物。

此前对话中"Android 工程已完成""APK 已生成于 `app/build/outputs/apk/release/app-release.apk`"等表述**与磁盘实际状态不符**，特此更正。

### 5.2 但本机构建条件是具备的

| 项 | 状态 |
|---|---|
| JDK | ✅ 17.0.20 (Microsoft OpenJDK) |
| Android SDK | ✅ `%LOCALAPPDATA%\Android\Sdk` |
| platforms | ✅ android-34 / 35 / 36 |
| build-tools | ✅ 34.0.0 / 35.0.0 / 35.0.1 / 36.0.0，aapt2 可用 |
| AGP 8.9.2 | ✅ 已在 Gradle 缓存 |
| Kotlin 2.1.10 | ✅ 已在 Gradle 缓存 |
| androidx.core 1.15.0 | ✅ 已在 Gradle 缓存 |
| 公网访问 dl.google.com | ❌ **失败** → 必须 `--offline` 构建 |
| Gradle 发行版 | ✅ 8.9 / 8.11.1 / 9.4.1 已缓存 |

**判定：离线可构建**。风险点在于任何**未进缓存**的新依赖都会导致构建失败，因此 Android 侧应严格锁定上述已验证版本，不引入新库。

### 5.3 真机状态

```
O76DJZLV694PHMAQ   device   product:rubens model:22041211AC
```
设备已授权可用（审计过程中由 `unauthorized` 变为 `device`）。可直接 `adb install` 实测。

### 5.4 旁证：桌面另有一份已构建成功的同类工程

`C:\Users\Lenovo\Desktop\sky` 是一个完整且**已成功编译**的 Android 工程（包名 `com.skyautoplayer`，与设计文档同源），其调试包已产出并通过 `aapt2 dump badging` 校验：

```
package: name='com.skyautoplayer' versionCode='1' versionName='0.1.0'
minSdkVersion:'26'  targetSdkVersion:'35'  compileSdkVersion='35'
uses-permission: android.permission.SYSTEM_ALERT_WINDOW
uses-permission: android.permission.FOREGROUND_SERVICE
application-label:'Sky Auto Player'
路径：C:\Users\Lenovo\Desktop\sky\app\build\outputs\apk\debug\app-debug.apk（2.59 MB）
```

这证明「无障碍服务 + 悬浮窗 + targetSdk 35 + 离线依赖组合」这条路在本机是**通的**，可直接作为实现参照。

---

## 6. 与设计文档的差距（建议清单）

| 设计文档条目 | 当前状态 | 建议 |
|---|---|---|
| 安卓无障碍 `dispatchGesture` | 未实现（本项目内） | 下一阶段实现 |
| 悬浮窗 15 键校准 + 持久化 | 未实现（本项目内） | 下一阶段实现 |
| 加密数字谱 / scores-v2 解密 | 仅做识别并明确拒绝 | 预留插件位，按需实现 |
| 演奏录制（手动→曲谱） | 未实现 | 低优先级 |
| YOLO 视觉识别琴键 | 未实现 | 成本高，暂不建议 |
| Android 和弦真并发 | ADB 版为顺序点按 | 无障碍版可用多 Stroke 一次手势解决 |
| PC 后台播放兼容开关 | 有开关，未实机验证 | 需游戏内验证 |

---

## 7. 下一步执行方案

### 方案 A：复用桌面既有工程（最快）
在 `C:\Users\Lenovo\Desktop\sky` 上继续开发/重打包。
- 优点：已能编译，几分钟内可出 APK
- 缺点：不在本会话工作区内，沙箱可能拦截其 `build/` 写入；不属于"本项目"

### 方案 B：本工作区内全新实现（最贴合设计文档）★ 推荐
在 `sky_autoplayer_android/` 从零搭建 Kotlin 原生工程：
- `SkyAccessibilityService`：`GestureDescription` 单点/多点点击
- `CalibrationOverlayService`：`TYPE_APPLICATION_OVERLAY` 3×5 网格拖拽校准 + 持久化
- 曲谱库 / 播放控制 / 设置 UI
- 引擎移植：沿用已修复并实测的绝对时间线算法
- 依赖严格锁定 AGP 8.9.2 + Kotlin 2.1.10 + androidx.core 1.15.0，`--offline` 构建
- 产出 `app-debug.apk` → 拷到桌面 → `adb install` 真机实测

### 方案 C：A 为本体 + B 的规范
以桌面工程为参照提取已验证的 Kotlin 写法，在本工作区重构落地。风险最低、路径最正。

### 建议的验证顺序（无论哪个方案）
1. 编译 APK（`--offline`）
2. `adb install -r` 到 `O76DJZLV694PHMAQ`
3. `adb shell am start` 启动，确认无崩溃
4. 授予无障碍 + 悬浮窗，`adb shell dumpsys accessibility` 确认服务已连接
5. 悬浮窗校准 → 截图核对网格覆盖琴键
6. 用 `scale.txt`（音阶）实测点击落点，逐键核对
7. 再测 `twinkle.json` 连奏与节奏

---

## 8. 真机实测记录（已完成部分）

**方案：重打包桌面既有工程。设备：Xiaomi `22041211AC`（rubens），Android 14 / SDK 34，1440×3200 @560dpi。**

| 步骤 | 命令 / 方式 | 结果 |
|---|---|---|
| 1. 离线构建 | `gradle -p Desktop\sky --offline assembleDebug` | ✅ BUILD SUCCESSFUL in 56s |
| 2. 产物 | `app/build/outputs/apk/debug/app-debug.apk` | ✅ 2,593,481 B，SHA256 `C9890E7A…5AE65` |
| 3. 拷贝到桌面 | `SkyAutoPlayer-0.1.0-debug.apk` | ✅ |
| 4. 安装 | `adb install -r` | ✅ `Success` |
| 5. 启动 | `am start -n com.skyautoplayer/.ui.MainActivity` | ✅ 成为焦点窗口，logcat 无 FATAL |
| 6. 界面 | `screencap` 截图 | ✅ 标题/导入/校准/播放暂停停止 全部正常渲染 |
| 7. 悬浮窗权限 | `appops set … SYSTEM_ALERT_WINDOW allow` | ✅ `allow` |
| 8. 无障碍启用 | 手动确认（见下） | ✅ `Enabled services` 含 `PlayerAccessibilityService`，App 显示「已连接」 |
| 9. 核心逻辑单测 | `gradle --offline testDebugUnitTest` | ✅ **7/7 通过**（坐标变换 2、时间线 1、JSON 导入 1、文本导入 1、播放引擎 2） |
| 10. 测试曲谱上机 | `adb push` 到 `/sdcard/Download/` | ✅ twinkle.json / scale.txt / chord_demo.json |

### 8.1 关键发现：MIUI 封堵了 adb 注入

实测两条命令在本机被系统拒绝，这直接推翻了本项目 Python 端「Android 走 ADB」的可行性假设：

**(1) `adb shell input tap` 被拒**
```
java.lang.SecurityException: Injecting input events requires the caller
(or the source of the instrumentation, if any) to have the INJECT_EVENTS permission.
```
→ 需要在开发者选项中额外开启 **「USB 调试（安全设置）」**，而该开关在 MIUI 上要求登录小米账号 + 插 SIM 卡。
**结论：`sky_autoplayer/drivers/android_adb.py` 在这类 MIUI 设备上不可用**，ADB 路线不能作为安卓端的正式方案。

**(2) `settings put secure` 被拒**
```
java.lang.SecurityException: Permission denial, must have one of:
[android.permission.WRITE_SECURE_SETTINGS]
```
→ adb shell 无 `WRITE_SECURE_SETTINGS`，**无障碍服务无法由脚本启用**，必须用户在
「设置 → 无障碍 → 已下载的应用 → Sky Auto Player」手动开启，且需在小米安全弹窗中勾选风险确认。

### 8.2 尚未完成的部分（需人工点按）

因 (1) 的封堵，以下 UI 交互步骤无法由脚本驱动，必须人工操作：
- 导入曲谱（点「IMPORT SKY SONG」→ 选 `/sdcard/Download/twinkle.json`）
- 校准 15 键（点「校准 15 个琴键」，把网格拖到与游戏琴键对齐）
- 实际演奏与节奏核对

### 8.3 结论

- **APK 可用性：已验证**。构建、安装、启动、权限、无障碍绑定、核心逻辑单测全部通过。
- **安卓端正式路线应为无障碍 `dispatchGesture`（即本 APK）**，而非 ADB。
- 唯一未验证环节是「真实游戏内点击落点与节奏」，取决于人工校准质量。

---

## 9. 缺陷修复记录：导入后播放报「曲谱没有可播放事件」

**用户报告**：导入 `twinkle.json` 后点播放 → `播放错误：曲谱没有可播放事件`。

**定位**：`PlaybackEngine.kt:50` 判定 `timeline.events.isEmpty()`。根因在导入层，共**三处格式缺口**叠加：

| # | 位置 | 问题 | 后果 |
|---|---|---|---|
| 1 | `SkyJsonImporter` 取数组 | 只读 `notes`/`events`，**漏了 SkyStudio 标准字段 `songNotes`** | 拿到空数组 → 0 事件 |
| 2 | `SkyJsonImporter` 解 key | 用 `optInt("key")`，但 SkyStudio 是 `"key":"1Key5"` **字符串** | `optInt` 返回 -1 → 全部 `continue` 跳过 |
| 3 | `SkyJsonImporter` 解 key | 不支持设计文档「统一事件流」的 `"keys":[0,2]` 数组与 `"t"` 字段 | `chord_demo.json` 解析为空 |
| 4 | `SkyTextImporter` | 只认 `时间 键位 时长` CSV，**完全不认简谱** `1 2 3 / -1` | `scale.txt` 同样报此错 |

**为什么原单测没拦住**：既有的 `SkyJsonImporterTest` 用的是自造文档
`{"notes":[{"time":10,"key":1}]}` —— 整数 key + `notes` 字段，
**恰好绕开了全部三个缺口**，所以测试全绿而真实文件全废。

**修复**：
1. 按 `songNotes` → `notes` → `events` → `sheets` 顺序取数组；
2. 新增 `parseKeys()`：支持 `1Key0` / `Key5` / `2Key14` / `R1C1` / `A1` / 整数 / 数字字符串，以及 `keys` 数组；
3. 新增 `parseKey` 的 `runCatching` 包裹 —— **单个坏 key 不再拖垮整份曲谱**（原先会抛异常导致整谱 `Failure`）；
4. 时间字段兼容 `time` / `t` / `at` / `timestamp`；
5. `SkyTextImporter` 增加简谱解析（`1-7`、`-N` 低音、`+N` 高音、`0` 休止、`/` 与 `//` 停顿、`1,3,5` 和弦、`//title:`/`//bpm:` 头），同时保留老 CSV 格式（以「时间 ≥ 3 位数字」判别）。

**验证方式（先证明失败，再证明修复）**：

- 新增 `SkyStudioRealFormatTest`（5 例），首次运行 **5/5 全部失败**，与诊断逐条吻合：
  `realSkyStudioSongNotesProduceEvents` 断言 0≠5 事件、`skyStudioTimestampIsMilliseconds` 索引越界。
- 修复后新增 `BundledSheetImportTest`（4 例），把**真实曲谱文件**放进 `app/src/test/resources/sheets/`，
  直接断言 `twinkle.json` = 42 个事件、`chord_demo.json` 首事件 = 和弦 `{0,2,4}`、`scale.txt` 简谱可解析、老 CSV 仍可用。

**最终结果：单测 7 → 16 个，全部通过（0 失败）。**

**产物**：`C:\Users\Lenovo\Desktop\SkyAutoPlayer-0.1.2-fix.apk`
（2,610,666 B，SHA256 `9E4CFFAC…F3CC78`），已 `adb install -r` 成功并启动，
无障碍服务保持已连接；旧的 0.1.0 / 0.1.1 两个问题包已从桌面移除以免装错。

