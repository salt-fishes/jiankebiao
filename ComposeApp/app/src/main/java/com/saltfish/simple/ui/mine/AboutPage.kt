package com.saltfish.simple.ui.mine

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.saltfish.simple.R
import com.saltfish.simple.ui.theme.AppMotion

/** 更新记录数据：新版本在前。 */
private val CHANGELOG: List<Pair<String, List<String>>> = listOf(
    "1.7.1" to listOf(
        "LTS 长期稳定版：在 v1.7 基础上打磨体验，无破坏性改动",
        "修复底栏药丸拖动跟手性（拖动基准冻结 + 手势取消兜底）",
        "振动反馈扩展：课表拖起/落位、课程增删改、课表切换与复制删除、学期设置",
        "隐私政策权限清单新增「振动」说明",
        "兼容性说明精简：支持 Android 8.0 及以上系统",
    ),
    "1.7" to listOf(
        "系统日历直同步：课程一键写入系统日历「简课表」，可一键清空撤销",
        "全新应用图标；设置页整合重排，层次更清晰",
        "更丰富的弹性动效：二级页转场、底栏拖拽吸附、图标回弹",
        "二级页返回时保留之前的浏览位置",
        "彻底修复暗色模式下玻璃界面正文变黑的问题",
        "操作振动反馈：底栏切换、调课落位、同步完成等关键节点轻微震动",
    ),
    "1.6" to listOf(
        "分享直达：微信/QQ 分享课表文件可直接选「简课表」导入",
        "磨砂玻璃风格正式化：默认内置渐变背景，可换自定义图片",
        "导出到系统日历（.ics）：可导入手机日历获得全天候提醒",
        "界面动效：底栏胶囊滑移、周数滚动、方向性转场、拖拽落位回弹",
        "升级 Material 3 至 1.4（Expressive 基线）",
    ),
    "1.5" to listOf(
        "多课表管理：班级 / 个人 / 同学的课表并存，顶栏面板快速切换",
        "新建课表可选导入文件或复制现有课表，自动命名与开学日识别",
        "每张课表独立的开学日、总周数与作息时间",
        "小组件按实例绑定课表（3×2 / 2×2）",
        "修复周次计算与开学前显示问题；手势返回",
    ),
    "1.4" to listOf(
        "班级课表 Excel 导入（免 OCR，秒级）",
        "长按拖拽调课、一键分享整周课表",
        "2×2 桌面小组件、小组件连堂课时间修正",
        "课程颜色互不相同（12 组色板去重分配）",
    ),
    "1.3" to listOf(
        "桌面小组件：今日课程速览",
        "上课提醒：课前 5/10/15/20 分钟本地通知，重启自动恢复",
        "实验性：自定义背景 + 磨砂玻璃界面（首页/今日页/底栏）",
        "课程块四周留距与顶部色条（玻璃模式）",
    ),
    "1.2" to listOf(
        "课程编辑：改名、教师、地点，删除单节排课",
        "新增课程：自定义星期、节次与周次",
        "作息时间设置、学期周数设置",
        "深色模式与动态取色",
    ),
    "1.1" to listOf(
        "课表周视图重构：左右切周、时间红线、冲突分槽",
        "今日页与「我的」设置页",
    ),
    "1.0" to listOf(
        "首发：导入课表 PDF 本地识别，自动生成课表",
    ),
)

