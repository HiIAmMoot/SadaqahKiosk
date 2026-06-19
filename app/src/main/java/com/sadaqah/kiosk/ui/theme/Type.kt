@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.sadaqah.kiosk.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.sadaqah.kiosk.Language
import com.sadaqah.kiosk.R
import com.sadaqah.kiosk.TranslationManager

// Latin-script bundled font (Inter). Used for every language except Arabic.
// Bundled rather than relying on FontFamily.Default so OEM-shipped system
// fonts can't drag the kiosk UI into a broken state — that was a real issue
// on at least one production device. Variable-axis weight is resolved at
// 400 (regular) and 700 (bold) via FontVariation.
val InterFontFamily = FontFamily(
    Font(
        resId = R.font.inter_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))
    ),
    Font(
        resId = R.font.inter_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))
    ),
)

// Inter has no Arabic coverage, so for Arabic UI we swap to Noto Naskh Arabic.
// Same bundle-vs-system-fallback reasoning as above.
val NotoNaskhArabicFontFamily = FontFamily(
    Font(
        resId = R.font.noto_naskh_arabic_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))
    ),
    Font(
        resId = R.font.noto_naskh_arabic_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))
    ),
)

/** Bundled font family that matches the currently selected UI language. */
@Composable
fun appFontFamily(): FontFamily {
    val language by TranslationManager.currentLanguage.collectAsState()
    return when (language) {
        Language.ARABIC -> NotoNaskhArabicFontFamily
        else -> InterFontFamily
    }
}

/**
 * Builds a Material3 Typography in which every text-style slot is forced to
 * use [family]. Material3 components pull from named slots (Button → labelLarge,
 * AlertDialog title → headlineSmall, etc.), so leaving any slot on
 * FontFamily.Default would let the OEM font leak back in for that component.
 */
fun appTypography(family: FontFamily): Typography {
    val d = Typography()
    return Typography(
        displayLarge   = d.displayLarge.copy(fontFamily = family),
        displayMedium  = d.displayMedium.copy(fontFamily = family),
        displaySmall   = d.displaySmall.copy(fontFamily = family),
        headlineLarge  = d.headlineLarge.copy(fontFamily = family),
        headlineMedium = d.headlineMedium.copy(fontFamily = family),
        headlineSmall  = d.headlineSmall.copy(fontFamily = family),
        titleLarge     = d.titleLarge.copy(fontFamily = family),
        titleMedium    = d.titleMedium.copy(fontFamily = family),
        titleSmall     = d.titleSmall.copy(fontFamily = family),
        bodyLarge      = d.bodyLarge.copy(fontFamily = family),
        bodyMedium     = d.bodyMedium.copy(fontFamily = family),
        bodySmall      = d.bodySmall.copy(fontFamily = family),
        labelLarge     = d.labelLarge.copy(fontFamily = family),
        labelMedium    = d.labelMedium.copy(fontFamily = family),
        labelSmall     = d.labelSmall.copy(fontFamily = family),
    )
}
