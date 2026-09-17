package com.skyautoplayer.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deletion tombstones (W9.2).
 *
 * Without these, `BundledSheetSeeder` — which only knows a version marker —
 * would pour every deliberately deleted bundled song back on the next version
 * bump or on 「重新导入内置曲库」. The SharedPreferences-backed constructor is a
 * thin shell; the logic under test lives in [BundledTombstones] itself, so an
 * in-memory backend is used here.
 */
class BundledTombstoneTest {

    private val assets = listOf(
        "0001_a.txt", "0002_b.txt", "0003_c.txt", "0004_d.txt", "0005_e.txt"
    )

    @Test
    fun markedAssetIsNoLongerEligibleForImport() {
        val tombstones = BundledTombstones(InMemoryTombstoneBackend())
        assertTrue(tombstones.filterEligible(assets).size == assets.size)

        tombstones.mark("0003_c.txt")

        val eligible = tombstones.filterEligible(assets)
        assertEquals(assets.size - 1, eligible.size)
        assertTrue("0003_c.txt" !in eligible, "tombstoned asset must be skipped")
        assertTrue(tombstones.contains("0003_c.txt"))
    }

    @Test
    fun markingIsIdempotent() {
        val tombstones = BundledTombstones(InMemoryTombstoneBackend())
        repeat(3) { tombstones.mark("0001_a.txt") }
        assertEquals(setOf("0001_a.txt"), tombstones.all())
    }

    @Test
    fun markAllAddsEverythingAtOnce() {
        val tombstones = BundledTombstones(InMemoryTombstoneBackend())
        tombstones.markAll(listOf("0001_a.txt", "0002_b.txt", "0001_a.txt"))
        assertEquals(setOf("0001_a.txt", "0002_b.txt"), tombstones.all())
        assertEquals(assets.size - 2, tombstones.filterEligible(assets).size)
    }

    @Test
    fun clearAllRestoresEveryAsset() {
        val tombstones = BundledTombstones(InMemoryTombstoneBackend())
        tombstones.markAll(listOf("0001_a.txt", "0002_b.txt", "0005_e.txt"))
        assertEquals(assets.size - 3, tombstones.filterEligible(assets).size)

        // 「恢复内置曲库」 = clearAll() then a forced reseed.
        tombstones.clearAll()

        assertTrue(tombstones.all().isEmpty())
        assertEquals(assets.size, tombstones.filterEligible(assets).size)
        assertTrue(!tombstones.contains("0001_a.txt"))
    }

    @Test
    fun tombstonesPersistAcrossInstancesSharingABackend() {
        val backend = InMemoryTombstoneBackend()
        BundledTombstones(backend).mark("0002_b.txt")

        // A fresh instance over the same storage still sees the tombstone.
        val reopened = BundledTombstones(backend)
        assertTrue(reopened.contains("0002_b.txt"))
        assertEquals(assets.size - 1, reopened.filterEligible(assets).size)
    }

    @Test
    fun blankNamesAreIgnored() {
        val tombstones = BundledTombstones(InMemoryTombstoneBackend())
        tombstones.mark("")
        tombstones.mark("   ")
        tombstones.markAll(listOf("", "0001_a.txt"))
        assertEquals(setOf("0001_a.txt"), tombstones.all())
    }

    @Test
    fun seededBackendStartsWithTheGivenNames() {
        val tombstones = BundledTombstones(InMemoryTombstoneBackend(setOf("0004_d.txt")))
        assertEquals(assets.size - 1, tombstones.filterEligible(assets).size)
        assertTrue("0004_d.txt" !in tombstones.filterEligible(assets))
    }
}
