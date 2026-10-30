package com.mdreader

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mdreader.core.DocumentParser
import com.mdreader.data.ReaderPrefs
import com.mdreader.data.RecentFile
import com.mdreader.data.SettingsStore
import com.mdreader.playback.PlaybackHub
import com.mdreader.playback.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

class ReaderViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val MAX_FILE_BYTES = 20L * 1024 * 1024
        const val GOOGLE_DOC_MIME = "application/vnd.google-apps.document"

        /** Google Drive's document providers (the second is used by older Drive versions). */
        val DRIVE_AUTHORITIES = setOf("com.google.android.apps.docs.storage", "com.google.android.apps.docs.storage.legacy")
    }

    private val store = SettingsStore(app)
    private val context get() = getApplication<Application>()

    val playback: StateFlow<PlaybackState> = PlaybackHub.state
    val prefs: StateFlow<ReaderPrefs> = store.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderPrefs())
    val recentFiles: StateFlow<List<RecentFile>> =
        store.recentFiles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            store.prefs.collect { p -> PlaybackHub.setVoiceSettings(context, p.speed, p.pitch, p.voiceName) }
        }
        viewModelScope.launch {
            PlaybackHub.state
                .map { it.documentKey to it.index }
                .distinctUntilChanged()
                .collect { (key, index) -> if (key != null) store.savePosition(key, index) }
        }
        PlaybackHub.warmUp(context)
    }

    /** Opens a file. [remember] adds it to recent files (only when its permission was persisted). */
    fun open(uri: Uri, remember: Boolean) {
        viewModelScope.launch {
            _loading.value = true
            try {
                val (name, bytes) = withContext(Dispatchers.IO) { read(uri) }
                val pdf = PdfText.isPdf(bytes)
                val document = withContext(Dispatchers.Default) {
                    // Drive hands over Google Docs as PDF, so pull the text out of it.
                    if (pdf) DocumentParser.parseText(name, PdfText.extract(context, bytes)) else DocumentParser.parse(name, bytes)
                }
                if (document.segments.isEmpty()) {
                    _message.value = if (pdf) {
                        "PDF에서 글자를 찾지 못했습니다. 스캔한 이미지로 된 PDF는 읽을 수 없습니다."
                    } else {
                        "읽을 내용이 없는 파일입니다."
                    }
                    return@launch
                }
                val key = uri.toString()
                PlaybackHub.load(context, document, key, store.position(key))
                if (remember) store.addRecent(RecentFile(key, document.title))
            } catch (e: SecurityException) {
                _message.value = "파일에 접근할 권한이 없습니다. 다시 선택해 주세요."
                store.removeRecent(uri.toString())
            } catch (e: IOException) {
                _message.value = if (uri.authority in DRIVE_AUTHORITIES && e !is UnreadableFileException) {
                    "Google Drive에서 파일을 받지 못했습니다. 인터넷 연결을 확인하거나, Drive 앱에서 " +
                        "'오프라인으로 사용'을 켠 뒤 다시 시도해 주세요."
                } else {
                    "파일을 열 수 없습니다: ${e.message ?: "알 수 없는 오류"}"
                }
            } finally {
                _loading.value = false
            }
        }
    }

    /** Text shared from another app (no file behind it). */
    fun openSharedText(text: String, subject: String?) {
        viewModelScope.launch {
            val document = withContext(Dispatchers.Default) {
                DocumentParser.parseText((subject?.takeIf { it.isNotBlank() } ?: "공유된 텍스트") + ".txt", text)
            }
            if (document.segments.isEmpty()) {
                _message.value = "읽을 내용이 없습니다."
            } else {
                PlaybackHub.load(context, document, "shared:" + text.hashCode(), 0)
            }
        }
    }

    fun openRecent(file: RecentFile) = open(Uri.parse(file.uri), remember = true)

    fun removeRecent(file: RecentFile) {
        viewModelScope.launch { store.removeRecent(file.uri) }
    }

    fun closeDocument() = PlaybackHub.close(context)

    fun togglePlay() = PlaybackHub.togglePlay(context)
    fun next() = PlaybackHub.next(context)
    fun previous() = PlaybackHub.previous(context)
    fun seek(index: Int) = PlaybackHub.seek(context, index)
    fun stop() = PlaybackHub.stop(context)
    fun setSleepTimer(minutes: Int) = PlaybackHub.setSleepTimer(context, minutes)

    fun setSpeed(value: Float) {
        viewModelScope.launch { store.setSpeed(value) }
    }

    fun setPitch(value: Float) {
        viewModelScope.launch { store.setPitch(value) }
    }

    fun setVoice(name: String?) {
        viewModelScope.launch { store.setVoice(name) }
    }

    fun setFontSize(value: Float) {
        viewModelScope.launch { store.setFontSize(value) }
    }

    fun showMessage(text: String) {
        _message.value = text
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun read(uri: Uri): Pair<String, ByteArray> {
        val resolver = context.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "문서.txt"
        var size = -1L
        var virtual = false
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameColumn = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = c.getColumnIndex(OpenableColumns.SIZE)
                val flagsColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                if (nameColumn >= 0 && !c.isNull(nameColumn)) name = c.getString(nameColumn)
                if (sizeColumn >= 0 && !c.isNull(sizeColumn)) size = c.getLong(sizeColumn)
                if (flagsColumn >= 0 && !c.isNull(flagsColumn)) {
                    virtual = c.getInt(flagsColumn) and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0
                }
            }
        }
        // Google Docs have no bytes of their own ("virtual"), and other non-text files may come with a
        // text version: ask the provider for a converted copy first.
        val mime = resolver.getType(uri)
        if (virtual || mime == GOOGLE_DOC_MIME) {
            return name to readLimited(openConverted(uri))
        }
        if (mime != null && !mime.startsWith("text/") && hasStreamType(uri, "text/plain")) {
            return name to readLimited(openConverted(uri))
        }
        if (size > MAX_FILE_BYTES) throw UnreadableFileException("파일이 너무 큽니다 (20MB 이하만 지원)")
        // Drive downloads a file that isn't on the phone yet while we read, so this can take a while.
        val stream = try {
            resolver.openInputStream(uri)
        } catch (e: FileNotFoundException) {
            // Some providers only offer a converted copy; fall back to that before giving up.
            runCatching { openConverted(uri) }.getOrNull() ?: throw e
        } ?: throw IOException("파일을 읽을 수 없습니다")
        return name to readLimited(stream)
    }

    private fun hasStreamType(uri: Uri, filter: String): Boolean =
        runCatching { context.contentResolver.getStreamTypes(uri, filter) }.getOrNull()?.isNotEmpty() == true

    /**
     * Opens a document through the provider's conversion (e.g. a Google Doc): plain text if offered,
     * otherwise PDF, whose text [open] extracts.
     */
    private fun openConverted(uri: Uri): InputStream {
        val resolver = context.contentResolver
        val text = runCatching { resolver.getStreamTypes(uri, "text/*") }.getOrNull().orEmpty()
        val type = text.firstOrNull { it == "text/plain" }
            ?: text.firstOrNull { it != "text/html" }
            ?: runCatching { resolver.getStreamTypes(uri, "application/pdf") }.getOrNull()?.firstOrNull()
            ?: throw UnreadableFileException("이 문서는 텍스트로 바꿀 수 없습니다. .txt 또는 .md 파일로 저장해 주세요.")
        return resolver.openTypedAssetFileDescriptor(uri, type, null)?.createInputStream()
            ?: throw IOException("파일을 읽을 수 없습니다")
    }

    private fun readLimited(stream: InputStream): ByteArray = stream.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_FILE_BYTES) throw UnreadableFileException("파일이 너무 큽니다 (20MB 이하만 지원)")
        }
        out.toByteArray()
    }

    /** A problem with the file itself, as opposed to a download or connection failure. */
    private class UnreadableFileException(message: String) : IOException(message)
}
