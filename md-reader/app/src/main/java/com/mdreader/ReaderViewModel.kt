package com.mdreader

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mdreader.core.DocumentParser
import com.mdreader.core.PlaylistOrder
import com.mdreader.core.ReadableDocument
import com.mdreader.data.Playlist
import com.mdreader.data.PlaylistItem
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
        const val MAX_FOLDER_FILES = 300
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

    val playlist: StateFlow<List<PlaylistItem>> = Playlist.items

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
        viewModelScope.launch(Dispatchers.IO) { Playlist.ensureLoaded(context) }
        PlaybackHub.warmUp(context)
    }

    /** Opens a file. [remember] adds it to recent files (only when its permission was persisted). */
    fun open(uri: Uri, remember: Boolean) {
        viewModelScope.launch {
            _loading.value = true
            try {
                val document = loadDocument(uri)
                val key = uri.toString()
                PlaybackHub.load(context, document, key, store.position(key))
                if (remember) store.addRecent(RecentFile(key, document.title))
            } catch (e: SecurityException) {
                _message.value = "파일에 접근할 권한이 없습니다. 다시 선택해 주세요."
                store.removeRecent(uri.toString())
            } catch (e: IOException) {
                _message.value = describe(uri, e)
            } finally {
                _loading.value = false
            }
        }
    }

    /** Reads and parses [uri]; an empty or unreadable file throws an IOException with a message for the user. */
    private suspend fun loadDocument(uri: Uri): ReadableDocument {
        val (name, bytes) = withContext(Dispatchers.IO) { read(uri) }
        val pdf = PdfText.isPdf(bytes)
        val document = withContext(Dispatchers.Default) {
            // Drive hands over Google Docs as PDF, so pull the text out of it.
            if (pdf) DocumentParser.parseText(name, PdfText.extract(context, bytes)) else DocumentParser.parse(name, bytes)
        }
        if (document.segments.isEmpty()) {
            throw UnreadableFileException(
                if (pdf) "PDF에서 글자를 찾지 못했습니다. 스캔한 이미지로 된 PDF는 읽을 수 없습니다." else "읽을 내용이 없는 파일입니다.",
            )
        }
        return document
    }

    private fun describe(uri: Uri, e: IOException): String = when {
        uri.authority in DRIVE_AUTHORITIES && e !is UnreadableFileException ->
            "Google Drive에서 파일을 받지 못했습니다. 인터넷 연결을 확인하거나, Drive 앱에서 " +
                "'오프라인으로 사용'을 켠 뒤 다시 시도해 주세요."
        e is UnreadableFileException -> e.message ?: "파일을 열 수 없습니다."
        else -> "파일을 열 수 없습니다: ${e.message ?: "알 수 없는 오류"}"
    }

    // ---- Playlist ----

    /** Copies each file's text into the playlist, in the order picked. */
    fun addToPlaylist(uris: List<Uri>, note: String? = null) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _loading.value = true
            var added = 0
            var lastProblem: String? = null
            try {
                for (uri in uris) {
                    try {
                        val document = loadDocument(uri)
                        withContext(Dispatchers.IO) { Playlist.add(context, document) }
                        added++
                    } catch (e: SecurityException) {
                        lastProblem = "파일에 접근할 권한이 없습니다."
                    } catch (e: IOException) {
                        lastProblem = describe(uri, e)
                    }
                }
            } finally {
                _loading.value = false
            }
            val failed = uris.size - added
            val result = when {
                failed == 0 -> "재생목록에 ${added}개를 추가했습니다."
                added == 0 -> lastProblem ?: "재생목록에 추가하지 못했습니다."
                else -> "재생목록에 ${added}개를 추가했습니다. ${failed}개는 열 수 없었습니다: $lastProblem"
            }
            _message.value = if (note == null) result else "$result $note"
        }
    }

    /**
     * Adds every readable file in a picked folder (and its subfolders), sorted by name so numbered
     * chapters play in order.
     */
    fun addFolderToPlaylist(treeUri: Uri) {
        viewModelScope.launch {
            _loading.value = true
            val files = try {
                withContext(Dispatchers.IO) { listFolder(treeUri) }
            } catch (e: Exception) {
                _loading.value = false
                _message.value = "폴더를 읽을 수 없습니다: ${e.message ?: "알 수 없는 오류"}"
                return@launch
            }
            _loading.value = false
            when {
                files.isEmpty() -> _message.value = "폴더에 읽을 수 있는 파일(.md, .txt, .pdf)이 없습니다."
                files.size > MAX_FOLDER_FILES ->
                    addToPlaylist(files.take(MAX_FOLDER_FILES), note = "(파일이 너무 많아 앞의 ${MAX_FOLDER_FILES}개만 넣었습니다.)")
                else -> addToPlaylist(files)
            }
        }
    }

    /** Readable documents under [treeUri], ordered by their path inside it. */
    private fun listFolder(treeUri: Uri): List<Uri> {
        val resolver = context.contentResolver
        val found = mutableListOf<Pair<String, Uri>>()
        val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(treeUri) to ""))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        while (pending.isNotEmpty() && found.size <= MAX_FOLDER_FILES) {
            val (parentId, path) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            resolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".")) pending.add(id to "$path$name/")
                    } else if (PlaylistOrder.isReadable(name, mime)) {
                        found.add(path + name to DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
                    }
                }
            }
        }
        return found.sortedWith(compareBy(PlaylistOrder.naturalOrder) { it.first }).map { it.second }
    }

    /** Adds the document on screen (e.g. one opened from Drive or shared text). */
    fun addCurrentToPlaylist() {
        val state = playback.value
        val document = state.document ?: return
        if (state.playlistItemId != null) {
            _message.value = "이미 재생목록에 있는 파일입니다."
            return
        }
        viewModelScope.launch {
            val item = withContext(Dispatchers.IO) { Playlist.add(context, document) }
            // From now on this document continues into the rest of the playlist.
            PlaybackHub.update {
                if (it.document === document) {
                    it.copy(playlistItemId = item.id, documentKey = Playlist.documentKey(item.id))
                } else {
                    it
                }
            }
            _message.value = "재생목록에 추가했습니다."
        }
    }

    /** Opens a playlist item; [autoPlay] starts reading right away. */
    fun openPlaylistItem(item: PlaylistItem, autoPlay: Boolean, fromStart: Boolean = false) {
        viewModelScope.launch {
            val document = withContext(Dispatchers.IO) { Playlist.document(context, item.id) }
            if (document == null) {
                _message.value = "저장된 내용을 찾을 수 없습니다. 재생목록에서 지우고 다시 추가해 주세요."
                return@launch
            }
            val key = Playlist.documentKey(item.id)
            val start = if (fromStart) 0 else store.position(key)
            PlaybackHub.load(context, document, key, start, playlistItemId = item.id)
            if (autoPlay) PlaybackHub.play(context)
        }
    }

    fun playPlaylist() {
        Playlist.items.value.firstOrNull()?.let { openPlaylistItem(it, autoPlay = true, fromStart = true) }
    }

    /** Jumps to the previous (-1) or next (+1) playlist item, keeping playback going if it was. */
    fun skipPlaylist(offset: Int) {
        val state = playback.value
        val target = state.playlistItemId?.let { Playlist.neighbor(it, offset) } ?: return
        openPlaylistItem(target, autoPlay = state.isPlaying, fromStart = true)
    }

    fun removeFromPlaylist(item: PlaylistItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Playlist.remove(context, item.id) }
            // The open copy can still be read; it just no longer leads anywhere.
            PlaybackHub.update { if (it.playlistItemId == item.id) it.copy(playlistItemId = null) else it }
        }
    }

    fun movePlaylistItem(from: Int, to: Int) {
        viewModelScope.launch { withContext(Dispatchers.IO) { Playlist.move(context, from, to) } }
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
