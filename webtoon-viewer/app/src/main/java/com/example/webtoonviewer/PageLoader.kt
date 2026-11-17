package com.example.webtoonviewer

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 화면에 이어 붙일 이미지 한 장 */
data class Page(val uri: Uri, val name: String, val size: ImageSize)

object PageLoader {

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")

    /** 고른 이미지들의 이름과 크기를 읽고, 이름 순서(2 < 10)로 정렬한다. 읽을 수 없는 파일은 뺀다. */
    suspend fun load(context: Context, uris: List<Uri>): List<Page> = withContext(Dispatchers.IO) {
        uris.distinct().mapNotNull { uri ->
            ensureActive()
            val size = readSize(context, uri) ?: return@mapNotNull null
            Page(uri, displayName(context, uri), size)
        }.sortedWith(compareBy(NaturalOrder) { it.name })
    }

    /** 폴더(문서 트리) 안의 이미지 파일 목록. 하위 폴더는 보지 않는다. */
    suspend fun listFolder(context: Context, treeUri: Uri): List<Uri> = withContext(Dispatchers.IO) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val result = ArrayList<Uri>()
        context.contentResolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: ""
                val mime = c.getString(2) ?: ""
                if (mime.startsWith("image/") || name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS) {
                    result += DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                }
            }
        }
        result
    }

    private fun readSize(context: Context, uri: Uri): ImageSize? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth > 0 && opts.outHeight > 0) ImageSize(opts.outWidth, opts.outHeight) else null
    } catch (e: Exception) {
        null
    }

    private fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (e: Exception) {
            // file:// 주소 등은 이름 조회가 안 된다
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
    }
}
