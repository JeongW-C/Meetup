package com.mdreader.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class ReaderPrefs(
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val voiceName: String? = null,
    val fontSize: Float = 18f,
)

data class RecentFile(val uri: String, val title: String)

/** Voice settings, reading positions and the recent-files list, kept with DataStore. */
class SettingsStore(private val context: Context) {

    private object Keys {
        val SPEED = floatPreferencesKey("speed")
        val PITCH = floatPreferencesKey("pitch")
        val VOICE = stringPreferencesKey("voice")
        val FONT_SIZE = floatPreferencesKey("font_size")
        val RECENT = stringPreferencesKey("recent")
        fun position(key: String) = intPreferencesKey("pos_" + key.hashCode())
    }

    private companion object {
        const val MAX_RECENT = 10
        const val FIELD_SEPARATOR = '\t'
    }

    val prefs: Flow<ReaderPrefs> = context.dataStore.data.map { p ->
        ReaderPrefs(
            speed = p[Keys.SPEED] ?: 1f,
            pitch = p[Keys.PITCH] ?: 1f,
            voiceName = p[Keys.VOICE],
            fontSize = p[Keys.FONT_SIZE] ?: 18f,
        )
    }

    val recentFiles: Flow<List<RecentFile>> = context.dataStore.data.map { decodeRecent(it) }

    suspend fun setSpeed(value: Float) = context.dataStore.edit { it[Keys.SPEED] = value }

    suspend fun setPitch(value: Float) = context.dataStore.edit { it[Keys.PITCH] = value }

    suspend fun setFontSize(value: Float) = context.dataStore.edit { it[Keys.FONT_SIZE] = value }

    suspend fun setVoice(name: String?) = context.dataStore.edit { p ->
        p.remove(Keys.VOICE)
        if (name != null) p[Keys.VOICE] = name
    }

    suspend fun position(documentKey: String): Int =
        context.dataStore.data.first()[Keys.position(documentKey)] ?: 0

    suspend fun savePosition(documentKey: String, index: Int) =
        context.dataStore.edit { it[Keys.position(documentKey)] = index }

    suspend fun addRecent(file: RecentFile) = context.dataStore.edit { p ->
        val list = listOf(file) + decodeRecent(p).filter { it.uri != file.uri }
        p[Keys.RECENT] = encodeRecent(list.take(MAX_RECENT))
    }

    suspend fun removeRecent(uri: String) = context.dataStore.edit { p ->
        p[Keys.RECENT] = encodeRecent(decodeRecent(p).filter { it.uri != uri })
    }

    private fun decodeRecent(p: Preferences): List<RecentFile> =
        p[Keys.RECENT].orEmpty().lines().mapNotNull { line ->
            val cut = line.indexOf(FIELD_SEPARATOR)
            if (cut <= 0) null else RecentFile(uri = line.substring(0, cut), title = line.substring(cut + 1))
        }

    private fun encodeRecent(list: List<RecentFile>): String =
        list.joinToString("\n") { it.uri + FIELD_SEPARATOR + it.title.replace('\n', ' ').replace(FIELD_SEPARATOR, ' ') }
}
