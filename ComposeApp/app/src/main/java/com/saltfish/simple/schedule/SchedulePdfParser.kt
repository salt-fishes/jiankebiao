package com.saltfish.simple.schedule

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
 * 规则包：整页 OCR 完成后按指纹匹配 [ParseRulePack]（正方/图标行式/导入包），
 * 表头锚词、噪声词、类型标记、字段正则全部随包；全不中退内置正方包；
 * 表格重建：
 *  1. 从第 0 页表头行锚词的星期名左缘提取列边界，跨页共用（第 2 页无表头）；
 *  2. 每个 OCR 行按左缘 x 归属最近星期列，列内按 (top,left) 排序拼接；
 *  3. 课程块起始行开启新块，其余行并入当前块；标签词与纯数字节次行丢弃。
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

    suspend fun parse(uri: Uri, pack: ParseRulePack): ParsedSchedule {
        val fd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("无法打开文件")
        fd.use { return parse(it, pack) }
    }

    suspend fun parse(file: File, pack: ParseRulePack): ParsedSchedule {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        fd.use { return parse(it, pack) }
    }

    private suspend fun parse(fd: ParcelFileDescriptor, pack: ParseRulePack): ParsedSchedule {
        val log = StringBuilder()
        try {
            log.appendLine("opencv init: ${OpenCVUtils.init(context)}")
            val cellBlocks = mutableListOf<CellBlock>()
            var sectionAnchors: List<Pair<Float, Int>> = emptyList()   // (行顶 y, 节次号)
            var dayColMins: List<Float>? = null
            val merged = arrayOfNulls<Int>(7)   // 跨页课程块续行状态（索引指向共享的 cellBlocks）

            PdfRenderer(fd).use { renderer ->
                val pageCount = renderer.pageCount
                log.appendLine("pdf pages=$pageCount")
                // 渲染倍率自适应：像素字 PDF（强智/老正方把文字画成小矩形）在 2x 下
                // 文字仅十余像素高，OCR 大量丢行；按最长边放大到 ~2800px（2..4x）。
                // 分区数与重叠随倍率同步放大，保证 band 接缝仍盖住 ≥2 行文本。
                val longSide = renderer.openPage(0).use { maxOf(it.width, it.height) }
                val effScale = max(
                    options.renderScale,
                    (2800f / maxOf(longSide, 1)).coerceIn(options.renderScale, 4f),
                )
                val effOverlap = (options.bandOverlap * effScale / options.renderScale).toInt()
                val effBands = max(
                    options.bandCount,
                    (options.bandCount * effScale / options.renderScale).toInt(),
                )
                log.appendLine("rulePack=${pack.id} effectiveScale=$effScale bands=$effBands overlap=$effOverlap")
                // 1. PdfRenderer 渲染所有页为位图（可选落盘 PNG 供诊断）
                val pages = (0 until pageCount).map { i ->
                    renderer.openPage(i).use { page ->
                        renderPageToBitmap(page, effScale).also { bmp ->
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
                                recognizeByBands(ocr, bmp, effBands, effOverlap)
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
                    // 3. 第 0 页提取星期列边界（规则包由用户在导入弹窗显式指定，跨页共用）
                    if (i == 0) {
                        dayColMins = extractDayColumnMins(res, pack, log)
                        log.appendLine("dayColMins=$dayColMins")
                        if (dayColMins == null) {
                            throw IllegalStateException(
                                "未能定位课表表头（需要「${pack.headerAnchor.ifEmpty { "星期" }}」锚词或星期行），" +
                                    "当前格式可能不受支持；可在「我的 → 解析规则包」导入适配规则包"
                            )
                        }
                        // 节次数字锚（左轴纯数字行，如强智）：卡面无节次文本时由此回填节次。
                        // 值域 1..30（"0" 是 "10" 被 OCR 拆出的碎片；节数不可能为 0）
                        sectionAnchors = res.mapNotNull { r ->
                            val t = r.text.trim()
                            val left = r.box.points.minOf { it.x }
                            val v = if (SECTION_NO_RE.matches(t)) t.toInt() else -1
                            if (v in 1..30 && left < dayColMins!![0]) {
                                r.box.points.minOf { it.y } to v
                            } else null
                        }.sortedBy { it.first }
                        log.appendLine("sectionAnchors=${sectionAnchors.size}")
                    }
                    if (dayColMins != null) {
                        buildCellBlocks(res, dayColMins!!, pack, merged, cellBlocks, log)
                    }
                }
            }
            log.appendLine("cellBlocks=${cellBlocks.size}")
            cellBlocks.forEach { b ->
                log.appendLine("  [${b.day}] ${b.text.replace('\n', ' ').take(110)}")
            }
            writeLog(log)   // 提前落盘定位崩溃点

            val courses = mutableListOf<ParsedCourse>()
            val entries = mutableListOf<ParsedEntry>()
            val seen = mutableMapOf<Triple<String, String, String>, Int>()

            for (cell in cellBlocks) {
                val dayIdx = cell.day
                val gridSections = sectionRangeFor(cell.top, cell.bottom, sectionAnchors)
                for (c in ScheduleParser.parseCell(cell.text, pack)) {
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
                    val start = c.sections.getOrNull(0) ?: gridSections.first
                    val end = c.sections.getOrNull(1)
                        ?: c.sections.getOrNull(0)
                        ?: gridSections.second
                    entries.add(
                        ParsedEntry(
                            course = normName, dayOfWeek = dayIdx,
                            startSection = start,
                            endSection = end,
                            weeks = c.weeks,
                            campus = c.campus, building = c.building, room = c.room,
                            teacher = c.teacher,
                        )
                    )
                }
            }

            // 同课同天、节次区间重叠或相邻（±1 节）的条目合并取并集：平行教学班
            // （实验课多组教师）、band 接缝重复框、网格误拆的连堂都会产生这种条目；
            // 周次为空视为通配（碎片块常缺【周次】行）。真实课表里同课同天紧挨着
            // 只会是一条连堂，合并不会吃掉真实条目。
            val dedupedEntries = mutableListOf<ParsedEntry>()
            for (e in entries) {
                val hit = dedupedEntries.firstOrNull { k ->
                    k.course == e.course && k.dayOfWeek == e.dayOfWeek &&
                        (k.weeks.isEmpty() || e.weeks.isEmpty() || k.weeks == e.weeks) &&
                        (k.startSection ?: 99) <= (e.endSection ?: -1) + 1 &&
                        (e.startSection ?: 99) <= (k.endSection ?: -1) + 1
                }
                if (hit == null) {
                    dedupedEntries.add(e)
                } else {
                    val s = minOf(hit.startSection ?: e.startSection ?: 1, e.startSection ?: hit.startSection ?: 1)
                    val en = maxOf(hit.endSection ?: e.endSection ?: s, e.endSection ?: hit.endSection ?: s)
                    dedupedEntries[dedupedEntries.indexOf(hit)] = hit.copy(
                        startSection = s,
                        endSection = en,
                        weeks = if (hit.weeks.isEmpty()) e.weeks else hit.weeks,
                        teacher = hit.teacher.ifBlank { e.teacher },
                        room = hit.room.ifBlank { e.room },
                    )
                }
            }
            log.appendLine("entries ${entries.size} -> ${dedupedEntries.size} after overlap-merge")
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
            return ParsedSchedule(mergedByName.values.toList(), dedupedEntries)
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

    private fun renderPageToBitmap(page: PdfRenderer.Page, scale: Float): Bitmap {
        val w = (page.width * scale).toInt()
        val h = (page.height * scale).toInt()
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
    private suspend fun recognizeByBands(
        ocr: PaddleOCR,
        page: Bitmap,
        bandCount: Int,
        bandOverlap: Int,
    ): List<OCRResult> {
        val bands = bandCount.coerceAtLeast(1)
        val bandH = (page.height + bands - 1) / bands
        val all = mutableListOf<OCRResult>()
        for (b in 0 until bands) {
            val top = max(0, b * bandH - bandOverlap)
            val bottom = min(page.height, (b + 1) * bandH + bandOverlap)
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

    private val SECTION_NO_RE = Regex("^\\d{1,2}$")
    /** 星期单元格（「星期一」/「周一」…），用于无锚词时的表头行回退识别。 */
    private val DAY_CELL_RE = Regex("(?:星期|周)?[一二三四五六日天]")

    private fun isNoiseLine(text: String, pack: ParseRulePack): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t in pack.labelWords || SECTION_NO_RE.matches(t)) return true
        // 页面装饰行（图例/打印时间等），无论是否冒号开头一律丢弃；
        // 注意不能只按 startsWith(":") 判断——属性区换行常把冒号断到行首（如 ":示例班1;…/:/学分/:3.0"）
        if (pack.legendWords.any { it in t }) return true
        if (t.startsWith(":") && pack.typeMarks.values.any { it in t }) return true
        return false
    }

    /**
     * 从第 0 页提取星期列左缘 x（跨页共用列边界）。
     * 优先锚词行（「时间段」）；其他教务系统（强智等）表头没有锚词，
     * 回退为「星期词最多的一行」作为表头行，列数允许缺 1（无周日列的课表）。
     */
    private fun extractDayColumnMins(
        results: List<OCRResult>,
        pack: ParseRulePack,
        log: StringBuilder,
    ): List<Float>? {
        val lines = results.map { r ->
            val pts = r.box.points
            OcrLine(r.text, pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
        }
        if (pack.headerAnchor.isNotEmpty()) {
            val header = lines.firstOrNull { it.text.contains(pack.headerAnchor) }
            if (header != null) {
                val dayMins = lines
                    .filter { abs(it.top - header.top) <= ROW_GAP && it.text.contains(pack.dayPattern) }
                    .map { it.left }
                    .sorted()
                if (dayMins.size >= pack.dayCount) return dayMins.take(pack.dayCount)
                if (dayMins.size >= pack.dayCount - 1) return dayMins
                log.appendLine("anchor '${pack.headerAnchor}' found but day cols=${dayMins.size}")
            }
        }
        // 回退：星期词最密集的一行 = 表头行
        val dayLines = lines
            .filter { it.text.contains(pack.dayPattern) || DAY_CELL_RE.containsMatchIn(it.text) }
            .sortedBy { it.top }
        if (dayLines.isNotEmpty()) {
            var best = listOf(dayLines.first())
            var cluster = listOf(dayLines.first())
            for (l in dayLines.drop(1)) {
                cluster = if (l.top - cluster.last().top <= ROW_GAP) cluster + l else listOf(l)
                if (cluster.size > best.size) best = cluster
            }
            if (best.size >= pack.dayCount - 1) {
                val mins = best.map { it.left }.sorted()
                log.appendLine("day-row fallback: ${best.size} cols @top=${best.first().top.toInt()}")
                return if (mins.size >= pack.dayCount) mins.take(pack.dayCount) else mins
            }
            log.appendLine("day-row fallback best=${best.size} cols, below threshold")
        }
        return null
    }

    /** 一个课程单元格：星期列 + 拼接文本 + 纵向跨度（节次回填用）。 */
    data class CellBlock(val day: Int, var text: String, var top: Float, var bottom: Float)

    private fun buildCellBlocks(
        results: List<OCRResult>,
        dayColMins: List<Float>,
        pack: ParseRulePack,
        merged: Array<Int?>,
        cells: MutableList<CellBlock>,
        log: StringBuilder,
    ) {
        if (results.isEmpty()) return
        val lines = results.map { r ->
            val pts = r.box.points
            OcrLine(r.text, pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
        }.filter { !isNoiseLine(it.text, pack) }

        // 每个文本行归属最近星期列
        val byCol = Array(7) { mutableListOf<OcrLine>() }
        for (line in lines) {
            val col = nearestIndex(line.left, dayColMins)
            byCol[col].add(line)
        }

        // 列内按 (top, left) 排序拼接 -> 课程块（跨页续行状态 merged 保留；带纵向跨度）
        val cellLines = mutableListOf<Triple<Int, String, Pair<Float, Float>>>()
        for (col in 0 until dayColMins.size) {
            val sorted = byCol[col].sortedWith(compareBy({ it.top }, { it.left }))
            for (l in sorted) {
                if (l.text.isNotBlank()) {
                    cellLines.add(Triple(col, l.text, l.top to l.bottom))
                }
            }
        }
        for ((col, text, span) in cellLines) {
            if (ScheduleParser.startsCourseBlock(text, pack)) {
                merged[col] = cells.size
                cells.add(CellBlock(col + 1, text, span.first, span.second))
            } else {
                val idx = merged[col]
                if (idx != null) {
                    val b = cells[idx]
                    b.text = b.text + "\n" + text
                    b.top = min(b.top, span.first)
                    b.bottom = max(b.bottom, span.second)
                } else {
                    // 无课程块可并入的孤儿行：暂记日志，丢弃
                    log.appendLine("orphan[$col]: ${text.replace('\n', ' ').take(40)}")
                }
            }
        }
    }

    /**
     * 单元格纵向跨度 → 节次区间（卡面无节次文本时的回退，如强智）。
     * 用最近邻锚匹配而非单向阈值：教务网格行高不均（上午/下午间有空档），
     * 单元格文本顶/底各自贴近真实的节次行位置。
     */
    private fun sectionRangeFor(
        top: Float,
        bottom: Float,
        anchors: List<Pair<Float, Int>>,
    ): Pair<Int?, Int?> {
        if (anchors.isEmpty()) return null to null
        val s = anchors.minByOrNull { abs(it.first - top) }?.second
        val e = anchors.minByOrNull { abs(it.first - bottom) }?.second
        if (s == null && e == null) return null to null
        // 归一：缺一侧时取另一侧；end 不小于 start
        val start = s ?: e ?: return null to null
        val end = e ?: start
        return start to maxOf(end, start)
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
