package com.skyautoplayer.importer

import com.skyautoplayer.domain.timeline.*
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Imports text sheets. Two shapes are recognised, decided per line:
 *
 * 1. **Jianpu** (the format in the design doc and Zephyr-style sheets):
 *    `1 2 3 / 4 5 6 // 7 1` with `-1` low, `+1` high, `0` rest,
 *    `/` short rest, `//` long rest, `1,3,5` chord, `//title:`/`//bpm:` headers.
 * 2. **Legacy timeline** (kept for backward compatibility):
 *    `1000 0+2 70` = time(ms) keys(+ joined) hold(ms). Detected by a 3+ digit
 *    leading timestamp, which jianpu tokens can never produce.
 */
class SkyTextImporter : SongImporter {

    override val handlesJson: Boolean = false

    override fun import(name: String, content: ByteArray): ImportResult = try {
        val raw = SheetText.decode(content)
        val lines = raw.lines()

        var title = name
        var bpm = 120.0
        for (line in lines) {
            val t = line.trim()
            TITLE.find(t)?.let { title = it.groupValues[1].trim().ifBlank { title } }
            BPM.find(t)?.let { it.groupValues[1].toDoubleOrNull()?.let { v -> if (v > 0) bpm = v } }
        }

        val stepMs = (60_000.0 / bpm / 4.0).toLong().coerceAtLeast(20L)
        val events = mutableListOf<TimelineEvent>()
        var t = 0L

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (TITLE.containsMatchIn(trimmed) || BPM.containsMatchIn(trimmed)) continue

            val legacy = parseLegacyLine(trimmed)
            if (legacy != null) {
                events += legacy
                continue
            }
            if (LEGACY_SHAPE.matches(trimmed)) continue   // legacy-shaped but unusable

            for (tok in trimmed.split(Regex("\\s+"))) {
                when {
                    tok == "/" -> t += SHORT_REST_MS
                    tok == "//" -> t += LONG_REST_MS
                    tok.startsWith("//") -> Unit            // section marker
                    tok.startsWith("#") -> break
                    else -> {
                        val parts = tok.split(",").filter { it.isNotBlank() }
                        val keys = parts.mapNotNull { jianpuToKey(it) }.filter { it in 0..14 }.toSet()
                        val isRest = parts.isNotEmpty() && parts.all { it.trim() == "0" }
                        when {
                            keys.isNotEmpty() -> {
                                events += TimelineEvent(t * 1_000L, keys, HOLD_MS * 1_000L)
                                t += stepMs
                            }
                            isRest -> t += stepMs        // rest: advance time, emit nothing
                            else -> Unit                 // unparseable: ignore without advancing
                        }
                    }
                }
            }
        }

        val sorted = events.sortedBy { it.atUs }
        val duration = sorted.maxOfOrNull { it.atUs + it.holdUs } ?: 0L
        ImportResult.Success(
            SongTimeline(
                UUID.randomUUID().toString(),
                title,
                duration,
                sorted,
                SourceMetadata("sky-text", name)
            )
        )
    } catch (e: Exception) {
        ImportResult.Failure("Invalid Sky text: ${e.message}")
    }

    /** `1000 0+2 70` / `1000,0+2,70` (3+ digit time disambiguates from jianpu). */
    private fun parseLegacyLine(line: String): TimelineEvent? {
        val parts = line.split(Regex("[,\\s]+")).filter { it.isNotBlank() }
        if (parts.size !in 2..3) return null
        val time = parts[0]
        if (time.length < 3 || !time.all { it.isDigit() }) return null
        if (!parts[1].matches(Regex("\\d+(\\+\\d+)?"))) return null
        val at = time.toLongOrNull() ?: return null
        val keys = parts[1].split("+").mapNotNull { it.toIntOrNull() }.filter { it in 0..14 }.toSet()
        if (keys.isEmpty()) return null
        val holdMs = parts.getOrNull(2)?.toLongOrNull()?.coerceAtLeast(1L) ?: HOLD_MS
        return TimelineEvent(at * 1_000L, keys, holdMs * 1_000L)
    }

    /** `1`->5, `-1`->0, `+1`->10, `0`->null(rest); degrees 4/7 snap to pentatonic. */
    private fun jianpuToKey(token: String): Int? {
        var body = token.trim()
        if (body.isEmpty()) return null
        var octave = 1
        while (body.startsWith("-") || body.startsWith("_")) { octave--; body = body.substring(1) }
        while (body.startsWith("+") || body.startsWith("^")) { octave++; body = body.substring(1) }
        while (body.endsWith(".")) { octave++; body = body.dropLast(1) }
        val degree = body.firstOrNull()?.digitToIntOrNull() ?: return null
        if (degree == 0) return null
        val slot = DEGREE_SLOT[degree] ?: return null
        return (octave.coerceIn(0, 2) * 5 + slot)
    }

    private companion object {
        const val HOLD_MS = 100L
        const val SHORT_REST_MS = 200L
        const val LONG_REST_MS = 500L
        val TITLE = Regex("^(?://|#)\\s*(?:title|曲名|name)\\s*[:：]\\s*(.+)$", RegexOption.IGNORE_CASE)
        val BPM = Regex("^(?://|#)\\s*bpm\\s*[:：]\\s*(\\d+(?:\\.\\d+)?)\\s*$", RegexOption.IGNORE_CASE)
        val LEGACY_SHAPE = Regex("^\\d{3,}[,\\s]+\\d+(\\+\\d+)*([,\\s]+\\d+)?$")
        val DEGREE_SLOT = mapOf(1 to 0, 2 to 1, 3 to 2, 4 to 2, 5 to 3, 6 to 4, 7 to 4)
    }
}
