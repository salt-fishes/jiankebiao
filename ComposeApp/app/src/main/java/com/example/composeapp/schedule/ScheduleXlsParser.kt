package com.example.composeapp.schedule

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 正方教务「班级课表」Excel 导出解析（.xls，BIFF8 二进制格式）。
 *
 * 个人课表走 SchedulePdfParser（OCR）；班级课表导出为 Excel，数据本身是结构化文本，
 * 无需 OCR。为避免引入 Apache POI 等重依赖，这里内置一个仅覆盖读取所需的
 * OLE2 复合文档 + BIFF8 最小实现：只取单元格文本（SST 共享字符串 + 行列号），
 * 不解析格式/公式依赖。
 *
 * 班级课表表格结构（以实际导出为准，列位动态识别）：
 *   表头行含 "星期一..星期日"；
 *   数据单元格为 "课程名/(x-y节)1-3周,5-16周/校区 地点/教师/总学时/学分"，
 *   同一格多条课程用换行分隔，个别条目可省略 "(x-y节)"（回退同行显式节次）。
 */
object ScheduleXlsParser {

    /** 支持 .xls（BIFF）；.xlsx/et 传入时给出明确报错而非 PDF 解析的误导性失败。 */
    private val XLS_LIKE_EXTENSIONS = setOf("xls", "xlsx", "et")

    fun isXlsLike(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in XLS_LIKE_EXTENSIONS

    fun parse(file: File): ParsedSchedule {
        val data = file.readBytes()
        if (data.size < 8 || data[0] != uncheckedLeaf(0xD0) || data[1] != uncheckedLeaf(0xCF) ||
            data[2] != uncheckedLeaf(0x11) || data[3] != uncheckedLeaf(0xE0)
        ) {
            throw IllegalArgumentException("不是有效的 .xls 文件（暂不支持 xlsx 或网页导出格式）")
        }
        return parseGrid(readGrid(data))
    }

    private fun uncheckedLeaf(v: Int): Byte = v.toByte()

    // ==================== 表格解析 ====================

    /** 单元格 key：row << 14 | col（BIFF8 最多 16384 列）。 */
    private fun key(row: Int, col: Int): Long = (row.toLong() shl 14) or col.toLong()

    private val DAY_CHARS = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7,
    )

    /** "星期三" / "周三" -> 3；仅接受短单元格，避免课程正文误匹配。 */
    private fun dayFromText(text: String): Int? {
        val t = text.trim()
        if (t.length > 5) return null
        val re = Regex("(?:星期|周)([一二三四五六日天])")
        val m = re.find(t) ?: return null
        return DAY_CHARS[m.groupValues[1][0]]
    }

    private val META_SECTION_RE = Regex("""\((\d+)\s*-\s*(\d+)节\)""")
    private val META_SINGLE_SECTION_RE = Regex("""\((\d+)节\)""")

    private fun normalizeName(s: String): String =
        s.replace(" ", "").replace("　", "").replace("（", "(").replace("）", ")").trim()

    private class CourseLine(
        val name: String,
        val sections: List<Int>?,   // null = 条目未写节次，用同行回退
        val weeks: List<Int>,
        val campus: String,
        val building: String,
        val room: String,
        val teacher: String,
        val credit: String,
    )

