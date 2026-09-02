package com.example.composeapp.ui.timetable

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.example.composeapp.data.EntryWithCourse
import com.example.composeapp.ui.theme.courseBlockColors
import java.io.File
import java.time.LocalDate

/**
 * 课表分享：把当前周完整课表离屏绘制为位图（自绘 Canvas，与屏幕截屏无关，
 * 可包含超出屏幕的部分），存 cache 后经 FileProvider 调起系统分享面板。
 * 分享图固定浅色风格（白底 + 浅色课程容器色），适合转发查看。
 */
object TimetableShare {

    /** 绘制整周课表位图。 */
    fun renderWeek(
        week: Int,
        monday: LocalDate?,
        entries: List<EntryWithCourse>,
        visibleDays: List<Int>,
        maxSection: Int,
        showNonCurrentWeek: Boolean,
    ): Bitmap {
        val axisW = 84f
        val dayW = if (visibleDays.size >= 7) 138f else 190f
        val rowH = 118f
        val headH = 132f
        val dayHeadH = 84f
        val pad = 26f
        val width = (pad * 2 + axisW + dayW * visibleDays.size).toInt()
        val height = (pad + headH + dayHeadH + rowH * maxSection + pad).toInt()

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x33, 0x33, 0x66)
            textSize = 54f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x77, 0x76, 0x80)
            textSize = 30f
        }
        val dayHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x47, 0x46, 0x4F)
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x47, 0x46, 0x4F)
            textSize = 26f
            textAlign = Paint.Align.CENTER
        }
        val linePaint = Paint().apply {
            color = Color.argb(36, 0, 0, 0)
            strokeWidth = 1.5f
        }

        // ---- 标题区：第 N 周 + 日期范围 ----
        var y = pad + 52f
        canvas.drawText("简课表 · 第 $week 周", pad, y, titlePaint)
        y += 40f
        canvas.drawText(
            monday?.let {
                val end = monday.plusDays((visibleDays.last() - 1).toLong())
                "${it.monthValue}月${it.dayOfMonth}日 - ${end.monthValue}月${end.dayOfMonth}日"
            } ?: "未设置开学日期",
            pad, y, subPaint,
        )

        // ---- 星期表头 ----
        val gridTop = pad + headH
        val weekday = listOf("一", "二", "三", "四", "五", "六", "日")
        visibleDays.forEachIndexed { i, d ->
            val cx = pad + axisW + dayW * i + dayW / 2
            canvas.drawText(weekday[d - 1], cx, gridTop + 34f, dayHeadPaint)
            if (monday != null) {
                val date = monday.plusDays((d - 1).toLong())
                canvas.drawText(
                    "${date.monthValue}/${date.dayOfMonth}",
                    cx, gridTop + 68f, axisPaint,
                )
            }
        }

        // ---- 节次轴 + 网格线 ----
        val gridBottom = gridTop + dayHeadH + rowH * maxSection
        for (s in 1..maxSection) {
            val ly = gridTop + dayHeadH + rowH * (s - 1)
            canvas.drawLine(pad, ly, width - pad, ly, linePaint)
            canvas.drawText(s.toString(), pad + axisW / 2, ly + rowH / 2 + 9f, axisPaint)
        }
        canvas.drawLine(pad, gridBottom, width - pad, gridBottom, linePaint)
        for (i in 0..visibleDays.size) {
            val lx = pad + axisW + dayW * i
            canvas.drawLine(lx, gridTop, lx, gridBottom, linePaint)
        }

        // ---- 课程块（与屏幕一致的可见性规则 + 冲突分槽） ----
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 27f
            typeface = Typeface.DEFAULT_BOLD
        }
        val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f }
        val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF()

        visibleDays.forEachIndexed { dayIdx, d ->
            val inWeek = entries.filter {
                it.dayOfWeek == d && (showNonCurrentWeek || it.isInWeek(week))
            }.sortedBy { it.startSection ?: 99 }
            // 贪心分槽：与同槽已放课程时间重叠则开新槽（与网格渲染一致）
            val slots = mutableListOf<MutableList<EntryWithCourse>>()
            for (e in inWeek) {
                val slot = slots.indexOfFirst { col -> col.none { it.sectionsOverlap(e) } }
                if (slot >= 0) slots[slot].add(e) else slots.add(mutableListOf(e))
            }
            val slotW = dayW / slots.size.coerceAtLeast(1)
            for ((slotIdx, col) in slots.withIndex()) {
                for (e in col) {
                    val s0 = e.startSection ?: 1
                    val s1 = e.endSection ?: s0
                    val left = pad + axisW + dayW * dayIdx + slotW * slotIdx + 3f
                    val top = gridTop + dayHeadH + rowH * (s0 - 1) + 3f
                    rect.set(left, top, left + slotW - 6f, top + rowH * (s1 - s0 + 1) - 6f)
                    val (container, onContainer) = courseBlockColors(e.colorIndex, darkTheme = false)
                    blockPaint.color = container.toArgb()
                    blockPaint.alpha = if (e.isInWeek(week)) 255 else 96
                    canvas.drawRoundRect(rect, 14f, 14f, blockPaint)

                    val tx = rect.left + 8f
                    var ty = rect.top + 32f
                    namePaint.color = onContainer.toArgb()
                    val lines = wrap(e.courseName + typeSymbol(e.type), rect.width() - 16f, namePaint, 6)
                    for (line in lines) {
                        if (ty > rect.bottom) break
                        canvas.drawText(line, tx, ty, namePaint)
                        ty += 32f
                    }
                    // 高度足够时追加 教师@地点
                    if (rect.height() >= rowH * 1.5f && (e.teacher.isNotBlank() || shortLoc(e).isNotBlank())) {
                        infoPaint.color = onContainer.toArgb()
                        infoPaint.alpha = 200
                        val info = buildString {
                            if (e.teacher.isNotBlank()) append(e.teacher)
                            val loc = shortLoc(e)
                            if (loc.isNotBlank()) append("@").append(loc)
                        }
                        for (line in wrap(info, rect.width() - 16f, infoPaint, 6)) {
                            if (ty > rect.bottom) break
                            canvas.drawText(line, tx, ty, infoPaint)
                            ty += 26f
                        }
                    }
                }
            }
        }
        return bmp
    }

    /** 存图并调起系统分享面板。 */
    fun share(context: Context, bitmap: Bitmap, week: Int) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "timetable_week_$week.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "分享课表"))
    }

    // ---- 绘制辅助 ----

    private fun shortLoc(e: EntryWithCourse): String {
        if (e.room.isBlank() || e.room == "未排地点") return e.room.ifBlank { e.building.ifBlank { e.campus } }
        val building = if (!e.room.contains(e.building)) e.building else ""
        return listOf(building, e.room).filter { it.isNotBlank() }.joinToString("")
    }

    private fun EntryWithCourse.sectionsOverlap(other: EntryWithCourse): Boolean {
        val a0 = startSection ?: 1; val a1 = endSection ?: a0
        val b0 = other.startSection ?: 1; val b1 = other.endSection ?: b0
        return a0 <= b1 && b0 <= a1
    }

    /** 逐字符折行（CJK 无空格断词），超出 maxLines 截断。 */
    private fun wrap(text: String, maxW: Float, paint: Paint, maxLines: Int): List<String> {
        val lines = mutableListOf<String>()
        var cur = StringBuilder()
        for (ch in text) {
            cur.append(ch)
            if (paint.measureText(cur.toString()) > maxW) {
                cur.deleteCharAt(cur.length - 1)
                lines.add(cur.toString())
                if (lines.size == maxLines) return lines
                cur = StringBuilder(ch.toString())
            }
        }
        if (cur.isNotEmpty() && lines.size < maxLines) lines.add(cur.toString())
        return lines
    }

    private fun androidx.compose.ui.graphics.Color.toArgb(): Int =
        android.graphics.Color.argb(
            (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
        )
}
