package com.skyautoplayer.importer

import com.skyautoplayer.domain.timeline.SongTimeline

sealed interface ImportResult {
    data class Success(val timeline: SongTimeline) : ImportResult
    data class Failure(val message: String, val position: Int? = null) : ImportResult
}

interface SongImporter {
    /**
     * True when this importer consumes JSON documents. The repository uses it
     * to pick a primary importer from the *content*, not the file extension.
     */
    val handlesJson: Boolean

    fun import(name: String, content: ByteArray): ImportResult
}
