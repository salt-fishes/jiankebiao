package com.saltfish.simple.schedule

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 课表截图「占用格」识别 v4：只检测"星期 × 节次"哪些格子有课，不识别课程内容。
 *
 * 架构（结构 = 几何锚点，OCR 只贴标签）：
 *  1. 行结构 ← 左轴节次数字。左侧条带里做文字连通域 y 向聚类，取窄簇（纯数字形状），
 *     用「最长等差数列」（可跳缺失项、种子步长可等分、半步长禁令、末步长越界即弃）
 *     锁定节次网格；缺失节按最小二乘回填，首尾在数字带内有簇支撑时最多延伸 1 格。
 *  2. 列结构 ← 表头星期行。表头条带最底部文字带，按"间隙双峰"合并字符碎片后，
 *     跑同一等差数列；缺列回填。绿底白字横幅经双边差提字。
 *  3. 标签 ← PaddleOCR：轴条带纯数字（1..12）给节次号，表头一~日给星期号；
 *     OCR 失败或命中不足时退化为自上而下 1..K / 周一起。OCR 不参与结构，
 *     认错字只影响行/列命名，不影响格子命中。
 *  4. 占用 ← 列中心窗口（±0.20colSp）逐格彩色占比（S>25 且 V>70 超 12%）。
 *     部分课表把「非本周课程」画成贴列边界的描边空心卡，描边在窗口外、
 *     不会误判有课；legacy 管线同样按连通域「空心环」剔除这类轮廓。
 *
 * 任何一步失败退回 v2 色块管线。识别结果必须经人工校正界面确认后再保存。
 */
object OccupancyParser {

    /** 一个被占据的时间块：day 1..7（周一=1），section 1..N（自上而下）。 */
    data class Block(
        val day: Int,
        val startSection: Int,
        val endSection: Int,
        /** 原图中的块区域（供校正界面叠加显示，不参与存储）。 */
        val rawRect: RectF,
    )

    data class Grid(
        val dayCount: Int,
        val sectionCount: Int,
        val blocks: List<Block>,
        /** 每列对应的星期（1=周一..7=周日），长度=列数。 */
        val dayOfColumn: List<Int>,
        /** 识别坐标所在的工作图尺寸（识别内部降采样）；drawOverlay 按此映射回原图。 */
        val workWidth: Int = 0,
        val workHeight: Int = 0,
    )

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /** 识别入口。OCR 为挂起调用；彻底失败抛出可读异常（调用方展示）。 */
    suspend fun parse(context: Context, src: Bitmap): Grid {
        val (grid, work) = parseWithWorkBitmap(context, src)
        work.recycle()
        return grid
    }

    /**
     * 识别入口（保留工作图）：图片识别建课表需要按块坐标裁剪原图做内容 OCR，
     * 工作图（内部降采样）与 Grid.rawRect 同坐标系，一并返回，调用方负责 recycle。
     */
    suspend fun parseWithWorkBitmap(context: Context, src: Bitmap): Pair<Grid, Bitmap> {
        check(OpenCVUtils.init(context)) { "图像处理组件初始化失败" }
        val work = downsample(src, 1600)
        val grid = try {
            parseByAnchors(context, work)
        } catch (t: Throwable) {
            legacyParse(work)
        }
        return grid to work
    }

    // ------------------------------------------------------------------
    // v4：轴锚定
    // ------------------------------------------------------------------

    /** 文字连通域。 */
    private class Comp(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val area: Int) {
        val w get() = x1 - x0
        val h get() = y1 - y0
        val cx get() = (x0 + x1) / 2.0
        val cy get() = (y0 + y1) / 2.0
        val fill get() = area.toDouble() / max(w * h, 1)
    }

    /** 等差数列项：pos 为格位（可跳号），y 为像素位置。 */
    private data class ApTerm(val pos: Int, val y: Double)

    private class AxisResult(
        val rowCenters: List<Double>,
        val sp: Double,
        val axisRight: Double,
        val sectionLabels: List<Int>,
        val debug: String = "",
    )

    private class HeaderResult(
        val colCenters: List<Double>,
        val dayOfColumn: List<Int>,
    )

    private suspend fun parseByAnchors(context: Context, src: Bitmap): Grid {
        val W = src.width
        val H = src.height
        val gray = Mat()
        Utils.bitmapToMat(src, gray)
        Imgproc.cvtColor(gray, gray, Imgproc.COLOR_RGBA2GRAY)
        val px = ByteArray(W * H)
        gray.get(0, 0, px)
        gray.release()
        val bgGray = medianByte(px)
        val pixels = IntArray(W * H)
        src.getPixels(pixels, 0, W, 0, 0, W, H)
        val tableTopY = coloredTopRow(pixels, W, H)
        val axisEdge = coloredLeftEdge(pixels, W, H)

        val ocr = PaddleOCR.create(context, PaddleOCRConfig())
        try {
            val axis = detectAxis(px, pixels, W, H, bgGray, tableTopY, axisEdge, ocr, src)
                ?: throw IllegalStateException("未能定位节次轴")
            val header = detectHeader(px, W, H, bgGray, axis, ocr, src)
                ?: throw IllegalStateException("未能定位表头星期行")
            // 模式门控：强彩色占比低 = 网格表课表（教务网页截图等，白底+彩色文字、
            // 无彩色课程块），按文字块聚类判定占用；否则按彩色块逐行带判定。
            // 实测：正方网页网格 ≈3%，卡片式课表 ≈21%。
            val coloredFrac = coloredFraction(pixels, W, H)
            val colGaps = header.colCenters.sorted().zipWithNext { a, b -> b - a }
            val colSp = if (colGaps.isEmpty()) 0.1 * W else medianD(colGaps)
            val blocks = if (coloredFrac < 0.08) {
                buildBlocksByText(
                    px, pixels, W, H, bgGray,
                    header.colCenters, colSp, axis.rowCenters, axis.sp,
                    header.dayOfColumn, axis.sectionLabels, axis.axisRight,
                )
            } else {
                buildBlocks(
                    pixels, W, H, header.colCenters, axis.rowCenters, axis.sp,
                    header.dayOfColumn, axis.sectionLabels,
                )
            }
            check(blocks.isNotEmpty()) { "未能从图片中识别出课程块，请确认截图包含完整课表" }
            // 结构诊断落盘：行列边界丢了哪一行/列，从这里直接可见
            runCatching {
                java.io.File(context.filesDir, "occupancy_debug.txt").appendText(
                    "\n---- 结构诊断 ----\n" +
                        "axis.rowCenters=${axis.rowCenters.map { it.roundToInt() }}\n" +
                        "axis.sectionLabels=${axis.sectionLabels}\n" +
                        "axis.sp=${axis.sp.roundToInt()} axisRight=${axis.axisRight.roundToInt()}\n" +
                        "header.colCenters=${header.colCenters.map { it.roundToInt() }}\n" +
                        "header.dayOfColumn=${header.dayOfColumn.toList()}\n" +
                        "tableTopY=$tableTopY axisEdge=$axisEdge coloredFrac=$coloredFrac\n" +
                        "axis.debug=\${axis.debug}\n" +
                        "blocks=${blocks.map { "d${it.day}:${it.startSection}-${it.endSection}" }}\n",
                )
            }
            val sectionCount = min(15, max(blocks.maxOf { it.endSection }, axis.sectionLabels.maxOrNull() ?: 1))
            return Grid(
                dayCount = header.dayOfColumn.maxOrNull() ?: header.colCenters.size,
                sectionCount = sectionCount,
                blocks = blocks,
                dayOfColumn = header.dayOfColumn,
                workWidth = W,
                workHeight = H,
            )
        } finally {
            ocr.release()
        }
    }

