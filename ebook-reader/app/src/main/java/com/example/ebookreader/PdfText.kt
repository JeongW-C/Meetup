package com.example.ebookreader

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/** PDF에서 글자만 뽑아낸다. 구글 드라이브는 구글 문서도 PDF로 넘겨준다. */
object PdfText {

    fun isPdf(bytes: ByteArray): Boolean =
        bytes.size >= 5 && String(bytes, 0, 5, Charsets.US_ASCII) == "%PDF-"

    fun extract(context: Context, bytes: ByteArray): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        val raw = PDDocument.load(bytes).use { document ->
            PDFTextStripper().apply {
                lineSeparator = "\n"
                paragraphEnd = "\n\n"
            }.getText(document)
        }
        return joinLines(raw)
    }

    /**
     * PDF는 원래 종이 폭에 맞춰 줄이 끊겨 있어서, 그대로 두면 글자 크기를 바꿀 때 문장 중간에서 줄이 바뀐다.
     * 문단 안의 줄바꿈을 공백으로 이어 붙이고, 문단 사이에는 빈 줄 하나를 둔다.
     * (줄 끝에서 단어가 잘렸는지는 PDF만으로 알 수 없어서, 가끔 단어 중간에 공백이 들어갈 수 있다)
     */
    fun joinLines(raw: String): String =
        raw.replace("\r\n", "\n")
            .split(Regex("\n\\s*\n"))
            .map { paragraph -> paragraph.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ") }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
}
