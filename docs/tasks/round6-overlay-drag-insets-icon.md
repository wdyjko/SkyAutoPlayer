# 本轮工单 · round6：浮层拖动区域 / 状态栏 insets / 应用图标

> **本文件自包含，可直接执行**。稳定约定另见 `docs/agent-conventions.md`（**可选读**，§0.1 已含必备摘要）。
> 除本文件外不要读其它文档（历史工单在 `docs/archive/`，正常情况不需要）。
> 目标工程：`C:\Users\Lenovo\Desktop\sky` ｜ 本轮版本：`versionCode 6 / 0.6.0` → **`7 / 0.7.0`**
> 本文件里的行号是**快照**，开工前必须重新核对（见 §0 最后一条）。

---

## 0. 硬约束摘要（必备，读完再动手）

| # | 约束 |
|---|---|
| 1 | 工程根 `C:\Users\Lenovo\Desktop\sky`，包名 `com.skyautoplayer`，minSdk 26 / targetSdk 35 / compileSdk 35 |
| 2 | 构建与测试**必须**加 `--offline`（本机访问不了 dl.google.com，加依赖必失败）。用绝对路径 `C:\Users\Lenovo\Desktop\sky\gradlew.bat -p C:\Users\Lenovo\Desktop\sky --offline ...` |
| 3 | **不许加任何新依赖**。可用仅：`androidx.core:core-ktx` / `androidx.activity:activity-ktx` / `lifecycle-runtime-ktx` / `kotlinx-coroutines-android`。**Material / Compose / RecyclerView / CardView / ViewPager / TabLayout 一律不可用** |
| 4 | **不重写大文件**：`overlay/PlaybackOverlayService.kt`（约 1376 行）、`ui/MainActivity.kt`（约 795 行）只做**定点修改** |
| 5 | 不重做 / 不重构**已验收**的行为；发现无关 bug → **先报告**，不要顺手改 |
| 6 | 浮层窗口是 `FLAG_NOT_FOCUSABLE`：**不许**在浮层上弹 `AlertDialog` / `PopupWindow` / 输入框（要提示用 `Toast`）。主界面可以正常用 `AlertDialog` |
| 7 | 不改三个 Service 的 `foregroundServiceType`，也不改 FGS 启动时机 |
| 8 | 曲目定位用 `timelineId` / 队列索引，**不要用 title 匹配**（库里有 21 组重名） |
| 9 | 日志 TAG 统一是 `AutoPlay`；测试基线**实测 85 例 / 0 失败**，开工前重跑核对，用例数不得减少 |
| 10 | 每条结论必须附**完整命令 + 原始输出**（界面项附 `screencap` 路径）。**没有命令输出就没有结论** |
| 11 | 引用代码用**符号锚点**（类名/函数名，如 `DragRootLayout.onInterceptTouchEvent`、`openPanel()`）。本文件里的行号只是快照，**开工前重新 `Select-String` 核对**，对不上以实际代码为准并先报告 |
| 12 | 需要用手指点按/听音/看观感的项**只能人工验证**，写进「未完成 / 无法验证」，**不要假装完成** |
| 13 | **交付方式：直接在回答里给出结果，不要新建任何文档或报告文件**（见 §4） |

---

## 0.1 本轮范围（**严格限定**）

| 编号 | 内容 | 一句话理由 |
|---|---|---|
| **W14** | 浮层**拖动区域收紧**：只有原始控制条能拖，展开的曲目面板内滑动不得拖动窗口 | 现在在面板里滑列表会把整个浮层拖走 |
| **W15** | 主界面**状态栏 insets 适配**：顶部卡片不再压到状态栏 | targetSdk 35 在 Android 15+ 强制 edge-to-edge |
| **W16** | **应用图标**：从 `/sdcard/Download/vivo互传/图标.jpg` 重新构图生成自适应图标 | 现在 manifest 没有 `android:icon`，用的是系统默认图标 |
| **W17** | 收尾：构建 + 安装 + 验收 + 回报 | — |

**明确不做**：不重做 W1–W13 的任何已验收行为；不做「最近播放 / 收藏」、自绘跳页键盘、把 `◀◀/▶▶` 移出控制条；不动 `PlaybackEngine` 时序；不加任何依赖。

---

## 1. 锚点快照（**开工前重核**）

