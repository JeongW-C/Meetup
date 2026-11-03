package com.mdreader.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistOrderTest {

    private val list = listOf("a", "b", "c", "d")

    @Test
    fun movesDown() {
        assertEquals(listOf("b", "c", "a", "d"), PlaylistOrder.move(list, 0, 2))
    }

    @Test
    fun movesUp() {
        assertEquals(listOf("a", "d", "b", "c"), PlaylistOrder.move(list, 3, 1))
    }

    @Test
    fun outOfRangeMoveIsIgnored() {
        assertEquals(list, PlaylistOrder.move(list, 0, 4))
        assertEquals(list, PlaylistOrder.move(list, -1, 2))
    }

    @Test
    fun findsNeighbors() {
        assertEquals("c", PlaylistOrder.neighbor(list, 1) { it == "b" })
        assertEquals("a", PlaylistOrder.neighbor(list, -1) { it == "b" })
        assertNull(PlaylistOrder.neighbor(list, 1) { it == "d" })
        assertNull(PlaylistOrder.neighbor(list, 1) { it == "x" })
    }

    @Test
    fun segmentsRoundTrip() {
        val segments = listOf("첫 문단.", "둘째\n문단", "  ", "셋째")
        assertEquals(listOf("첫 문단.", "둘째 문단", "셋째"), PlaylistOrder.decodeSegments(PlaylistOrder.encodeSegments(segments)))
    }

    @Test
    fun sortsNumbersNaturally() {
        val names = listOf("10장.md", "2장.md", "1장.md", "부록.txt", "B.txt", "a.txt")
        assertEquals(
            listOf("1장.md", "2장.md", "10장.md", "a.txt", "B.txt", "부록.txt"),
            names.sortedWith(PlaylistOrder.naturalOrder),
        )
    }

    @Test
    fun picksReadableFiles() {
        assertTrue(PlaylistOrder.isReadable("노트.MD", null))
        assertTrue(PlaylistOrder.isReadable("책.pdf", "application/pdf"))
        assertTrue(PlaylistOrder.isReadable("메모", "text/plain"))
        assertFalse(PlaylistOrder.isReadable("사진.jpg", "image/jpeg"))
        assertFalse(PlaylistOrder.isReadable(".숨김.md", null))
    }
}
