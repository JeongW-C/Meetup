package com.mdreader

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.IOException

/** Text extraction for PDFs, which is how Google Drive hands over Google Docs. */
object PdfText {

    fun isPdf(bytes: ByteArray): Boolean =
        bytes.size >= 5 && String(bytes, 0, 5, Charsets.US_ASCII) == "%PDF-"

    /** Returns the text with a blank line between paragraphs, so it splits like a .txt file. */
    fun extract(context: Context, bytes: ByteArray): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        val document = try {
            PDDocument.load(bytes)
        } catch (e: IOException) {
            throw IOException("PDF를 열 수 없습니다 (암호가 걸렸거나 손상된 파일)", e)
        }
        return document.use {
            PDFTextStripper().apply {
                lineSeparator = "\n"
                paragraphEnd = "\n\n"
            }.getText(it)
        }
    }
}
