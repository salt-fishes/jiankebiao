package com.example.composeapp.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * 应用动效中枢（Expressive 风格）。
 *
 * material3 1.4.0 稳定线的 MotionScheme/MaterialExpressiveTheme 是 internal，
 * 公开版在 1.5 线；升 1.5 后本文件整体替换为官方 MotionScheme 即可，调用点不动。
 * 命名对齐 MotionScheme：位移/尺寸等「空间」属性用弹簧，透明度等「效果」属性用短时值。
 */
object AppMotion {

    /** 空间默认：中等弹簧、轻微回弹（页签滑移、面板展开、图标选中缩放）。 */
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** 空间快速：硬弹簧无回弹（胶囊拖拽吸附、行内小元素）。 */
    fun <T> spatialFast(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /** 效果默认：透明度/遮罩类属性的常规时长。 */
    fun <T> effects(): FiniteAnimationSpec<T> = tween(220)

    /** 效果快速：淡入淡出的短时长。 */
    fun <T> effectsFast(): FiniteAnimationSpec<T> = tween(150)
}
