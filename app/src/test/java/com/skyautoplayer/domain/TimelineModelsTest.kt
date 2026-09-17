package com.skyautoplayer.domain

import com.skyautoplayer.domain.timeline.*
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineModelsTest {
    @Test fun preservesChordAndHold() {
        val event = TimelineEvent(1_000, setOf(0, 4, 14), 80_000)
        val timeline = SongTimeline("id", "song", 2_000_000, listOf(event))
        assertEquals(setOf(0, 4, 14), timeline.events.single().keys)
        assertEquals(80_000, timeline.events.single().holdUs)
    }
}
