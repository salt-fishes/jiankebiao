package com.saltfish.simple.schedule

import com.saltfish.simple.schedule.ParseRulePack.Companion.IconGrid
import com.saltfish.simple.schedule.ParseRulePack.Companion.Qz
import com.saltfish.simple.schedule.ParseRulePack.Companion.Zfsoft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ScheduleParser 规则包解释验证：单元格文本取自真实 OCR 输出（姓名等已替换为占位），
 * 三种内置包（正方键值式 / 图标行式 / 强智位置式）各覆盖正例与误判防线。
 */
class ScheduleParserTest {

    // ---- 正方键值式（PDF OCR 实测形态：跨行断裂、字段碎片） ----

    private val zfCell = listOf(
        "模拟电子线路★",
        "(1-2节)1-3周,5-15周/校区:下 沙/楼号:环宇楼/场地:环宇 楼A404/教师:张三/教学班 :(2026-2027-1)-XX12345- 67/教学班组成:示例班1;示例班2/选课",
    ).joinToString("\n")

    @Test
    fun `正方键值式完整字段`() {
        val c = ScheduleParser.parseCell(zfCell, Zfsoft).single()
        assertEquals("模拟电子线路", c.name)
        assertEquals("讲课", c.type)
        assertEquals("张三", c.teacher)
        assertEquals("下沙", c.campus)
        assertEquals("环宇楼", c.building)
        assertEquals("环宇楼A404", c.room)
        assertEquals("(2026-2027-1)-XX12345-67", c.classNo)
        assertEquals(listOf(1, 2), c.sections)
        assertEquals((1..3).toList() + (5..15).toList(), c.weeks)
    }

    @Test
    fun `学分碎片不另起课程块而是并入上一块`() {
        // OCR 实测：属性区换行把 "学分:3.0" 断成独立行（曾因行尾 0 无保护变成垃圾课程）
        val text = zfCell + "\n:/学分:3.0"
        val courses = ScheduleParser.parseCell(text, Zfsoft)
        assertEquals(1, courses.size)
        assertEquals("3.0", courses.single().credit)
    }

    @Test
    fun `冒号被OCR读成空格或丢失时仍能取到字段`() {
        // 块内拼接会去掉空格："教师 李四" → "教师李四"（无分隔符）
        val text = "示例物理方法★\n(1-2节)1-3周/校区:下沙/教师 李四/学分:2.0"
        val c = ScheduleParser.parseCell(text, Zfsoft).single()
        assertEquals("李四", c.teacher)
        assertEquals("下沙", c.campus)
        assertEquals("2.0", c.credit)
    }

    @Test
    fun `数值结尾的碎片行不产生幽灵课程`() {
        // 打印时间行被 OCR 成 "J丁F时时问.2020"（无冒号可挡），多位数结尾应整体拒绝
        val courses = ScheduleParser.parseCell("大学物理A2★\n(6-7节)1-3周\nJ丁F时时问.2020", Zfsoft)
        assertEquals(1, courses.size)
        assertEquals("大学物理A2", courses.single().name)
    }

    @Test
    fun `实验标记的 OCR 变体仍可识别`() {
        // ○ → 字母 O / 数字 0（前一位非数字）
        for (tail in listOf("AO", "A0", "A〇")) {
            val c = ScheduleParser.parseCell("物理实验$tail", Zfsoft).single()
            assertEquals("物理实验A", c.name)
            assertEquals("实验", c.type)
        }
    }

    // ---- 强智位置式 ----

    @Test
    fun `强智位置式字段回填`() {
        val text = "大学物理(理论)\n【1-6,10-18周】\n张三\nN6-403"
        val c = ScheduleParser.parseCell(text, Qz).single()
        assertEquals("大学物理", c.name)
        assertEquals("讲课", c.type)
        assertEquals("张三", c.teacher)
        assertEquals("N6-403", c.room)
        assertEquals((1..6).toList() + (10..18).toList(), c.weeks)
    }

    @Test
    fun `强智教师与周次同行`() {
        // OCR 实测：教师名紧邻【周次】（"王五【2-6周】"）
        val c = ScheduleParser.parseCell("中国近现代史纲要(理论)\n王五【2-6周】\nN4-JT02", Qz).single()
        assertEquals("中国近现代史纲要", c.name)
        assertEquals("王五", c.teacher)
        assertEquals("N4-JT02", c.room)
        assertEquals((2..6).toList(), c.weeks)
    }

