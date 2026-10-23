package com.example.ebookreader

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View

/** 페이지 나누기 결과 중 한 페이지만 그려 주는 뷰 */
class PageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var pages: Paginator.Result? = null
        private set
    var page: Int = 0
        private set

    val contentWidth: Int get() = width - paddingLeft - paddingRight
    val contentHeight: Int get() = height - paddingTop - paddingBottom

    /** 화면 크기가 바뀌면(메뉴 숨기기 등) 페이지를 다시 나눠야 한다 */
    var onContentSizeChanged: (() -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw != 0 && oldh != 0) onContentSizeChanged?.invoke()
    }

    fun show(pages: Paginator.Result?, page: Int) {
        this.pages = pages
        this.page = if (pages == null) 0 else page.coerceIn(0, pages.pageCount - 1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val result = pages ?: return
        val layout = result.layout
        val startLine = result.pageStartLines[page]
        val endLine = result.pageEndLine(page)
        val top = layout.getLineTop(startLine)
        val bottom = layout.getLineTop(endLine)

        canvas.save()
        canvas.translate(paddingLeft.toFloat(), (paddingTop - top).toFloat())
        canvas.clipRect(0, top, contentWidth, bottom)
        layout.draw(canvas)
        canvas.restore()
    }
}
