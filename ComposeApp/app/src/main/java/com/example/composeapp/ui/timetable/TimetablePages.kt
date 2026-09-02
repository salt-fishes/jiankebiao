package com.example.composeapp.ui.timetable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.composeapp.data.TimetableEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 课表列表项聚合信息（管理页/切换面板/小组件设置共用）。 */
data class TimetableInfo(
    val timetable: TimetableEntity,
    val courseCount: Int,
)

/** 导入目标：新建课表 / 覆盖现有 / 导入自动新建的课表（可顺便命名）。 */
sealed class ImportTarget {
    data class NewTimetable(val name: String, val startMillis: Long, val totalWeeks: Int) : ImportTarget()
    data class Existing(val timetableId: Long) : ImportTarget()
    data class IntoCreated(
        val timetableId: Long,
        val name: String,
        val startMillis: Long,
        val totalWeeks: Int,
    ) : ImportTarget()
}

private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

private fun fmtDate(millis: Long): String =
    if (millis == 0L) "未设置" else DATE_FMT.format(Date(millis))

private fun parseDate(text: String): Long? = runCatching {
    val d = java.time.LocalDate.parse(text.trim())
    d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
}.getOrNull()

/** 日期行：显示 + 点"修改"弹文本输入（ISO 格式，简单可靠）。 */
@Composable
private fun DateField(label: String, millis: Long, onChange: (Long) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(fmtDate(millis), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = {
            text = fmtDate(millis)
            editing = true
        }) { Text("修改") }
    }
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(label) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("开学日（第 1 周周一，格式 2026-09-14）") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    parseDate(text)?.let(onChange)
                    editing = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } },
        )
    }
}

/** 节数 +/- 行。 */
@Composable
private fun WeeksRow(weeks: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("总周数", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { onChange((weeks - 1).coerceIn(8, 30)) }) { Text("−") }
        Text("$weeks", style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = { onChange((weeks + 1).coerceIn(8, 30)) }) { Text("+") }
    }
}

/**
 * 新建课表对话框：先选来源（导入文件 / 从现有课表复制），课表名可现在填或留空
 * （留空时：导入流程用解析出的名字自动命名，复制流程用"未命名"，之后可改）。
 */
