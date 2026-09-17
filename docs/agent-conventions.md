# Agent 常驻约定（光遇自动弹琴 · Android）

> **本文件每轮都要读**；本轮具体要做什么在 `docs/tasks/<本轮>.md` 里。
> 阅读顺序：**本文件 → 本轮的 task 文件 →（其它一律不要读）**。
> 最后实测更新：2026-09-16

---

## 1. 工程

| 项 | 值 |
|---|---|
| 工程根 | `C:\Users\Lenovo\Desktop\sky` |
| 包名 / 主 Activity | `com.skyautoplayer` / `.ui.MainActivity` |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| Kotlin / AGP | 2.1.10 / 8.9.2 |
| 当前版本 | `versionCode 6` / `versionName "0.6.0"`（下一版号以本轮 task 文件为准） |
| 应用性质 | 光遇（Sky）自动弹琴；**只模拟输入**（无障碍手势），不读内存、不注入 |
| 目标设备 | Android 16，1260×2800 @560dpi；**横屏 2800×1260 = 800dp 宽**（density 3.5） |
| 光遇包名 | 用 `adb shell pm list packages` 实测，**不要凭记忆断言** |

---

## 2. 构建 / 测试 / 安装（**必须离线**）

```powershell
# 测试：开工前先跑一次，把「实测用例数」记为本轮基线
C:\Users\Lenovo\Desktop\sky\gradlew.bat -p C:\Users\Lenovo\Desktop\sky --offline testDebugUnitTest

# 构建
C:\Users\Lenovo\Desktop\sky\gradlew.bat -p C:\Users\Lenovo\Desktop\sky --offline assembleDebug

# 安装 / 启动 / 日志
adb install -r C:\Users\Lenovo\Desktop\sky\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.skyautoplayer/.ui.MainActivity
adb logcat -d -s AutoPlay        # 全工程日志 TAG 统一为 AutoPlay
```

- **`--offline` 是强制的**：本机无法访问 `dl.google.com`，加任何新依赖都会构建失败。
- `gradle` 可能不在 PATH，用上面的 `gradlew.bat` 绝对路径。
- 若写不了 `C:\Users\Lenovo\.gradle` 或工程目录，Gradle 会直接失败 —— 先确认写权限。
- **测试基线**：2026-09-16 实测 **85 例 / 0 失败**（16 个测试类）。开工前重跑核对，之后**用例数不得减少**。

---

## 3. 依赖白名单（**不许加任何新依赖**）

`app/build.gradle.kts` 的 `dependencies` 只允许这些（其余一律不可用）：

```
implementation  androidx.core:core-ktx:1.15.0
implementation  androidx.activity:activity-ktx:1.10.1
implementation  androidx.lifecycle:lifecycle-runtime-ktx:2.8.7
implementation  org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1
```

**明确不可用**：Material Components、Compose、RecyclerView、CardView、ViewPager、TabLayout、
BottomNavigationView、Room、Gson、kotlinx-serialization。

界面一律用**框架自带控件**（`LinearLayout` / `FrameLayout` / `ListView` / `ScrollView` / `TextView` / `Button`）
+ `res/drawable/*.xml` + `res/values/colors.xml` + `res/values/styles.xml`。JSON 用 `org.json`。

---

## 4. 禁止事项（每轮都适用）

1. **不加依赖**（见 §3），尤其不许为"美化"引入 UI 框架。
2. **不重做、不重构已验收的实现**；只在现有结构上**增量修改**。
3. **不重写大文件**：`overlay/PlaybackOverlayService.kt`（约 1376 行）、`ui/MainActivity.kt`（约 795 行）都只做**定点修改**。
4. 不改三个 Service 的 `foregroundServiceType`，也不改 FGS 启动时机（`MainActivity.onCreate` 里的预热启动是必需的）。
5. 不在**浮层**窗口上用 `AlertDialog` / `PopupWindow` / 任何需要输入焦点的控件 —— 浮层是 `FLAG_NOT_FOCUSABLE`，弹不出来；要提示就用 `Toast` 或自绘 View。（主界面可以正常用 `AlertDialog`。）
6. 不为了动画或纯视觉**让浮层超出屏幕边界**。
7. 曲目定位一律用 `timelineId` / 队列索引，**不要用 title 匹配**（库里有 21 组重名）。
8. 不绕过删除墓碑（`deleted_bundled`），不改 `BUNDLED_SHEETS_VERSION` 的语义。
9. 不用 `adb shell input tap` 之类注入输入来"驱动"手机；需要点按的步骤一律列入人工验收。
10. 不声称任何未实测的结论 —— **没有命令输出就没有结论**。
11. 不顺手改与本轮任务无关的代码；发现无关 bug → **先报告**。

