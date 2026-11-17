package com.example.webtoonviewer

import kotlin.math.ceil
import kotlin.math.roundToInt

/** 이미지 한 장의 원본 크기(픽셀) */
data class ImageSize(val width: Int, val height: Int)

/**
 * 화면에 그릴 이미지 조각 하나.
 * [srcTop]~[srcBottom] 는 원본 이미지의 세로 범위, [top]/[height] 는 화면 폭에 맞춘 뒤의 위치와 높이.
 */
data class Tile(
    val imageIndex: Int,
    val srcTop: Int,
    val srcBottom: Int,
    val top: Int,
    val height: Int,
)

/**
 * 여러 장의 이미지를 화면 폭([viewWidth])에 맞춰 세로로 이어 붙이고, 긴 이미지를 일정 높이의 조각으로 나눈다.
 *
 * 세로로 수만 픽셀인 웹툰 이미지를 한 번에 읽으면 메모리가 모자라고 GPU가 그릴 수 있는 크기도 넘기 때문에,
 * 화면에 보이는 조각만 그때그때 잘라 읽는다(BitmapRegionDecoder). 이 클래스는 그 조각 나누기와
 * 스크롤 위치 계산만 맡는 순수 로직이라 안드로이드 없이 단위 테스트할 수 있다.
 */
class TileLayout(
    val images: List<ImageSize>,
    val viewWidth: Int,
    /** 조각 하나의 화면상 목표 높이(픽셀) */
    targetTileHeight: Int = DEFAULT_TILE_HEIGHT,
) {
    val tiles: List<Tile>
    /** 각 이미지가 화면상 시작하는 y 위치 */
    val imageTops: IntArray
    /** 각 이미지의 화면상 높이 */
    val imageHeights: IntArray
    val totalHeight: Int
    private val tileTops: IntArray

    init {
        require(viewWidth > 0) { "viewWidth must be positive" }
        require(targetTileHeight > 0) { "targetTileHeight must be positive" }
        val result = ArrayList<Tile>()
        imageTops = IntArray(images.size)
        imageHeights = IntArray(images.size)
        var y = 0
        images.forEachIndexed { index, size ->
            imageTops[index] = y
            if (size.width <= 0 || size.height <= 0) return@forEachIndexed
            val scale = viewWidth.toDouble() / size.width
            val srcStep = maxOf(1, ceil(targetTileHeight / scale).toInt())
            val imageTop = y
            var srcTop = 0
            while (srcTop < size.height) {
                var srcBottom = minOf(size.height, srcTop + srcStep)
                // 끝에 아주 얇은 조각이 남으면 앞 조각에 붙인다
                if (size.height - srcBottom < srcStep / 4) srcBottom = size.height
                val bottom = imageTop + (srcBottom * scale).roundToInt()
                val height = maxOf(1, bottom - y)
                result += Tile(index, srcTop, srcBottom, y, height)
                y += height
                srcTop = srcBottom
            }
            imageHeights[index] = y - imageTop
        }
        tiles = result
        totalHeight = y
        tileTops = IntArray(result.size) { result[it].top }
    }

    /** 화면 y 위치를 포함하는 조각의 번호 */
    fun tileIndexAt(y: Int): Int {
        if (tiles.isEmpty()) return -1
        val clamped = y.coerceIn(0, totalHeight - 1)
        val found = tileTops.binarySearch(clamped)
        return if (found >= 0) found else -found - 2
    }

    /** 화면 y 위치를 "몇 번째 이미지의 몇 % 지점"으로 바꾼다. 화면 폭이 바뀌어도 같은 곳을 다시 찾기 위해 쓴다. */
    fun positionOf(y: Int): ImagePosition {
        val tile = tileIndexAt(y)
        if (tile < 0) return ImagePosition(0, 0f)
        val image = tiles[tile].imageIndex
        val h = imageHeights[image]
        val fraction = if (h > 0) ((y - imageTops[image]).toFloat() / h).coerceIn(0f, 1f) else 0f
        return ImagePosition(image, fraction)
    }

    /** [positionOf] 의 반대 */
    fun yOf(position: ImagePosition): Int {
        if (images.isEmpty()) return 0
        val image = position.imageIndex.coerceIn(0, images.size - 1)
        return imageTops[image] + (imageHeights[image] * position.fraction.coerceIn(0f, 1f)).roundToInt()
    }

    companion object {
        const val DEFAULT_TILE_HEIGHT = 1024

        /**
         * 원본을 몇 분의 1로 줄여 읽을지(2의 거듭제곱). 줄인 폭이 화면 폭보다 작아지지 않는 가장 큰 값을 고른다.
         * 화면보다 훨씬 큰 이미지도 메모리를 아끼면서 화질은 떨어지지 않는다.
         */
        fun sampleSizeFor(srcWidth: Int, viewWidth: Int): Int {
            var sample = 1
            while (srcWidth / (sample * 2) >= viewWidth) sample *= 2
            return sample
        }
    }
}

data class ImagePosition(val imageIndex: Int, val fraction: Float)
