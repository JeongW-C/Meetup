package com.mdreader.core

/**
 * Turns Markdown into plain paragraphs that read well through text-to-speech.
 *
 * Block structure (headings, list items, table rows) becomes separate paragraphs,
 * markup characters are removed, and content that makes no sense spoken aloud
 * (fenced code blocks, images, URLs, HTML tags) is dropped.
 */
object MarkdownSpeech {

    private val FENCE = Regex("^\\s{0,3}(`{3,}|~{3,})")
    private val BLOCKQUOTE = Regex("^\\s{0,3}(>\\s?)+")
    private val THEMATIC_BREAK = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val SETEXT_UNDERLINE = Regex("^\\s{0,3}(=+|-+)\\s*$")
    private val ATX_HEADING = Regex("^\\s{0,3}#{1,6}(?:\\s+(.*?))?(?:\\s+#+)?\\s*$")
    private val LIST_ITEM = Regex("^\\s*(?:[-*+]|\\d{1,9}[.)])\\s+(?:\\[[ xX]]\\s+)?(.*)$")
    private val REFERENCE_DEFINITION = Regex("^\\s{0,3}\\[[^\\]]+]:\\s*\\S+.*$")
    private val TABLE_SEPARATOR = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)+\\|?\\s*$")

    private val ESCAPE = Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!|>~<])")
    private val CODE_SPAN = Regex("(`+)(.+?)\\1")
    private val HTML_COMMENT = Regex("<!--.*?-->")
    private val IMAGE = Regex("!\\[[^\\]]*]\\([^)]*\\)|!\\[[^\\]]*]\\[[^\\]]*]")
    private val INLINE_LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val REFERENCE_LINK = Regex("\\[([^\\]]+)]\\[[^\\]]*]")
    private val AUTOLINK = Regex("<(?:https?|ftp|mailto):[^>\\s]*>")
    private val HTML_TAG = Regex("</?[a-zA-Z][^>]*>")
    private val BARE_URL = Regex("(?:https?|ftp)://\\S+|www\\.\\S+\\.\\S+")
    private val STRONG_EMPHASIS = Regex("(\\*{1,3})(\\S(?:.*?\\S)?)\\1")
    private val UNDERSCORE_EMPHASIS = Regex("(?<![A-Za-z0-9])(_{1,3})(\\S(?:.*?\\S)?)\\1(?![A-Za-z0-9])")
    private val STRIKETHROUGH = Regex("~~(.+?)~~")
    private val WHITESPACE = Regex("\\s+")

    private const val ESCAPED_CHARS = "\\`*_{}[]()#+-.!|>~<"
    private const val ESCAPE_BASE = ''
    private const val CODE_BASE = ''

    private val SENTENCE_END = ".!?。！？…:;"

    fun toParagraphs(markdown: String): List<String> {
        val text = stripFrontMatter(markdown.replace("\r\n", "\n").replace('\r', '\n'))
        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            val cleaned = cleanInline(current.toString())
            if (cleaned.isNotEmpty()) paragraphs += cleaned
            current.setLength(0)
        }

        var fence: String? = null
        var inComment = false

        for (raw in text.split('\n')) {
            if (fence != null) {
                if (raw.trim().startsWith(fence)) fence = null
                continue
            }
            if (inComment) {
                val end = raw.indexOf("-->")
                if (end >= 0) inComment = false
                continue
            }

            val line = raw.replace(BLOCKQUOTE, "")
            val trimmed = line.trim()

            val fenceMatch = FENCE.find(line)
            if (fenceMatch != null) {
                flush()
                fence = fenceMatch.groupValues[1]
                continue
            }
            if (trimmed.startsWith("<!--") && !trimmed.contains("-->")) {
                flush()
                inComment = true
                continue
            }
            if (trimmed.isEmpty()) {
                flush()
                continue
            }
            if (current.isNotEmpty() && SETEXT_UNDERLINE.matches(line)) {
                val heading = cleanInline(current.toString())
                current.setLength(0)
                if (heading.isNotEmpty()) paragraphs += endSentence(heading)
                continue
            }
            if (THEMATIC_BREAK.matches(line) || REFERENCE_DEFINITION.matches(line) ||
                TABLE_SEPARATOR.matches(line)
            ) {
                flush()
                continue
            }
            if (trimmed.startsWith("|") && trimmed.count { it == '|' } >= 2) {
                flush()
                val row = tableRow(trimmed)
                if (row.isNotEmpty()) paragraphs += row
                continue
            }
            val heading = ATX_HEADING.matchEntire(line)
            if (heading != null) {
                flush()
                val title = cleanInline(heading.groupValues[1])
                if (title.isNotEmpty()) paragraphs += endSentence(title)
                continue
            }
            val item = LIST_ITEM.matchEntire(line)
            if (item != null) {
                flush()
                current.append(item.groupValues[1])
                continue
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(trimmed)
        }
        flush()
        return paragraphs
    }

    /** Removes inline Markdown and HTML from a single paragraph of text. */
    fun cleanInline(input: String): String {
        var s = input.replace(ESCAPE) { (ESCAPE_BASE + ESCAPED_CHARS.indexOf(it.groupValues[1][0])).toString() }

        val codeSpans = mutableListOf<String>()
        s = s.replace(CODE_SPAN) {
            codeSpans += it.groupValues[2].trim()
            (CODE_BASE + (codeSpans.size - 1)).toString()
        }

        s = s.replace(HTML_COMMENT, "")
            .replace(IMAGE, "")
            .replace(INLINE_LINK, "$1")
            .replace(REFERENCE_LINK, "$1")
            .replace(AUTOLINK, "")
            .replace(HTML_TAG, "")
            .replace(BARE_URL, "")
        // Repeat so nested emphasis such as ***bold _and_ italic*** is fully unwrapped.
        repeat(3) {
            s = s.replace(STRONG_EMPHASIS, "$2")
                .replace(UNDERSCORE_EMPHASIS, "$2")
                .replace(STRIKETHROUGH, "$1")
        }
        s = s.replace("*", "")
        s = decodeEntities(s)

        val restored = StringBuilder(s.length)
        for (c in s) {
            when {
                c >= CODE_BASE && c < CODE_BASE + codeSpans.size -> restored.append(codeSpans[c - CODE_BASE])
                c >= ESCAPE_BASE && c < ESCAPE_BASE + ESCAPED_CHARS.length -> restored.append(ESCAPED_CHARS[c - ESCAPE_BASE])
                else -> restored.append(c)
            }
        }
        return restored.toString().replace(WHITESPACE, " ").trim()
    }

    private fun tableRow(line: String): String {
        val body = line.trim().removePrefix("|").removeSuffix("|")
        return body.split('|')
            .map { cleanInline(it) }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
    }

    private fun endSentence(text: String): String =
        if (text.last() in SENTENCE_END) text else "$text."

    private fun stripFrontMatter(text: String): String {
        if (!text.startsWith("---\n")) return text
        val end = text.indexOf("\n---", startIndex = 4)
        if (end < 0) return text
        val after = text.indexOf('\n', end + 4)
        return if (after < 0) "" else text.substring(after + 1)
    }

    private fun decodeEntities(s: String): String =
        s.replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
}
