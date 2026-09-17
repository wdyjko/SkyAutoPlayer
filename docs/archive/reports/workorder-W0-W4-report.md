# 执行回报：游戏内浮层控制面（工单 v2.0）

> 目标工程：`C:\Users\Lenovo\Desktop\sky` ｜ 包名 `com.skyautoplayer`
> 执行日期：2026-09 ｜ 版本：0.1.0 → **0.2.0**

---

## 完成情况

- **W0**：**通过**（0.1–0.3、0.5–0.8 全部实测；0.4 为人工项）
  证据：见下「验证证据」第 5–8 行；5 分钟复测进程 PID 与窗口 token 前后一致。
- **W1**：**已完成**。文件：新增 2 / 删除 2 / 修改 1。
- **W2**：**已完成**。`CalibrationOverlayService.saveAndClose()` 先拉起控制条再 `stopSelf()`。
- **W3**：**已完成**。主界面新增「开始演奏模式」按钮，截图已确认渲染。
- **W4**：**已完成**。构建、安装、冷启动、焦点、浮层存活、按钮命令全部有原始输出。

---

## 变更清单（每个文件一行）

```
app/src/main/java/com/skyautoplayer/overlay/PlaybackOverlayService.kt    +377 -0    新增：游戏内演奏控制条（由 OverlayService 改造而来）
app/src/main/java/com/skyautoplayer/overlay/OverlayService.kt              +0 -21    删除：已被 PlaybackOverlayService 取代（重命名）
app/src/main/java/com/skyautoplayer/overlay/OverlayState.kt                +0 -11    删除：OverlayController 是死代码（全工程无引用，工单 §W1 允许二选一）
app/src/main/java/com/skyautoplayer/overlay/OverlayGeometry.kt            +28 -0     新增：控制条几何（纯函数，可单测）——防拖出屏幕
app/src/main/java/com/skyautoplayer/overlay/CalibrationOverlayService.kt   +6 -1     改：saveAndClose() 保存后拉起控制条再 stopSelf()
app/src/main/java/com/skyautoplayer/ui/MainActivity.kt                    +17 -0    改：新增「开始演奏模式」按钮（保留原有导入/校准/无障碍/播放暂停停止）
app/src/main/AndroidManifest.xml                                           +1 -1    改：service 名同步改为 .overlay.PlaybackOverlayService
app/build.gradle.kts                                                       +2 -2    改：versionCode 1→2，versionName 0.1.0→0.2.0
app/src/test/java/com/skyautoplayer/overlay/OverlayGeometryTest.kt        +56 -0    新增：几何单测 5 例
```

---

## 验证证据

**1. 单测**
```
gradle -p C:\Users\Lenovo\Desktop\sky --offline testDebugUnitTest
→ TOTAL=38 FAILED=0（退出码 0）
```
> ⚠️ 与工单差异：工单写基线 **16 个**，实际是 **38 个**。原因是前几轮为修复真实曲谱格式缺口
> 新增了 33 个用例（`SkyStudioRealFormatTest` / `BundledSheetImportTest` / `RealUserSheetTest` /
> `AcceptedSheetShapesTest` / `AllBundledSheetsParseTest`），本轮又加 5 个几何用例。
> **没有任何用例被删除**，未触发 §0.5 第 6 条。

**2. 构建**
```
gradle -p C:\Users\Lenovo\Desktop\sky --offline assembleDebug
→ BUILD SUCCESSFUL in 37s
```

**3. 安装**
```
adb -s O76DJZLV694PHMAQ install -r C:\Users\Lenovo\Desktop\SkyAutoPlayer-0.2.0.apk
→ Success
APK 路径：C:\Users\Lenovo\Desktop\SkyAutoPlayer-0.2.0.apk
大小：2,606,680 字节
SHA256：788DCF12B5E826140B7A933C21D6EFA6974F35769E474AD35D9FEB75D854D949
versionCode=2 versionName=0.2.0 (aapt2 dump badging)
```

**4. 冷启动**
```
adb -s O76DJZLV694PHMAQ logcat -d | Select-String "FATAL EXCEPTION"
→ no FATAL EXCEPTION
AutoPlay 日志：PlaybackForegroundService onCreate：前台播放服务已启动
```

**5. 浮层窗口存在**
```
dumpsys window windows → WINDOW: 17 Window{ae38c0e u0 com.skyautoplayer}:
                          Frames: parent=[104,0][3200,1440] frame=[104,132][818,314]
```
横屏下宽 714px、高 182px。

