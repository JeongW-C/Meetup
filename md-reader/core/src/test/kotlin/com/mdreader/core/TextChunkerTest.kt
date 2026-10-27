package com.mdreader.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextChunkerTest {

    @Test
    fun shortTextIsOneChunk() {
        assertEquals(listOf("짧은 문장."), TextChunker.split("  짧은 문장.  ", 100))
    }

    @Test
    fun blankTextIsEmpty() {
        assertEquals(emptyList(), TextChunker.split("   ", 100))
    }

    @Test
    fun splitsAtSentenceBoundaries() {
        val text = "첫 번째 문장입니다. 두 번째 문장입니다! 세 번째 문장인가요? 네."
        assertEquals(
            listOf("첫 번째 문장입니다.", "두 번째 문장입니다!", "세 번째 문장인가요? 네."),
            TextChunker.split(text, 15),
        )
    }

    @Test
    fun packsSentencesUpToLimit() {
        val text = "가. 나. 다. 라."
        assertEquals(listOf("가. 나.", "다. 라."), TextChunker.split(text, 6))
    }

    @Test
    fun longSentenceSplitsAtSpaces() {
        val chunks = TextChunker.split("하나 둘 셋 넷 다섯 여섯 일곱", 7)
        assertEquals(listOf("하나 둘 셋", "넷 다섯 여섯", "일곱"), chunks)
    }

    @Test
    fun wordLongerThanLimitIsHardCut() {
        assertEquals(listOf("abcd", "efgh", "ij"), TextChunker.split("abcdefghij", 4))
    }

    @Test
    fun noChunkExceedsLimitAndNoTextIsLost() {
        val text = (1..400).joinToString(" ") { "문장$it 입니다." }
        val chunks = TextChunker.split(text, 120)
        assertTrue(chunks.all { it.length <= 120 })
        assertEquals(text, chunks.joinToString(" "))
    }
}