@Composable
fun NewTimetableDialog(
    timetables: List<TimetableInfo>,
    onConfirm: (name: String, copyFromId: Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var copyFrom by rememberSaveable { mutableStateOf(0L) }   // 0 = 导入文件

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建课表") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("课表名（可留空，稍后设置）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.padding(top = 12.dp))
                Text("课程来源", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.padding(top = 4.dp))
                // 横向滑动：课表多时右侧选项不会被挤出屏幕
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = copyFrom == 0L,
                        onClick = { copyFrom = 0L },
                        label = { Text("导入文件") },
                    )
                    timetables.forEach { info ->
                        FilterChip(
                            selected = copyFrom == info.timetable.id,
                            onClick = { copyFrom = info.timetable.id },
                            label = { Text("复制《${info.timetable.name}》", maxLines = 1) },
                        )
                    }
                }
                Spacer(Modifier.padding(top = 4.dp))
                Text(
                    if (copyFrom == 0L) "选择文件后自动解析导入，可在导入时命名"
                    else "复制该课表全部课程（周次重置为整学期），之后可修改",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim(), copyFrom.takeIf { it != 0L }) }) {
                Text(if (copyFrom == 0L) "选择文件" else "创建")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 导入选择弹窗（解析完成后）。
 * - 常规导入：默认「新建课表」（自动命名），可切换为覆盖现有课表；
 * - [presetTarget] 非 null（自动新建课表流程）：直接导入该课表，可顺便命名与调整日期。
 */
@Composable
fun ImportChooseDialog(
    parsedName: String,
    suggestedStartMillis: Long,
    suggestedTotalWeeks: Int,
    timetables: List<TimetableInfo>,
    defaultStartMillis: Long,
    defaultTotalWeeks: Int,
    onConfirm: (ImportTarget) -> Unit,
    onDismiss: () -> Unit,
    presetTarget: TimetableInfo? = null,
    presetName: String = "",   // 用户在新建弹窗里已输入的名字，优先于解析建议名
) {
    // 预置目标（自动新建流程）：名称默认用 用户输入 > 解析建议名 > 目标原名
    var name by rememberSaveable {
        mutableStateOf(
            presetName.ifBlank {
                parsedName.ifBlank { presetTarget?.timetable?.name ?: "新课表" }
            }
        )
    }
    var start by rememberSaveable {
        mutableStateOf(
            when {
                suggestedStartMillis != 0L -> suggestedStartMillis
                presetTarget != null && presetTarget.timetable.startMillis != 0L -> presetTarget.timetable.startMillis
                else -> defaultStartMillis
            }
        )
    }
    var weeks by rememberSaveable {
        mutableStateOf(
            when {
                suggestedTotalWeeks in 8..30 -> suggestedTotalWeeks
                presetTarget != null -> presetTarget.timetable.totalWeeks
                else -> defaultTotalWeeks
            }
        )
    }
    var newMode by rememberSaveable { mutableStateOf(presetTarget == null) }
    var overwriteId by rememberSaveable { mutableStateOf(0L) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (presetTarget != null) "导入到新课表" else "导入课表") },
        text = {
            Column {
                if (presetTarget == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = newMode,
                            onClick = { newMode = true },
                            label = { Text("新建课表") },
                        )
                        FilterChip(
                            selected = !newMode,
                            onClick = { newMode = false },
                            label = { Text("覆盖现有") },
                        )
                    }
                    Spacer(Modifier.padding(top = 12.dp))
                }
                if (presetTarget != null || newMode) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("课表名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.padding(top = 4.dp))
                    DateField("开学日", start) { start = it }
                    WeeksRow(weeks) { weeks = it }
                } else {
                    if (timetables.isEmpty()) {
                        Text("还没有现有课表", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        timetables.forEach { info ->
                            val selected = overwriteId == info.timetable.id
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { overwriteId = info.timetable.id }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = if (selected) MaterialTheme.colorScheme.primary
                                    else Color.Transparent,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${info.timetable.name}（${info.courseCount} 门课程）",
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                        Text(
                            "覆盖后该课表原有课程将被清除，不可恢复",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            val enabled = when {
                presetTarget != null -> name.isNotBlank()
                newMode -> name.isNotBlank()
                else -> overwriteId != 0L
            }
            TextButton(
                onClick = {
                    onConfirm(
                        when {
                            presetTarget != null -> ImportTarget.IntoCreated(
                                presetTarget.timetable.id, name.trim(), start, weeks,
                            )
                            newMode -> ImportTarget.NewTimetable(name.trim(), start, weeks)
                            else -> ImportTarget.Existing(overwriteId)
                        }
                    )
                },
                enabled = enabled,
            ) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 课表管理页：切换 / 重命名 / 复制 / 改开学日与周数 / 删除 / 新建。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableManagePage(
    timetables: List<TimetableInfo>,
    activeId: Long,
    glass: Boolean,
    onSwitch: (TimetableEntity) -> Unit,
    onUpdate: (TimetableEntity) -> Unit,
    onEditSchedule: (TimetableEntity) -> Unit,
    onCopy: (TimetableEntity) -> Unit,
    onDelete: (TimetableEntity) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
) {
    var editTarget by remember { mutableStateOf<TimetableEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<TimetableInfo?>(null) }
    val transparent = androidx.compose.ui.graphics.Color.Transparent

    Scaffold(
        containerColor = if (glass) transparent else MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("课表管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = onCreate) { Text("新建") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (glass) transparent else MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp, vertical = 8.dp,
            ),
        ) {
            items(timetables, key = { it.timetable.id }) { info ->
                val t = info.timetable
                val active = t.id == activeId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { if (!active) onSwitch(t) }
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                t.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            if (active) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "使用中",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Text(
                            buildString {
                                append("开学 ${fmtDate(t.startMillis)} · ${t.totalWeeks} 周 · ${info.courseCount} 门课程")
                                if (t.sectionTimesCsv.isNotBlank()) append(" · 自定义作息")
                                if (!active) append("（点击切换）")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onEditSchedule(t) }) { Text("作息") }
                    TextButton(onClick = { onCopy(t) }) { Text("复制") }
                    IconButton(onClick = { editTarget = t }) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "编辑",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { deleteTarget = info }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    // 重命名/开学日/周数编辑
    editTarget?.let { t ->
        var name by rememberSaveable { mutableStateOf(t.name) }
        var start by rememberSaveable { mutableStateOf(t.startMillis) }
        var weeks by rememberSaveable { mutableStateOf(t.totalWeeks) }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("编辑课表") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("课表名") },
                        singleLine = true,
                    )
                    DateField("开学日", start) { start = it }
                    WeeksRow(weeks) { weeks = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdate(t.copy(name = name.trim().ifBlank { t.name }, startMillis = start, totalWeeks = weeks))
                    editTarget = null
                }, enabled = name.isNotBlank()) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editTarget = null }) { Text("取消") } },
        )
    }

    // 删除确认
    deleteTarget?.let { info ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除课表") },
            text = {
                Text("将删除《${info.timetable.name}》的全部 ${info.courseCount} 门课程，不可恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(info.timetable)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

/** 桌面小组件实例信息。 */
data class WidgetInstanceInfo(val widgetId: Int, val compact: Boolean)

/** 小组件绑定设置页：每个桌面实例选择展示哪张课表。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetBindPage(
    widgets: List<WidgetInstanceInfo>,
    timetables: List<TimetableInfo>,
    bindings: Map<Int, Long>,
    glass: Boolean,
    onBind: (widgetId: Int, timetableId: Long) -> Unit,
    onBack: () -> Unit,
) {
    val transparent = androidx.compose.ui.graphics.Color.Transparent
    Scaffold(
        containerColor = if (glass) transparent else MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("桌面小组件") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (glass) transparent else MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text(
                "为桌面上的每个小组件选择展示的课表；未设置的跟随当前课表。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(top = 12.dp))
            if (widgets.isEmpty()) {
                Text(
                    "桌面上还没有简课表小组件\n长按桌面空白处 → 小部件 → 简课表 添加",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            widgets.forEach { w ->
                val bound = bindings[w.widgetId] ?: 0L
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("小组件 #${w.widgetId}", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (w.compact) "2×2 紧凑" else "3×2 标准",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    timetables.forEach { info ->
                        FilterChip(
                            selected = bound == info.timetable.id,
                            onClick = { onBind(w.widgetId, info.timetable.id) },
                            label = { Text(info.timetable.name, maxLines = 1) },
                        )
                    }
                    FilterChip(
                        selected = bound == 0L,
                        onClick = { onBind(w.widgetId, 0L) },
                        label = { Text("跟随当前") },
                    )
                }
                Spacer(Modifier.padding(top = 8.dp))
            }
        }
    }
}
