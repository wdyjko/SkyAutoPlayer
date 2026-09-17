# 执行回报：曲库 / 内置歌单 / 游戏内校准（工单 v3.0）

> 目标工程：`C:\Users\Lenovo\Desktop\sky` ｜ 包名 `com.skyautoplayer`
> 版本：0.2.0 → **0.3.0**（versionCode 2 → 3）

---

## 完成情况

- **W1**：**已完成**。校准入口移到浮层、两浮层互斥、旋转重投影、竖屏拒存、`naturalOrientation` 修正、测试点击自吞修复。
- **W2**：**已完成**。新增 `FileSongStore` + 9 例单测。
- **W3**：**已完成**。多选导入 + 主界面曲库列表（搜索/排序/点选/删除）。
- **W4**：**已完成（核心）**。打包脚本 + **579 首** assets + 首次导入 + 4 例单测。
- **W5**：**已完成**。浮层曲目面板（每页 8 条 / 分页）+ ◀◀ / ▶▶。
- **W6**：**已完成**。构建、安装、验收；日志类证据见「未完成 / 无法验证」的环境限制说明。

---

## 变更清单（每个文件一行）

```
app/src/main/java/com/skyautoplayer/overlay/OverlayState.kt             +20  -0    重建：OverlayController 升级为两浮层共享开关（v2.0 曾按上轮工单授权删除）
app/src/main/java/com/skyautoplayer/overlay/CalibrationOverlayService.kt +89  -0    W1.2/1.3/1.4/1.6/1.7：旋转重投影、竖屏拒存、naturalOrientation、测试点击防自吞
app/src/main/java/com/skyautoplayer/overlay/PlaybackOverlayService.kt   +210 -0    W1.1/W5：校准按钮、曲目面板、分页、上下首、与校准互斥；日志提级
app/src/main/java/com/skyautoplayer/storage/SongStore.kt                +239 -0    新增：SongEntry / SongStore / FileSongStore（索引原子写、坏文件跳过）
app/src/main/java/com/skyautoplayer/storage/BundledSheetSeeder.kt       +108 -0    新增：内置曲库首次导入（版本幂等 + 55s 时长规则）
app/src/main/java/com/skyautoplayer/application/SongQueue.kt            +89  -0    新增：曲库选中态唯一真源（按 index/id，禁用 title 定位）
app/src/main/java/com/skyautoplayer/application/PlaybackRuntime.kt      +4   -0    组合根挂载 songs: FileSongStore
app/src/main/java/com/skyautoplayer/ui/MainActivity.kt                  +230 -0    W3/W4.3：多选导入、曲库列表、搜索排序删除、seeder 接入、重新导入按钮
app/build.gradle.kts                                                    +2   -2    versionCode 2→3，versionName 0.2.0→0.3.0
tools/build_bundled_sheets.py                                           +298 -0    新增：内置歌单打包脚本（幂等，自带解析）
tools/bundled_sheets_manifest.json                                      +…   -0    新增：579 条 included + 123 条 excluded 审计清单
app/src/main/assets/bundled_sheets/                                     +579 files  新增：579 首曲谱（UTF-8+BOM）
app/src/test/java/com/skyautoplayer/storage/FileSongStoreTest.kt        +174 -0    新增：9 例
app/src/test/java/com/skyautoplayer/storage/BundledSheetsTest.kt        +118 -0    新增：4 例（直读 src/main/assets，不复制进 test resources）
```

---

## 内置歌单数据

- **打包曲目数：579**（期望 579 ✓）
- **剔除：123** —— 按时长 **104** / 点名 **2** / 加密 **10** / 坏文件 **7**（期望 104 / 2 / 10 / 7 ✓，逐项吻合）
- **assets 总大小：12,786,760 B（12.19 MB，UTF-8）** ← 转换前 UTF-16 为 24.4 MB，正好省一半
- **APK 大小：2,606,680 B → 4,813,185 B（+2,206,505 B ≈ +2.10 MB）**
  （assets 在包内压缩后仅 2,053,662 B；好于工单预估的 3–4 MB，未触发 §0.5 第 7 条）
