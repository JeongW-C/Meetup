package com.example.webtoonviewer

import android.graphics.Color
import android.util.Log
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 조각 하나 = 목록 항목 하나. 높이는 미리 계산해 두었으므로 그림을 읽기 전에도 스크롤 길이가 정확하다. */
class TileAdapter(
    private val scope: CoroutineScope,
    private val decoder: TileDecoder,
) : RecyclerView.Adapter<TileAdapter.Holder>() {

    private var pages: List<Page> = emptyList()
    var layout: TileLayout? = null
        private set
    private var samples = IntArray(0)

    fun submit(pages: List<Page>, layout: TileLayout) {
        this.pages = pages
        this.layout = layout
        samples = IntArray(pages.size) { TileLayout.sampleSizeFor(pages[it].size.width, layout.viewWidth) }
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    override fun getItemCount() = layout?.tiles?.size ?: 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = ImageView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
            scaleType = ImageView.ScaleType.FIT_XY
        }
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val tile = layout!!.tiles[position]
        val page = pages[tile.imageIndex]
        val sample = samples[tile.imageIndex]
        val key = "${page.uri}#${tile.srcTop}-${tile.srcBottom}@$sample"
        val image = holder.image

        image.layoutParams = image.layoutParams.apply { height = tile.height }
        holder.job?.cancel()
        holder.key = key

        val cached = decoder.cached(key)
        if (cached != null) {
            image.setBackgroundColor(Color.TRANSPARENT)
            image.setImageBitmap(cached)
            return
        }
        image.setImageDrawable(null)
        image.setBackgroundColor(PLACEHOLDER)
        holder.job = scope.launch {
            try {
                val bitmap = decoder.decode(page, tile, sample, key)
                if (holder.key == key) {
                    image.setBackgroundColor(Color.TRANSPARENT)
                    image.setImageBitmap(bitmap)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // OutOfMemoryError 도 여기서 받아서 앱이 죽지 않게 한다
                Log.w("TileAdapter", "tile failed: ${page.name} ${tile.srcTop}", e)
                if (holder.key == key) image.setBackgroundColor(ERROR)
            }
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel()
        holder.job = null
        holder.key = null
        holder.image.setImageDrawable(null)
    }

    class Holder(val image: ImageView) : RecyclerView.ViewHolder(image) {
        var job: Job? = null
        var key: String? = null
    }

    companion object {
        private val PLACEHOLDER = Color.rgb(24, 24, 24)
        private val ERROR = Color.rgb(64, 16, 16)
    }
}