    /**
     * 解析单条课程行 "课程名/(1-2节)1-3周,5-16周/下沙 环宇楼C204（智慧教室）/杜昕/32/2.0"。
     * 不符合结构（缺 "/" 分段、无周次/节次信息）返回 null。
     */
    private fun parseCourseLine(line: String): CourseLine? {
        val parts = line.split('/')
        if (parts.size < 2) return null
        val name = normalizeName(parts[0])
        if (name.isEmpty()) return null
        val meta = parts[1].trim()
        if ("周" !in meta && "节" !in meta) return null

        val sections = META_SECTION_RE.find(meta)?.let {
            listOf(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        } ?: META_SINGLE_SECTION_RE.find(meta)?.let {
            val s = it.groupValues[1].toInt()
            listOf(s, s)
        }
        val weeks = ScheduleParser.parseWeeks(meta.substringAfter("节)", meta))

        // 字段顺序：课程/(节)周次/地点/教师/总学时/学分；教师~学分三段锚定在行尾，
        // 地点内若出现 "/" 会被重新拼回（parts[2..size-3]）。
        val teacher: String
        val credit: String
        val loc: String
        when {
            parts.size >= 6 -> {
                // loc 段为 parts[2..size-4]（教师/学时/学分锚定行尾），地点含 "/" 时重新拼回
                loc = (2 until parts.size - 3).joinToString("/") { parts[it] }.trim()
                teacher = parts[parts.size - 3].trim()
                credit = parts[parts.size - 1].trim()
            }
            parts.size == 5 -> { loc = parts[2]; teacher = parts[3].trim(); credit = parts[4].trim() }
            parts.size == 4 -> { loc = parts[2]; teacher = parts[3].trim(); credit = "" }
            parts.size == 3 -> { loc = parts[2]; teacher = ""; credit = "" }
            else -> { loc = ""; teacher = ""; credit = "" }
        }
        val (campus, building, room) = splitLocation(loc)
        return CourseLine(name, sections, weeks, campus, building, room, teacher, credit)
    }

    /** "下沙 环宇楼C204（智慧教室）" -> campus=下沙, building=环宇楼, room=全段；"未排地点" 不拆楼号。 */
    private fun splitLocation(loc: String): Triple<String, String, String> {
        val t = loc.trim()
        if (t.isEmpty()) return Triple("", "", "")
        val i = t.indexOfFirst { it == ' ' || it == '　' }
        var campus = ""
        var rest = t
        if (i > 0) {
            campus = t.take(i).trim()
            rest = t.substring(i + 1).trim()
        }
        if (rest.isEmpty() || rest == "未排地点") return Triple(campus, "", rest)
        val building = Regex("^[一-鿿]+").find(rest)?.value ?: ""
        return Triple(campus, building, rest)
    }

    /** 课程级信息聚合（同名课程跨单元格合并，取首个非空值）。 */
    private class CourseAcc(
        var credit: String, var teacher: String,
        var campus: String, var building: String, var room: String,
    ) {
        fun merge(other: CourseLine) {
            if (credit.isBlank()) credit = other.credit
            if (teacher.isBlank()) teacher = other.teacher
            if (campus.isBlank()) campus = other.campus
            if (building.isBlank()) building = other.building
            if (room.isBlank()) room = other.room
        }
    }

    private fun parseGrid(grid: Map<Long, String>): ParsedSchedule {
        if (grid.isEmpty()) throw IllegalArgumentException("Excel 中没有可读取的数据")
        var maxRow = 0
        var maxCol = 0
        for (k in grid.keys) {
            val r = (k shr 14).toInt()
            val c = (k and 0x3FFF).toInt()
            if (r > maxRow) maxRow = r
            if (c > maxCol) maxCol = c
        }

        // 1) 定位星期表头行（取含 "星期X" 单元格最多的一行）
        var headerRow = -1
        var dayCols: Map<Int, Int> = emptyMap()   // 列号 -> dayOfWeek
        for (r in 0..maxRow) {
            val m = HashMap<Int, Int>()
            for (c in 0..maxCol) {
                val v = grid[key(r, c)] ?: continue
                dayFromText(v)?.let { m[c] = it }
            }
            if (m.size > dayCols.size) {
                dayCols = m
                headerRow = r
            }
        }
        if (headerRow < 0 || dayCols.size < 5) {
            throw IllegalArgumentException("未找到星期表头，请确认导出的是正方教务的班级课表 Excel")
        }

        // 2) 数据行：每格按行拆条目
        val coursesByName = LinkedHashMap<String, CourseAcc>()
        val entries = mutableListOf<ParsedEntry>()
        for (r in headerRow + 1..maxRow) {
            val rowLines = mutableListOf<Pair<Int, CourseLine>>()   // dayOfWeek -> line
            for ((c, day) in dayCols) {
                val text = grid[key(r, c)]?.trim() ?: continue
                if (text.isEmpty()) continue
                for (raw in text.lines()) {
                    val line = raw.trim()
                    if (line.isEmpty()) continue
                    parseCourseLine(line)?.let { rowLines.add(day to it) }
                }
            }
            // 同行其他课程都写了节次而本条漏写时，用其作回退
            val rowFallback = rowLines.mapNotNull { it.second.sections }.firstOrNull()
            for ((day, cl) in rowLines) {
                val acc = coursesByName.getOrPut(cl.name) {
                    CourseAcc(cl.credit, cl.teacher, cl.campus, cl.building, cl.room)
                }
                acc.merge(cl)
                val sections = cl.sections ?: rowFallback
                entries.add(
                    ParsedEntry(
                        course = cl.name,
                        dayOfWeek = day,
                        startSection = sections?.getOrNull(0),
                        endSection = sections?.getOrNull(1),
                        weeks = cl.weeks,
                        campus = cl.campus,
                        building = cl.building,
                        room = cl.room,
                        teacher = cl.teacher,
                    )
                )
            }
        }
        if (entries.isEmpty()) throw IllegalArgumentException("未识别到课程，请确认导出的是班级课表 Excel")

        // 教务把跨节次行的连堂课拆开导出（如 6-8 节拆成 "(6-7节)" 与 "(8-8节)" 两行），
        // 同课同天、周次与地点一致且节次相邻的条目合并回一块
        mergeAdjacentEntries(entries)

        val courses = coursesByName.map { (name, a) ->
            ParsedCourse(
                name = name, type = "", credit = a.credit,
                teacher = a.teacher, campus = a.campus, building = a.building,
                room = a.room, classNo = "", composition = "",
            )
        }
        return ParsedSchedule(courses, entries)
    }

    /** 相邻节次条目合并（反复配对直到不动点，覆盖 6-7+8-9、6-7+8+9 等拆分形态）。 */
    private fun mergeAdjacentEntries(entries: MutableList<ParsedEntry>) {
        var merged = true
        while (merged) {
            merged = false
            outer@ for (i in entries.indices) {
                val a = entries[i]
                for (j in entries.indices) {
                    if (i == j) continue
                    val b = entries[j]
                    val adjacent = a.startSection != null && a.endSection != null &&
                        b.startSection != null && b.endSection != null &&
                        (a.endSection!! + 1 == b.startSection || b.endSection!! + 1 == a.startSection)
                    if (a.course != b.course || a.dayOfWeek != b.dayOfWeek || !adjacent) continue
                    if (a.weeks != b.weeks || a.teacher != b.teacher ||
                        a.room != b.room || a.campus != b.campus || a.building != b.building
                    ) continue
                    entries[i] = a.copy(
                        startSection = minOf(a.startSection!!, b.startSection!!),
                        endSection = maxOf(a.endSection!!, b.endSection!!),
                    )
                    entries.removeAt(j)
                    merged = true
                    break@outer
                }
            }
        }
    }

    // ==================== OLE2 复合文档 ====================

    private const val END_OF_CHAIN = -2     // 0xFFFFFFFE
    private const val FREE_SECTOR = -1      // 0xFFFFFFFF

    private fun u16(d: ByteArray, off: Int): Int =
        (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8)

    /** u32 按补码读入 Int：0xFFFFFFFE -> -2 (END_OF_CHAIN)，与 FAT 链终止值比较一致。 */
    private fun u32(d: ByteArray, off: Int): Int =
        (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8) or
            ((d[off + 2].toInt() and 0xFF) shl 16) or ((d[off + 3].toInt() and 0xFF) shl 24)

    private class DirEntry(
        val name: String, val type: Int,
        val start: Int, val size: Long,
        val child: Int, val left: Int, val right: Int,
    )

    private class Ole2(private val d: ByteArray) {

        private val sectorSize = 1 shl u16(d, 0x1E)
        private val miniSize = 1 shl u16(d, 0x20)
        private val miniCutoff = u32(d, 0x38).toLong() and 0xFFFFFFFFL
        private val dirFirst = u32(d, 0x30)

        private val sectorOff = { s: Int -> 512 + s * sectorSize }

        /** DIFAT：头部 109 项 + DIFAT 链上的其余 FAT 扇区号。 */
        private val fatSectorIds: IntArray = run {
            val ids = ArrayList<Int>()
            for (i in 0 until 109) {
                val s = u32(d, 0x4C + i * 4)
                if (s != FREE_SECTOR && s != END_OF_CHAIN && s >= 0) ids.add(s)
            }
            var ds = u32(d, 0x44)
            var guard = 0
            while (ds != END_OF_CHAIN && ds >= 0 && guard++ < 65536) {
                val base = sectorOff(ds)
                if (base + sectorSize > d.size) break
                for (i in 0 until sectorSize / 4 - 1) {
                    val s = u32(d, base + i * 4)
                    if (s != FREE_SECTOR && s != END_OF_CHAIN && s >= 0) ids.add(s)
                }
                ds = u32(d, base + sectorSize - 4)
            }
            ids.toIntArray()
        }

        private val fat: IntArray = run {
            val per = sectorSize / 4
            val a = IntArray(fatSectorIds.size * per)
            fatSectorIds.forEachIndexed { i, s ->
                val base = sectorOff(s)
                for (j in 0 until per) a[i * per + j] = u32(d, base + j * 4)
            }
            a
        }

        private val miniFat: IntArray = run {
            val per = sectorSize / 4
            val ids = ArrayList<Int>()
            var s = u32(d, 0x3C)
            val count = u32(d, 0x40)
            var got = 0
            var guard = 0
            while (s >= 0 && s != END_OF_CHAIN && got < count && guard++ < 65536) {
                ids.add(s)
                got++
                s = if (s < fat.size) fat[s] else END_OF_CHAIN
            }
            val a = IntArray(ids.size * per)
            ids.forEachIndexed { i, sec ->
                val base = sectorOff(sec)
                for (j in 0 until per) a[i * per + j] = u32(d, base + j * 4)
            }
            a
        }

        private fun chain(start: Int, fat: IntArray, guardMax: Int = 1 shl 20): List<Int> {
            val out = ArrayList<Int>()
            var s = start
            val seen = HashSet<Int>()
            while (s >= 0 && s != END_OF_CHAIN && s != FREE_SECTOR && seen.add(s)) {
                out.add(s)
                s = if (s < fat.size) fat[s] else END_OF_CHAIN
            }
            check(out.size <= guardMax) { "FAT 链异常" }
            return out
        }

        private fun readChainSectors(start: Int, size: Long, fat: IntArray, unit: Int): ByteArray {
            val out = ByteArray(size.toInt().coerceAtLeast(0))
            var filled = 0
            for (s in chain(start, fat)) {
                val base = if (unit == sectorSize) sectorOff(s) else s * unit
                if (filled >= out.size) break
                val n = minOf(unit, out.size - filled)
                if (base + n > d.size) break
                System.arraycopy(d, base, out, filled, n)
                filled += n
            }
            return out
        }

        private val dirEntries: List<DirEntry> = run {
            val bytes = readChainSectors(dirFirst, Long.MAX_VALUE.coerceAtMost(
                chain(dirFirst, fat).size.toLong() * sectorSize), fat, sectorSize)
            val out = ArrayList<DirEntry>()
            var off = 0
            while (off + 128 <= bytes.size) {
                val nameLen = u16(bytes, off + 0x40)
                val name = if (nameLen in 2..64) {
                    val charCount = nameLen / 2 - 1
                    String(bytes, off, charCount * 2, StandardCharsets.UTF_16LE)
                } else ""
                out.add(
                    DirEntry(
                        name = name,
                        type = bytes[off + 0x42].toInt() and 0xFF,
                        start = u32(bytes, off + 0x74),
                        size = u32(bytes, off + 0x78).toLong() and 0xFFFFFFFFL,
                        child = u32(bytes, off + 0x4C),
                        left = u32(bytes, off + 0x44),
                        right = u32(bytes, off + 0x48),
                    )
                )
                off += 128
            }
            out
        }

        private val miniStream: ByteArray by lazy {
            val root = dirEntries.firstOrNull { it.type == 5 } ?: return@lazy ByteArray(0)
            readChainSectors(root.start, root.size, fat, sectorSize)
        }

        /** 按 DFS 找流条目（Workbook / Book）。 */
        fun openStream(names: Set<String>): ByteArray? {
            if (dirEntries.isEmpty()) return null
            val root = dirEntries.first { it.type == 5 }
            val stack = ArrayDeque<Int>()
            if (root.child >= 0) stack.add(root.child)
            val seen = HashSet<Int>()
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                if (i < 0 || i >= dirEntries.size || !seen.add(i)) continue
                val e = dirEntries[i]
                if (e.type == 2 && e.name.lowercase() in names) {
                    return if (e.size < miniCutoff) {
                        readChainSectors(e.start, e.size, miniFat, miniSize)
                    } else {
                        readChainSectors(e.start, e.size, fat, sectorSize)
                    }
                }
                if (e.right >= 0) stack.add(e.right)
                if (e.left >= 0) stack.add(e.left)
            }
            return null
        }
    }

