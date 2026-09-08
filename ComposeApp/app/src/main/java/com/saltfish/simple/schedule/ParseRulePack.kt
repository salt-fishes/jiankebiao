package com.saltfish.simple.schedule

import org.json.JSONArray
import org.json.JSONObject

/**
 * 块起始规则（schema v2）：一条正则命中即认定课程块起始行。
 *  - pattern：正则；建议锚定（^…$），执行器按 find 语义在行内搜索；
 *  - nameGroup：课名捕获组（默认 1）；
 *  - type：固定课程类型；或 typeGroup + typeMap：按捕获文本查类型（如 (理论)→讲课）。
 */
data class CourseStartRule(
    val pattern: String,
    val nameGroup: Int = 1,
    val type: String = "",
    val typeGroup: Int = 0,
    val typeMap: Map<String, String> = emptyMap(),
    val name: String = "",
)

/**
 * 字段规则（schema v2）：有序列表，按序执行，字段先到先得。
 *  - 无 linePrefix 且 line=null：在「正文」（未命中前缀的续行拼接、去空白、括号归一）中 find；
 *  - linePrefix 非空：路由以该前缀开头的行，取前缀后的文本为值（"sections" 字段特殊：
 *    行内同时提取节次+周次）；
 *  - line="first"/"last"：逐行扫描全部续行（OCR 图标常被吞，行形状比前缀可靠），
 *    取首/末条命中行捕获组的值；stripTrailingParen/excludeChars 作用于候选行；
 *  - 变换白名单：stripTrailingParen（剥尾部括注）、splitCampus（按空格拆校区/教室）、
 *    cleanTeacher（剥职称括注）、excludeChars（候选行排除字符）。
 */
data class FieldRule(
    val field: String,
    val pattern: String = "",
    val captureGroup: Int = 1,
    val linePrefix: String? = null,
    val line: String? = null,
    val stripTrailingParen: Boolean = false,
    val splitCampus: Boolean = false,
    val cleanTeacher: Boolean = false,
    val excludeChars: String = "",
    val name: String = "",
)