    // ------------------------------------------------------------------
    // 行结构：左轴
    // ------------------------------------------------------------------

    /** 表格首列彩色块的左缘：[0.25H,0.95H] 行窗内，列向强彩色最长连续段 > 6%H。
     *  轴条带右界压到它之前，块内文字碎片就进不了轴候选。 */
    private fun coloredLeftEdge(pixels: IntArray, W: Int, H: Int): Int {
        val y0 = (0.25 * H).toInt()
        val y1 = (0.95 * H).toInt()
        val thr = (0.06 * H).toInt()
        for (x in 0 until W) {
            var best = 0
            var cur = 0
            for (y in y0 until y1) {
                val v = pixels[y * W + x]
                val rr = (v shr 16) and 0xFF
                val gg = (v shr 8) and 0xFF
                val bb = v and 0xFF
                val mx = max(rr, max(gg, bb))
                val mn = min(rr, min(gg, bb))
                val strong = mx > 80 && (mx - mn) * 255 > 60 * mx
                cur = if (strong) cur + 1 else 0
                if (cur > best) best = cur
            }
            if (best > thr) return x
        }
        return W
    }

    private suspend fun detectAxis(
        px: ByteArray,
        pixels: IntArray,
        W: Int,
        H: Int,
        bgGray: Int,
        tableTopY: Int,
        axisEdge: Int,
        ocr: PaddleOCR,
        src: Bitmap,
    ): AxisResult? {
        var stripW = max((0.18 * W).roundToInt(), 12)
        if (axisEdge > 12) stripW = min(stripW, axisEdge - 2)
        val texts = ccInRegion(px, W, 0, 0, stripW, H) { g -> g < bgGray - 40 }
            .filter { it.h in 9 until (0.05 * H).toInt() && it.area > 10 }
        if (texts.size < 4) return null
        val clusters = yClusters(texts, H)
        // 窄簇 = 节次数字形状；阈值有绝对下限（窄条带下 "11" 宽 26px 也不能丢）
        val narrow = clusters.filter { it.w < max(0.25 * stripW, 27.0) }
        if (narrow.size < 4) return null

        // 候选 = 几何窄簇（数字形状；时间行更宽被排除）。
        // OCR 纯数字只作行号标签，绝不做结构候选——时间行会被 OCR 拆成
        // 「08」「20」这类假数字锚，产生半步长节距（真机 235 的教训）。
        data class Anchor(val y: Double, val x0: Double, val num: Int?)
        val digitAnchors = mutableListOf<Anchor>()
        runCatching {
            val strip = Bitmap.createBitmap(src, 0, 0, stripW, H)
            val digitRegex = Regex("^[0-9]{1,2}$")
            for (r in ocr.recognize(strip).results) {
                val t = r.text.trim()
                if (!digitRegex.matches(t)) continue
                val num = t.toInt()
                if (num !in 1..12) continue
                val ys = r.box.points.map { it.y.toDouble() }
                val xs = r.box.points.map { it.x.toDouble() }
                digitAnchors += Anchor(ys.average(), xs.min(), num)
            }
        }
        val cand: List<Anchor> = narrow.map { Anchor(it.cy, it.x0.toDouble(), null) }
        if (cand.size < 4) return null
        val candYs = cand.map { it.y }.sorted()

        fun nearestX0(y: Double): Double = cand.minByOrNull { abs(it.y - y) }!!.x0

        // sp 下限：候选中位间距 ×0.70，禁掉「杂项+数字」链成的半步长复合 AP
        val medGap = medianD(candYs.zipWithNext { a, b -> b - a })
        val spLo = max(0.030 * H, 0.70 * medGap)

        fun scoreAps(pool: List<Double>): Pair<List<ApTerm>, IntArray>? {
            var best: Pair<List<ApTerm>, IntArray>? = null
            var bestScore = intArrayOf(0, 0)
            for (ap in allAps(pool, spLo, 0.115 * H)) {
                val xs = ap.map { nearestX0(it.y) }
                val medX = medianD(xs)
                // 数字列在条带左部（≤0.75）：卡片式课表 ≈0.4，网格表（无彩色轴边可压）
                // 条带更宽、数字列相对更靠右 ≈0.65，0.60 会误拒网格表
                if (medX > 0.75 * stripW) continue
                val kept = ap.filterIndexed { i, _ -> abs(xs[i] - medX) < 0.10 * stripW }
                val score = intArrayOf(kept.size, ap.size)
                if (best == null || score[0] > bestScore[0] ||
                    (score[0] == bestScore[0] && score[1] > bestScore[1])
                ) {
                    best = kept to score
                    bestScore = score
                }
            }
            return best
        }

        var best = scoreAps(candYs) ?: return null
        // x0 一致性精化：滤掉杂项后重跑一次
        scoreAps(best.first.map { it.y })?.let { if (it.second[0] >= 4) best = it }
        val keptAp = best.first
        if (keptAp.size < 4) return null

        // 表格顶之上的项（状态栏/标题栏杂项）剔除
        val filtered = if (tableTopY > 0) {
            val spT = (keptAp.last().y - keptAp.first().y) / max(keptAp.last().pos - keptAp.first().pos, 1)
            keptAp.filter { it.y > tableTopY - 0.5 * spT }
        } else keptAp
        if (filtered.size < 4) return null

        // 最小二乘重采样成完整均匀网格（回填缺失节）
        val (grid0, sp) = resampleGrid(filtered)
        val grid = grid0.toMutableList()

        // 端点延伸：下一格附近有「数字带内、形状像文字」的簇才延伸，每侧至多 1 格
        val medX0 = medianD(filtered.map { nearestX0(it.y) })
        val maxNW = narrow.maxOf { it.w }
        val bandL = medX0 - 6
        val bandR = medX0 + maxNW - 4

        fun support(yp: Double): Boolean = clusters.any { c ->
            abs(c.cy - yp) < 0.30 * sp && c.x0 <= bandR && c.x1 >= bandL &&
                c.h < 0.05 * H && c.w < max(0.5 * stripW, 45.0)
        }
        // 顶部延伸收紧到「窄簇 + 紧 x0 带」：表头角标（如"10月"）会撑出幽灵首行，
        // 整体行号错位比缺一行危害大
        fun supportTop(yp: Double): Boolean = narrow.any { c ->
            abs(c.cy - yp) < 0.30 * sp &&
                c.x0 >= medX0 - 0.08 * stripW && c.x0 <= medX0 + 0.08 * stripW &&
                c.h < 0.05 * H
        }
        if (grid.last() + sp < H - 0.02 * H && support(grid.last() + sp)) grid.add(grid.last() + sp)
        if (grid.first() - sp > 0.02 * H && supportTop(grid.first() - sp)) grid.add(0, grid.first() - sp)

        // 节次号标签：OCR 数字按 y 贴到网格行（0.35sp 内才可信，时间行的假数字
        // 落在两行之间不会被贴），缺失位置插值
        val marks = mutableMapOf<Int, Int>()
        for (a in digitAnchors) {
            val num = a.num ?: continue
            var bi = -1
            var bd = Double.MAX_VALUE
            grid.forEachIndexed { i, gy ->
                val d = abs(gy - a.y)
                if (d < bd) { bd = d; bi = i }
            }
            if (bi >= 0 && bd < 0.35 * sp && bi !in marks) marks[bi] = num
        }
        val sectionLabels = interpolateLabels(grid.size, marks)

        // 轴文本右界（含时间行；排除课程块侵入：x0 过右或过宽的组件不算）
        val lo = grid.first() - sp / 2
        val hi = grid.last() + sp / 2
        var axisRight = medX0 + 10
        for (c in texts) {
            if (c.cy in lo..hi && c.x0 < medX0 + 0.25 * stripW && c.w < 0.5 * stripW) {
                axisRight = max(axisRight, c.x1.toDouble())
            }
        }
        val debug = "texts=%d clusters=%d narrow=%d narrowCy=%s digitAnchors=%s marks=%s".format(
            texts.size, clusters.size, narrow.size,
            narrow.take(14).map { "%.0f(w%.0f,x0=%.0f)".format(it.cy, it.w, it.x0) },
            digitAnchors.map { "${it.num}@%.0f".format(it.y) },
            marks.entries.sortedBy { it.key },
        )
        return AxisResult(grid, sp, axisRight, sectionLabels, debug)
    }