    // ==================== BIFF8 记录 ====================

    private class Rec(val id: Int, val data: ByteArray)

    private fun readRecords(stream: ByteArray): List<Rec> {
        val out = ArrayList<Rec>()
        var off = 0
        while (off + 4 <= stream.size) {
            val id = u16(stream, off)
            val len = u16(stream, off + 2)
            if (off + 4 + len > stream.size) break
            out.add(Rec(id, stream.copyOfRange(off + 4, off + 4 + len)))
            off += 4 + len
        }
        return out
    }

    /** 跨多个记录块（SST + CONTINUE）的字节读取器。 */
    private class ChunkReader(private val chunks: List<ByteArray>) {
        var ci = 0
        var pos = 0

        val hasMore: Boolean
            get() {
                var i = ci
                var p = pos
                while (i < chunks.size) {
                    if (p < chunks[i].size) return true
                    i++
                    p = 0
                }
                return false
            }

        private fun advance() {
            while (ci < chunks.size && pos >= chunks[ci].size) {
                ci++
                pos = 0
            }
        }

        fun readByte(): Int {
            advance()
            check(ci < chunks.size) { "数据越界" }
            return chunks[ci][pos++].toInt() and 0xFF
        }

        fun readU16(): Int = readByte() or (readByte() shl 8)

