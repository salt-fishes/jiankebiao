package com.example.composeapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

// 基于 seed #333464（TonalSpot，material-color-utilities 计算）
val SeedPrimary = Color(0xFF333464)

// ---- 亮色方案 ----
val LightPrimary = Color(0xFF585992)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFE1DFFF)
val LightOnPrimaryContainer = Color(0xFF13144A)
val LightSecondary = Color(0xFF5D5C72)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFE2E0F9)
val LightOnSecondaryContainer = Color(0xFF1A1A2C)
val LightTertiary = Color(0xFF795369)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFFD8EC)
val LightOnTertiaryContainer = Color(0xFF2E1125)
val LightError = Color(0xFFB3261E)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFF9DEDC)
val LightOnErrorContainer = Color(0xFF410E0B)
val LightSurface = Color(0xFFFCF8FD)
val LightOnSurface = Color(0xFF1C1B1F)
val LightOnSurfaceVariant = Color(0xFF47464F)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF6F2F7)
val LightSurfaceContainer = Color(0xFFF0EDF1)
val LightSurfaceContainerHigh = Color(0xFFEBE7EC)
val LightSurfaceContainerHighest = Color(0xFFE5E1E6)
val LightOutline = Color(0xFF777680)
val LightOutlineVariant = Color(0xFFC8C5D0)
val LightInverseSurface = Color(0xFF313034)
val LightInverseOnSurface = Color(0xFFF3EFF4)
val LightInversePrimary = Color(0xFFC1C1FF)
val LightSurfaceDim = Color(0xFFDCD9DE)
val LightSurfaceBright = Color(0xFFFCF8FD)

// ---- 暗色方案 ----
val DarkPrimary = Color(0xFFC1C1FF)
val DarkOnPrimary = Color(0xFF292A60)
val DarkPrimaryContainer = Color(0xFF404178)
val DarkOnPrimaryContainer = Color(0xFFE1DFFF)
val DarkSecondary = Color(0xFFC6C4DD)
val DarkOnSecondary = Color(0xFF2F2F42)
val DarkSecondaryContainer = Color(0xFF454559)
val DarkOnSecondaryContainer = Color(0xFFE2E0F9)
val DarkTertiary = Color(0xFFE9B9D3)
val DarkOnTertiary = Color(0xFF46263A)
val DarkTertiaryContainer = Color(0xFF5F3C51)
val DarkOnTertiaryContainer = Color(0xFFFFD8EC)
val DarkError = Color(0xFFF2B8B5)
val DarkOnError = Color(0xFF601410)
val DarkErrorContainer = Color(0xFF8C1D18)
val DarkOnErrorContainer = Color(0xFFF9DEDC)
val DarkSurface = Color(0xFF131316)
val DarkOnSurface = Color(0xFFE5E1E6)
val DarkOnSurfaceVariant = Color(0xFFC8C5D0)
val DarkSurfaceContainerLowest = Color(0xFF0E0E11)
val DarkSurfaceContainerLow = Color(0xFF1C1B1F)
val DarkSurfaceContainer = Color(0xFF201F23)
val DarkSurfaceContainerHigh = Color(0xFF2A292D)
val DarkSurfaceContainerHighest = Color(0xFF353438)
val DarkOutline = Color(0xFF918F9A)
val DarkOutlineVariant = Color(0xFF47464F)
val DarkInverseSurface = Color(0xFFE5E1E6)
val DarkInverseOnSurface = Color(0xFF313034)
val DarkInversePrimary = Color(0xFF585992)
val DarkSurfaceDim = Color(0xFF131316)
val DarkSurfaceBright = Color(0xFF39393C)

// ---- 课程块派生色（12 组，seed 色相每 30° 展开，MD3 容器色调对）----
data class BlockPalette(
    val containerLight: Color,
    val onContainerLight: Color,
    val containerDark: Color,
    val onContainerDark: Color,
)

