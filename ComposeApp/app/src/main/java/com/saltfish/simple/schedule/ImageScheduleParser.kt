package com.saltfish.simple.schedule

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * 图片识别建课表：课表截图 → 占用结构（OccupancyParser）→ 逐实心块裁剪 OCR →
 * 规则包解析字段 → ParsedSchedule（走与 PDF 导入相同的确认入库流程）。
 *
 * 与「课表对比」的区别：对比只要占用格；建课表需要课程内容（课名/教师/地点）。
 * 块 ↔ 课程映射：占用块按连续色块切分，天然与课程卡一对一；节次优先取卡面
 * 文本里的 "(N-M节)"，缺失时回退占用网格的行区间。
 * 已知边界（导入确认界面提示用户核对）：
 *  - 截图只展示一周：单双周信息丢失，周次默认填满整学期；
 *  - 「非本周课程」的描边空心卡无文字，自动跳过；
 *  - 课程内容 OCR 错字率高于占用识别，导入前务必逐条核对。
 */
class ImageScheduleParser(
    private val context: android.content.Context,
    private val totalWeeks: Int = 17,
) {

    suspend fun parse(file: File, pack: ParseRulePack): ParsedSchedule {
        val src = BitmapFactory.decodeFile(file.absolutePath)
            ?: throw IllegalStateException("无法读取图片文件")
        val (grid, work) = OccupancyParser.parseWithWorkBitmap(context, src)
        try {
            check(grid.blocks.isNotEmpty()) { "未能从图片中识别出课程块，请确认截图包含完整课表" }

            // 1. 逐块裁剪 OCR（单实例复用；裁剪含少量边距防贴边截断）
            val ocr = PaddleOCR.create(context, PaddleOCRConfig())
            data class CellText(
                val day: Int,
                val text: String,
                val gridStart: Int,
                val gridEnd: Int,
            )
            val cells = mutableListOf<CellText>()
            try {
                for (b in grid.blocks) {
                    val crop = cropBlock(work, b.rawRect) ?: continue
                    try {
                        val text = recognizeText(ocr, crop)
                        if (text.isNotBlank()) {
                            cells += CellText(b.day, text, b.startSection, b.endSection)
                        }
                    } finally {
                        crop.recycle()
                    }
                }
            } finally {
                ocr.release()
            }
            check(cells.isNotEmpty()) { "课程块内未识别到文字，请确认截图清晰度" }
            // 逐块 OCR 原文落盘（files/ocr_debug_image.txt）：前缀路由问题排查入口
            runCatching {
                File(context.filesDir, "ocr_debug_image.txt").writeText(
                    cells.joinToString("\n\n") { c ->
                        "[day ${c.day} ${c.gridStart}-${c.gridEnd}节]\n${c.text}"
                    }
                )
            }

            // 2. 规则包由用户在导入弹窗显式指定，逐格解析字段（命中轨迹落盘诊断）
            val courses = mutableListOf<ParsedCourse>()
            val entries = mutableListOf<ParsedEntry>()
            val seenCourse = mutableMapOf<Triple<String, String, String>, Int>()
            val seenEntry = mutableSetOf<String>()
            val defaultWeeks = (1..totalWeeks.coerceIn(1, 30)).toList()
            val traceLog = StringBuilder()

            for (cell in cells) {
                val (cellCourses, cellTrace) = ScheduleParser.parseCellTraced(cell.text, pack)
                cellTrace.blocks.forEach { traceLog.appendLine("[day ${cell.day}] ${it.render()}") }
                cellTrace.unmatchedLines.forEach { traceLog.appendLine("[day ${cell.day}] 未入块: $it") }
                for (c in cellCourses) {
                    val normName = c.name.replace(" ", "").replace("　", "")
                        .replace("（", "(").replace("）", ")")
                    if (normName.isBlank() || normName.length == 1) continue
                    val key = Triple(normName, c.type, c.credit)
                    if (key !in seenCourse) {
                        seenCourse[key] = courses.size
                        courses.add(
                            ParsedCourse(
                                name = normName, type = c.type, credit = c.credit,
                                teacher = c.teacher, campus = c.campus,
                                building = c.building, room = c.room,
                                classNo = c.classNo, composition = c.composition,
                            )
                        )
                    }
                    // 节次：卡面 "(N-M节)" 优先，缺失回退占用网格行区间
                    val start = c.sections.getOrNull(0) ?: cell.gridStart
                    val end = c.sections.getOrNull(1) ?: cell.gridEnd
                    val dedupKey = "$normName|${cell.day}|$start|$end|${c.room}"
                    if (!seenEntry.add(dedupKey)) continue
                    entries.add(
                        ParsedEntry(
                            course = normName,
                            dayOfWeek = cell.day,
                            startSection = start,
                            endSection = max(start, end),
                            // 截图只展示一周，周次信息缺失时默认整学期（确认界面可改）
                            weeks = if (c.weeks.isEmpty()) defaultWeeks else c.weeks,
                            campus = c.campus,
                            building = c.building,
                            room = c.room,
                            teacher = c.teacher,
                        )
                    )
                }
            }
            check(entries.isNotEmpty()) {
                "识别到课程块但未解析出课程内容，请确认截图为课程表（而非对比占用图）"
            }
            // 规则命中轨迹追加到调试文件（规则包作者据此定位缺失规则）
            if (traceLog.isNotEmpty()) {
                runCatching {
                    File(context.filesDir, "ocr_debug_image.txt").appendText("\n---- 规则命中轨迹 ----\n$traceLog")
                }
            }

            // 同名课程合并非空字段（与 PDF 解析一致的兜底）
            val mergedByName = linkedMapOf<String, ParsedCourse>()
            for (c in courses) {
                val prev = mergedByName[c.name]
                mergedByName[c.name] = if (prev == null) c else prev.copy(
                    type = prev.type.ifBlank { c.type },
                    credit = prev.credit.ifBlank { c.credit },
                    teacher = prev.teacher.ifBlank { c.teacher },
                    campus = prev.campus.ifBlank { c.campus },
                    building = prev.building.ifBlank { c.building },
                    room = prev.room.ifBlank { c.room },
                    classNo = prev.classNo.ifBlank { c.classNo },
                    composition = prev.composition.ifBlank { c.composition },
                )
            }
            return ParsedSchedule(mergedByName.values.toList(), entries)
        } finally {
            work.recycle()
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 按块坐标裁剪工作图（含 2% 边距，clamp 到位图范围）；区域过小返回 null。 */
    private fun cropBlock(work: Bitmap, rect: android.graphics.RectF): Bitmap? {
        val padX = (rect.width() * 0.02f).toInt().coerceAtLeast(1)
        val padY = (rect.height() * 0.02f).toInt().coerceAtLeast(1)
        val x0 = max(0, (rect.left - padX).toInt())
        val y0 = max(0, (rect.top - padY).toInt())
        val x1 = min(work.width, (rect.right + padX).toInt())
        val y1 = min(work.height, (rect.bottom + padY).toInt())
        if (x1 - x0 < 12 || y1 - y0 < 12) return null
        return Bitmap.createBitmap(work, x0, y0, x1 - x0, y1 - y0)
    }

    /** 裁剪块 OCR：结果按 (top,left) 排序拼接为单元格文本。 */
    private suspend fun recognizeText(ocr: PaddleOCR, crop: Bitmap): String {
        val results = ocr.recognize(crop).results
        return results
            .sortedWith(
                compareBy(
                    { it.box.points.minOf { p -> p.y } },
                    { it.box.points.minOf { p -> p.x } },
                )
            )
            .joinToString("\n") { it.text.trim() }
            .trim()
    }
}