- **manifest 路径**：`C:\Users\Lenovo\Desktop\sky\tools\bundled_sheets_manifest.json`

**汇总口径核对**（与附录 A.2 逐项一致）：

| 项 | 附录 A 期望 | 实测 |
|---|---|---|
| included | 579 | **579** |
| 音符总数 | 419,598 | **419,598** |
| 保留集总时长 | 1314 分钟 | **1314.4 分钟** |
| 最短时长 | ≥55s | **55,172 ms** |
| 最长时长 | 534s | **534,324 ms** |

---

## 验证证据

**1. 单测**
```
gradlew.bat -p C:\Users\Lenovo\Desktop\sky --offline testDebugUnitTest
→ TOTAL=51 FAILED=0（基线 38 → 51，+13，无删除、无用例由绿转红）
   新增：storage.FileSongStoreTest 9 例、storage.BundledSheetsTest 4 例
```

**2. 构建**
```
gradlew.bat -p C:\Users\Lenovo\Desktop\sky --offline assembleDebug
→ BUILD SUCCESSFUL
```

**3. 安装**
```
adb install -r C:\Users\Lenovo\Desktop\SkyAutoPlayer-0.3.0.apk
→ Success
APK：C:\Users\Lenovo\Desktop\SkyAutoPlayer-0.3.0.apk
大小：4,813,185 B
SHA256：DF28A733EFE93CC35A1555F21A1787EE5EBD17069BEAF16CDEA6D914C580B8E4
```

**4. 首次导入（清空曲库后冷启动，轮询文件数计时）**
```
rm -rf files/songs ; rm -f shared_prefs/bundled_sheets.xml ; am start …
songs before launch: 0
FIRST-RUN IMPORT COMPLETE: 580 files in 8.1s
songs after: 580
shared_prefs/bundled_sheets.xml → <int name="bundled_sheets_version" value="1" />
```
8.1s < 10s，**未触发 §0.5 第 8 条**（余量不大，已如实记录）。

**5. 幂等性（第二次启动不重复导入）**
```
index.json BEFORE : -rw------- 110137 2026-09-15 21:36 files/songs/index.json
index.json AFTER  : -rw------- 110137 2026-09-15 21:36 files/songs/index.json
→ IDEMPOTENT: 文件大小与 mtime 完全一致，未被重写
```

**6. 曲库落盘条目数**
```
adb shell run-as com.skyautoplayer ls files/songs | wc -l
→ 580（579 首 + index.json），与 SongStore 条目数一致
```

**7. 焦点归属 / 浮层窗口**
```
Window #9 Window{com.skyautoplayer:c05c5fb u0 com.skyautoplayer type=2038 }:   ← TYPE_APPLICATION_OVERLAY
  Frames: parent=[120,0][2800,1260] frame=[120,9][1240,191]
mCurrentFocus=Window{702d527 u0 com.netease.sky.huawei/com.tgc.sky.netease.GameActivity_Netease}
```
浮层存在（type=2038），**焦点属光遇本体，不属 `com.skyautoplayer`** ✓

**8. 截图**
- `tools/_shots/20_main_library.png` —— 主界面「**曲库 579 首（显示 579）**」+ 列表（标题 · 时长 · 音符数）
- `tools/_shots/22_overlay_over_sky.png` —— 浮层 `≡ ◀◀ ▶ ■ ▶▶ 曲目 校准` 浮于光遇之上，**未遮挡 15 键**

**9. 校准保存的 orientation**
未取到 —— 需人工在横屏完成一次校准（见下）。

---

## 未完成 / 无法验证