    // ------------------------------------------------------------------
    // 列结构：表头星期行
    // ------------------------------------------------------------------

    private suspend fun detectHeader(
        px: ByteArray,
        W: Int,
        H: Int,
        bgGray: Int,
        axis: AxisResult,
        ocr: PaddleOCR,
        src: Bitmap,
    ): HeaderResult? {
        val sp = axis.sp
        val top = max(axis.rowCenters[0] - 0.5 * sp, 0.03 * H)
        if (top < 0.04 * H) return null
        val end = min(top + 0.30 * sp, H - 1.0).toInt()

        val small = mutableListOf<Comp>()
        for (c in ccInRegion(px, W, 0, 0, W, end) { g -> g < bgGray - 40 }) {
            if (c.w > 0.45 * W && c.h < 0.35 * H) {
                // 整宽横幅：双边差提字（绿底白字 / 灰底黑字）
                val med = regionMedian(px, W, c.x0, c.y0, c.x1, c.y1)
                for (t in ccInRegion(px, W, c.x0, c.y0, c.x1, c.y1) { g -> abs(g - med) > 25 }) {
                    if (t.h in (0.004 * H).toInt()..(0.08 * H).toInt() &&
                        t.w < 0.30 * W && t.area > 12 && t.fill < 0.65
                    ) small += Comp(c.x0 + t.x0, c.y0 + t.y0, c.x0 + t.x1, c.y0 + t.y1, t.area)
                }
            } else if (c.h >= (0.004 * H).toInt() && c.h <= (0.08 * H).toInt() &&
                c.w < 0.30 * W && c.area > 12 && c.fill < 0.65
            ) {
                small += c
            }
        }
        if (small.size < 4) return null

        // 最底部 ≥4 组件的文字带 = 星期行（可与日期行粘连）
        small.sortBy { it.cy }
        val bands = mutableListOf<MutableList<Comp>>()
        for (c in small) {
            val last = bands.lastOrNull()?.lastOrNull()
            if (last != null && c.cy - last.cy <= 0.018 * H) bands.last() += c
            else bands += mutableListOf(c)
        }
        val band = bands.lastOrNull { it.size >= 4 } ?: return null
        var centers = band.map { it.cx }.sorted()

        // 间隙双峰分裂：从大间隙往小试，取第一个「合并后单元数落在 4..8」的阈值。
        // 同列字符碎片间隙小、列间间隙大；星期行与日期行交错的半格碎片也能正确归列。
        run {
            val sorted = centers.sorted()
            val gaps = sorted.zipWithNext { a, b -> b - a }.filter { it >= 4 }.sortedDescending()
            for (i in 0 until gaps.size - 1) {
                if (gaps[i + 1] <= 3 || gaps[i] / gaps[i + 1] < 1.8) continue
                val thr = kotlin.math.sqrt(gaps[i] * gaps[i + 1])
                var units = 1
                for (g in sorted.zipWithNext { a, b -> b - a }) {
                    if (g >= thr) units++
                }
                if (units in 4..8) {
                    val merged = mutableListOf(centers[0])
                    for (b in centers.drop(1)) {
                        if (b - merged.last() < thr) merged[merged.lastIndex] = (merged.last() + b) / 2
                        else merged += b
                    }
                    centers = merged
                    break
                }
            }
        }

        var use: List<Double>? = null
        if (centers.size in 4..8) {
            val gaps = centers.zipWithNext { a, b -> b - a }
            val med = medianD(gaps)
            if (gaps.all { abs(it - med) < 0.4 * med }) use = centers
        }
        if (use == null) {
            val medGapU = medianD(centers.sorted().zipWithNext { a, b -> b - a })
            val spLoU = max(0.05 * W, 0.70 * medGapU)
            var bestAp: List<ApTerm>? = null
            var bestScore = intArrayOf(0, 0)
            for (ap in allAps(centers, spLoU, 0.22 * W, tol = 0.25)) {
                val ys = ap.map { it.y }
                val step = (ys.last() - ys.first()) / max(ap.last().pos - ap.first().pos, 1)
                val score = intArrayOf(ys.size, step.roundToInt())
                if (bestAp == null || score[0] > bestScore[0] ||
                    (score[0] == bestScore[0] && score[1] > bestScore[1])
                ) {
                    bestAp = ap; bestScore = score
                }
            }
            if (bestAp != null) {
                use = fillGaps(bestAp.map { it.y })
            }
        }
        var cols = use ?: return null
        val cutoff = axis.axisRight + 0.12 * sp
        cols = cols.filter { it > cutoff }
        if (cols.size !in 4..8) return null

        // OCR 星期标签 → 每列星期号；命中不足退化为周一起顺序编号
        val marks = mutableListOf<Pair<Double, Int>>() // (x 中心, 星期号)
        runCatching {
            val strip = Bitmap.createBitmap(src, 0, 0, W, end)
            for (r in ocr.recognize(strip).results) {
                val day = dayFromText(r.text) ?: continue
                marks += (r.box.points.map { it.x }.average()) to day
            }
        }
        val colSp = medianD(cols.sorted().zipWithNext { a, b -> b - a }).takeIf { it > 0 } ?: 0.1 * W
        val colMarks = mutableMapOf<Int, Int>()
        val used = BooleanArray(marks.size)
        cols.forEachIndexed { ci, cc ->
            var bi = -1
            var bd = 0.5 * colSp
            marks.forEachIndexed { mi, (mx, day) ->
                if (!used[mi]) {
                    val d = abs(mx - cc)
                    if (d < bd) { bd = d; bi = mi }
                }
            }
            if (bi >= 0) {
                colMarks[ci] = marks[bi].second
                used[bi] = true
            }
        }
        val dayOfColumn = if (colMarks.size >= 3) interpolateLabels(cols.size, colMarks)
        else (1..cols.size).toList()
        return HeaderResult(cols, dayOfColumn)
    }

