package com.skyautoplayer.importer

import com.skyautoplayer.domain.timeline.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Imports SkyStudio exports.
 *
 * The canonical SkyStudio document is:
 * ```
 * { "name": "Song", "bpm": 120, "songNotes": [ { "time": 948, "key": "1Key0" } ] }
 * ```
 * so we must read `songNotes` (not `notes`) and accept keys that are *strings*
 * like `"1Key0"` as well as bare integers. Getting either wrong yields an empty
 * timeline and the UI reports "曲谱没有可播放事件".
 */
class SkyJsonImporter : SongImporter {

    override val handlesJson: Boolean = true

    override fun import(name: String, content: ByteArray): ImportResult = try {
        val text = SheetText.decode(content).trim()
        val root: Any = if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
        val doc = unwrap(root)
        val title = doc.optString("name", "").ifBlank { doc.optString("songName", "").ifBlank { name } }

        val picked = sequenceOf("songNotes", "notes", "events", "sheets")
            .mapNotNull { doc.optJSONArray(it) }
            .firstOrNull { it.length() > 0 }

        // A file holding a single note object ({ "time":200, "key":"1Key7" })
        // has no wrapping array; treat the document itself as a one-note sheet.
        val sourceEvents = picked ?: when {
            doc.has("key") || doc.has("keys") || doc.has("note") -> JSONArray().put(doc)
            else -> JSONArray()
        }

        // at(us) -> key -> hold(us); same timestamp folds into a chord.
        val grouped = sortedMapOf<Long, MutableMap<Int, Long>>()
        var skipped = 0
        for (i in 0 until sourceEvents.length()) {
            val item = sourceEvents.optJSONObject(i)
            if (item == null) {
                skipped++
                continue
            }
            val keys = parseKeys(item)
            if (keys.isEmpty()) {
                skipped++
                continue
            }

            val rawAt = item.optLong(
                "time",
                item.optLong("t", item.optLong("at", item.optLong("timestamp", 0L)))
            ).coerceAtLeast(0L)
            val at = if (item.has("atUs")) rawAt else rawAt * 1_000L

            val rawHold = item.optLong(
                "duration",
                item.optLong("hold", item.optLong("holdMs", 60L))
            ).coerceAtLeast(1L)
            val hold = if (item.has("holdUs")) rawHold else rawHold * 1_000L

            val slot = grouped.getOrPut(at) { mutableMapOf() }
            for (k in keys) slot[k] = maxOf(slot[k] ?: 0L, hold)
        }

        val events = grouped.map { (at, keys) ->
            TimelineEvent(at, keys.keys.toSet(), keys.values.maxOrNull() ?: 1L)
        }
        val duration = events.maxOfOrNull { it.atUs + it.holdUs } ?: 0L
        ImportResult.Success(
            SongTimeline(
                UUID.randomUUID().toString(),
                title,
                duration,
                events,
                SourceMetadata("sky-json", name)
                    .copy(importerVersion = if (skipped > 0) "1(skipped=$skipped)" else "1")
            )
        )
    } catch (e: Exception) {
        ImportResult.Failure("Invalid Sky JSON: ${e.message}")
    }

    /**
     * Keys for one note item. Handles both SkyStudio's single `"key"` and the
     * design doc's unified-events `"keys": [0, 2]` array.
     */
    private fun parseKeys(item: JSONObject): Set<Int> {
        item.optJSONArray("keys")?.let { arr ->
            return (0 until arr.length())
                .mapNotNull { parseKey(arr.opt(it)) }
                .filter { it in 0..14 }
                .toSet()
        }
        val single = parseKey(item.opt("key") ?: item.opt("note") ?: item.opt("k"))
        return if (single == null) emptySet() else setOf(single)
    }

    /** Accepts `1Key0`, `Key5`, `2Key14`, `R1C1`, `A1`, `5`, and raw integers. */
    private fun parseKey(raw: Any?): Int? = runCatching {
        when (raw) {
            null -> null
            is Int -> raw.takeIf { it in 0..14 }
            is Number -> raw.toInt().takeIf { it in 0..14 }
            is String -> parseKeyString(raw)
            else -> null
        }
    }.getOrNull()   // a malformed key must never abort the whole sheet

    private fun parseKeyString(raw: String): Int? {
        val t = raw.trim()
        if (t.isEmpty()) return null

        // "1Key5" / "Key5" / "2Key14"  (layer prefix is not a capture group)
        NK.find(t)?.let { m ->
            // Out-of-range values are dropped, never clamped: inventing key 14
            // from "1Key99" would play a note the author never wrote.
            return m.groupValues.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..14 }
        }
        // explicit row/column "R1C1"
        RC.find(t)?.let { m ->
            val row = m.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
            val col = m.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
            return ((row - 1) * 5 + (col - 1)).coerceIn(0, 14)
        }
        // letter+octave "A1".."G3"
        LETTER.find(t)?.let { m ->
            val letter = m.groupValues.getOrNull(1)?.firstOrNull()?.uppercaseChar() ?: return null
            val slot = LETTER_SLOT[letter] ?: return null
            val octave = m.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
            return ((octave - 1) * 5 + slot).coerceIn(0, 14)
        }
        // bare number
        return t.toIntOrNull()?.takeIf { it in 0..14 }
    }

    /** Peels `[{...}]`, `{"data":{...}}`, `{"song":{...}}` wrappers. */
    private fun unwrap(root: Any): JSONObject = when (root) {
        is JSONObject -> {
            val inner = sequenceOf("data", "song", "sheet", "payload")
                .mapNotNull { root.opt(it) }
                .firstOrNull { candidate ->
                    (candidate is JSONObject && candidate.has("songNotes")) ||
                        (candidate is JSONArray && candidate.length() > 0 && candidate.opt(0) is JSONObject)
                }
            if (inner != null) unwrap(inner) else root
        }
        is JSONArray -> {
            val first = root.opt(0)
            when {
                first is JSONObject && (first.has("songNotes") || first.has("name") || first.has("bpm")) -> first
                root.length() > 0 -> JSONObject().put("songNotes", root)
                else -> JSONObject()
            }
        }
        else -> JSONObject()
    }

    private companion object {
        val NK = Regex("^(?:\\d+)?Key(\\d{1,2})$", RegexOption.IGNORE_CASE)
        val RC = Regex("^R([1-3])C([1-5])$", RegexOption.IGNORE_CASE)
        val LETTER = Regex("^([A-Ga-g])([1-3])$")
        val LETTER_SLOT = mapOf('A' to 0, 'C' to 1, 'D' to 2, 'E' to 3, 'G' to 4, 'B' to 4, 'F' to 3)
    }
}
