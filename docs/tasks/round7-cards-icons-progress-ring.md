# 本轮工单 · round7：卡片间距 / 图标统一 / 悬浮球进度环 / 前台隐藏浮层 / 按钮文案

> **本文件自包含，可直接执行**。稳定约定另见 `docs/agent-conventions.md`（**可选读**，§0 已含必备摘要）。
> 除本文件外不要读其它文档（历史工单在 `docs/archive/`，正常情况不需要）。
> 目标工程：`C:\Users\Lenovo\Desktop\sky` ｜ 本轮版本：`versionCode 7 / 0.7.0` → **`8 / 0.8.0`**
> 本文件里的行号是**快照**，开工前必须重新核对（见 §0 最后一条）。

---

## 0. 硬约束摘要（必备，读完再动手）

| # | 约束 |
|---|---|
| 1 | 工程根 `C:\Users\Lenovo\Desktop\sky`，包名 `com.skyautoplayer`，minSdk 26 / targetSdk 35 / compileSdk 35 |
| 2 | 构建与测试**必须**加 `--offline`（本机访问不了 dl.google.com，加依赖必失败）。用绝对路径 `C:\Users\Lenovo\Desktop\sky\gradlew.bat -p C:\Users\Lenovo\Desktop\sky --offline ...` |
| 3 | **不许加任何新依赖**。可用仅：`androidx.core:core-ktx` / `androidx.activity:activity-ktx` / `lifecycle-runtime-ktx` / `kotlinx-coroutines-android`。**Material / Compose / RecyclerView / CardView / ViewPager / TabLayout / 任何图标库 一律不可用** |
| 4 | 图标一律用**框架自带的 `VectorDrawable`**（`res/drawable/ic_*.xml`），不要下载 PNG、不要引图标库 |
| 5 | **不重写大文件**：`overlay/PlaybackOverlayService.kt`（约 1453 行）、`ui/MainActivity.kt`（约 795 行）只做**定点修改** |
| 6 | 不重做 / 不重构**已验收**的行为；发现无关 bug → **先报告**，不要顺手改 |
| 7 | 浮层窗口是 `FLAG_NOT_FOCUSABLE`：**不许**在浮层上弹 `AlertDialog` / `PopupWindow` / 输入框（要提示用 `Toast`）。主界面可以正常用 `AlertDialog` |
| 8 | 不改三个 Service 的 `foregroundServiceType`，也不改 FGS 启动时机 |
| 9 | 曲目定位用 `timelineId` / 队列索引，**不要用 title 匹配**（库里有 21 组重名） |
| 10 | 日志 TAG 统一是 `AutoPlay`；测试基线**实测 85 例 / 0 失败**，开工前重跑核对，用例数不得减少 |
| 11 | 每条结论必须附**完整命令 + 原始输出**（界面项附 `screencap` 路径）。**没有命令输出就没有结论** |
| 12 | 引用代码用**符号锚点**（类名/函数名，如 `card()`、`compactButton()`、`barDrawable`、`OverlayController`）。本文件行号只是快照，**开工前重新 `Select-String` 核对**，对不上以实际代码为准并先报告 |
| 13 | 需要手指点按 / 听音 / 看观感的项**只能人工验证**，写进「未完成 / 无法验证」，**不要假装完成** |
| 14 | **交付方式：直接在回答里给出结果，不要新建任何文档或报告文件**（见 §2.4） |

---

## 0.1 本轮范围（**严格限定**）

| 编号 | 内容 | 一句话理由 |
|---|---|---|
| **W18** | 主界面**卡片间距**：演奏 / 校准 / 设置页的卡片之间加 12dp | 现在卡片间距是 **0**，太挤 |
| **W19** | 浮层**图标统一美化**：字符符号 → 统一 vector 图标（含最小化改"圆圈套短横"） | 现在混用符号、字号与按钮宽度都不一致，有割裂感 |
| **W20** | 悬浮球**进度环**：灰色轨道 + 绿色弧，从 12 点顺时针随进度退回灰色 | 现在只是"播放时整圈变绿"，看不出进度 |
| **W21** | **本应用在前台时隐藏浮层**（球与控制条一起） | 现在球会压在 App 卡片上，**并且会吃掉那个位置的点击** |
| **W22** | 「开始演奏模式」按钮**去掉括号文案** | `（进入游戏后不再切屏）` 容易引起歧义 |
| **W23** | 收尾：构建 + 安装 + 验收 | — |