    private fun verboseCheck(centers: List<Double>): Boolean = centers.size in 4..8

    /** 从表头文字推断星期号（1=周一..7=周日）；不含可信星期字返回 null。 */
    private fun dayFromText(text: String): Int? {
        val s = text.trim()
        if (s.isEmpty() || s.length > 5) return null
        val weekdays = "一二三四五六日天"
        for ((idx, ch) in s.withIndex()) {
            if (ch in weekdays) {
                if (idx == 0) return dayCharToNumber(ch)
                val prev = s[idx - 1]
                if (prev == '周' || prev == '期') return dayCharToNumber(ch)
                return null // 「第三周」这类非星期上下文
            }
        }
        return null
    }

    private fun dayCharToNumber(ch: Char): Int? = when (ch) {
        '一' -> 1
        '二' -> 2
        '三' -> 3
        '四' -> 4
        '五' -> 5
        '六' -> 6
        '日', '天' -> 7
        else -> null
    }

    // ------------------------------------------------------------------
    // 占用：逐格彩色占比
    // ------------------------------------------------------------------

    private fun buildBlocks(
        pixels: IntArray,
        W: Int,
        H: Int,
        colCenters: List<Double>,
        rowCenters: List<Double>,
        sp: Double,
        dayOfColumn: List<Int>,
        sectionLabels: List<Int>,
    ): List<Block> {
        val gaps = colCenters.sorted().zipWithNext { a, b -> b - a }
        val colSp = if (gaps.isEmpty()) 0.1 * W else medianD(gaps)
        val blocks = mutableListOf<Block>()
        for ((ci, cc) in colCenters.withIndex()) {
            val l = max(cc - 0.38 * colSp, 0.0)
            val r = min(cc + 0.38 * colSp, W.toDouble())
            if (r <= l) continue
            // 占用判定收在列中心 ±0.20colSp：部分课表把「非本周课程」画成贴着列
            // 边界（≈±0.37colSp）的描边空心卡，全带宽采样会蹭到描边误判有课；
            // 实心块彩色区 ≥±0.40colSp，中心窗口不受影响。rawRect 仍用全带宽。
            val cl = max(cc - 0.20 * colSp, 0.0)
            val cr = min(cc + 0.20 * colSp, W.toDouble())
            var spanStart = -1
            for ((ri, rc) in rowCenters.withIndex()) {
                // 采样带 ±0.36sp：比块身窄，行高不均的渗边与表头细线落不进来
                val t = max(rc - 0.36 * sp, 0.0)
                val b = min(rc + 0.36 * sp, H.toDouble())
                // 占用判定：彩色块占比（卡片式课表）。网格表课表（教务网页截图，
                // 白底深字无彩色块）走 buildBlocksByText 文字块聚类，不在此判定。
                val occupied = if (b <= t || cr <= cl) false else coloredRatio(pixels, W, H, cl, t, cr, b) > 0.12
                if (occupied && spanStart < 0) spanStart = ri
                if ((!occupied || ri == rowCenters.lastIndex) && spanStart >= 0) {
                    val spanEnd = if (occupied) ri else ri - 1
                    if (spanEnd >= spanStart) {
                        val st = max(rowCenters[spanStart] - 0.45 * sp, 0.0)
                        val en = min(rowCenters[spanEnd] + 0.45 * sp, H.toDouble())
                        blocks += Block(
                            day = dayOfColumn.getOrElse(ci) { ci + 1 },
                            startSection = sectionLabels.getOrElse(spanStart) { spanStart + 1 },
                            endSection = sectionLabels.getOrElse(spanEnd) { spanEnd + 1 },
                            rawRect = RectF(l.toFloat(), st.toFloat(), r.toFloat(), en.toFloat()),
                        )
                    }
                    spanStart = -1
                }
            }
        }
        return blocks
    }

