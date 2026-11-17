package com.example.webtoonviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TileLayoutTest {

    @Test
    fun fitsEachImageToViewWidth() {
        // 720 x 20000 이미지를 1080 폭 화면에 맞추면 1.5배 → 30000
        val layout = TileLayout(listOf(ImageSize(720, 20000)), viewWidth = 1080)
        assertEquals(30000, layout.totalHeight)
        assertEquals(30000, layout.imageHeights[0])
    }

    @Test
    fun tilesCoverWholeImageWithoutGapsOrOverlap() {
        val sizes = listOf(ImageSize(800, 12345), ImageSize(1600, 3001), ImageSize(1080, 77))
        val layout = TileLayout(sizes, viewWidth = 1080, targetTileHeight = 1000)
        var y = 0
        val srcCursor = IntArray(sizes.size)
        for (tile in layout.tiles) {
            assertEquals("화면 위치가 이어져야 함", y, tile.top)
            assertEquals("원본 위치가 이어져야 함", srcCursor[tile.imageIndex], tile.srcTop)
            assertTrue(tile.height > 0)
            assertTrue(tile.srcBottom > tile.srcTop)
            y += tile.height
            srcCursor[tile.imageIndex] = tile.srcBottom
        }
        assertEquals(layout.totalHeight, y)
        sizes.forEachIndexed { i, s -> assertEquals(s.height, srcCursor[i]) }
    }

    @Test
    fun imagesAreStackedInOrder() {
        val layout = TileLayout(listOf(ImageSize(1080, 500), ImageSize(540, 500)), viewWidth = 1080)
        assertEquals(0, layout.imageTops[0])
        assertEquals(500, layout.imageTops[1])
        assertEquals(1500, layout.totalHeight)
    }

    @Test
    fun tileHeightsStayNearTarget() {
        val layout = TileLayout(listOf(ImageSize(690, 60000)), viewWidth = 1440, targetTileHeight = 1024)
        for (tile in layout.tiles) {
            assertTrue("tile ${tile.height}", tile.height <= 1024 * 5 / 4 + 2)
        }
    }

    @Test
    fun tileIndexAtFindsContainingTile() {
        val layout = TileLayout(listOf(ImageSize(1000, 5000)), viewWidth = 1000, targetTileHeight = 1000)
        assertEquals(0, layout.tileIndexAt(0))
        assertEquals(0, layout.tileIndexAt(999))
        assertEquals(1, layout.tileIndexAt(1000))
        assertEquals(4, layout.tileIndexAt(4999))
        assertEquals(4, layout.tileIndexAt(100000))
        assertEquals(0, layout.tileIndexAt(-5))
    }

    @Test
    fun positionSurvivesWidthChange() {
        val sizes = listOf(ImageSize(720, 10000), ImageSize(720, 20000))
        val portrait = TileLayout(sizes, viewWidth = 1080)
        val y = portrait.imageTops[1] + portrait.imageHeights[1] / 4
        val pos = portrait.positionOf(y)
        assertEquals(1, pos.imageIndex)
        assertEquals(0.25f, pos.fraction, 0.001f)

        val landscape = TileLayout(sizes, viewWidth = 2400)
        val y2 = landscape.yOf(pos)
        val expected = landscape.imageTops[1] + landscape.imageHeights[1] / 4
        assertTrue(abs(expected - y2) <= 1)
    }

    @Test
    fun emptyLayout() {
        val layout = TileLayout(emptyList(), viewWidth = 1080)
        assertEquals(0, layout.totalHeight)
        assertEquals(-1, layout.tileIndexAt(0))
        assertEquals(0, layout.yOf(ImagePosition(3, 0.5f)))
    }

    @Test
    fun sampleSizeNeverGoesBelowViewWidth() {
        assertEquals(1, TileLayout.sampleSizeFor(720, 1080))
        assertEquals(1, TileLayout.sampleSizeFor(2000, 1080))
        assertEquals(2, TileLayout.sampleSizeFor(2160, 1080))
        assertEquals(4, TileLayout.sampleSizeFor(5000, 1080))
    }
}
