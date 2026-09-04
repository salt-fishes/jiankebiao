package com.saltfish.simple.ui.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saltfish.simple.data.EntryWithCourse
import com.saltfish.simple.ui.theme.Haptics

private val DAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 单个对比来源的占用：key = day * 100 + section。 */
private typealias Occupied = Set<Long>

private fun dbOccupied(entries: List<EntryWithCourse>, week: Int): Set<Long> =
    entries.filter { e -> e.weeksCsv.split(',').mapNotNull { it.trim().toIntOrNull() }.contains(week) }
        .flatMap { e ->
            val d = e.dayOfWeek * 100L
            ((d + (e.startSection ?: 1))..(d + (e.endSection ?: e.startSection ?: 1))).asSequence()
        }.toSet()

private fun photoOccupied(t: CompareTimetable): Set<Long> =
    t.blocks.flatMap { b ->
        val d = b.day * 100L
        ((d + b.startSection)..(d + b.endSection)).asSequence()
    }.toSet()

private fun coveredDays(t: CompareTimetable): IntRange = 1..t.dayCount

/**
 * 课表对比（实验性）：勾选若干数据库课表（按周）与图片对比课表（静态占用），
 * 合并出共同空闲时间。纯本地计算。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(
    timetables: List<com.saltfish.simple.ui.timetable.TimetableInfo>,
    compareTimetables: List<CompareTimetable>,
    glass: Boolean,
    sectionsPerDay: Int,
    defaultWeek: Int,
    loadEntries: suspend (Long) -> List<EntryWithCourse>,
    onPickImage: () -> Unit,
    onDeleteCompare: (Long) -> Unit,
    onBack: () -> Unit,
) {
    var selected by rememberSaveable {
        mutableStateOf(compareTimetables.map { "photo:${it.id}" }.toSet())
    }
    var week by rememberSaveable { mutableStateOf(defaultWeek) }
    // 每个来源的占用集合（含覆盖天数），key = sourceKey
    var dbOccupancy by remember { mutableStateOf(mapOf<Long, Occupied>()) }
    var loading by remember { mutableStateOf(false) }

    val selectedDb = timetables.filter { "db:${it.timetable.id}" in selected }

    LaunchedEffect(selected, week) {
        loading = true
        val result = mutableMapOf<Long, Occupied>()
        selectedDb.forEach { info ->
            result[info.timetable.id] = runCatching { dbOccupied(loadEntries(info.timetable.id), week) }
                .getOrDefault(emptySet())
        }
        dbOccupancy = result
        loading = false
    }

    Scaffold(
        containerColor = if (glass) Color.Transparent else MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("课表对比（实验性）") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = onPickImage) { Text("从图片添加") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (glass) Color.Transparent else MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                "勾选要对比的课表（数据库课表按周计入；图片课表按截图占用计入）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )

            // ---- 数据库课表 ----
            timetables.forEach { info ->
                val key = "db:${info.timetable.id}"
                SourceRow(
                    title = info.timetable.name.ifBlank { "未命名" },
                    subtitle = "数据库课表",
                    checked = key in selected,
                    onToggle = { selected = if (key in selected) selected - key else selected + key },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // ---- 图片对比课表 ----
            compareTimetables.forEach { t ->
                val key = "photo:${t.id}"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = key in selected,
                        onCheckedChange = {
                            selected = if (key in selected) selected - key else selected + key
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(t.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "图片识别 · ${t.blocks.size} 个占用块",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "删除",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clickable { onDeleteCompare(t.id) }
                            .padding(8.dp),
                    )
                }
            }
            if (timetables.isEmpty() && compareTimetables.isEmpty()) {
                Text(
                    "还没有可对比的课表：先导入课表，或点右上角「从图片添加」识别朋友的课表截图。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            // ---- 周选择（仅影响数据库课表） ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                Text("对比周：", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(6.dp))
                (1..maxOf(20, defaultWeek)).forEach { w ->
                    FilterChip(
                        selected = week == w,
                        onClick = { week = w },
                        label = { Text("$w") },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- 合并网格 ----
            if (selected.isNotEmpty()) {
                Text(
                    if (loading) "计算中…" else "共同空闲（以第 $week 周为例）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))

                val sectionRows = sectionsPerDay
                // 统一计算合并状态：cells[day][row]
                val selectedPhotoTables = selected.filter { it.startsWith("photo:") }
                    .mapNotNull { key -> compareTimetables.find { "photo:${it.id}" == key } }
                val cells: List<List<CellState>> = (1..7).map { day ->
                    (1..sectionRows).map { section ->
                        mergeCell(
                            day, section,
                            selectedDb.map { dbOccupancy[it.timetable.id] ?: emptySet() },
                            selectedPhotoTables,
                        )
                    }
                }
                Row {
                    Column(Modifier.width(24.dp)) {
                        Spacer(Modifier.height(20.dp))
                        repeat(sectionRows) { row ->
                            Box(Modifier.height(34.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    "${row + 1}",
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Column {
                        Row {
                            repeat(7) { d ->
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    Text(
                                        DAY_LABELS[d],
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        repeat(sectionRows) { row ->
                            Row {
                                repeat(7) { day ->
                                    val cell = cells[day][row]
                                    Box(
                                        Modifier
                                            .padding(1.dp)
                                            .weight(1f)
                                            .height(34.dp)
                                            .background(
                                                when {
                                                    cell.occupiedBy > 0 -> MaterialTheme.colorScheme.secondaryContainer
                                                    cell.covered -> MaterialTheme.colorScheme.primaryContainer
                                                    else -> MaterialTheme.colorScheme.surfaceContainerLow
                                                },
                                                RoundedCornerShape(4.dp),
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            when {
                                                cell.occupiedBy > 0 -> "${cell.occupiedBy}"
                                                cell.covered -> "闲"
                                                else -> "?"
                                            },
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "填色 = 有人有课（数字为人数）；「闲」 = 所选课表都空闲；「?」 = 图片课表未覆盖该天",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                // ---- 最长共同空闲汇总 ----
                val best = longestFreeSpans(cells)
                if (best.isNotEmpty()) {
                    Text(
                        "最长共同空闲：" + best.joinToString("；") { (day, len) ->
                            "${DAY_LABELS[day - 1]} 连续 $len 节"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private data class CellState(val occupiedBy: Int, val covered: Boolean)

@Composable
private fun SourceRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun mergeCell(
    day: Int,
    section: Int,
    dbSets: List<Occupied>,
    photoTables: List<CompareTimetable>,
): CellState {
    val key = day * 100L + section
    val occupiedBy = dbSets.count { key in it } + photoTables.count { t ->
        day <= t.dayCount && key in photoOccupied(t)
    }
    val covered = photoTables.all { day in coveredDays(it) }
    return CellState(occupiedBy, covered)
}

/** 每天的最长连续共同空闲段（≥2 节才列出，按长度降序，最多 3 条）。 */
private fun longestFreeSpans(cells: List<List<CellState>>): List<Pair<Int, Int>> {
    val spans = mutableListOf<Pair<Int, Int>>()
    cells.forEachIndexed { dayIdx, column ->
        var run = 0
        column.forEach { cell ->
            if (cell.covered && cell.occupiedBy == 0) run++ else {
                if (run >= 2) spans += (dayIdx + 1) to run
                run = 0
            }
        }
        if (run >= 2) spans += (dayIdx + 1) to run
    }
    return spans.sortedByDescending { it.second }.take(3)
}
