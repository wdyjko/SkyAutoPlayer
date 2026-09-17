# 本轮工单 · round9：把设备现有曲库提升为内置曲库

> **本文件自包含，可直接执行**。稳定约定另见 `docs/agent-conventions.md`（可选读）。
> 除本文件外不要读其它文档（历史工单在 `docs/archive/`）。
> 目标工程：`C:\Users\Lenovo\Desktop\sky` ｜ 本轮版本：`versionCode 9 / 0.8.1` → **`10 / 0.9.0`**
> 同时把 `BundledSheetSeeder.BUNDLED_SHEETS_VERSION` 从 **1 递增到 2**（否则不会重跑导入）。
> 本文件行号是**快照**，开工前必须重新 `Select-String` 核对。

---

## 0. 硬约束摘要（必备，读完再动手）

| # | 约束 |
|---|---|
| 1 | 工程根 `C:\Users\Lenovo\Desktop\sky`，包名 `com.skyautoplayer`；测试机序列号 `10AE2Q0QA2004P9` |
| 2 | 构建/测试**必须** `--offline`：`C:\Users\Lenovo\Desktop\sky\gradlew.bat -p C:\Users\Lenovo\Desktop\sky --offline ...` |
| 3 | **不许加任何新依赖**（可用仅 core-ktx / activity-ktx / lifecycle-runtime-ktx / coroutines-android） |
| 4 | **不重写大文件**（`PlaybackOverlayService.kt` / `MainActivity.kt` 只做定点修改）；本轮 app 侧改动**集中在 `storage/BundledSheetSeeder.kt` 与 `storage/SongStore.kt`** |
| 5 | 不改 `PlaybackEngine` / 浮层 / UI；**不动通知栏图标** |
| 6 | 每条结论附**完整命令 + 原始输出**；**没有命令输出就没有结论** |
| 7 | 引用代码用**符号锚点**（`seedIfNeeded()`、`alreadyPresent`、`SongStore.updateOrigin`），行号只作快照 |
| 8 | 需要手指操作 / 听音 / 看观感的项**只能人工验证**，写进「未完成 / 无法验证」 |
| 9 | **交付方式：直接在回答里给结果，不要新建任何文档 / 报告文件** |

---

## 0.1 本轮范围

| 编号 | 内容 |
|---|---|
| **W26** | 用**设备当前曲库**（707 首）重新生成内置资产 + manifest（新脚本，从设备拉取并转换） |
| **W27** | app 侧配套：`BUNDLED_SHEETS_VERSION → 2`、**按 (标题,时长) 去重提升**、时长阈值与脚本对齐到 0 |
| **W28** | **退役旧源与旧脚本**（用户已确认 `/sdcard/skyMusicAuto` 不再需要） |
| **W29** | 收尾：构建 + 安装 + 验收（**核心断言：曲库总数保持 707，不翻倍**） |

**明确不做**：不重新设计曲库 UI；不动 `origin` 的既有语义；不删用户的历史导入；不改 `BundledTombstones` 的机制。

---

## 0.2 实测基线（本次已从设备拉取核对，直接引用这些数字）

| 项 | 值 |
|---|---|
| 设备曲库总数 | **707 首**（`files/songs/index.json`，144,386 B） |
| `origin` 分布 | **BUNDLED 565 + USER 142** |
| 已删除的内置曲目 | **14 首**（`shared_prefs/deleted_bundled.xml` 里 14 条 `<string>`；579 − 565 = 14，对得上） |
| `bundled_sheets_version` | **1**（`shared_prefs/bundled_sheets.xml`） |
| 时长 | min **15.6s** / 中位 135.6s / max 644.5s；总 1813 分钟 |
| 事件总数 | **341,522** |
| < 55s 的曲目 | **3 首，全部是 USER**：`春娇与志明` 15.6s、`蜜雪冰城主题曲` 23.5s、`生日快乐` 37.9s |
| 重名标题 | **28 组**（含 `Flower Dance`、`偏爱`、`搁浅`…） |
| `files/songs` 体积 | **17,060 KB ≈ 16.7 MiB** |

**内部单曲格式**（`files/songs/<timelineId>.json`，µs 单位）：

```json
{"timelineId":"33b5…","title":"-Counting Stars- …","durationUs":250122000,
 "events":[{"atUs":0,"keys":[5,8],"holdUs":60000},
           {"atUs":284000,"keys":[2],"holdUs":60000}, …]}
```

**索引格式**（`files/songs/index.json`）：

