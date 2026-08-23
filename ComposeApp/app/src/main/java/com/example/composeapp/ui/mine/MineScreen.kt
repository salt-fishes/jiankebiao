package com.example.composeapp.ui.mine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.composeapp.data.ScheduleSettings
import com.example.composeapp.data.WeekCalculator
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 我的页：学期设置 / 显示开关 / 导入 / 数据。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MineScreen(
    settings: ScheduleSettings,
    courseCount: Int,
    entryCount: Int,
    parsing: Boolean,
    parseError: String?,
    onPickPdf: () -> Unit,
    onSetSemesterStart: (LocalDate) -> Unit,
    onSetTotalWeeks: (Int) -> Unit,
    onSetShowWeekend: (Boolean) -> Unit,
    onSetShowNonCurrentWeek: (Boolean) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSetDarkMode: (String) -> Unit,
    onOpenSectionTimes: () -> Unit,
    onClearData: () -> Unit,
    onShowSnackbar: (String) -> Unit,
    onOpenAbout: () -> Unit,
    onOpenPrivacy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showWeekDialog by rememberSaveable { mutableStateOf(false) }
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }
    val today = remember { LocalDate.now() }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("我的", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))

        // ---- 学期信息卡 ----
        Card(colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                val week = WeekCalculator.currentWeek(settings.semesterStartDate, today)
                Text(
                    "第 $week 周",
                    style = MaterialTheme.typography.headlineSmall,
                )
                settings.semesterStartDate?.let { date ->
                    // 开学日期 + 行内"修改"入口（点击弹日期选择）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDatePicker = true }
                            .padding(vertical = 2.dp),
                    ) {
                        Text(
                            "开学：${date.format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日"))}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "修改",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                // 修改学期周数（放在修改开学时间后）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showWeekDialog = true }
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        "学期周数",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${settings.totalWeeks} 周",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        SectionHeader("显示")
        SwitchRow("显示周末", settings.showWeekend, onSetShowWeekend)
        SwitchRow("显示非本周课程（淡化）", settings.showNonCurrentWeek, onSetShowNonCurrentWeek)

        SectionHeader("外观")
        SwitchRow(
            "动态取色（Android 12+）",
            settings.dynamicColor,
            onSetDynamicColor,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Text("深色模式", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.darkMode == "system",
                onClick = { onSetDarkMode("system") },
                label = { Text("跟随系统") },
            )
            FilterChip(
                selected = settings.darkMode == "light",
                onClick = { onSetDarkMode("light") },
                label = { Text("亮色") },
            )
            FilterChip(
                selected = settings.darkMode == "dark",
                onClick = { onSetDarkMode("dark") },
                label = { Text("暗色") },
            )
        }

        SectionHeader("作息时间")
        Card(colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenSectionTimes() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("作息时间设置", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "共 ${settings.sectionTimes.size} 节 · 点击进入编辑",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${fmt(settings.sectionTimes.first().start.hour, settings.sectionTimes.first().start.minute)}" +
                        " - " +
                        fmt(settings.sectionTimes.last().end.hour, settings.sectionTimes.last().end.minute),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        SectionHeader("课表数据")
        Button(onClick = onPickPdf, enabled = !parsing, modifier = Modifier.fillMaxWidth()) {
            Text(if (parsing) "解析中…" else "导入课表 PDF")
        }
        if (parsing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        parseError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "已导入 $courseCount 门课程 · $entryCount 条排课",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionHeader("数据")
        TextButton(onClick = { showClearConfirm = true }) {
            Text("清除课表数据", color = MaterialTheme.colorScheme.error)
        }

        SectionHeader("关于")
        Card(colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenAbout() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text("关于简课表", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("版本 1.2", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPrivacy() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text("隐私政策", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("不联网 · 不收集数据", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    if (showDatePicker) {
        val initMillis = settings.semesterStartDate
            ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val dateState = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = initMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { ms ->
                        val d = java.time.Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        onSetSemesterStart(d)
                        onShowSnackbar("开学时间已更新")
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清除课表数据") },
            text = { Text("将删除所有课程与排课记录，此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    onClearData()
                    showClearConfirm = false
                    onShowSnackbar("已清除")
                }) { Text("清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }

    if (showWeekDialog) {
        var sel by rememberSaveable { mutableIntStateOf(settings.totalWeeks) }
        AlertDialog(
            onDismissRequest = { showWeekDialog = false },
            title = { Text("学期总周数") },
            text = {
                Column {
                    Text(
                        "当前 ${sel} 周（课表按此生成周数）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items((8..30).toList()) { w ->
                            FilterChip(
                                selected = sel == w,
                                onClick = { sel = w },
                                label = { Text("$w") },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSetTotalWeeks(sel)
                    onShowSnackbar("学期周数已更新为 $sel 周")
                    showWeekDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showWeekDialog = false }) { Text("取消") }
            },
        )
    }

    // ---- 作息时间编辑已移至独立页面 SectionTimePage ----
}

private fun fmt(h: Int, m: Int): String = "%02d:%02d".format(h, m)

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