**明确不做**：不重做 W1–W17 任何已验收行为；不动 `PlaybackEngine` 时序；不做「最近播放 / 收藏」、自绘跳页键盘；不改通知栏小图标；不加依赖。

---

## 1. 锚点快照（**开工前重核**）

| 符号锚点 | 行号快照 | 用途 |
|---|---|---|
| `MainActivity.card()` | `~469-479` | W18：卡片内部子元素 `topMargin = 8dp`，但**卡片之间无 margin** |
| `MainActivity.columnPage()` / `scrollPage()` / `performPage` / `calibrationPage` / `settingsPage` | `~481` / `~487` / `~244` / `~262` / `~276` | W18：加卡片间距的位置 |
| `PlaybackOverlayService.compactButton()` | `~411-419` | W19：所有按钮的构建入口（文字 + 不定宽） |
| `PlaybackOverlayService` 里 bar 的按钮注册处（`▶ / ◀◀ / ■ / ▶▶ / ♪ / ◎ / ≡`） | `~214-265` | W19：图标替换点 |
| `BarLayout` 常量对象（`PREV_DP / PLAY_DP / STOP_DP / NEXT_DP / LIBRARY_DP / CALIBRATE_DP / MINIMIZE_BTN_DP / BALL_DP / TITLE_WIDTH_DP / BAR_CONTENT_DP`） | `~1380+` | W19：按钮尺寸统一到 40dp |
| `PlaybackOverlayService.ballDrawable()` | `~469-477` | W20：现在的"实心圆 + 2dp 描边"，要被进度环取代 |
| `PlaybackOverlayService.ballView`（`TextView`，`text="♪"`） | `~334-357` | W20：改成自绘 View |
| `PlaybackOverlayService.displayPositionUs()` | `~1268` | W20：**直接复用**的插值进度 |
| `PlaybackOverlayService.tick` / `TICK_MS = 200L` | `~129-132` / `~1447` | W20：复用这个 200ms ticker |
| `PlayerAccessibilityService.onAccessibilityEvent`（现在是 `= Unit`） | `:16` | W21：实现前台包名监听 |
| `OverlayController`（共享 `StateFlow` 容器） | `OverlayState.kt:13-20` | W21：加"前台是否本应用"的状态 |
| `MainActivity` 的 `styledButton("开始演奏模式（进入游戏后不再切屏）", …)` | `:207` | W22 |

---

## W18　主界面卡片间距

### W18.1 根因

`card()`（`~469-479`）给卡片**内部**子元素设了 `topMargin = 8dp`，但把卡片加到页面时（`performPage` 里三次 `addView(card(...))`）**没有传 LayoutParams** → **卡片之间零间距**。

### W18.2 改法

间距职责放在**页面层**，不要塞进 `card()`：

```kotlin
// 页面层统一加间距；card() 内部排版保持 8dp 不动（可微调到 10dp）
private fun LinearLayout.addCard(v: View, gapDp: Int = 12) =
    addView(v, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(gapDp) })
```

| 位置 | 现在 | 目标 |
|---|---|---|
| 卡片之间 | **0dp** | **12dp** |
| 卡片内部子元素 | 8dp | 10dp（略松一点更透气） |
| 页面左右 padding | 12dp | 12dp（保持） |
| 页面底部（dock 之上） | 8dp | 12dp |

- **演奏 / 校准 / 设置三个页面必须用同一套**（`曲库`页是列表、结构不同，但页面级 padding 与底部留白保持一致）。
- `校准` / `设置` 是 `scrollPage`，改法相同。
- 只在**卡片之间**加，**不要**在最后一张卡片后面留出可滚动空隙（`scrollPage` 的底部 padding 已由页面负责）。

### W18.3 判据

三个页面各截一张图：卡片之间等距 **12dp**、左右留白一致、底部不贴 dock；`曲库`页观感不被破坏。

---

## W19　浮层图标统一美化

### W19.1 根因（4 条，都有证据）

1. **混用字符集**：`≡`(18f) / `◀◀`(13f) / `▶`(14f) / `■`(13f) / `▶▶`(13f) / `♪`(20f) / `◎`(13f) —— 有几何符号、有实心三角/方块、有双字符拼接，**风格天然不统一**。
2. **字号不同**（13/14/18/20f）→ 视觉大小必然不一。
3. **按钮宽度不同**（`PREV/PLAY/STOP/NEXT/LIBRARY/CALIBRATE` = 36/42/38/36/46/46dp）→ 间距看着不均。
4. **`Button` 按文字基线排版**，不是几何居中；再叠上 `Button` 自带背景 → "不居中"。

