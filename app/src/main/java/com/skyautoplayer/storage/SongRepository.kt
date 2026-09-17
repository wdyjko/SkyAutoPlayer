package com.skyautoplayer.storage

import android.content.ContentResolver
import android.net.Uri
import com.skyautoplayer.importer.ImportResult
import com.skyautoplayer.importer.ImporterDispatcher
import com.skyautoplayer.importer.SongImporter

/**
 * Reads the selected document and hands it to [ImporterDispatcher], which
 * picks an importer by content rather than by file extension.
 */
class SongRepository(
    private val resolver: ContentResolver,
    private val importers: List<SongImporter>
) {
    fun import(uri: Uri, displayName: String): ImportResult {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return ImportResult.Failure("Unable to read selected document")
        return ImporterDispatcher.dispatch(importers, displayName, bytes)
    }
}
