package com.example.composeapp.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.example.composeapp.R

/**
 * 桌面小组件：通过 ContentProvider 查询 Room 数据并渲染。
 */
class ItemWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            val views = buildRemoteViews(context)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    private fun buildRemoteViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_item)
        // 骨架：通过 ContentProvider 读取最新一条数据
        val latest = context.contentResolver
            .query(
                android.net.Uri.parse("content://com.example.composeapp.provider/items"),
                arrayOf("title"),
                null, null, "id DESC LIMIT 1"
            )
        if (latest != null) {
            if (latest.moveToFirst()) {
                val title = latest.getString(0)
                views.setTextViewText(R.id.widget_text, title)
            }
            latest.close()
        }
        return views
    }
}
