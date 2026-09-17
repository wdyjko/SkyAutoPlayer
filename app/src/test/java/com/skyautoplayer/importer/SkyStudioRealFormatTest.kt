package com.skyautoplayer.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests using the REAL SkyStudio export shape:
 *   { "name": ..., "bpm": 120, "songNotes": [ { "time": 500, "key": "1Key5" } ] }
 *
 * The pre-existing test only used a synthetic {"notes":[{"key":1}]} document
 * (integer key + "notes" field), which is why the app shipped broken for real
 * SkyStudio files and reported "曲谱没有可播放事件".
 */
class SkyStudioRealFormatTest {

    private val twinkle = """
        {
          "name": "Twinkle Twinkle (demo)",
          "author": "built-in (public domain melody)",
          "bpm": 120,
          "pitchLevel": 0,
          "songNotes": [
            {"time": 0, "key": "1Key5"},
            {"time": 500, "key": "1Key5"},
            {"time": 1000, "key": "1Key9"},
            {"time": 1500, "key": "1Key9"},
            {"time": 2000, "key": "1Key10"}
          ]
        }
    """.trimIndent()

    @Test
    fun realSkyStudioSongNotesProduceEvents() {
        val result = SkyJsonImporter().import("twinkle.json", twinkle.toByteArray())
        assertTrue(result is ImportResult.Success, "import should succeed, got $result")
        val timeline = (result as ImportResult.Success).timeline
        assertEquals("Twinkle Twinkle (demo)", timeline.title)
        assertEquals(5, timeline.events.size, "all 5 songNotes must become events")
        assertEquals(0L, timeline.events[0].atUs)
        assertEquals(setOf(5), timeline.events[0].keys)
        assertEquals(500_000L, timeline.events[1].atUs)
        assertEquals(setOf(9), timeline.events[2].keys)
        assertEquals(setOf(10), timeline.events[4].keys)
        assertTrue(timeline.durationUs > 0, "duration must be positive")
    }

    @Test
    fun skyStudioTimestampIsMilliseconds() {
        val result = SkyJsonImporter().import("s.json", twinkle.toByteArray()) as ImportResult.Success
        // 1500ms must land at 1_500_000us, not 1_500_000_000us
        assertEquals(1_500_000L, result.timeline.events[3].atUs)
    }

    @Test
    fun sameTimestampBecomesChordWithStringKeys() {
        val json = """{"name":"c","bpm":120,"songNotes":[
            {"time":948,"key":"1Key0"},
            {"time":948,"key":"1Key2"},
            {"time":1200,"key":"1Key7"}]}"""
        val result = SkyJsonImporter().import("c.json", json.toByteArray()) as ImportResult.Success
        assertEquals(2, result.timeline.events.size)
        assertEquals(setOf(0, 2), result.timeline.events[0].keys)
        // Timeline keeps absolute SkyStudio ms->us timing (existing contract).
        assertEquals(948_000L, result.timeline.events[0].atUs)
        assertEquals(setOf(7), result.timeline.events[1].keys)
    }

    @Test
    fun skipsUnparseableKeysButKeepsTheRest() {
        val json = """{"songNotes":[
            {"time":0,"key":"1Key5"},
            {"time":100,"key":"garbage"},
            {"time":200,"key":"1Key6"}]}"""
        val result = SkyJsonImporter().import("m.json", json.toByteArray())
        assertTrue(result is ImportResult.Success, "one bad key must not fail the whole sheet")
        assertEquals(2, (result as ImportResult.Success).timeline.events.size)
    }

    @Test
    fun jianpuTextProducesEvents() {
        val txt = """
            // title: 音阶
            1 2 3 / 0 // +1
        """.trimIndent()
        val result = SkyTextImporter().import("scale.txt", txt.toByteArray())
        assertTrue(result is ImportResult.Success, "jianpu import should succeed, got $result")
        val events = (result as ImportResult.Success).timeline.events
        assertTrue(events.isNotEmpty(), "jianpu text must produce events")
        assertEquals(setOf(5), events[0].keys, "'1' is mid-octave degree 1 -> key 5")
    }
}