| 符号锚点 | 行号快照 | 用途 |
|---|---|---|
| `PlaybackOverlayService.DragRootLayout` | `~1261-1376` | W14：拖动拦截类（窗口根容器） |
| `DragRootLayout.onInterceptTouchEvent` | `~1294` | W14：主改点 |
| `PlaybackOverlayService.openPanel()` 里的 `barColumn.addView(panel)` | `~719` | W14：面板在拦截层**内部**，问题根源 |
| `PlaybackOverlayService.collapse()` / `expand()` | `~508` / `~584` | W14：球必须保持可拖 |
| `PlaybackOverlayService.ballView` | `~334` | W14：给球挂拖动 |
| `MainActivity` 的 `setContentView(...)` / `pages` / `buildDock()` / `showPage()` | `~298` / `~284` / `~333` / `~351` | W15：给根容器加 insets |
| `AndroidManifest.xml` 的 `<application>` | `:6` | W16：加 `android:icon` / `android:roundIcon` |
| `app/src/main/res/` | — | W16：目前**没有** `mipmap-*` 目录 |

---

## W14　拖动区域收紧

### W14.1 根因（已定位）

W11.3 把拖动拦截做成**窗口根容器** `DragRootLayout`（`FrameLayout`），它的 `onInterceptTouchEvent` 对**整个窗口**生效；而曲目面板被塞进了 `barColumn`：

```kotlin
// openPanel()
panel = buildPanel()
barColumn.addView(panel)      // ← 面板在 DragRootLayout 内部
```

于是：手指在面板的 `ScrollView` 里滑动 → 位移超过 `scaledTouchSlop` → 父容器在 `ACTION_MOVE` 时抢先返回 `true` → 触摸被夺走 → 变成拖动窗口，列表不再滚动。这是 Android 触摸分发的必然结果，不是偶发。

### W14.2 修改方案（**采用方案 A，结构性修复**）

把拦截层从"窗口根"**下移**到只包住那条 bar：

```
DragRootLayout (FrameLayout, 窗口根 —— 只做布局，不再拦截)
├─ ballView                      ← 球自己挂拖动
└─ barColumn (LinearLayout, 纵向)
   ├─ DragBarLayout (只包住 bar 行，由它做 onInterceptTouchEvent)   ← 新增
   │  └─ row (横条：≡ ◀◀ ▶ ■ ▶▶ 曲目 校准 歌名 时间)
   └─ panel (曲目面板)             ← 现在是拦截层的**兄弟**，天然不参与拖动
```

要点：

1. 把现有 `DragRootLayout` 的拦截逻辑（`onInterceptTouchEvent` / `pastSlop` / `beginDrag` / `finishDrag` / 长按 250ms）**原样搬到一个新类**（如 `DragBarLayout`），**不要改算法**：仍用 `rawX/rawY` 增量法、仍在超 slop 时丢弃首个位移、仍在拖动结束时吞掉 `ACTION_UP`（避免误触发按钮点击）。
2. `DragBarLayout` 只包 `row`；`panel` 保持为 `barColumn` 的直接子 View，**不经由拦截层**。
3. **球要单独挂同一套拖动逻辑**（它是 `barColumn` 的兄弟，不再受根容器保护）。可直接复用同一个类，或抽成一个可复用的 `View.OnTouchListener` / 辅助类给两处用。
   ⚠️ 目标：**"最小化后球仍能拖动"这个已验收行为不能丢**。
4. **保留长按语义**：长按 250ms 也进入拖动（用户要的"长按任意位置可拖"）。下移之后它的作用范围自然变成"bar 内任意位置"，符合本轮要求。
5. 可选加固：在 `DragBarLayout.onInterceptTouchEvent` 里再判一次"触点是否落在本 View 边界内"，作为双保险。

### W14.3 判据

- 展开曲目面板后，**在面板里上下滑动只滚列表，窗口纹丝不动**（这是本轮要修的核心）。
- 在 bar 上任意位置（含空白/padding）滑动 → 窗口能移动。
- 最小化后，**球仍能拖动**；点球能展开。
- 点 bar 上的按钮**不会**被误判成拖动（拖动的 `ACTION_UP` 仍被吞掉）。

---

## W15　状态栏 insets 适配

### W15.1 根因（已定位）

1. `targetSdk = 35` → 在 Android 15+ 上**强制 edge-to-edge**，`decorFitsSystemWindows` 默认 `false`，内容从 y=0 开始画。
2. `MainActivity` **完全没有 insets 处理** —— 全文只有各卡片的 `setPadding`，没有 `WindowInsets` / `EdgeToEdge` / `setDecorFitsSystemWindows`。
3. `styles.xml` 里的 `android:statusBarColor` 在 edge-to-edge 下**已废弃且被忽略**（对 targetSdk 35 的应用）—— 所以"给状态栏上色"这条老办法不生效。

