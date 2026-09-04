package com.saltfish.simple.schedule

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max

/** 连通域候选块（原图像素坐标）。 */
private data class Comp(val x: Int, val y: Int, val w: Int, val h: Int, val cx: Double)

private fun medianOf(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val s = values.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
}

/**
 * 课表截图「占用格」识别：不识别课程内容，只检测"星期 × 节次"哪些格子被彩色课程块占据。
 *
 * 设计动机（对比课表的前置）：各类课表 APP/教务 H5 的课程块都是高饱和彩色块、
 * 空白格为白/浅灰底，因此用色彩掩码 + 连通域即可得到占用矩阵，**完全绕开
 * OCR 文字内容的精度问题**。OCR 仅用于两处小范围辅助：
 *  - 表头条带（"一 二 三 四 五 六 日"）：确认列数与起始星期；
 *  - 两者都失败时退化为默认假设（7 列、周一起、行序即节次序）。
 *
 * 识别结果必须经人工校正界面确认后再保存——算法负责大部分，用户补齐长尾。
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
        /** 起始星期（1=周一）。表头 OCR 失败时为 1。 */
        val startDay: Int,
    )

    /** 识别入口。失败抛出可读异常（调用方展示）。OCR 子步骤为挂起调用。 */
    suspend fun parse(context: Context, src: Bitmap): Grid {
        check(OpenCVUtils.init(context)) { "图像处理组件初始化失败" }
        val mat = Mat()
        Utils.bitmapToMat(src, mat)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGR2HSV)

        // 1. 彩色掩码：饱和度足够且不太暗 → 课程块；白/灰底与黑字被排除
        val mask = Mat()
        Core.inRange(mat, Scalar(0.0, 40.0, 70.0), Scalar(255.0, 255.0, 255.0), mask)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, org.opencv.core.Size(9.0, 9.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)

        // 2. 连通域 → 课程块候选
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids)
        val minArea = max(400.0, src.width * src.height * 0.0004)
        val comps = mutableListOf<Comp>()
        for (i in 1 until n) {
            val x = stats.get(i, 0)[0].toInt()
            val y = stats.get(i, 1)[0].toInt()
            val w = stats.get(i, 2)[0].toInt()
            val h = stats.get(i, 3)[0].toInt()
            if (w * h < minArea || w < src.width * 0.04 || h < src.height * 0.02) continue
            comps += Comp(x, y, w, h, centroids.get(i, 0)[0])
        }
        if (comps.size < 2) throw IllegalStateException("未能从图片中识别出课程块，请确认截图包含完整课表")
        comps.sortBy { it.y }

        // 3. 列聚类：按水平中心排序，间距超过块中位宽的 60% 即新一列
        val medW = medianOf(comps.map { it.w.toDouble() })
        val cols = mutableListOf<Comp>()
        comps.sortedBy { it.cx }.forEach { c ->
            if (cols.isEmpty() || c.cx - cols.last().cx > medW * 0.6) cols += c
        }
        // 4. 行聚类：块顶边间距超过块中位高的 40% 即新一节
        val medH = medianOf(comps.map { it.h.toDouble() })
        val bandStarts = mutableListOf<Int>()
        comps.sortedBy { it.y }.forEach { c ->
            if (bandStarts.isEmpty() || c.y - bandStarts.last() > medH * 0.4) bandStarts += c.y
        }

        // 5. 表头小范围 OCR → 列与星期的映射（失败则默认周一起的顺序列）
        val startDay = detectStartDay(context, src, comps)

        // 6. 块 → (星期, 起止节次)
        val blocks = comps.map { c ->
            val colIdx = cols.indexOfFirst { col -> abs(c.cx - col.cx) <= medW * 0.75 }
                .let { if (it < 0) 0 else it }
            val day = (startDay - 1 + colIdx) % 7 + 1
            val startRow = bandStarts.count { it <= c.y + medH * 0.2 }
            val endRow = bandStarts.count { it < c.y + c.h - medH * 0.2 }
            Block(
                day = day,
                startSection = startRow.coerceAtLeast(1),
                endSection = max(startRow, endRow),
                rawRect = RectF(c.x.toFloat(), c.y.toFloat(), (c.x + c.w).toFloat(), (c.y + c.h).toFloat()),
            )
        }
        mat.release(); mask.release(); labels.release(); stats.release(); centroids.release()
        return Grid(
            dayCount = cols.size,
            sectionCount = bandStarts.size,
            blocks = blocks,
            startDay = startDay,
        )
    }

    /**
     * 表头 OCR：裁剪最顶块上方的条带，识别"一/二/…/日"字样并按 x 匹配列。
     * 返回起始星期（1=周一）；无法可靠识别时返回 1。
     */
    private suspend fun detectStartDay(context: Context, src: Bitmap, comps: List<Comp>): Int {
        return try {
            val top = comps.minOf { it.y }.toFloat()
            if (top < src.height * 0.04f) return 1  // 表头空间不足，放弃
            val strip = Bitmap.createBitmap(src, 0, 0, src.width, minOf(src.height, top.toInt()))
            val ocr = PaddleOCR.create(context, PaddleOCRConfig())
            val texts = try {
                ocr.recognize(strip).results
            } finally {
                ocr.release(); strip.recycle()
            }
            // 每个 OCR 行找中文星期字；映射到列（按 x 中心就近）
            val colCenters = comps.sortedBy { it.cx }.map { it.cx }
            val dayByCol = mutableMapOf<Int, Int>()
            for (r in texts) {
                val cx = r.box.points.map { it.x }.average()
                val col = colCenters.indices.minBy { abs(colCenters[it] - cx) }
                val day = dayCharToNumber(r.text) ?: continue
                dayByCol.putIfAbsent(col, day)
            }
            if (dayByCol.size < 3) return 1  // 匹配太少，不可靠
            // 由任意两列推出起始日：day = startDay - 1 + col
            val col0 = dayByCol.keys.min()
            val d0 = dayByCol[col0]!!
            val start = ((d0 - col0) % 7 + 7) % 7 + 1
            // 校验一致性
            val consistent = dayByCol.all { (c, d) -> (d - c - start + 1).mod(7) == 0 }
            if (consistent) start else 1
        } catch (t: Throwable) {
            1
        }
    }

    /** 从表头文字推断星期序号（1=周一 .. 7=周日）；不含星期字返回 null。 */
    private fun dayCharToNumber(text: String): Int? {
        val t = text.replace("周", "").replace("星期", "").trim()
        if (t.length > 2) return null
        return when {
            '一' in t -> 1
            '二' in t -> 2
            '三' in t -> 3
            '四' in t -> 4
            '五' in t -> 5
            '六' in t -> 6
            '日' in t || '天' in t -> 7
            else -> null
        }
    }

    /** 调试：把识别结果画到副本上（校正界面叠加用）。 */
    fun drawOverlay(src: Bitmap, grid: Grid): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = android.graphics.Canvas(out)
        val paint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = max(2f, src.width / 200f)
            color = android.graphics.Color.argb(230, 30, 160, 90)
        }
        grid.blocks.forEach { b ->
            c.drawRect(b.rawRect, paint)
        }
        return out
    }
}
