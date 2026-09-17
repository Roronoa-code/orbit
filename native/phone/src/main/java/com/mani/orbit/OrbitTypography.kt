package com.mani.orbit

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The identical Manrope variable font embedded in the approved web design, bundled for offline use.
@OptIn(ExperimentalTextApi::class)
private val OrbitFont = FontFamily((200..800 step 100).map { weight ->
    Font(R.font.orbit_manrope, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
})
private fun TextStyle.orbit() = copy(fontFamily = OrbitFont, letterSpacing = 0.sp)
internal val OrbitTypography = Typography().let { type ->
    Typography(
        displayLarge = type.displayLarge.orbit(), displayMedium = type.displayMedium.orbit(), displaySmall = type.displaySmall.orbit(),
        headlineLarge = type.headlineLarge.orbit(), headlineMedium = type.headlineMedium.orbit(), headlineSmall = type.headlineSmall.orbit(),
        titleLarge = type.titleLarge.orbit(), titleMedium = type.titleMedium.orbit(), titleSmall = type.titleSmall.orbit(),
        bodyLarge = type.bodyLarge.orbit().copy(fontSize = 14.sp, lineHeight = 21.sp),
        bodyMedium = type.bodyMedium.orbit().copy(fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall = type.bodySmall.orbit().copy(fontSize = 12.sp, lineHeight = 18.sp),
        labelLarge = type.labelLarge.orbit(), labelMedium = type.labelMedium.orbit(), labelSmall = type.labelSmall.orbit(),
    )
}