        fun readU32(): Int = readU16() or (readU16() shl 16)

        fun skip(n: Int) {
            var left = n
            while (left > 0) {
                advance()
                check(ci < chunks.size) { "数据越界" }
                val take = minOf(left, chunks[ci].size - pos)
                pos += take
                left -= take
            }
        }

        /**
         * 读取 BIFF8 字符串字符数据。跨 CONTINUE 块时新块以 1 字节压缩标志开头
         * （bit0：1=UTF-16LE，0=单字节），可能中途切换。
         */
        fun readChars(cch: Int, highByte: Boolean): String {
            val sb = StringBuilder(cch.coerceIn(0, 4096))
            var remain = cch
            var high = highByte
            var firstChunk = true
            var guard = 0
            while (remain > 0 && guard++ < cch + 8) {
                advance()
                if (ci >= chunks.size) break
                if (!firstChunk) high = (readByte() and 1) == 1
                firstChunk = false
                val bytesPer = if (high) 2 else 1
                val avail = chunks[ci].size - pos
                val take = minOf(remain, avail / bytesPer)
                if (take == 0) {
                    pos += 1   // 块尾散字节，跳过防死循环
                    continue
                }
                if (high) {
                    repeat(take) {
                        sb.append(((chunks[ci][pos].toInt() and 0xFF) or ((chunks[ci][pos + 1].toInt() and 0xFF) shl 8)).toChar())
                        pos += 2
                    }
                } else {
                    repeat(take) {
                        sb.append((chunks[ci][pos].toInt() and 0xFF).toChar())
                        pos++
                    }
                }
                remain -= take
            }
            return sb.toString()
        }
    }