### W19.2 目标规格

| 要点 | 要求 |
|---|---|
| 形式 | 每个图标一个 `res/drawable/ic_*.xml`，`<vector>` **24×24 viewport**，路径用 `fillColor="@android:color/white"` + 由代码/XML `tint` 上色 |
| **不要** | 不要下载 PNG（会糊、要出 5 套密度）；不要引图标库；不用 `TextView` 显示字符 |
| 按钮 | 统一 **40×40dp**，图标 24dp 居中；用 `ImageButton`（`scaleType="centerInside"`）或 `Button` + `setCompoundDrawables`，背景用主题自带的 `?android:attr/selectableItemBackgroundBorderless`（有 ripple，零依赖） |
| tint | 常态 `@color/text_primary`，播放态 `@color/accent` |
| 许可 | 若从开源图标集抄 path 数据，**必须在 XML 头注释写明来源与许可**（推荐 Material Symbols = Apache-2.0；Feather = MIT；Lucide = ISC） |

### W19.3 图标选型

| 位置 | 现在 | 目标 | 说明 |
|---|---|---|---|
| 最小化 | `≡` | **圆圈 + 中间短横** | 用户指定；画法：`<path>` 一个圆环（stroke 或用两段弧）＋ 一条 8dp 短横；圆直径约 20dp（在 24dp viewport 内） |
| 上一首 / 下一首 | `◀◀` / `▶▶` | `skip_previous` / `skip_next` | 三角 + 竖条，语义标准 |
| 播放 / 暂停 | `▶` | `play_arrow` / `pause` | 播放态切 `pause` |
| 停止 | `■` | `stop` | 圆角方块，比实心方块精致 |
| 曲目 | `♪` | `queue_music` / `library_music` | |
| 校准 | `◎` | `tune` / `graphic_eq` | 比同心圆表意 |

- 曲目面板内部的「清除 / ✕ / 上一页 / 下一页」等文字按钮**不在本轮图标化范围**（它们靠文字更清楚）；但**尺寸与圆角风格要与 bar 保持协调**。
- 悬浮球中心的 `♪` 也一并换成 `ic_music_note`（与 W20 一起做）。

### W19.4 判据

把 bar 上的 **7 个按钮截图放大后横向并排**：视觉重心在同一水平线上、视觉大小一致、线宽一致、无"实心 vs 空心"混搭；点击各按钮功能与之前一致（不得因为换 `ImageButton` 而丢掉原来 bind 的点击逻辑）。

---

## W20　悬浮球进度环

### W20.1 现状

`ballDrawable()`（`~469-477`）返回一个 `GradientDrawable(OVAL)`：深色实心填充 + **2dp 整圈描边**；播放时描边变绿（`0xFF7FD1B9`），否则灰（`0xFF8A9099`）。所以现在是"**整圈变绿**"，看不出进度。

### W20.2 目标

灰色**轨道环** + 绿色**进度弧**：绿色从 **12 点方向顺时针**开始，随播放进度**逐渐退回灰色**。

```
style = STROKE, useCenter = false, strokeCap = ROUND, strokeWidth ≈ 3dp
startAngle = -90 + 360 * progress
sweepAngle = 360 * (1 - progress)
```

- **`useCenter = false` 是关键** —— 否则画出来是扇形而不是环。
- **必须先改底色**：现在是**实心**填充，直接叠绿弧会糊在一起。目标层次：深色半透明填充圆 → 灰色轨道环 → 绿色进度弧 → 中心图标。
- 实现方式：把 `ballView` 从 `TextView` 换成**自绘 `View`**（`onDraw` 里画上面四层；中心图标用 `Drawable`（W19 的 vector）`setBounds` 后 `draw`）。

### W20.3 进度来源与刷新（**必须复用现有实现，不要另起一套**）

- 进度：`displayPositionUs()`（`~1268`，已做本地插值）÷ `PlaybackState` 的 `durationUs`。
- 刷新：复用现有 `tick`（`TICK_MS = 200L`，`~129-132`）→ 每 200ms `invalidate()`。对 3 分半的曲子每步约 **0.35°**，肉眼平滑。
- ⚠️ **不要**直接用 `PlaybackState.positionUs`（它只在派发音符时更新，会一跳一跳）。

### W20.4 状态语义（**必须区分"暂停"和"播完"**，否则两者长得一样）