```json
{"version":1,"entries":[{"id":"…","title":"…","durationUs":250122000,
 "eventCount":888,"importedAt":1789538594508,
 "sourceName":"0001_-Counting Stars- ….txt","origin":"BUNDLED"}, …]}
```

---

## 1. 锚点快照（**开工前重核**）

| 符号锚点 | 位置 | 用途 |
|---|---|---|
| `BundledSheetSeeder.seedIfNeeded()` | `storage/BundledSheetSeeder.kt` | W27：去重提升逻辑的落点 |
| `BundledSheetSeeder.alreadyPresent`（按 `sourceName` 去重） | 同上 | W27：**保留**，但不够，需加第二条判据 |
| `BundledSheetSeeder.MIN_BUNDLED_DURATION_US = 55_000_000L` | 同上（`companion object`） | W27：**改为 0** |
| `BundledSheetSeeder.BUNDLED_SHEETS_VERSION = 1` | 同上 | W27：**改为 2** |
| `SongStore.updateOrigin(...)` / `SongOrigin` | `storage/SongStore.kt` | W27：新增"提升"能力 |
| `BundledSheetsTest.expectedCount = 579` | `app/src/test/.../BundledSheetsTest.kt` | W27：改成新资产数 |
| 旧脚本与旧 manifest | `tools/build_bundled_sheets.py`（**本轮退役**）、`tools/bundled_sheets_manifest.json`（**保留**，它是命名迁移表） | W26 沿用旧 asset 名；W28 退役旧脚本 |
| 旧源目录 `/sdcard/skyMusicAuto` | — | **本轮正式退役**：不再作为任何来源；设备曲库是唯一权威 |

---

## W26　用设备当前曲库生成新的内置资产

### W26.1 从设备拉取曲库

推荐用 tar 打包后拉取（707 个文件逐个 `cat` 太慢）：

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
# 1) 让 app 把曲库打包到它自己的外部私有目录（该目录 app 有写权限）
& $adb shell run-as com.skyautoplayer sh -c `
  'tar -czf /sdcard/Android/data/com.skyautoplayer/files/songs.tgz -C files songs'
# 2) 拉回本地
& $adb pull /sdcard/Android/data/com.skyautoplayer/files/songs.tgz C:\temp\songs.tgz
# 3) 单独拉索引（也用于离线校验）
cmd /c "`"$adb`" exec-out run-as com.skyautoplayer cat files/songs/index.json > C:\temp\index.json"
```

- 拉完后**本地解压**，得到 `songs/<id>.json`（707 个）+ `index.json`。
- 若 `tar` 在设备上不可用 → 退回逐个 `adb exec-out run-as com.skyautoplayer cat files/songs/<id>.json`（按 index.json 里的 id 列表循环）。

### W26.2 新脚本 `tools/bundle_from_device_library.py`

一次性脚本，**不进 APK**，可重复执行（幂等）。输入输出：

```
输入  --index     <index.json>          设备曲库索引
      --songs     <songs 目录>          解压后的 <id>.json
      --old-manifest tools/bundled_sheets_manifest.json   （用于沿用旧 asset 名）
输出  --assets    app/src/main/assets/bundled_sheets
      --manifest  tools/bundled_sheets_manifest.json      （覆盖，新格式见下）
```

**算法（按此顺序）**：

1. 清空 assets 目标目录（保证幂等）。
2. 读 `index.json` 的 `entries`；逐个读 `<id>.json` 的内部 timeline。
3. **过滤规则：`events` 为空的跳过；其余全部保留 —— 本轮不做任何时长过滤**（用户要求"全部"）。
   - 预期：**707 首全部保留**（与 0.2 节的数字一致；若有出入，先报告再继续）。
4. **命名（关键，决定墓碑是否继续有效）**：
   - 先在**旧 manifest** 里按 `(title, durationUs)` 找匹配：
     - 命中 → **沿用旧的 asset 文件名**（例如 `0001_-Counting Stars- ….txt`）。
       这样 `alreadyPresent`（按 asset 名幂等）和 `deleted_bundled`（墓碑按 asset 名）**继续有效**。
     - 未命中（即那 142 首手动导入的，以及原标题/时长变过的）→ 分配**新名**：`<4 位序号>_<Windows 合法化标题>.txt`，
       序号从旧 manifest 的最大序号之后继续，并保证全局唯一。
   - Windows 合法化：去掉 `\ / : * ? " < > |` 与控制字符、去尾部点/空格、截断到 60 字符（沿用旧脚本的规则）。
