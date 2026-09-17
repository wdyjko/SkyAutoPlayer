package com.skyautoplayer.application

import com.skyautoplayer.storage.SongEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single source of truth for "which song is selected".
 *
 * [com.skyautoplayer.domain.playback.PlaybackState] carries only a title, and the
 * library contains 21 groups of duplicate titles (e.g. `Flower Dance`,
 * `My heart will go on`). Highlighting and prev/next therefore key off
 * [currentIndex] / [SongEntry.id] and never off a title.
 */
object SongQueue {

    private val _songs = MutableStateFlow<List<SongEntry>>(emptyList())
    val songs: StateFlow<List<SongEntry>> = _songs

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex

    /** Switching songs starts playback by default. */
    var autoPlayOnSwitch: Boolean = true

    fun setSongs(list: List<SongEntry>) {
        _songs.value = list
        if (_currentIndex.value >= list.size) _currentIndex.value = -1
    }

    /** Reload from the store, keeping the current selection if it still exists. */
    fun refresh() {
        val currentId = current()?.id
        val list = PlaybackRuntime.songs.list()
        setSongs(list)
        if (currentId != null) {
            val idx = list.indexOfFirst { it.id == currentId }
            if (idx >= 0) _currentIndex.value = idx
        }
    }

    fun current(): SongEntry? = _songs.value.getOrNull(_currentIndex.value)

    fun select(index: Int): SongEntry? {
        val list = _songs.value
        if (index !in list.indices) return null
        _currentIndex.value = index
        return list[index]
    }

    fun next(): SongEntry? {
        val list = _songs.value
        if (list.isEmpty()) return null
        val cur = _currentIndex.value
        return select(if (cur < 0) 0 else (cur + 1) % list.size)
    }

    fun prev(): SongEntry? {
        val list = _songs.value
        if (list.isEmpty()) return null
        val cur = _currentIndex.value
        return select(if (cur < 0) list.size - 1 else (cur - 1 + list.size) % list.size)
    }

    /**
     * Load the song at [index] and optionally start it.
     *
     * `stop()` first on purpose: `Play` and `Resume` share one engine branch and
     * would otherwise continue from the previous position instead of restarting.
     */
    fun loadAndPlay(index: Int, autoPlay: Boolean = autoPlayOnSwitch): Boolean {
        val entry = select(index) ?: return false
        val timeline = PlaybackRuntime.songs.load(entry.id) ?: return false
        PlaybackRuntime.playback.stop()
        PlaybackRuntime.playback.load(timeline)
        if (autoPlay) PlaybackRuntime.playback.play()
        return true
    }

    fun loadAndPlay(entry: SongEntry, autoPlay: Boolean = autoPlayOnSwitch): Boolean {
        val index = _songs.value.indexOfFirst { it.id == entry.id }
        return if (index >= 0) loadAndPlay(index, autoPlay) else false
    }

    /**
     * Load without playing (W8.4).
     *
     * The overlay uses this when the user picks a row in the panel: playback
     * starts only when ▶ is pressed, so a song's t=0 note can never land on a
     * retracting IME or panel.
     */
    fun loadOnly(index: Int): Boolean = loadAndPlay(index, autoPlay = false)

    /** Drop the given ids from the in-memory queue (used after a batch delete). */
    fun removeIds(ids: Set<String>) {
        if (ids.isEmpty()) return
        val currentId = current()?.id
        val remaining = _songs.value.filterNot { it.id in ids }
        _songs.value = remaining
        _currentIndex.value = when {
            currentId == null -> -1
            currentId in ids -> -1
            else -> remaining.indexOfFirst { it.id == currentId }
        }
    }

    fun setCurrentById(id: String?) {
        _currentIndex.value = if (id == null) -1 else _songs.value.indexOfFirst { it.id == id }
    }

    fun clear() {
        _songs.value = emptyList()
        _currentIndex.value = -1
    }
}
