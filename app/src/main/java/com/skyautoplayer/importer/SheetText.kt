package com.skyautoplayer.importer

import java.nio.charset.StandardCharsets

/**
 * Byte-level helpers shared by the importers and the repository dispatcher.
 *
 * SkyStudio exports UTF-16LE with a BOM, and the community frequently shares
 * those JSON payloads with a `.txt` extension. So neither the extension nor a
 * naive UTF-8 read can be trusted - always decode through here and sniff the
 * decoded content.
 */
object SheetText {

    fun decode(bytes: ByteArray): String = when {
        bytes.startsWith(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        bytes.startsWith(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        bytes.startsWith(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        else -> String(bytes, StandardCharsets.UTF_8)
    }.removePrefix("\uFEFF")

    /** True when the payload is a JSON document (`{...}` or `[...]`), whatever the extension. */
    fun looksLikeJson(bytes: ByteArray): Boolean {
        val head = decode(bytes).trimStart()
        return head.startsWith("{") || head.startsWith("[")
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
}
