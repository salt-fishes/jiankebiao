package com.example.composeapp.schedule

/** PDF 中提取的单个字符及其坐标（PDF 坐标系：x 向右，y 向下，单位 pt）。 */
data class PdfChar(
    val char: String,
    val x: Float,
    val y: Float,
)

/** 解析出的课程基础信息。 */
data class ParsedCourse(
    val name: String,
    val type: String,          // 讲课/实验/上机/实践/集中实践
    val credit: String,
    val teacher: String,
    val campus: String,
    val building: String,
    val room: String,
    val classNo: String,
    val composition: String,
)

/** 解析出的排课条目。同一门课不同天可能不同教室，故地点/教师随条目存储。 */
data class ParsedEntry(
    val course: String,
    val dayOfWeek: Int,        // 1=星期一 ... 7=星期日
    val startSection: Int?,
    val endSection: Int?,
    val weeks: List<Int>,      // 展开的周列表
    val campus: String = "",   // 校区（随条目）
    val building: String = "", // 楼号（随条目）
    val room: String = "",     // 场地（随条目）
    val teacher: String = "",  // 教师（随条目）
)

/** 整个 PDF 的解析结果。 */
data class ParsedSchedule(
    val courses: List<ParsedCourse>,
    val entries: List<ParsedEntry>,
) {
    /** 序列化为 JSON（用于跨进程/界面恢复）。 */
    fun toJson(): String = org.json.JSONObject().apply {
        put("courses", org.json.JSONArray().apply {
            courses.forEach { c ->
                put(org.json.JSONObject().apply {
                    put("name", c.name); put("type", c.type); put("credit", c.credit)
                    put("teacher", c.teacher); put("campus", c.campus)
                    put("building", c.building); put("room", c.room)
                    put("classNo", c.classNo); put("composition", c.composition)
                })
            }
        })
        put("entries", org.json.JSONArray().apply {
            entries.forEach { e ->
                put(org.json.JSONObject().apply {
                    put("course", e.course); put("dayOfWeek", e.dayOfWeek)
                    put("startSection", e.startSection ?: org.json.JSONObject.NULL)
                    put("endSection", e.endSection ?: org.json.JSONObject.NULL)
                    put("weeks", org.json.JSONArray(e.weeks.toTypedArray()))
                    put("campus", e.campus); put("building", e.building)
                    put("room", e.room); put("teacher", e.teacher)
                })
            }
        })
    }.toString()

    companion object {
        /** 从 JSON 反序列化（解析失败时返回包含 error 的 Result）。 */
        fun fromJson(text: String): Result<ParsedSchedule> = runCatching {
            val root = org.json.JSONObject(text)
            if (root.has("error")) error(root.getString("error"))
            val courses = mutableListOf<ParsedCourse>()
            val entries = mutableListOf<ParsedEntry>()
            root.getJSONArray("courses").let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    courses.add(
                        ParsedCourse(
                            name = o.getString("name"), type = o.getString("type"),
                            credit = o.getString("credit"), teacher = o.getString("teacher"),
                            campus = o.getString("campus"), building = o.getString("building"),
                            room = o.getString("room"), classNo = o.getString("classNo"),
                            composition = o.getString("composition"),
                        )
                    )
                }
            }
            root.getJSONArray("entries").let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val weeks = mutableListOf<Int>()
                    val w = o.getJSONArray("weeks")
                    for (j in 0 until w.length()) weeks.add(w.getInt(j))
                    entries.add(
                        ParsedEntry(
                            course = o.getString("course"), dayOfWeek = o.getInt("dayOfWeek"),
                            startSection = if (o.isNull("startSection")) null else o.getInt("startSection"),
                            endSection = if (o.isNull("endSection")) null else o.getInt("endSection"),
                            weeks = weeks,
                            campus = o.optString("campus", ""),
                            building = o.optString("building", ""),
                            room = o.optString("room", ""),
                            teacher = o.optString("teacher", ""),
                        )
                    )
                }
            }
            ParsedSchedule(courses, entries)
        }
    }
}
