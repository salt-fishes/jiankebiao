package com.saltfish.simple.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * 磨砂玻璃原语（移植自 FU—Liquiglass 设计系统，Web → Compose）。
 *
 * 玻璃 = 着色 tint + 1px 定向描边 + 定向高光（135° 顶部渐隐）；背景由
 * CustomBackgroundLayer 统一模糊+scrim，卡片只需轻着色即呈磨砂质感。
 * 层级约定：Air（chip，弱）< Glass（导航/卡片/标题舱/底栏，中）。
 * 高密度正文文字保持主题色可读，不做透明化——玻璃只用于界面层级。
 */
enum class GlassLevel(val tintAlpha: Float) {
    Air(tintAlpha = 0.34f),
    Glass(tintAlpha = 0.46f),
}

/** 当前是否暗色主题（以 surface 亮度判断，供玻璃参数选择）。 */
@Composable
fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/**
 * 玻璃表面：半透明着色底 + 定向 1px 描边 + 顶部定向高光。
 * 覆盖层不拦截点击（无 pointerInput），内容交互不受影响。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.large,
    level: GlassLevel = GlassLevel.Glass,
    content: @Composable BoxScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val dark = isDarkTheme()
    val borderColor = if (dark) Color.White.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.60f)
    val borderColorSoft = if (dark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.14f)
    val highlight = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.28f)
    Box(
        modifier
            .clip(shape)
            .background(cs.surface.copy(alpha = level.tintAlpha))
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(listOf(borderColor, borderColorSoft)),
                shape = shape,
            )
    ) {
        // 定向高光：135° 自左上渐隐 + 右下角微弱回光（单一光源，避免塑料感均匀描边）
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.linearGradient(
                        0f to highlight,
                        0.38f to Color.Transparent,
                    )
                )
        )
        content()
    }
}

/**
 * 实验性自定义背景层：图片铺满（Crop）+ 模糊 + surface 色 scrim 压暗/提亮，
 * 保证前景文字可读（先安静背景，再谈玻璃）。
 *
 * 玻璃模式正式化后：开启开关但未选图时，渲染内置品牌渐变（随亮/暗色系），
 * 不再透传内容露出主题窗底——那会让暗色模式的浅色文字叠在白底上不可读。
 * 模糊使用 RenderEffect（API 31+）；低版本自动退化为仅 scrim（可接受）。
 */
@Composable
fun CustomBackgroundLayer(
    enabled: Boolean,
    imagePath: String,
    blurDp: Int,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    val cs = MaterialTheme.colorScheme
    // 以文件 mtime 作为缓存键：覆盖选择新图后立即刷新（路径不变也能重解码）；
    // 无图时 bitmap 为 null，走内置渐变。
    val stamp = if (imagePath.isNotBlank()) File(imagePath).lastModified() else 0L
    val bitmap = remember(imagePath, stamp) {
        if (imagePath.isBlank()) null else decodeDownsampled(imagePath, maxDim = 1440)
    }
    Box(Modifier.fillMaxSize()) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .then(
                        if (blurDp > 0) Modifier.blur(blurDp.coerceIn(1, 28).dp) else Modifier
                    ),
            )
            // scrim 随模糊强度递增：0dp 完全直显背景（不做白底遮挡），28dp 时 0.55
            val scrimAlpha = 0.55f * (blurDp.coerceIn(0, 28) / 28f)
            if (scrimAlpha > 0f) {
                Box(Modifier.matchParentSize().background(cs.surface.copy(alpha = scrimAlpha)))
            }
        } else {
            // 内置品牌渐变：顶部 primaryContainer 微光 → surfaceBright → surfaceDim。
            // 颜色全部取自当前色系，亮/暗模式对比度都由色系保证。
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                cs.primaryContainer.copy(alpha = 0.30f),
                                cs.surfaceBright,
                                cs.surfaceDim,
                            )
                        )
                    )
            )
        }
        content()
    }
}

/** 解码本地图片，按最大边降采样（避免整图内存）。 */
private fun decodeDownsampled(path: String, maxDim: Int): Bitmap? = runCatching {
    val f = File(path)
    if (!f.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
    BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}.getOrNull()
