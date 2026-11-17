package com.example.webtoonviewer

import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract

/** 무엇을 열었는지: 고른 이미지 파일들 또는 폴더 하나. 다음에 앱을 켤 때 이어 보기 위해 저장한다. */
sealed class Source {
    data class Files(val uris: List<Uri>) : Source()
    data class Folder(val tree: Uri) : Source()

    fun save(prefs: SharedPreferences) {
        val editor = prefs.edit()
        when (this) {
            is Files -> editor.putString(KEY_TYPE, TYPE_FILES).putString(KEY_URIS, uris.joinToString("\n"))
            is Folder -> editor.putString(KEY_TYPE, TYPE_FOLDER).putString(KEY_URIS, tree.toString())
        }
        editor.apply()
    }

    companion object {
        private const val KEY_TYPE = "sourceType"
        private const val KEY_URIS = "sourceUris"
        private const val TYPE_FILES = "files"
        private const val TYPE_FOLDER = "folder"

        fun restore(prefs: SharedPreferences): Source? {
            val uris = prefs.getString(KEY_URIS, null)?.split("\n")?.filter { it.isNotBlank() }?.map(Uri::parse)
            if (uris.isNullOrEmpty()) return null
            return when (prefs.getString(KEY_TYPE, null)) {
                TYPE_FILES -> Files(uris)
                TYPE_FOLDER -> Folder(uris.first())
                else -> null
            }
        }

        /** "primary:Download/웹툰/1화" 같은 폴더 ID 에서 마지막 이름만 */
        fun folderName(tree: Uri): String? = try {
            DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/')
                .ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }
}
