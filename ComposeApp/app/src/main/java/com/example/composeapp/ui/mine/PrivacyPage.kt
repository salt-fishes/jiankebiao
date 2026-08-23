package com.example.composeapp.ui.mine

import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 隐私政策页：强调本应用完全离线、不申请联网权限、不收集任何数据。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPage(
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("隐私政策") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
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
            // 核心承诺卡片：离线
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "本应用完全离线运行",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "「简课表」不申请任何联网权限，也不会访问网络。\n" +
                            "你的课表数据只保存在手机本地，绝不上传。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            PrivacySection("一、我们收集哪些信息", "我们不收集任何信息。本应用无需注册、无需登录、无需账号，也不申请网络、位置、通讯录、存储等任何敏感权限。")
            PrivacySection("二、课表数据存放在哪里", "课表 PDF 及解析后的课程数据仅存储在您手机的本地存储中（应用私有目录），卸载应用后数据随之删除。")
            PrivacySection("三、是否会上传或共享数据", "不会。应用内没有任何联网功能，代码中未申请 android.permission.INTERNET 权限，因此不存在数据上传、广告 SDK 或第三方统计。")
            PrivacySection("四、OCR 识别如何处理", "PDF 课表识别（OCR）在手机本地完成，使用内置模型，不需要网络，也不会将课表图片或文字发送到任何服务器。")
            PrivacySection("五、权限说明", "应用仅在导入课表时使用系统文件选择器读取您主动选择的 PDF 文件，并申请通知权限用于显示解析进度，不涉及其他权限。")
            PrivacySection("六、联系我们", "如您对本隐私政策有任何疑问，请联系：xunguang255@163.com")
            PrivacySection("七、政策更新", "本政策如有更新，将在应用内「关于」页面展示最新版本。更新日期：2026-08-23")

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
private fun PrivacySection(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
