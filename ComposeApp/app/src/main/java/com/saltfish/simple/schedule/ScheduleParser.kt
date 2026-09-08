package com.saltfish.simple.schedule

/**
 * 课程块文本解析器（v2 规则执行器）。
 *
 * 「教务系统导出文本 → 课程字段」的全部判据来自 [ParseRulePack] 的规则数据：
 *  - 块起始：有序 courseStart 规则，第一条命中即开新块；
 *  - 字段抽取：有序 fieldRules，按序执行、字段先到先得；
 *    行前缀路由（◎📍👤）是结构性剥离，未命中前缀的行拼接为「正文」供正文规则使用；
 *  - 节次/周次正则与单双周展开数学是解释器机制，随包配置。
 *
 * 诊断：[parseCellTraced] 返回逐块命中轨迹与未入块行清单，供 ocr_debug 与
 * 规则包作者定位缺失规则。
 */
object ScheduleParser {

    /** 把一个课程单元格文本解析为多条课程（pack 缺省 = 内置正方规则）。 */
    fun parseCell(text: String, pack: ParseRulePack = ParseRulePack.Zfsoft): List<CellCourse> =
        parseCellTraced(text, pack).first

    /** 解析 + 命中轨迹（诊断用；结果与 [parseCell] 完全一致）。
     *  轨迹含每块对应的原始行下标（PDF 路径按块回填节次用）。 */
    fun parseCellTraced(
        text: String,
        pack: ParseRulePack = ParseRulePack.Zfsoft,
    ): Pair<List<CellCourse>, CellParseTrace> {
        val compiled = pack.compiled
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val blocks = mutableListOf<MutableList<String>>()
        val blockIdx = mutableListOf<MutableList<Int>>()
        val unmatched = mutableListOf<String>()
        val unmatchedIdx = mutableListOf<Int>()
        for ((i, line) in lines.withIndex()) {
            var forceStart = false
            var start = compiled.matchCourseStart(line)
            if (start == null) {
                // 无标记课名前瞻：OCR 偶发吞掉课名尾部的类型标记
                // （"物理实验A○"→"物理实验A"，真机实测导致整段课粘进上一格消失）。
                // 纯课名形状行（中文开头、无字段分隔符/标记）+ 下一行以节次"(N"开头
                // + 与下一行拼接不构成块起始（那是标题断行拼回场景，让位）→ 新块起始
                val nextLine = lines.getOrNull(i + 1) ?: ""
                val joinWithNextMatches =
                    (blocks.lastOrNull()?.lastOrNull() + nextLine).let { compiled.matchCourseStart(it) } != null ||
                        (unmatched.lastOrNull() + nextLine).let { compiled.matchCourseStart(it) } != null
                if (!joinWithNextMatches && nextLine.startsWith("(") &&
                    line.length in 2..16 && line[0].code in 0x4E00..0x9FFF &&
                    line.none { it in "/：:（(）)【】★○●◇〇 " }
                ) forceStart = true
            }
            if (start == null && blocks.isNotEmpty()) {
                // OCR 盒子拆行修复：课名与类型标记被拆成两行（"电工电子技术基础B" +
                // "(理论) 教师【周次】"）时，与块内上一行拼回再测块起始；
                // 命中则从上一行处切分出新块，拼接出的课名须像课名（不含字段特征字符）
                val lastBlock = blocks.last()
                if (lastBlock.size > 1) {
                    val idx = lastBlock.size - 1
                    val joined = lastBlock[idx] + line
                    val js = compiled.matchCourseStart(joined)
                    if (js != null && js.name.length <= 30 &&
                        js.name.none { it in "【:：/；;，" }
                    ) {
                        val newBlock = mutableListOf(joined)
                        val newIdx = mutableListOf(blockIdx.last().last(), i)
                        if (js.rest.isNotEmpty()) {
                            newBlock.add(js.rest)
                            newIdx.add(i)
                        }
                        blocks[blocks.size - 1] = lastBlock.subList(0, idx).toMutableList()
                        blockIdx[blocks.size - 1] = blockIdx.last().subList(0, idx).toMutableList()
                        blocks.add(newBlock)
                        blockIdx.add(newIdx)
                        continue
                    }
                }
            }
            if (start == null && blocks.isEmpty() && unmatched.isNotEmpty()) {
                // 首块建立前的标题断行（"体育与健康III（跆拳" + "I)★"）：
                // 与上一条未入块行拼回再测，命中即开块，避免整格课程丢失
                val joined = unmatched.last() + line
                val js = compiled.matchCourseStart(joined)
                if (js != null && js.name.length <= 30 &&
                    js.name.none { it in "【:：/；;，" }
                ) {
                    unmatched.removeAt(unmatched.size - 1)
                    val baseIdx = unmatchedIdx.removeAt(unmatchedIdx.size - 1)
                    blocks.add(mutableListOf(joined))
                    val idxs = mutableListOf(baseIdx, i)
                    if (js.rest.isNotEmpty()) {
                        blocks.last().add(js.rest)
                        idxs.add(i)
                    }
                    blockIdx.add(idxs)
                    continue
                }
            }
            if (start != null || forceStart) {
                blocks.add(mutableListOf(line))
                blockIdx.add(mutableListOf(i))
                // 宽松规则命中粘连行：标题行剩余部分（教师/周次等）按续行处理
                if (start?.rest?.isNotEmpty() == true) {
                    blocks.last().add(start.rest)
                    blockIdx.last().add(i)
                }
            } else if (blocks.isNotEmpty()) {
                blocks.last().add(line)
                blockIdx.last().add(i)
            } else {
                unmatched.add(line)
                unmatchedIdx.add(i)
            }
        }
        val courses = mutableListOf<CellCourse>()
        val traces = mutableListOf<BlockTrace>()
        for ((bi, block) in blocks.withIndex()) {
            val (course, trace) = parseBlock(block, pack, compiled, blockIdx[bi])
            courses.add(course)
            traces.add(trace)
        }
        return courses to CellParseTrace(unmatched, traces)
    }