    private fun coloredRatio(
        pixels: IntArray,
        W: Int,
        H: Int,
        l: Double,
        t: Double,
        r: Double,
        b: Double,
    ): Double {
        val x0 = l.toInt().coerceAtLeast(0)
        val x1 = r.toInt().coerceAtMost(W)
        val y0 = t.toInt().coerceAtLeast(0)
        val y1 = b.toInt().coerceAtMost(H)
        var total = 0
        var hit = 0
        var y = y0
        while (y < y1) {
            var x = x0
            val rowOff = y * W
            while (x < x1) {
                val v = pixels[rowOff + x]
                val rr = (v shr 16) and 0xFF
                val gg = (v shr 8) and 0xFF
                val bb2 = v and 0xFF
                val mx = max(rr, max(gg, bb2))
                val mn = min(rr, min(gg, bb2))
                total++
                if (mx > 70 && (mx - mn) * 255 > 25 * mx) hit++
                x += 2
            }
            y += 2
        }
        return if (total == 0) 0.0 else hit.toDouble() / total
    }

    /**
     * 暗色文字占比：窗口内灰度显著低于背景中位数即视为文字（蓝色等饱和彩字
     * 的灰度亮度同样远低于白底，可被捕捉）。保留供调试与后续策略使用。
     */
    private fun textRatio(
        pixels: IntArray,
        W: Int,
        H: Int,
        l: Double,
        t: Double,
        r: Double,
        b: Double,
    ): Double {
        val x0 = l.toInt().coerceAtLeast(0)
        val x1 = r.toInt().coerceAtMost(W)
        val y0 = t.toInt().coerceAtLeast(0)
        val y1 = b.toInt().coerceAtMost(H)
        val vals = ArrayList<Int>((x1 - x0) * (y1 - y0) / 4 + 1)
        var y = y0
        while (y < y1) {
            var x = x0
            val rowOff = y * W
            while (x < x1) {
                val v = pixels[rowOff + x]
                vals.add(max((v shr 16) and 0xFF, max((v shr 8) and 0xFF, v and 0xFF)))
                x += 2
            }
            y += 2
        }
        if (vals.isEmpty()) return 0.0
        vals.sort()
        val bg = vals[vals.size / 2]
        val thr = bg - 45
        var hit = 0
        for (v in vals) if (v < thr) hit++
        return hit.toDouble() / vals.size
    }

    /** 全图强彩色像素占比（步进采样）：区分卡片式课表（占比高）与网格表（仅彩色文字，占比低）。 */
    private fun coloredFraction(pixels: IntArray, W: Int, H: Int): Double {
        var total = 0
        var hit = 0
        var y = 0
        while (y < H) {
            var x = 0
            val off = y * W
            while (x < W) {
                val v = pixels[off + x]
                val rr = (v shr 16) and 0xFF
                val gg = (v shr 8) and 0xFF
                val bb = v and 0xFF
                val mx = max(rr, max(gg, bb))
                val mn = min(rr, min(gg, bb))
                total++
                if (mx > 70 && (mx - mn) * 255 > 25 * mx) hit++
                x += 4
            }
            y += 4
        }
        return if (total == 0) 0.0 else hit.toDouble() / total
    }