5. **格式转换**：内部 timeline → **SkyStudio 兼容 JSON**（这样 app 侧**不需要新增解析器**，`SkyJsonImporter` 直接能吃）：

   ```json
   {"name":"<title>","author":"<source.author 或 \"\">",
    "songNotes":[{"time":<atUs/1000>,"key":"1Key<k>","duration":<max(1, holdUs/1000)>}, …]}
   ```

   - 同一条 event 的多个 key → 生成**多条** note（`time` 相同，`duration` 相同）。
   - `time` 用**四舍五入**；`duration` 至少 1。
6. **⚠️ ms 碰撞检测（不允许静默丢音）**：
   若两条**不同** `atUs` 四舍五入后 `time` 相同且 key 不同 → 导入时会被合并成一个和弦，**改变原曲**。
   处理：对后者 `time += 1` 递推避让，并把该条记入 manifest 的 `warnings[]`（含曲名、原始 atUs）。
7. 写出 **UTF-8 + BOM**。
8. 产出 manifest（覆盖旧文件）：

   ```json
   {"generatedFrom":"device library (707 songs)","includedCount":707,"warnings":[…],
    "included":[{"assetName":"0001_….txt","title":"…","durationUs":250122000,
                 "eventCount":888,"sourceId":"33b5…","renamedFromOldManifest":true}, …]}
   ```

9. **脚本自检（不符就退出码非 0）**：
   - `includedCount == 707`
   - 每个 asset 名唯一、且通过 Windows 合法化
   - **每个 asset 的 `(title, durationUs)` 都能在 `index.json` 里找到匹配**（这是 W27"不产生重复"的离线证明）
   - 打印汇总：总数 / 新分配名字的数量 / 沿用旧名的数量 / ms 碰撞数 / assets 总字节数

### W26.3 判据

- `app/src/main/assets/bundled_sheets/` 下 **707 个 `.txt`**；
- 新旧对照：沿用旧名的条数应约等于 565（**允许不同**，因为可能有同名不同时长的；以脚本自检为准）；
- 脚本重复执行两次，产出**逐字节一致**（幂等）。

---

## W27　app 侧配套改动

### W27.1 按 `(标题, 时长)` 去重提升（**核心，防止 142 首变重复**）

**现状**：`seedIfNeeded()` 只用 `alreadyPresent = store.list().map { it.sourceName }` 做幂等 —— 只认 asset 文件名。而用户手动导入的 142 首 `sourceName` 是原始文件名（如 `坏女孩.txt`），**匹配不上新的 asset 名** → 会再导入一份 → 曲库从 707 涨到 849。

**改法**：在 `seedIfNeeded()` 里，**解析完 asset 拿到 `timeline` 之后**，先按 `(title, durationUs)` 在现有库里查：

```kotlin
// 进入循环前建一次索引（本轮的数据量 707 首足够小）
val bySong = runCatching { store.list().associateBy { it.title to it.durationUs } }
    .getOrDefault(emptyMap())

var promoted = 0
...
is ImportResult.Success -> {
    val timeline = result.timeline
    val existing = bySong[timeline.title to timeline.durationUs]
    if (existing != null && existing.origin != SongOrigin.BUNDLED) {
        // 已在库中（手动导入的）：只把它"提升"为内置，不重复导入
        runCatching { store.promote(existing.id, name) }
            .onSuccess { promoted++ }
            .onFailure { skipped++ }
    } else if (existing != null) {
        skipped++                       // 已经是内置且同名同时长
    } else if (timeline.events.isEmpty() || timeline.durationUs < MIN_BUNDLED_DURATION_US) {
        skipped++
    } else {
        runCatching { store.save(timeline, name, SongOrigin.BUNDLED) }
            .onSuccess { imported++ }
            .onFailure { skipped++ }
    }
}
```

配套：`SongStore` 新增

```kotlin
/** 把一条已存在的曲目标记为内置，并把 sourceName 改成新的 asset 名（不重写单曲 JSON）。 */
fun promote(id: String, assetName: String): Boolean
```

`FileSongStore` 实现：改内存索引中该条的 `origin = BUNDLED`、`sourceName = assetName` → **复用现有 `writeIndex()`（temp + rename）**，不要逐条写盘，也**不要动 `<id>.json`**。

`Result` 增加 `promoted` 字段，并把日志改成（便于取证）：

```
Log.i(TAG, "内置曲库：提升 $promoted 首，导入 $imported 首，跳过 $skipped 首（共 ${names.size}）")
```

### W27.2 `BUNDLED_SHEETS_VERSION` 1 → **2**

- 不递增就不会重跑导入（`storedVersion >= BUNDLED_SHEETS_VERSION` 会直接返回 `alreadyDone`）。
- 递增后首次启动会自动执行 promote。