    @Test
    fun `强智教室取教学班说明前的首个匹配`() {
        // 尾部教学班碎片（示例班2076）不再是教室候选
        val c = ScheduleParser.parseCell(
            "劳动实践(实践)\n赵六【10-13周】\nN4-室外1\n劳动实践示例班2511,机\n制2076",
            Qz,
        ).single()
        assertEquals("N4-室外1", c.room)
        assertEquals("赵六", c.teacher)
    }

    // ---- 图标行式（网页/截图） ----

    @Test
    fun `图标行式前缀路由`() {
        val text = listOf(
            "数学分析★",
            "◎ (1-2节)1-4周,6-11周",
            "📍 新校区 图信楼B305",
            "👤 张三(高等学校教师/讲师)",
            "🏠 (2026-2027-1)-XX12345-67",
        ).joinToString("\n")
        val c = ScheduleParser.parseCell(text, IconGrid).single()
        assertEquals("数学分析", c.name)
        assertEquals("讲课", c.type)
        assertEquals(listOf(1, 2), c.sections)
        assertEquals((1..4).toList() + (6..11).toList(), c.weeks)
        assertEquals("新校区", c.campus)
        assertEquals("图信楼B305", c.room)
        assertEquals("张三", c.teacher)
        assertEquals("(2026-2027-1)-XX12345-67", c.classNo)
    }

    @Test
    fun `网页截图图标被OCR吞掉时按行形状恢复`() {
        // OCR 实测：📍 整个丢失、👤 变 "1"、🏠 丢失、🕐 变 "©"、括号全角半角混排
        val text = listOf(
            "机械工程材料★",
            "©（3-4节)1-5周,7-11周",
            "新校区机电楼A201(新校区)",
            "1钱七(高等学校教师/副教授)",
            "(2026-2027-1)-0Y351035-06",
        ).joinToString("\n")
        val c = ScheduleParser.parseCell(text, IconGrid).single()
        assertEquals("机械工程材料", c.name)
        assertEquals(listOf(3, 4), c.sections)
        assertEquals((1..5).toList() + (7..11).toList(), c.weeks)
        assertEquals("钱七", c.teacher)
        assertEquals("新校区机电楼A201", c.room)
        assertEquals("(2026-2027-1)-0Y351035-06", c.classNo)
    }

    // ---- 包错配防线（用户看到「0 门课程」的典型成因） ----

    @Test
    fun `规则包错配时不产生课程`() {
        // 正方文本用强智包解析：类型标记 (理论) 不存在 → 无课程块 → 0 门课
        assertTrue(ScheduleParser.parseCell(zfCell, Qz).isEmpty())
        // 强智文本用正方包解析：行尾无 ★○●◇ 标记 → 无课程块 → 0 门课
        assertTrue(ScheduleParser.parseCell("大学物理(理论)\n【1-6,10-18周】\n张三\nN6-403", Zfsoft).isEmpty())
    }

    // ---- v2 架构：v1 糖编译等价性 / v2 原生导入 / 命中轨迹 ----

    private val iconCellSwallowed = listOf(
        "机械工程材料★",
        "©（3-4节)1-5周,7-11周",
        "新校区机电楼A201(新校区)",
        "1钱七(高等学校教师/副教授)",
        "(2026-2027-1)-0Y351035-06",
    ).joinToString("\n")

