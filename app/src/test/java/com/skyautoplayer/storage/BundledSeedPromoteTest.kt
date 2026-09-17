package com.skyautoplayer.storage

import com.skyautoplayer.domain.timeline.SongTimeline
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * W27: the bundled seeder must *promote* songs the user already had instead of
 * importing them again.
 *
 * The device library holds 142 hand-imported rows whose `sourceName` is the
 * original file name, so the seeder's asset-name idempotency check can never
 * match them. Without the (title, durationUs) fallback they would be imported a
 * second time and the library would grow 707 -> 849.
 *
 * Uses the [SheetAssets] / [VersionMarker] seams so the whole `seedIfNeeded`
 * path runs on the JVM (there is no `Context` in a unit test).
 */
class BundledSeedPromoteTest {

    /** `time`/`duration` are milliseconds, so this sheet lasts 61s. */
    private val sheet = """
        {"name":"Song A","songNotes":[
          {"time":0,"key":"1Key0","duration":60000},
          {"time":1000,"key":"1Key1","duration":60000}]}
    """.trimIndent()

    private val sheetDurationUs = 61_000_000L

    private fun assets(vararg names: String) = FakeSheetAssets(
        names.associateWith { sheet.toByteArray(Charsets.UTF_8) }
    )

    private fun seeder(store: SongStore, assets: SheetAssets, marker: VersionMarker) =
        BundledSheetSeeder(store, BundledTombstones(InMemoryTombstoneBackend()), assets, marker)

    @Test
    fun promoteDoesNotDuplicate() {
        // Exactly what a hand-imported row looks like: USER origin, original file
        // name, and a (title, durationUs) that the asset now also provides.
        val human = SongEntry(
            id = "user-1",
            title = "Song A",
            durationUs = sheetDurationUs,
            eventCount = 2,
            importedAt = 1L,
            sourceName = "\u574F\u5973\u5B69.txt",
            origin = SongOrigin.USER
        )
        val store = InMemorySongStore(listOf(human))
        val marker = FakeMarker(1)

        val result = seeder(store, assets("0001_song-a.txt"), marker).seedIfNeeded(force = true)

        assertEquals(1, result.promoted, "the existing row must be promoted")
        assertEquals(0, result.imported, "nothing may be imported on top of it")
        assertEquals(1, store.count(), "the library must not grow")
        val row = store.list().single()
        assertEquals(SongOrigin.BUNDLED, row.origin)
        assertEquals("0001_song-a.txt", row.sourceName)
        assertEquals(2, marker.read(), "the pushed marker must record the new version")
    }

    @Test
    fun promoteIsIdempotentAcrossReseeds() {
        val store = InMemorySongStore(
            listOf(
                SongEntry(
                    "user-1", "Song A", sheetDurationUs, 2, 1L,
                    "\u574F\u5973\u5B69.txt", SongOrigin.USER
                )
            )
        )
        val marker = FakeMarker(0)
        val seeder = seeder(store, assets("0001_song-a.txt"), marker)

        seeder.seedIfNeeded(force = true)
        val second = seeder.seedIfNeeded(force = true)

        assertEquals(1, store.count(), "a reseed must not add a second row")
        assertEquals(0, second.promoted, "an already-promoted row is skipped")
        assertEquals(0, second.imported)
        assertEquals(1, second.skipped)
    }

    @Test
    fun anUnknownSheetIsImportedAsBundled() {
        val store = InMemorySongStore()
        val marker = FakeMarker(0)

        val result = seeder(store, assets("0001_song-a.txt"), marker).seedIfNeeded(force = true)

        assertEquals(1, result.imported)
        assertEquals(0, result.promoted)
        val row = store.list().single()
        assertEquals(SongOrigin.BUNDLED, row.origin)
        assertEquals("Song A", row.title)
        assertEquals(sheetDurationUs, row.durationUs)
    }

    @Test
    fun versionMarkerShortCircuitsWhenNotForced() {
        val store = InMemorySongStore()
        val result = seeder(store, assets("0001_song-a.txt"), FakeMarker(2))
            .seedIfNeeded(force = false)

        assertEquals(true, result.alreadyDone)
        assertEquals(0, store.count())
    }
}

/** Minimal in-memory [SongStore]; only what `seedIfNeeded` touches. */
private class InMemorySongStore(initial: List<SongEntry> = emptyList()) : SongStore {

    private val rows = initial.toMutableList()

    override fun list(): List<SongEntry> = rows.sortedBy { it.title.lowercase() }

    override fun load(id: String): SongTimeline? = null

    override fun save(timeline: SongTimeline, sourceName: String, origin: SongOrigin): SongEntry {
        val entry = SongEntry(
            id = timeline.timelineId,
            title = timeline.title,
            durationUs = timeline.durationUs,
            eventCount = timeline.events.size,
            importedAt = 0L,
            sourceName = sourceName,
            origin = origin
        )
        rows += entry
        return entry
    }

    override fun delete(id: String) {
        rows.removeAll { it.id == id }
    }

    override fun promote(id: String, assetName: String): Boolean {
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0) return false
        rows[index] = rows[index].copy(origin = SongOrigin.BUNDLED, sourceName = assetName)
        return true
    }

    override fun count(): Int = rows.size
}

private class FakeSheetAssets(private val map: Map<String, ByteArray>) : SheetAssets {
    override fun names(): List<String> = map.keys.toList()
    override fun bytes(name: String): ByteArray? = map[name]
}

private class FakeMarker(private var value: Int) : VersionMarker {
    override fun read(): Int = value
    override fun write(version: Int) {
        value = version
    }
}
