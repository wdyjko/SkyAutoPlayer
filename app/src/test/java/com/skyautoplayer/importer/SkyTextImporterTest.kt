package com.skyautoplayer.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SkyTextImporterTest {
    @Test
    fun `txt song creates a non-empty timeline`() {
        val result = SkyTextImporter().import("test-song.txt", "1000 0+2 70\n1300 1 70".toByteArray())
        val success = assertIs<ImportResult.Success>(result)
        assertEquals("sky-text", success.timeline.source.format)
        assertEquals(2, success.timeline.events.size)
        assertEquals(setOf(0, 2), success.timeline.events.first().keys)
    }
}