    /** BIFF8 字符串：cch(2) + flags(1) + [富文本/扩展长度] + 字符。 */
    private fun readUnicodeString(rd: ChunkReader): String {
        val cch = rd.readU16()
        val flags = rd.readByte()
        val rich = flags and 0x08 != 0
        val ext = flags and 0x04 != 0
        val cRun = if (rich) rd.readU16() else 0
        val cbExt = if (ext) rd.readU32() else 0
        if (cRun > 0) rd.skip(cRun * 4)
        if (cbExt > 0) rd.skip(cbExt)
        return rd.readChars(cch, flags and 0x01 != 0)
    }

    /** 读取 SST（0x00FC）及其 CONTINUE（0x003C）链上的共享字符串表。 */
    private fun readSst(recs: List<Rec>): List<String> {
        val idx = recs.indexOfFirst { it.id == 0x00FC }
        if (idx < 0) return emptyList()
        val chunks = ArrayList<ByteArray>()
        chunks.add(recs[idx].data)
        for (i in idx + 1 until recs.size) {
            if (recs[i].id != 0x003C) break
            chunks.add(recs[i].data)
        }
        val rd = ChunkReader(chunks)
        if (!rd.hasMore) return emptyList()
        rd.skip(8)  // cstTotal + cstUnique
        val out = ArrayList<String>()
        while (rd.hasMore) {
            val s = readUnicodeString(rd)
            out.add(s)
            if (out.size > 1_000_000) break
        }
        return out
    }