    /** 判断文本是否以课程块起始行开头（用于续行识别）。 */
    fun startsCourseBlock(text: String, pack: ParseRulePack = ParseRulePack.Zfsoft): Boolean =
        pack.compiled.matchCourseStart(
            text.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
        ) != null

    /** 解析周次描述（"1-3周,5-15周" / "9周,15周" / "1-16周(单周)"）→ 展开的周列表。
     *  分隔符含 OCR 变体：句点/顿号（强智 "【3.7-10.12-14周】"）。
     *  周次描述应为 weeksPattern 抽出的完整片段（含「周」与可选单双尾注）。 */
    fun parseWeeks(text: String, pack: ParseRulePack = ParseRulePack.Zfsoft): List<Int> {
        val weeks = mutableSetOf<Int>()
        text.replace("周", " ").split(Regex("[;；,，.、]")).forEach { raw ->
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
            // 周次 0 不存在（"第0周" 是 OCR 误读），过滤防垃圾周
            range.filter { it in 1..30 && (parity < 0 || it % 2 == parity) }.forEach { weeks.add(it) }
        }
        return weeks.sorted()
    }

    /** 周次片段（"N-M" / "N"）的结构化匹配，与包的 weeksPattern（正文抽取用）分工。 */
    private val RANGE_RE = Regex("""^(\d+)\s*[-–—~至]\s*(\d+)$""")
    private val SINGLE_RE = Regex("""^(\d+)$""")

    // ------------------------------------------------------------------
    // 命中轨迹（诊断）
    // ------------------------------------------------------------------

    /** 单个单元格的解析轨迹。 */
    data class CellParseTrace(
        /** 未进入任何课程块的行（首个块起始行之前的孤儿行）。 */
        val unmatchedLines: List<String>,
        val blocks: List<BlockTrace>,
    )

