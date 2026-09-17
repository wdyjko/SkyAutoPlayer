package com.skyautoplayer.storage

import com.skyautoplayer.domain.timeline.SongTimeline
import com.skyautoplayer.domain.timeline.SourceMetadata
import com.skyautoplayer.domain.timeline.TimelineEvent
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FileSongStore runs entirely on the JVM by taking a directory; the Android
 * `Context` constructor is a convenience overload used by the app.
 *
 * Gradle's unit-test CWD is the module directory (`app/`).
 */
class FileSongStoreTest {

    private val counter = AtomicInteger(0)

    private fun tempDir(): File {
        val dir = File("build/test-tmp/songstore-${System.nanoTime()}-${counter.incrementAndGet()}")
        dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    private fun event(atUs: Long, keys: Set<Int> = setOf(1), holdUs: Long = 60_000L) =
        TimelineEvent(atUs, keys, holdUs)

    private fun timeline(
        id: String,
        title: String,
        events: List<TimelineEvent> = listOf(event(0), event(250_000), event(500_000))
    ) = SongTimeline(
        timelineId = id,
        title = title,
        durationUs = events.maxOf { it.atUs + it.holdUs },
        events = events,
        source = SourceMetadata("sky-json", "$title.txt")
    )

    @Test
    fun roundTripPreservesFields() {
        val dir = tempDir()
        val store = FileSongStore(dir)
        val original = timeline("id-1", "Twinkle")

        val entry = store.save(original, "Twinkle.txt")
        assertEquals("id-1", entry.id)
        assertEquals(3, entry.eventCount)

        val loaded = assertNotNull(store.load("id-1"), "saved song must load back")
        assertEquals(original.title, loaded.title)
        assertEquals(original.durationUs, loaded.durationUs)
        assertEquals(original.events.size, loaded.events.size)
        assertEquals(original.events[1].atUs, loaded.events[1].atUs)
        assertEquals(original.events[1].keys, loaded.events[1].keys)
        assertEquals(original.events[1].holdUs, loaded.events[1].holdUs)
    }

    @Test
    fun emptyDirectoryGivesEmptyLibrary() {
        val store = FileSongStore(tempDir())
        assertTrue(store.list().isEmpty())
        assertEquals(0, store.count())
    }

    @Test
    fun listIsSortedByTitle() {
        val dir = tempDir()
        val store = FileSongStore(dir)
        store.save(timeline("c", "Charlie"), "c.txt")
        store.save(timeline("a", "alpha"), "a.txt")
        store.save(timeline("b", "Bravo"), "b.txt")

        assertEquals(listOf("alpha", "Bravo", "Charlie"), store.list().map { it.title })
    }

    @Test
    fun corruptSongFileIsSkippedNotFatal() {
        val dir = tempDir()
        val store = FileSongStore(dir)
        store.save(timeline("good", "Good"), "good.txt")

        // Garbage JSON, and valid JSON that violates the timeline invariants
        // (empty keys) - both must be skipped without throwing.
        File(dir, "broken.json").writeText("{not json at all", Charsets.UTF_8)
        File(dir, "invalidevent.json").writeText(
            """{"timelineId":"bad","title":"Bad","durationUs":10,"events":[{"atUs":0,"keys":[],"holdUs":10}]}""",
            Charsets.UTF_8
        )

        // Force a rebuild from disk by dropping the index and re-opening.
        File(dir, FileSongStore.INDEX_NAME).delete()
        val reopened = FileSongStore(dir)

        assertEquals(1, reopened.count(), "only the good song should survive")
        assertEquals("Good", reopened.list().single().title)
        assertNull(reopened.load("broken"))
        assertNull(reopened.load("invalidevent"))
        assertTrue(reopened.skippedCount >= 2, "skipped=${reopened.skippedCount}")
    }

    @Test
    fun indexIsRebuiltWhenMissing() {
        val dir = tempDir()
        val store = FileSongStore(dir)
        store.save(timeline("x", "Xylophone"), "x.txt")
        File(dir, FileSongStore.INDEX_NAME).delete()

        val reopened = FileSongStore(dir)
        assertEquals(1, reopened.count())
        assertEquals("Xylophone", reopened.list().single().title)
    }

    @Test
    fun deleteRemovesSongAndIndexRow() {
        val dir = tempDir()
        val store = FileSongStore(dir)
        store.save(timeline("keep", "Keep"), "keep.txt")
        store.save(timeline("drop", "Drop"), "drop.txt")
        assertEquals(2, store.count())

        store.delete("drop")

        assertEquals(1, store.count())
        assertEquals("Keep", store.list().single().title)
        assertNull(store.load("drop"))
        assertTrue(!File(dir, "drop.json").exists(), "song file must be removed")
        // Index on disk must agree after a reopen.
        assertEquals(1, FileSongStore(dir).count())
    }

    @Test
    fun countMatchesSongFilesOnDisk() {
        val dir = tempDir()
        val store = FileSongStore(dir)
        repeat(4) { i -> store.save(timeline("id-$i", "Song $i"), "s$i.txt") }

        val jsonFiles = dir.listFiles { f ->
            f.isFile && f.extension == "json" && f.name != FileSongStore.INDEX_NAME
        }.orEmpty()

        assertEquals(4, store.count())
        assertEquals(4, jsonFiles.size)
    }

    @Test
    fun savingSameIdTwiceDoesNotDuplicateTheRow() {
        val dir = tempDir()
        val store = FileSongStore(dir)
        store.save(timeline("dup", "First"), "a.txt")
        store.save(timeline("dup", "Second"), "b.txt")

        assertEquals(1, store.count())
        assertEquals("Second", store.load("dup")?.title)
    }

    @Test
    fun idsWithPathCharactersDoNotEscapeTheDirectory() {
        val dir = tempDir()
        val store = FileSongStore(dir)
        store.save(timeline("../../evil", "Evil"), "evil.txt")

        // Must stay inside rootDir.
        val stray = File(dir.parentFile, "evil.json")
        assertTrue(!stray.exists(), "sanitised id must not escape the songs directory")
        assertEquals(1, store.count())
    }

    // ── W9: origin + batch delete ────────────────────────────────────────

    @Test
    fun originSurvivesARoundTrip() {
        val dir = tempDir()
        val store = FileSongStore(dir)
        store.save(timeline("b", "Bundled One"), "0001_a.txt", SongOrigin.BUNDLED)
        store.save(timeline("u", "User One"), "mine.txt", SongOrigin.USER)

        val reopened = FileSongStore(dir)
        val byId = reopened.list().associateBy { it.id }
        assertEquals(SongOrigin.BUNDLED, byId.getValue("b").origin)
        assertEquals(SongOrigin.USER, byId.getValue("u").origin)
        assertTrue(byId.getValue("b").isBundled)
        assertTrue(!byId.getValue("u").isBundled)
        // sourceName is the tombstone key for bundled rows.
        assertEquals("0001_a.txt", byId.getValue("b").sourceName)
    }

    @Test
    fun savingWithoutOriginDefaultsToUser() {
        val dir = tempDir()
        val store = FileSongStore(dir)
        val entry = store.save(timeline("x", "Plain"), "plain.txt")
        assertEquals(SongOrigin.USER, entry.origin)
    }

    @Test
    fun legacyIndexWithoutOriginIsReadAsUserAndDoesNotWipeTheLibrary() {
        val dir = tempDir()
        val store = FileSongStore(dir)
        store.save(timeline("keep1", "Keep One"), "k1.txt", SongOrigin.BUNDLED)
        store.save(timeline("keep2", "Keep Two"), "k2.txt")

        // Simulate an index written by the previous version: no `origin` field.
        val legacy = """
            {"version":1,"entries":[
              {"id":"keep1","title":"Keep One","durationUs":500000,"eventCount":3,
               "importedAt":1,"sourceName":"k1.txt"},
              {"id":"keep2","title":"Keep Two","durationUs":500000,"eventCount":3,
               "importedAt":2,"sourceName":"k2.txt"}
            ]}
        """.trimIndent()
        File(dir, FileSongStore.INDEX_NAME).writeText(legacy, Charsets.UTF_8)

        val reopened = FileSongStore(dir)
        assertEquals(2, reopened.count(), "a legacy index must not be discarded")
        assertTrue(
            reopened.list().all { it.origin == SongOrigin.USER },
            "missing origin must be read as USER, never BUNDLED"
        )
    }

    @Test
    fun rebuildRecoversOriginFromTheSongFileItself() {
        val dir = tempDir()
        val store = FileSongStore(dir)
        store.save(timeline("b", "Bundled"), "0007_x.txt", SongOrigin.BUNDLED)
        store.save(timeline("u", "User"), "mine.txt", SongOrigin.USER)

        // Lose the index entirely: a rebuild must still know what was bundled,
        // otherwise deleting it later would not create a tombstone.
        File(dir, FileSongStore.INDEX_NAME).delete()
        val rebuilt = FileSongStore(dir)

        val byId = rebuilt.list().associateBy { it.id }
        assertEquals(2, rebuilt.count())
        assertEquals(SongOrigin.BUNDLED, byId.getValue("b").origin)
        assertEquals(SongOrigin.USER, byId.getValue("u").origin)
    }

    @Test
    fun deleteManyRemovesFilesAndRewritesTheIndexOnce() {
        val dir = tempDir()
        val store = FileSongStore(dir)
        (1..5).forEach { store.save(timeline("id$it", "Song $it"), "s$it.txt") }
        assertEquals(5, store.count())

        store.deleteMany(setOf("id2", "id4"))

        assertEquals(3, store.count())
        assertEquals(listOf("Song 1", "Song 3", "Song 5"), store.list().map { it.title })
        assertTrue(!File(dir, "id2.json").exists())
        assertTrue(!File(dir, "id4.json").exists())
        // Index on disk must agree after a reopen (i.e. it really was rewritten).
        assertEquals(3, FileSongStore(dir).count())

        val jsonFiles = dir.listFiles { f ->
            f.isFile && f.extension == "json" && f.name != FileSongStore.INDEX_NAME
        }.orEmpty()
        assertEquals(3, jsonFiles.size)
    }

    @Test
    fun deleteManyWithEmptySetIsANoOp() {
        val dir = tempDir()
        val store = FileSongStore(dir)
        store.save(timeline("a", "A"), "a.txt")
        store.deleteMany(emptySet())
        assertEquals(1, store.count())
    }

    @Test
    fun updateOriginRelabelsWithoutRewritingSongFiles() {
        val dir = tempDir()
        val store = FileSongStore(dir)
        // Simulates an index written before `origin` existed: everything USER.
        store.save(timeline("b1", "Bundled One"), "0001_a.txt", SongOrigin.USER)
        store.save(timeline("b2", "Bundled Two"), "0002_b.txt", SongOrigin.USER)
        store.save(timeline("u1", "Mine"), "my_song.txt", SongOrigin.USER)

        val songFile = File(dir, "b1.json")
        val bytesBefore = songFile.readBytes().toList()
        val modifiedBefore = songFile.lastModified()

        val changed = store.updateOrigin(
            mapOf(
                "b1" to SongOrigin.BUNDLED,
                "b2" to SongOrigin.BUNDLED,
                "u1" to SongOrigin.USER   // unchanged -> not counted
            )
        )

        assertEquals(2, changed)
        val byId = FileSongStore(dir).list().associateBy { it.id }
        assertEquals(SongOrigin.BUNDLED, byId.getValue("b1").origin)
        assertEquals(SongOrigin.BUNDLED, byId.getValue("b2").origin)
        assertEquals(SongOrigin.USER, byId.getValue("u1").origin)
        // Index-only: the song payload is untouched.
        assertEquals(bytesBefore, songFile.readBytes().toList())
        assertEquals(modifiedBefore, songFile.lastModified())
    }

    @Test
    fun updateOriginIsIdempotent() {
        val dir = tempDir()
        val store = FileSongStore(dir)
        store.save(timeline("b", "B"), "0001_a.txt", SongOrigin.USER)
        assertEquals(1, store.updateOrigin(mapOf("b" to SongOrigin.BUNDLED)))
        assertEquals(0, store.updateOrigin(mapOf("b" to SongOrigin.BUNDLED)))
    }
}
