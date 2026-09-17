package com.skyautoplayer.domain.timeline

data class SongTimeline(
    val timelineId: String,
    val title: String,
    val durationUs: Long,
    val events: List<TimelineEvent>,
    val source: SourceMetadata = SourceMetadata(),
    val validation: ValidationSummary = ValidationSummary()
) {
    init {
        require(durationUs >= 0)
        require(events.zipWithNext().all { it.first.atUs <= it.second.atUs })
        require(events.all { it.atUs >= 0 && it.keys.isNotEmpty() && it.keys.all { key -> key in 0..14 } })
    }
}

data class TimelineEvent(
    val atUs: Long,
    val keys: Set<Int>,
    val holdUs: Long,
    val sourcePosition: Int? = null,
    val warnings: List<String> = emptyList()
) {
    init {
        require(atUs >= 0)
        require(keys.isNotEmpty() && keys.all { it in 0..14 })
        require(holdUs > 0)
    }
}

data class SourceMetadata(val format: String = "unknown", val name: String? = null, val importerVersion: String = "1")
data class ValidationSummary(val warnings: List<String> = emptyList(), val discardedEvents: Int = 0)

object TimelineValidator {
    fun validate(events: List<TimelineEvent>, durationUs: Long): List<String> {
        val errors = mutableListOf<String>()
        if (durationUs < 0) errors += "durationUs must be non-negative"
        if (events.zipWithNext().any { it.first.atUs > it.second.atUs }) errors += "events are not sorted"
        if (events.any { it.keys.any { key -> key !in 0..14 } }) errors += "key out of range"
        if (events.any { it.atUs + it.holdUs > durationUs }) errors += "event extends beyond duration"
        return errors
    }
}
