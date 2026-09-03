package com.saltfish.simple.schedule

import kotlin.math.abs

/**
 * 课程块文本解析器（与 tools/parse_schedule_pdf.py 逻辑一致的 Kotlin 版）。
 *
 * 单元格文本格式（多条课程用独立块分隔，每块首行为 "课程名+类型标记"）：
 *   模拟电子线路★
 *   (1-2节)1-3周,5-15周/校区:下沙/楼号:环宇楼/场地:环宇楼A404/教师:修思文/...
 *
 * 注意：OCR 输出存在跨行断裂（"校区:下\n沙"），因此属性解析先将块内所有行
 * 拼接为一个字符串再按正则定位字段，不依赖物理换行。
 */
object ScheduleParser {

    /** 课程名后的标记符号 -> 课程类型（与 PDF 图例一致）。
     *  OCR 常把 ○ 误识别为 〇(U+3007)/O/0，一并映射为"实验"。 */
    private val TYPE_MARK = mapOf(
        ":" to "集中实践", "★" to "讲课", "○" to "实验", "●" to "上机", "◇" to "实践",
        "〇" to "实验", "О" to "实验", "O" to "实验", "0" to "实验",
    )

    /** 课程块起始行：课程名 + 类型标记，如 "大学物理A2★"。 */
    private val BLOCK_RE = Regex("^(.+?)([★○●◇:〇ОO])\\s*$")

    /** 属性 key（交替顺序：教学班组成 在 教学班 之前）。
     *  分隔符含 : ： 及中点 ·・（OCR 常把冒号识别成中点）。 */
    private val FIELD_SEARCH_RE = Regex(
        "(教学班组成|教学班|校区|楼号|场地|教师|选课备注|学分)\\s*[:：·・]\\s*([^/]*)"
    )

    private val WEEK_RE = Regex("""^(\d+)\s*[-–—~至]\s*(\d+)\s*$""")
    private val SINGLE_WEEK_RE = Regex("""^(\d+)\s*$""")
    private val SECTIONS_RE = Regex("""\((\d+)-(\d+)节\)""")
    /** 从拼接文本中抽取所有 "N-M周" / "N周" 片段。 */
    private val WEEK_RANGES_RE = Regex("""(\d+\s*-\s*\d+|\d+)\s*周""")

    /** 解析周次描述 "1-3周,5-15周" / "9周,15周" / "1-16周(单周)" -> 展开的周列表。 */
    fun parseWeeks(text: String): List<Int> {
        val weeks = mutableSetOf<Int>()
        text.replace("周", " ").split(Regex("[;；,，]")).forEach { raw ->
            val part0 = raw.trim()
            // "(单周)/(双周)" 标记：按奇偶过滤本段展开结果
            val parity = when {
                "单" in part0 -> 1
                "双" in part0 -> 0
                else -> -1
            }
            val part = part0.replace(Regex("[单双()（）]"), "").trim()
            val m = WEEK_RE.matchEntire(part)
            val range: List<Int> = when {
                m != null -> (m.groupValues[1].toInt()..m.groupValues[2].toInt()).toList()
                SINGLE_WEEK_RE.matchEntire(part) != null -> listOf(part.toInt())
                else -> emptyList()
            }
            range.filter { parity < 0 || it % 2 == parity }.forEach { weeks.add(it) }
        }
        return weeks.sorted()
    }

    /** 判断文本是否以课程块起始行开头（用于续行识别）。 */
    fun startsCourseBlock(text: String): Boolean =
        matchBlockStart(text.lineSequence().firstOrNull { it.isNotBlank() } ?: "") != null

    /** 匹配课程块起始行，返回 (课程名, 标记)。支持 ○ 被误识别为 0 的情况。
     *  行尾 '0' 兜底仅当去掉 0 后含至少一个 CJK 字符才生效：
     *  - "物理实验A0" → 课程名"物理实验A" ✓（含 CJK）
     *  - "C语言开发0" → 课程名"C语言开发" ✓（含 CJK，ASCII 开头不影响）
     *  - "04M0015-04" → 拒绝 ✗（纯 ASCII 续行碎片，防幽灵块） */
    private fun matchBlockStart(line: String): Pair<String, String>? {
        BLOCK_RE.matchEntire(line.trim())?.let {
            return it.groupValues[1].trim() to it.groupValues[2]
        }
        val t = line.trim()
        if (t.endsWith("0") && ':' !in t && '：' !in t && t.length > 1) {
            val head = t.dropLast(1)
            if (head.any { it.code >= 0x2E80 }) {
                return head.trim() to "0"
            }
        }
        return null
    }

    /** 把一个课程单元格文本解析为多条课程。 */
    fun parseCell(text: String): List<CellCourse> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val blocks = mutableListOf<MutableList<String>>()
        for (line in lines) {
            if (matchBlockStart(line) != null) {
                blocks.add(mutableListOf(line))
            } else if (blocks.isNotEmpty()) {
                blocks.last().add(line)
            }
        }
        return blocks.map { parseBlock(it) }
    }

    private fun parseBlock(block: List<String>): CellCourse {
        val title = block.first()
        val (nameRaw, mark) = matchBlockStart(title) ?: return CellCourse()
        val name = nameRaw.trim()

        // 属性区：拼接所有续行并去空白（OCR 跨行断裂的字段在此重新连接）
        val body = block.drop(1).joinToString("") { it.trim() }
            .replace(" ", "").replace("　", "")

        val fields = mutableMapOf(
            "校区" to "", "楼号" to "", "场地" to "", "教师" to "",
            "教学班" to "", "教学班组成" to "", "选课备注" to "", "学分" to "",
        )
        FIELD_SEARCH_RE.findAll(body).forEach { fm ->
            fields[fm.groupValues[1]] = fm.groupValues[2].trim()
        }

        val sm = SECTIONS_RE.find(body)
        val weekRanges = WEEK_RANGES_RE.findAll(body)
            .map { it.groupValues[1].trim().replace(" ", "") }
            .joinToString(",")
        val weeks = if (weekRanges.isNotBlank()) parseWeeks(weekRanges) else emptyList()

        return CellCourse(
            name = name,
            type = TYPE_MARK[mark] ?: "",
            sections = if (sm != null) listOf(sm.groupValues[1].toInt(), sm.groupValues[2].toInt()) else emptyList(),
            weeks = weeks,
            campus = fields["校区"] ?: "",
            building = fields["楼号"] ?: "",
            room = fields["场地"] ?: "",
            teacher = fields["教师"] ?: "",
            classNo = fields["教学班"] ?: "",
            composition = fields["教学班组成"] ?: "",
            credit = fields["学分"] ?: "",
        )
    }

    /** 单元格内解析出的单条课程（未去重）。 */
    data class CellCourse(
        val name: String = "",
        val type: String = "",
        val sections: List<Int> = emptyList(),
        val weeks: List<Int> = emptyList(),
        val campus: String = "",
        val building: String = "",
        val room: String = "",
        val teacher: String = "",
        val classNo: String = "",
        val composition: String = "",
        val credit: String = "",
    )
}
