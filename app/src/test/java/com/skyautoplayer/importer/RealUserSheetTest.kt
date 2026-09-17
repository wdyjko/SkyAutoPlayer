package com.skyautoplayer.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real sheet supplied by the user: `龙卷风.txt`.
 *
 * It is a SkyStudio export that is **UTF-16LE with a BOM** and whose payload is
 * **JSON**, despite the `.txt` extension. Extension-based dispatch sent it to
 * the plain-text parser, producing zero events and the "曲谱没有可播放事件"
 * error. These tests pin the content-sniffing behaviour that fixes it.
 */
class RealUserSheetTest {

    private val name = "longjuanfeng_utf16.txt"

    private fun bytes(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("sheets/$name")?.readBytes()
            ?: error("missing test resource sheets/$name")

    private val importers = listOf(SkyJsonImporter(), SkyTextImporter())

    @Test
    fun fileIsDetectedAsJsonDespiteTxtExtension() {
        assertTrue(SheetText.looksLikeJson(bytes()), "UTF-16 JSON payload must sniff as JSON")
    }

    @Test
    fun utf16BomDecodesToReadableJson() {
        val text = SheetText.decode(bytes())
        assertTrue(text.trimStart().startsWith("["), "top level is a JSON array")
        assertTrue(text.contains("songNotes"), "must expose songNotes")
        assertTrue(text.contains("龙卷风"), "Chinese title must survive decoding")
    }

    @Test
    fun dispatcherPicksJsonForTxtFile() {
        val result = ImporterDispatcher.dispatch(importers, name, bytes())
        assertTrue(result is ImportResult.Success, "dispatch must succeed, got $result")
        val timeline = (result as ImportResult.Success).timeline
        assertEquals("龙卷风", timeline.title)
        assertEquals(232, timeline.events.size, "232 songNotes -> 232 events")
    }

    @Test
    fun parsedTimelineIsPlayable() {
        val timeline = (ImporterDispatcher.dispatch(importers, name, bytes()) as ImportResult.Success).timeline
        assertTrue(timeline.events.isNotEmpty())
        assertTrue(timeline.events.all { it.keys.isNotEmpty() }, "no empty-key events")
        assertTrue(timeline.events.all { ev -> ev.keys.all { it in 0..14 } }, "keys within 0..14")
        assertTrue(timeline.events.all { it.holdUs > 0 }, "hold must be positive")
        assertEquals(timeline.events.sortedBy { it.atUs }, timeline.events, "events sorted")
        assertTrue(timeline.durationUs >= 92_000_000L, "last note is at 92s")
    }

    @Test
    fun textImporterAloneWouldFindNothing() {
        // Documents exactly why content sniffing is required.
        val result = SkyTextImporter().import(name, bytes())
        val events = (result as? ImportResult.Success)?.timeline?.events.orEmpty()
        assertTrue(events.isEmpty(), "plain-text parser cannot read a JSON payload")
    }
}
