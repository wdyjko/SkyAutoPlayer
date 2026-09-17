package com.skyautoplayer.storage

import com.skyautoplayer.domain.timeline.SongTimeline
import com.skyautoplayer.domain.timeline.SourceMetadata
import com.skyautoplayer.domain.timeline.TimelineEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Where a library row came from. Bundled rows get a deletion tombstone. */
enum class SongOrigin { BUNDLED, USER }

/** Index row: enough to render a list without reading every song's events. */
data class SongEntry(
    val id: String,
    val title: String,
    val durationUs: Long,
    val eventCount: Int,
    val importedAt: Long,
    val sourceName: String,
    /**
     * Legacy indexes (written before this field existed) have no `origin`.
     * They are read as [SongOrigin.USER] - never as BUNDLED - so an upgrade can
     * never mass-tombstone songs the user did not delete.
     */
    val origin: SongOrigin = SongOrigin.USER
) {
    val isBundled: Boolean get() = origin == SongOrigin.BUNDLED
}

interface SongStore {
    fun list(): List<SongEntry>
    fun load(id: String): SongTimeline?
    fun save(
        timeline: SongTimeline,
        sourceName: String,
        origin: SongOrigin = SongOrigin.USER
    ): SongEntry
    fun delete(id: String)

    /** Delete many rows, rewriting the index exactly once. */
    fun deleteMany(ids: Set<String>) {
        ids.forEach { delete(it) }
    }

    /**
     * Index-only origin update; no song file is rewritten.
     *
     * Used to backfill [SongOrigin.BUNDLED] onto rows that came from an index
     * written before the field existed - see [BundledSheetSeeder.backfillOrigin].
     *
     * @return how many rows actually changed.
     */
    fun updateOrigin(origins: Map<String, SongOrigin>): Int = 0

    /**
     * W27: turn an existing row into a bundled one, adopting [assetName] as its
     * source name.
     *
     * Called when the bundled assets now ship a song the user had already
     * imported by hand - importing it again would append a duplicate row, so the
     * existing row is re-labelled in place instead. The song file is deliberately
     * NOT rewritten (the content is already that song); only the index changes.
     *
     * @return true when a row was found and updated.
     */
    fun promote(id: String, assetName: String): Boolean = false

    fun count(): Int
}

/**
 * File-backed song library.
 *
 * - songs: `songs/<timelineId>.json` in the internal timeline format
 * - index: `songs/index.json` (metadata only)
 *
 * The index is written via a temp file + rename so a crash mid-write cannot
 * leave a truncated index behind, and it is rebuilt from the directory whenever
 * it is missing or unreadable.
 *
 * Every read is wrapped in `runCatching`: [SongTimeline] and [TimelineEvent]
 * enforce their invariants in `init` and throw on bad data, so one corrupt file
 * must never take down the whole library.
 */
class FileSongStore(private val rootDir: File) : SongStore {

    constructor(context: android.content.Context) : this(File(context.filesDir, DIR_NAME))

    private val indexFile = File(rootDir, INDEX_NAME)
    private var cached: MutableList<SongEntry>? = null

    /** Files skipped because they could not be parsed, since construction. */
    var skippedCount: Int = 0
        private set

    override fun list(): List<SongEntry> = entries().sortedBy { it.title.lowercase() }

    override fun count(): Int = entries().size

    override fun load(id: String): SongTimeline? {
        val file = songFile(id)
        if (!file.isFile) return null
        return runCatching { decodeTimeline(file.readText(Charsets.UTF_8)) }
            .onFailure { skippedCount++ }
            .getOrNull()
    }