### W27.3 时长阈值与脚本对齐：`MIN_BUNDLED_DURATION_US` → **0**

- 本轮脚本**不做时长过滤**，app 侧也必须一致，否则那 3 首短曲（`生日快乐` 37.9s 等）在重新导入时会被静默跳过、`origin` 停在 USER。
- 把 `companion object` 里的常量改为 `0L`，并把上面那段"55 秒的理由"注释改成：
  「筛选由打包脚本负责（`tools/bundle_from_device_library.py`）；运行时不再过滤，只做 `events` 非空校验。」
- `BundledSheetsTest` 里依赖该常量的断言**用常量本身**（改完后恒真），不要硬编码 55_000_000。

### W27.4 更新单测

- `BundledSheetsTest.expectedCount`：**579 → 707**（以 `assets/bundled_sheets` 实际文件数为准，必须与脚本自检一致）。
- **新增**一例 `promoteDoesNotDuplicate`：构造一个内存 `SongStore`（含 1 条 `origin=USER`、title/duration 与某 asset 一致的条目）→ 跑 `seedIfNeeded(force = true)` → 断言**库总数不变**、且该条 `origin == BUNDLED`、`sourceName == asset 名`。
- 保留原有断言：文件名规则、每个 asset 可导入、事件满足 timeline 不变量。

### W27.5 判据（**这是本轮最重要的验收**）

| 场景 | 期望 |
|---|---|
| 装 0.9.0 后首次启动 / 点「重新导入内置曲库」 | 日志出现 `提升 707 首，导入 0 首，跳过 0 首（共 707）`（数字允许小幅差异，但**导入必须为 0**） |
| 曲库总数 | **仍然是 707**（不是 849、不是其它） |
| `origin` 分布 | **全部 707 都是 BUNDLED**（这是"全部变成内置曲库"的定义） |
| 那 14 首被删的 | **仍然不在库里**（新资产里根本没有它们） |
| 那 3 首 <55s 的 | 在库里，且 `origin == BUNDLED` |
| 再点一次「重新导入内置曲库」 | 总数仍 707、无新增（幂等） |

---

## W28　退役旧源与旧脚本

用户已确认：**旧源 `/sdcard/skyMusicAuto` 不再需要**，设备当前曲库就是唯一权威来源。本工作单把项目侧的旧管线收掉。

1. **保留** `tools/bundled_sheets_manifest.json` —— ⚠️ 它**不是"源"**，而是**命名迁移表**：W26.2 第 4 步靠它按 `(title, durationUs)` 反查、沿用旧 asset 文件名，从而保住 `alreadyPresent` 幂等与 `deleted_bundled` 墓碑。**不要删、不要改名。**
2. **旧脚本改名 + 标注退役**：`tools/build_bundled_sheets.py` → `tools/legacy_build_bundled_sheets_skymusicaudit.py`，并在文件头加三行注释：

   ```python
   # 已退役（2026-09）：这是旧源 /sdcard/skyMusicAuto 的打包脚本，仅作历史参考。
   # 现行管线见 tools/bundle_from_device_library.py（以设备曲库为唯一来源）。
   # 保留原因：它记录了最初 702 份的筛选口径，是 579 那批资产的可追溯依据。
   ```

   **不要直接删除**（可追溯性比整洁更重要）。
3. **更新工程内过时注释**：`storage/BundledSheetSeeder.kt` 的类注释写着
   "The 579 assets are UTF-8+BOM SkyStudio exports produced by `tools/build_bundled_sheets.py`"
   → 改成新的数量（707）与新的脚本名（`tools/bundle_from_device_library.py`）。
4. **不要动设备上的 `/sdcard/skyMusicAuto` 目录** —— 那是用户的个人数据；"退役"只发生在项目侧。
5. **可选清理（在会话工作区，不在工程里）**：`tools/_skymusic_audit/`（702 个源文件副本，26.5 MB）已无用可删；`tools/_library_dump/` 是本轮中间产物，验收完成前先留着。

**判据**：

| # | 检查 | 期望 |
|---|---|---|
| 1 | `Select-String "skyMusicAuto" app/` | **零命中**（工程内不得再引用旧源） |
| 2 | `Select-String "build_bundled_sheets" app/` | **零命中**（工程内不得再引用旧脚本） |
| 3 | `ls tools/` | 出现 `bundle_from_device_library.py` 与 `legacy_build_bundled_sheets_skymusicaudit.py`，且**没有** `build_bundled_sheets.py` |
| 4 | `tools/bundled_sheets_manifest.json` | **仍存在**（本轮会被新脚本覆盖为新格式，但文件不能被删） |