    /** 单个课程块的规则命中轨迹。 */
    data class BlockTrace(
        val startLine: String,
        val startRule: String,
        val name: String,
        val type: String,
        /** 字段命中明细："teacher=张三 ← 职称括注行「1钱七(高等学校教师/副教…」"。 */
        val fills: List<String>,
        val sections: List<Int>,
        val weeksCount: Int,
        /** 本块对应的原始行下标（多课同格时按块回填节次用）。 */
        val lineIndices: List<Int> = emptyList(),
    ) {
        fun render(): String = buildString {
            append("块「").append(name).append('"')
            if (type.isNotEmpty()) append('(').append(type).append(')')
            append(" ← ").append(startRule)
            if (fills.isNotEmpty()) append("; ").append(fills.joinToString("; "))
            if (sections.size == 2) append("; 节次=").append(sections[0]).append('-').append(sections[1])
            append("; 周×").append(weeksCount)
        }
    }

    // ------------------------------------------------------------------
    // 内部：规则执行
    // ------------------------------------------------------------------

    private fun parseBlock(
        block: List<String>,
        pack: ParseRulePack,
        compiled: ParseRulePack.CompiledRules,
        lineIndices: List<Int> = emptyList(),
    ): Pair<CellCourse, BlockTrace> {
        val title = block.first()
        val start = compiled.matchCourseStart(title)
        // forceStart 块（无标记课名前瞻）无规则命中：课名取整行
        val name = start?.name?.takeIf { it.isNotEmpty() } ?: title
        val type = start?.type ?: ""
        val fills = mutableListOf<String>()

        val out = mutableMapOf<String, String>()   // 语义字段 → 值
        var sections: List<Int> = emptyList()
        var weeks: List<Int> = emptyList()

        val rest = block.drop(1).map { it.trim() }.filter { it.isNotEmpty() }

        // 1) 前缀路由（结构性剥离）：每行归属列表序第一条前缀命中的规则
        val prefixRules = compiled.fieldRules.withIndex().filter { it.value.linePrefix != null }
        val claimed = mutableMapOf<Int, Pair<Int, String>>()   // 行下标 → (规则下标, 前缀后文本)
        for ((li, line) in rest.withIndex()) {
            val hit = prefixRules.firstOrNull { line.startsWith(it.value.linePrefix!!) }
            if (hit != null) claimed[li] = hit.index to line.removePrefix(hit.value.linePrefix!!).trim()
        }

        // 2) 正文：未命中前缀的行拼接（去空白、全角括号归一）——OCR 跨行断裂在此愈合
        val body = rest.withIndex()
            .filter { it.index !in claimed }
            .joinToString("") { it.value }
            .replace(" ", "").replace("　", "")
            .replace("（", "(").replace("）", ")")
            .let { if (it.length > ParseRulePack.MAX_SUBJECT_LEN) it.take(ParseRulePack.MAX_SUBJECT_LEN) else it }

        // 3) 字段规则按序执行（先到先得：字段已有值则跳过；"sections" 特殊：可能带出周次）
        for ((ri, rule) in compiled.fieldRules.withIndex()) {
            if (rule.field != "sections" && out.containsKey(rule.field)) continue
            when {
                rule.linePrefix != null -> {
                    val hits = claimed.entries.filter { it.value.first == ri }.sortedBy { it.key }
                    if (rule.field == "sections") {
                        for (entry in hits) {
                            val value = entry.value.second
                            compiled.sections?.find(value)?.let { sm ->
                                sections = listOf(sm.groupValues[1].toInt(), sm.groupValues[2].toInt())
                                fills += "sections=${sections[0]}-${sections[1]} ← ${rule.desc}"
                            }
                            val wr = compiled.weeks.findAll(value)
                                .map { it.groupValues[1].trim().replace(" ", "") }
                                .joinToString(",")
                            if (wr.isNotBlank()) {
                                weeks = parseWeeks(wr, pack)
                                fills += "weeks×${weeks.size} ← ${rule.desc}"
                            }
                        }
                    } else {
                        val last = hits.lastOrNull() ?: continue
                        applyValue(rule, last.value.second, out, fills)
                    }
                }
                rule.linePick != ParseRulePack.LINE_BODY -> {
                    // 行扫描：候选=除标题外的全部续行（OCR 图标常被吞，行形状比前缀可靠）
                    var chosenValue: String? = null
                    for (line in rest) {
                        val t = if (rule.stripTrailingParen) stripTrailingParen(line) else line
                        if (rule.excludeChars.any { it in t }) continue
                        val re = rule.re ?: continue
                        val m = re.find(t) ?: continue
                        chosenValue = m.groupValues[rule.captureGroup.coerceIn(1, m.groupValues.size)].trim()
                        if (rule.linePick == ParseRulePack.LINE_FIRST) break
                    }
                    chosenValue?.let { applyValue(rule, it, out, fills) }
                }
                else -> {
                    val re = rule.re ?: continue
                    val m = re.find(body) ?: continue
                    applyValue(rule, m.groupValues[rule.captureGroup.coerceIn(1, m.groupValues.size)], out, fills)
                }
            }
        }

        // 4) 节次/周次机制兜底：规则未取到时从正文提取（周次展开数学属解释器）
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

        val course = CellCourse(
            name = name,
            type = type,
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
        return course to BlockTrace(
            startLine = title.take(40),
            startRule = start?.rule?.desc ?: "?",
            name = name,
            type = type,
            fills = fills,
            sections = sections,
            weeksCount = weeks.size,
            lineIndices = lineIndices,
        )
    }

    /** 字段值落位：先做白名单变换，再写入输出；splitCampus 会同时填 campus。 */
    private fun applyValue(
        rule: ParseRulePack.CompiledRules.FieldRuleC,
        raw: String,
        out: MutableMap<String, String>,
        fills: MutableList<String>,
    ) {
        var v = raw.trim()
        if (rule.cleanTeacher) v = cleanTeacher(v)
        val from = " ← ${rule.desc}"
        if (rule.splitCampus) {
            applyLocation(v, out)
            val campus = out["campus"]?.takeIf { it.isNotEmpty() }?.let { " campus=$it" } ?: ""
            fills += "room=${out["room"]}$campus$from"
        } else {
            out[rule.field] = v
            fills += "${rule.field}=$v$from"
        }
    }

    /** 教师值清理（白名单变换）：从首个职称括注起整体截断——兼容嵌套括注
     *  （"张三(无)" / "李四(高等学校教师/未详职称(高校教师))" → 张三/李四）。 */
    private val TEACHER_TITLE_PAREN =
        Regex("[（(][^（()）]*(?:高等学校|教师|讲师|教授|助教|职称|无)")

    private fun cleanTeacher(v: String): String {
        val t = v.trim()
        val m = TEACHER_TITLE_PAREN.find(t) ?: return t
        return t.substring(0, m.range.first).trim()
    }

    /** 地点值拆分（白名单变换）：先剥尾部括注（"(新校区)"/"(光学)" 是注记不是教室），
     *  再按空格拆 "新校区 图信楼B305" → campus=新校区, room=图信楼B305；无空格整体记 room。
     *  仅当首段是 ≤5 字纯 CJK（校区名形态）才拆——"N田1区 足球-机械工程学院" 首段含
     *  字母数字，是场地名的一部分，整体记 room。 */
    private fun applyLocation(value: String, out: MutableMap<String, String>) {
        val v = stripTrailingParen(value.trim().replace("　", " "))
        val sp = v.indexOfFirst { it == ' ' }
        val head = if (sp > 0) v.take(sp) else ""
        val headLooksLikeCampus = head.isNotEmpty() && head.length <= 5 &&
            head.all { it.code in 0x2E80..0x9FFF }
        if (headLooksLikeCampus && sp in 1 until v.length - 1 && out["campus"].isNullOrEmpty()) {
            out["campus"] = head
            out["room"] = v.substring(sp + 1).trim()
        } else {
            out["room"] = v
        }
    }

    /** 剥掉尾部的括注（"(新校区)"）：剥后不剩正文则原样返回。 */
    private fun stripTrailingParen(t: String): String {
        var s = t.trim()
        while (true) {
            val m = Regex("[（(][^（()）]*[)）]$").find(s) ?: break
            s = s.removeRange(m.range).trim()
        }
        return s
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
