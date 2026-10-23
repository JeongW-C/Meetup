package com.example.ebookreader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.noties.markwon.Markwon
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 파일을 읽어 화면에 보여줄 텍스트로 바꾼다 */
object DocumentLoader {

    class Document(val name: String, val text: CharSequence)

    fun load(context: Context, uri: Uri): Document {
        val name = displayName(context, uri) ?: uri.lastPathSegment ?: "document"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("cannot open $uri")
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

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }
}
