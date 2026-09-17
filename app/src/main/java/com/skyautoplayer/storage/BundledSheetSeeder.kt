package com.skyautoplayer.storage

import android.content.Context
import android.util.Log
import com.skyautoplayer.importer.ImportResult
import com.skyautoplayer.importer.SkyJsonImporter

/**
 * Imports the bundled song library into [SongStore] on first run.
 *
 * The 707 assets are UTF-8+BOM SkyStudio exports produced by
 * `tools/bundle_from_device_library.py` (the device library is the single source
 * of truth since W26). Import is versioned so it happens once; `force = true`
 * re-runs it (used by the "重新导入内置曲库" button so a user can recover from a
 * wiped library).
 *
 * W27: a song the user already has - because they imported it by hand before it
 * became part of the bundle - is *promoted* (its index row becomes BUNDLED and
 * adopts the asset name) instead of being imported a second time. Without that,
 * the 142 hand-imported rows would double the library.
 *
 * Call from a background thread - it touches disk and the asset stream.
 */
class BundledSheetSeeder(
    private val store: SongStore,
    private val tombstones: BundledTombstones,
    private val assets: SheetAssets,
    private val marker: VersionMarker,
    private val onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
) {

    /** Production wiring: assets straight out of the APK, marker in SharedPreferences. */
    constructor(
        context: Context,
        store: SongStore,
        tombstones: BundledTombstones = BundledTombstones(context),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ) : this(
        store,
        tombstones,
        ContextSheetAssets(context),
        SharedPreferencesVersionMarker(context),
        onProgress
    )

    data class Result(
        val imported: Int,
        val skipped: Int,
        val total: Int,
        val alreadyDone: Boolean,
        /** W27: existing rows re-labelled as bundled instead of re-imported. */
        val promoted: Int = 0
    )

    /**
     * Screening belongs to the packaging script
     * (`tools/bundle_from_device_library.py`); the runtime no longer filters by
     * duration, it only rejects assets with no events. Keeping a length rule
     * here would silently skip short songs that the script deliberately kept and
     * leave their library row marked USER.
     */
    fun seedIfNeeded(force: Boolean = false): Result = runCatching {
        val storedVersion = marker.read()
        if (!force && storedVersion >= BUNDLED_SHEETS_VERSION) {
            // Upgrading from a build whose index had no `origin` field would
            // leave every bundled row marked USER, which silently disables the
            // tombstones. Backfill before reporting "already done".
            backfillOrigin()
            Log.i(TAG, "内置曲库：已是最新 (v$storedVersion)，跳过导入")
            return@runCatching Result(0, 0, 0, alreadyDone = true)
        }

        val names = assets.names().sorted()
        if (names.isEmpty()) {
            Log.w(TAG, "内置曲库：assets/$ASSET_DIR 为空，无可导入内容")
            return@runCatching Result(0, 0, 0, alreadyDone = false)
        }

        val importer = SkyJsonImporter()
        var imported = 0
        var skipped = 0
        var promoted = 0

        // The importer mints a fresh UUID per import, so re-importing an asset
        // already in the library would APPEND a duplicate rather than replace
        // it. Keying on the asset name makes the reseed idempotent: it now only
        // fills in what is genuinely missing (new release assets), which is
        // exactly what 「重新导入内置曲库」 should mean.
        val alreadyPresent = runCatching { store.list().map { it.sourceName }.toHashSet() }
            .getOrDefault(emptySet())

        // W27: the second dedup key. A row the user imported by hand has the
        // original file name as its sourceName, so it can never match an asset
        // name - without this lookup those rows would be imported again and the
        // library would grow from 707 to 849. The value is a *list* because the
        // library legitimately holds rows with identical (title, duration) pairs
        // (8 such groups today); every one of them must be promoted.
        val bySong = runCatching {
            store.list().groupBy { it.title to it.durationUs }
        }.getOrDefault(emptyMap())

        names.forEachIndexed { index, name ->
            // W9.2: check the tombstone BEFORE opening anything, so a deleted
            // bundled song can never come back on a version bump or a forced
            // reseed. 「恢复内置曲库」 clears the tombstones first.
            if (tombstones.contains(name)) {
                skipped++
                onProgress(index + 1, names.size)
                return@forEachIndexed
            }
            if (name in alreadyPresent) {
                skipped++
                onProgress(index + 1, names.size)
                return@forEachIndexed
            }

            val bytes = assets.bytes(name)

            if (bytes == null) {
                skipped++
            } else {
                when (val result = importer.import(name, bytes)) {
                    is ImportResult.Success -> {
                        val timeline = result.timeline
                        val existing = bySong[timeline.title to timeline.durationUs].orEmpty()
                        val promoteable = existing.filter { it.origin != SongOrigin.BUNDLED }
                        if (promoteable.isNotEmpty()) {
                            // Already in the library, just marked as a user
                            // import: re-label instead of importing again, so
                            // the row count never changes.
                            var ok = 0
                            promoteable.forEach { entry ->
                                runCatching { store.promote(entry.id, name) }
                                    .onSuccess { ok++ }
                            }
                            if (ok > 0) promoted += ok else skipped++
                        } else if (existing.isNotEmpty()) {
                            skipped++   // already bundled, same title and length
                        } else if (timeline.events.isEmpty() ||
                            timeline.durationUs < MIN_BUNDLED_DURATION_US
                        ) {
                            skipped++
                        } else {
                            // sourceName == asset name == the tombstone key.
                            runCatching { store.save(timeline, name, SongOrigin.BUNDLED) }
                                .onSuccess { imported++ }
                                .onFailure { skipped++ }
                        }
                    }
                    is ImportResult.Failure -> skipped++
                }
            }
            onProgress(index + 1, names.size)
        }

        marker.write(BUNDLED_SHEETS_VERSION)
        // Log.i, not Log.d: on Android 16 / vivo the DEBUG level of this tag is
        // filtered out, which would hide the required first-run evidence.
        Log.i(TAG, "内置曲库：提升 $promoted 首，导入 $imported 首，跳过 $skipped 首（共 ${names.size}）")
        Result(imported, skipped, names.size, alreadyDone = false, promoted = promoted)
    }.getOrElse { error ->
        // Never let the built-in library take the app down.
        Log.e(TAG, "内置曲库导入失败", error)
        Result(0, 0, 0, alreadyDone = false)
    }

    /** Clears the version marker only; the next [seedIfNeeded] rebuilds. */
    fun resetVersionMarker() {
        marker.write(0)
    }

    /**
     * Re-labels rows whose `sourceName` is a bundled asset name as
     * [SongOrigin.BUNDLED], without touching a single song file.
     *
     * Needed for installs that were populated by a build whose index predates
     * the `origin` field: those rows read back as USER, so deleting them would
     * create no tombstone and 「重新导入内置曲库」 would resurrect them.
     * Matching is by exact asset file name (`0001_xxx.txt`), which a
     * user-imported file is extremely unlikely to collide with.
     *
     * @return how many rows were re-labelled.
     */
    fun backfillOrigin(): Int = runCatching {
        val assetNames = assets.names().toHashSet()
        if (assetNames.isEmpty()) return@runCatching 0
        val updates = store.list()
            .filter { it.origin == SongOrigin.USER && it.sourceName in assetNames }
            .associate { it.id to SongOrigin.BUNDLED }
        if (updates.isEmpty()) return@runCatching 0
        val changed = store.updateOrigin(updates)
        if (changed > 0) Log.i(TAG, "内置曲库来源回填：$changed 首标记为 BUNDLED")
        changed
    }.getOrElse { error ->
        Log.w(TAG, "内置曲库来源回填失败（忽略）", error)
        0
    }

    companion object {
        internal const val TAG = "AutoPlay"
        internal const val ASSET_DIR = "bundled_sheets"
        internal const val PREFS = "bundled_sheets"
        internal const val KEY_VERSION = "bundled_sheets_version"

        const val BUNDLED_SHEETS_VERSION = 2

        /**
         * Screening happens in the packaging script; the runtime keeps no length
         * rule (W27). The constant stays so the intent is explicit and testable.
         */
        const val MIN_BUNDLED_DURATION_US = 0L
    }
}

