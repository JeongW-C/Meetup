package com.mdreader.data

import android.content.Context
import com.mdreader.core.PlaylistOrder
import com.mdreader.core.ReadableDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class PlaylistItem(val id: String, val title: String, val segmentCount: Int)

/**
 * Files queued to be read one after another. Each file's text is copied into the app's own storage
 * when it is added, so the list keeps working for files whose access can't be kept (Google Drive's
 * picker) and after the original is moved or deleted.
 *
 * File access blocks: call the mutating functions off the main thread.
 */
object Playlist {

    private const val DIR = "playlist"
    private const val INDEX = "index.json"
    const val KEY_PREFIX = "playlist:"

    private val lock = Any()
    private val _items = MutableStateFlow<List<PlaylistItem>>(emptyList())
    val items: StateFlow<List<PlaylistItem>> = _items.asStateFlow()

    @Volatile
    private var loaded = false

    fun documentKey(id: String) = KEY_PREFIX + id

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (!loaded) {
                _items.value = readIndex(context)
                loaded = true
            }
        }
    }

    fun add(context: Context, document: ReadableDocument): PlaylistItem = synchronized(lock) {
        ensureLoaded(context)
        val item = PlaylistItem(UUID.randomUUID().toString(), document.title, document.segments.size)
        textFile(context, item.id).writeText(PlaylistOrder.encodeSegments(document.segments))
        save(context, _items.value + item)
        item
    }

    fun remove(context: Context, id: String) = synchronized(lock) {
        ensureLoaded(context)
        save(context, _items.value.filter { it.id != id })
        textFile(context, id).delete()
    }

    fun move(context: Context, from: Int, to: Int) = synchronized(lock) {
        ensureLoaded(context)
        save(context, PlaylistOrder.move(_items.value, from, to))
    }

    /** The cached text of [id], or null if the item or its copy is gone. */
    fun document(context: Context, id: String): ReadableDocument? {
        ensureLoaded(context)
        val item = _items.value.firstOrNull { it.id == id } ?: return null
        val file = textFile(context, id)
        if (!file.exists()) return null
        val segments = PlaylistOrder.decodeSegments(file.readText())
        return if (segments.isEmpty()) null else ReadableDocument(item.title, segments)
    }

    /** The item [offset] places from [id] in the current order (so reordering while reading is respected). */
    fun neighbor(id: String, offset: Int): PlaylistItem? =
        PlaylistOrder.neighbor(_items.value, offset) { it.id == id }

    private fun dir(context: Context) = File(context.applicationContext.filesDir, DIR).apply { mkdirs() }

    private fun textFile(context: Context, id: String) = File(dir(context), "$id.txt")

    private fun save(context: Context, list: List<PlaylistItem>) {
        val json = JSONArray()
        list.forEach { json.put(JSONObject().put("id", it.id).put("title", it.title).put("count", it.segmentCount)) }
        val target = File(dir(context), INDEX)
        val temp = File(dir(context), "$INDEX.tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(target)) {
            target.writeText(json.toString())
            temp.delete()
        }
        _items.value = list
    }

    private fun readIndex(context: Context): List<PlaylistItem> {
        val file = File(dir(context), INDEX)
        if (!file.exists()) return emptyList()
        return runCatching {
            val json = JSONArray(file.readText())
            (0 until json.length()).map { i ->
                val o = json.getJSONObject(i)
                PlaylistItem(o.getString("id"), o.optString("title", "문서"), o.optInt("count", 0))
            }
        }.getOrDefault(emptyList())
    }
}
