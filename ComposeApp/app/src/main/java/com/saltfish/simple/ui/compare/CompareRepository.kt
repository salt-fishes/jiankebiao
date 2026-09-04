package com.saltfish.simple.ui.compare

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 「对比课表」：轻量课表，只有占用矩阵（星期×节次），来源于课表截图识别。
 * 与正式课表（Room）并存，专门用于多人课表对比找共同空闲时间。
 * 数据量很小，存 JSON 文件即可，不进 Room（避免迁移成本）。
 */
data class CompareBlock(
    val day: Int,          // 1..7，周一=1
    val startSection: Int, // 1..N
    val endSection: Int,
)

data class CompareTimetable(
    val id: Long,
    val name: String,
    val dayCount: Int,     // 截图中可见的星期列数（未覆盖的星期视为无课）
    val blocks: List<CompareBlock>,
)

object CompareRepository {

    private const val FILE = "compare_timetables.json"

    private fun file(context: Context) = File(context.filesDir, FILE)

    suspend fun load(context: Context): List<CompareTimetable> = withContext(Dispatchers.IO) {
        val f = file(context)
        if (!f.exists()) return@withContext emptyList()
        runCatching { decode(f.readText()) }.getOrDefault(emptyList())
    }

    suspend fun save(context: Context, timetables: List<CompareTimetable>) = withContext(Dispatchers.IO) {
        file(context).writeText(encode(timetables))
    }

    suspend fun add(context: Context, timetable: CompareTimetable): List<CompareTimetable> {
        val next = load(context) + timetable
        save(context, next)
        return next
    }

    suspend fun remove(context: Context, id: Long): List<CompareTimetable> {
        val next = load(context).filterNot { it.id == id }
        save(context, next)
        return next
    }

    fun nextId(existing: List<CompareTimetable>): Long =
        (existing.maxOfOrNull { it.id } ?: 0L) + 1

    private fun encode(list: List<CompareTimetable>): String = JSONObject().apply {
        put("version", 1)
        put("timetables", JSONArray().apply {
            list.forEach { t ->
                put(JSONObject().apply {
                    put("id", t.id)
                    put("name", t.name)
                    put("dayCount", t.dayCount)
                    put("blocks", JSONArray().apply {
                        t.blocks.forEach { b ->
                            put(JSONObject().apply {
                                put("day", b.day)
                                put("startSection", b.startSection)
                                put("endSection", b.endSection)
                            })
                        }
                    })
                })
            }
        })
    }.toString()

    private fun decode(text: String): List<CompareTimetable> {
        val root = JSONObject(text)
        val arr = root.optJSONArray("timetables") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val blocks = mutableListOf<CompareBlock>()
            val bArr = o.optJSONArray("blocks") ?: JSONArray()
            for (j in 0 until bArr.length()) {
                val b = bArr.optJSONObject(j) ?: continue
                blocks += CompareBlock(
                    day = b.optInt("day"),
                    startSection = b.optInt("startSection"),
                    endSection = b.optInt("endSection"),
                )
            }
            CompareTimetable(
                id = o.optLong("id"),
                name = o.optString("name"),
                dayCount = o.optInt("dayCount", 7),
                blocks = blocks,
            )
        }
    }
}
