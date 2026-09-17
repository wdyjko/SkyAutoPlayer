package com.skyautoplayer.importer

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Documents and locks down every accepted sheet shape, so the answer to
 * "is my format OK?" is provable rather than assumed.
 */
class AcceptedSheetShapesTest {

    private fun json(s: String) = SkyJsonImporter().import("t.json", s.trimIndent().toByteArray())
    private fun txt(s: String) = SkyTextImporter().import("t.txt", s.trimIndent().toByteArray())

    private fun count(r: ImportResult): Int =
        (r as? ImportResult.Success)?.timeline?.events?.size ?: -1

    // ── SkyStudio canonical ────────────────────────────────────────
    @Test fun songNotesWrapper() {
        val r = json("""{"name":"A","bpm":120,"songNotes":[{"time":200,"key":"1Key7"},{"time":400,"key":"1Key5"}]}""")
        assertTrue(count(r) == 2, "songNotes wrapper -> 2 events, got $r")
    }

    @Test fun topLevelArrayOfNotes() {
        val r = json("""[{"time":200,"key":"1Key7"},{"time":400,"key":"1Key5"}]""")
        assertTrue(count(r) == 2, "top-level array -> 2 events, got $r")
    }

    @Test fun nestedUnderData() {
        val r = json("""{"data":{"name":"B","songNotes":[{"time":0,"key":"1Key1"}]}}""")
        assertTrue(count(r) == 1, "nested under data -> 1 event, got $r")
    }

    // ── the shape the user asked about, as a STANDALONE file ───────
    @Test fun singleBareNoteObject() {
        val r = json("""{"time":200,"key":"1Key7"}""")
        assertTrue(count(r) == 1, "a lone note object -> 1 event, got $r")
    }

    // ── unified events (design doc) ────────────────────────────────
    @Test fun unifiedEventsWithKeysArray() {
        val r = json("""{"name":"C","events":[{"t":0,"keys":[0,2],"hold":100},{"t":250,"keys":[5]}]}""")
        assertTrue(count(r) == 2, "events+keys[] -> 2 events, got $r")
    }

    // ── alternate key spellings ────────────────────────────────────
    @Test fun alternateKeySpellings() {
        for (key in listOf("\"1Key7\"", "\"Key7\"", "\"2Key7\"", "\"R2C3\"", "7")) {
            val r = json("""{"songNotes":[{"time":0,"key":$key}]}""")
            assertTrue(count(r) == 1, "key=$key should parse, got $r")
        }
    }

    @Test fun outOfRangeKeyIsSkippedNotFatal() {
        val r = json("""{"songNotes":[{"time":0,"key":"1Key7"},{"time":10,"key":"1Key99"},{"time":20,"key":"1Key3"}]}""")
        assertTrue(count(r) == 2, "1Key99 skipped, other 2 kept, got $r")
    }

    // ── legacy ms fields ───────────────────────────────────────────
    @Test fun legacyFieldNames() {
        val r = json("""{"notes":[{"at":500,"note":3,"duration":80}]}""")
        assertTrue(count(r) == 1, "at/note/duration -> 1 event, got $r")
    }

    // ── txt: jianpu ────────────────────────────────────────────────
    @Test fun jianpuTxt() {
        assertTrue(count(txt("1 2 3 / -1 0 // +1")) > 0, "jianpu txt must parse")
    }

    @Test fun jianpuChordAndHeader() {
        val r = txt("// title: Demo\n// bpm: 90\n1,3,5 / 2,5")
        assertTrue(count(r) == 2, "jianpu chords -> 2 events, got $r")
        assertTrue((r as ImportResult.Success).timeline.title == "Demo", "title header honoured")
    }

    // ── txt: legacy timeline ───────────────────────────────────────
    @Test fun legacyTimelineTxt() {
        val r = txt("1000 0 70\n1300 1+3 70")
        assertTrue(count(r) == 2, "legacy txt -> 2 events, got $r")
    }
}
