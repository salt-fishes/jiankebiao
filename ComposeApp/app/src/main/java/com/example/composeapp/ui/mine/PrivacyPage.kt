package com.example.composeapp.ui.mine

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp

/**
 * 隐私政策页：核心承诺 + 权限清单（申请哪些权限、用作何用）+
 * 不联网承诺 + 数据存储 + 兼容性说明。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPage(
    glass: Boolean = false,
    onBack: () -> Unit,
) {
    Scaffold(
        containerColor = if (glass) androidx.compose.ui.graphics.Color.Transparent
        else MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("隐私政策") },
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
        ) {
            Spacer(Modifier.height(8.dp))

            // ---- 核心承诺：离线 ----
            if (glass) {
                com.example.composeapp.ui.theme.GlassSurface(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) { CorePromise() }
                }
            } else {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) { CorePromise() }
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle("权限说明：申请了哪些权限，用来做什么")
            GlassCard(glass, Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    PermissionRow(
                        "日历（读取与写入）",
                        "把课程写入系统日历「简课表」、执行「清空系统日历中的课程」撤销同步。" +
                            "仅在你点击「同步到系统日历」或「清空」按钮时使用，不会读取你其他日历的内容。",
                    )
                    CardDivider()
                    PermissionRow(
                        "通知",
                        "显示课表导入的解析结果通知，以及课前上课提醒（Android 13 及以上需你授权）。",
                    )
                    CardDivider()
                    PermissionRow(
                        "精确闹钟",
                        "让课前提醒在设定时刻准点触发，不用于任何其他目的。",
                    )
                    CardDivider()
                    PermissionRow(
                        "前台服务",
                        "导入课表期间在本地运行 OCR 识别，防止长时间解析被系统中断。",
                    )
                    CardDivider()
                    PermissionRow(
                        "开机自启",
                        "手机重启后自动恢复课前提醒闹钟，避免提醒静默失效。",
                    )
                    CardDivider()
                    PermissionRow(
                        "系统文件选择器（非存储权限）",
                        "仅读取你在弹窗中主动选择的课表 PDF / Excel 文件；导出 .ics 时写入你指定的位置。" +
                            "本应用不申请「存储空间」权限。",
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle("不联网承诺")
            GlassCard(glass, Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "本应用没有申请 android.permission.INTERNET（互联网）权限。" +
                            "这不是一句口号，而是系统层面的硬性限制：没有该权限，应用在技术上就无法发起任何网络请求。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "因此：无数据上传、无广告 SDK、无第三方统计埋点、无远程配置。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle("数据存放在哪里")
            GlassCard(glass, Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "课程数据保存在应用私有的本地数据库（Room）中；背景图片、解析临时文件保存在应用私有目录。" +
                            "这些位置其他应用无法访问，卸载应用后全部随之删除。" +
                            "写入系统日历的课程事件保存在系统日历的「简课表」日历中，可随时在应用内一键清空。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle("唯一的对外数据出口")
            GlassCard(glass, Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "只有一种情况数据会离开本应用：你主动点击「分享」时，应用把生成的课表图片交给" +
                            "系统分享面板中你选择的应用（如微信、QQ）处理。除此之外不存在任何数据出口。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle("兼容性说明")
            GlassCard(glass, Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "• 支持 Android 8.0（API 26）至最新系统，含荣耀 MagicOS、华为 HarmonyOS、小米澎湃 OS 等国产 ROM\n" +
                            "• 背景模糊与动态取色需要 Android 12 及以上，低版本自动降级，不影响核心功能\n" +
                            "• 荣耀 / 华为等系统日历没有 .ics 文件导入入口，推荐使用应用内「同步到系统日历」直接写入\n" +
                            "• 课表识别当前适配正方教务导出的个人课表 PDF 与班级课表 Excel，其他教务系统欢迎邮件反馈",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle("联系我们与政策更新")
            GlassCard(glass, Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "如对本政策有任何疑问，请联系：xunguang255@163.com",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "本政策如有更新，将在应用内「关于」页同步展示最新版本。\n更新日期：2026-09-03（随 v1.7 更新）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "开发者：咸鱼",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CorePromise() {
    Text(
        "本应用完全离线运行",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "「简课表」不申请任何联网权限，也不会访问网络。\n你的课表数据只保存在手机本地，绝不上传。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
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

/** 权限清单行：权限名 + 用途说明。 */
@Composable
private fun PermissionRow(name: String, purpose: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(
            purpose,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 卡片内分组行之间的细分隔线。 */
@Composable
private fun CardDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}
