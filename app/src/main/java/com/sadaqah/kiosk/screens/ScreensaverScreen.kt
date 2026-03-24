package com.sadaqah.kiosk.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil3.compose.rememberAsyncImagePainter
import com.sadaqah.kiosk.R
import com.sadaqah.kiosk.model.Settings
import com.sadaqah.kiosk.rememberStrings
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

object ScreensaverStyle {
    const val HUE_DRIFT  = "hue_drift"
    const val RIPPLE     = "ripple"
    const val SCROLLING  = "scrolling"
    const val PARTICLES  = "particles"

    val all = listOf(SCROLLING, HUE_DRIFT, RIPPLE, PARTICLES)
    fun label(style: String) = when (style) {
        RIPPLE    -> "Ripples"
        SCROLLING -> "Scrolling"
        PARTICLES -> "Particles"
        else      -> "Hue + Orbs"
    }
}

@Composable
fun ScreensaverScreen(style: String, settings: Settings, onTouch: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onTouch() } }
    ) {
        when (style) {
            ScreensaverStyle.RIPPLE    -> RipplePulsesScreensaver(settings)
            ScreensaverStyle.SCROLLING -> ScrollingPatternScreensaver(settings)
            ScreensaverStyle.PARTICLES -> ParticleStarfieldScreensaver(settings)
            else                       -> HueOrbsScreensaver(settings)
        }
        ScreensaverOverlay(settings, onDismiss = onTouch)
    }
}

// ── Combined: Hue Drift + Floating Orbs ──────────────────────────────────────
// Background colour drifts ±8° through the hue wheel on a 90s cycle while
// brightness breathes softly on a 40s cycle. Six semi-transparent orbs derived
// from the theme colours drift across the screen using async Lissajous paths.

