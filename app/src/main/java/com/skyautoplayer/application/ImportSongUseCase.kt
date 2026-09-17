package com.skyautoplayer.application

import android.net.Uri
import com.skyautoplayer.importer.ImportResult
import com.skyautoplayer.storage.SongRepository

class ImportSongUseCase(private val repository: SongRepository) {
    fun import(uri: Uri, displayName: String): ImportResult = repository.import(uri, displayName)
}