| 状态 | 轨道环 | 进度弧 | 中心图标 |
|---|---|---|---|
| `Idle` / `Ready(position=0)` | 灰 | 无（整圈灰） | ♪ |
| **`Playing`** | 灰 | 绿，从 12 点顺时针递减 | ♪ |
| **`Paused`** | 灰 | **保持当前进度**，颜色改**暗绿/琥珀**（如 `@color/accent_dim`） | ⏸ |
| 播完（`Ready` 且 `position == duration`） | 灰 | 无（整圈灰） | ♪ |

### W20.5 其它要求

- **触摸区域仍是 48dp**（`BALL_DP`）：环画在直径 44dp 内，不要为了好看把球缩小。
- 收起/展开的缩放动画期间，环跟着 scale 即可，**不要重置进度**。
- 性能：48dp 小区域每 200ms 一次 `invalidate()`，可忽略；但 `onDraw` 里不要分配对象（`Paint` 提到字段里复用）。

### W20.6 判据

播放一首 ≥3 分钟的曲子：截图 3 次（开始时 / 中段 / 接近结束）→ 绿色弧从 12 点顺时针**单调递减**；暂停时弧停住且颜色变化；播完整圈灰。

---

## W21　本应用在前台时隐藏浮层

### W21.1 现状与问题

overlay 窗口浮在**所有**应用之上，**本应用在前台时也显示**。后果：

1. 观感割裂：球压在 App 卡片左上角，看起来像布局 bug；
2. **功能干扰**：球是**可触摸窗口**，会**吃掉那个位置的点击** —— 在 App 里点那个位置会展开浮层，而不是点卡片。

### W21.2 改法

**只在"前台包名 == 本应用"时隐藏浮层（球 + 控制条一起）；其它情况一律保持显示。**

1. 实现 `PlayerAccessibilityService.onAccessibilityEvent`（现在是 `= Unit`）：
   - 监听 `AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED`（必要时加 `TYPE_WINDOWS_CHANGED`）
   - 取 `event.packageName`
2. 在 `OverlayController`（`OverlayState.kt:13-20`，已有 `calibrating` 的 `MutableStateFlow`）里新增一个状态，例如
   `val ownAppForeground: StateFlow<Boolean>`，由无障碍服务更新。
3. `PlaybackOverlayService` 订阅它：为 `true` 时 `root.visibility = GONE`，为 `false` 时恢复 `VISIBLE`。
4. **判定策略（重要，避免闪烁）**：**只在"包名 == `com.skyautoplayer`"时隐藏**，其它任何包名（系统 UI、输入法、通知栏、别的 App）一律**保持显示**。
   这样拉下通知栏、弹输入法时浮层不会闪。
5. **兜底**：无障碍服务未连接 / 收不到事件时，**保持显示**（不要默认隐藏）。
6. 初始值：服务刚启动时（从 App 内点「开始演奏模式」）前台就是本应用 → 建议初值 `false`（显示），让第一次事件把它纠正过来；也可以初值就判一次当前前台，**但必须避免"启动瞬间闪一下"**。

### W21.3 注意

- **不要**因为隐藏就 `stopSelf` 或停掉前台服务 —— 只是 `visibility` 切换。
- 切回光遇后浮层必须**自动恢复**（靠同一个事件流，不需要用户操作）。
- 光遇的真实包名**用 `adb shell pm list packages` 实测**，不要写死猜测值；本应用自己前台时隐藏这条逻辑只依赖本应用包名，与光遇包名无关。

### W21.4 判据

| 场景 | 期望 |
|---|---|
| App 在自己前台 | 浮层（球与控制条）**不可见**；卡片该位置的点击**正常生效** |
| 切到光遇 | 浮层**自动出现**（不需要重新点「开始演奏模式」） |
| 在游戏里拉下通知栏 / 弹输入法 | 浮层**不闪**（仍显示） |
| 无障碍服务未连接 | 浮层**保持显示**（兜底） |

---

## W22　按钮文案

`MainActivity` 里（`:207`）：

```kotlin
val performModeButton = styledButton("开始演奏模式（进入游戏后不再切屏）", primary = true) { … }
```

改为：

```kotlin
val performModeButton = styledButton("开始演奏模式", primary = true) { … }
```

- 只改**按钮文案**；代码注释里的「开始演奏模式」（`:58`、`:351`）以及校准页的提示文案（`:269` 的 "1. 先点「开始演奏模式」，再切到光遇（横屏）。"）**保持不动**。
- 全文检查是否还有别处出现该括号文案（`strings.xml` 里目前没有）。