val CourseBlockPalettes: List<BlockPalette> = listOf(
    BlockPalette(Color(0xFFE1DFFF), Color(0xFF2A2B5D), Color(0xFF414275), Color(0xFFE1DFFF)), // hue 285
    BlockPalette(Color(0xFFF6D9FF), Color(0xFF471C5D), Color(0xFF5F3476), Color(0xFFF6D9FF)), // hue 318
    BlockPalette(Color(0xFFFFD8E8), Color(0xFF5E0A40), Color(0xFF7B2557), Color(0xFFFFD8E8)), // hue 351
    BlockPalette(Color(0xFFFFDAD6), Color(0xFF551F1B), Color(0xFF713530), Color(0xFFFFDAD6)), // hue 24
    BlockPalette(Color(0xFFFFDCC4), Color(0xFF4E2600), Color(0xFF6F3900), Color(0xFFFFDCC4)), // hue 57
    BlockPalette(Color(0xFFFFDF91), Color(0xFF3E2E00), Color(0xFF594400), Color(0xFFFFDF91)), // hue 90
    BlockPalette(Color(0xFFD9EA9E), Color(0xFF293500), Color(0xFF3F4C11), Color(0xFFD9EA9E)), // hue 123
    BlockPalette(Color(0xFFBDF2C9), Color(0xFF00391D), Color(0xFF005229), Color(0xFFB4F5C4)), // hue 138
    BlockPalette(Color(0xFFA4F4BA), Color(0xFF00391C), Color(0xFF00522B), Color(0xFFA4F4BA)), // hue 156
    BlockPalette(Color(0xFF5DF9EC), Color(0xFF003733), Color(0xFF00504B), Color(0xFF5DF9EC)), // hue 189
    BlockPalette(Color(0xFFB5EBFF), Color(0xFF003543), Color(0xFF004E60), Color(0xFFB5EBFF)), // hue 222
    BlockPalette(Color(0xFFE2E1FF), Color(0xFF1A1B60), Color(0xFF43447E), Color(0xFFE3E2FF)), // hue 255
)

/** 课程 colorIndex -> (容器色, 内容色)，自动适配亮暗主题。 */
fun courseBlockColors(index: Int, darkTheme: Boolean): Pair<Color, Color> {
    val p = CourseBlockPalettes[(((index % CourseBlockPalettes.size) + CourseBlockPalettes.size) % CourseBlockPalettes.size)]
    return if (darkTheme) p.containerDark to p.onContainerDark
    else p.containerLight to p.onContainerLight
}

/** RGB -> HSV（h 0..360, s 0..1, v 0..1）。 */
private fun rgbToHsv(c: Color): FloatArray {
    val r = c.red; val g = c.green; val b = c.blue
    val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
    val d = mx - mn
    val h = when {
        d == 0f -> 0f
        mx == r -> 60f * (((g - b) / d) % 6f)
        mx == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    val hue = (h + 360f) % 360f
    val s = if (mx == 0f) 0f else d / mx
    return floatArrayOf(hue, s, mx)
}

/**
 * 动态取色下的课程块颜色：以当前 colorScheme 的 primary 色相为基准，
 * 按 30° 间隔旋转生成 12 组容器/内容色调对（与默认十二组同款结构），
 * 使动态取色下仍保持十组颜色区分度，且随壁纸主题联动。
 */
@Composable
fun courseBlockColorsDynamic(index: Int): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    val hsv = rgbToHsv(cs.primary)
    val hue = (hsv[0] + (index % CourseBlockPalettes.size) * 30f) % 360f
    return if (dark) {
        Color.hsv(hue, saturation = 0.40f, value = 0.30f) to
            Color.hsv(hue, saturation = 0.35f, value = 0.90f)
    } else {
        Color.hsv(hue, saturation = 0.45f, value = 0.88f) to
            Color.hsv(hue, saturation = 0.55f, value = 0.20f)
    }
}
