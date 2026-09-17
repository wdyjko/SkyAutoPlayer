package com.skyautoplayer.overlay

import com.skyautoplayer.storage.SongEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Search behaviour for the in-overlay song picker.
 *
 * The library ships 579 songs (~73 pages), so paging alone is unusable; this
 * filter is the primary way to reach a song. It must also preserve original
 * queue indices, because titles are not unique (21 duplicate groups).
 */
class SongFilterTest {

    private fun song(id: String, title: String, source: String = "$title.txt") =
        SongEntry(
            id = id,
            title = title,
            durationUs = 60_000_000L,
            eventCount = 100,
            importedAt = 0L,
            sourceName = source
        )

    private val library = listOf(
        song("1", "青花瓷"),
        song("2", "Flower Dance"),
        song("3", "flower dance 简易版"),
        song("4", "My heart will go on"),
        song("5", "反方向的钟"),
        song("6", "龙卷风"),
        song("7", "Riverside", "Riverside_UTF16.txt"),
        song("8", "Counting Stars")
    )

    @Test
    fun blankQueryReturnsEverythingWithOriginalIndices() {
        val all = SongFilter.filter(library, "")
        assertEquals(library.size, all.size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), all.map { it.first })

        assertEquals(library.size, SongFilter.filter(library, "   ").size)
    }

    @Test
    fun matchesChineseSubstring() {
        val hit = SongFilter.filter(library, "青花")
        assertEquals(1, hit.size)
        assertEquals("青花瓷", hit.single().second.title)
    }

    @Test
    fun matchIsCaseInsensitive() {
        val lower = SongFilter.filter(library, "flower")
        val upper = SongFilter.filter(library, "FLOWER")
        assertEquals(2, lower.size)
        assertEquals(lower.map { it.first }, upper.map { it.first })
    }

    @Test
    fun indicesPointIntoTheFullQueueNotTheFilteredList() {
        // "flow" matches entries 1 and 2; the indices must stay 1 and 2.
        val hit = SongFilter.filter(library, "flow")
        assertEquals(listOf(1, 2), hit.map { it.first })
        assertEquals("Flower Dance", library[hit[0].first].title)
        assertEquals("flower dance 简易版", library[hit[1].first].title)
    }

    @Test
    fun matchesSourceFileNameToo() {
        val hit = SongFilter.filter(library, "_UTF16")
        assertEquals(1, hit.size)
        assertEquals("Riverside", hit.single().second.title)
    }

    @Test
    fun queryIsTrimmed() {
        assertEquals(1, SongFilter.filter(library, "  龙卷风  ").size)
    }

    @Test
    fun noMatchReturnsEmpty() {
        assertTrue(SongFilter.filter(library, "zzzz-not-a-song").isEmpty())
    }

    @Test
    fun duplicateTitlesRemainDistinguishable() {
        val dupes = listOf(
            song("a", "Flower Dance"),
            song("b", "Flower Dance"),
            song("c", "Flower Dance")
        )
        val hit = SongFilter.filter(dupes, "Flower")
        assertEquals(3, hit.size)
        // Distinct ids, distinct queue indices.
        assertEquals(listOf("a", "b", "c"), hit.map { it.second.id })
        assertEquals(listOf(0, 1, 2), hit.map { it.first })
    }

    @Test
    fun pagingOverLargeLibrary() {
        val many = (1..579).map { song("id$it", "Song %03d".format(it)) }

        val all = SongFilter.filter(many, "")
        assertEquals(579, all.size)
        assertEquals(73, SongFilter.pageCount(all.size, 8))

        // Last page holds the remainder (579 = 72*8 + 3).
        val last = SongFilter.pageSlice(all, 72, 8)
        assertEquals(3, last.size)
        assertEquals("Song 577", last.first().second.title)

        // Out-of-range page clamps instead of throwing.
        assertEquals(72, SongFilter.clampPage(999, all.size, 8))
        assertEquals(0, SongFilter.clampPage(-5, all.size, 8))
    }

    @Test
    fun searchShrinksPagingToASinglePage() {
        val many = (1..579).map { song("id$it", "Song %03d".format(it)) }
        val hit = SongFilter.filter(many, "Song 421")
        assertEquals(1, hit.size)
        assertEquals(1, SongFilter.pageCount(hit.size, 8))
    }
}
