package com.mdreader.core

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentParserTest {

    private val korean = "안녕하세요. 똠방각하 뷁 테스트입니다."

    @Test
    fun decodesUtf8() {
        assertEquals(korean, TextDecoder.decode(korean.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun decodesUtf8WithBom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + korean.toByteArray(Charsets.UTF_8)
        assertEquals(korean, TextDecoder.decode(bytes))
    }

    @Test
    fun decodesUtf16WithBom() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + korean.toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + korean.toByteArray(Charsets.UTF_16BE)
        assertEquals(korean, TextDecoder.decode(le))
        assertEquals(korean, TextDecoder.decode(be))
    }

    @Test
    fun decodesCp949() {
        // "똠", "뷁" exist only in CP949, not in plain EUC-KR.
        val bytes = korean.toByteArray(Charset.forName("x-windows-949"))
        assertEquals(korean, TextDecoder.decode(bytes))
    }

    @Test
    fun decodesEucKr() {
        val text = "한글 텍스트 파일"
        assertEquals(text, TextDecoder.decode(text.toByteArray(Charset.forName("EUC-KR"))))
    }

    @Test
    fun detectsMarkdownByExtension() {
        assertTrue(DocumentParser.isMarkdown("notes.md"))
        assertTrue(DocumentParser.isMarkdown("README.Markdown"))
        assertFalse(DocumentParser.isMarkdown("notes.txt"))
        assertFalse(DocumentParser.isMarkdown("md"))
    }

    @Test
    fun markdownFileIsStripped() {
        val doc = DocumentParser.parseText("guide.md", "# 안내\n\n**중요한** 내용")
        assertEquals("guide", doc.title)
        assertEquals(listOf("안내.", "중요한 내용"), doc.segments)
    }

    @Test
    fun textFileKeepsMarkdownCharacters() {
        val doc = DocumentParser.parseText("memo.txt", "# 그대로\n\n*별표*")
        assertEquals(listOf("# 그대로", "*별표*"), doc.segments)
    }

    @Test
    fun plainTextJoinsWrappedLinesWithinParagraphs() {
        assertEquals(
            listOf("첫 줄 이어지는 줄", "둘째 문단"),
            DocumentParser.plainParagraphs("첫 줄\r\n이어지는 줄\r\n\r\n\r\n둘째 문단\r\n"),
        )
    }

    @Test
    fun plainTextWithoutBlankLinesIsOneParagraphPerLine() {
        assertEquals(listOf("하나", "둘", "셋"), DocumentParser.plainParagraphs("하나\n둘\n\n".trimEnd() + "\n셋"))
    }

    @Test
    fun longParagraphsAreChunked() {
        val long = (1..300).joinToString(" ") { "이것은 $it 번째 문장입니다." }
        val doc = DocumentParser.parseText("long.txt", long)
        assertTrue(doc.segments.size > 1)
        assertTrue(doc.segments.all { it.length <= DocumentParser.MAX_SEGMENT_LENGTH })
    }

    @Test
    fun titleFallsBackForNamesWithoutExtension() {
        assertEquals("README", DocumentParser.parseText("README", "x").title)
        assertEquals(".md", DocumentParser.parseText(".md", "x").title)
    }

    @Test
    fun parsesBytesEndToEnd() {
        val doc = DocumentParser.parse("a.md", "- 항목 하나\n- 항목 둘".toByteArray(Charset.forName("x-windows-949")))
        assertEquals(listOf("항목 하나", "항목 둘"), doc.segments)
    }
}
