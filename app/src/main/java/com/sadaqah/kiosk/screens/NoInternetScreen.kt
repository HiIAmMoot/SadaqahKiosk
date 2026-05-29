package com.sadaqah.kiosk.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sadaqah.kiosk.R
import com.sadaqah.kiosk.model.Settings
import com.sadaqah.kiosk.rememberStrings
import com.sadaqah.kiosk.responsiveDp
import com.sadaqah.kiosk.responsiveSp

@Composable
fun NoInternetScreen(
    settings: Settings,
    onOpenSettings: () -> Unit
) {
    val strings = rememberStrings()
    val accent = Color(settings.buttonBorderColor)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(settings.backgroundColor))
    ) {
        Image(
            painter = painterResource(id = R.drawable.pattern),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.tint(
                Color(settings.patternColor),
                blendMode = BlendMode.Modulate
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = responsiveDp(40.dp)),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BrokenWifiIcon(
                color = accent,
                size = responsiveDp(220.dp)
            )

            Spacer(modifier = Modifier.height(responsiveDp(40.dp)))

            Text(
                text = strings.noInternetConnection,
                color = accent,
                fontSize = responsiveSp(64.0),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(responsiveDp(60.dp)))

            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(containerColor = Color(settings.buttonColor)),
                shape = RoundedCornerShape(responsiveDp(16.dp)),
                border = BorderStroke(responsiveDp(4.dp), accent),
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(responsiveDp(90.dp))
            ) {
                Text(
                    text = strings.settings,
                    color = accent,
                    fontSize = responsiveSp(28.0),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun BrokenWifiIcon(color: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h * 0.78f
        val stroke = Stroke(width = w * 0.07f, cap = StrokeCap.Round)
        val arcs = listOf(0.85f, 0.62f, 0.39f)

        arcs.forEach { fraction ->
            val arcSize = androidx.compose.ui.geometry.Size(w * fraction, h * fraction)
            val topLeft = Offset(cx - arcSize.width / 2f, cy - arcSize.height / 2f)
            drawArc(
                color = color,
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke
            )
        }

        // Dot at the bottom centre
        drawCircle(
            color = color,
            radius = w * 0.05f,
            center = Offset(cx, cy)
        )

        // Diagonal slash through everything
        val slashStroke = Stroke(
            width = w * 0.09f,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.cornerPathEffect(0f)
        )
        drawLine(
            color = color,
            start = Offset(w * 0.18f, h * 0.18f),
            end = Offset(w * 0.82f, h * 0.82f),
            strokeWidth = slashStroke.width,
            cap = StrokeCap.Round
        )
    }
}
