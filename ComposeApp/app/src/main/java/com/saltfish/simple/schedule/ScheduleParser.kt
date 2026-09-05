package com.saltfish.simple.schedule

/**
 * 课程块文本解析器（规则包驱动版）。
 *
 * 「教务系统导出文本 → 课程字段」的差异全部来自 [ParseRulePack]：
 *  - 键值式字段（正方斜杠式）：块内续行拼接后按 key 正则定位取值；
 *  - 行前缀式字段（图标行式/截图课程卡）：行首前缀直接路由语义字段；
 *  - 节次/周次正则与单双周标记词随包配置。
 *
 * 注意：OCR 输出存在跨行断裂（"校区:下\n沙"），键值式先把块内所有行
 * 拼接为一个字符串再按正则定位字段，不依赖物理换行。
 */
object ScheduleParser {

    /** 把一个课程单元格文本解析为多条课程（pack 缺省 = 内置正方规则）。 */
    fun parseCell(text: String, pack: ParseRulePack = ParseRulePack.Zfsoft): List<CellCourse> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val blocks = mutableListOf<MutableList<String>>()
        for (line in lines) {
            if (matchBlockStart(line, pack) != null) {
                blocks.add(mutableListOf(line))
            } else if (blocks.isNotEmpty()) {
                blocks.last().add(line)
            }
        }
        return blocks.map { parseBlock(it, pack) }
    }

    /** 判断文本是否以课程块起始行开头（用于续行识别）。 */
    fun startsCourseBlock(text: String, pack: ParseRulePack = ParseRulePack.Zfsoft): Boolean =
        matchBlockStart(text.lineSequence().firstOrNull { it.isNotBlank() } ?: "", pack) != null

    /** 解析周次描述（"1-3周,5-15周" / "9周,15周" / "1-16周(单周)"）→ 展开的周列表。 */
    fun parseWeeks(text: String, pack: ParseRulePack = ParseRulePack.Zfsoft): List<Int> {
        val weeks = mutableSetOf<Int>()
        text.replace("周", " ").split(Regex("[;；,，]")).forEach { raw ->
            val part0 = raw.trim()
            // "(单周)/(双周)" 标记：按奇偶过滤本段展开结果
            val parity = when {
                pack.paritySingle in part0 -> 1
                pack.parityDouble in part0 -> 0
                else -> -1
            }
            val part = part0.replace(pack.paritySingle, "").replace(pack.parityDouble, "")
                .replace(Regex("[()（）]"), "").trim()
            val m = RANGE_RE.matchEntire(part)
            val range: List<Int> = when {
                m != null -> (m.groupValues[1].toInt()..m.groupValues[2].toInt()).toList()
                SINGLE_RE.matchEntire(part) != null -> listOf(part.toInt())
                else -> emptyList()
            }
            range.filter { parity < 0 || it % 2 == parity }.forEach { weeks.add(it) }
        }
        return weeks.sorted()
    }

    /** 周次片段（"N-M" / "N"）的结构化匹配，与包的 weeksPattern（正文抽取用）分工。 */
    private val RANGE_RE = Regex("""^(\d+)\s*[-–—~至]\s*(\d+)$""")
    private val SINGLE_RE = Regex("""^(\d+)$""")

    /** 强智形态的教师与周次同行："张三【2-6周】" / "李四【10-17周】"。 */
    private val CJK_NAME_BEFORE_BRACKET = Regex("""([\u2E80-\u9FFF]{2,4})(?=【)""")

    /** 名字+职称括注（网页/截图 👤 行的 OCR 变形："1张三(高等学校教师/副教授)"）。
     *  名字前允许 ≤2 个非 CJK 字符（变形的图标），不能是 CJK（防吃掉姓氏）。 */
    private val TITLE_ANNOTATED_NAME = Regex("""[^\u2E80-\u9FFF]{0,2}([\u2E80-\u9FFF]{2,4})[（(](?:高等学校|教师|讲师|教授|助教|无|职称)""")

    /** 教学班编号行形状："(2026-2027-1)-XX12345-67"（🏠 前缀被 OCR 吞掉时）。 */
    private val CLASS_NO_SHAPE = Regex("""\(\d{4}-\d{4}-\d{1,2}\)-[A-Za-z0-9\-–]+""")

    /** 剥掉尾部的括注（"(新校区)"）：剥后不剩正文则原样返回。 */
    private fun stripTrailingParen(t: String): String {
        var s = t.trim()
        while (true) {
            val m = Regex("[（(][^（()）]*[)）]$").find(s) ?: break
            s = s.removeRange(m.range).trim()
        }
        return s
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 课程块起始行：课程名 + 行尾类型标记，如 "大学物理A2★"（单字符）
     *  或 "理论力学(理论)"（多字符后缀，强智形态）。 */
    private fun matchBlockStart(line: String, pack: ParseRulePack): Pair<String, String>? {
        val marks = pack.typeMarks.keys
        if (marks.isNotEmpty()) {
            val t = line.trim()
            if (t.isNotEmpty()) {
                for (mark in marks) {
                    if (t.endsWith(mark)) {
                        val head = t.dropLast(mark.length).trim()
                        if (head.isNotEmpty()) return head to mark
                    }
                }
                for ((wrong, right) in pack.markAliases) {
                    if (t.endsWith(wrong)) {
                        val head = t.dropLast(wrong.length).trim()
                        if (head.isNotEmpty() && (!wrong.all { it.isDigit() } || digitAliasOk(t, head))) {
                            return head to wrong
                        }
                    }
                }
                // OCR 兜底：○ 被识别成 '0'——仅当去掉 0 后含 CJK 才生效
                if (pack.trailingZeroFallback && t.last() == '0' && t.length > 1) {
                    val head = t.dropLast(1).trim()
                    if (digitAliasOk(t, head)) return head to "0"
                }
            }
        }
        return null
    }

    /**
     * 行尾数字标记（○→0）的放行条件：
     *  - 末位 0 的**前一位不是数字**（排除 "学分:3.0"/"2020" 这类数值结尾的 OCR 碎片）；
     *  - 行内无冒号（字段碎片 "/学分:3.0" 不算课程块）；
     *  - 去掉 0 后含 CJK（纯 ASCII 续行碎片 "XX12345-67" 拒绝）。
     */
    private fun digitAliasOk(t: String, head: String): Boolean =
        t.length >= 2 && !t[t.length - 2].isDigit() &&
            ':' !in t && '：' !in t &&
            head.any { it.code >= 0x2E80 }

    private fun parseBlock(block: List<String>, pack: ParseRulePack): CellCourse {
        val title = block.first()
        val (nameRaw, mark) = matchBlockStart(title, pack) ?: return CellCourse()
        val name = nameRaw.trim()
        val compiled = pack.compiled

        val out = mutableMapOf<String, String>()   // 语义字段 → 值
        var sections: List<Int> = emptyList()
        var weeks: List<Int> = emptyList()
        var kvBody = StringBuilder()

        // 1) 行前缀式：逐行路由
        val rest = block.drop(1)
        val prefixLines = mutableListOf<String>()
        for (raw in rest) {
            val line = raw.trim()
            val hit = pack.linePrefixes.entries.firstOrNull { line.startsWith(it.key) }
            if (hit != null) {
                prefixLines.add(line)
                val value = line.removePrefix(hit.key).trim()
                when (hit.value) {
                    "sections" -> {
                        compiled.sections?.find(value)?.let { sm ->
                            sections = listOf(sm.groupValues[1].toInt(), sm.groupValues[2].toInt())
                        }
                        val wr = compiled.weeks.findAll(value)
                            .map { it.groupValues[1].trim().replace(" ", "") }
                            .joinToString(",")
                        if (wr.isNotBlank()) weeks = parseWeeks(wr, pack)
                    }
                    "teacher" -> out["teacher"] = cleanTeacher(value)
                    "room" -> applyLocation(value, out)
                    else -> out[hit.value] = value
                }
            } else {
                kvBody.append(line)
            }
        }

        // 2) 键值式：续行拼接（去空白）后按 key 正则定位；
        //    全角括号归一（OCR 常把 "(3-4节)" 读成 "（3-4节)"，节次正则会漏）
        val body = kvBody.toString().replace(" ", "").replace("　", "")
            .replace("（", "(").replace("）", ")")
            .let { if (it.length > ParseRulePack.MAX_SUBJECT_LEN) it.take(ParseRulePack.MAX_SUBJECT_LEN) else it }
        if (body.isNotEmpty()) {
            compiled.fieldSearch?.findAll(body)?.forEach { fm ->
                val key = fm.groupValues[1]
                val semantic = pack.keyMap[key] ?: return@forEach
                val value = fm.groupValues[2].trim()
                if (semantic == "teacher") out[semantic] = cleanTeacher(value)
                else if (semantic == "room") applyLocation(value, out)
                else out[semantic] = value
            }
            if (sections.isEmpty()) {
                compiled.sections?.find(body)?.let { sm ->
                    sections = listOf(sm.groupValues[1].toInt(), sm.groupValues[2].toInt())
                }
            }
            if (weeks.isEmpty()) {
                val wr = compiled.weeks.findAll(body)
                    .map { it.groupValues[1].trim().replace(" ", "") }
                    .joinToString(",")
                if (wr.isNotBlank()) weeks = parseWeeks(wr, pack)
            }
        }

        // 3) 位置式教师兜底：无前缀命中时（截图课程卡/网页/强智形态）。
        //    图标前缀（📍👤）常被 OCR 吞掉或变形（👤→"1"），行前缀路由失效，
        //    按行形状恢复字段；仅对声明了 teacher 前缀或 positionalTeacher 的包生效。
        if ("teacher" !in out && (pack.positionalTeacher || "teacher" in pack.linePrefixes.values)) {
            val candidates = prefixLines.ifEmpty { block.drop(1) }
            // a) 名字+职称括注（"1张三(高等学校教师/副教授)"，前导是变形的图标）
            candidates.firstNotNullOfOrNull { line ->
                TITLE_ANNOTATED_NAME.find(line.trim())?.groupValues?.get(1)
            }?.let { out["teacher"] = cleanTeacher(it) }
            // b) 强智形态：教师与周次同行（"张三【2-6周】"）
            if ("teacher" !in out) {
                candidates.firstNotNullOfOrNull { line ->
                    CJK_NAME_BEFORE_BRACKET.find(line.trim())?.groupValues?.get(1)
                }?.let { out["teacher"] = cleanTeacher(it) }
            }
            // c) 截图课程卡形态：末个短中文行
            if ("teacher" !in out) {
                val teacherLine = candidates.lastOrNull { line ->
                    val t = line.trim()
                    t.length in 2..6 && t.any { it.code >= 0x2E80 } &&
                        t.none { it.isDigit() } &&
                        t.first() !in "（(［[" && !t.endsWith("教室") &&
                        !t.endsWith("室）") && !t.endsWith("室)")
                }
                if (teacherLine != null) out["teacher"] = cleanTeacher(teacherLine.trim())
            }
        }
        // 4) 位置式教室兜底：含数字、无括注、非纯数字的行（强智 "N6-403"，
        //    网页 "新校区 机电楼A101(新校区)"——尾部括注剥掉后参与匹配）。
        //    排除节/周字（节次周次行同样 CJK+数字）；取首个匹配：教室行在教学班
        //    说明（"示例班123"）之前，取末位会被其污染。
        if ("room" !in out && (pack.positionalRoom || "room" in pack.linePrefixes.values)) {
            val roomLine = block.drop(1)
                .firstOrNull { line ->
                    val t = stripTrailingParen(line.trim())
                    t.length in 2..12 && t.any { it.isDigit() } && t.any { it.code >= 0x2E80 || it in 'A'..'z' } &&
                        t.none { it.isWhitespace() } &&
                        t.none { it in "（）()【】[]【】" } &&
                        t.none { it in "节周" } &&
                        !t.all { it.isDigit() } && '.' !in t
                }
            if (roomLine != null) applyLocation(stripTrailingParen(roomLine.trim()), out)
        }
        // 5) 教学班编号兜底："(2026-2027-1)-XX12345-67" 行形状（🏠 前缀被 OCR 吞掉时）。
        //    仅对声明了 classNo 前缀的包生效。
        if ("classNo" !in out && "classNo" in pack.linePrefixes.values) {
            block.drop(1).firstOrNull { CLASS_NO_SHAPE.containsMatchIn(it.trim()) }?.let {
                out["classNo"] = it.trim()
            }
        }

        return CellCourse(
            name = name,
            type = pack.typeMarks[mark]
                ?: pack.markAliases.entries.firstOrNull { it.key == mark }?.let { pack.typeMarks[it.value] }
                ?: "",
            sections = sections,
            weeks = weeks,
            campus = out["campus"] ?: "",
            building = out["building"] ?: "",
            room = out["room"] ?: "",
            teacher = out["teacher"] ?: "",
            classNo = out["classNo"] ?: "",
            composition = out["composition"] ?: "",
            credit = out["credit"] ?: "",
        )
    }

    /** 教师值清理：剥掉尾部的职称括注（"张三(无)" / "李四(高等学校教师/教授)"）。 */
    private fun cleanTeacher(v: String): String {
        var t = v.trim()
        val m = Regex("[（(]([^（()）]*)[)）]\\s*$").find(t)
        if (m != null) {
            val inner = m.groupValues[1]
            if (inner == "无" || listOf("教师", "讲师", "教授", "助教", "职称").any { it in inner }) {
                t = t.removeRange(m.range).trim()
            }
        }
        return t
    }

    /** 地点值拆分："新校区 图信楼B305" → campus=新校区, room=图信楼B305；无空格整体记 room。 */
    private fun applyLocation(value: String, out: MutableMap<String, String>) {
        val v = value.trim().replace("　", " ")
        val sp = v.indexOfFirst { it == ' ' }
        if (sp in 1 until v.length - 1 && out["campus"].isNullOrEmpty()) {
            out["campus"] = v.take(sp)
            out["room"] = v.substring(sp + 1).trim()
        } else {
            out["room"] = v
        }
    }
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
