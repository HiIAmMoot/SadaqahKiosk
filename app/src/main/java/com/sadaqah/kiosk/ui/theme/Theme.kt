package com.sadaqah.kiosk.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

// This app manages all colours through the Settings object.
// Always use the static light scheme — dark mode and dynamic colour
// must never interfere with kiosk UI rendering.
private val ColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

@Composable
fun SadaqahKioskTheme(content: @Composable () -> Unit) {
    val family = appFontFamily()
    MaterialTheme(
        colorScheme = ColorScheme,
        typography = appTypography(family),
    ) {
        // Plain Text(...) calls merge with LocalTextStyle.current rather than
        // pulling from Typography directly, so we have to push the bundled
        // family onto LocalTextStyle too. Without this, screens that build
        // Text(...) without an explicit fontFamily would still resolve through
        // FontFamily.Default and let the OEM font leak in.
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = family),
            content = content
        )
    }
}
