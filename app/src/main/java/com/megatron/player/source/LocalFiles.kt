package com.megatron.player.source

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

object LocalFiles {
    fun root(source: MediaSourceConfig): String = DocumentsContract.getTreeDocumentId(Uri.parse(source.address))
    fun list(context: Context, source: MediaSourceConfig, path: String): List<BrowserEntry> {
        val tree = Uri.parse(source.address)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, path.ifEmpty { root(source) })
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        return context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    add(BrowserEntry(cursor.getString(1) ?: "未命名文件", id,
                        cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        if (cursor.isNull(3)) 0L else cursor.getLong(3), if (cursor.isNull(4)) 0L else cursor.getLong(4),
                        DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()))
                }
            }
        } ?: throw java.io.IOException("无法读取目录，请检查文件授权。")
    }
}
