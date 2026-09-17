package com.skyautoplayer.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.skyautoplayer.BuildConfig
import com.skyautoplayer.R
import com.skyautoplayer.accessibility.PlayerAccessibilityService
import com.skyautoplayer.application.PlaybackRuntime
import com.skyautoplayer.application.SongQueue
import com.skyautoplayer.domain.playback.PlaybackState
import com.skyautoplayer.importer.ImportResult
import com.skyautoplayer.importer.SkyJsonImporter
import com.skyautoplayer.importer.SkyTextImporter
import com.skyautoplayer.overlay.CalibrationOverlayService
import com.skyautoplayer.overlay.OverlayController
import com.skyautoplayer.overlay.PlaybackOverlayService
import com.skyautoplayer.storage.BundledSheetSeeder
import com.skyautoplayer.storage.BundledTombstones
import com.skyautoplayer.storage.SharedPreferencesCalibrationStore
import com.skyautoplayer.storage.SongEntry
import com.skyautoplayer.storage.SongRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * W12: the main screen is now a four-tab shell.
 *
 * 演奏 / 曲库 / 校准 / 设置 live in a content FrameLayout and are switched by
 * `visibility` only - no Fragment, no ViewPager, no new dependency. Whatever the
 * active tab is, 「开始演奏模式」 stays pinned to the dock, because it is the
 * one action the user needs on the way into the game.
 *
 * The refactor was deliberately split into "move the view-building code" and
 * "wire the dock": every business branch below (import / search / sort /
 * multi-select delete / start performing / bundled reseed + tombstones) is the
 * pre-W12 code, unchanged.
 */
class MainActivity : ComponentActivity() {

    // ── perform page ─────────────────────────────────────────────────────
    private lateinit var status: TextView
    private lateinit var accessibilityStatus: TextView
    private lateinit var performHint: TextView

    // ── library page ─────────────────────────────────────────────────────
    private lateinit var libraryStatus: TextView
    private lateinit var search: EditText
    private lateinit var listView: ListView
    private lateinit var adapter: SongAdapter
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionLabel: TextView

    // ── calibration page ─────────────────────────────────────────────────
    private lateinit var calibrationStatus: TextView

    // ── settings page ────────────────────────────────────────────────────
    private lateinit var settingsStatus: TextView
    private lateinit var bundledStatus: TextView

    // ── shell ────────────────────────────────────────────────────────────
    private lateinit var pages: List<View>
    private lateinit var dockTabs: List<TextView>
    private var currentPage = 0

    private var allSongs: List<SongEntry> = emptyList()
    private var visibleSongs: List<SongEntry> = emptyList()
    private var sortMode = SortMode.NAME
    private var query = ""

    // W9.3 selection mode
    private val selectedIds = linkedSetOf<String>()
    private var selectionMode = false

    /** One-shot note shown under the library count after a batch action. */
    private var lastActionMessage: String? = null

    private var calibrationPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pre-warm the playback FGS while we are still in the foreground; doing
        // this after switching to Sky would trip Android 14's FGS restrictions.
        PlaybackRuntime.startForegroundService(this)