    override fun save(
        timeline: SongTimeline,
        sourceName: String,
        origin: SongOrigin
    ): SongEntry {
        ensureDir()
        val entry = SongEntry(
            id = timeline.timelineId,
            title = timeline.title,
            durationUs = timeline.durationUs,
            eventCount = timeline.events.size,
            importedAt = System.currentTimeMillis(),
            sourceName = sourceName,
            origin = origin
        )
        // `origin` is written into the song file as well as the index, so a
        // rebuild after index loss does not silently demote bundled songs to
        // USER (which would break their tombstones).
        songFile(entry.id).writeText(
            encodeTimeline(timeline, origin).toString(), Charsets.UTF_8
        )
        val list = entries()
        val existing = list.indexOfFirst { it.id == entry.id }
        if (existing >= 0) list[existing] = entry else list += entry
        writeIndex(list)
        return entry
    }

    override fun delete(id: String) {
        songFile(id).delete()
        val list = entries()
        list.removeAll { it.id == id }
        writeIndex(list)
    }

    /** Deletes the files and rewrites the index once (W9.4). */
    override fun deleteMany(ids: Set<String>) {
        if (ids.isEmpty()) return
        val list = entries()
        ids.forEach { id ->
            songFile(id).delete()
            // Also drop the cached copy so nothing can re-read it.
        }
        list.removeAll { it.id in ids }
        writeIndex(list)
    }

    override fun updateOrigin(origins: Map<String, SongOrigin>): Int {
        if (origins.isEmpty()) return 0
        val list = entries()
        var changed = 0
        list.forEachIndexed { index, entry ->
            val wanted = origins[entry.id] ?: return@forEachIndexed
            if (wanted != entry.origin) {
                list[index] = entry.copy(origin = wanted)
                changed++
            }
        }
        if (changed > 0) writeIndex(list)
        return changed
    }

    /** W27: index-only promotion; see [SongStore.promote]. */
    override fun promote(id: String, assetName: String): Boolean {
        val list = entries()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return false
        val current = list[index]
        if (current.origin == SongOrigin.BUNDLED && current.sourceName == assetName) return true
        list[index] = current.copy(origin = SongOrigin.BUNDLED, sourceName = assetName)
        // Index-only, through the existing temp + rename writer. The seeder calls
        // this once per promoted row, which happens once per install (the version
        // marker in bundled_sheets.xml gates it) - not on any hot path.
        writeIndex(list)
        return true
    }

    // ── index ────────────────────────────────────────────────────────────

    private fun entries(): MutableList<SongEntry> {
        cached?.let { return it }
        val loaded = readIndex() ?: rebuildIndex()
        cached = loaded
        return loaded
    }

