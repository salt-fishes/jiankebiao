package com.example.composeapp.ui.mine

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.composeapp.ui.theme.AppMotion
import com.example.composeapp.ui.theme.Haptics
import com.example.composeapp.data.ScheduleSettings
import com.example.composeapp.data.WeekCalculator
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** 我的页：学期设置 / 显示开关 / 导入 / 数据。 */
@OptIn(
    ExperimentalMaterial3Api::class
)
@Composable
fun MineScreen(
    settings: ScheduleSettings,
    glass: Boolean = false,
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
    onOpenTimetableManage: () -> Unit = {},
    onOpenWidgetBind: () -> Unit = {},
    onSetRemindEnabled: (Boolean) -> Unit,
    onSetRemindMinutes: (Int) -> Unit,
    onSendTestReminder: () -> Unit,
    onSetCustomBgEnabled: (Boolean) -> Unit,
    onPickBackground: () -> Unit,
    onClearBackground: () -> Unit,
    onSetCustomBgBlur: (Int) -> Unit,
    onSyncCalendar: () -> Unit,
    onClearCalendar: () -> Unit,
    onExportIcs: () -> Unit,
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
    val context = LocalContext.current

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---- 页面标题：唯一的“大字”，层级起点 ----
        Text(
            "我的",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp),
        )

        // ---- 学期信息：全页视觉重心，最常查看/修改的两项 ----
        GlassCard(glass, Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                val rawWeek = WeekCalculator.currentWeek(settings.semesterStartDate, today)
                if (rawWeek < 1) {
                    // 边界处理：开学前显示倒计时
                    val daysToStart =
                        settings.semesterStartDate?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it) }
                    Text(
                        text = when {
                            settings.semesterStartDate == null -> "未设置开学时间"
                            daysToStart != null && daysToStart > 0 -> "距开学还有 $daysToStart 天"
                            else -> "今天开学"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Text(
                        "第 $rawWeek 周",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                settings.semesterStartDate?.let { date ->
                    HorizontalDivider(
                        Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDatePicker = true }
                            .padding(vertical = 4.dp),
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showWeekDialog = true }
                        .padding(vertical = 4.dp),
                ) {
                    Text("学期周数", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        "${settings.totalWeeks} 周",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // ---- 课表：导入 / 作息 / 管理与小组件 / 日历同步 ----
        SectionHeader("课表")
        GlassCard(glass, Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Button(
                    onClick = onPickPdf,
                    enabled = !parsing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(if (parsing) "解析中…" else "导入课表文件（PDF / Excel）")
                }
                if (parsing) {
                    LinearProgressIndicator(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )
                }
                parseError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                CardDivider()
                ActionRow(
                    "作息时间",
                    "共 ${settings.sectionTimes.size} 节 · 点击进入编辑",
                    trailing = "${fmt(settings.sectionTimes.first().start.hour, settings.sectionTimes.first().start.minute)}" +
                        " - ${fmt(settings.sectionTimes.last().end.hour, settings.sectionTimes.last().end.minute)}",
                ) { onOpenSectionTimes() }
                CardDivider()
                ActionRow("课表管理", "多课表切换 / 重命名 / 复制", enabled = !parsing) { onOpenTimetableManage() }
                CardDivider()
                ActionRow("桌面小组件", "3×2 与 2×2 分别绑定课表") { onOpenWidgetBind() }
                CardDivider()
                ActionRow(
                    "同步到系统日历",
                    "写入系统日历「简课表」，随系统日历提醒",
                    enabled = entryCount > 0 && !parsing,
                ) { onSyncCalendar() }
                CardDivider()
                ActionRow("清空系统日历中的课程", "撤销同步，仅删除本应用写入的课程") { onClearCalendar() }
                CardDivider()
                ActionRow("导出 .ics 文件", "备用：供其他日历应用手动导入", enabled = entryCount > 0) { onExportIcs() }
            }
        }

        // ---- 显示：可见性开关 + 磨砂玻璃背景 ----
        SectionHeader("显示")
        GlassCard(glass, Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                SwitchRow("显示周末", settings.showWeekend, onSetShowWeekend, Modifier.padding(horizontal = 16.dp))
                CardDivider()
                SwitchRow(
                    "显示非本周课程（淡化）",
                    settings.showNonCurrentWeek,
                    onSetShowNonCurrentWeek,
                    Modifier.padding(horizontal = 16.dp),
                )
                CardDivider()
                SwitchRow("磨砂玻璃风格", settings.customBgEnabled, onSetCustomBgEnabled, Modifier.padding(horizontal = 16.dp))
                AnimatedVisibility(
                    visible = settings.customBgEnabled,
                    enter = expandVertically(AppMotion.spatial()) + fadeIn(AppMotion.effects()),
                    exit = shrinkVertically(AppMotion.spatialFast()) + fadeOut(AppMotion.effectsFast()),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPickBackground() }
                                .padding(vertical = 8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("选择背景图片（可选）", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (settings.customBgPath.isBlank()) "未设置 · 使用内置渐变背景"
                                    else "已设置 · 自定义图片铺满首页/今日页/底栏",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "选择",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (settings.customBgPath.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "背景模糊",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.width(72.dp),
                                )
                                Slider(
                                    value = settings.customBgBlurDp.toFloat(),
                                    onValueChange = { onSetCustomBgBlur(((it / 4f).roundToInt() * 4)) },
                                    valueRange = 0f..28f,
                                    steps = 6,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${settings.customBgBlurDp}dp",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(44.dp),
                                )
                            }
                            TextButton(onClick = onClearBackground) {
                                Text("清除背景图片", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Text(
                            "覆盖首页、今日页与底栏；背景可换为自定义图片",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ---- 外观：主题色相关 ----
        SectionHeader("外观")
        GlassCard(glass, Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    "深色模式",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                    FilterChip(
                        selected = settings.darkMode == "system",
                        onClick = {
                            Haptics.tick(context)
                            onSetDarkMode("system")
                        },
                        label = { Text("跟随系统") },
                    )
                    FilterChip(
                        selected = settings.darkMode == "light",
                        onClick = {
                            Haptics.tick(context)
                            onSetDarkMode("light")
                        },
                        label = { Text("亮色") },
                    )
                    FilterChip(
                        selected = settings.darkMode == "dark",
                        onClick = {
                            Haptics.tick(context)
                            onSetDarkMode("dark")
                        },
                        label = { Text("暗色") },
                    )
                }
                CardDivider(Modifier.padding(vertical = 4.dp))
                SwitchRow(
                    "动态取色（Android 12+）",
                    settings.dynamicColor,
                    onSetDynamicColor,
                    Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        // ---- 提醒 ----
        SectionHeader("提醒")
        GlassCard(glass, Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                SwitchRow("上课前提醒", settings.remindEnabled, onSetRemindEnabled, Modifier.padding(horizontal = 16.dp))
                AnimatedVisibility(
                    visible = settings.remindEnabled,
                    enter = expandVertically(AppMotion.spatial()) + fadeIn(AppMotion.effects()),
                    exit = shrinkVertically(AppMotion.spatialFast()) + fadeOut(AppMotion.effectsFast()),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 2.dp),
                        ) {
                            Text(
                                "提前",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(10.dp))
                            listOf(5, 10, 15, 20).forEach { m ->
                                FilterChip(
                                    selected = settings.remindMinutesBefore == m,
                                    onClick = {
                                        Haptics.tick(context)
                                        onSetRemindMinutes(m)
                                    },
                                    label = { Text("$m 分钟") },
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "提醒在手机本地触发，重启后自动恢复",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onSendTestReminder) {
                                Text("发送测试通知")
                            }
                        }
                    }
                }
            }
        }

        // ---- 数据：统计与危险操作 ----
        SectionHeader("数据")
        GlassCard(glass, Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "当前《${settings.timetableName.ifBlank { "我的课表" }}》：$courseCount 门课程 · $entryCount 条排课",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                CardDivider()
                ActionRow("清除当前课表", "删除当前课表全部课程与排课，不可恢复", danger = true) {
                    showClearConfirm = true
                }
            }
        }

        // ---- 关于 ----
        SectionHeader("关于")
        GlassCard(glass, Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                ActionRow(
                    "关于简课表",
                    trailing = "版本 ${com.example.composeapp.BuildConfig.VERSION_NAME}",
                ) { onOpenAbout() }
                CardDivider()
                ActionRow("隐私政策", trailing = "不联网 · 不收集") { onOpenPrivacy() }
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
            title = { Text("清除当前课表") },
            text = { Text("将删除《${settings.timetableName.ifBlank { "我的课表" }}》的全部课程与排课（其他课表不受影响），此操作不可恢复。") },
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

/** 玻璃开关卡片容器：glass 开启时为磨砂玻璃面，否则为普通实色 Card。 */
@Composable
private fun GlassCard(
    glass: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (glass) {
        com.example.composeapp.ui.theme.GlassSurface(modifier = modifier) { content() }
    } else {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            modifier = modifier,
        ) { content() }
    }
}

private fun fmt(h: Int, m: Int): String = "%02d:%02d".format(h, m)

/** 卡片内分组行之间的细分隔线（左右留出卡片内边距）。 */
@Composable
private fun CardDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

/** 设置卡片内的可点击行：主标题 + 可选说明 + 可选右侧值。 */
@Composable
private fun ActionRow(
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

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
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        val context = LocalContext.current
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = {
            Haptics.tick(context)
            onChange(it)
        })
    }
}