@Composable
fun HueOrbsScreensaver(settings: Settings) {
    val t  = rememberInfiniteTransition(label = "hue_orbs")
    val c1 = Color(settings.buttonColor)
    val c2 = Color(settings.buttonBorderColor)

    val hue    by t.animateFloat(0f, 1f,    infiniteRepeatable(tween(90_000, easing = LinearEasing), RepeatMode.Reverse), label = "hue")
    val bright by t.animateFloat(0f, 1f,    infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Reverse), label = "bright")

    val o1x by t.animateFloat(0.10f, 0.82f, infiniteRepeatable(tween(17_300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "o1x")
    val o1y by t.animateFloat(0.08f, 0.72f, infiniteRepeatable(tween(13_100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "o1y")
    val o2x by t.animateFloat(0.55f, 0.12f, infiniteRepeatable(tween(21_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "o2x")
    val o2y by t.animateFloat(0.78f, 0.15f, infiniteRepeatable(tween(18_700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "o2y")
    val o3x by t.animateFloat(0.88f, 0.28f, infiniteRepeatable(tween(15_400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "o3x")
    val o3y by t.animateFloat(0.38f, 0.88f, infiniteRepeatable(tween(22_600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "o3y")
    val o4x by t.animateFloat(0.22f, 0.75f, infiniteRepeatable(tween(29_100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "o4x")
    val o4y by t.animateFloat(0.91f, 0.08f, infiniteRepeatable(tween(16_800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "o4y")
    val o5x by t.animateFloat(0.72f, 0.04f, infiniteRepeatable(tween(11_700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "o5x")
    val o5y by t.animateFloat(0.18f, 0.62f, infiniteRepeatable(tween(31_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "o5y")
    val o6x by t.animateFloat(0.42f, 0.92f, infiniteRepeatable(tween(23_400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "o6x")
    val o6y by t.animateFloat(0.52f, 0.04f, infiniteRepeatable(tween(14_900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "o6y")

    val bgColor = hueShiftColor(Color(settings.backgroundColor), hue, bright)
    val patColor = hueShiftColor(Color(settings.patternColor), hue, bright)

    Box(Modifier.fillMaxSize().background(bgColor)) {
        Image(
            painter = painterResource(id = R.drawable.pattern),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.tint(patColor, blendMode = BlendMode.Modulate)
        )
        Canvas(Modifier.fillMaxSize()) {
            data class Orb(val fx: Float, val fy: Float, val r: Float, val color: Color, val a: Float)
            listOf(
                Orb(o1x, o1y,  90f, c1, 0.10f),
                Orb(o2x, o2y, 115f, c2, 0.08f),
                Orb(o3x, o3y,  78f, c1, 0.12f),
                Orb(o4x, o4y, 135f, c2, 0.07f),
                Orb(o5x, o5y,  65f, c1, 0.14f),
                Orb(o6x, o6y, 102f, c2, 0.09f),
            ).forEach { orb ->
                val cx = orb.fx * size.width
                val cy = orb.fy * size.height
                val rPx = orb.r.dp.toPx()
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(orb.color.copy(alpha = orb.a), orb.color.copy(alpha = 0f)),
                        center = Offset(cx, cy),
                        radius = rPx
                    ),
                    radius = rPx,
                    center = Offset(cx, cy)
                )
            }
        }
    }
}

// ── Ripple Pulses ─────────────────────────────────────────────────────────────

@Composable
fun RipplePulsesScreensaver(settings: Settings) {
    val t        = rememberInfiniteTransition(label = "ripple")
    val dim  by t.animateFloat(0f, 0.20f, infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Reverse), label = "dim")
    val time by t.animateFloat(0f, 1f,    infiniteRepeatable(tween(3_500,  easing = LinearEasing)), label = "time")

    Box(Modifier.fillMaxSize().background(dimColor(Color(settings.backgroundColor), dim))) {
        Image(
            painter = painterResource(id = R.drawable.pattern),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.tint(Color(settings.patternColor), blendMode = BlendMode.Modulate)
        )
        Canvas(Modifier.fillMaxSize()) {
            val centre    = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = maxOf(size.width, size.height) * 0.85f
            val strokePx  = 3.dp.toPx()
            val ringColor = Color(settings.buttonBorderColor)
            repeat(4) { i ->
                val phase = ((time + i * 0.25f) % 1f)
                drawCircle(
                    color  = ringColor.copy(alpha = (1f - phase) * 0.65f),
                    radius = phase * maxRadius,
                    center = centre,
                    style  = Stroke(width = strokePx)
                )
            }
        }
    }
}

// ── Scrolling Pattern ─────────────────────────────────────────────────────────

@Composable
fun ScrollingPatternScreensaver(settings: Settings) {
    val t       = rememberInfiniteTransition(label = "scroll")
    val dim     by t.animateFloat(0f, 0.20f, infiniteRepeatable(tween(55_000, easing = LinearEasing), RepeatMode.Reverse), label = "dim")
    val scrollX by t.animateFloat(0f, 1f,    infiniteRepeatable(tween(25_000, easing = LinearEasing), RepeatMode.Reverse), label = "sx")
    val scrollY by t.animateFloat(0f, 1f,    infiniteRepeatable(tween(19_000, easing = LinearEasing), RepeatMode.Reverse), label = "sy")

    Box(Modifier.fillMaxSize().background(dimColor(Color(settings.backgroundColor), dim))) {
        Image(
            painter = painterResource(id = R.drawable.pattern),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.35f
                    scaleY = 1.35f
                    translationX = (scrollX - 0.5f) * size.width  * 0.28f
                    translationY = (scrollY - 0.5f) * size.height * 0.28f
                },
            contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.tint(Color(settings.patternColor), blendMode = BlendMode.Modulate)
        )
    }
}

// ── Particle Starfield ────────────────────────────────────────────────────────

@Composable
fun ParticleStarfieldScreensaver(settings: Settings) {
    val t    = rememberInfiniteTransition(label = "particles")
    val dim  by t.animateFloat(0f,  0.25f, infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Reverse), label = "dim")
    val time by t.animateFloat(0f, 50f,    infiniteRepeatable(tween(50_000, easing = LinearEasing), RepeatMode.Reverse), label = "time")

    Box(Modifier.fillMaxSize().background(dimColor(Color(settings.backgroundColor), dim))) {
        Image(
            painter = painterResource(id = R.drawable.pattern),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.tint(Color(settings.patternColor), blendMode = BlendMode.Modulate)
        )
        Canvas(Modifier.fillMaxSize()) {
            val particleColor = Color(settings.buttonBorderColor)
            repeat(28) { i ->
                val seed  = i * 137.508f
                val freqX = 0.030f + (i % 7) * 0.008f
                val freqY = 0.025f + (i % 5) * 0.011f
                val x     = (sin((time * freqX + seed).toDouble())          * 0.5 + 0.5).toFloat() * size.width
                val y     = (cos((time * freqY + seed * 1.618f).toDouble()) * 0.5 + 0.5).toFloat() * size.height
                val rPx   = (4f + (i % 5) * 3f).dp.toPx()
                val alpha = 0.20f + (i % 5) * 0.08f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(particleColor.copy(alpha = alpha), particleColor.copy(alpha = 0f)),
                        center = Offset(x, y),
                        radius = rPx * 1.5f
                    ),
                    radius = rPx * 1.5f,
                    center = Offset(x, y)
                )
            }
        }
    }
}

// ── Message + Logo overlay (applied to all styles) ───────────────────────────
// Messages cycle with a fade-in (1.2s) → hold (5.5s) → fade-out (0.8s) rhythm.
// The logo (if set) is shown below the message, centred on screen.

@Composable
fun ScreensaverOverlay(settings: Settings, onDismiss: () -> Unit) {
    val strings = rememberStrings()
    val customMessage = settings.screensaverCustomMessage.trim()
    val hasCustom = customMessage.isNotBlank()
    val cycleMessages = settings.screensaverCycleMessages
    val defaultMessages = strings.screensaverDefaultMessages

    if (!hasCustom && settings.logoUri.isNullOrBlank() && defaultMessages.isEmpty()) return

    val alpha = remember { Animatable(0f) }
    var currentMessage by remember { mutableStateOf(if (hasCustom) customMessage else defaultMessages.firstOrNull() ?: "") }

    val config = LocalConfiguration.current
    val scale = (config.screenWidthDp / 800f).coerceIn(0.5f, 1.5f)
    val messageFontSize = 68f * scale
    val textColor = Color(settings.buttonBorderColor)

    val totalDurationMs = settings.screensaverDurationSec * 1000L
    val customHoldMs    = settings.screensaverCustomMessageHoldSec * 1000L
    val messageHoldMs   = settings.screensaverMessageHoldSec * 1000L

    // Always return to donation screen after the configured duration
    LaunchedEffect(Unit) {
        delay(totalDurationMs)
        alpha.animateTo(0f, tween(800))
        onDismiss()
    }

    LaunchedEffect(hasCustom, cycleMessages) {
        if (!cycleMessages) {
            if (hasCustom) currentMessage = customMessage
            alpha.animateTo(1f, tween(1200))
            // Holds until the total-duration timeout above fires
        } else {
            // Cycling: custom first (configurable hold), then rotate defaults, repeat
            while (true) {
                if (hasCustom) {
                    currentMessage = customMessage
                    alpha.animateTo(1f, tween(1200))
                    delay(customHoldMs)
                    alpha.animateTo(0f, tween(800))
                }
                for (msg in defaultMessages) {
                    currentMessage = msg
                    alpha.animateTo(1f, tween(1200))
                    delay(messageHoldMs)
                    alpha.animateTo(0f, tween(800))
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(40.dp),
            modifier = Modifier.padding(horizontal = 48.dp)
        ) {
            if (!settings.logoUri.isNullOrBlank()) {
                val logoUri = remember(settings.logoUri) { settings.logoUri.toUri() }
                Image(
                    painter = rememberAsyncImagePainter(logoUri),
                    contentDescription = "Logo",
                    modifier = Modifier
                        .size((400f * scale).dp)
                        .clip(RoundedCornerShape(20.dp)),
                    contentScale = ContentScale.Fit
                )
            }

            if (currentMessage.isNotBlank()) {
                Text(
                    text = currentMessage,
                    color = textColor,
                    fontSize = messageFontSize.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = (messageFontSize * 1.2f).sp
                )
            }
        }
    }
}

// ── Shared colour helpers ─────────────────────────────────────────────────────

private fun hueShiftColor(base: Color, hueProgress: Float, brightnessProgress: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(base.toArgb(), hsv)
    hsv[0] = (hsv[0] + hueProgress * 16f - 8f + 360f) % 360f
    hsv[2] = (hsv[2] - brightnessProgress * 0.12f).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv)).copy(alpha = base.alpha)
}

private fun dimColor(base: Color, amount: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(base.toArgb(), hsv)
    hsv[2] = (hsv[2] - amount).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv)).copy(alpha = base.alpha)
}
