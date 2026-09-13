package com.joeyos.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.joeyos.app.R

/**
 * JoeyOS's one typeface: Inter, bundled (res/font/inter.ttf, SIL Open Font License, see
 * assets/licences/ofl-inter.txt) so it looks the same on every device. It's a variable font, so
 * the one file gives each weight JoeyOS uses. Every text style and every screen takes it from
 * here: change it in this one place. (Chosen over the system Roboto + monospace, which made long
 * descriptions tiring to read on a handheld.)
 */
@OptIn(ExperimentalTextApi::class)
val JoeyFont = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { w ->
        Font(R.font.inter, w, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight)))
    }
)

private val Base = Typography()

/** Material's type scale with Inter throughout, so text without its own style uses it too. */
val Typography = Base.copy(
    displayLarge = Base.displayLarge.copy(fontFamily = JoeyFont),
    displayMedium = Base.displayMedium.copy(fontFamily = JoeyFont),
    displaySmall = Base.displaySmall.copy(fontFamily = JoeyFont),
    headlineLarge = Base.headlineLarge.copy(fontFamily = JoeyFont),
    headlineMedium = Base.headlineMedium.copy(fontFamily = JoeyFont),
    headlineSmall = Base.headlineSmall.copy(fontFamily = JoeyFont),
    titleLarge = TextStyle(fontFamily = JoeyFont, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleMedium = Base.titleMedium.copy(fontFamily = JoeyFont),
    titleSmall = Base.titleSmall.copy(fontFamily = JoeyFont),
    bodyLarge = TextStyle(fontFamily = JoeyFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = Base.bodyMedium.copy(fontFamily = JoeyFont),
    bodySmall = Base.bodySmall.copy(fontFamily = JoeyFont),
    labelLarge = Base.labelLarge.copy(fontFamily = JoeyFont),
    labelMedium = Base.labelMedium.copy(fontFamily = JoeyFont),
    labelSmall = Base.labelSmall.copy(fontFamily = JoeyFont),
)