**判据**：按钮上只剩「开始演奏模式」；`Select-String "不再切屏"` 在 `res/` 与 `ui/` 下**零命中**（校准页提示里那句不含该词）。

---

## W23　收尾

```powershell
C:\Users\Lenovo\Desktop\sky\gradlew.bat -p C:\Users\Lenovo\Desktop\sky --offline testDebugUnitTest
C:\Users\Lenovo\Desktop\sky\gradlew.bat -p C:\Users\Lenovo\Desktop\sky --offline assembleDebug
adb install -r C:\Users\Lenovo\Desktop\sky\app\build\outputs\apk\debug\app-debug.apk
```

**直接在回答里给出结果**（**不要新建任何文档 / 报告文件**），内容包含：

1. 完成情况（W18–W23 逐条）
2. 变更清单（每个文件一行：路径 + 增删行数 + 一句话）
3. 验证证据：单测与构建/安装的原始输出、APK 路径 / 大小 / SHA256
4. §2.1 逐条证据
5. 未完成 / 无法验证（人工项列在这里）
6. 需要用户决策（如有）

---

## 2. 验收

### 2.1 Agent 可自动执行（逐条附命令与原始输出）

| # | 检查 | 期望 |
|---|---|---|
| 1 | `gradlew --offline testDebugUnitTest` | 全绿，用例数 ≥ **85** |
| 2 | 构建 + 安装 + 冷启动 `logcat -s AutoPlay` | 成功、无 `FATAL EXCEPTION` |
| 3 | `ls app/src/main/res/drawable/ic_*.xml` | 新增 7 个以上图标文件 |
| 4 | `Select-String "不再切屏" app/src/main` | **零命中** |
| 5 | `git diff`/人工比对 `app/build.gradle.kts` 依赖块 | **无变化**（未引依赖） |
| 6 | App 在前台时 `adb shell screencap` | 浮层（球 / 控制条）**不可见** |
| 7 | `adb shell dumpsys window` 查本包 overlay 窗口 | 窗口**仍存在**（只是 `visibility = GONE`，不是服务被停） |
| 8 | 三个页面各 `screencap` | 卡片间距均匀、左右留白一致 |
| 9 | 播放中两次截图（间隔 ~30s） | 进度弧位置不同（在走） |
| 10 | `adb logcat -d -s AutoPlay` 回归 | 内置曲库 579 首仍正常（首次导入日志或曲库计数） |

### 2.2 必须人工（在回答里作为「未完成 / 无法验证」列出，不要假装完成）

| # | 动作 | 确认什么 |
|---|---|---|
| M1 | 看三个页面 | 卡片间距观感是否舒服（12dp 是否足够 / 是否要 16dp） |
| M2 | 放大对比 bar 上 7 个图标 | 是否**居中、大小一致、风格统一**（这是本轮的主观测点） |
| M3 | 点最小化 | 圆圈套短横的图标是否符合预期 |
| M4 | 播放一首长曲看球上的环 | 绿色是否从**正上方顺时针**逐渐退回灰色；暂停 / 播完是否能区分 |
| M5 | 在 App 里点原先球的位置 | 点击**是否正常落到卡片上**（不再被球吃掉） |
| M6 | 切到光遇 → 切回 App → 再切回光遇 | 浮层是否正确隐藏 / 恢复，有无闪烁 |
| M7 | 回归：在游戏内展开曲目面板滑动、拖动 bar、最小化/展开 | W14 / W11 的行为未被破坏 |

---

## 3. 本轮停止条件（除 §0 硬约束里的第 3 / 4 / 5 / 6 条外）

1. 需要**改 `BarLayout` 的 BALL_DP（48dp）或把球缩小** → 触摸目标不能小于 48dp。
2. 需要为了做图标而**引入图标库 / 下载 PNG**。
3. 需要在 W21 里**停掉前台服务**来隐藏浮层（只允许切 `visibility`）。
4. 需要让浮层在"非本应用"的任何情况下也隐藏（会造成通知栏/输入法时闪烁）。
5. 需要修改 `card()` 的**内部**间距语义之外的东西（例如把间距塞进 `card()` 导致外部无法覆盖）。
6. 改了 bar 图标的实现方式后**点击功能失效**，且无法在不重写 `compactButton` 的前提下修好 → 先报告。
