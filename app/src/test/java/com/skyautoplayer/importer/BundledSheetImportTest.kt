package com.skyautoplayer.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Imports the *actual* bundled sheet files (copied into src/test/resources)
 * so the exact files shipped to the device are covered by CI.
 */
class BundledSheetImportTest {

    private fun load(path: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(path)?.readBytes()
            ?: error("missing test resource: $path")

    @Test
    fun realTwinkleJsonImportsAllNotes() {
        val result = SkyJsonImporter().import("twinkle.json", load("sheets/twinkle.json"))
        assertTrue(result is ImportResult.Success, "got $result")
        val timeline = (result as ImportResult.Success).timeline
        assertEquals("Twinkle Twinkle (demo)", timeline.title)
        assertTrue(timeline.events.isNotEmpty(), "twinkle.json must yield events")
        assertEquals(42, timeline.events.size, "twinkle.json has 42 songNotes")
        assertTrue(timeline.events.all { it.keys.isNotEmpty() }, "no event may have empty keys")
        assertTrue(timeline.events.all { it.keys.all { k -> k in 0..14 } }, "all keys in 0..14")
        assertEquals(timeline.events.sortedBy { it.atUs }, timeline.events, "events must be sorted")
    }

    @Test
    fun realChordDemoJsonImportsChords() {
        val result = SkyJsonImporter().import("chord_demo.json", load("sheets/chord_demo.json"))
        assertTrue(result is ImportResult.Success, "got $result")
        val events = (result as ImportResult.Success).timeline.events
        assertEquals(7, events.size)
        assertEquals(setOf(0, 2, 4), events[0].keys, "first event is a 3-note chord")
    }

    @Test
    fun realScaleTxtJianpuImports() {
        val result = SkyTextImporter().import("scale.txt", load("sheets/scale.txt"))
        assertTrue(result is ImportResult.Success, "got $result")
        val timeline = (result as ImportResult.Success).timeline
        assertTrue(timeline.events.isNotEmpty(), "scale.txt must yield events")
        // low octave 1 2 3 5 6 -> keys 0..4
        assertEquals(setOf(0), timeline.events[0].keys)
        assertEquals(setOf(1), timeline.events[1].keys)
        // header "// bpm: 90" is honoured
        assertEquals("十五键音阶", timeline.title)
    }

    @Test
    fun legacyTimelineTxtStillWorks() {
        val legacy = "1000 0 70\n1300 1+3 70\n1600 2 70".toByteArray()
        val result = SkyTextImporter().import("legacy.txt", legacy)
        assertTrue(result is ImportResult.Success, "got $result")
        val events = (result as ImportResult.Success).timeline.events
        assertEquals(3, events.size)
        assertEquals(setOf(0), events[0].keys)
        assertEquals(setOf(1, 3), events[1].keys)
        assertEquals(1_300_000L, events[1].atUs)
    }
}
