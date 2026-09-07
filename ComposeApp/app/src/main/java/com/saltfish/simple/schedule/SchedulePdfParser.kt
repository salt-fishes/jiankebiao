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
 * 表格重建（格线网格优先，失败回退文本聚类）：
 *  1. 从第 0 页表头行锚词的星期名左缘提取列边界，左轴纯数字提取节次锚，跨页共用；
 *  2. 格线网格（[ScheduleGrid]）：渲染图像素探横线/竖线，节次锚定行、
 *     列内边界无横线段即连堂（rowspan 直接读出），OCR 框按中心落格；
 *  3. 回退：文本流聚类——每个 OCR 行按左缘 x 归属最近星期列，列内按 (top,left)
 *     排序拼接；课程块起始行开启新块，其余行并入当前块；节次按块几何对锚点最近邻。
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
                //    第 0 页位图保留到格线网格提取灰度后再回收
                val results = coroutineScope {
                    pages.mapIndexed { pi, bmp ->
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
                                if (pi != 0) bmp.recycle()
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
                    }
                    // 节次数字锚（左轴纯数字行）：每页独立提取——续页（正方第 2 页起）
                    // 坐标是页内局部 y，复用第 0 页网格会整页错标。
                    // 值域 1..30（"0" 是 "10" 被 OCR 拆出的碎片；节数不可能为 0）
                    val pageAnchors = res.mapNotNull { r ->
                        val t = r.text.trim()
                        val left = r.box.points.minOf { it.x }
                        val v = if (SECTION_NO_RE.matches(t)) t.toInt() else -1
                        if (v in 1..30 && left < dayColMins!![0]) {
                            r.box.points.minOf { it.y } to v
                        } else null
                    }.sortedBy { it.first }
                    if (i == 0) {
                        sectionAnchors = pageAnchors   // 回退路径的节次回填沿用
                        log.appendLine("sectionAnchors=${sectionAnchors.size}")
                    }
                    // 格线网格按页构建：锚不足 3 的页（无独立节次轴的续页）回退文本聚类
                    val pageGrid = runCatching { buildGrid(pages[i], dayColMins!!, pageAnchors, log) }
                        .onFailure { if (i == 0) log.appendLine("grid FAILED: ${it.javaClass.simpleName}: ${it.message}") }
                        .getOrNull()
                    if (pageGrid != null) {
                        buildCellBlocksGrid(res, pageGrid.first, pageGrid.second, pack, cellBlocks, log)
                    } else {
                        if (i == 0) log.appendLine("grid=null page$i (回退文本聚类)")
                        buildCellBlocks(res, dayColMins!!, pack, merged, cellBlocks, log)
                    }
                }
                runCatching { pages.forEach { it.recycle() } }   // 循环结束后统一回收
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
                val (cellCourses, cellTrace) = ScheduleParser.parseCellTraced(cell.text, pack)
                // 规则命中轨迹：规则包作者据此定位缺失/误判规则
                cellTrace.blocks.forEach { log.appendLine("  parse[${cell.day}] ${it.render()}") }
                cellTrace.unmatchedLines.forEach { log.appendLine("  parse[${cell.day}] 未入块: $it") }
                for ((ci, c) in cellCourses.withIndex()) {
                    // 节次回填优先级：卡面显式节次 > 格线模式 expandSpan（子块行标签
                    // 为核 + 无线扩展至横线段，同格双卡/连堂/粘连统一）>
                    // 块几何对锚点最近邻（回退路径）> 整格跨度
                    val gridSections: Pair<Int, Int> = if (cell.gridProbe != null && cell.gridCol != null) {
                        cellTrace.blocks.getOrNull(ci)?.lineIndices
                            ?.mapNotNull { cell.rowLabels.getOrNull(it) }
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { core ->
                                ScheduleGrid.expandSpan(
                                    cell.gridRows, core, cell.gridProbe!!,
                                    cell.gridCol!!.first, cell.gridCol!!.second,
                                    debug = { msg -> log.appendLine("  expand[$dayIdx#${c.name}] $msg") },
                                )
                            }
                            ?: run {
                                val lo = cell.rowLabels.minOrNull() ?: 1
                                lo to (cell.rowLabels.maxOrNull() ?: lo)
                            }
                    } else {
                        // 同格多课：按块自己的行几何回填节次（比整格跨度精确）
                        val blockSpan = cellTrace.blocks.getOrNull(ci)
                            ?.lineIndices?.mapNotNull { cell.spans.getOrNull(it) }
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { list -> list.minOf { it.first } to list.maxOf { it.second } }
                            ?: (cell.top to cell.bottom)
                        val (s, e) = sectionRangeFor(blockSpan.first, blockSpan.second, sectionAnchors)
                        (s ?: 1) to (e ?: s ?: 1)
                    }
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
        if (t.startsWith(":") && pack.compiled.typeWords.any { it in t }) return true
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

    /** 一个课程单元格：星期列 + 拼接文本 + 纵向跨度（节次回填用）。
     *  spans 与 text 的行一一对应（同格多课按块回填节次用）。
     *  gridRows/gridCol/gridProbe 非空 = 格线网格模式：节次由回填期 expandSpan
     *  （子块行标签为核 + 无线扩展至横线段）决定，rowLabels 与 text 行一一对应。 */
    internal data class CellBlock(
        val day: Int,
        var text: String,
        var top: Float,
        var bottom: Float,
        val spans: MutableList<Pair<Float, Float>> = mutableListOf(),
        val rowLabels: List<Int> = emptyList(),
        val gridRows: List<ScheduleGrid.RowBand> = emptyList(),
        val gridCol: Pair<Float, Float>? = null,
        val gridProbe: ScheduleGrid.SegmentProbe? = null,
    )

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
                cells.add(CellBlock(col + 1, text, span.first, span.second, mutableListOf(span)))
            } else {
                val idx = merged[col]
                if (idx != null) {
                    val b = cells[idx]
                    b.text = b.text + "\n" + text
                    b.top = min(b.top, span.first)
                    b.bottom = max(b.bottom, span.second)
                    b.spans.add(span)
                } else {
                    // 无课程块可并入的孤儿行：暂记日志，丢弃
                    log.appendLine("orphan[$col]: ${text.replace('\n', ' ').take(40)}")
                }
            }
        }
    }

    // ---- 格线网格（格线分块：行=锚点+横线吸附，连堂=列内边界无横线段） ----

    /** 渲染图灰度像素上的线段探测：横线=带内长暗行；竖线=带内长暗列。 */
    private class LineProbe(
        private val w: Int,
        private val h: Int,
        pixels: IntArray,
    ) : ScheduleGrid.SegmentProbe {
        private val gray = FloatArray(w * h) { i ->
            val c = pixels[i]
            ((c shr 16) and 0xFF) * 0.299f + ((c shr 8) and 0xFF) * 0.587f + (c and 0xFF) * 0.114f
        }
        private fun dark(x: Int, y: Int) = gray[y * w + x] < 170f   // 黑线(强智)与浅灰线(网页)都覆盖

        override fun findSegment(yFrom: Float, yTo: Float, x0: Float, x1: Float): Float? {
            val yA = yFrom.toInt().coerceIn(0, h - 1)
            val yB = yTo.toInt().coerceIn(0, h - 1)
            val xA = x0.toInt().coerceIn(0, w - 1)
            val xB = x1.toInt().coerceIn(0, w - 1)
            if (xB <= xA || yB < yA) return null
            val span = xB - xA + 1
            val bandMid = (yFrom + yTo) / 2f
            var bestY: Float? = null
            var bestDist = Float.MAX_VALUE
            for (y in yA..yB) {
                var run = 0
                var best = 0
                var darkCount = 0
                for (x in xA..xB) {
                    if (dark(x, y)) { run++; best = max(best, run); darkCount++ } else run = 0
                }
                // 长暗行 + 总暗量过半：横线（容断口，排除文字行——文字行断口密）
                if (best >= span * 0.55f && darkCount >= span * 0.4f) {
                    // 带内可能有多条线（如表格外框与真边界），取距带中心最近者
                    val d = abs(y - bandMid)
                    if (d < bestDist) { bestDist = d; bestY = y.toFloat() }
                }
            }
            return bestY
        }

        /** 在 [xFrom, xTo] 带内找竖线（表右缘定位用），返回线的 x（无则 null）。 */
        fun findVertical(xFrom: Float, xTo: Float, y0: Float, y1: Float): Float? {
            val xA = xFrom.toInt().coerceIn(0, w - 1)
            val xB = xTo.toInt().coerceIn(0, w - 1)
            val yA = y0.toInt().coerceIn(0, h - 1)
            val yB = y1.toInt().coerceIn(0, h - 1)
            if (yB <= yA) return null
            val span = yB - yA + 1
            for (x in xA..xB) {
                var run = 0
                var best = 0
                var darkCount = 0
                for (y in yA..yB) {
                    if (dark(x, y)) { run++; best = max(best, run); darkCount++ } else run = 0
                }
                if (best >= span * 0.5f && darkCount >= span * 0.35f) return x.toFloat()
            }
            return null
        }
    }

    /**
     * 从第 0 页渲染图构建格线网格。[dayColMins] 是星期表头文本左缘（略偏右于真实
     * 竖线），做列左缘用；最后一列右缘由竖线探测定位，找不到按列宽估计。
     * 节次锚先整流（严格递增），不足 3 个返回 null 走回退。
     * 返回 (网格, 像素线段探针)——探针供切块时做行边界线查询。
     */
    private fun buildGrid(
        page: Bitmap,
        dayColMins: List<Float>,
        anchorsRaw: List<Pair<Float, Int>>,
        log: StringBuilder,
    ): Pair<ScheduleGrid.Grid, ScheduleGrid.SegmentProbe>? {
        val anchors = ScheduleGrid.sanitizeAnchors(anchorsRaw)
        if (anchors.size < 3) return null
        // 锚等距校验：节次行距应大致均匀（±倍数内）。备注/图例行的数字会被 OCR
        // 误当节次锚（正方 PDF「(7-9)」→ 假锚 7@…/9@…），等距破坏 = 锚表不可信，
        // 此时网格整体不可用，返回 null 让该页回退文本聚类（卡面显式节次不受影响）。
        val gaps = (1 until anchors.size).map { anchors[it].first - anchors[it - 1].first }
            .sorted()
        val median = gaps[gaps.size / 2]
        if (median <= 0f || gaps.any { it < median * 0.45f || it > median * 2.2f }) {
            log.appendLine("grid anchors 非等距 gaps=$gaps median=$median → 回退")
            return null
        }
        val w = page.width
        val h = page.height
        val pixels = IntArray(w * h)
        page.getPixels(pixels, 0, w, 0, 0, w, h)
        val probe = LineProbe(w, h, pixels)

        val avgColW = (dayColMins.size - 1).takeIf { it > 0 }
            ?.let { (dayColMins.last() - dayColMins.first()) / it }
            ?: ((dayColMins.last() - dayColMins.first()).toFloat())
        // 最后一列右缘：在估算位置 ±0.4 列宽内找竖线
        val lastLeft = dayColMins.last()
        val rightEdge = probe.findVertical(
            lastLeft + avgColW * 0.6f, lastLeft + avgColW * 1.4f,
            anchors.first().first, anchors.last().first,
        ) ?: (lastLeft + avgColW)
        val colRanges = dayColMins.mapIndexed { i, left ->
            left to (if (i < dayColMins.size - 1) dayColMins[i + 1] else rightEdge)
        }
        val zoneX0 = dayColMins.first() + 4f
        val zoneX1 = rightEdge - 4f
        // 表上下缘：首末锚外推半行，附近有横线则吸附
        val firstGap = anchors[1].first - anchors[0].first
        val lastGap = anchors.last().first - anchors[anchors.size - 2].first
        val topEst = anchors.first().first - firstGap * 0.55f
        val botEst = anchors.last().first + lastGap * 0.55f
        val tableTop = probe.findSegment(topEst - firstGap * 0.3f, topEst + firstGap * 0.3f, zoneX0, zoneX1) ?: topEst
        val tableBottom = probe.findSegment(botEst - lastGap * 0.3f, botEst + lastGap * 0.3f, zoneX0, zoneX1) ?: botEst

        val rows = ScheduleGrid.buildRows(anchors, tableTop, tableBottom, probe, zoneX0, zoneX1)
        log.appendLine(
            "grid rows=${rows.size}(${rows.first().section}..${rows.last().section}) " +
                "cols=${colRanges.size} rightEdge=${rightEdge.toInt()}"
        )
        log.appendLine("grid anchors=" + anchors.joinToString { "${it.second}@${it.first.toInt()}" })
        log.appendLine("grid rowBounds=" + rows.joinToString { "${it.section}:[${it.top.toInt()},${it.bottom.toInt()}]" })
        return ScheduleGrid.Grid(colRanges, rows) to probe
    }

    /** 格线模式分块 v3：每列聚合为一个块（行按 y 排序），块携带行标签、
     *  行带表、列 x 范围与像素探针——节次由回填期 expandSpan 决定。
     *  表头星期词文本（「星期二」等短词）不进任何块。 */
    private fun buildCellBlocksGrid(
        results: List<OCRResult>,
        grid: ScheduleGrid.Grid,
        probe: ScheduleGrid.SegmentProbe,
        pack: ParseRulePack,
        cells: MutableList<CellBlock>,
        log: StringBuilder,
    ) {
        val boxes = results.map { r ->
            val pts = r.box.points
            ScheduleGrid.TextBox(
                r.text,
                pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y },
            )
        }.filter {
            !isNoiseLine(it.text, pack) &&
                !(it.text.length <= 6 && it.text.contains(pack.dayPattern))
        }
        for ((day, lines) in boxes.groupBy { grid.columnAt(it.cx) }) {
            if (day == null) continue
            val withLabel = lines.sortedWith(compareBy({ it.top }, { it.left }))
                .map { it to grid.rowSectionAt(it.cy) }
                .filter { it.second != null }
            if (withLabel.isEmpty()) continue
            val text = withLabel.joinToString("\n") { it.first.text }
            cells.add(
                CellBlock(
                    day = day, text = text,
                    top = withLabel.minOf { it.first.top },
                    bottom = withLabel.maxOf { it.first.bottom },
                    spans = mutableListOf(
                        withLabel.minOf { it.first.top } to withLabel.maxOf { it.first.bottom }
                    ),
                    rowLabels = withLabel.map { it.second!! },
                    gridRows = grid.rows,
                    gridCol = grid.colRanges[day - 1],
                    gridProbe = probe,
                )
            )
        }
        log.appendLine("grid colBlocks=${boxes.groupBy { grid.columnAt(it.cx) }.keys.count { it != null }}")
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
