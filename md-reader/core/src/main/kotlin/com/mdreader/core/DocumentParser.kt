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
    private val EXTENSION = Regex("[A-Za-z0-9]{1,8}")

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

    /**
     * The file name without its extension. Google Docs have no extension but often a dot in the name
     * ("12장. 결정 (수진)"), so only a short run of letters/digits after the last dot counts as one.
     */
    fun titleOf(fileName: String): String {
        val base = fileName.substringAfterLast('/').trim()
        val dot = base.lastIndexOf('.')
        val hasExtension = dot > 0 && EXTENSION.matches(base.substring(dot + 1))
        return (if (hasExtension) base.substring(0, dot) else base).trim().ifBlank { "문서" }
    }
}
