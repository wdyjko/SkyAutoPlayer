# 本轮工单 · round8（**热修**）：浮层无限闪烁

> **本文件自包含，可直接执行**。稳定约定另见 `docs/agent-conventions.md`（可选读）。
> 除本文件外不要读其它文档（历史工单在 `docs/archive/`）。
> 目标工程：`C:\Users\Lenovo\Desktop\sky` ｜ 本轮版本：`versionCode 8 / 0.8.0` → **`9 / 0.8.1`**
> 本文件行号是**快照**，开工前必须重新 `Select-String` 核对。

---

## 0. 硬约束摘要（必备）

| # | 约束 |
|---|---|
| 1 | 工程根 `C:\Users\Lenovo\Desktop\sky`，包名 `com.skyautoplayer` |
| 2 | 构建/测试**必须** `--offline`，用绝对路径 `C:\Users\Lenovo\Desktop\sky\gradlew.bat -p C:\Users\Lenovo\Desktop\sky --offline ...` |
| 3 | **不许加任何新依赖** |
| 4 | **不重写大文件**（`PlaybackOverlayService.kt` 约 1450+ 行、`MainActivity.kt` 约 795 行）——本轮是**删代码**，不是重构 |
| 5 | 浮层窗口 `FLAG_NOT_FOCUSABLE`：不许在浮层上弹 `AlertDialog` |
| 6 | 每条结论附**完整命令 + 原始输出**；**没有命令输出就没有结论** |
| 7 | 引用代码用**符号锚点**（`onAccessibilityEvent`、`onResume`、`refreshOverlayVisibility`），行号只作快照 |
| 8 | **交付方式：直接在回答里给结果，不要新建任何文件** |

---

## 0.1 本轮范围

| 编号 | 内容 |
|---|---|
| **W24** | 修掉"浮层无限闪烁"：把 `OverlayController.ownAppForeground` 收敛为**单一写入者**（Activity 生命周期），删除无障碍事件写入者 |
| **W25** | 收尾：构建 + 安装 + 验收 |

**明确不做**：不改 UI、不动图标、不动进度环、不动曲库、不动 `PlaybackEngine`、不重构 `refreshOverlayVisibility` 的结构。

---

## W24　浮层无限闪烁

### W24.1 根因（已定位到行，符号锚点为准）

同一个开关 `OverlayController.ownAppForeground`（`overlay/OverlayState.kt:32-37`）有**两个写入者**：

**写入者 1 —— `MainActivity.onResume` / `onPause`（语义正确）**
```kotlin
// onResume（约 :342-351）
if (PlayerAccessibilityService.instance != null) {          // ← 无障碍守卫
    OverlayController.setOwnAppForeground(true)
}
// onPause（约 :359-363）
OverlayController.setOwnAppForeground(false)
```

**写入者 2 —— `PlayerAccessibilityService.onAccessibilityEvent`（判据本身不成立）**
```kotlin
// 约 :29-34
if (changed.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
val foreground = changed.packageName?.toString() ?: return
OverlayController.setOwnAppForeground(foreground == packageName)   // ← 错误来源
```

**消费方**：`PlaybackOverlayService` 订阅它（约 `:1227`）→ `refreshOverlayVisibility()`（约 `:1247-1258`）：
```kotlin
val a11yConnected = PlayerAccessibilityService.instance != null
val ownForeground = OverlayController.ownAppForeground.value && a11yConnected
val hide = OverlayController.calibrating.value || ownForeground
root.visibility = if (hide) View.GONE else View.VISIBLE
params.flags = if (hide) params.flags or FLAG_NOT_TOUCHABLE else params.flags and FLAG_NOT_TOUCHABLE.inv()
updateViewLayout(...)
```

**为什么必然自激振荡**：**浮层窗口本身就属于 `com.skyautoplayer`**。于是"包名 == 自己"的 `TYPE_WINDOW_STATE_CHANGED` 既可能来自我们的 Activity，**也可能来自我们自己的浮层窗口**：