    private fun readIndex(): MutableList<SongEntry>? {
        if (!indexFile.isFile) return null
        return runCatching {
            val root = JSONObject(indexFile.readText(Charsets.UTF_8))
            val arr = root.getJSONArray("entries")
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                SongEntry(
                    id = o.getString("id"),
                    title = o.optString("title"),
                    durationUs = o.optLong("durationUs"),
                    eventCount = o.optInt("eventCount"),
                    importedAt = o.optLong("importedAt"),
                    sourceName = o.optString("sourceName"),
                    // Missing on legacy indexes -> USER (never BUNDLED).
                    origin = parseOrigin(o.optString("origin"))
                )
            }
        }.getOrNull()
    }

    /** Rebuild from whatever song files are actually on disk. */
    private fun rebuildIndex(): MutableList<SongEntry> {
        skippedCount = 0
        val rebuilt = mutableListOf<SongEntry>()
        ensureDir()
        rootDir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != INDEX_NAME }
            .orEmpty()
            .sortedBy { it.name }
            .forEach { file ->
                val text = runCatching { file.readText(Charsets.UTF_8) }
                    .onFailure { skippedCount++ }
                    .getOrNull()
                val timeline = text?.let {
                    runCatching { decodeTimeline(it) }
                        .onFailure { skippedCount++ }
                        .getOrNull()
                }
                if (timeline != null) {
                    rebuilt += SongEntry(
                        id = timeline.timelineId,
                        title = timeline.title,
                        durationUs = timeline.durationUs,
                        eventCount = timeline.events.size,
                        importedAt = file.lastModified(),
                        sourceName = timeline.source.name ?: file.name,
                        // Recovered from the song file itself, so a rebuild keeps
                        // bundled songs tombstone-able.
                        origin = text?.let { parseOriginFromSong(it) } ?: SongOrigin.USER
                    )
                }
            }
        writeIndex(rebuilt)
        return rebuilt
    }

    private fun writeIndex(list: List<SongEntry>) {
        ensureDir()
        val root = JSONObject().apply {
            put("version", INDEX_VERSION)
            put("entries", JSONArray().apply {
                list.forEach { e ->
                    put(JSONObject().apply {
                        put("id", e.id)
                        put("title", e.title)
                        put("durationUs", e.durationUs)
                        put("eventCount", e.eventCount)
                        put("importedAt", e.importedAt)
                        put("sourceName", e.sourceName)
                        put("origin", e.origin.name)
                    })
                }
            })
        }
        val tmp = File(rootDir, "$INDEX_NAME.tmp")
        tmp.writeText(root.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(indexFile)) {
            // Rename can fail if the target exists on some filesystems.
            indexFile.delete()
            if (!tmp.renameTo(indexFile)) {
                indexFile.writeText(root.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    // ── (de)serialisation ────────────────────────────────────────────────

    private fun decodeTimeline(text: String): SongTimeline {
        val root = JSONObject(text)
        val eventsArr = root.getJSONArray("events")
        val events = ArrayList<TimelineEvent>(eventsArr.length())
        for (i in 0 until eventsArr.length()) {
            val e = eventsArr.getJSONObject(i)
            val keysArr = e.getJSONArray("keys")
            val keys = LinkedHashSet<Int>(keysArr.length())
            for (k in 0 until keysArr.length()) keys += keysArr.getInt(k)
            events += TimelineEvent(
                atUs = e.getLong("atUs"),
                keys = keys,
                holdUs = e.getLong("holdUs")
            )
        }
        val src = root.optJSONObject("source")
        return SongTimeline(
            timelineId = root.getString("timelineId"),
            title = root.optString("title"),
            durationUs = root.optLong("durationUs"),
            events = events,
            source = SourceMetadata(
                format = src?.optString("format")?.takeIf { it.isNotEmpty() } ?: "store",
                name = src?.optString("name")?.takeIf { it.isNotEmpty() }
            )
        )
    }

    private fun encodeTimeline(timeline: SongTimeline, origin: SongOrigin): JSONObject = JSONObject().apply {
        put("timelineId", timeline.timelineId)
        put("title", timeline.title)
        put("durationUs", timeline.durationUs)
        put("events", JSONArray().apply {
            timeline.events.forEach { e ->
                put(JSONObject().apply {
                    put("atUs", e.atUs)
                    put("keys", JSONArray().apply { e.keys.sorted().forEach { put(it) } })
                    put("holdUs", e.holdUs)
                })
            }
        })
        put("source", JSONObject().apply {
            put("format", timeline.source.format)
            put("name", timeline.source.name ?: "")
            put("origin", origin.name)
        })
    }

    /** Reads `source.origin` straight from a stored song file. */
    private fun parseOriginFromSong(text: String): SongOrigin =
        runCatching {
            parseOrigin(JSONObject(text).optJSONObject("source")?.optString("origin").orEmpty())
        }.getOrDefault(SongOrigin.USER)

    private fun parseOrigin(raw: String?): SongOrigin =
        if (raw.equals(SongOrigin.BUNDLED.name, ignoreCase = true)) {
            SongOrigin.BUNDLED
        } else {
            SongOrigin.USER
        }

    // ── paths ────────────────────────────────────────────────────────────

    private fun songFile(id: String) = File(rootDir, "${sanitize(id)}.json")

    private fun sanitize(id: String) =
        id.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "song" }

    private fun ensureDir() {
        if (!rootDir.isDirectory) rootDir.mkdirs()
    }

    companion object {
        const val DIR_NAME = "songs"
        const val INDEX_NAME = "index.json"
        const val INDEX_VERSION = 1
    }
}
