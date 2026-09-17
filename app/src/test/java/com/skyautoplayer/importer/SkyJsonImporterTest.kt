package com.skyautoplayer.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkyJsonImporterTest {
    @Test fun groupsSameTimestampAndConvertsMilliseconds() {
        val result = SkyJsonImporter().import("song.json", """{"name":"demo","notes":[{"time":10,"key":1,"duration":50},{"time":10,"key":2,"duration":80}]}""".toByteArray())
        assertTrue(result is ImportResult.Success)
        val event = (result as ImportResult.Success).timeline.events.single()
        assertEquals(10_000L, event.atUs)
        assertEquals(setOf(1, 2), event.keys)
        assertEquals(80_000L, event.holdUs)
    }
}
