package com.example.composeapp.ui.mine

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
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 关于页：应用介绍 / 主要功能与优势 / 更新记录 / 开发者信息。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutPage(
    versionName: String,
    glass: Boolean = false,
    onBack: () -> Unit,
) {
    Scaffold(
        containerColor = if (glass) androidx.compose.ui.graphics.Color.Transparent
        else MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (glass) androidx.compose.ui.graphics.Color.Transparent
                    else MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            // 图标占位（圆形首字）
            Box(
                Modifier
                    .size(72.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "简",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("简课表", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "版本 $versionName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "一个把课表装进口袋的本地应用",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            SectionTitle("主要功能")
            FeatureRow("课表周视图", "左右滑动切换周次，今日课程高亮，当前时间线提示", glass)
            FeatureRow("今日页", "正在上课 / 下一节课 / 今日课程时间轴", glass)
            FeatureRow("PDF 自动识别", "导入课表 PDF 自动解析课程、节次、周次与地点", glass)
            FeatureRow("本地编辑", "点课程可修改教师、地点，删除排课", glass)
            FeatureRow("桌面小组件", "桌面直接查看今日课程列表", glass)
            FeatureRow("上课提醒", "课前 5/10/15/20 分钟本地通知，准点触发", glass)
            FeatureRow("个性化", "深色模式、动态取色、作息时间、周末显隐自定义", glass)
            FeatureRow("自定义背景（实验）", "上传背景图片，首页/今日页/底栏磨砂玻璃风格", glass)

            Spacer(Modifier.height(20.dp))
            SectionTitle("优势")
            GlassCard(glass, Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "• 全程离线运行，不联网、不收集任何数据\n" +
                            "• 课程自动识别，省去手动录入\n" +
                            "• 界面简洁，Material You 设计",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionTitle("更新记录")
            ChangelogItem("1.3", listOf(
                "桌面小组件：今日课程速览",
                "上课提醒：课前 5/10/15/20 分钟本地通知，重启自动恢复",
                "实验性：自定义背景 + 磨砂玻璃界面（首页/今日页/底栏）",
                "课程块四周留距与顶部色条（玻璃模式）",
            ), glass, tag = "当前版本")
            ChangelogItem("1.2", listOf(
                "课程编辑：改名、教师、地点，删除单节排课",
                "新增课程：自定义星期、节次与周次",
                "作息时间设置、学期周数设置",
                "深色模式与动态取色",
            ), glass)
            ChangelogItem("1.1", listOf(
                "课表周视图重构：左右切周、时间红线、冲突分槽",
                "今日页与「我的」设置页",
            ), glass)
            ChangelogItem("1.0", listOf(
                "首发：导入课表 PDF 本地识别，自动生成课表",
            ), glass)

            Spacer(Modifier.height(24.dp))
            SectionTitle("致谢")
            GlassCard(glass, Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "• PaddleOCR（PP-OCRv6 tiny）：课表文字识别\n" +
                            "• ONNX Runtime：OCR 模型本地推理\n" +
                            "• OpenCV：图像预处理\n" +
                            "• Jetpack Compose · Material 3：界面与设计\n" +
                            "• Room：本地数据持久化\n" +
                            "• Kotlin：开发语言",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionTitle("开发者")
            InfoRow("开发者", "咸鱼")
            InfoRow("联系邮箱", "xunguang255@163.com")
            InfoRow("版本", versionName)
            Spacer(Modifier.height(12.dp))
            Text(
                "如果你在使用中遇到问题或有建议，欢迎通过邮箱联系。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun FeatureRow(title: String, detail: String, glass: Boolean = false) {
    GlassCard(glass, Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            modifier = modifier,
        ) { content() }
    }
}

/** 更新记录条目：版本号 + 标记 + 变更列表。 */
@Composable
private fun ChangelogItem(
    version: String,
    items: List<String>,
    glass: Boolean,
    tag: String = "",
) {
    GlassCard(glass, Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "v$version",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tag == "当前版本") MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            items.forEach { line ->
                Text(
                    "• $line",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(key: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}