/** 关于页：应用介绍 / 主要功能 / 更新记录（点击展开）/ 开发者信息。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutPage(
    versionName: String,
    glass: Boolean = false,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
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
            Spacer(Modifier.height(20.dp))

            // ---- 头部：真实应用图标 + 名称 + 版本 + 一句话定位 ----
            Image(
                painter = painterResource(R.drawable.ic_launcher_bg),
                contentDescription = "应用图标",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(22.dp)),
            )
            Spacer(Modifier.height(12.dp))
            Text("简课表", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "版本 $versionName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "把课表装进口袋的本地应用 · 不联网 · 课表本地识别",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            // ---- 主要功能：收纳为一张分组卡片，行间细分隔线 ----
            SectionTitle("主要功能")
            GlassCard(glass, Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    FeatureRow("多课表管理", "班级课表 / 个人课表 / 同学的课表并存，随时切换")
                    CardDivider()
                    FeatureRow("课表周视图", "左右滑动切换周次，今日课程高亮，当前时间线提示")
                    CardDivider()
                    FeatureRow("今日页", "正在上课 / 下一节课 / 今日课程时间轴")
                    CardDivider()
                    FeatureRow("PDF / Excel 导入", "个人课表 PDF 本地识别；班级课表 Excel 直接解析")
                    CardDivider()
                    FeatureRow("长按拖拽调课", "长按课程块即可跨天、跨节次移动")
                    CardDivider()
                    FeatureRow("系统日历同步", "课程直接写入系统日历，随系统提醒，可一键清空")
                    CardDivider()
                    FeatureRow("课表分享", "一键生成整周课表图片，调起系统分享")
                    CardDivider()
                    FeatureRow("桌面小组件", "3×2 与 2×2 两种规格，可分别绑定课表")
                    CardDivider()
                    FeatureRow("上课提醒", "课前 5/10/15/20 分钟本地通知，准点触发")
                    CardDivider()
                    FeatureRow("个性化", "深色模式、动态取色、磨砂玻璃、自定义背景与作息时间")
                }
            }

            Spacer(Modifier.height(24.dp))

            // ---- 更新记录：收纳折叠，点击展开；当前版本默认展开 ----
            SectionTitle("更新记录")
            var expanded by rememberSaveable {
                mutableStateOf(setOf(CHANGELOG.first().first))
            }
            CHANGELOG.forEachIndexed { index, (version, items) ->
                val isExpanded = version in expanded
                val rotation by animateFloatAsState(
                    targetValue = if (isExpanded) 180f else 0f,
                    animationSpec = AppMotion.spatialFast(),
                    label = "changelogChevron$index",
                )
                GlassCard(glass, Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expanded = if (isExpanded) expanded - version else expanded + version
                                }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(
                                "v$version",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (index == 0) "当前版本 · ${items.size} 项更新" else "${items.size} 项更新",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (index == 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (isExpanded) "收起" else "展开",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.graphicsLayer { rotationZ = rotation },
                            )
                        }
                        AnimatedVisibility(
                            visible = isExpanded,
                            enter = expandVertically(AppMotion.spatial()) + fadeIn(AppMotion.effects()),
                            exit = shrinkVertically(AppMotion.spatialFast()) + fadeOut(AppMotion.effectsFast()),
                        ) {
                            Column(Modifier.padding(bottom = 8.dp)) {
                                items.forEach { line ->
                                    Text(
                                        "• $line",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            SectionTitle("优势与致谢")
            GlassCard(glass, Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "• 全程离线运行，不联网、不收集任何数据\n" +
                            "• 课程自动识别，省去手动录入\n" +
                            "• 界面简洁，Material You 设计",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "感谢以下开源项目：PaddleOCR（PP-OCRv6 tiny）、ONNX Runtime、OpenCV、Jetpack Compose · Material 3、Room、Kotlin",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            SectionTitle("兼容性")
            GlassCard(glass, Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "• 支持 Android 8.0 及以上系统\n" +
                            "• 课表识别当前适配正方教务导出的 PDF 与班级课表 Excel\n" +
                            "• 其他教务系统如有适配需求，欢迎发邮件反馈",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            SectionTitle("开发者")
            GlassCard(glass, Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    InfoRow("开发者", "咸鱼")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    InfoRow("联系邮箱", "xunguang255@163.com")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            "https://github.com/salt-fishes/jiankebiao".toUri(),
                                        )
                                    )
                                }
                            }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(
                            "开源仓库",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "github.com/salt-fishes/jiankebiao",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    )
}

/** 卡片内分组行之间的细分隔线。 */
@Composable
private fun CardDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

@Composable
private fun FeatureRow(title: String, detail: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
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

/** 玻璃开关卡片容器：glass 开启时为磨砂玻璃面，否则为普通实色 Card。 */
@Composable
private fun GlassCard(
    glass: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (glass) {
        com.saltfish.simple.ui.theme.GlassSurface(modifier = modifier) { content() }
    } else {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            modifier = modifier,
        ) { content() }
    }
}

@Composable
private fun InfoRow(key: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
