package com.saltfish.simple.ui.timetable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.saltfish.simple.data.EntryWithCourse

/** 编辑排课：课程名（全局）+ 本节的教师/地点；支持删除本条。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseEditSheet(
    entry: EntryWithCourse,
    onSave: (courseName: String, teacher: String, campus: String, building: String, room: String) -> Unit,
    onDelete: (EntryWithCourse) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(entry.courseName) }
    var teacher by rememberSaveable { mutableStateOf(entry.teacher) }
    var campus by rememberSaveable { mutableStateOf(entry.campus) }
    var building by rememberSaveable { mutableStateOf(entry.building) }
    var room by rememberSaveable { mutableStateOf(entry.room) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("编辑课程", style = MaterialTheme.typography.titleLarge)
            Text(
                "周一 · ${entry.sectionRangeLabel} · 第 ${
                    com.saltfish.simple.data.TimeUtils.compressInts(entry.weeks)
                } 周",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("课程名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = teacher,
                onValueChange = { teacher = it },
                label = { Text("教师") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = campus,
                    onValueChange = { campus = it },
                    label = { Text("校区") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = building,
                    onValueChange = { building = it },
                    label = { Text("楼号") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = room,
                onValueChange = { room = it },
                label = { Text("场地") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                TextButton(onClick = {
                    onDelete(entry)
                    onDismiss()
                }) {
                    Text("删除本节", color = MaterialTheme.colorScheme.error)
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = {
                    if (name.trim().isNotEmpty()) {
                        onSave(name.trim(), teacher, campus, building, room)
                    }
                    onDismiss()
                }) {
                    Text("保存")
                }
            }
        }
    }
}