**6. 焦点归属（本工单最关键的一条）**
```
切到光遇后：mCurrentFocus=Window{333b3f2 u0 com.netease.sky.huawei/com.tgc.sky.netease.GameActivity_Netease}
```
焦点属于光遇**游戏本体 Activity**，不属于 `com.skyautoplayer` ✓

**7. 截图**
- `tools/_shots/13_after_5min.png` — 光遇游戏本体 + 控制条浮于其上（未覆盖下半屏琴键）
- `tools/_shots/17_compact_width.png` — 最终版控制条（714px 宽，占横屏 22%）
- `tools/_shots/10_main_v020.png` — 主界面新增按钮

**8. 5 分钟存活复测**
```
T0        : ps → 14907 com.skyautoplayer ; Window token 3c17339
T+5min    : ps → 14907 com.skyautoplayer ; Window token 3c17339
            mCurrentFocus = com.netease.sky.huawei/...GameActivity_Netease
```
**PID 与窗口 token 前后完全一致** —— 进程未被 MIUI 杀、浮层未被移除 ✓

**9. 三个按钮真的调到引擎**
```
控制条收到命令：Stop+Play → 收到命令：Stop → 收到命令：Play → play() 已开始：title=龙卷风, positionUs=0
控制条收到命令：Pause     → 收到命令：Pause
控制条收到命令：Stop      → 收到命令：Stop
手势统计：accepted=22, completed=20, cancelled=2
```
`positionUs=0` 证明「从头播放」语义正确（先 stop 再 play）✓

---

## 与工单描述不一致之处（§0.5 第 7 条要求先报告）

| # | 工单描述 | 实际情况 | 处理 |
|---|---|---|---|
| 1 | 单测基线 16 个 | 实际 **38 个**（历史新增，无删除） | 以实际为准，已在上文标注 |
| 2 | §2.2「无障碍服务授权 ✅ 已验证已启用」 | 开工时**处于关闭状态**（多次重装导致） | 已请用户重新开启，现已启用 |
| 3 | §2.1「`OverlayController` 同为死代码」 | 确认存在，位于 `OverlayState.kt:7` | 按 §W1 选项删除 |
| 4 | §W1 要点 9「光遇乐器键在**下半屏**」 | **实测不成立**：横屏键盘位于**中间带**，顶部约在屏幕高度 **17.7%** 处（~255px），底部到 ~76% | 以实测为准，见下「需要用户决策」 |
| 5 | §W1 要点 2「约 0.9×屏宽」 | 已按用户明确要求改为**内容自适应宽度** | 见下 |

---

## 未完成 / 无法验证（需人工）

| # | 项 | 状态 | 原因 |
|---|---|---|---|
| M1 | 点「校准 15 个琴键」验证浮层出现 | 已由用户完成 | Agent 无法注入输入（MIUI 拒 `INJECT_EVENTS`） |
| M2 | 手动切到光遇验证浮层仍在 | **已由 Agent 自动完成**（`am start` 不需要输入注入） | — |
| M3 | 手指点游戏本身（琴键/走动）验证游戏正常响应 | ❌ **未验证** | 需人工。这是 `FLAG_NOT_FOCUSABLE` 地基的最终判据 |
| M4 | 光遇乐器界面点浮层「播放」，核对第一个音落点 | ❌ **未验证** | 需人工。日志已证明手势被 accepted/completed，但「落在正确琴键、开头不丢音」只能人耳/人眼判断 |
| M5 | 播放中点暂停/继续 | 部分验证 | 命令已确认到达引擎；游戏内是否有残留按音需人工 |
| M6 | 挂 5 分钟看浮层与通知 | ✅ 浮层已验证；常驻通知未截图确认 | — |
| M7 | 全程录屏 | ❌ 未做 | 需人工 |

---

## 需要用户决策

1. **控制条尺寸已按你的反馈改小**（2786px → 714px，横屏占比 87% → 22%）。
   工单 §W1 要点 2 要求「约 0.9×屏宽」，我按你的明确指示偏离了该条，请确认。
2. **"安全区钳制"已按你的意见退化为「只防拖出屏幕」**。目前用户可以把控制条拖到琴键上，
   那样注入的音会打到浮层上造成漏音。若你希望我加回限制，请告知。
3. **M3 / M4 是本次痛点（开头丢音）的最终判据**，只能你手动确认。建议：
   校准 → 进光遇乐器界面 → 点浮层 ▶ → 看第一个音是否准确、开头是否丢音。
4. 附录 A 的范围（落盘曲库 `SongStore`、游戏内切歌、主界面曲库列表）本次**未做**，等你批准。
