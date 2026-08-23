package com.example.composeapp.data

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import kotlinx.coroutines.runBlocking

/**
 * 封装 Room 数据库，供桌面小组件等外部组件跨进程查询。
 */
class ItemContentProvider : ContentProvider() {

    private val db: AppDatabase by lazy { AppDatabase.getInstance(context!!) }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = when (MATCHER.match(uri)) {
        ITEMS -> "vnd.android.cursor.dir/$MIME_DIR"
        ITEM_ID -> "vnd.android.cursor.item/$MIME_DIR"
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        if (MATCHER.match(uri) != ITEMS) return null
        val items = runBlocking { db.itemDao().getRecent(MAX_RESULTS) }
        val cursor = MatrixCursor(COLUMNS)
        items.forEach { item ->
            cursor.addRow(arrayOf<Any>(item.id, item.title, item.updatedAt))
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (MATCHER.match(uri) != ITEMS || values == null) return null
        val title = values.getAsString(COLUMN_TITLE) ?: "untitled"
        val id = runBlocking { db.itemDao().insert(ItemEntity(title = title)) }
        return ContentUris.withAppendedId(CONTENT_URI, id)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0

    companion object {
        const val AUTHORITY = "com.example.composeapp.provider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/items")

        const val COLUMN_ID = "id"
        const val COLUMN_TITLE = "title"
        const val COLUMN_UPDATED_AT = "updatedAt"
        val COLUMNS = arrayOf(COLUMN_ID, COLUMN_TITLE, COLUMN_UPDATED_AT)

        private const val MIME_DIR = "com.example.composeapp.item"
        private const val ITEMS = 1
        private const val ITEM_ID = 2
        private const val MAX_RESULTS = 20

        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "items", ITEMS)
            addURI(AUTHORITY, "items/#", ITEM_ID)
        }
    }
}