| # | 项 | 状态 | 原因 |
|---|---|---|---|
| 1 | §5.1 第 4/5/9 条要求的 **logcat 证据** | ❌ **本机取不到** | 见下方「环境限制」——设备完全屏蔽本应用日志，已改用文件系统证据替代 |
| 2 | 首次导入的进度文案回显 | ❌ 未取到 | 同上；导入本身已完成（580 文件 + 版本标记） |
| 3 | 浮层按钮点击 → 引擎命令日志 | ❌ 未取到 | 同上。按钮渲染已在截图中确认，但"点击后引擎收到命令"无法用日志证明 |
| 4 | 横屏校准保存（`orientation` = 1/3） | ❌ 未验证 | 需人工在光遇横屏内点「校准」→ 拖 15 点 → 保存 |
| 5 | 竖屏拒存 Toast | ❌ 未验证 | 需人工在竖屏点「保存」 |
| 6 | 浮层选曲换曲实效（M5） | ❌ 未验证 | 需人工点「曲目」→ 翻页 → 选一首 |
| 7 | 真实演奏音准 / 开头丢音（M4） | ❌ 未验证 | 需人工听音核对 |

### 环境限制（重要）

执行到 W6 时**设备被更换**：原小米 `22041211AC`（Android 14）断开，现为 **vivo `V2309A`（Android 16 / SDK 36 / 1260×2800）**。
工单 v3.0 §0 已删除设备相关前置要求，故按新设备继续。

这台设备**完全屏蔽了本应用的日志**：
```
adb logcat -d -s AutoPlay                      → 空
adb logcat -d --pid=<app pid>                  → 空
流式 logcat 捕获 30s（含启动全过程）           → 仅系统日志，无一条本应用日志
```
对比：`Log.i` 的两条系统级日志在重装后首个瞬间曾短暂出现，随后彻底不可见 —— 判定为 ROM 侧对第三方应用日志的过滤。

**应对**：把工单要求的证据日志从 `Log.d` 提到 **`Log.i`**（否则在该设备上永远不可见），并改用**文件系统级证据**替代日志：
曲库文件数（580）、`index.json` 的 mtime 不变（幂等）、`bundled_sheets_version=1`。
这比日志更能说明"导入真的成功了"。

---

## 需要用户决策

1. **§0.5 第 6 条差异**：工单 §W1.1 与附录 C 假定 `overlay/OverlayState.kt` 存在，但它在上一轮（工单 v2.0 §W1 明确授权"复用或删除"）已被删除。本轮按 v3.0 要求**重建**为共享开关，语义与骨架一致。请确认。
2. **§W1 要点 9 / §W5.1 的"琴键在**下半屏**"假设在本机不成立**：实测光遇横屏键盘位于**中间带**（顶部约 18% 屏高处起）。因此：
   - 曲目面板未按"下半屏"规避，而是**打开面板即暂停播放**，使面板覆盖区域不会吞掉音符；
   - 面板高度取屏高 40%（按工单要求），位于屏幕上部。
   若你希望改为"面板固定不遮任何键"，需要牺牲每页条数。
3. **首次导入 8.1s**，接近 10s 阈值。若换低端机可能超时；届时可按附录 B 改成预转归一化 JSON。
4. **附录 B 范围未动**（引擎时序改造、起播门、权限向导、分享导入、校准网格增强）。

---

## 附：W1 各子项落点

| 子项 | 实现 |
|---|---|
| W1.1 互斥 | `OverlayController.calibrating`；控制条订阅后 `GONE`；校准服务 `onCreate/onDestroy` 置位 |
| W1.2 旋转重投影 | `CalibrationOverlayService.onConfigurationChanged` → 重建快照 → `reprojectAnchors()` + `relayoutControls()` |
| W1.3 竖屏拒存 | `saveAndClose()` 先判 `ROTATION_90/270`，否则 Toast 并 `return`（不保存、不关闭） |
| W1.4 naturalOrientation | 固定写 `Surface.ROTATION_0` |
| W1.5 profileId | **未改**，仍为 `display-<id>` |
| W1.6 测试点击 | 注入前把 15 个锚点设为 `FLAG_NOT_TOUCHABLE`，300ms 后恢复 |
| W1.7 交接 | 保留原顺序：先 `startForegroundService(PlaybackOverlayService)` 再 `stopSelf()` |