        status = TextView(this).apply {
            textSize = 17f
            setTextColor(color(R.color.text_primary))
            text = "Sky Auto Player\n未载入曲谱"
        }
        accessibilityStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
        }
        performHint = TextView(this).apply {
            textSize = 12f
            setTextColor(color(R.color.text_secondary))
            text = "开启演奏模式后，控制条会浮在光遇之上：校准、选曲、播放都不用再切屏。"
        }
        libraryStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(color(R.color.text_primary))
            text = "曲库读取中…"
        }
        settingsStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
            text = "版本读取中…"
        }
        bundledStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
            text = "内置曲库：读取中…"
        }
        calibrationStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
            text = "校准信息读取中…"
        }

        search = EditText(this).apply {
            hint = "搜索曲名"
            textSize = 14f
            setTextColor(color(R.color.text_primary))
            setHintTextColor(color(R.color.text_secondary))
            background = drawable(R.drawable.field_bg)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    applyFilter()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
        }

        adapter = SongAdapter(this)
        listView = ListView(this).apply {
            adapter = this@MainActivity.adapter
            divider = null
            dividerHeight = dp(6)
            setPadding(0, dp(4), 0, dp(4))
            clipToPadding = false
            setOnItemClickListener { _, _, position, _ ->
                val entry = visibleSongs.getOrNull(position) ?: return@setOnItemClickListener
                if (selectionMode) {
                    toggleSelection(entry)
                    return@setOnItemClickListener
                }
                // Import != load: selecting only stages the song, no autoplay.
                val idx = allSongs.indexOfFirst { it.id == entry.id }
                if (idx >= 0 && SongQueue.loadAndPlay(idx, autoPlay = false)) {
                    renderLibraryStatus()
                    Toast.makeText(this@MainActivity, "已载入：${entry.title}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "载入失败：${entry.title}", Toast.LENGTH_SHORT).show()
                }
            }
            setOnItemLongClickListener { _, _, position, _ ->
                val entry = visibleSongs.getOrNull(position) ?: return@setOnItemLongClickListener false
                // W9.3: long press enters multi-select; a second long press exits.
                if (selectionMode) exitSelectionMode() else enterSelectionMode(entry)
                true
            }
        }

        val importButton = styledButton("导入曲谱（可多选）") { openDocument() }
        val sortButton = styledButton("排序：名称") {
            sortMode = sortMode.next()
            (it as Button).text = "排序：${sortMode.label}"
            applyFilter()
        }
        val calibrationButton = styledButton("在游戏内校准 15 个琴键", primary = true) {
            openCalibrationOverlay()
        }
        val accessibilityButton = styledButton("开启无障碍服务") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        // W22: the parenthetical was ambiguous - the button only *starts* the
        // overlay, the user still has to switch to Sky themselves.
        val performModeButton = styledButton("开始演奏模式", primary = true) {
            startForegroundService(Intent(this@MainActivity, PlaybackOverlayService::class.java))
            status.text = "演奏控制条已开启 · 现在切到光遇即可"
            refreshAccessibilityStatus()
        }
        val reseedButton = styledButton("重新导入内置曲库") { runSeeder(force = true) }
        // W9.2: clears the tombstones first, so songs deleted on purpose come
        // back. Distinct from 「重新导入内置曲库」 which respects the tombstones.
        val restoreButton = styledButton("恢复内置曲库（找回已删）") { restoreBundledLibrary() }
        val resetCalibrationButton = styledButton("重置校准") { confirmResetCalibration() }

        // W9.3 selection action bar, hidden until a long press.
        selectionLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(color(R.color.text_primary))
            text = "已选 0 项"
        }
        selectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(selectionLabel, LinearLayout.LayoutParams(0, -2, 1f))
            addView(styledButton("全选") { selectAllVisible() })
            addView(styledButton("取消") { exitSelectionMode() })
            addView(styledButton("删除所选") { confirmDeleteSelected() })
        }
        val playbackControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(styledButton("播放") {
                PlaybackRuntime.startForegroundService(this@MainActivity)
                PlaybackRuntime.playback.play()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(styledButton("暂停") { PlaybackRuntime.playback.pause() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(styledButton("停止") { PlaybackRuntime.playback.stop() }, LinearLayout.LayoutParams(0, -2, 1f))
        }

        // ── pages (W12.1: pure view-code move, no business change) ───────
        // W18: the gap between cards is owned by the page (addCard), not by
        // card() itself, so a page can override it.
        val performPage = columnPage().apply {
            addCard(card(sectionTitle("演奏状态"), status))
            addCard(card(sectionTitle("演奏控制条"), performHint, playbackControls))
            addCard(card(sectionTitle("无障碍"), accessibilityStatus, accessibilityButton), last = true)
        }
        val libraryPage = columnPage().apply {
            addCard(card(libraryStatus))
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(importButton, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(sortButton, LinearLayout.LayoutParams(0, -2, 1f))
                }
            )
            addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(selectionBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(listView, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val calibrationPage = scrollPage {
            addCard(card(sectionTitle("游戏内校准"), calibrationStatus, calibrationButton))
            addCard(card(sectionTitle("重置"), resetCalibrationButton))
            addCard(
                card(
                    sectionTitle("怎么做"),
                    bodyText(
                        "1. 先点「开始演奏模式」，再切到光遇（横屏）。\n" +
                            "2. 在控制条上点 ◎ 进入校准，把 15 个锚点拖到琴键上。\n" +
                            "3. 横屏保存才会生效；竖屏保存会被拒绝。"
                    )
                ),
                last = true
            )
        }
        val settingsPage = scrollPage {
            addCard(card(sectionTitle("版本"), settingsStatus))
            addCard(card(sectionTitle("内置曲库"), bundledStatus, reseedButton, restoreButton))
            addCard(
                card(
                    sectionTitle("关于"),
                    bodyText("Sky Auto Player 通过无障碍服务派发手势来弹奏 Sky Studio 曲谱。\n曲谱与校准数据全部保存在本机。")
                ),
                last = true
            )
        }
        pages = listOf(performPage, libraryPage, calibrationPage, settingsPage)

        dockTabs = listOf("演奏", "曲库", "校准", "设置").mapIndexed { index, label ->
            TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                background = drawable(R.drawable.dock_tab_bg)
                isClickable = true
                setOnClickListener { showPage(index) }
            }
        }

        // W15: targetSdk 35 forces edge-to-edge on Android 15+, so the content
        // window starts at y=0 and the first card was drawn *under* the status
        // bar (and the dock under the gesture bar). `android:statusBarColor` in
        // the theme is ignored in that mode, so the inset has to be consumed
        // here. Applied once to the root container, not per page, and
        // deliberately NOT applied inside the two overlay services - those have
        // their own window positioning (`defaultY()` reads window metrics).
        val rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg))
            addView(
                FrameLayout(this@MainActivity).apply {
                    pages.forEach { addView(it, FrameLayout.LayoutParams(-1, -1)) }
                },
                LinearLayout.LayoutParams(-1, 0, 1f)
            )
            addView(buildDock(performModeButton))
        }
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(rootContainer)
        showPage(0)

        lifecycleScope.launch {
            PlaybackRuntime.engine.state.collect { status.text = it.asDisplayText() }
        }

        // W4.3: bundled library import runs off the main thread so startup is
        // never blocked by 579 files.
        runSeeder(force = false)
    }

    override fun onResume() {
        super.onResume()
        // W24: the single writer of OverlayController.ownAppForeground.
        //
        // This activity's own lifecycle is the only reliable "our app is in
        // front" signal. Judging it from accessibility window events does not
        // work: our overlay window belongs to this same package, so hiding the
        // overlay produced another own-package window-state change, which made
        // the flag oscillate and the overlay flicker forever.
        //
        // Unconditional on purpose - an accessibility guard here would make W21
        // silently stop working whenever the service is off.
        OverlayController.setOwnAppForeground(true)
        refreshAccessibilityStatus()
        if (calibrationPending && Settings.canDrawOverlays(this)) openCalibrationOverlay()
        reloadLibrary()
        refreshCalibrationStatus()
        refreshSettingsStatus()
    }

    override fun onPause() {
        // Leaving us (to Sky, the launcher, the file picker, ...) means the
        // overlay must be back on screen. No event stream is involved any more,
        // so this is the only place that clears the flag.
        OverlayController.setOwnAppForeground(false)
        super.onPause()
    }

    // ── W12 shell ────────────────────────────────────────────────────────

    private fun buildDock(performModeButton: Button): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = drawable(R.drawable.dock_bg)
            setPadding(dp(10), dp(10), dp(10), dp(12))
            // 「开始演奏模式」 is NOT inside a tab - it is the dock's main action.
            addView(performModeButton, LinearLayout.LayoutParams(-1, -2))
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    dockTabs.forEach { tab ->
                        addView(tab, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
                    }
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            )
        }

    private fun showPage(index: Int) {
        currentPage = index
        pages.forEachIndexed { i, page -> page.visibility = if (i == index) View.VISIBLE else View.GONE }
        dockTabs.forEachIndexed { i, tab ->
            tab.isSelected = i == index
            tab.setTextColor(color(if (i == index) R.color.accent else R.color.text_secondary))
        }
        if (index == 2) refreshCalibrationStatus()
        if (index == 3) refreshSettingsStatus()
    }

    private fun refreshSettingsStatus() {
        val prefs = getSharedPreferences("bundled_sheets", Context.MODE_PRIVATE)
        val bundledVersion = prefs.getInt("bundled_sheets_version", 0)
        settingsStatus.text = buildString {
            append("版本 ${BuildConfig.VERSION_NAME}（versionCode ${BuildConfig.VERSION_CODE}）\n")
            append("包名 $packageName\n")
            append("曲库 ${allSongs.size} 首")
        }
        bundledStatus.text = buildString {
            append("内置曲库版本标记：$bundledVersion")
            append("（代码版本 ${BundledSheetSeeder.BUNDLED_SHEETS_VERSION}）\n")
            append("内置曲目 ${allSongs.count { it.isBundled }} 首，用户导入 ${allSongs.count { !it.isBundled }} 首")
        }
    }

    private fun refreshCalibrationStatus() {
        val profile = SharedPreferencesCalibrationStore(this).load("display-${displayId()}")
        calibrationStatus.text = if (profile == null) {
            "尚未校准（display-${displayId()}）\n未校准时，琴键点击位置无法保证。"
        } else {
            val orientation = when (profile.orientation) {
                0 -> "竖屏 0°"
                1 -> "横屏 90°"
                2 -> "竖屏 180°"
                3 -> "横屏 270°"
                else -> "未知（${profile.orientation}）"
            }
            buildString {
                append("已校准（display-${displayId()}）\n")
                append("朝向：$orientation\n")
                append("更新时间：${formatTimestamp(profile.updatedAt)}\n")
                append("锚点数：${profile.normalizedKeyPoints.size}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun displayId(): Int =
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.displayId

    private fun formatTimestamp(millis: Long): String =
        if (millis <= 0L) "—"
        else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

    private fun confirmResetCalibration() {
        AlertDialog.Builder(this)
            .setTitle("重置校准")
            .setMessage("删除 display-${displayId()} 的校准数据？之后需要在光遇（横屏）内重新校准。")
            .setPositiveButton("重置") { _, _ ->
                SharedPreferencesCalibrationStore(this).clear("display-${displayId()}")
                refreshCalibrationStatus()
                Toast.makeText(this, "已重置校准", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── W12 view helpers ─────────────────────────────────────────────────

    private fun color(id: Int): Int = getColor(id)

    private fun drawable(id: Int) = getDrawable(id)!!

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun styledButton(text: String, primary: Boolean = false, onClick: (View) -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 14f
            isAllCaps = false
            minimumWidth = 0
            minimumHeight = 0
            stateListAnimator = null
            setTextColor(color(if (primary) R.color.on_accent else R.color.text_primary))
            background = drawable(if (primary) R.drawable.btn_primary else R.drawable.btn_secondary)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setOnClickListener { onClick(it) }
        }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(color(R.color.accent))
        letterSpacing = 0.08f
    }

    private fun bodyText(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(color(R.color.text_secondary))
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    /** Rounded panel that groups related controls. */
    private fun card(vararg children: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = drawable(R.drawable.bg_card)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        children.forEachIndexed { index, child ->
            addView(
                child,
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (index == 0) 0 else dp(10) }
            )
        }
    }

    /**
     * W18: the gap BETWEEN cards belongs to the page, not to [card].
     *
     * It used to be zero - `addView(card(...))` with no LayoutParams - so the
     * cards were flush against each other. `last = true` drops the trailing gap
     * so a scrollable page does not end with a dead strip.
     */
    private fun LinearLayout.addCard(view: View, gapDp: Int = CARD_GAP_DP, last: Boolean = false) {
        addView(
            view,
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = if (last) 0 else dp(gapDp) }
        )
    }

    private fun columnPage(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    /** A card column wrapped in a ScrollView - a ScrollView takes exactly one child. */
    private fun scrollPage(build: LinearLayout.() -> Unit): ScrollView {
        val column = columnPage().apply(build)
        return ScrollView(this).apply {
            isFillViewport = true
            addView(column, ViewGroup.LayoutParams(-1, -2))
        }
    }

    // ── library ──────────────────────────────────────────────────────────

    private fun runSeeder(force: Boolean) {
        lifecycleScope.launch {
            val seeder = BundledSheetSeeder(
                context = applicationContext,
                store = PlaybackRuntime.songs
            ) { done, total ->
                runOnUiThread {
                    libraryStatus.text = "内置曲库导入中 $done/$total …"
                    bundledStatus.text = "内置曲库导入中 $done/$total …"
                }
            }
            val result = withContext(Dispatchers.IO) { seeder.seedIfNeeded(force) }
            val message = when {
                result.alreadyDone -> "内置曲库：已就绪（无需重复导入）"
                result.total == 0 -> if (force) "内置曲库：重新导入失败（见日志）" else "内置曲库：无内容"
                else -> "内置曲库：已导入 ${result.imported} 首，跳过 ${result.skipped} 首"
            }
            // Keep the summary visible: reloadLibrary() re-renders the count line,
            // and lastActionMessage is what survives as its suffix.
            lastActionMessage = message
            bundledStatus.text = message
            reloadLibrary()
        }
    }

    private fun reloadLibrary() {
        lifecycleScope.launch {
            val songs = withContext(Dispatchers.IO) { PlaybackRuntime.songs.list() }
            allSongs = songs
            SongQueue.setSongs(songs)
            applyFilter()
            refreshSettingsStatus()
        }
    }

    private fun applyFilter() {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) allSongs else allSongs.filter {
            it.title.lowercase().contains(q) || it.sourceName.lowercase().contains(q)
        }
        visibleSongs = when (sortMode) {
            SortMode.NAME -> filtered.sortedBy { it.title.lowercase() }
            SortMode.DURATION -> filtered.sortedByDescending { it.durationUs }
            SortMode.IMPORTED -> filtered.sortedByDescending { it.importedAt }
        }
        adapter.submit(visibleSongs, selectionMode, selectedIds, SongQueue.current()?.id)
        renderLibraryStatus()
    }

    private fun renderLibraryStatus() {
        val current = SongQueue.current()
        val suffix = if (current != null) "　当前：${current.title}" else ""
        val action = lastActionMessage?.let { "　$it" }.orEmpty()
        libraryStatus.text = "曲库 ${allSongs.size} 首（显示 ${visibleSongs.size}）$suffix$action"
    }

    // ── W9.3 / W9.4 selection + batch delete ─────────────────────────────

    private fun enterSelectionMode(entry: SongEntry) {
        selectionMode = true
        selectedIds.clear()
        selectedIds += entry.id
        selectionBar.visibility = View.VISIBLE
        renderSelection()
        applyFilter()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedIds.clear()
        selectionBar.visibility = View.GONE
        applyFilter()
    }

    private fun toggleSelection(entry: SongEntry) {
        if (!selectedIds.add(entry.id)) selectedIds.remove(entry.id)
        renderSelection()
        applyFilter()
    }

    private fun selectAllVisible() {
        selectedIds.clear()
        selectedIds += visibleSongs.map { it.id }
        renderSelection()
        applyFilter()
    }

    private fun renderSelection() {
        selectionLabel.text = "已选 ${selectedIds.size} 项"
    }

    private fun confirmDeleteSelected() {
        if (selectedIds.isEmpty()) {
            Toast.makeText(this, "没有选中任何曲目", Toast.LENGTH_SHORT).show()
            return
        }
        val chosen = allSongs.filter { it.id in selectedIds }
        val bundledCount = chosen.count { it.isBundled }
        val message = buildString {
            append("从曲库移除 ${chosen.size} 首曲目？")
            if (bundledCount > 0) {
                append("\n\n其中内置曲目 $bundledCount 首，之后可用「恢复内置曲库」找回。")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("删除所选")
            .setMessage(message)
            .setPositiveButton("删除") { _, _ -> performDelete(chosen) }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * W9.4: one index rewrite for the whole batch, on the IO dispatcher, with
     * the queue refreshed so the overlay never shows stale rows.
     */
    private fun performDelete(chosen: List<SongEntry>) {
        val ids = chosen.map { it.id }.toSet()
        lifecycleScope.launch {
            val currentId = SongQueue.current()?.id
            val bundledNames = chosen.filter { it.isBundled }.map { it.sourceName }
            withContext(Dispatchers.IO) {
                // W9.2: tombstone bundled rows first, so a later reseed cannot
                // resurrect them.
                if (bundledNames.isNotEmpty()) {
                    BundledTombstones(applicationContext).markAll(bundledNames)
                }
                PlaybackRuntime.songs.deleteMany(ids)
            }
            // If the playing song was deleted, stop before the queue forgets it.
            if (currentId != null && currentId in ids) {
                PlaybackRuntime.playback.stop()
            }
            SongQueue.removeIds(ids)
            lastActionMessage = "已删除 ${ids.size} 首"
            exitSelectionMode()
            reloadLibrary()
        }
    }

    /** W9.2: clear tombstones, then force a reseed so deleted songs return. */
    private fun restoreBundledLibrary() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                BundledTombstones(applicationContext).clearAll()
            }
            lastActionMessage = "已清除删除记录，正在找回内置曲库…"
            renderLibraryStatus()
            runSeeder(force = true)
        }
    }

    // ── import ───────────────────────────────────────────────────────────

    private fun openDocument() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, REQUEST_IMPORT)
    }

    @Deprecated("Use Activity Result APIs when the UI module is expanded")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK || data == null) return

        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
        }
        if (uris.isEmpty()) data.data?.let { uris += it }
        if (uris.isEmpty()) return

        lifecycleScope.launch {
            val repository = SongRepository(
                contentResolver,
                listOf(SkyJsonImporter(), SkyTextImporter())
            )
            var imported = 0
            var failed = 0
            var firstError: String? = null

            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    val name = displayName(uri)
                    when (val result = repository.import(uri, name)) {
                        is ImportResult.Success -> {
                            // Import != load: just persist it into the library.
                            runCatching { PlaybackRuntime.songs.save(result.timeline, name) }
                                .onSuccess { imported++ }
                                .onFailure { failed++; if (firstError == null) firstError = it.message }
                        }
                        is ImportResult.Failure -> {
                            failed++
                            if (firstError == null) firstError = result.message
                        }
                    }
                }
            }
            val message = buildString {
                append("已导入 $imported 首")
                if (failed > 0) {
                    append("，失败 $failed 首")
                    firstError?.let { append("（$it）") }
                }
            }
            libraryStatus.text = message
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            reloadLibrary()
        }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment
            ?: "selected-song"

    // ── overlay / accessibility ──────────────────────────────────────────

    private fun openCalibrationOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            calibrationPending = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        calibrationPending = false
        startForegroundService(Intent(this, CalibrationOverlayService::class.java))
    }

    private fun refreshAccessibilityStatus() {
        accessibilityStatus.text = if (PlayerAccessibilityService.instance != null) {
            "无障碍服务：已连接"
        } else {
            "无障碍服务：未连接（未开启则不会点击琴键）"
        }
    }

    // ── misc ─────────────────────────────────────────────────────────────

    private fun PlaybackState.asDisplayText(): String = when (this) {
        PlaybackState.Idle -> "Sky Auto Player\n未载入曲谱"
        is PlaybackState.Ready -> "$title\n已就绪  ${formatDuration(positionUs)} / ${formatDuration(durationUs)}"
        is PlaybackState.Playing -> "$title\n播放中  ${formatDuration(positionUs)} / ${formatDuration(durationUs)}"
        is PlaybackState.Paused -> "$title\n已暂停  ${formatDuration(positionUs)} / ${formatDuration(durationUs)}"
        is PlaybackState.Error -> "播放错误：$message"
    }

    private fun formatDuration(us: Long): String {
        val total = (us / 1_000_000L).coerceAtLeast(0L)
        return "%d:%02d".format(total / 60, total % 60)
    }

    private enum class SortMode(val label: String) {
        NAME("名称"), DURATION("时长"), IMPORTED("导入时间");

        fun next(): SortMode = entries[(ordinal + 1) % entries.size]
    }

    /**
     * Two-line library row (W12.3 step 3): the title on top, `时长 · 音符 · 来源`
     * underneath, with the selection box and the "current song" arrow folded into
     * the title line so the existing multi-select behaviour is untouched.
     */
    private class SongAdapter(private val context: Context) : BaseAdapter() {

        private val rows = mutableListOf<SongEntry>()
        private var selectionMode = false
        private var selectedIds: Set<String> = emptySet()
        private var currentId: String? = null

        fun submit(list: List<SongEntry>, selectionMode: Boolean, selectedIds: Set<String>, currentId: String?) {
            rows.clear()
            rows += list
            this.selectionMode = selectionMode
            this.selectedIds = HashSet(selectedIds)
            this.currentId = currentId
            notifyDataSetChanged()
        }

        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_song, parent, false)
            val entry = rows[position]
            val title = view.findViewById<TextView>(R.id.row_title)
            val subtitle = view.findViewById<TextView>(R.id.row_subtitle)

            val box = if (!selectionMode) "" else if (entry.id in selectedIds) "[✓] " else "[   ] "
            val isCurrent = entry.id == currentId
            title.text = box + (if (isCurrent) "▶ " else "") + entry.title
            title.setTextColor(
                context.getColor(if (isCurrent) R.color.accent else R.color.text_primary)
            )
            subtitle.text = "${fmt(entry.durationUs)} · ${entry.eventCount} 音 · " +
                if (entry.isBundled) "内置" else "导入"
            return view
        }

        private fun fmt(us: Long): String {
            val total = (us / 1_000_000L).coerceAtLeast(0L)
            return "%d:%02d".format(total / 60, total % 60)
        }
    }

    companion object {
        private const val REQUEST_IMPORT = 42

        /** W18: gap between two cards on a page. */
        private const val CARD_GAP_DP = 12
    }
}