    /**
     * 网格表占用：文字块聚类。网格表（教务网页截图等）没有彩色课程块，
     * 课程文字在合并单元格内的纵向位置与节次行中心不对齐，逐行带采样必然
     * 错位；改为把同列的文字行聚成「块」，块的 y 范围覆盖哪些节次行即是
     * 哪些节次——与文字在单元格内的具体位置无关。
     */
    private fun buildBlocksByText(
        px: ByteArray,
        pixels: IntArray,
        W: Int,
        H: Int,
        bgGray: Int,
        colCenters: List<Double>,
        colSp: Double,
        rowCenters: List<Double>,
        sp: Double,
        dayOfColumn: List<Int>,
        sectionLabels: List<Int>,
        axisRight: Double,
    ): List<Block> {
        val blocks = mutableListOf<Block>()
        val topY = max(rowCenters[0] - 0.55 * sp, 0.0)
        for ((ci, cc) in colCenters.withIndex()) {
            val l = max(cc - 0.48 * colSp, axisRight + 2)
            val r = min(cc + 0.48 * colSp, W.toDouble())
            if (r <= l) continue
            // 该列的文字行组件（灰度显著低于背景；网格浅线亮于阈值不会入选）
            val comps = ccInRegion(px, W, l.toInt(), topY.toInt(), r.toInt(), H) { g -> g < bgGray - 40 }
                .filter { it.h in 4..(0.06 * H).toInt() && it.w > 4 && it.w < 0.45 * colSp * 2 && it.area > 8 }
                .sortedBy { it.cy }
            if (comps.isEmpty()) continue
            // 纵向聚类成块：行距小于 0.55sp 的相邻文字行归入同一块（同一课程的
            // 多行文本间隙小；跨课程的间隙更大时自然分开，分不开也没关系——
            // 上层按「课名行」拆分多课）
            val runs = mutableListOf<MutableList<Comp>>()
            for (c in comps) {
                val last = runs.lastOrNull()?.lastOrNull()
                if (last != null && c.cy - last.cy < 0.55 * sp) runs.last().add(c)
                else runs.add(mutableListOf(c))
            }
            for (run in runs) {
                val y0 = run.minOf { it.y0 }.toDouble()
                val y1 = run.maxOf { it.y1 }.toDouble()
                // 块覆盖的节次行：与行带（中心±0.5sp）相交即算覆盖
                var first = -1
                var lastRow = -1
                for ((ri, rc) in rowCenters.withIndex()) {
                    val rowTop = rc - 0.5 * sp
                    val rowBot = rc + 0.5 * sp
                    val overlap = min(y1, rowBot) - max(y0, rowTop)
                    if (overlap > 0.25 * sp) {
                        if (first < 0) first = ri
                        lastRow = ri
                    }
                }
                if (first < 0) continue
                val x0 = run.minOf { it.x0 }.toDouble()
                val x1 = run.maxOf { it.x1 }.toDouble()
                blocks += Block(
                    day = dayOfColumn.getOrElse(ci) { ci + 1 },
                    startSection = sectionLabels.getOrElse(first) { first + 1 },
                    endSection = sectionLabels.getOrElse(lastRow) { lastRow + 1 },
                    rawRect = RectF(
                        max(x0 - 4, l).toFloat(),
                        max(y0 - 4, topY).toFloat(),
                        min(x1 + 4, r).toFloat(),
                        min(y1 + 4, H.toDouble()).toFloat(),
                    ),
                )
            }
        }
        return blocks
    }

    /** 彩色行剖面：第一处横向彩色质量超阈的 y（表格首行块顶）。强彩优先，退弱彩。 */
    private fun coloredTopRow(pixels: IntArray, W: Int, H: Int): Int {
        val strong = DoubleArray(H)
        val weak = DoubleArray(H)
        var y = 0
        while (y < H) {
            var x = 0
            val off = y * W
            while (x < W) {
                val v = pixels[off + x]
                val rr = (v shr 16) and 0xFF
                val gg = (v shr 8) and 0xFF
                val bb = v and 0xFF
                val mx = max(rr, max(gg, bb))
                val mn = min(rr, min(gg, bb))
                if (mx > 70) {
                    val s = (mx - mn) * 255.0 / mx
                    if (s > 60 && mx > 80) strong[y] += 1.0
                    if (s > 25) weak[y] += 1.0
                }
                x += 2
            }
            y += 2
        }
        // 强彩 0.08W：表头里的小色块（高亮丸/图标行）不算表格；块行（≥1 列宽）才算
        val strongThr = 0.08 * W
        val weakThr = 0.15 * W
        y = 0
        while (y < H) {
            if (strong[y] > strongThr) return y
            y += 2
        }
        y = 0
        while (y < H) {
            if (weak[y] > weakThr) return y
            y += 2
        }
        return 0
    }

    // ------------------------------------------------------------------
    // 几何工具
    // ------------------------------------------------------------------