### W15.2 修改方案

给 `MainActivity` 的**根内容容器**加 insets padding（`androidx.core` 已在白名单里，`ViewCompat` 在 minSdk 26 上也兼容）：

```kotlin
ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
    insets
}
```

要点：

1. **上下都要吃**：顶部 top（状态栏）、底部 bottom（手势导航条）—— 底部 dock 现在会被导航条压住。
2. 根容器是 `setContentView(...)` 传进去的那个容器（当前是「`FrameLayout` 内容区 + dock」的父容器）；**加在根上**，不要逐个页面加。
3. **不要**在浮层服务（`PlaybackOverlayService` / `CalibrationOverlayService`）里加这个 padding —— 它们有自己的窗口定位逻辑（`defaultY()` 用 window metrics insets），两套会打架。
4. **不要**用 `android:windowOptOutEdgeToEdgeEnforcement` 逃逸：那是 API 35 的临时口子，升级 targetSdk 后会被忽略，属于迟早要还的债。
5. `styles.xml` 里的 `statusBarColor` **可以保留**（在 Android ≤14 上仍然生效），不要删。

### W15.3 判据

- 顶部第一张卡片的上边缘在**状态栏之下**，时间/电量图标不再压在卡片上。
- 底部 dock 不与手势导航条重叠。
- 四个 tab 切换后布局仍然正常（不出现双重 padding / 跳动）。
- 浮层（游戏内控制条）位置**没有变化**。

---

## W16　应用图标

### W16.1 源图实测（已探测，直接用这些数字）

| 项 | 值 |
|---|---|
| 路径 | `/sdcard/Download/vivo互传/图标.jpg`（`adb pull` 取；本机副本：`tools/_icon/icon-source.jpg`，155,784 B） |
| 尺寸 | **2400 × 1080**（20:9），无 EXIF 旋转 |
| 内容 | 《光·遇》双人截图（白发面具角色 + 尖顶巫师帽角色），紫→橙渐变星空 + 星点 + 远景塔楼 |
| 主体位置 | 横向 **17%–66%**（x ≈ 408–1584px）、纵向 **17%–100%**（y ≈ 184–1080px，脚触底边） |
| 背景主色 | 紫红/品红→橙桃渐变；前景地面浅蓝紫 |
| 水印 | **右下角有一小串白色字形** → 裁剪框**必须排除**它 |
| 中心正方形裁剪 | ❌ **不可用**：会切掉巫师帽尖、两人腿脚、左侧黑披风与红玫瑰 |

### W16.2 裁剪方案

主体外接框约 **1176 × 896**，比图片高度 1080 还宽 → **正方形裁剪装不下两人完整宽度**。按优先级二选一：

- **方案 1（推荐，人物最完整）**：裁 `x 380–1610 × y 40–1080`（1230×1040），再用同一张图的天空**向外补边成正方形**（模糊拉伸或镜像），然后把内容缩放进安全区。
- **方案 2（最省事，损失很小）**：直接取 1080×1080 正方形，`x 456–1536`、`y 0–1080`，左右各切掉约 48px（≈ 主体边缘 4%，缩到图标尺寸后肉眼几乎不可见）。

两种方案都要**目视确认右下角水印不在裁剪框内**（裁完先存一张中间产物截图核对）。

### W16.3 自适应图标规格（minSdk 26 → 所有目标设备都支持）

| 产出 | 规格 |
|---|---|
| 主路径 | `res/mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml`（`<adaptive-icon>`，含 `foreground` + `background` 两层） |
| **前景层** | 108dp 画布，**主体必须落在中心 72dp 内**（更稳是压在 **66dp 圆**内），否则各厂商圆形/方形遮罩会切到人 |
| 背景层 | 从原图取色的纯色或竖向渐变（例如深紫 `#3A2058`），或直接用模糊过的星空 |
| PNG 密度 | `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_foreground.png` = **108 / 162 / 216 / 288 / 324 px** |
| 兜底 PNG（可选） | `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` = **48 / 72 / 96 / 144 / 192 px** |
| manifest | 在 `<application>` 上加 `android:icon="@mipmap/ic_launcher"` 与 `android:roundIcon="@mipmap/ic_launcher_round"` |

