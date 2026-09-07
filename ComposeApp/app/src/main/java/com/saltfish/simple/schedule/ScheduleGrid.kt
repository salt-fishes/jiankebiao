package com.saltfish.simple.schedule

/**
 * 格线网格 v2：把课表渲染图还原成「星期 × 节次」格子，OCR 文本框按几何落格。
 *
 * 与 v1（预生成格子、无横线即连堂）的差异：强智类 PDF 的横线只有组边界线，
 * 「线缺失=连堂」不成立。v2 改为——
 *  - 行 = 左轴节次数字锚定行中心，相邻锚中点定边界，附近横线吸附校正；
 *  - 每个文字行按中心 y 打**行标签**（最近锚的节次号）；
 *  - 列内纵向切块三规则：相邻行跨行带时，有横线段 → 切；无横线但词法判
 *    块起始 → 切；否则同块（真连堂卡的文字跨行带保持一体）；
 *  - 块的节次跨度 = 块内行标签 min..max（逐行打标，不做整体 bbox 猜测）。
 *
 * 纯几何、零 Android 依赖，可在 JVM 单测：[SegmentProbe] 由调用方注入。
 * 节次跨度由 [expandSpan] 以文字行标签为核、向两侧无线扩展至横线段。
 */
internal object ScheduleGrid {

    /** 一行节次：纵向 [top, bottom) 与节次号（来自左轴数字锚）。 */
    data class RowBand(val section: Int, val top: Float, val bottom: Float)

    data class Grid(
        /** 每个星期列的 (左缘, 右缘)。 */
        val colRanges: List<Pair<Float, Float>>,
        val rows: List<RowBand>,
    ) {
        /** 文本框中心 x 落在哪一列（1..7），列外返回 null。 */
        fun columnAt(cx: Float): Int? =
            colRanges.indexOfFirst { cx >= it.first && cx <= it.second }
                .takeIf { it >= 0 }?.plus(1)

        /** y 最近锚行的节次号；表格外（首行上方/末行下方）返回 null。 */
        fun rowSectionAt(cy: Float): Int? =
            rows.filter { cy >= it.top && cy <= it.bottom }
                .minByOrNull { minOf(cy - it.top, it.bottom - cy) }?.section
    }

    /** 待落格的文本框（几何 + 文本）。 */
    data class TextBox(
        val text: String,
        val left: Float, val top: Float, val right: Float, val bottom: Float,
    ) {
        val cx: Float get() = (left + right) / 2f
        val cy: Float get() = (top + bottom) / 2f
    }

    /** 线段探测：在 [yFrom, yTo] 像素带、[x0, x1] 水平范围内找横线，返回线的 y（无则 null）。 */
    fun interface SegmentProbe {
        fun findSegment(yFrom: Float, yTo: Float, x0: Float, x1: Float): Float?
    }

    /**
     * 由锚点构建行带。anchors = (锚 y, 节次号)，须按 y 升序、值严格递增
     * （调用方先 [sanitizeAnchors]）；边界 = 相邻锚中点，±snap 内探到横线则
     * 吸附到**距估计最近**的线（带内可能同时有外框线和真边界线）。
     * snap 取 0.45×间距：锚 y 有 ±半行内的检测偏差（OCR 框歪），窗口过窄会
     * 错过真边界线（强智 2|3 实测差 9px 没吸到 → 上午段连片并块）。
     */
    fun buildRows(
        anchors: List<Pair<Float, Int>>,
        tableTop: Float,
        tableBottom: Float,
        probe: SegmentProbe,
        colX0: Float,
        colX1: Float,
    ): List<RowBand> {
        require(anchors.size >= 2) { "节次锚不足" }
        val ys = anchors.map { it.first }
        val bounds = mutableListOf(tableTop)
        for (i in 0 until anchors.size - 1) {
            val mid = (ys[i] + ys[i + 1]) / 2f
            val snap = (ys[i + 1] - ys[i]) * 0.45f
            val b = probe.findSegment(mid - snap, mid + snap, colX0, colX1) ?: mid
            bounds += b
        }
        bounds += tableBottom
        return anchors.indices.map { i ->
            RowBand(anchors[i].second, bounds[i], bounds[i + 1])
        }
    }

    /**
     * 节次跨度扩展：以文字行标签为核，向两侧扩张至遇到横线段（线=格子边界）。
     * 统一五种情形——双节卡（文字跨两带，核 1-2）、同格双卡上下铺（各卡核窄，
     * 卡间无线扩张回整格 5-6）、组边界有线止步（不吞邻组）、长 rowspan（劳动
     * 实践核 6-7 扩到 5-8）、跨格粘连（2|3 有线止步，不吞邻格）。
     * 返回 (起始节次, 结束节次)；coreLabels 为空返回 null。
     * [debug] 逐步探测日志（ocr_debug 诊断用）。单侧最多扩 [maxExpand] 节：
     * 锚表个别残留噪声时钳制病态扩张（正常连堂半天 4 节，核通常已含 2 节，
     * 2 步足够覆盖劳动实践 5-8 型）。
     */
    fun expandSpan(
        rows: List<RowBand>,
        coreLabels: List<Int>,
        probe: SegmentProbe,
        x0: Float,
        x1: Float,
        debug: ((String) -> Unit)? = null,
        maxExpand: Int = 2,
    ): Pair<Int, Int>? {
        if (coreLabels.isEmpty() || rows.isEmpty()) return null
        val sections = rows.map { it.section }.toSet()
        var lo = coreLabels.min()
        var hi = coreLabels.max()
        debug?.invoke("core=$coreLabels")
        var steps = 0
        while (lo - 1 in sections && steps < maxExpand) {
            val y = rows.first { it.section == lo }.top
            val hit = probe.findSegment(y - 3f, y + 3f, x0 + 4f, x1 - 4f)
            debug?.invoke("  上扩 $lo-1 边界y=$y 线=$hit")
            if (hit != null) break
            lo--; steps++
        }
        steps = 0
        while (hi + 1 in sections && steps < maxExpand) {
            val y = rows.first { it.section == hi }.bottom
            val hit = probe.findSegment(y - 3f, y + 3f, x0 + 4f, x1 - 4f)
            debug?.invoke("  下扩 $hi+1 边界y=$y 线=$hit")
            if (hit != null) break
            hi++; steps++
        }
        debug?.invoke("  → $lo..$hi")
        return lo to hi
    }

    /** 锚点整流：OCR 数字可能有噪声（粘连/误读），取节次号严格递增的
     *  最长子序列（LIS，贪心会被「早到的大号噪声」带偏），y 顺序即行序。 */
    fun sanitizeAnchors(anchors: List<Pair<Float, Int>>): List<Pair<Float, Int>> {
        val sorted = anchors.sortedBy { it.first }
        if (sorted.isEmpty()) return sorted
        val n = sorted.size
        val len = IntArray(n) { 1 }
        val prev = IntArray(n) { -1 }
        for (i in 1 until n) {
            for (j in 0 until i) {
                if (sorted[j].second < sorted[i].second && len[j] + 1 > len[i]) {
                    len[i] = len[j] + 1
                    prev[i] = j
                }
            }
        }
        var end = (0 until n).maxByOrNull { len[it] } ?: return emptyList()
        val out = mutableListOf<Pair<Float, Int>>()
        while (end != -1) {
            out.add(sorted[end])
            end = prev[end]
        }
        out.reverse()
        return out
    }
}
