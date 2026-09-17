package com.skyautoplayer.importer

/**
 * Chooses which importer handles a payload.
 *
 * The decision is made from the **content**, not the file extension, because
 * SkyStudio sheets are routinely shared as `.txt` files whose payload is
 * UTF-16 JSON. Dispatching on the extension alone sent those to the plain-text
 * parser, which produced zero events and surfaced as "曲谱没有可播放事件".
 *
 * Kept free of Android types so it is directly unit-testable.
 */
object ImporterDispatcher {

    fun dispatch(importers: List<SongImporter>, displayName: String, bytes: ByteArray): ImportResult {
        if (importers.isEmpty()) return ImportResult.Failure("No importer configured")

        val looksJson = SheetText.looksLikeJson(bytes)
        val jsonImporter = importers.firstOrNull { it.handlesJson }
        val textImporter = importers.firstOrNull { !it.handlesJson }

        val primary = if (looksJson) jsonImporter else textImporter
        val fallback = if (looksJson) textImporter else jsonImporter
        val ordered = listOfNotNull(primary, fallback).distinct()

        var lastFailure: ImportResult.Failure? = null
        for (importer in ordered) {
            when (val result = importer.import(displayName, bytes)) {
                is ImportResult.Success ->
                    if (result.timeline.events.isEmpty()) {
                        // Parsed but useless. Fail at import time with a message
                        // the user can act on, instead of reporting "已就绪" and
                        // only failing later with "曲谱没有可播放事件".
                        lastFailure = ImportResult.Failure(
                            "曲谱里没有找到任何音符（格式可能不受支持）"
                        )
                    } else {
                        return result
                    }
                is ImportResult.Failure -> lastFailure = result
            }
        }
        return lastFailure ?: ImportResult.Failure("Unsupported song format: $displayName")
    }
}
