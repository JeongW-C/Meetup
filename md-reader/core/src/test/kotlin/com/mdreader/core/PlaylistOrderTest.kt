package com.mdreader.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
