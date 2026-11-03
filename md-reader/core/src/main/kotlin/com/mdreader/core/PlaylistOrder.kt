package com.mdreader.core

/** Pure list helpers behind the playlist, kept here so they can be unit tested without Android. */
object PlaylistOrder {

    /** Returns [list] with the element at [from] moved to [to]; out-of-range moves leave it unchanged. */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val result = list.toMutableList()
        result.add(to, result.removeAt(from))
        return result
    }

    /** The element [offset] places away from the first one matching [isCurrent], or null past either end. */
    fun <T> neighbor(list: List<T>, offset: Int, isCurrent: (T) -> Boolean): T? {
        val index = list.indexOfFirst(isCurrent)
        if (index < 0) return null
        return list.getOrNull(index + offset)
    }

    /** Stores a document's segments one per line, so a cached copy reads back exactly as parsed. */
    fun encodeSegments(segments: List<String>): String =
        segments.map { it.replace('\r', ' ').replace('\n', ' ').trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    fun decodeSegments(text: String): List<String> =
        text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
}