    private fun rkToDouble(rk: Int): Double {
        val div100 = rk and 1 == 1
        val asInt = rk and 2 != 0
        val v: Double = if (asInt) {
            (rk shr 2).toDouble()
        } else {
            // S=0：低 30 位是 IEEE double 高 32 位（先清掉低 2 个标志位）
            Double.fromBits((rk.toLong() and 0xFFFF_FFFCL) shl 32)
        }
        return if (div100) v / 100.0 else v
    }

    private fun doubleAt(d: ByteArray, off: Int): Double {
        var bits = 0L
        for (i in 7 downTo 0) bits = (bits shl 8) or (d[off + i].toLong() and 0xFF)
        return Double.fromBits(bits)
    }

    private fun numToStr(v: Double): String =
        if (v == kotlin.math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) {
            v.toLong().toString()
        } else v.toString()

    /** 遍历 Workbook 流：第一个子流为全局（SST/BOUNDSHEET），其后第一个子流即工作表。 */
    private fun readGrid(data: ByteArray): Map<Long, String> {
        val ole = Ole2(data)
        val stream = ole.openStream(setOf("workbook", "book"))
            ?: throw IllegalArgumentException("Excel 中未找到工作簿数据（Book/Workbook 流）")
        val recs = readRecords(stream)
        val strings = readSst(recs)
        val grid = HashMap<Long, String>()

        fun putStr(row: Int, col: Int, s: String) {
            if (s.isNotEmpty()) grid[key(row, col)] = s
        }
        fun putNum(row: Int, col: Int, v: Double) = putStr(row, col, numToStr(v))

        var subDepth = 0
        var globalsDone = false
        var sheetActive = false
        var pendingFormulaString: Pair<Int, Int>? = null
        for (r in recs) {
            when (r.id) {
                0x0809 -> {   // BOF：全局子流结束后出现的第一个子流即工作表
                    if (globalsDone) sheetActive = true
                    subDepth++
                    continue
                }
                0x000A -> {   // EOF：工作表子流结束后即完成
                    subDepth--
                    if (sheetActive) break
                    if (subDepth == 0) globalsDone = true
                    continue
                }
            }
            if (!sheetActive) continue
            val d = r.data
            when (r.id) {
                0x00FD -> if (d.size >= 10) {   // LABELSST
                    val isst = u32(d, 6)
                    strings.getOrNull(isst)?.let { putStr(u16(d, 0), u16(d, 2), it) }
                }
                0x0204 -> if (d.size >= 7) {    // LABEL（内联字符串）
                    val rd2 = ChunkReader(listOf(d))
                    rd2.skip(6)
                    putStr(u16(d, 0), u16(d, 2), readUnicodeString(rd2))
                }
                0x0203 -> if (d.size >= 14) putNum(u16(d, 0), u16(d, 2), doubleAt(d, 6))
                0x027E -> if (d.size >= 10) putNum(u16(d, 0), u16(d, 2), rkToDouble(u32(d, 6)))
                0x00BD -> {   // MULRK
                    if (d.size >= 8) {
                        val row = u16(d, 0)
                        val colFirst = u16(d, 2)
                        val n = (d.size - 6) / 6
                        for (i in 0 until n) {
                            putNum(row, colFirst + i, rkToDouble(u32(d, 6 + i * 6)))
                        }
                    }
                }
                0x0006 -> {   // FORMULA：字符串结果由后续 STRING 记录给出
                    pendingFormulaString = null
                    if (d.size >= 14 &&
                        d[12] == 0xFF.toByte() && d[13] == 0xFF.toByte() &&
                        d[6].toInt() == 0
                    ) {
                        pendingFormulaString = u16(d, 0) to u16(d, 2)
                    } else if (d.size >= 14) {
                        putNum(u16(d, 0), u16(d, 2), doubleAt(d, 6))
                    }
                }
                0x0207 -> if (pendingFormulaString != null) {   // STRING
                    val rd2 = ChunkReader(listOf(d))
                    putStr(
                        pendingFormulaString!!.first, pendingFormulaString!!.second,
                        readUnicodeString(rd2),
                    )
                    pendingFormulaString = null
                }
            }
        }
        return grid
    }
}
