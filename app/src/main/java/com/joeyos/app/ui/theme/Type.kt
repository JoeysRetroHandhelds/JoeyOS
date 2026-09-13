package com.joeyos.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
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

/**
 * Line spacing as a multiple of each text's own size (em), not a fixed height. The old fixed
 * 24sp (sized for 16sp body text) was inherited by every 10-12sp description that only set its
 * size, so their lines sat twice as far apart as they should (found on device, with Inter).
 */
private fun TextStyle.joey(lineHeight: Float) = copy(fontFamily = JoeyFont, lineHeight = lineHeight.em)

/** Material's type scale with Inter throughout, so text without its own style uses it too. */
val Typography = Base.copy(
    displayLarge = Base.displayLarge.joey(1.15f),
    displayMedium = Base.displayMedium.joey(1.15f),
    displaySmall = Base.displaySmall.joey(1.2f),
    headlineLarge = Base.headlineLarge.joey(1.2f),
    headlineMedium = Base.headlineMedium.joey(1.2f),
    headlineSmall = Base.headlineSmall.joey(1.25f),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp).joey(1.25f),
    titleMedium = Base.titleMedium.joey(1.3f),
    titleSmall = Base.titleSmall.joey(1.3f),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp).joey(1.35f),
    bodyMedium = Base.bodyMedium.joey(1.35f),
    bodySmall = Base.bodySmall.joey(1.35f),
    labelLarge = Base.labelLarge.joey(1.3f),
    labelMedium = Base.labelMedium.joey(1.3f),
    labelSmall = Base.labelSmall.joey(1.3f),
)