/**
 * 解析规则包：把「教务系统导出文本 → 课程字段」的差异化部分声明为纯数据，
 * 解释器（ScheduleParser）只有一套规则执行器，规则包可内置可导入。
 *
 * 设计边界（红线）：
 *  - 规则包是**纯数据**：只有词表、正则、映射与白名单变换，没有也不允许有任何可执行逻辑；
 *  - 内置样本一律占位数据（如「张三」），禁止真实姓名/学号进入规则包文件；
 *  - 外部正则受限：长度上限 + 编译校验 + 捕获组数校验，解释器侧再对被匹配文本限长，
 *    防灾难性回溯。
 *
 * schema v2（规则执行器模型，见 [CourseStartRule] / [FieldRule]）；
 * schema v1 仍可导入：typeMarks/markAliases/keyMap/linePrefixes/positionalTeacher 等
 * 在编译期展开为等价 v2 规则（糖编译），解析行为与 v1 解释器一致。
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
    // ---- 块起始 / 字段抽取（v2 原生形态） ----
    val courseStart: List<CourseStartRule> = emptyList(),
    val fieldRules: List<FieldRule> = emptyList(),
    // ---- 以下为 v1 形态（仅作糖编译输入；v2 包导入时被拒绝） ----
    /** 行尾类型标记 → 课程类型（"★"→"讲课"）。 */
    val typeMarks: Map<String, String> = emptyMap(),
    /** OCR 误识别标记 → 标准标记（"〇"→"○"）。 */
    val markAliases: Map<String, String> = emptyMap(),
    /** 行尾 '0' 兜底（○ 被 OCR 成 0）——仅当去 0 后含 CJK 才生效。 */
    val trailingZeroFallback: Boolean = false,
    // ---- 字段抽取：键值式（糖） ----
    val keyMap: Map<String, String> = emptyMap(),
    val fieldSeparators: String = ":：·・",
    val fieldEndChars: String = "/",
    // ---- 字段抽取：行前缀式（糖） ----
    val linePrefixes: Map<String, String> = emptyMap(),
    // ---- 节次/周次（机制层正则，随包配置） ----
    val sectionsPattern: String = "\\((\\d+)-(\\d+)节\\)",
    val weeksPattern: String = WEEKS_PATTERN_DEFAULT,
    val paritySingle: String = "单",
    val parityDouble: String = "双",
    // v1 位置式兜底开关（糖编译输入）
    val positionalTeacher: Boolean = false,
    val positionalRoom: Boolean = false,
    /** 截图类规则包：解析手机截图而非导出文件——导入路由据此走照片选择器。 */
    val screenshot: Boolean = false,
    /** 导入时使用的 schema 版本（仅 toJson 往返用）。 */
    val sourceSchema: Int = 1,
) {

    // ------------------------------------------------------------------
    // JSON 序列化
    // ------------------------------------------------------------------

    fun toJson(): String = JSONObject().apply {
        put("schemaVersion", sourceSchema)
        put("id", id)
        put("name", name)
        put("version", version)
        if (sourceSchema >= 2 && screenshot) put("screenshot", true)
        put("match", JSONObject().apply {
            put("anyOf", JSONArray(matchAnyOf))
            put("minHits", matchMinHits)
        })
        put("table", JSONObject().apply {
            put("headerAnchor", headerAnchor)
            put("dayPattern", dayPattern)
            put("dayCount", dayCount)
            put("labelWords", JSONArray(labelWords))
            put("legendWords", JSONArray(legendWords))
        })
        if (sourceSchema >= 2) {
            put("blockStart", JSONObject().apply {
                put("courseStart", JSONArray().apply {
                    courseStart.forEach { r ->
                        put(JSONObject().apply {
                            put("pattern", r.pattern)
                            if (r.name.isNotEmpty()) put("name", r.name)
                            if (r.nameGroup != 1) put("nameGroup", r.nameGroup)
                            if (r.type.isNotEmpty()) put("type", r.type)
                            if (r.typeGroup > 0) {
                                put("typeGroup", r.typeGroup)
                                put("typeMap", JSONObject(r.typeMap))
                            }
                        })
                    }
                })
            })
            put("fields", JSONObject().apply {
                put("fieldRules", JSONArray().apply {
                    fieldRules.forEach { r ->
                        put(JSONObject().apply {
                            put("field", r.field)
                            if (r.name.isNotEmpty()) put("name", r.name)
                            if (r.pattern.isNotEmpty()) put("pattern", r.pattern)
                            if (r.captureGroup != 1) put("captureGroup", r.captureGroup)
                            r.linePrefix?.let { put("linePrefix", it) }
                            r.line?.let { put("line", it) }
                            if (r.stripTrailingParen) put("stripTrailingParen", true)
                            if (r.splitCampus) put("splitCampus", true)
                            if (r.cleanTeacher) put("cleanTeacher", true)
                            if (r.excludeChars.isNotEmpty()) put("excludeChars", r.excludeChars)
                        })
                    }
                })
                if (keyMap.isNotEmpty()) put("keyMap", JSONObject(keyMap))
                if (linePrefixes.isNotEmpty()) put("linePrefixes", JSONObject(linePrefixes))
                if (fieldSeparators != ":：·・") put("fieldSeparators", fieldSeparators)
                if (fieldEndChars != "/") put("fieldEndChars", fieldEndChars)
                put("sectionsPattern", sectionsPattern)
                put("weeksPattern", weeksPattern)
                put("paritySingle", paritySingle)
                put("parityDouble", parityDouble)
            })
        } else {
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
        }
    }.toString()

    companion object {
        const val SCHEMA_VERSION = 1
        const val SCHEMA_VERSION_MAX = 2

        // ---- 安全上限（外部导入的规则包） ----
        const val MAX_PATTERN_LEN = 200        // 单条正则长度上限
        const val MAX_LIST_ITEMS = 24          // 词表/规则条目上限
        const val MAX_WORD_LEN = 24            // 词表单词长度上限
        const val MAX_SUBJECT_LEN = 4000       // 解释器侧被匹配文本长度上限
        /** 语义字段白名单：字段规则的 field 只能取自这里。 */
        val SEMANTIC_FIELDS = setOf(
            "campus", "building", "room", "teacher",
            "classNo", "composition", "credit", "sections",
        )

        // 行规则扫描方式（linePick）
        const val LINE_BODY = 0    // 正文拼接
        const val LINE_FIRST = 1   // 逐行取首条命中
        const val LINE_LAST = 2    // 逐行取末条命中

        // ------------------------------------------------------------------
        // 行形状判据（数据，供内置包与 v1 糖编译共用；执行器不感知具体格式）
        // ------------------------------------------------------------------

        /** 名字+职称括注（网页/截图 👤 行的 OCR 变形："1张三(高等学校教师/副教授)"）。
         *  名字前允许 ≤2 个非 CJK 字符（变形的图标），不能是 CJK（防吃掉姓氏）。 */
        internal const val TEACHER_TITLE_SHAPE =
            "[^\\u2E80-\\u9FFF]{0,2}([\\u2E80-\\u9FFF]{2,4})[（(](?:高等学校|教师|讲师|教授|助教|无|职称)"

        /** 强智形态的教师与周次同行："张三【2-6周】"（名字与【之间允许 OCR 空格）；
         *  允许逗号/顿号分隔的多教师（"刘思平,朱丽娟,柳利芳【3-7周】"，平行教学班）。 */
        internal const val TEACHER_BEFORE_BRACKET =
            "((?:[\\u2E80-\\u9FFF]{2,4}[,，、]?){1,4})\\s*(?=【)"

        /** 末行短中文兜底（截图课程卡形态）：2..6 个 CJK、非「教室」结尾。 */
        internal const val TEACHER_LAST_SHORT_CJK = "^(?!.*教室$)([\\u2E80-\\u9FFF]{2,6})$"

        /** 教室行形状：含数字、含 CJK/字母、无括注/点；允许一个内部空格（"新校区 机电楼A101"，
         *  拆分由 splitCampus 完成）；长度上限放宽到 ~26（强智室外场地 "N田1区足球-机械工程学院"）。
         *  节/周行由 excludeChars 排除。 */
        internal const val ROOM_LINE_SHAPE =
            "^(?=.*\\d)(?=.*[A-Za-z\\u2E80-\\u9FFF])([A-Za-z\\u2E80-\\u9FFF][A-Za-z0-9\\-\\u2E80-\\u9FFF]{0,14}(?: ?[A-Za-z0-9\\-\\u2E80-\\u9FFF]{1,10})?)$"

        /** 教学班编号行形状："(2026-2027-1)-XX12345-67"（🏠 前缀被 OCR 吞掉时）。 */
        internal const val CLASS_NO_LINE_SHAPE = "(\\(\\d{4}-\\d{4}-\\d{1,2}\\)-[A-Za-z0-9\\-–]+)"

        /** 行尾 '0' 块起始（○ 被 OCR 成 0）的防误判守卫：前一位非数字、行内无冒号、含 CJK。 */
        internal const val DIGIT_TAIL_GUARD = "^(?!.*[：:])(?=.*[\\u2E80-\\u9FFF])(.*[^\\d])"

        /** 通用周次片段（正方/图标行式）：整段含「周」与可选 (单/双) 尾注，
         *  单双周过滤依赖 parseWeeks 收到完整片段（"2周,6-10周(双)"）。 */
        internal const val WEEKS_PATTERN_DEFAULT =
            "((?:\\d+\\s*[-–]\\s*\\d+|\\d+)\\s*周(?:\\s*[（(]\\s*[单双]\\s*周?\\s*[）)])?)"

        /** 强智多段周次：【2-4,6,11-13,15-18周】/【1-4.6周】/【3.7-10.12-14周】
         *  ——续段允许区间或单周，分隔符含 OCR 的句点/顿号变体。 */
        internal const val WEEKS_PATTERN_MULTI =
            "((?:\\d+(?:\\s*[-–]\\s*\\d+)?(?:\\s*[.，,、]\\s*\\d+(?:\\s*[-–]\\s*\\d+)?)*)\\s*周(?:\\s*[（(]\\s*[单双]\\s*周?\\s*[）)])?)"

        internal fun teacherFallbackRules(): List<FieldRule> = listOf(
            FieldRule("teacher", TEACHER_TITLE_SHAPE, line = "first", cleanTeacher = true, name = "职称括注行"),
            FieldRule("teacher", TEACHER_BEFORE_BRACKET, line = "first", cleanTeacher = true, name = "【周次】同行教师"),
            FieldRule("teacher", TEACHER_LAST_SHORT_CJK, line = "last", cleanTeacher = true, name = "末行短中文兜底"),
        )

        internal val RoomShapeRule = FieldRule(
            "room", ROOM_LINE_SHAPE, line = "first",
            stripTrailingParen = true, splitCampus = true, excludeChars = "节周", name = "教室行形状",
        )

        internal val ClassNoShapeRule = FieldRule(
            "classNo", CLASS_NO_LINE_SHAPE, line = "first", name = "教学班编号行",
        )

        // ------------------------------------------------------------------
        // JSON 反序列化
        // ------------------------------------------------------------------

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

        private fun courseStartFromJson(arr: JSONArray?): List<CourseStartRule> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CourseStartRule(
                    pattern = o.getString("pattern"),
                    name = o.optString("name", ""),
                    nameGroup = o.optInt("nameGroup", 1),
                    type = o.optString("type", ""),
                    typeGroup = o.optInt("typeGroup", 0),
                    typeMap = o.optJSONObject("typeMap")
                        ?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } }
                        ?: emptyMap(),
                )
            }
        }

        private fun fieldRulesFromJson(arr: JSONArray?): List<FieldRule> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FieldRule(
                    field = o.getString("field"),
                    name = o.optString("name", ""),
                    pattern = o.optString("pattern", ""),
                    captureGroup = o.optInt("captureGroup", 1),
                    linePrefix = if (o.has("linePrefix")) o.getString("linePrefix") else null,
                    line = if (o.has("line")) o.getString("line") else null,
                    stripTrailingParen = o.optBoolean("stripTrailingParen", false),
                    splitCampus = o.optBoolean("splitCampus", false),
                    cleanTeacher = o.optBoolean("cleanTeacher", false),
                    excludeChars = o.optString("excludeChars", ""),
                )
            }
        }

        /**
         * 从 JSON 反序列化并做完整校验（结构 + 安全上限 + 正则可编译）。
         * 任何不合法直接抛 IllegalArgumentException，导入界面展示原因。
         */
        fun fromJson(text: String): ParseRulePack {
            val root = JSONObject(text)
            val schema = root.getInt("schemaVersion")
            require(schema in 1..SCHEMA_VERSION_MAX) {
                "不支持的规则包版本（当前支持 1/$SCHEMA_VERSION_MAX，过低可能需要升级应用）"
            }
            val id = str(root, "id", "").trim()
            require(id.isNotEmpty() && id.length <= 40 && id.all { it.isLetterOrDigit() || it in "-_." }) {
                "id 只能含字母数字与 -_."
            }
            val name = str(root, "name", "").trim()
            require(name.isNotEmpty() && name.length <= 40) { "name 不能为空且不超过 40 字" }
            val match = root.getJSONObject("match")
            val table = root.getJSONObject("table")
            val matchAnyOf = strList(match, "anyOf")
            require(matchAnyOf.isNotEmpty() && matchAnyOf.size <= MAX_LIST_ITEMS) { "match.anyOf 不能为空" }
            matchAnyOf.forEach {
                require(it.isNotEmpty() && it.length <= MAX_WORD_LEN) { "match.anyOf 词条超长" }
            }

            val pack: ParseRulePack
            if (schema == 1) {
                val blockStart = root.getJSONObject("blockStart")
                val fields = root.getJSONObject("fields")
                pack = ParseRulePack(
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
                    weeksPattern = str(fields, "weeksPattern", WEEKS_PATTERN_DEFAULT),
                    paritySingle = str(fields, "paritySingle", "单"),
                    parityDouble = str(fields, "parityDouble", "双"),
                    positionalTeacher = fields.optBoolean("positionalTeacher", false),
                    positionalRoom = fields.optBoolean("positionalRoom", false),
                    sourceSchema = 1,
                )
            } else {
                val blockStart = root.optJSONObject("blockStart") ?: JSONObject()
                val fields = root.optJSONObject("fields") ?: JSONObject()
                // v2 收紧：v1 的执行器开关一律改为显式规则
                listOf("typeMarks", "markAliases", "trailingZeroFallback").forEach {
                    require(!blockStart.has(it)) { "schemaVersion 2 不再支持 blockStart.$it，请改写为 courseStart 规则" }
                }
                listOf("positionalTeacher", "positionalRoom").forEach {
                    require(!fields.has(it)) { "schemaVersion 2 不再支持 fields.$it，请改写为 fieldRules 行形状规则" }
                }
                pack = ParseRulePack(
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
                    courseStart = courseStartFromJson(blockStart.optJSONArray("courseStart")),
                    fieldRules = fieldRulesFromJson(fields.optJSONArray("fieldRules")),
                    keyMap = strMap(fields, "keyMap"),
                    fieldSeparators = str(fields, "fieldSeparators", ":：·・"),
                    fieldEndChars = str(fields, "fieldEndChars", "/"),
                    linePrefixes = strMap(fields, "linePrefixes"),
                    sectionsPattern = str(fields, "sectionsPattern", "\\((\\d+)-(\\d+)节\\)"),
                    weeksPattern = str(fields, "weeksPattern", WEEKS_PATTERN_DEFAULT),
                    paritySingle = str(fields, "paritySingle", "单"),
                    parityDouble = str(fields, "parityDouble", "双"),
                    screenshot = root.optBoolean("screenshot", false),
                    sourceSchema = 2,
                )
            }
            pack.validateAndCompile()
            return pack
        }

        // ------------------------------------------------------------------
        // 内置规则包（v2 原生：块起始与字段抽取全部为规则数据）
        // ------------------------------------------------------------------

        private fun cs(pattern: String, type: String, name: String) =
            CourseStartRule(pattern = pattern, type = type, name = name)

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
            courseStart = listOf(
                cs("^(?=.*[一-龥])(.+)★$", "讲课", "类型标记 ★"),
                cs("^(?=.*[一-龥])(.+)○$", "实验", "类型标记 ○"),
                cs("^(?=.*[一-龥])(.+)●$", "上机", "类型标记 ●"),
                cs("^(?=.*[一-龥])(.+)◇$", "实践", "类型标记 ◇"),
                cs("^(.+):$", "集中实践", "类型标记 :"),
                cs("^(?=.*[一-龥])(.+)〇$", "实验", "标记别名 〇"),
                cs("^(?=.*[一-龥])(.+)О$", "实验", "标记别名 О"),
                cs("^(?=.*[一-龥])(.+)O$", "实验", "标记别名 O"),
                cs("${DIGIT_TAIL_GUARD}0$", "实验", "行尾0兜底（○被OCR成0）"),
                // 粘连行：OCR 检测框常把「课名+标记」与后续内容合进一行
                // （"物理实验A○ (6-8节)11-17周/校区:下"）——标记后紧跟空白或
                // 分隔符即视为块起始，其余文本按续行（rest）交给字段规则
                cs("^([^/：:【]*[一-龥][^/：:【]{0,22})★(?=[\\s/（(])", "讲课", "粘连行标记 ★"),
                cs("^([^/：:【]*[一-龥][^/：:【]{0,22})○(?=[\\s/（(])", "实验", "粘连行标记 ○"),
                cs("^([^/：:【]*[一-龥][^/：:【]{0,22})●(?=[\\s/（(])", "上机", "粘连行标记 ●"),
                cs("^([^/：:【]*[一-龥][^/：:【]{0,22})◇(?=[\\s/（(])", "实践", "粘连行标记 ◇"),
                cs("^([^/：:【]*[一-龥][^/：:【]{0,22})O(?=[\\s/（(])", "实验", "粘连行标记 O"),
            ),
            keyMap = mapOf(
                "教学班组成" to "composition", "教学班" to "classNo",
                "校区" to "campus", "楼号" to "building", "场地" to "room",
                "教师" to "teacher", "学分" to "credit",
            ),
            // 空格兜底：OCR 常把冒号读成空格（"教师 李四"）；正文拼接本就会去空白
            fieldSeparators = ":：·・ ",
            fieldEndChars = "/",
            sectionsPattern = "\\((\\d+)-(\\d+)节\\)",
            weeksPattern = WEEKS_PATTERN_DEFAULT,
            paritySingle = "单",
            parityDouble = "双",
            sourceSchema = 2,
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
            courseStart = listOf(
                cs("^(?=.*[一-龥])(.+)★$", "讲课", "类型标记 ★"),
                cs("^(?=.*[一-龥])(.+)●$", "上机", "类型标记 ●"),
                cs("^(?=.*[一-龥])(.+)○$", "实验", "类型标记 ○"),
                cs("^(?=.*[一-龥])(.+)◇$", "实践", "类型标记 ◇"),
                cs("^(?=.*[一-龥])(.+)〇$", "实验", "标记别名 〇"),
                cs("^(?=.*[一-龥])(.+)O$", "实验", "标记别名 O"),
                cs("${DIGIT_TAIL_GUARD}0$", "", "行尾0兜底（无类型）"),
                // 粘连行：同 zfsoft（"物理实验A○ (6-8节)11-17周/…"式检测框粘行）
                cs("^([^/：:【]*[一-龥][^/：:【]{0,22})★(?=[\\s/（(])", "讲课", "粘连行标记 ★"),
                cs("^([^/：:【]*[一-龥][^/：:【]{0,22})○(?=[\\s/（(])", "实验", "粘连行标记 ○"),
                cs("^([^/：:【]*[一-龥][^/：:【]{0,22})●(?=[\\s/（(])", "上机", "粘连行标记 ●"),
                cs("^([^/：:【]*[一-龥][^/：:【]{0,22})◇(?=[\\s/（(])", "实践", "粘连行标记 ◇"),
                cs("^([^/：:【]*[一-龥][^/：:【]{0,22})O(?=[\\s/（(])", "实验", "粘连行标记 O"),
            ),
            fieldRules = listOf(
                FieldRule("sections", linePrefix = "◎", name = "节次周次行"),
                FieldRule("room", linePrefix = "📍", splitCampus = true, name = "地点行"),
                FieldRule("teacher", linePrefix = "👤", cleanTeacher = true, name = "教师行"),
                FieldRule("classNo", linePrefix = "🏠", name = "教学班行"),
                FieldRule("room", linePrefix = "@", splitCampus = true, name = "@地点行"),
            ) + teacherFallbackRules() + listOf(RoomShapeRule, ClassNoShapeRule),
            sectionsPattern = "\\((\\d+)-(\\d+)节\\)",
            weeksPattern = WEEKS_PATTERN_DEFAULT,
            paritySingle = "单",
            parityDouble = "双",
            screenshot = true,
            sourceSchema = 2,
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
            legendWords = listOf("打印时间", "图例", "备注", "实践环节"),
            courseStart = listOf(
                CourseStartRule(
                    pattern = "^(.+)\\((理论|理论课|实践|实验|上机|实训)\\)$",
                    nameGroup = 1,
                    typeGroup = 2,
                    typeMap = mapOf(
                        "理论" to "讲课", "理论课" to "讲课", "实践" to "实践",
                        "实验" to "实验", "上机" to "上机", "实训" to "实践",
                    ),
                    name = "课名(类型)后缀",
                ),
                // 宽松兜底：OCR 常把标题与教师粘连成一行、或吞掉右括号
                // （"大学体育3(实践" / "电工电子技术基础B (实验) 刘红梅【16-17周】"）
                CourseStartRule(
                    pattern = "^(.+?)[（(](理论|理论课|实践|实验|上机|实训)",
                    nameGroup = 1,
                    typeGroup = 2,
                    typeMap = mapOf(
                        "理论" to "讲课", "理论课" to "讲课", "实践" to "实践",
                        "实验" to "实验", "上机" to "上机", "实训" to "实践",
                    ),
                    name = "课名(类型)宽松匹配",
                ),
            ),
            fieldRules = teacherFallbackRules() + listOf(RoomShapeRule),
            weeksPattern = WEEKS_PATTERN_MULTI,
            paritySingle = "单",
            parityDouble = "双",
            sourceSchema = 2,
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
            courseStart = listOf(
                cs("^(?=.*[一-龥])(.+)★$", "讲课", "类型标记 ★"),
                cs("^(?=.*[一-龥])(.+)●$", "上机", "类型标记 ●"),
                cs("^(?=.*[一-龥])(.+)○$", "实验", "类型标记 ○"),
                cs("^(?=.*[一-龥])(.+)◇$", "实践", "类型标记 ◇"),
                cs("^(?=.*[一-龥])(.+)〇$", "实验", "标记别名 〇"),
                cs("${DIGIT_TAIL_GUARD}0$", "", "行尾0兜底（无类型）"),
            ),
            fieldRules = listOf(
                FieldRule("room", linePrefix = "@", splitCampus = true, name = "@地点行"),
            ) + teacherFallbackRules() + listOf(RoomShapeRule),
            sectionsPattern = "\\((\\d+)-(\\d+)节\\)",
            weeksPattern = WEEKS_PATTERN_DEFAULT,
            paritySingle = "单",
            parityDouble = "双",
            sourceSchema = 2,
        )

        val Builtins = listOf(Zfsoft, IconGrid, Qz, Generic)
    }

    // ------------------------------------------------------------------
    // 校验：结构 + 上限 + 正则可编译（导入与内置共用）
    // ------------------------------------------------------------------

    /** 编译后的运行时句柄（懒编译；非数据的一部分，不参与 equals）。 */
    val compiled: CompiledRules by lazy { buildCompiled() }

    class CompiledRules(
        val courseStart: List<StartRuleC>,
        val fieldRules: List<FieldRuleC>,
        val sections: Regex?,
        val weeks: Regex,
        /** 课程类型词表（课程块噪声过滤用，如「图例：集中实践 ★: 讲课」碎片行）。 */
        val typeWords: Set<String>,
    ) {
        class StartRuleC(
            val desc: String,
            val re: Regex,
            val nameGroup: Int,
            val typeGroup: Int,
            val typeMap: Map<String, String>,
            val typeConst: String,
        )

        /** 块起始命中结果；rest = 标题行中类型标记之后的剩余文本
         *  （宽松规则命中粘连行时，剩余部分按续行交给字段规则）。 */
        class StartMatch(
            val rule: StartRuleC,
            val name: String,
            val type: String,
            val rest: String,
        )

        class FieldRuleC(
            val desc: String,
            val field: String,
            val re: Regex?,
            val captureGroup: Int,
            val linePrefix: String?,
            val linePick: Int,
            val stripTrailingParen: Boolean,
            val splitCampus: Boolean,
            val cleanTeacher: Boolean,
            val excludeChars: String,
        )

        /** 有序块起始匹配：第一条命中规则生效。 */
        fun matchCourseStart(line: String): StartMatch? {
            val t = if (line.length > MAX_SUBJECT_LEN) line.take(MAX_SUBJECT_LEN) else line
            for (r in courseStart) {
                val m = r.re.find(t) ?: continue
                val name = m.groupValues.getOrNull(r.nameGroup)?.trim().orEmpty()
                if (name.isEmpty()) continue
                val type = if (r.typeGroup > 0 && r.typeMap.isNotEmpty()) {
                    r.typeMap[m.groupValues.getOrNull(r.typeGroup).orEmpty()].orEmpty()
                } else r.typeConst
                val rest = if (m.range.last + 1 < t.length) t.substring(m.range.last + 1).trim() else ""
                return StartMatch(r, name, type, rest)
            }
            return null
        }
    }

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
        // ---- v2 规则列表 ----
        require(courseStart.size <= MAX_LIST_ITEMS) { "courseStart 规则过多" }
        courseStart.forEachIndexed { i, r ->
            require(r.pattern.isNotEmpty() && r.pattern.length <= MAX_PATTERN_LEN) { "courseStart[$i] pattern 非法" }
            require(r.nameGroup >= 1) { "courseStart[$i] nameGroup 需 ≥1" }
            require(r.typeMap.size <= MAX_LIST_ITEMS) { "courseStart[$i] typeMap 条目过多" }
            r.typeMap.forEach { (k, v) ->
                require(k.isNotEmpty() && k.length <= MAX_WORD_LEN && v.length <= MAX_WORD_LEN) {
                    "courseStart[$i] typeMap 词条超长"
                }
            }
            if (r.typeGroup > 0) require(r.typeMap.isNotEmpty()) { "courseStart[$i] typeGroup 需配合 typeMap" }
        }
        require(fieldRules.size <= MAX_LIST_ITEMS) { "fieldRules 规则过多" }
        fieldRules.forEachIndexed { i, r ->
            require(r.field in SEMANTIC_FIELDS) { "fieldRules[$i] 语义字段 ${r.field} 不在白名单" }
            require(r.line == null || r.line in listOf("first", "last")) { "fieldRules[$i] line 取值非法" }
            require(r.linePrefix == null || r.line == null) { "fieldRules[$i] linePrefix 与 line 不可同时使用" }
            if (r.linePrefix == null) {
                require(r.pattern.isNotEmpty() && r.pattern.length <= MAX_PATTERN_LEN) { "fieldRules[$i] pattern 非法" }
            } else {
                require(r.linePrefix.isNotEmpty() && r.linePrefix.length <= MAX_WORD_LEN) { "fieldRules[$i] linePrefix 非法" }
            }
            require(r.captureGroup >= 1) { "fieldRules[$i] captureGroup 需 ≥1" }
            require(r.excludeChars.length <= MAX_WORD_LEN) { "fieldRules[$i] excludeChars 超长" }
        }
        if (sourceSchema >= 2) {
            require(courseStart.isNotEmpty()) { "schemaVersion 2 需要 courseStart 规则" }
        }
        compiled // 强制编译，正则不合法在此抛出
    }

    // ------------------------------------------------------------------
    // 编译：统一为 v2 规则（v1 形态在此做糖展开）
    // ------------------------------------------------------------------

    private fun buildCompiled(): CompiledRules {
        val sections = if (sectionsPattern.isBlank()) null
        else runCatching { Regex(sectionsPattern) }
            .getOrElse { throw IllegalArgumentException("sectionsPattern 编译失败") }
        val weeks = runCatching { Regex(weeksPattern) }
            .getOrElse { throw IllegalArgumentException("weeksPattern 编译失败") }

        val startData = if (courseStart.isNotEmpty()) courseStart else expandV1BlockStart()
        val startRules = startData.map { r ->
            val re = compileRegex(r.pattern, "courseStart[${r.name.ifEmpty { r.pattern.take(20) }}]")
            val groupCount = groupCountOf(r.pattern)
            require(r.nameGroup in 1..groupCount) { "courseStart(${r.name}) nameGroup=${r.nameGroup} 超出捕获组数 $groupCount" }
            if (r.typeGroup > 0) {
                require(r.typeGroup in 1..groupCount) { "courseStart(${r.name}) typeGroup=${r.typeGroup} 超出捕获组数 $groupCount" }
            }
            CompiledRules.StartRuleC(
                desc = r.name.ifEmpty { r.pattern.take(30) },
                re = re, nameGroup = r.nameGroup,
                typeGroup = r.typeGroup, typeMap = r.typeMap, typeConst = r.type,
            )
        }

        val fieldData = if (fieldRules.isNotEmpty()) fieldRules else expandV1Fields()
        val fieldRulesC = fieldData.map { r ->
            val re = if (r.linePrefix != null) null
            else compileRegex(r.pattern, "fieldRules(${r.name.ifEmpty { r.field }})")
            if (re != null) {
                val groupCount = groupCountOf(r.pattern)
                require(r.captureGroup in 1..groupCount) {
                    "fieldRules(${r.name.ifEmpty { r.field }}) captureGroup=${r.captureGroup} 超出捕获组数 $groupCount"
                }
            }
            CompiledRules.FieldRuleC(
                desc = r.name.ifEmpty { r.field },
                field = r.field, re = re, captureGroup = r.captureGroup,
                linePrefix = r.linePrefix,
                linePick = when (r.line) {
                    "first" -> LINE_FIRST
                    "last" -> LINE_LAST
                    else -> LINE_BODY
                },
                stripTrailingParen = r.stripTrailingParen,
                splitCampus = r.splitCampus,
                cleanTeacher = r.cleanTeacher,
                excludeChars = r.excludeChars,
            )
        }

        val typeWords = buildSet {
            startData.forEach { r ->
                if (r.type.isNotBlank()) add(r.type)
                addAll(r.typeMap.values.filter { it.isNotBlank() })
            }
        }

        return CompiledRules(startRules, fieldRulesC, sections, weeks, typeWords)
    }

    private fun compileRegex(pattern: String, where: String): Regex =
        runCatching { Regex(pattern) }
            .getOrElse { throw IllegalArgumentException("$where 正则编译失败") }

    private fun groupCountOf(pattern: String): Int =
        java.util.regex.Pattern.compile(pattern).matcher("").groupCount()

    /** v1 糖编译：typeMarks/markAliases/trailingZeroFallback → 块起始规则（顺序保持 v1 语义）。 */
    private fun expandV1BlockStart(): List<CourseStartRule> {
        val rules = mutableListOf<CourseStartRule>()
        val seen = mutableSetOf<String>()
        fun add(pattern: String, type: String, name: String) {
            if (seen.add(pattern)) rules += CourseStartRule(pattern = pattern, type = type, name = name)
        }
        typeMarks.forEach { (mark, t) ->
            add("^(.+)${escapeLiteral(mark)}$", t, "类型标记 $mark")
        }
        markAliases.forEach { (wrong, right) ->
            val pat = if (wrong.all { it.isDigit() }) "$DIGIT_TAIL_GUARD${escapeLiteral(wrong)}$"
            else "^(.+)${escapeLiteral(wrong)}$"
            add(pat, typeMarks[right] ?: "", "标记别名 $wrong→$right")
        }
        if (trailingZeroFallback) {
            add("${DIGIT_TAIL_GUARD}0$", typeForMark("0"), "行尾0兜底")
        }
        return rules
    }

    /** v1 糖编译：keyMap/linePrefixes/位置式开关 → 字段规则（优先级保持 v1：前缀 > 键值 > 位置兜底）。 */
    private fun expandV1Fields(): List<FieldRule> {
        val rules = mutableListOf<FieldRule>()
        linePrefixes.forEach { (p, f) ->
            rules += FieldRule(
                field = f, linePrefix = p,
                cleanTeacher = f == "teacher", splitCampus = f == "room",
                name = "前缀 $p",
            )
        }
        // 长 key 在前，防「教学班」截胡「教学班组成」；扩展后缀守卫防短 key 吃进长 key 的值
        val keys = keyMap.keys.sortedByDescending { it.length }
        keys.forEach { k ->
            val f = keyMap[k] ?: return@forEach
            val extensions = keys.filter { it != k && it.startsWith(k) }.map { it.removePrefix(k) }
            val guard = if (extensions.isNotEmpty()) {
                "(?!${extensions.joinToString("|") { escapeLiteral(it) }})"
            } else ""
            val pattern =
                "(?:^|[${cls(fieldEndChars)};；,，])(${escapeLiteral(k)})$guard\\s*[${cls(fieldSeparators)}]?\\s*([^${cls(fieldEndChars)}]*)"
            rules += FieldRule(
                field = f, pattern = pattern, captureGroup = 2,
                cleanTeacher = f == "teacher", splitCampus = f == "room",
                name = "键值 $k",
            )
        }
        if (positionalTeacher || "teacher" in linePrefixes.values) rules += teacherFallbackRules()
        if (positionalRoom || "room" in linePrefixes.values) rules += listOf(RoomShapeRule)
        if ("classNo" in linePrefixes.values) rules += listOf(ClassNoShapeRule)
        return rules
    }

    private fun typeForMark(mark: String): String =
        typeMarks[mark] ?: markAliases[mark]?.let { typeMarks[it] } ?: ""

    /** 指纹得分：全文命中 matchAnyOf 的子串数。 */
    fun fingerprintScore(fullText: String): Int =
        matchAnyOf.count { it in fullText }

    /** 此包是否适用（得分达标）。 */
    fun matches(fullText: String): Boolean = fingerprintScore(fullText) >= matchMinHits
}

/** 字符类内转义（Android ICU 对 \Q..\E 在类内的支持不一致，不能使用 Regex.escape）。 */
private fun cls(s: String): String =
    s.replace("\\", "\\\\").replace("]", "\\]")
        .replace("^", "\\^").replace("-", "\\-")

/** 正则字面量转义（用于把标记词嵌入模式）。 */
private fun escapeLiteral(s: String): String = buildString {
    for (c in s) {
        if (c in "\\^$.|?*+()[]{}-" || c.code < 0x20) append('\\')
        append(c)
    }
}
