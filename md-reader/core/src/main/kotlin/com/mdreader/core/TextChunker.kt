package com.mdreader.core

/**
 * Splits text into pieces short enough for a single TextToSpeech.speak() call,
 * preferring sentence boundaries, then spaces, and cutting mid-word only as a last resort.
 */
object TextChunker {

    private val SENTENCE_BREAK = Regex("(?<=[.!?。！？…])\\s+")

    fun split(text: String, maxLength: Int): List<String> {
        require(maxLength > 0) { "maxLength must be positive" }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.length <= maxLength) return listOf(trimmed)

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in trimmed.split(SENTENCE_BREAK)) {
            for (piece in splitLong(sentence, maxLength)) {
                if (current.isNotEmpty() && current.length + 1 + piece.length > maxLength) {
                    chunks += current.toString()
                    current.setLength(0)
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(piece)
            }
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    private fun splitLong(sentence: String, maxLength: Int): List<String> {
        if (sentence.length <= maxLength) return listOf(sentence)
        val pieces = mutableListOf<String>()
        var rest = sentence
        while (rest.length > maxLength) {
            val space = rest.lastIndexOf(' ', maxLength)
            val cut = if (space > 0) space else maxLength
            pieces += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) pieces += rest
        return pieces
    }
}
