package com.example.composeapp.ui.mine

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.composeapp.data.ScheduleSettings
import com.example.composeapp.data.SectionTime

/** 作息时间页：一日节数调整 + 各节起止时间编辑（独立全屏页面）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionTimePage(
    settings: ScheduleSettings,
    onSetSectionTimes: (List<SectionTime>) -> Unit,
    onSetSectionsPerDay: (Int) -> Unit,
    onBack: () -> Unit,
) {
    var editingSection by rememberSaveable { mutableStateOf<Int?>(null) }
    var pickingEnd by rememberSaveable { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf<java.time.LocalTime?>(null) }

    BackHandler { onBack() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column {
            // ---- 顶栏：返回 + 标题 ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text("作息时间设置", style = MaterialTheme.typography.titleMedium)
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "按课程表的节次数调整每日作息；修改后课表左侧节次轴同步更新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                // ---- 一日节数步进器（4..16） ----
                Card(colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("一日节数", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "课表网格与作息表行数随之变化",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { onSetSectionsPerDay(settings.sectionsPerDay - 1) },
                            enabled = settings.sectionsPerDay > 4,
                        ) { Text("−", style = MaterialTheme.typography.titleMedium) }
                        Text(
                            "${settings.sectionsPerDay}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                                                IconButton(
                            onClick = { onSetSectionsPerDay(settings.sectionsPerDay + 1) },
                            enabled = settings.sectionsPerDay < 16,
                        ) { Icon(Icons.Filled.Add, contentDescription = "增加一节") }
                    }
                }

                // ---- 各节起止时间 ----
                Card(colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )) {
                    LazyColumn(Modifier.fillMaxWidth().height((settings.sectionTimes.size * 44).dp)) {
                        itemsIndexed(settings.sectionTimes) { i, st ->
                            if (i > 0) HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { editingSection = st.section; pickingEnd = false }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                Text("第 ${st.section} 节", Modifier.weight(1f))
                                Text(
                                    "${fmt(st.start.hour, st.start.minute)} - ${fmt(st.end.hour, st.end.minute)}",
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.size(6.dp))
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- 时间编辑弹窗：先开始，后结束 ----
    androidx.compose.runtime.key(editingSection to pickingEnd) {
        editingSection?.let { sec ->
            val current = settings.sectionTimes.firstOrNull { it.section == sec }
            if (current != null) {
                val initial = if (pickingEnd) current.end else current.start
                val timeState = rememberTimePickerState(
                    initialHour = initial.hour,
                    initialMinute = initial.minute,
                    is24Hour = true,
                )
                Dialog(
                    onDismissRequest = { editingSection = null; pendingStart = null },
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    ) {
                        Column(
                            Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                if (pickingEnd) "第 $sec 节 · 结束时间" else "第 $sec 节 · 开始时间",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            TimePicker(state = timeState)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { editingSection = null; pendingStart = null }) {
                                    Text("取消")
                                }
                                TextButton(onClick = {
                                    val picked = java.time.LocalTime.of(timeState.hour, timeState.minute)
                                    if (!pickingEnd) {
                                        pendingStart = picked
                                        pickingEnd = true
                                    } else {
                                        val start = pendingStart ?: current.start
                                        onSetSectionTimes(settings.sectionTimes.map {
                                            if (it.section == sec) it.copy(start = start, end = picked) else it
                                        })
                                        editingSection = null
                                        pendingStart = null
                                    }
                                }) { Text(if (pickingEnd) "确定" else "下一步") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun fmt(h: Int, m: Int): String = "%02d:%02d".format(h, m)
