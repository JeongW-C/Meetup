package com.example.ebookreader

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * 긴 텍스트를 화면 크기에 맞는 페이지로 나눈다.
 *
 * 전체 텍스트를 한 번에 줄바꿈(StaticLayout)한 뒤,
 * 화면 높이를 넘지 않을 만큼씩 줄을 묶어 한 페이지로 만든다.
 */
object Paginator {

    class Result(
        val layout: StaticLayout,
        /** 각 페이지가 시작하는 줄 번호 */
        val pageStartLines: IntArray,
    ) {
        val pageCount: Int get() = pageStartLines.size

        fun pageEndLine(page: Int): Int =
            if (page + 1 < pageStartLines.size) pageStartLines[page + 1] else layout.lineCount

        /** 페이지 첫 글자의 위치(문자 인덱스) */
        fun pageStartOffset(page: Int): Int = layout.getLineStart(pageStartLines[page])

        /** 문자 인덱스가 들어 있는 페이지 번호 */
        fun pageForOffset(offset: Int): Int {
            val line = layout.getLineForOffset(offset.coerceIn(0, layout.text.length))
            val idx = pageStartLines.binarySearch(line)
            return if (idx >= 0) idx else (-idx - 2).coerceAtLeast(0)
        }
    }

    fun paginate(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        height: Int,
        lineSpacingMultiplier: Float,
    ): Result {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setLineSpacing(0f, lineSpacingMultiplier)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .build()

        val starts = ArrayList<Int>()
        var line = 0
        while (line < layout.lineCount) {
            starts.add(line)
            val top = layout.getLineTop(line)
            var next = line + 1 // 줄이 화면보다 커도 최소 한 줄은 넣는다
            while (next < layout.lineCount && layout.getLineBottom(next) - top <= height) {
                next++
            }
            line = next
        }
        if (starts.isEmpty()) starts.add(0)
        return Result(layout, starts.toIntArray())
    }
}
