package com.mdreader.core

/** A document ready to be read aloud: each segment is spoken (and highlighted) as one unit. */
data class ReadableDocument(
    val title: String,
    val segments: List<String>,
)

object DocumentParser {

    /** Kept well under TextToSpeech.getMaxSpeechInputLength() (4000 on most engines). */
    const val MAX_SEGMENT_LENGTH = 1500

    private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd", "mkdn")
    private val BLANK_LINE = Regex("\\n\\s*\\n")
    private val WHITESPACE = Regex("\\s+")

    fun parse(fileName: String, bytes: ByteArray): ReadableDocument =
        parseText(fileName, TextDecoder.decode(bytes))

    fun parseText(fileName: String, text: String): ReadableDocument {
        val paragraphs = if (isMarkdown(fileName)) MarkdownSpeech.toParagraphs(text) else plainParagraphs(text)
        val segments = paragraphs.flatMap { TextChunker.split(it, MAX_SEGMENT_LENGTH) }
        return ReadableDocument(title = titleOf(fileName), segments = segments)
    }

    fun isMarkdown(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in MARKDOWN_EXTENSIONS

    /**
     * Paragraphs are separated by blank lines and their hard-wrapped lines are joined.
     * A file with no blank lines at all is treated as one paragraph per line.
     */
    fun plainParagraphs(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty()) return emptyList()
        val blocks = normalized.split(BLANK_LINE)
        val parts = if (blocks.size == 1) normalized.split('\n') else blocks
        return parts.map { it.replace(WHITESPACE, " ").trim() }.filter { it.isNotEmpty() }
    }

    private fun titleOf(fileName: String): String {
        val base = fileName.substringAfterLast('/')
        val dot = base.lastIndexOf('.')
        return (if (dot > 0) base.substring(0, dot) else base).ifBlank { "문서" }
    }
}