1. 点搜索框 → 浮层窗口去掉 `FLAG_NOT_FOCUSABLE`、EditText 抢焦点、拉起 IME → 事件包名 = **com.skyautoplayer（我们自己的浮层窗口）** → 开关 `true` → **隐藏浮层**
2. 浮层 `GONE` + `FLAG_NOT_TOUCHABLE` → 焦点交给游戏/输入法/桌面 → 事件包名 ≠ 自己 → 开关 `false` → **显示浮层**
3. 浮层重新显示 → 又改变自身窗口状态 → 回到第 1 步 → **无限循环 = 闪烁**

**"隐藏浮层"这个动作本身就会产生一条归属于我们包名的窗口状态变化** —— 判据在触发自己。

**与实测现象逐条吻合**：
- 必须**开无障碍**才复现 → 关掉后写入者 2 不存在，且 `onResume` 的守卫 + `&& a11yConnected` 让开关恒为 `false`（浮层恒显示，W21 等于失效）
- **用搜索才触发** → 搜索是唯一同时"改窗口 flag + 抢焦点 + 弹 IME"的操作，一次制造一串窗口状态变化
- **回到 App 就停** → Activity 真实前台，`onResume` 持续写 `true`，浮层已 `GONE`，没有焦点交替

**附带影响**：振荡期间 `updateViewLayout` / `FLAG_NOT_TOUCHABLE` 按事件频率反复切换、IME 反复弹收 → 卡顿；条所在矩形内点击时通时断。

### W24.2 修复方案（**只保留一个写入者**）

**① 删除写入者 2** —— `PlayerAccessibilityService.onAccessibilityEvent`（约 `:29-34`）

改成空实现，并**删掉它上面那段已过时的 W21 注释**：

```kotlin
/**
 * 本服务不参与"浮层可见性"的判定 —— 见 OverlayController.ownAppForeground：
 * 该状态由 MainActivity 的生命周期驱动（`onResume` / `onPause`）。
 * 用窗口事件判定是不成立的：浮层窗口本身属于本包，隐藏浮层又会触发新的窗口状态变化，
 * 会造成 hide/show 无限振荡。
 */
override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
```

- 同时删掉 `import com.skyautoplayer.overlay.OverlayController`（若已无其它用法）。
- **保留** `onInterrupt` / `onServiceConnected` / `onDestroy` / `dispatch` 全部原样。

**② `MainActivity.onResume` 去掉无障碍守卫**

```kotlin
// 改前
if (PlayerAccessibilityService.instance != null) {
    OverlayController.setOwnAppForeground(true)
}
// 改后：无条件
OverlayController.setOwnAppForeground(true)
```

- `onPause` 保持无条件 `setOwnAppForeground(false)`；把那条 "re-decided by the accessibility event stream" 的注释改掉（事件流已不再参与判定）。

**③ `refreshOverlayVisibility()` 去掉 `&& a11yConnected`**

```kotlin
// 改前
val a11yConnected = PlayerAccessibilityService.instance != null
val ownForeground = OverlayController.ownAppForeground.value && a11yConnected
// 改后
val ownForeground = OverlayController.ownAppForeground.value
```

- 理由：该守卫是"判据来自无障碍"时代的产物；现在判据来自 Activity 生命周期，可靠。去掉后 **W21 在关闭无障碍时也正常工作**（App 内隐藏 / 游戏内显示），且**开关无障碍行为一致**。
- ⚠️ `a11yConnected` 这个变量**同时还被函数末尾的日志用到**（`"…无障碍连接=$a11yConnected…"`）。本轮的处置：**把该变量一并删除，并去掉日志里的 `无障碍连接=` 字段**（判据已与无障碍无关，留着会误导）。

**④ 把已有日志改成"仅在值变化时打印"**

该函数末尾**已经有一条** `Log.i(TAG, "浮层可见性：hidden=…（本应用前台=…，无障碍连接=…，校准中=…）")`，但它**每次调用都打**——振荡期间会刷屏。改成只在 `hidden` 值变化时打印：

```kotlin
private var lastHiddenLogged: Boolean? = null
...
if (lastHiddenLogged != hide) {
    lastHiddenLogged = hide
    Log.i(TAG, "浮层可见性：hidden=$hide（本应用前台=$ownForeground，校准中=${OverlayController.calibrating.value}）")
}
```

