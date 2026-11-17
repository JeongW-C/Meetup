package com.example.webtoonviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executors

/**
 * 긴 이미지에서 필요한 조각만 잘라 읽는다.
 *
 * - JPEG/PNG/WebP(안드로이드 버전에 따라 HEIF)는 BitmapRegionDecoder 로 그 부분만 읽는다.
 * - 그 밖의 형식(GIF 등)은 이미지 전체를 화면 폭에 가깝게 줄여 읽은 뒤 잘라 쓴다.
 * 디코더는 스레드 하나에서만 다루므로 따로 잠글 필요가 없다.
 */
class TileDecoder(context: Context) {

    private val resolver = context.applicationContext.contentResolver
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "tile-decoder") }
    private val dispatcher = executor.asCoroutineDispatcher()

    /** 이미 읽은 조각. 앱 메모리의 1/4 까지 둔다. */
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 4).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    // 아래 둘은 디코더 스레드에서만 만진다
    private val regionDecoders = LinkedHashMap<Uri, BitmapRegionDecoder?>(8, 0.75f, true)
    private var fallback: Pair<String, Bitmap>? = null

    fun cached(key: String): Bitmap? = cache.get(key)

    fun clearCache() = cache.evictAll()

    suspend fun decode(page: Page, tile: Tile, sample: Int, key: String): Bitmap = withContext(dispatcher) {
        cache.get(key)?.let { return@withContext it }
        val bitmap = decodeRegion(page, tile, sample) ?: decodeWithFallback(page, tile, sample)
        cache.put(key, bitmap)
        bitmap
    }

    private fun decodeRegion(page: Page, tile: Tile, sample: Int): Bitmap? {
        val decoder = regionDecoder(page.uri) ?: return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            decoder.decodeRegion(Rect(0, tile.srcTop, page.size.width, tile.srcBottom), opts)
        } catch (e: Exception) {
            Log.w(TAG, "decodeRegion failed: ${page.name}", e)
            null
        }
    }

    private fun regionDecoder(uri: Uri): BitmapRegionDecoder? {
        if (regionDecoders.containsKey(uri)) return regionDecoders[uri]
        val decoder = try {
            resolver.openInputStream(uri)?.use { input ->
                if (Build.VERSION.SDK_INT >= 31) {
                    BitmapRegionDecoder.newInstance(input)
                } else {
                    @Suppress("DEPRECATION")
                    BitmapRegionDecoder.newInstance(input, false)
                }
            }
        } catch (e: IOException) {
            null // 부분 읽기를 지원하지 않는 형식
        }
        regionDecoders[uri] = decoder
        // 동시에 열어 두는 디코더는 몇 개만 (각각 원본 파일 내용을 메모리에 들고 있다)
        while (regionDecoders.size > MAX_OPEN_DECODERS) {
            val eldest = regionDecoders.entries.iterator().next()
            eldest.value?.recycle()
            regionDecoders.remove(eldest.key)
        }
        return decoder
    }

    private fun decodeWithFallback(page: Page, tile: Tile, sample: Int): Bitmap {
        val key = "${page.uri}@$sample"
        val full = fallback?.takeIf { it.first == key }?.second ?: run {
            fallback = null
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = resolver.openInputStream(page.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: throw IOException("cannot decode ${page.name}")
            fallback = key to decoded
            decoded
        }
        val ratio = full.height.toDouble() / page.size.height
        val top = (tile.srcTop * ratio).toInt().coerceIn(0, full.height - 1)
        val bottom = (tile.srcBottom * ratio).toInt().coerceIn(top + 1, full.height)
        return Bitmap.createBitmap(full, 0, top, full.width, bottom - top)
    }

    /** 다른 이미지 묶음을 열 때 이전 디코더를 정리한다 */
    suspend fun reset() = withContext(dispatcher) {
        regionDecoders.values.forEach { it?.recycle() }
        regionDecoders.clear()
        fallback = null
        cache.evictAll()
    }

    fun shutdown() {
        executor.execute {
            regionDecoders.values.forEach { it?.recycle() }
            regionDecoders.clear()
            fallback = null
        }
        executor.shutdown()
    }

    companion object {
        private const val TAG = "TileDecoder"
        private const val MAX_OPEN_DECODERS = 4
    }
}
