package com.sadaqah.kiosk

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Reference device: Lenovo M9 tab (~800dp wide in landscape).
// Scale is clamped to [0.5, 1.5] so UI stays usable on small phones.
@Composable
fun responsiveDp(base: Dp): Dp {
    val config = LocalConfiguration.current
    val scale = (config.screenWidthDp / 800f).coerceIn(0.5f, 1.5f)
    return (base.value * scale).dp
}

@Composable
fun responsiveSp(base: Double): TextUnit {
    val config = LocalConfiguration.current
    val scale = (config.screenWidthDp / 800f).coerceIn(0.5f, 1.5f)
    return (base * scale).sp
}

@Composable
fun donationGridColumns(): Int {
    val config = LocalConfiguration.current
    return if (config.screenWidthDp >= 600) 3 else 2
}

