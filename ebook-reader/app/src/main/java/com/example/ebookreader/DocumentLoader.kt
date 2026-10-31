package com.example.ebookreader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.noties.markwon.Markwon
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 파일을 읽어 화면에 보여줄 텍스트로 바꾼다 */
object DocumentLoader {

    class Document(val name: String, val text: CharSequence)

    /** 열 수는 있지만 글자로 보여줄 수 없는 문서. messageRes 는 사용자에게 보여줄 안내 문구 */
    class NotTextException(val messageRes: Int) : Exception()

    fun load(context: Context, uri: Uri): Document {
        val name = displayName(context, uri) ?: uri.lastPathSegment ?: "document"
        val bytes = openStream(context, uri).use { it.readBytes() }

        // PDF 파일, 그리고 드라이브가 PDF로 넘겨주는 구글 문서는 글자만 뽑아서 보여준다
        if (PdfText.isPdf(bytes)) {
            val pdfText = try {
                PdfText.extract(context, bytes)
            } catch (e: Exception) {
                throw NotTextException(R.string.open_failed_pdf)
            }
            if (pdfText.isBlank()) throw NotTextException(R.string.open_failed_pdf_no_text)
            return Document(name, pdfText.trim())
        }

        val raw = decode(bytes).replace("\r\n", "\n")

        val lower = name.lowercase()
        val isMarkdown = lower.endsWith(".md") || lower.endsWith(".markdown") ||
            context.contentResolver.getType(uri)?.contains("markdown") == true
        val text: CharSequence = if (isMarkdown) Markwon.create(context).toMarkdown(raw) else raw
        return Document(name, text)
    }

    /** UTF-8로 읽어 보고, 깨지면 한국어 윈도우 인코딩(CP949/EUC-KR)으로 읽는다 */
    private fun decode(bytes: ByteArray): String {
        val hasBom = bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        if (hasBom) return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            val korean = listOf("x-windows-949", "MS949", "EUC-KR")
                .firstOrNull { Charset.isSupported(it) }
            String(bytes, if (korean != null) Charset.forName(korean) else Charsets.ISO_8859_1)
        }
    }

    /**
     * 보통 파일은 그대로 열고, 구글 드라이브의 구글 문서 같은 "가상 파일"은
     * 제공 앱에 텍스트(없으면 PDF)로 변환해 달라고 해서 연다.
     */
    private fun openStream(context: Context, uri: Uri): InputStream {
        val resolver = context.contentResolver
        if (isVirtual(context, uri)) {
            val text = resolver.getStreamTypes(uri, "text/*").orEmpty()
            val type = text.firstOrNull { it == "text/plain" }
                ?: text.firstOrNull { it != "text/html" }
                ?: resolver.getStreamTypes(uri, "application/pdf")?.firstOrNull()
                ?: throw NotTextException(R.string.open_failed_not_text)
            return resolver.openTypedAssetFileDescriptor(uri, type, null)?.createInputStream()
                ?: throw NotTextException(R.string.open_failed_not_text)
        }
        return resolver.openInputStream(uri) ?: error("cannot open $uri")
    }

    private fun isVirtual(context: Context, uri: Uri): Boolean {
        if (!DocumentsContract.isDocumentUri(context, uri)) return false
        return try {
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)
                ?.use { c ->
                    c.moveToFirst() && c.getInt(0) and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0
                } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }
}
