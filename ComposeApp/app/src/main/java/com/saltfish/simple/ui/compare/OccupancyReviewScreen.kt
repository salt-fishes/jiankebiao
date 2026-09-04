package com.saltfish.simple.ui.compare

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saltfish.simple.schedule.OccupancyParser
import com.saltfish.simple.ui.theme.Haptics
import kotlin.math.roundToInt

private val DAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 截图识别的原始结果（校正界面输入，grid 供叠加与校正网格初始化）。 */
data class OccupancyDetection(
    val imagePath: String,
    val grid: OccupancyParser.Grid,
)

/**
 * 占用识别人工校正：原图叠加识别框 + 可点选的占用网格（7 天 × N 节）。
 * 算法负责大部分格子，用户点选修正长尾——保存前必须过这一步。
 */
@Composable
fun OccupancyReviewScreen(
    detection: OccupancyDetection,
    glass: Boolean = false,
    onSave: (name: String, dayCount: Int, blocks: List<CompareBlock>) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap = remember { BitmapFactory.decodeFile(detection.imagePath) }
    var name by rememberSaveable { mutableStateOf("") }
    val grid = detection.grid
    // 占用集合：key = day * 100 + section
    var occupied by remember {
        mutableStateOf(
            grid.blocks
                .flatMap { b -> ((b.day * 100L + b.startSection)..(b.day * 100L + b.endSection)).asSequence() }
                .toSet()
        )
    }

    fun toggle(day: Int, section: Int) {
        val key = day * 100L + section
        occupied = if (key in occupied) occupied - key else occupied + key
        Haptics.tick(context)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(if (glass) Color.Transparent else MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            "识别结果校正",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "算法只保证大概，请对照原图点选修正：填色格 = 有课。确认后保存。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        // 原图 + 识别框叠加（灰显，突出校正网格）
        if (bitmap != null) {
            val overlay = remember { OccupancyParser.drawOverlay(bitmap, grid) }
            androidx.compose.foundation.Canvas(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(overlay.width.toFloat() / overlay.height)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            ) {
                drawImage(
                    image = overlay.asImageBitmap(),
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    alpha = 0.55f,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        // 校正网格
        Row {
            Column(Modifier.width(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(22.dp))
                repeat(grid.sectionCount) { row ->
                    Box(Modifier.height(30.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "${row + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Column {
                Row {
                    repeat(7) { d ->
                        Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
                            Text(
                                DAY_LABELS[d],
                                style = MaterialTheme.typography.labelSmall,
                                color = if (d < grid.dayCount) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
                repeat(grid.sectionCount) { row ->
                    Row {
                        repeat(7) { day ->
                            val editable = day < grid.dayCount
                            val on = editable && (day + 1) * 100L + (row + 1) in occupied
                            Box(
                                Modifier
                                    .padding(1.dp)
                                    .size(width = 42.dp, height = 30.dp)
                                    .background(
                                        when {
                                            !editable -> MaterialTheme.colorScheme.surfaceContainerLowest
                                            on -> MaterialTheme.colorScheme.primaryContainer
                                            else -> MaterialTheme.colorScheme.surfaceContainerLow
                                        },
                                        RoundedCornerShape(4.dp),
                                    )
                                    .then(
                                        if (editable) Modifier.clickable { toggle(day + 1, row + 1) }
                                        else Modifier
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (on) {
                                    Text(
                                        "课",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        if (grid.dayCount < 7) {
            Text(
                "截图中仅显示 ${grid.dayCount} 天，其余日期不计入对比。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("课表名称（如：张三）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("取消") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val blocks = occupied.map { key ->
                        CompareBlock(
                            day = (key / 100L).toInt(),
                            startSection = (key % 100L).toInt(),
                            endSection = (key % 100L).toInt(),
                        )
                    }
                    onSave(name.ifBlank { "对比课表" }, grid.dayCount, blocks)
                },
            ) { Text("保存") }
        }
        Spacer(Modifier.height(24.dp))
    }
}