### W16.4 工具与可复现性

- **Pillow 12.3.0 已确认可用**（`python -c "import PIL"`）。
- 写一个幂等脚本 **`tools/build_icon.py`**：把裁剪框、背景色、安全区比例写成**顶部常量**，以后换图改常量重跑即可；脚本要打印每张产出的路径与像素尺寸。
- 不要手工用画图软件产出后就丢掉过程 —— 必须能重跑。

### W16.5 判据

- `aapt2 dump badging app-debug.apk` 输出里出现 `application-icon-*`（当前没有）。
- 装机后桌面图标变成新图；**圆形遮罩下人物不被切**（可用 `adb shell screencap` 或直接看桌面）。
- `res/mipmap-*` 目录已生成且被 manifest 引用。
- ⚠️ **不要顺手改通知栏小图标**：现在用的是 `android.R.drawable.ic_media_play`，若日后要换，`setSmallIcon` **必须用单色 alpha 图**，否则部分 ROM 会显示成白块。本轮不动它。

---

## W17　收尾

```powershell
C:\Users\Lenovo\Desktop\sky\gradlew.bat -p C:\Users\Lenovo\Desktop\sky --offline testDebugUnitTest
C:\Users\Lenovo\Desktop\sky\gradlew.bat -p C:\Users\Lenovo\Desktop\sky --offline assembleDebug
adb install -r C:\Users\Lenovo\Desktop\sky\app\build\outputs\apk\debug\app-debug.apk
```

**直接在回答里给出结果**（**不要新建任何文档 / 报告文件**），内容包含：

1. 完成情况（W14 / W15 / W16 / W17 逐条）
2. 变更清单（每个文件一行：路径 + 增删行数 + 一句话）
3. 验证证据：单测与构建/安装的原始输出、APK 路径 / 大小 / SHA256
4. 下表逐条证据
5. 未完成 / 无法验证（人工项列在这里）
6. 需要用户决策（如有）

---

## 2. 验收

### 2.1 Agent 可自动执行（逐条附命令与原始输出）

| # | 检查 | 期望 |
|---|---|---|
| 1 | 单测 | 全绿，用例数 ≥ **85** |
| 2 | 构建 + 安装 + 冷启动 `logcat` | 成功、无 `FATAL EXCEPTION` |
| 3 | `aapt2 dump badging <apk>` 查 icon | 出现 `application-icon-*` |
| 4 | `ls app/src/main/res` | 出现 `mipmap-anydpi-v26` 与各 `mipmap-*dpi` |
| 5 | 打开主界面后 `adb shell screencap` | 顶部卡片不压状态栏；dock 不被导航条压住 |
| 6 | `adb shell run-as com.skyautoplayer ls files/songs \| wc -l` | 与曲库条目数一致（**回归**：内置 579 首仍可导入） |
| 7 | `adb shell dumpsys window` 查 overlay 窗口与 `mCurrentFocus` | 浮层窗口仍在；浏览态焦点**仍属光遇**（回归 W8） |
| 8 | `app/build.gradle.kts` 依赖块 | **无变化** |

### 2.2 必须人工（在回答里作为「未完成 / 无法验证」列出，不要假装完成）

| # | 动作 | 确认什么 |
|---|---|---|
| M1 | 游戏内展开曲目面板 → 在列表里上下滑 | **窗口不动**、列表在滚 |
| M2 | 在 bar 上任意位置滑 | 窗口能移动 |
| M3 | 最小化 → 拖球 → 点球展开 | 球仍可拖；展开后按钮仍在球心（回归 W11） |
| M4 | 点 bar 上各按钮 | 不会被误判成拖动 |
| M5 | 看主界面 | 顶卡不压状态栏、dock 不被压住 |
| M6 | 看桌面图标（含圆形遮罩） | 新图标生效、人物不被切 |

---

## 3. 本轮停止条件（除 §0 硬约束摘要里的第 3 / 4 / 5 条外）

1. 需要改变 W14 的**拖动算法**（只允许把拦截层下移，算法原样搬）。
2. 需要让"球不可拖"来换取实现便利（W11 已验收行为不可退化）。
3. 需要在浮层服务里也加 insets padding。
4. 需要改通知栏小图标（本轮范围外）。
5. 源图裁剪后**水印仍在框内**且无法通过调整裁剪框排除 → 先报告。
6. 需要修改 `app/build.gradle.kts` 的依赖块。