- **不要再加第二条日志**，就地改这一条。

### W24.3 判据

| 场景 | 期望 |
|---|---|
| 开无障碍 → 开始演奏模式 → 进游戏 → 开面板 → **点搜索框输入** | 浮层**稳定显示**、能正常搜索、**不闪** |
| 回 App → 曲库搜索框输入 | 浮层保持**隐藏**、不闪 |
| **关无障碍** → 重复上面两条 | 均不闪；且 **App 内浮层仍隐藏**（W21 依然生效） |
| App ↔ 游戏来回切 5 次 | 隐藏 / 恢复正确，无闪烁 |
| `logcat -s AutoPlay` | 可见性日志**只出现在真正的界面切换时**，不出现高频来回 |

---

## W25　收尾

```powershell
C:\Users\Lenovo\Desktop\sky\gradlew.bat -p C:\Users\Lenovo\Desktop\sky --offline testDebugUnitTest
C:\Users\Lenovo\Desktop\sky\gradlew.bat -p C:\Users\Lenovo\Desktop\sky --offline assembleDebug
adb install -r C:\Users\Lenovo\Desktop\sky\app\build\outputs\apk\debug\app-debug.apk
```

**直接在回答里给出结果**（不要新建任何文件）：

1. 完成情况（W24 / W25）
2. 变更清单（每个文件一行：路径 + 增删行数 + 一句话）—— 本轮预期是**净删除行数**
3. 验证证据：单测 / 构建 / 安装的原始输出、APK 路径 + 大小 + SHA256
4. 判据逐条证据（含 `logcat` 的可见性日志片段）
5. 未完成 / 无法验证（人工项：在游戏内实际点搜索框输入）

---

## 2. 验收

### 2.1 Agent 可自动执行

| # | 检查 | 期望 |
|---|---|---|
| 1 | `gradlew --offline testDebugUnitTest` | 全绿，用例数 ≥ 85 |
| 2 | 构建 + 安装 + 冷启动 `logcat -s AutoPlay` | 成功、无 `FATAL EXCEPTION` |
| 3 | `Select-String "setOwnAppForeground" app/src/main/java` | **只剩 `MainActivity`（2 处）与 `OverlayState.kt` 定义**，`PlayerAccessibilityService` 里**零命中** |
| 4 | `Select-String "a11yConnected" app/src/main/java` | **零命中**（变量与日志字段都要删掉） |
| 5 | 打开 App → `screencap` | 浮层（球 / 控制条）**不可见** |
| 6 | `adb shell dumpsys window` 查本包 overlay 窗口 | 窗口**仍存在**（只是 `visibility=GONE`，服务未被停） |
| 7 | `logcat` 统计可见性日志条数（App 内静置 30s） | **≤ 2 条**（不再高频来回） |
| 8 | `app/build.gradle.kts` 依赖块 | 无变化 |

### 2.2 必须人工（写进「未完成 / 无法验证」）

| # | 动作 | 确认什么 |
|---|---|---|
| M1 | 开无障碍 → 进游戏 → 开面板 → 点搜索框打字 | 浮层**不闪**、可正常搜索（这是本轮的核心复现路径） |
| M2 | 回 App，在曲库搜索框打字 | 浮层保持隐藏、不闪 |
| M3 | 关掉无障碍，重复 M1 / M2 | 均不闪；App 内仍然隐藏 |
| M4 | App ↔ 游戏来回切 5 次 | 隐藏 / 恢复正确，无闪烁 |
| M5 | 回归：游戏内拖动控制条、最小化/展开、进度环 | 未被本轮改动破坏 |

---

## 3. 本轮停止条件

1. 需要**改 `refreshOverlayVisibility()` 的结构**（只允许删掉 `&& a11yConnected` 与加日志）。
2. 需要**保留**无障碍事件作为判据（哪怕"改良"它）—— 本轮方案要求单一写入者。
3. 需要新增依赖，或改动 `PlaybackOverlayService` 的其它行为。
4. 发现 `onPause` 之外还有别的地方在写 `ownAppForeground` → 先报告再动。
