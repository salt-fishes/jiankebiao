package com.saltfish.simple.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.saltfish.simple.data.EntryWithCourse
import com.saltfish.simple.data.TimeUtils
import com.saltfish.simple.ui.theme.courseBlockColors
import com.saltfish.simple.ui.theme.courseBlockColorsDynamic

private val DAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 课程详情：时间 / 地点 / 教师 / 学分（教师与学分只在详情展示，不上格子）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailSheet(
    entry: EntryWithCourse,
    dynamicColor: Boolean,
    onEdit: (EntryWithCourse) -> Unit = {},
    onDelete: (EntryWithCourse) -> Unit = {},
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            // 头部：色点 + 课程名 + 徽标（类型/学分）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(
                            if (dynamicColor) {
                                courseBlockColorsDynamic(entry.colorIndex).first
                            } else {
                                courseBlockColors(
                                    entry.colorIndex,
                                    MaterialTheme.colorScheme.surface.luminance() < 0.5f,
                                ).first
                            },
                            CircleShape
                        )
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    entry.courseName,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                if (entry.type.isNotBlank()) AssistChip(
                    onClick = {},
                    label = { Text(entry.type) }
                )
                if (entry.credit.isNotBlank()) AssistChip(
                    onClick = {},
                    label = { Text("${entry.credit} 学分") }
                )
            }

            HorizontalDivider(
                Modifier.padding(vertical = 14.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            DetailRow("时间", timeLabel(entry))
            DetailRow("地点", entry.fullLocation.ifBlank { "—" })
            DetailRow("教师", entry.teacher.ifBlank { "—" })

            // 操作行（MD3：主操作 FilledTonal，次操作 Text）
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                FilledTonalButton(onClick = { onEdit(entry); onDismiss() }) {
                    Text("编辑")
                }
                TextButton(onClick = {
                    onDelete(entry)
                    onDismiss()
                }) {
                    Text("删除本节", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(width = 56.dp, height = 22.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun timeLabel(e: EntryWithCourse): String {
    val day = DAY_NAMES.getOrElse(e.dayOfWeek - 1) { "" }
    return buildString {
        append(day).append(" · ").append(e.sectionRangeLabel)
        val w = TimeUtils.compressInts(e.weeks)
        if (w.isNotBlank()) append(" · 第 $w 周")
    }
}
