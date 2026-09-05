package com.saltfish.simple.schedule

import org.json.JSONObject

/**
 * 解析规则包 v1：把「教务系统导出文本 → 课程字段」的差异化部分声明为纯数据，
 * 解释器（ScheduleParser / SchedulePdfParser / 图片识别）只有一套，规则包可内置可导入。
 *
 * 设计边界（红线）：
 *  - 规则包是**纯数据**：只有词表、正则、映射，没有也不允许有任何可执行逻辑；
 *  - 内置样本一律占位数据（如「张三」），禁止真实姓名/学号进入规则包文件；
 *  - 外部正则受限：长度上限 + 编译校验，解释器侧再对被匹配文本限长，防灾难性回溯。
 *
 * 两类字段抽取模式（可同时生效）：
 *  - 键值式（keyMap）：正方斜杠式 "…/校区:下沙/教师:张三/…"，按 key 正则定位取值；
 *  - 行前缀式（linePrefixes）：图标行式 "◎ (1-2节)1-4周…" / "📍 教室" / "👤 教师"，
 *    语义直接映射到目标字段，适配 Web 课表与课程卡截图。
 */
data class ParseRulePack(
    val id: String,
    val name: String,
    val version: Int = 1,
    /** 指纹：解析文本中命中 anyOf 子串的数量 ≥ minHits 才入围，取命中最高者。 */
    val matchAnyOf: List<String>,
    val matchMinHits: Int,
    // ---- 表格重建（PDF/图片网格） ----
    val headerAnchor: String,
    val dayPattern: String,
    val dayCount: Int,
    /** 整行丢弃的标签词（上午/下午/时间段…）。 */
    val labelWords: List<String>,
    /** 页面装饰行特征词（图例/打印时间…）。 */
    val legendWords: List<String>,
    // ---- 课程块起始 ----
    /** 行尾类型标记 → 课程类型（"★"→"讲课"）；图片单卡模式可为空表。 */
    val typeMarks: Map<String, String>,
    /** OCR 误识别标记 → 标准标记（"〇"→"○"）。 */
    val markAliases: Map<String, String>,
    /** 行尾 '0' 兜底（○ 被 OCR 成 0）——仅当去 0 后含 CJK 才生效。 */
    val trailingZeroFallback: Boolean,
    // ---- 字段抽取：键值式 ----
    /** 属性 key → 语义字段名（campus/building/room/teacher/classNo/composition/credit）。 */
    val keyMap: Map<String, String>,
    /** key 后允许的分隔字符（含 OCR 变体）。 */
    val fieldSeparators: String,
    /** 值的终止字符。 */
    val fieldEndChars: String,
    // ---- 字段抽取：行前缀式 ----
    /** 行首前缀 → 语义字段名；"sections" 前缀行内提取节次+周次。 */
    val linePrefixes: Map<String, String>,
    // ---- 节次/周次 ----
    val sectionsPattern: String,
    val weeksPattern: String,
    val paritySingle: String,
    val parityDouble: String,
    /** 无前缀命中时，末行短中文行按教师兜底（截图课程卡形态）。 */
    val positionalTeacher: Boolean = false,
    /** 位置式教室识别：无前缀命中时，取含数字且无括注的行按教室兜底（强智形态）。 */
    val positionalRoom: Boolean = false,
) {

    // ------------------------------------------------------------------
    // JSON (schema v1)
    // ------------------------------------------------------------------

    fun toJson(): String = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("id", id)
        put("name", name)
        put("version", version)
        put("match", JSONObject().apply {
            put("anyOf", org.json.JSONArray(matchAnyOf))
            put("minHits", matchMinHits)
        })
        put("table", JSONObject().apply {
            put("headerAnchor", headerAnchor)
            put("dayPattern", dayPattern)
            put("dayCount", dayCount)
            put("labelWords", org.json.JSONArray(labelWords))
            put("legendWords", org.json.JSONArray(legendWords))
        })
        put("blockStart", JSONObject().apply {
            put("typeMarks", JSONObject(typeMarks))
            put("markAliases", JSONObject(markAliases))
            put("trailingZeroFallback", trailingZeroFallback)
        })
        put("fields", JSONObject().apply {
            put("keyMap", JSONObject(keyMap))
            put("fieldSeparators", fieldSeparators)
            put("fieldEndChars", fieldEndChars)
            put("linePrefixes", JSONObject(linePrefixes))
            put("sectionsPattern", sectionsPattern)
            put("weeksPattern", weeksPattern)
            put("paritySingle", paritySingle)
            put("parityDouble", parityDouble)
            put("positionalTeacher", positionalTeacher)
            put("positionalRoom", positionalRoom)
        })
    }.toString()

    companion object {
        const val SCHEMA_VERSION = 1

        // ---- 安全上限（外部导入的规则包） ----
        const val MAX_PATTERN_LEN = 200        // 单条正则长度上限
        const val MAX_LIST_ITEMS = 24          // 词表条目上限
        const val MAX_WORD_LEN = 24            // 词表单词长度上限
        const val MAX_SUBJECT_LEN = 4000       // 解释器侧被匹配文本长度上限
        /** 语义字段白名单：keyMap/linePrefixes 的值只能取自这里。 */
        val SEMANTIC_FIELDS = setOf(
            "campus", "building", "room", "teacher",
            "classNo", "composition", "credit", "sections",
        )

        private fun str(o: JSONObject, key: String, def: String): String =
            if (o.has(key)) o.getString(key) else def

        private fun intOf(o: JSONObject, key: String, def: Int): Int =
            if (o.has(key)) o.getInt(key) else def

        private fun strList(o: JSONObject, key: String): List<String> =
            if (o.has(key) && !o.isNull(key)) {
                val arr = o.getJSONArray(key)
                (0 until arr.length()).map { arr.getString(it) }
            } else emptyList()

        private fun strMap(o: JSONObject, key: String): Map<String, String> =
            if (o.has(key) && !o.isNull(key)) {
                val m = o.getJSONObject(key)
                m.keys().asSequence().associateWith { m.getString(it) }
            } else emptyMap()

        /**
         * 从 JSON 反序列化并做完整校验（结构 + 安全上限 + 正则可编译）。
         * 任何不合法直接抛 IllegalArgumentException，导入界面展示原因。
         */
        fun fromJson(text: String): ParseRulePack {
            val root = JSONObject(text)
            require(root.getInt("schemaVersion") == SCHEMA_VERSION) { "不支持的规则包版本" }
            val id = str(root, "id", "").trim()
            require(id.isNotEmpty() && id.length <= 40 && id.all { it.isLetterOrDigit() || it in "-_." }) {
                "id 只能含字母数字与 -_."
            }
            val name = str(root, "name", "").trim()
            require(name.isNotEmpty() && name.length <= 40) { "name 不能为空且不超过 40 字" }
            val match = root.getJSONObject("match")
            val table = root.getJSONObject("table")
            val blockStart = root.getJSONObject("blockStart")
            val fields = root.getJSONObject("fields")

            val matchAnyOf = strList(match, "anyOf")
            require(matchAnyOf.isNotEmpty() && matchAnyOf.size <= MAX_LIST_ITEMS) { "match.anyOf 不能为空" }
            matchAnyOf.forEach {
                require(it.isNotEmpty() && it.length <= MAX_WORD_LEN) { "match.anyOf 词条超长" }
            }

            val pack = ParseRulePack(
                id = id,
                name = name,
                version = intOf(root, "version", 1),
                matchAnyOf = matchAnyOf,
                matchMinHits = intOf(match, "minHits", 1),
                headerAnchor = str(table, "headerAnchor", ""),
                dayPattern = str(table, "dayPattern", "星期"),
                dayCount = intOf(table, "dayCount", 7),
                labelWords = strList(table, "labelWords"),
                legendWords = strList(table, "legendWords"),
                typeMarks = strMap(blockStart, "typeMarks"),
                markAliases = strMap(blockStart, "markAliases"),
                trailingZeroFallback = blockStart.optBoolean("trailingZeroFallback", true),
                keyMap = strMap(fields, "keyMap"),
                fieldSeparators = str(fields, "fieldSeparators", ":：·・"),
                fieldEndChars = str(fields, "fieldEndChars", "/"),
                linePrefixes = strMap(fields, "linePrefixes"),
                sectionsPattern = str(fields, "sectionsPattern", "\\((\\d+)-(\\d+)节\\)"),
                weeksPattern = str(fields, "weeksPattern", "(\\d+\\s*-\\s*\\d+|\\d+)\\s*周"),
                paritySingle = str(fields, "paritySingle", "单"),
                parityDouble = str(fields, "parityDouble", "双"),
                positionalTeacher = fields.optBoolean("positionalTeacher", false),
                positionalRoom = fields.optBoolean("positionalRoom", false),
            )
            pack.validateAndCompile()
            return pack
        }

        // ------------------------------------------------------------------
        // 内置规则包
        // ------------------------------------------------------------------

        /** 正方教务系统（文本层 PDF / 像素字 PDF 的 OCR 文本同构）。 */
        val Zfsoft = ParseRulePack(
            id = "zfsoft",
            name = "正方 PDF（字段式）",
            matchAnyOf = listOf("校区:", "楼号:", "教学班", "选课备注", "时间段"),
            matchMinHits = 2,
            headerAnchor = "时间段",
            dayPattern = "星期",
            dayCount = 7,
            labelWords = listOf("上午", "下午", "晚上", "时间段", "节次"),
            legendWords = listOf("打印时间", "其他课程", "图例", "集中实践"),
            typeMarks = mapOf(
                "★" to "讲课", "○" to "实验", "●" to "上机", "◇" to "实践", ":" to "集中实践",
            ),
            markAliases = mapOf("〇" to "○", "О" to "○", "O" to "○", "0" to "○"),
            trailingZeroFallback = true,
            keyMap = mapOf(
                "教学班组成" to "composition", "教学班" to "classNo",
                "校区" to "campus", "楼号" to "building", "场地" to "room",
                "教师" to "teacher", "学分" to "credit",
            ),
            // 空格兜底：OCR 常把冒号读成空格（"教师 李四"）；正则两侧本就允许 \s*
            fieldSeparators = ":：·・ ",
            fieldEndChars = "/",
            linePrefixes = emptyMap(),
            sectionsPattern = "\\((\\d+)-(\\d+)节\\)",
            weeksPattern = "(\\d+\\s*-\\s*\\d+|\\d+)\\s*周",
            paritySingle = "单",
            parityDouble = "双",
        )

        /** 图标行式网格（Web 课表/强智类导出：◎节次周次 📍地点 👤教师 🏠教学班）。 */
        val IconGrid = ParseRulePack(
            id = "icon-grid",
            name = "网页/截图（图标行式）",
            matchAnyOf = listOf("高等学校教师", "未排地点", "时间段"),
            matchMinHits = 2,
            headerAnchor = "时间段",
            dayPattern = "星期",
            dayCount = 7,
            labelWords = listOf("上午", "下午", "晚上", "时间段", "节次"),
            legendWords = listOf("打印时间", "图例"),
            typeMarks = mapOf("★" to "讲课", "●" to "上机", "○" to "实验", "◇" to "实践"),
            markAliases = mapOf("〇" to "○"),
            trailingZeroFallback = true,
            keyMap = emptyMap(),
            fieldSeparators = ":：·・",
            fieldEndChars = "/",
            linePrefixes = mapOf(
                "◎" to "sections",
                "📍" to "room",
                "👤" to "teacher",
                "🏠" to "classNo",
                "@" to "room",
            ),
            sectionsPattern = "\\((\\d+)-(\\d+)节\\)",
            weeksPattern = "(\\d+\\s*-\\s*\\d+|\\d+)\\s*周",
            paritySingle = "单",
            parityDouble = "双",
            positionalTeacher = true,
        )

        /**
         * 强智教务系统（像素字 PDF / 网页导出）：课名以 (理论)/(实践) 等结尾，
         * 周次在【1-6,10-18周】里，教师/教室是无前缀裸行，节次不写在卡上
         * （由表格行位置决定，PDF 路径用节次数字列回填，截图路径用占用网格回填）。
         */
        val Qz = ParseRulePack(
            id = "qz",
            name = "强智 PDF（位置式）",
            matchAnyOf = listOf("(理论)", "(实践)", "(实验)", "【"),
            matchMinHits = 2,
            headerAnchor = "",
            dayPattern = "星期",
            dayCount = 7,
            labelWords = listOf("节次", "上午", "下午", "晚上"),
            legendWords = listOf("打印时间", "图例"),
            typeMarks = mapOf(
                "(理论)" to "讲课", "(实践)" to "实践", "(实验)" to "实验",
                "(上机)" to "上机", "(实训)" to "实践", "(理论课)" to "讲课",
            ),
            markAliases = emptyMap(),
            trailingZeroFallback = false,
            keyMap = emptyMap(),
            fieldSeparators = ":：·・",
            fieldEndChars = "/",
            linePrefixes = emptyMap(),
            sectionsPattern = "",
            weeksPattern = "(\\d+\\s*[-–]\\s*\\d+(?:\\s*[,，]\\s*\\d+\\s*[-–]\\s*\\d+)*|\\d+)\\s*周",
            paritySingle = "单",
            parityDouble = "双",
            positionalTeacher = true,
            positionalRoom = true,
        )

        /** 通用兜底包：指纹全不中时使用——宽松周次/节次 + 课程名行启发式。 */
        val Generic = ParseRulePack(
            id = "generic",
            name = "通用兜底（自动）",
            matchAnyOf = listOf("周"),
            matchMinHits = 0,
            headerAnchor = "",
            dayPattern = "星期",
            dayCount = 7,
            labelWords = listOf("上午", "下午", "晚上", "节次"),
            legendWords = listOf("打印时间", "图例"),
            typeMarks = mapOf("★" to "讲课", "●" to "上机", "○" to "实验", "◇" to "实践"),
            markAliases = mapOf("〇" to "○"),
            trailingZeroFallback = true,
            keyMap = emptyMap(),
            fieldSeparators = ":：·・",
            fieldEndChars = "/",
            linePrefixes = mapOf("@" to "room"),
            sectionsPattern = "\\((\\d+)-(\\d+)节\\)",
            weeksPattern = "(\\d+\\s*-\\s*\\d+|\\d+)\\s*周",
            paritySingle = "单",
            parityDouble = "双",
            positionalTeacher = true,
        )

        val Builtins = listOf(Zfsoft, IconGrid, Qz, Generic)
    }

    // ------------------------------------------------------------------
    // 校验：结构 + 上限 + 正则可编译（导入与内置共用）
    // ------------------------------------------------------------------

    /** 编译后的运行时句柄（懒编译；非数据的一部分，不参与 equals）。 */
    val compiled: CompiledRules by lazy { buildCompiled() }

    class CompiledRules(
        val sections: Regex?,
        val weeks: Regex,
        val fieldSearch: Regex?,   // 键值式：'(k1|k2|…)[sep](值)'
    )

    /** 导入校验入口：越界/不合法抛 IllegalArgumentException，并强制完成正则编译。 */
    fun validateAndCompile() {
        require(headerAnchor.length <= MAX_WORD_LEN) { "headerAnchor 超长" }
        require(dayPattern.isNotEmpty() && dayPattern.length <= MAX_WORD_LEN) { "dayPattern 非法" }
        require(dayCount in 1..7) { "dayCount 必须在 1..7" }
        listOf(labelWords, legendWords).forEach { list ->
            require(list.size <= MAX_LIST_ITEMS) { "词表条目过多" }
            list.forEach { require(it.isNotEmpty() && it.length <= MAX_WORD_LEN) { "词表词条超长" } }
        }
        listOf(typeMarks, markAliases, keyMap, linePrefixes).forEach { m ->
            require(m.size <= MAX_LIST_ITEMS) { "映射条目过多" }
            m.forEach { (k, v) ->
                require(k.isNotEmpty() && k.length <= MAX_WORD_LEN) { "映射 key 超长: $k" }
                require(v.length <= MAX_WORD_LEN) { "映射 value 超长: $v" }
            }
        }
        keyMap.values.forEach {
            require(it in SEMANTIC_FIELDS) { "keyMap 含未知语义字段: $it" }
        }
        linePrefixes.values.forEach {
            require(it in SEMANTIC_FIELDS) { "linePrefixes 含未知语义字段: $it" }
        }
        typeMarks.keys.forEach {
            require(it.isNotEmpty() && it.length <= 6) { "类型标记长度需在 1..6: $it" }
        }
        markAliases.keys.forEach { require(it.length == 1) { "标记别名必须单字符: $it" } }
        require(fieldSeparators.length in 1..8) { "fieldSeparators 非法" }
        require(fieldEndChars.length in 1..8) { "fieldEndChars 非法" }
        require(sectionsPattern.length <= MAX_PATTERN_LEN) { "sectionsPattern 超长" }
        require(weeksPattern.length <= MAX_PATTERN_LEN) { "weeksPattern 超长" }
        compiled // 强制编译，正则不合法在此抛出
    }

    private fun buildCompiled(): CompiledRules {
        val sections = if (sectionsPattern.isBlank()) null
        else runCatching { Regex(sectionsPattern) }
            .getOrElse { throw IllegalArgumentException("sectionsPattern 编译失败") }
        val weeks = runCatching { Regex(weeksPattern) }
            .getOrElse { throw IllegalArgumentException("weeksPattern 编译失败") }
        val fieldSearch = if (keyMap.isEmpty()) null
        else runCatching {
            // 长 key 在前，防「教学班」截胡「教学班组成」。
            // 字符类手动转义（Android ICU 对 \Q..\E 在类内的支持不一致，不能用 Regex.escape）
            fun cls(s: String) = s.replace("\\", "\\\\").replace("]", "\\]")
                .replace("^", "\\^").replace("-", "\\-")
            val keys = keyMap.keys.sortedByDescending { it.length }
                .joinToString("|") { Regex.escape(it) }
            // 字段以文本起点或值终止/分段符（/;；,，）为边界、分隔符可缺省：
            // OCR 常把冒号整个读没（"教师李四"）；边界锚定防「新校区」里的「校区」误配
            Regex("(?:^|[${cls(fieldEndChars)};；,，])(${keys})\\s*[${cls(fieldSeparators)}]?\\s*([^${cls(fieldEndChars)}]*)")
        }.getOrElse { throw IllegalArgumentException("keyMap 正则编译失败") }
        return CompiledRules(sections, weeks, fieldSearch)
    }

    /** 指纹得分：全文命中 matchAnyOf 的子串数。 */
    fun fingerprintScore(fullText: String): Int =
        matchAnyOf.count { it in fullText }

    /** 此包是否适用（得分达标）。 */
    fun matches(fullText: String): Boolean = fingerprintScore(fullText) >= matchMinHits
}
