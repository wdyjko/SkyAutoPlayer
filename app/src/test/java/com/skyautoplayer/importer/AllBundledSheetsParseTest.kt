package com.skyautoplayer.importer

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Sweeps every sheet in src/test/resources/sheets and requires each to import
 * with at least one playable event **through the real dispatcher**, so the
 * content-sniffing path is exercised too. Adding a broken sheet to that folder
 * fails the build instead of failing silently on a phone.
 */
class AllBundledSheetsParseTest {

    private val importers = listOf(SkyJsonImporter(), SkyTextImporter())

    private fun resourceNames(): List<String> {
        val url = javaClass.classLoader!!.getResource("sheets") ?: error("no sheets/ test resource")
        val dir = java.io.File(url.toURI())
        return dir.listFiles().orEmpty()
            .filter { it.isFile && (it.extension == "json" || it.extension == "txt") }
            .map { it.name }
            .sorted()
    }

    @Test
    fun everyBundledSheetParsesToPlayableEvents() {
        val names = resourceNames()
        assertTrue(names.isNotEmpty(), "expected sheet resources")

        val failures = mutableListOf<String>()
        for (name in names) {
            val bytes = javaClass.classLoader!!.getResourceAsStream("sheets/$name")!!.readBytes()
            when (val result = ImporterDispatcher.dispatch(importers, name, bytes)) {
                is ImportResult.Success -> {
                    val events = result.timeline.events
                    if (events.isEmpty()) failures += "$name -> 0 events (would report 曲谱没有可播放事件)"
                    if (events.any { it.keys.isEmpty() }) failures += "$name -> event with empty keys"
                    if (events.any { it.keys.any { k -> k !in 0..14 } }) failures += "$name -> key out of 0..14"
                    if (events.any { it.holdUs <= 0 }) failures += "$name -> non-positive hold"
                    if (events != events.sortedBy { it.atUs }) failures += "$name -> events not sorted"
                    if (result.timeline.title.isBlank()) failures += "$name -> blank title"
                }
                is ImportResult.Failure -> failures += "$name -> Failure: ${result.message}"
            }
        }
        assertTrue(failures.isEmpty(), "sheet import problems:\n" + failures.joinToString("\n"))
    }
}
