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

    private val READABLE_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd", "mkdn", "txt", "text", "pdf")
    private val DIGITS = Regex("\\d+|\\D+")

    /** Whether a file found in a folder is one the reader can read aloud (by name, or by type when it has no extension). */
    fun isReadable(fileName: String, mimeType: String?): Boolean {
        if (fileName.startsWith(".")) return false
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension in READABLE_EXTENSIONS) return true
        return extension.isEmpty() && (
            mimeType?.startsWith("text/") == true ||
                mimeType == "application/pdf" ||
                mimeType == "application/vnd.google-apps.document"
            )
    }

    /** Orders names the way people number files: "2장" before "10장", ignoring case. */
    val naturalOrder: Comparator<String> = Comparator { a, b ->
        val left = DIGITS.findAll(a.lowercase()).map { it.value }.toList()
        val right = DIGITS.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]
            val y = right[i]
            val compared = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0')
                val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else {
                x.compareTo(y)
            }
            if (compared != 0) return@Comparator compared
        }
        left.size - right.size
    }
}
