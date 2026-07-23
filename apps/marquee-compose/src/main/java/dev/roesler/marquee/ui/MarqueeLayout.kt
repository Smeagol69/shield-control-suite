package dev.roesler.marquee.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class MarqueeSizeClass {
    COMPACT_TV,
    EXPANDED_TV,
}

data class MarqueeLayout(
    val sizeClass: MarqueeSizeClass,
    val outerHorizontalPadding: Dp,
    val outerVerticalPadding: Dp,
    val headerLogoSize: Dp,
    val headerContentGap: Dp,
    val posterWidth: Dp,
    val personWidth: Dp,
    val gridMinimumWidth: Dp,
    val heroHeight: Dp,
    val providerTabWidth: Dp,
    val providerTabHeight: Dp,
    val sectionTitleSize: TextUnit,
    val sectionSubtitleSize: TextUnit,
    val controlHorizontalPadding: Dp,
    val controlVerticalPadding: Dp,
) {
    val compact: Boolean
        get() = sizeClass == MarqueeSizeClass.COMPACT_TV
}

internal fun marqueeSizeClass(widthDp: Float, heightDp: Float): MarqueeSizeClass =
    if (widthDp <= 1_100f || heightDp <= 650f) {
        MarqueeSizeClass.COMPACT_TV
    } else {
        MarqueeSizeClass.EXPANDED_TV
    }

internal fun marqueeLayout(sizeClass: MarqueeSizeClass): MarqueeLayout =
    when (sizeClass) {
        MarqueeSizeClass.COMPACT_TV -> MarqueeLayout(
            sizeClass = sizeClass,
            outerHorizontalPadding = 28.dp,
            outerVerticalPadding = 14.dp,
            headerLogoSize = 34.dp,
            headerContentGap = 10.dp,
            posterWidth = 104.dp,
            personWidth = 112.dp,
            gridMinimumWidth = 120.dp,
            heroHeight = 210.dp,
            providerTabWidth = 136.dp,
            providerTabHeight = 46.dp,
            sectionTitleSize = 16.sp,
            sectionSubtitleSize = 9.sp,
            controlHorizontalPadding = 12.dp,
            controlVerticalPadding = 7.dp,
        )

        MarqueeSizeClass.EXPANDED_TV -> MarqueeLayout(
            sizeClass = sizeClass,
            outerHorizontalPadding = 40.dp,
            outerVerticalPadding = 24.dp,
            headerLogoSize = 40.dp,
            headerContentGap = 16.dp,
            posterWidth = 120.dp,
            personWidth = 132.dp,
            gridMinimumWidth = 138.dp,
            heroHeight = 245.dp,
            providerTabWidth = 152.dp,
            providerTabHeight = 54.dp,
            sectionTitleSize = 18.sp,
            sectionSubtitleSize = 10.sp,
            controlHorizontalPadding = 15.dp,
            controlVerticalPadding = 9.dp,
        )
    }

val LocalMarqueeLayout = staticCompositionLocalOf {
    marqueeLayout(MarqueeSizeClass.COMPACT_TV)
}
