package com.saltfish.simple.ui.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saltfish.simple.schedule.RulePackStore
import com.saltfish.simple.ui.theme.GlassSurface

/**
 * 解析规则包管理页：列出内置与导入的规则包，支持从文件导入、删除导入包。
 *
 * 规则包 = 「教务系统导出/课程截图 → 课程字段」的格式规则（纯数据），
 * 供 PDF 导入与图片识别共用。开发规则包请使用仓库 rulepack-dev/ 工作台。
 */
@Composable
fun RulePackPage(
    entries: List<RulePackStore.Entry>,
    glass: Boolean = false,
    onImport: () -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "解析规则包",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        Text(
            "导入 PDF / 截图识别课表时，应用按指纹自动选择规则包解析字段。" +
                "新教务系统无需等待更新：自己制作规则包即可（仓库 rulepack-dev/ 工作台）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Text("从文件导入规则包（JSON）")
        }

        entries.forEach { entry ->
            val container = if (entry.builtin) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.secondaryContainer
            if (glass && entry.builtin) {
                GlassSurface(Modifier.fillMaxWidth()) {
                    EntryContent(entry, onDelete = null)
                }
            } else {
                Card(
                    colors = CardDefaults.cardColors(containerColor = container),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    EntryContent(entry, onDelete = if (entry.builtin) null else onDelete)
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "注意：规则包只包含格式规则（词表/正则），不应包含任何真实课表数据。" +
                "发现来源不明的规则包请勿导入。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EntryContent(
    entry: RulePackStore.Entry,
    onDelete: ((String) -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.pack.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                buildString {
                    append(if (entry.builtin) "内置" else "导入")
                    append(" · v${entry.pack.version} · ${entry.pack.id}")
                    append(" · 适配指纹 ${entry.pack.matchAnyOf.take(3)}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onDelete != null) {
            IconButton(onClick = { onDelete(entry.pack.id) }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
