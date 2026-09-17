# 浮层曲目搜索 —— 实现与验证记录

> 需求（用户提出）：曲目面板每页 8 首、共 579 首（约 73 页），逐页翻找不现实，
> 需要**在展开曲目挑选时可搜索，以快捷选歌**。
> 版本：0.3.0 → **0.4.0**（versionCode 3 → 4）

---

## 1. 与上一版工单的冲突（必须先说明）

工单 v3.0 §W5.1 明确写着：

> 不做搜索框（`FLAG_NOT_FOCUSABLE` 下拿不到焦点）——搜索留在主界面。

而 `FLAG_NOT_FOCUSABLE` 正是**保证浮层不抢游戏焦点**的那个标志，也是该工单自己
在验收标准里要求的（"`mCurrentFocus` 始终不属于 `com.skyautoplayer`"）。

**要能用系统输入法打字，窗口就必须获得焦点。二者无法同时成立。**

用户明确要求搜索，故本次按需求实现，并采用**影响面最小**的方案：
**只在曲目面板展开期间取得焦点，面板一关闭立刻还原 `FLAG_NOT_FOCUSABLE` 并把焦点交还游戏。**

---

## 2. 实现要点

### 2.1 搜索

- 面板顶部新增 `EditText`（提示"搜索曲名 / 文件名"）+「清除」+「✕ 关闭」
- **同时匹配曲名与源文件名**，大小写不敏感，`trim` 后为空视为不过滤
- 面板展开时**显式** `requestFocus()` 并拉起输入法（不依赖平台的隐式聚焦，实测不稳定）
- 每次输入重置到第 1 页；分页标签在搜索时显示 `页码/总页 · N 首匹配`
- 无匹配时显示"没有匹配「xxx」的曲目"

### 2.2 选中仍按 index/id，不用标题

曲库有 21 组重名（如 `Flower Dance` 两首）。过滤结果带回**在完整队列中的原始下标**，
点击时用该下标调 `SongQueue.loadAndPlay(index)`；高亮也用 `SongEntry.id` 比对。
（本机实测：搜 `flower` 得到 3 条，其中两条同名 `Flower Dance` 被正确分别列出与选中。）

### 2.3 键盘与面板的空间竞争（本机实测发现）

本机为 vivo V2309A，**横屏输入法占据屏幕下半部，可用高度仅 448px / 1260px**。
原实现有三处问题，已逐一修正：

| # | 问题 | 实测现象 | 修正 |
|---|---|---|---|
| 1 | `FLAG_LAYOUT_NO_LIMITS` 使 `ADJUST_RESIZE` 失效，且 `WindowInsets` 不上报 IME | 打字时结果**只露出 1 行** | 改用 `getWindowVisibleDisplayFrame()` + `OnGlobalLayoutListener` 取真实可用高度 |
| 2 | 隐藏按钮行后窗口宽度失去基准 | 宽度从 1120px 塌缩到 768px | 展开面板时锁定 `params.width` 为当前控制条宽度 |
| 3 | 按钮行与分页行在打字时纯属浪费 | 挤压结果区 | 进入搜索模式即隐藏**搬运按钮行(182px)与分页行(112px)**，空间全给结果列表 |

修正后：搜索模式下结果列表可见 **3 行**（原 1 行），搜索框始终可见。

### 2.4 焦点归还

`closePanel()` 中恢复 `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`、
`SOFT_INPUT_STATE_ALWAYS_HIDDEN`，`clearFocus()` 并 `hideSoftInputFromWindow()`。
实测选中一首后：面板关闭、frame 回到 182px 高的控制条、`mInputShown=false`。

> `FLAG_NOT_TOUCH_MODAL` 全程保留，因此面板展开时点击面板以外的区域仍会传给游戏。

---

## 3. 验证证据（全部为 adb 自动化，无人工操作）

本机**未屏蔽 adb 输入注入**（与之前的小米设备不同），因此本轮可以全自动验证。

```
1) 点「开始演奏模式」      → overlay window type=2038 建立
2) am start 切到光遇      → mCurrentOrientation=1（横屏），frame=[120,9][1240,191]
3) 点「曲目」             → 面板展开，搜索框自动聚焦，mInputShown=true
4) input text "flower"    → 见截图 50_search_final.png
5) 点第 2 条结果          → 见截图 51_playing_selected.png
```

**截图 50（搜索生效）**：字段含 `flower`，579 首中筛出 **3 首**：
`Falling Flower 舞い落ちる花びら · 01:38`、`Flower Dance · 05:14`、`Flower Dance · 04:30`

**主界面状态栏读数（选中记录）**：
```
曲库 579 首（显示 579）　当前：Flower Dance
```

**截图 54（播放）**：浮层按钮显示 ⏸（播放中），标签为选中的曲目，15 键完整无遮挡。

### 单测

```
gradlew.bat -p C:\Users\Lenovo\Desktop\sky --offline testDebugUnitTest
→ TOTAL=61 FAILED=0（上一轮 51 → 61，+10 例 SongFilterTest）
```

新增 `SongFilterTest`（10 例）覆盖：空/纯空白查询、中文子串、大小写不敏感、
**下标指向完整队列而非过滤后列表**、源文件名匹配、trim、无匹配、
**重名曲目仍可用 id/下标区分**、579 首分页边界（73 页、末页余 3 条）、
搜索后收敛为单页。过滤逻辑抽为纯函数 `SongFilter` 以便 JVM 单测。

---

## 4. 产物

```
app/src/main/java/com/skyautoplayer/overlay/SongFilter.kt        +45   新增：纯函数过滤/分页
app/src/test/java/com/skyautoplayer/overlay/SongFilterTest.kt   +127   新增：10 例
app/src/main/java/com/skyautoplayer/overlay/PlaybackOverlayService.kt  377 → 809 行
app/build.gradle.kts                                                     versionCode 4 / 0.4.0
```

APK：`C:\Users\Lenovo\Desktop\SkyAutoPlayer-0.4.0.apk`
大小 **4,822,810 B** ｜ SHA256 `6A8D11D4770672C1EFEE632EB0B84970E5D4B66D689A27ECEBAC68FD2A22B609`

---

## 5. 需要你确认的取舍

1. **焦点**：面板展开期间浮层会取得焦点（否则无法输入）。此时游戏会失去焦点，
   但**播放已自动暂停**，选完歌后面板关闭、焦点交还。若你希望"面板常开且不抢焦点"，
   只能放弃系统输入法、改为浮层内自绘键盘——而自绘键盘无法输入中文，中文曲名将搜不了。
2. **搜索自动聚焦**：点「曲目」即弹键盘。如果你更想先翻页、需要时再点搜索框，
   我可以改成"不自动聚焦"。
3. **面板高度**：浏览模式下面板较高（占屏高约 77%），会遮住部分琴键。
   因展开面板即暂停播放，不会漏音；如需更矮可调小 `PANEL_MAX_FRACTION`。

---

## 6. 一处测试假象（如实记录）

自动化流程开头执行了 `am force-stop`，该操作会连同**无障碍服务**一起杀掉进程；
新进程里无障碍尚未重新绑定就派发了手势，于是出现：

```
播放错误：Gesture submission failed: Unavailable
```

`DispatchSubmission.Unavailable` 来自 `PlayerAccessibilityService.instance == null`。
等无障碍重连后再次点播放即正常（截图 54）。
**这是测试流程的产物，不是产品缺陷**——正常使用不会 force-stop。
