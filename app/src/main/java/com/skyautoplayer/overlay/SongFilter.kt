package com.skyautoplayer.overlay

import com.skyautoplayer.storage.SongEntry

/**
 * Filtering for the in-overlay song search.
 *
 * Kept free of Android types so it is unit-testable, and — importantly — it
 * returns each match **paired with its index in the full queue**. Selection is
 * id-based because the library contains 21 groups of duplicate titles, so a
 * filtered row must never be mapped back to the queue by title.
 */
object SongFilter {

    /**
     * @return (index-in-full-queue, entry) for every entry whose title or source
     *         file name contains [query], case-insensitively. A blank query
     *         returns everything with its original indices.
     */
    fun filter(songs: List<SongEntry>, query: String): List<Pair<Int, SongEntry>> {
        val q = query.trim()
        if (q.isEmpty()) return songs.withIndex().map { it.index to it.value }
        return songs.withIndex()
            .filter { (_, entry) ->
                entry.title.contains(q, ignoreCase = true) ||
                    entry.sourceName.contains(q, ignoreCase = true)
            }
            .map { it.index to it.value }
    }

    fun pageCount(matchCount: Int, pageSize: Int): Int =
        ((matchCount + pageSize - 1) / pageSize).coerceAtLeast(1)

    /** Clamp a page number into range for the current match count. */
    fun clampPage(page: Int, matchCount: Int, pageSize: Int): Int =
        page.coerceIn(0, pageCount(matchCount, pageSize) - 1)

    /** The slice of [matches] shown on [page]. */
    fun pageSlice(
        matches: List<Pair<Int, SongEntry>>,
        page: Int,
        pageSize: Int
    ): List<Pair<Int, SongEntry>> =
        matches.drop(page * pageSize).take(pageSize)
}