    @Test
    fun `v1 包糖编译与内置包行为一致`() {
        val v1Json = """
            {"schemaVersion":1,"id":"v1test-zf","name":"v1正方测试","version":1,
             "match":{"anyOf":["校区:","时间段"],"minHits":2},
             "table":{"headerAnchor":"时间段","dayPattern":"星期","dayCount":7,
                      "labelWords":["上午","下午"],"legendWords":["打印时间"]},
             "blockStart":{"typeMarks":{"★":"讲课","○":"实验","●":"上机","◇":"实践",":":"集中实践"},
                           "markAliases":{"〇":"○","О":"○","O":"○","0":"○"},
                           "trailingZeroFallback":true},
             "fields":{"keyMap":{"教学班组成":"composition","教学班":"classNo","校区":"campus",
                                 "楼号":"building","场地":"room","教师":"teacher","学分":"credit"},
                       "fieldSeparators":":：·・ ","fieldEndChars":"/","linePrefixes":{},
                       "sectionsPattern":"\\((\\d+)-(\\d+)节\\)",
                       "weeksPattern":"(\\d+\\s*-\\s*\\d+|\\d+)\\s*周",
                       "paritySingle":"单","parityDouble":"双",
                       "positionalTeacher":false,"positionalRoom":false}}
        """.trimIndent()
        val imported = ParseRulePack.fromJson(v1Json)
        assertEquals(
            ScheduleParser.parseCell(zfCell, Zfsoft).single(),
            ScheduleParser.parseCell(zfCell, imported).single(),
        )
        // OCR 变体（数字别名 + 防误判守卫）同样生效
        val c = ScheduleParser.parseCell("物理实验A0", imported).single()
        assertEquals("物理实验A", c.name)
        assertEquals("实验", c.type)
    }

    @Test
    fun `v1 图标包糖编译的行形状兜底与内置一致`() {
        val v1Json = """
            {"schemaVersion":1,"id":"v1test-icon","name":"v1图标测试","version":1,
             "match":{"anyOf":["高等学校教师"],"minHits":1},
             "table":{"dayPattern":"星期","dayCount":7},
             "blockStart":{"typeMarks":{"★":"讲课","●":"上机","○":"实验","◇":"实践"},
                           "markAliases":{"〇":"○"},"trailingZeroFallback":true},
             "fields":{"keyMap":{},"fieldSeparators":":：·・","fieldEndChars":"/",
                       "linePrefixes":{"◎":"sections","📍":"room","👤":"teacher","🏠":"classNo","@":"room"},
                       "positionalTeacher":true,"positionalRoom":false}}
        """.trimIndent()
        val imported = ParseRulePack.fromJson(v1Json)
        assertEquals(
            ScheduleParser.parseCell(iconCellSwallowed, IconGrid).single(),
            ScheduleParser.parseCell(iconCellSwallowed, imported).single(),
        )
    }

    @Test
    fun `v2 原生规则包导入并正确解析`() {
        val v2Json = """
            {"schemaVersion":2,"id":"v2test","name":"v2测试包","version":1,
             "match":{"anyOf":["【"],"minHits":1},
             "table":{"dayPattern":"星期","dayCount":7},
             "blockStart":{"courseStart":[
               {"pattern":"^(.+)\\((理论|理论课|实践|实验)\\)$","nameGroup":1,"typeGroup":2,
                "typeMap":{"理论":"讲课","理论课":"讲课","实践":"实践","实验":"实验"},"name":"课名(类型)后缀"}]},
             "fields":{"fieldRules":[
               {"field":"teacher","pattern":"([一-龥]{2,4})(?=【)","line":"first","cleanTeacher":true,"name":"【周次】同行教师"},
               {"field":"teacher","pattern":"^(?!.*教室$)([一-龥]{2,6})$","line":"last","cleanTeacher":true,"name":"末行短中文兜底"},
               {"field":"room","pattern":"^(?=.*\\d)(?=.*[A-Za-z一-龥])([A-Za-z一-龥][A-Za-z0-9\\-一-龥]{1,11})$",
                "line":"first","stripTrailingParen":true,"splitCampus":true,"excludeChars":"节周","name":"教室行形状"}]}}
        """.trimIndent()
        val pack = ParseRulePack.fromJson(v2Json)
        val c = ScheduleParser.parseCell("大学物理(理论)\n张三【2-6周】\nN6-403", pack).single()
        assertEquals("大学物理", c.name)
        assertEquals("讲课", c.type)
        assertEquals("张三", c.teacher)
        assertEquals("N6-403", c.room)
        assertEquals((2..6).toList(), c.weeks)
    }