    /** 区域内按灰度谓词做连通域（工作图灰度缓冲）。 */
    private fun ccInRegion(
        px: ByteArray,
        W: Int,
        rx0: Int,
        ry0: Int,
        rx1: Int,
        ry1: Int,
        pred: (Int) -> Boolean,
    ): List<Comp> {
        val w = rx1 - rx0
        val h = ry1 - ry0
        if (w < 4 || h < 4) return emptyList()
        val mask = Mat(h, w, CvType.CV_8UC1)
        val buf = ByteArray(w * h)
        for (y in 0 until h) {
            val rowOff = (ry0 + y) * W + rx0
            for (x in 0 until w) {
                buf[y * w + x] = if (pred(px[rowOff + x].toInt() and 0xFF)) 0xFF.toByte() else 0
            }
        }
        mask.put(0, 0, buf)
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8)
        mask.release(); labels.release(); centroids.release()
        val out = mutableListOf<Comp>()
        for (i in 1 until n) {
            val x = stats.get(i, 0)[0].toInt()
            val y = stats.get(i, 1)[0].toInt()
            val cw = stats.get(i, 2)[0].toInt()
            val ch = stats.get(i, 3)[0].toInt()
            val area = stats.get(i, 4)[0].toInt()
            out += Comp(rx0 + x, ry0 + y, rx0 + x + cw, ry0 + y + ch, area)
        }
        stats.release()
        return out
    }

    private fun regionMedian(px: ByteArray, W: Int, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        val samples = ArrayList<Int>((x1 - x0) * (y1 - y0) / 8 + 1)
        var y = y0
        while (y < y1) {
            var x = x0
            val off = y * W
            while (x < x1) {
                samples.add(px[off + x].toInt() and 0xFF)
                x += 3
            }
            y += 3
        }
        if (samples.isEmpty()) return 255
        samples.sort()
        return samples[samples.size / 2]
    }

    /** y 向聚类（0.008H 间隙）：同一节的上下行文字合并。 */
    private fun yClusters(comps: List<Comp>, H: Int): List<Comp> {
        val out = mutableListOf<Comp>()
        for (c in comps.sortedBy { it.cy }) {
            val last = out.lastOrNull()
            if (last != null && c.cy - last.cy <= 0.008 * H) {
                out[out.lastIndex] = Comp(
                    min(last.x0, c.x0), min(last.y0, c.y0),
                    max(last.x1, c.x1), max(last.y1, c.y1), last.area + c.area,
                )
            } else {
                out += c
            }
        }
        return out
    }

    /** 等差数列延伸（种子跨 m 格；向前向后均可跳缺失项；步长按位置跨度自适应）。 */
    private fun apExtend(s: List<Double>, i: Int, j: Int, m: Int, tol: Double): List<ApTerm> {
        val n = s.size
        val terms = ArrayList<ApTerm>()
        terms.add(ApTerm(0, s[i]))
        terms.add(ApTerm(m, s[j]))
        var k = i - 1
        while (k >= 0) {
            val p0 = terms.first().pos
            val y0 = terms.first().y
            val step = (terms.last().y - y0) / (terms.last().pos - p0)
            val expect = y0 - step
            val d = expect - s[k]
            if (abs(d) <= tol * step) {
                terms.add(0, ApTerm(p0 - 1, s[k]))
            } else if (d > tol * step) {
                if (abs(s[k] - (expect - step)) <= tol * step) {
                    terms.add(0, ApTerm(p0 - 2, s[k]))
                } else if (s[k] < expect - step - tol * step) {
                    break
                }
            }
            k--
        }
        k = j + 1
        while (k < n) {
            val pN = terms.last().pos
            val yN = terms.last().y
            val step = (yN - terms.first().y) / (pN - terms.first().pos)
            val expect = yN + step
            val d = s[k] - expect
            if (abs(d) <= tol * step) {
                terms.add(ApTerm(pN + 1, s[k]))
            } else if (d > tol * step) {
                if (abs(s[k] - (expect + step)) <= tol * step) {
                    terms.add(ApTerm(pN + 2, s[k]))
                } else if (s[k] > expect + step + tol * step) {
                    break
                }
            }
            k++
        }
        return terms
    }

    private fun allAps(
        centers: List<Double>,
        spLo: Double,
        spHi: Double,
        tol: Double = 0.22,
        minTerms: Int = 4,
    ): List<List<ApTerm>> {
        val s = centers.sorted()
        val n = s.size
        val out = ArrayList<List<ApTerm>>()
        val seen = HashSet<String>()
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val span = s[j] - s[i]
                for (m in 1..3) {
                    val step = span / m
                    if (step < spLo || step > spHi) continue
                    val terms = apExtend(s, i, j, m, tol)
                    if (terms.size < minTerms) continue
                    val stepF = (terms.last().y - terms.first().y) / (terms.last().pos - terms.first().pos)
                    if (stepF < spLo || stepF > spHi) continue
                    val key = "${terms.first().y.toInt()}-${terms.last().y.toInt()}-${terms.size}"
                    if (seen.add(key)) out += terms
                }
            }
        }
        return out
    }

    /** 对 (pos,y) 最小二乘后按格位重采样成均匀网格。 */
    private fun resampleGrid(terms: List<ApTerm>): Pair<List<Double>, Double> {
        val n = terms.size
        val pm = terms.sumOf { it.pos }.toDouble() / n
        val ym = terms.sumOf { it.y } / n
        var denom = 0.0
        var num = 0.0
        for (t in terms) {
            denom += (t.pos - pm) * (t.pos - pm)
            num += (t.pos - pm) * (t.y - ym)
        }
        val slope = if (denom > 0) num / denom else 1.0
        val p0 = terms.minOf { it.pos }
        val p1 = terms.maxOf { it.pos }
        val grid = (p0..p1).map { p -> ym + (p - pm) * slope }
        return grid to slope
    }

    /** 缺口回填：相邻差接近 k×med 时插入中间格。 */
    private fun fillGaps(ys: List<Double>): List<Double> {
        if (ys.size < 2) return ys
        val diffs = ys.zipWithNext { a, b -> b - a }
        val med = medianD(diffs)
        val grid = mutableListOf(ys[0])
        for (i in 1 until ys.size) {
            val d = ys[i] - ys[i - 1]
            val k = max(1, (d / med).roundToInt())
            for (j in 1 until k) grid.add(ys[i - 1] + d * j / k)
            grid.add(ys[i])
        }
        return grid
    }

    /** 标签插值：marks = 索引→数值；未标注位置按相邻标注线性内插/外推。 */
    private fun interpolateLabels(size: Int, marks: Map<Int, Int>): List<Int> {
        if (marks.isEmpty()) return (1..size).toList()
        val idxs = marks.keys.sorted()
        val out = IntArray(size)
        for (j in 0 until size) {
            val before = idxs.lastOrNull { it <= j }
            val after = idxs.firstOrNull { it >= j }
            when {
                before == j -> out[j] = marks[before]!!
                before != null && after != null && before < j -> {
                    val f = (j - before).toDouble() / (after - before)
                    out[j] = (marks[before]!! + f * (marks[after]!! - marks[before]!!)).roundToInt()
                }
                after != null -> out[j] = marks[after]!! - (after - j)
                before != null -> out[j] = marks[before]!! + (j - before)
                else -> out[j] = j + 1
            }
        }
        for (j in 1 until size) if (out[j] < out[j - 1]) out[j] = out[j - 1]
        for (j in size - 2 downTo 0) if (out[j] > out[j + 1]) out[j] = out[j + 1]
        return out.map { it.coerceIn(1, 15) }
    }

    private fun medianD(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    private fun medianByte(px: ByteArray): Int {
        val stride = max(4, (px.size / 4 / 40000 / 4) * 4)
        val samples = ArrayList<Int>(px.size / stride + 1)
        var i = 0
        while (i < px.size) {
            samples.add(px[i].toInt() and 0xFF)
            i += stride
        }
        samples.sort()
        return samples[samples.size / 2]
    }

    // ------------------------------------------------------------------
    // v2 退化管线（结构锚定失败时的兜底）
    // ------------------------------------------------------------------

    private class CompF(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val area: Float) {
        val w get() = x1 - x0
        val h get() = y1 - y0
        val cx get() = (x0 + x1) / 2.0
    }

    private fun legacyParse(src: Bitmap): Grid {
        val hsv = Mat()
        Utils.bitmapToMat(src, hsv)
        Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGBA2BGR)
        Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_BGR2HSV)
        val mask = Mat()
        Core.inRange(hsv, Scalar(0.0, 30.0, 75.0), Scalar(255.0, 255.0, 255.0), mask)
        hsv.release()
        val k = max(3, min(src.width, src.height) / 200)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, org.opencv.core.Size(k.toDouble(), k.toDouble()))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
        kernel.release()

        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids)
        val minArea = max(400.0, src.width * src.height * 0.0004)
        val comps = mutableListOf<CompF>()
        for (i in 1 until n) {
            val x = stats.get(i, 0)[0].toFloat()
            val y = stats.get(i, 1)[0].toFloat()
            val w = stats.get(i, 2)[0].toFloat()
            val h = stats.get(i, 3)[0].toFloat()
            val area = stats.get(i, 4)[0].toFloat()
            if (w * h < minArea || w < src.width * 0.03f || h < src.height * 0.012f) continue
            if (w > src.width * 0.85f || h > src.height * 0.55f) continue
            comps += CompF(x, y, x + w, y + h, area)
        }
        mask.release(); labels.release(); stats.release(); centroids.release()
        val deduped = comps.filter { c ->
            comps.none { o -> o !== c && o.x0 <= c.x0 && o.y0 <= c.y0 && o.x1 >= c.x1 && o.y1 >= c.y1 }
        }
        // 描边空心卡（部分课表的「非本周课程」）是环形连通域：彩色面积只够描边，
        // 占外接框 ~0.13-0.19；实心课程块（含白字镂空）≥0.4。不剔除的话环形外接框
        // 会把整段空档当成跨节大块。
        val solid = deduped.filter { it.area >= 0.22f * it.w * it.h }
        check(solid.size >= 3) { "未能从图片中识别出课程块，请确认截图包含完整课表" }

        val colClusters = mutableListOf<MutableList<CompF>>()
        solid.sortedBy { it.x0 }.forEach { c ->
            val cl = colClusters.firstOrNull { cluster ->
                val lo = max(c.x0, cluster.minOf { it.x0 })
                val hi = min(c.x1, cluster.maxOf { it.x1 })
                hi - lo > 0.4f * min(c.w, cluster.maxOf { it.w })
            }
            if (cl != null) cl += c else colClusters += mutableListOf(c)
        }
        while (colClusters.size > 7) {
            var bestI = 0
            var bestGap = Float.MAX_VALUE
            for (i in 0 until colClusters.lastIndex) {
                val gap = colClusters[i + 1].minOf { it.x0 } - colClusters[i].maxOf { it.x1 }
                if (gap < bestGap) { bestGap = gap; bestI = i }
            }
            colClusters[bestI].addAll(colClusters[bestI + 1])
            colClusters.removeAt(bestI + 1)
        }
        colClusters.sortBy { it.minOf { it.x0 } }
        val colCenters = colClusters.map { cl -> (cl.minOf { it.x0 } + cl.maxOf { it.x1 }) / 2.0 }
        val dayOfColumn = colCenters.indices.map { it + 1 }

        val medH = medianF(solid.map { it.h })
        val bandStarts = mutableListOf<Float>()
        solid.sortedBy { it.y0 }.forEach { c ->
            if (bandStarts.isEmpty() || c.y0 - bandStarts.last() > medH * 0.4) bandStarts += c.y0
        }
        val blocks = solid.map { c ->
            val colIdx = colCenters.indices
                .map { i -> i to abs(c.cx - colCenters[i]) }
                .minByOrNull { it.second }?.first ?: 0
            val startRow = bandStarts.count { it <= c.y0 + medH * 0.2 }
            val endRow = bandStarts.count { it < c.y1 - medH * 0.2 }
            Block(
                day = dayOfColumn.getOrElse(colIdx) { colIdx + 1 },
                startSection = startRow.coerceAtLeast(1),
                endSection = max(startRow, endRow),
                rawRect = RectF(c.x0, c.y0, c.x1, c.y1),
            )
        }
        return Grid(
            dayCount = colCenters.size,
            sectionCount = bandStarts.size,
            blocks = blocks,
            dayOfColumn = dayOfColumn,
            workWidth = src.width,
            workHeight = src.height,
        )
    }

    private fun medianF(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    /** 调试：把识别结果画到副本上（校正界面叠加用）。识别坐标在工作图坐标系，
     *  按 workWidth/Height 映射回 src 尺寸——调用方可能传原图。 */
    fun drawOverlay(src: Bitmap, grid: Grid): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = android.graphics.Canvas(out)
        val paint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = max(2f, src.width / 200f)
            color = android.graphics.Color.argb(235, 30, 160, 90)
        }
        val sx = if (grid.workWidth > 0) src.width.toFloat() / grid.workWidth else 1f
        val sy = if (grid.workHeight > 0) src.height.toFloat() / grid.workHeight else 1f
        grid.blocks.forEach { b ->
            c.drawRect(
                RectF(
                    b.rawRect.left * sx, b.rawRect.top * sy,
                    b.rawRect.right * sx, b.rawRect.bottom * sy,
                ),
                paint,
            )
        }
        return out
    }

    private fun downsample(src: Bitmap, maxDim: Int): Bitmap {
        val maxSide = max(src.width, src.height)
        if (maxSide <= maxDim) return src
        val scale = maxDim.toFloat() / maxSide
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).roundToInt().coerceAtLeast(1),
            (src.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }
}