---

## W29　收尾

```powershell
C:\Users\Lenovo\Desktop\sky\gradlew.bat -p C:\Users\Lenovo\Desktop\sky --offline testDebugUnitTest
C:\Users\Lenovo\Desktop\sky\gradlew.bat -p C:\Users\Lenovo\Desktop\sky --offline assembleDebug
adb install -r C:\Users\Lenovo\Desktop\sky\app\build\outputs\apk\debug\app-debug.apk
```

**直接在回答里给出结果**（不要新建任何文件）：

1. 完成情况（W26 / W27 / W28 / W29）
2. 变更清单（每个文件一行：路径 + 增删行数 + 一句话）
3. 数据：assets 文件数、总字节数、沿用旧名的条数、ms 碰撞数、APK 大小与 SHA256
4. 验收证据（§2.1 逐条 + 关键 `logcat` 片段）
5. 未完成 / 无法验证
6. 需要用户决策（如有）

---

## 2. 验收

### 2.1 Agent 可自动执行（逐条附命令与原始输出）

| # | 检查 | 期望 |
|---|---|---|
| 1 | `gradlew --offline testDebugUnitTest` | 全绿，用例数 ≥ **86**（85 + 新增 promote 用例） |
| 2 | 构建 + 安装 + 冷启动 `logcat -s AutoPlay` | 成功、无 `FATAL EXCEPTION` |
| 3 | `ls app/src/main/assets/bundled_sheets \| wc -l` | **707** |
| 4 | 重跑 `tools/bundle_from_device_library.py` | 产出逐字节一致（幂等） |
| 5 | 安装后 `adb shell run-as com.skyautoplayer cat files/songs/index.json` → 数 `entries` | **707** |
| 6 | 同上，数 `"origin":"BUNDLED"` 与 `"origin":"USER"` | BUNDLED = **707**，USER = **0** |
| 7 | `adb shell run-as com.skyautoplayer cat shared_prefs/bundled_sheets.xml` | `value="2"` |
| 8 | 再点一次「重新导入内置曲库」后再查 5/6 | 仍是 707 / 707 / 0（幂等） |
| 9 | `app/build.gradle.kts` 依赖块 | 无变化 |
| 10 | APK 大小 | 记录（预计从 `5,596,739 B` 增长到 **约 6 MB** 量级，assets 压缩后约占 2.5–3 MB） |
| 11 | `Select-String "skyMusicAuto" app/` 与 `Select-String "build_bundled_sheets" app/` | **均零命中**（W28 退役判据） |
| 12 | `ls tools/` | 有 `bundle_from_device_library.py` + `legacy_build_bundled_sheets_skymusicaudit.py`；**无** `build_bundled_sheets.py` |

### 2.2 必须人工（写进「未完成 / 无法验证」）

| # | 动作 | 确认什么 |
|---|---|---|
| M1 | 打开「曲库」页看列表 | 707 首都在、**没有重复条目**（重点看那 142 首手动导入的歌是否只出现一次） |
| M2 | 在曲库里删除任意一首内置曲目 → 点「重新导入内置曲库」 | 删掉的**不复活**（墓碑仍然有效） |
| M3 | 确认那 14 首之前删掉的仍不在列表里 | 删除状态被保留 |
| M4 | 随便挑 3 首（含一首手动导入的）播放 | 音符/节奏与之前一致（转换没有改变内容） |

---

## 3. 本轮停止条件

1. 脚本自检显示**保留数 ≠ 707** → 先报告（说明拉取的曲库与 0.2 节基线不一致）。
2. 存在 asset 的 `(title, durationUs)` **无法**在 `index.json` 中匹配 → 先报告（会导致重复导入，不能带着这个前提往下做）。
3. ms 碰撞数 > 0 且无法通过 +1ms 避让解决 → 先报告。
4. 需要在 app 侧**新增一个内部格式解析器**（本轮要求走"转成 SkyStudio JSON、复用现有导入器"这条路，零解析器改动）。
5. 需要改动 `BundledTombstones` 的机制，或需要清空用户的曲库/墓碑来达成目标。
6. 需要重命名**旧 manifest 里已存在的 asset**（会让 `alreadyPresent` 与墓碑失效）。
7. 需要**删除** `tools/bundled_sheets_manifest.json`，或需要改动/删除设备上的 `/sdcard/skyMusicAuto` 目录 → 先报告（前者是命名迁移表，后者是用户个人数据）。
