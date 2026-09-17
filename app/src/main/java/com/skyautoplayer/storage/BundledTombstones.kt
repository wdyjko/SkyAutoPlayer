package com.skyautoplayer.storage

import android.content.Context

/**
 * Where the tombstone set lives. Abstracted so JVM unit tests can inject an
 * in-memory implementation instead of Android SharedPreferences.
 */
interface TombstoneBackend {
    fun load(): Set<String>
    fun store(names: Set<String>)
}

class SharedPreferencesTombstoneBackend(context: Context) : TombstoneBackend {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): Set<String> =
        // getStringSet returns the live instance; copy before anyone mutates it.
        prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()

    override fun store(names: Set<String>) {
        prefs.edit().putStringSet(KEY, names.toSet()).apply()
    }

    companion object {
        const val PREFS = "deleted_bundled"
        const val KEY = "assets"
    }
}

/** In-memory backend, for tests. */
class InMemoryTombstoneBackend(initial: Set<String> = emptySet()) : TombstoneBackend {
    private var names: Set<String> = initial.toSet()
    override fun load(): Set<String> = names
    override fun store(names: Set<String>) {
        this.names = names.toSet()
    }
}

/**
 * Remembers bundled songs the user deleted (W9.2).
 *
 * `BundledSheetSeeder` only knows a version marker, so without this every
 * deleted bundled song would pour back on the next version bump or on
 * 「重新导入内置曲库」. The key is the **asset file name**, which is exactly the
 * `sourceName` recorded on bundled [SongEntry] rows.
 *
 * 「恢复内置曲库」 is [clearAll] followed by a forced reseed.
 */
class BundledTombstones(private val backend: TombstoneBackend) {

    constructor(context: Context) : this(SharedPreferencesTombstoneBackend(context))

    fun mark(assetName: String) {
        if (assetName.isBlank()) return
        val current = backend.load()
        if (assetName in current) return
        backend.store(current + assetName)
    }

    fun markAll(assetNames: Collection<String>) {
        val current = backend.load()
        val merged = current + assetNames.filter { it.isNotBlank() }
        if (merged.size != current.size) backend.store(merged)
    }

    fun contains(assetName: String): Boolean = assetName in backend.load()

    fun all(): Set<String> = backend.load()

    fun clearAll() = backend.store(emptySet())

    /** Asset names that are NOT tombstoned, i.e. still eligible for import. */
    fun filterEligible(assetNames: List<String>): List<String> {
        val dead = backend.load()
        return assetNames.filterNot { it in dead }
    }
}