    @Test
    fun `v2 包拒绝 v1 执行器开关`() {
        val badJson = """
            {"schemaVersion":2,"id":"v2bad","name":"v2非法包","version":1,
             "match":{"anyOf":["【"],"minHits":1},
             "table":{"dayPattern":"星期","dayCount":7},
             "blockStart":{"typeMarks":{"★":"讲课"}},"fields":{}}
        """.trimIndent()
        try {
            ParseRulePack.fromJson(badJson)
            fail("v2 包不应接受 typeMarks")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("courseStart"))
        }
    }

    @Test
    fun `命中轨迹记录未入块行与规则来源`() {
        val (_, trace) = ScheduleParser.parseCellTraced("大学物理(理论)\n张三\nN6-403", Zfsoft)
        assertEquals(3, trace.unmatchedLines.size)
        assertTrue(trace.blocks.isEmpty())

        val (_, ok) = ScheduleParser.parseCellTraced(iconCellSwallowed, IconGrid)
        val block = ok.blocks.single()
        assertEquals("机械工程材料", block.name)
        assertTrue(block.fills.any { it.startsWith("teacher=钱七") && it.contains("职称括注行") })
        assertTrue(block.fills.any { it.startsWith("room=新校区机电楼A201") && it.contains("教室行形状") })
        assertTrue(ok.unmatchedLines.isEmpty())
    }

    // ---- 真实样本回归（占位名）：正方网页 / 强智 PDF 导入差异修复 ----

    @Test
    fun `网页单双周尾注随周次保留`() {
        val text = listOf(
            "材料力学A★",
            "(9-10节)2周,6-10周(双)",
            "新校区 机电楼A101(新校区)",
            "张三(高等学校教师/副教授)",
            "(2026-2027-1)-UY351038-05",
        ).joinToString("\n")
        val c = ScheduleParser.parseCell(text, IconGrid).single()
        assertEquals(listOf(2, 6, 8, 10), c.weeks)
        assertEquals("新校区", c.campus)
        assertEquals("机电楼A101", c.room)
        assertEquals("张三", c.teacher)
    }

    @Test
    fun `网页第N周单周写法与嵌套职称括注`() {
        val text = listOf(
            "大学物理实验AO",
            "(5-6节)第3周",
            "新校区 图信楼A120(光学)",
            "👤 张三(高等学校教师/未详职称(高校教师))",
            "(2026-2027-1)-IX352001-47",
        ).joinToString("\n")
        val c = ScheduleParser.parseCell(text, IconGrid).single()
        assertEquals("大学物理实验A", c.name)
        assertEquals(listOf(3), c.weeks)
        assertEquals("张三", c.teacher)
        assertEquals("新校区", c.campus)
        assertEquals("图信楼A120", c.room)
    }

    @Test
    fun `强智多段周次与多教师`() {
        val c = ScheduleParser.parseCell(
            "概率论与数理统计A(理论)\n张三,李四,王五【2-4,6,11-13,15-18周】\nN6-JT01\n概率论与数理统计A机制2511,机制2512022",
            Qz,
        ).single()
        assertEquals("概率论与数理统计A", c.name)
        assertEquals("张三,李四,王五", c.teacher)
        assertEquals(listOf(2, 3, 4, 6, 11, 12, 13, 15, 16, 17, 18), c.weeks)
        assertEquals("N6-JT01", c.room)
    }

    @Test
    fun `强智逗号单周列表与点分隔周次`() {
        val a = ScheduleParser.parseCell("形势与政策(三)(理论)\n李四【10,14周】\nN4-JT02", Qz).single()
        assertEquals(listOf(10, 14), a.weeks)
        assertEquals("李四", a.teacher)

        val b = ScheduleParser.parseCell(
            "大学物理实验(二)(实验)\n张三,李四,王五【3.7-10.12-14周】\nN2-411",
            Qz,
        ).single()
        assertEquals("张三,李四,王五", b.teacher)
        assertEquals(listOf(3, 7, 8, 9, 10, 12, 13, 14), b.weeks)

        val d = ScheduleParser.parseCell("大学物理B2(理论)\n王五【1-4.6周】\nN4-502", Qz).single()
        assertEquals(listOf(1, 2, 3, 4, 6), d.weeks)
    }

    @Test
    fun `网页同格双课拆分与教室校区拆分`() {
        val text = listOf(
            "马克思主义基本原理★",
            "(1-2节)1-4周,6-11周",
            "新校区 图信楼B305",
            "张三(无)",
            "(2026-2027-1)-IX351003-39",
            "形势与政策III★",
            "(1-2节)12-13周",
            "新校区 图信楼B305",
            "张三(无)",
            "(2026-2027-1)-IX351009-64",
        ).joinToString("\n")
        val courses = ScheduleParser.parseCell(text, IconGrid)
        assertEquals(2, courses.size)
        assertEquals("马克思主义基本原理", courses[0].name)
        assertEquals(listOf(1, 2), courses[0].sections)
        assertEquals((1..4).toList() + (6..11).toList(), courses[0].weeks)
        assertEquals("张三", courses[0].teacher)
        assertEquals("新校区", courses[0].campus)
        assertEquals("图信楼B305", courses[0].room)
        assertEquals("形势与政策III", courses[1].name)
        assertEquals(listOf(12, 13), courses[1].weeks)
    }

    @Test
    fun `强智长室外场地不被形状上限截断`() {
        val c = ScheduleParser.parseCell(
            "大学体育3(实践)\n张三【1-5,7-17周】\nN田1区足球-机械工程学院",
            Qz,
        ).single()
        assertEquals("N田1区足球-机械工程学院", c.room)
        assertEquals("张三", c.teacher)
    }

    @Test
    fun `强智右括号丢失的标题仍能开新块`() {
        // OCR 实测：一列两课上下相邻，第二课标题 "大学体育3(实践" 被吞右括号
        val courses = ScheduleParser.parseCell(
            listOf(
                "大学物理B2(理论)",
                "张三【1-5,10-17周】",
                "N4-502",
                "大学体育3(实践",
                "李四【1-5,7-17周】",
                "N田1区足球-机械工程学院",
                "备注实践环节：工程实训A【王五（7-9)】",
            ).joinToString("\n"),
            Qz,
        )
        assertEquals(2, courses.size)
        assertEquals("大学物理B2", courses[0].name)
        assertEquals((1..5).toList() + (10..17).toList(), courses[0].weeks)
        assertEquals("N4-502", courses[0].room)
        assertEquals("大学体育3", courses[1].name)
        assertEquals("实践", courses[1].type)
        assertEquals((1..5).toList() + (7..17).toList(), courses[1].weeks)
        assertEquals("N田1区足球-机械工程学院", courses[1].room)
        assertEquals("李四", courses[1].teacher)
    }

    @Test
    fun `强智标题与教师粘连成行时仍能开新块`() {
        // OCR 实测："电工电子技术基础B (实验) 李四【16-17周】" 标题与教师同行
        val courses = ScheduleParser.parseCell(
            listOf(
                "大学英语Il(理论) 张三【1-2周】 N6-407",
                "大学英语Ⅲ(通识教育必修课)-理论012",
                "电工电子技术基础B (实验) 李四【16-17周】",
                "N2-411",
                "电工电子技术基础B(学科基础课)-实验019",
            ).joinToString("\n"),
            Qz,
        )
        assertEquals(2, courses.size)
        assertEquals("大学英语Il", courses[0].name)
        assertEquals(listOf(1, 2), courses[0].weeks)
        assertEquals("电工电子技术基础B", courses[1].name)
        assertEquals("实验", courses[1].type)
        assertEquals("李四", courses[1].teacher)
        assertEquals(listOf(16, 17), courses[1].weeks)
        assertEquals("N2-411", courses[1].room)
    }

    @Test
    fun `强智课名与类型标记被OCR拆成两行时拼回`() {
        // OCR 实测（周二列）："电工电子技术基础B" 与 "(理论) 陈科鹏 【1-6,10-16周】" 分属两个盒子
        val courses = ScheduleParser.parseCell(
            listOf(
                "大学物理B2(理论)",
                "张三【1-5,10-15周】",
                "N4-502",
                "大学物理B2(学科基础课)-理论035",
                "电工电子技术基础B",
                "(理论) 李四 【1-6,10-16周】",
                "N8-B305",
            ).joinToString("\n"),
            Qz,
        )
        assertEquals(2, courses.size)
        assertEquals("大学物理B2", courses[0].name)
        assertEquals((1..5).toList() + (10..15).toList(), courses[0].weeks)
        assertEquals("电工电子技术基础B", courses[1].name)
        assertEquals("讲课", courses[1].type)
        assertEquals("李四", courses[1].teacher)
        assertEquals((1..6).toList() + (10..16).toList(), courses[1].weeks)
        assertEquals("N8-B305", courses[1].room)
    }

    // ---- 网页截图真实 OCR 形态回归（占位名，取自模拟器实拍截图的逐块 OCR） ----

    @Test
    fun `截图同格双课与垃圾前缀教师`() {
        // OCR 实测：👤 读成 "1"、同格两课上下叠放、马原的 classNo 带脏数字
        val courses = ScheduleParser.parseCell(
            listOf(
                "马克思主义基本原理★",
                "(5-6节)1-5周,7-11周",
                "新校区图信楼B305",
                "1张三(无)",
                "(2020-2027-1)-00561003–39",
                "形势与政策III★",
                "(5-6节)12-13周",
                "新校区图信楼B305",
                "1张三(无)",
                "(2026-2027-1)-0x561009-64",
            ).joinToString("\n"),
            IconGrid,
        )
        assertEquals(2, courses.size)
        assertEquals("马克思主义基本原理", courses[0].name)
        assertEquals((1..5).toList() + (7..11).toList(), courses[0].weeks)
        assertEquals("张三", courses[0].teacher)
        assertEquals(listOf(5, 6), courses[0].sections)
        assertEquals("形势与政策III", courses[1].name)
        assertEquals(listOf(12, 13), courses[1].weeks)
    }

    @Test
    fun `截图双周尾注与O别名与嵌套括注`() {
        // OCR 实测："(9-10节)2周,6-10周(双)" / "大学物理实验AO" / 职称括注被断行
        val a = ScheduleParser.parseCell(
            listOf(
                "材料力学A★",
                "(9-10节)2周,6-10周(双)",
                "新校区机电模A101(新校区",
                "张三(高等学校教师/副教",
                "(2026-2027-1)-UY351035-0",
            ).joinToString("\n"),
            IconGrid,
        ).single()
        assertEquals(listOf(2, 6, 8, 10), a.weeks)
        assertEquals("张三", a.teacher)
        assertEquals(listOf(9, 10), a.sections)

        val b = ScheduleParser.parseCell(
            listOf(
                "大学物理实验AO",
                "(5-6节)24周,6-18周",
                "新校区图信接A120(光学)",
                "1李四(高等学校教师/末评职",
                "(高校教师）)",
                "(2028-2027-1)-x352001-47",
            ).joinToString("\n"),
            IconGrid,
        ).single()
        assertEquals("大学物理实验A", b.name)
        assertEquals("实验", b.type)
        assertEquals("李四", b.teacher)
        assertEquals("新校区图信接A120", b.room)
    }

    @Test
    fun `标题断行的符号碎片拼回而非产生垃圾课程`() {
        // OCR 实测：标题 "体育与健康III（跆拳 / I)★" 被断行——
        // 拼回后成为真实课程（名称截断可手改），"I)★" 单独不得成为课程
        val courses = ScheduleParser.parseCell(
            "体育与健康III（跆拳\nI)★\n(5-6节)第6周\nQ新校区未排地点\n1李庆兵(高等学校教师/讲\n(2026-2027-1)-U886204",
            IconGrid,
        )
        assertEquals(1, courses.size)
        assertEquals("体育与健康III（跆拳I)", courses[0].name)
        assertEquals("讲课", courses[0].type)
        assertEquals(listOf(5, 6), courses[0].sections)
        assertEquals(listOf(6), courses[0].weeks)
        assertEquals("李庆兵", courses[0].teacher)
        // 纯符号碎片单独出现仍不得成为课程
        assertTrue(ScheduleParser.parseCell("I)★\n(5-6节)第6周", IconGrid).isEmpty())
    }

    @Test
    fun `卡片截图文本不得误配网页大图规则包`() {
        // 卡片竖屏截图走「课表对比」占用识别；若误选 icon-grid 导入会产生乱码课程
        val cardText = "高等数学A1★\n张三\n@下沙 翔宇楼103（智慧教室）\n日程 课表 学习 我的"
        assertTrue(!IconGrid.matches(cardText))
        val webText = "时间段 节次 星期一 高等学校教师 未排地点"
        assertTrue(IconGrid.matches(webText))
    }
}
