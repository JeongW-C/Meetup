package com.mdreader

import android.app.Application
import android.net.Uri
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
import java.io.IOException

class ReaderViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val MAX_FILE_BYTES = 20L * 1024 * 1024
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
                val document = withContext(Dispatchers.Default) { DocumentParser.parse(name, bytes) }
                if (document.segments.isEmpty()) {
                    _message.value = "읽을 내용이 없는 파일입니다."
                    return@launch
                }
                val key = uri.toString()
                PlaybackHub.load(context, document, key, store.position(key))
                if (remember) store.addRecent(RecentFile(key, document.title))
            } catch (e: SecurityException) {
                _message.value = "파일에 접근할 권한이 없습니다. 다시 선택해 주세요."
                store.removeRecent(uri.toString())
            } catch (e: IOException) {
                _message.value = "파일을 열 수 없습니다: ${e.message ?: "알 수 없는 오류"}"
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
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameColumn = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0 && !c.isNull(nameColumn)) name = c.getString(nameColumn)
                if (sizeColumn >= 0 && !c.isNull(sizeColumn)) size = c.getLong(sizeColumn)
            }
        }
        if (size > MAX_FILE_BYTES) throw IOException("파일이 너무 큽니다 (20MB 이하만 지원)")
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("파일을 읽을 수 없습니다")
        if (bytes.size > MAX_FILE_BYTES) throw IOException("파일이 너무 큽니다 (20MB 이하만 지원)")
        return name to bytes
    }
}
