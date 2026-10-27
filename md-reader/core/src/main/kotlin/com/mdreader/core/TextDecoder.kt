package com.mdreader.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decodes file bytes into text. Handles byte order marks, and falls back from UTF-8
 * to the Korean legacy encodings (CP949 / EUC-KR) that older .txt files often use.
 */
object TextDecoder {

    private val LEGACY_KOREAN = listOf("x-windows-949", "MS949", "windows-949", "EUC-KR")

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        decodeStrict(bytes, Charsets.UTF_8)?.let { return it }
        for (name in LEGACY_KOREAN) {
            val charset = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            decodeStrict(bytes, charset)?.let { return it }
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }
}