/**
 * W27: where the seeder gets its assets from.
 *
 * A seam, not an abstraction for its own sake: the 707-promote path is the whole
 * point of this release and the only way to cover it in a plain JVM unit test
 * (there is no Context there). Production always uses [ContextSheetAssets].
 */
interface SheetAssets {
    fun names(): List<String>
    fun bytes(name: String): ByteArray?
}

/** W27: the "already seeded" marker, injectable for the same reason. */
interface VersionMarker {
    fun read(): Int
    fun write(version: Int)
}

private class ContextSheetAssets(private val context: Context) : SheetAssets {
    override fun names(): List<String> =
        context.assets.list(BundledSheetSeeder.ASSET_DIR).orEmpty().toList()

    override fun bytes(name: String): ByteArray? = runCatching {
        context.assets.open("${BundledSheetSeeder.ASSET_DIR}/$name").use { it.readBytes() }
    }.getOrNull()
}

private class SharedPreferencesVersionMarker(context: Context) : VersionMarker {
    private val prefs = context.getSharedPreferences(
        BundledSheetSeeder.PREFS, Context.MODE_PRIVATE
    )

    override fun read(): Int = prefs.getInt(BundledSheetSeeder.KEY_VERSION, 0)

    override fun write(version: Int) {
        prefs.edit().putInt(BundledSheetSeeder.KEY_VERSION, version).apply()
    }
}
