package com.example.composeapp.schedule

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.OCRBox
import com.paddle.ocr.model.OCRResult
import com.paddle.ocr.util.OpenCVUtils
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * PaddleOCR 课表解析：PDF(PdfRenderer 渲染) → Bitmap → OCR(PP-OCRv6 tiny) → 文本框聚类重建表格 → 课程数据。
 *
 * 渲染：android.graphics.pdf.PdfRenderer（本 PDF 用 STSong-Light 非嵌入字体，PDFBox 无
 * 对应字体渲染出豆腐块，故用系统渲染器），2x 缩放 + 白底合成；
 * 分区识别：整页按高度切 band 逐块 OCR（小字更清晰），块间重叠区用 IoU 去重；
 * 表格重建：
 *  1. 从第 0 页表头行（含"时间段"）的星期名左缘提取列边界，跨页共用（第 2 页无表头）；
 *  2. 每个 OCR 行按左缘 x 归属最近星期列，列内按 (top,left) 排序拼接；
 *  3. 课程块起始行开启新块，其余行并入当前块；"上午/下午"标签与纯数字节次行丢弃。
 */
class SchedulePdfParser(
    private val context: Context,
    private val options: ParseOptions = ParseOptions(),
) {

    data class ParseOptions(
        val bandCount: Int = 4,          // 分区数（1 = 整页识别）
        val bandOverlap: Int = 80,       // 分区重叠像素（2x 渲染；≥2 行高，防接缝截断）
        val renderScale: Float = 2f,     // PdfRenderer 渲染缩放（2x → 1684x1190）
        val detBoxThresh: Float = 0.4f,
        val detLimitSideLen: Int = 128,
        val savePagePng: Boolean = true,
    )

    suspend fun parse(uri: Uri): ParsedSchedule {
        val fd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("无法打开文件")
        fd.use { return parse(it) }
    }

    suspend fun parse(file: File): ParsedSchedule {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        fd.use { return parse(it) }
    }

    private suspend fun parse(fd: ParcelFileDescriptor): ParsedSchedule {
        val log = StringBuilder()
        log.appendLine("options=$options")
        try {
            log.appendLine("opencv init: ${OpenCVUtils.init(context)}")
            val cellBlocks = mutableListOf<Pair<Int, String>>()
            var dayColMins: List<Float>? = null
            val merged = arrayOfNulls<Int>(7)   // 跨页课程块续行状态（索引指向共享的 cellBlocks）

            PdfRenderer(fd).use { renderer ->
                val pageCount = renderer.pageCount
                log.appendLine("pdf pages=$pageCount")
                // 1. PdfRenderer 渲染所有页为位图（可选落盘 PNG 供诊断）
                val pages = (0 until pageCount).map { i ->
                    renderer.openPage(i).use { page ->
                        renderPageToBitmap(page).also { bmp ->
                            if (options.savePagePng) savePagePng(bmp, i, log)
                        }
                    }
                }
                // 2. 每页独立 OCR 实例并行识别；页内按分区（band）识别保证小字精度
                val results = coroutineScope {
                    pages.map { bmp ->
                        async<List<OCRResult>> {
                            val ocr = PaddleOCR.create(
                                context,
                                PaddleOCRConfig(
                                    detBoxThresh = options.detBoxThresh,
                                    detLimitSideLen = options.detLimitSideLen,
                                )
                            )
                            try {
                                recognizeByBands(ocr, bmp)
                            } finally {
                                ocr.release()
                                bmp.recycle()
                            }
                        }
                    }.map { it.await() }
                }

                for ((i, res) in results.withIndex()) {
                    log.appendLine("page $i: ocrLines=${res.size}")
                    res.take(6).forEach { r ->
                        log.appendLine("   [${r.box.points.joinToString { "${it.x.toInt()},${it.y.toInt()}" }}] ${r.text.take(30)}")
                    }
                    // 物理实验课程 OCR 行诊断
                    res.filter { "物理" in it.text || "实验" in it.text }
                        .forEach { r ->
                            log.appendLine("   [物理][${r.box.points.joinToString { "${it.x.toInt()},${it.y.toInt()}" }}] ${r.text.take(50)}")
                        }
                    // 3. 从第 0 页提取列边界（仅一次），跨页共用
                    if (i == 0) {
                        dayColMins = extractDayColumnMins(res)
                        log.appendLine("dayColMins=$dayColMins")
                    }
                    if (dayColMins != null) {
                        buildCellBlocks(res, dayColMins!!, merged, cellBlocks, log)
                    }
                }
            }
            log.appendLine("cellBlocks=${cellBlocks.size}")
            cellBlocks.forEach { (c, t) ->
                log.appendLine("  [$c] ${t.replace('\n', ' ').take(110)}")
            }
            writeLog(log)   // 提前落盘定位崩溃点

            val courses = mutableListOf<ParsedCourse>()
            val entries = mutableListOf<ParsedEntry>()
            val seen = mutableMapOf<Triple<String, String, String>, Int>()

            for ((dayIdx, cell) in cellBlocks) {
                for (c in ScheduleParser.parseCell(cell)) {
                    val normName = c.name
                        .replace(" ", "").replace("　", "")
                        .replace("（", "(").replace("）", ")")
                    val key = Triple(normName, c.type, c.credit)
                    if (key !in seen) {
                        seen[key] = courses.size
                        courses.add(
                            ParsedCourse(
                                name = normName, type = c.type, credit = c.credit,
                                teacher = c.teacher, campus = c.campus,
                                building = c.building, room = c.room,
                                classNo = c.classNo, composition = c.composition,
                            )
                        )
                    }
                    entries.add(
                        ParsedEntry(
                            course = normName, dayOfWeek = dayIdx + 1,
                            startSection = c.sections.getOrNull(0),
                            endSection = c.sections.getOrNull(1),
                            weeks = c.weeks,
                            campus = c.campus, building = c.building, room = c.room,
                            teacher = c.teacher,
                        )
                    )
                }
            }
            log.appendLine("courses=${courses.size} entries=${entries.size}")

            // 同名课程合并（OCR 单块偶发漏读某字段，取跨块非空值）
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
        } catch (t: Throwable) {
            log.appendLine("EXCEPTION: ${t.javaClass.simpleName}: $t")
            t.stackTrace.take(6).forEach { log.appendLine("  at $it") }
            writeLog(log)
            throw t
        } finally {
            writeLog(log)
        }
    }

    private fun writeLog(log: StringBuilder) {
        runCatching {
            File(context.filesDir, "ocr_debug.txt").writeText(log.toString())
        }
    }

    // ---- PdfRenderer 页面渲染 ----

    private fun renderPageToBitmap(page: PdfRenderer.Page): Bitmap {
        val w = (page.width * options.renderScale).toInt()
        val h = (page.height * options.renderScale).toInt()
        val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        page.render(raw, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        val opaque = Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply {
            drawColor(Color.WHITE)
            drawBitmap(raw, 0f, 0f, null)
        }
        raw.recycle()
        return opaque
    }

    private fun savePagePng(bmp: Bitmap, index: Int, log: StringBuilder) {
        runCatching {
            File(context.filesDir, "page_$index.png")
                .outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { log.appendLine("savePng($index) failed: $it") }
    }

    // ---- 分区识别 ----

    /**
     * 按高度切 [bandCount] 条带逐块 OCR，块间重叠 [bandOverlap] 像素；
     * 块内坐标 + 顶部偏移映射回整页；重叠区用 IoU 去重（保留置信度更高者）。
     */
    private suspend fun recognizeByBands(ocr: PaddleOCR, page: Bitmap): List<OCRResult> {
        val bands = options.bandCount.coerceAtLeast(1)
        val bandH = (page.height + bands - 1) / bands
        val all = mutableListOf<OCRResult>()
        for (b in 0 until bands) {
            val top = max(0, b * bandH - options.bandOverlap)
            val bottom = min(page.height, (b + 1) * bandH + options.bandOverlap)
            val band = Bitmap.createBitmap(page, 0, top, page.width, bottom - top)
            try {
                val result = ocr.recognize(band)
                for (r in result.results) {
                    val mappedPts = r.box.points.map { PointF(it.x, it.y + top) }
                    all.add(OCRResult(OCRBox(mappedPts), r.text, r.confidence))
                }
            } catch (t: Throwable) {
                runCatching {
                    File(context.filesDir, "ocr_debug.txt")
                        .appendText("band $b EXCEPTION: ${t.javaClass.simpleName}: $t\n")
                }
            } finally {
                band.recycle()
            }
        }
        // 重叠去重：IoU >= 0.35 视为同一文本行，保留置信度高者
        val kept = mutableListOf<OCRResult>()
        for (r in all.sortedByDescending { it.confidence }) {
            val dup = kept.any { boxIoU(it.box, r.box) >= 0.35f }
            if (!dup) kept.add(r)
        }
        return kept.sortedWith(compareBy(
            { it.box.points.minOf { p -> p.y } },
            { it.box.points.minOf { p -> p.x } },
        ))
    }

    private fun boxIoU(a: OCRBox, b: OCRBox): Float {
        val aL = a.points.minOf { it.x }; val aR = a.points.maxOf { it.x }
        val aT = a.points.minOf { it.y }; val aB = a.points.maxOf { it.y }
        val bL = b.points.minOf { it.x }; val bR = b.points.maxOf { it.x }
        val bT = b.points.minOf { it.y }; val bB = b.points.maxOf { it.y }
        val ix = max(0f, min(aR, bR) - max(aL, bL))
        val iy = max(0f, min(aB, bB) - max(aT, bT))
        val inter = ix * iy
        if (inter <= 0f) return 0f
        val aArea = (aR - aL) * (aB - aT)
        val bArea = (bR - bL) * (bB - bT)
        return inter / (aArea + bArea - inter)
    }

    // ---- 表格重建（OCR 检测框聚类） ----

    private data class OcrLine(
        val text: String,
        val left: Float, val top: Float,
        val right: Float, val bottom: Float,
    )

    private val LABEL_STOP = setOf("上午", "下午", "晚上", "时间段", "节次")
    private val SECTION_NO_RE = Regex("^\\d{1,2}$")
    /** 图例行特征词（": 集中实践 ★: 讲课 ○: 实验…" 的碎片）。 */
    private val LEGEND_WORDS = listOf("集中实践", "讲课", "上机", "图例")

    private fun isNoiseLine(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t in LABEL_STOP || SECTION_NO_RE.matches(t)) return true
        // 页面装饰行（图例/打印时间等），无论是否冒号开头一律丢弃；
        // 注意不能只按 startsWith(":") 判断——属性区换行常把冒号断到行首（如 ":25微电1;…/:/学分/:3.0"）
        if ("打印时间" in t || "其他课程" in t || "图例" in t || "集中实践" in t) return true
        if (t.startsWith(":") && ("讲课" in t || "上机" in t)) return true
        return false
    }

    /** 从第 0 页表头行提取 7 个星期列的左缘 x（跨页共用列边界）。 */
    private fun extractDayColumnMins(results: List<OCRResult>): List<Float>? {
        val lines = results.map { r ->
            val pts = r.box.points
            OcrLine(r.text, pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
        }
        val header = lines.firstOrNull { it.text.contains("时间段") } ?: return null
        val dayMins = lines
            .filter { abs(it.top - header.top) <= ROW_GAP && it.text.contains("星期") }
            .map { it.left }
            .sorted()
        return if (dayMins.size >= 7) dayMins.take(7) else null
    }

    private fun buildCellBlocks(
        results: List<OCRResult>,
        dayColMins: List<Float>,
        merged: Array<Int?>,
        cells: MutableList<Pair<Int, String>>,
        log: StringBuilder,
    ) {
        if (results.isEmpty()) return
        val lines = results.map { r ->
            val pts = r.box.points
            OcrLine(r.text, pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
        }.filter { !isNoiseLine(it.text) }

        // 每个文本行归属最近星期列
        val byCol = Array(7) { mutableListOf<OcrLine>() }
        for (line in lines) {
            val col = nearestIndex(line.left, dayColMins)
            byCol[col].add(line)
        }

        // 列内按 (top, left) 排序拼接 -> 课程块（跨页续行状态 merged 保留）
        val cellLines = mutableListOf<Pair<Int, String>>()
        for (col in 0 until 7) {
            val sorted = byCol[col].sortedWith(compareBy({ it.top }, { it.left }))
            for (l in sorted) {
                if (l.text.isNotBlank()) cellLines.add(col to l.text)
            }
        }
        for ((col, text) in cellLines) {
            if (ScheduleParser.startsCourseBlock(text)) {
                merged[col] = cells.size
                cells.add(col to text)
            } else {
                val idx = merged[col]
                if (idx != null) {
                    cells[idx] = col to (cells[idx].second + "\n" + text)
                } else {
                    // 无课程块可并入的孤儿行：暂记日志，丢弃
                    log.appendLine("orphan[$col]: ${text.replace('\n', ' ').take(40)}")
                }
            }
        }
    }

    private fun nearestIndex(x: Float, centers: List<Float>): Int {
        var best = 0
        var bestDist = Float.MAX_VALUE
        for (i in centers.indices) {
            val d = abs(x - centers[i])
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    companion object {
        // 聚类阈值（像素，2x 渲染）
        private const val ROW_GAP = 15f
    }
}