---

## 5. 证据规则

每条"已完成 / 已修复 / 已验证"，必须紧跟：

1. 执行的**完整命令**；
2. **原始输出**（可截取关键行，不得改写）；
3. 涉及界面时，附 `screencap` 的**保存路径**。

拿不到证据的，写进回报的「未完成 / 无法验证」，**不要美化**。

---

## 6. 代码定位规则（**重要，这是本文件存在的首要原因**）

- 引用代码一律用**符号锚点**：类名 / 函数名 / 注释标签
  （例：`DragRootLayout.onInterceptTouchEvent`、`openPanel()`、`ballColumn`、`showPage()`、`buildDock()`）。
- task 文件里给的行号只是**快照**。**开工前必须重新 `Select-String` 核对**；对不上就以实际代码为准，并先报告差异再动手。
- 每轮改动都会让行号整体漂移 —— 不要相信任何跨轮的行号。

---

## 7. 交付方式（**不要新建文件**）

每轮结束时，**直接在回答里**给出结果。**不要创建任何文档 / 报告文件。**

回答里包含：

```
## 完成情况
- W14：<已完成/部分>  证据：<命令 + 关键输出>
- ...

## 变更清单（每个文件一行）
<路径>  <+新增行/-删除行>  <一句话说明>

## 验证证据
- 单测：<命令> → <x/y passed>（基线 <N>）
- 构建 / 安装：<命令> → <结果>
- 本轮专项证据：<按 task 文件的验收表逐条给命令与原始输出>
- APK：<路径> / <大小> / <SHA256>
- 截图：<保存路径>

## 未完成 / 无法验证
<逐条列出原因；需要人工动手的一律写在这里>

## 需要用户决策
```

---

## 8. 人工验收分工

| 只能自动 | 必须人工 |
|---|---|
| 构建、单测、安装、`logcat`、`dumpsys`、`screencap`、文件系统 / SharedPreferences 检查、`aapt2 dump badging` | 在光遇里用手指点浮层、**听第一个音是否落在正确琴键**、拖动与收缩的手感、桌面图标观感 |

人工项一律在回答里列为「未完成 / 无法验证」，**不要假装完成**。

---

## 9. 稳定事实附录（跨轮不变）

| 事实 | 值 |
|---|---|
| 内置曲库 | **579 首**，`app/src/main/assets/bundled_sheets/*.txt`（UTF-8+BOM，共 12.2 MB），**全部打包进 APK** |
| 筛选口径 | 源 702 → 可解析 685 → 时长 ≥55s 保留 581 → 按源文件名点名排除 2 → **最终 579** |
| 排除明细 | 104 按时长 + 2 点名（`未命名.txt`、`IPhone马林巴琴.txt`）+ 10 加密谱 + 7 坏文件 = 123 |
| 导入器行为 | 严格按**毫秒时间戳**分桶（无合并窗口）；`hold` 缺省 **60ms**；`duration = max(at+hold)` |
| 关键模块锚点 | `overlay/PlaybackOverlayService`（控制条 / 曲目面板 / 悬浮球）、`overlay/CalibrationOverlayService`、`storage/SongStore`、`storage/BundledSheetSeeder`、`storage/BundledTombstones`、`application/SongQueue`、`application/PlaybackRuntime`、`ui/MainActivity` |
| 设备前提 | **Android 15+ 强制 edge-to-edge**（targetSdk 35），任何新页面 / 根容器都要处理 window insets |

---

## 10. 文档结构

```
docs/
├─ agent-conventions.md          ← 本文件：稳定约定（可并入每轮工单，也可选读）
├─ tasks/roundN-<主题>.md         ← 每轮一份工单；发给 agent 的就是这一个文件
└─ archive/                       ← 历史工单与历史回报，正常情况不要读
```

> **交付方式：agent 直接在回答里给结果，不新建文件。**
> 每轮给 agent 的指令可以短到一句话：
> 「读 `docs/tasks/round6-overlay-drag-insets-icon.md` 并执行；结果直接在回答里给我。」
