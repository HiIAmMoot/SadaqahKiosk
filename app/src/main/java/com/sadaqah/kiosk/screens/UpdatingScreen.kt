package com.sadaqah.kiosk.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.BlendMode
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
import com.sadaqah.kiosk.update.UpdateState

@Composable
fun UpdatingScreen(
    settings: Settings,
    state: UpdateState
) {
    val strings = rememberStrings()
    val border = Color(settings.buttonBorderColor)

    val subtitle = when (state) {
        is UpdateState.Downloading -> "${strings.updateDownloading} — ${state.progressPct}%"
        is UpdateState.Installing -> strings.updateInstalling
        is UpdateState.ReadyToInstall -> strings.updateInstalling
        is UpdateState.Checking -> strings.checkForUpdates
        else -> strings.pleaseWait
    }
    val versionLine = when (state) {
        is UpdateState.Downloading -> "v${state.target.version}"
        is UpdateState.Installing -> "v${state.target.version}"
        is UpdateState.ReadyToInstall -> "v${state.target.version}"
        else -> null
    }

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
                .padding(horizontal = responsiveDp(60.dp), vertical = responsiveDp(60.dp)),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = strings.updateInProgress,
                color = border,
                fontSize = responsiveSp(56.0),
                lineHeight = responsiveSp(64.0),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(responsiveDp(28.dp)))

            Text(
                text = subtitle,
                color = border,
                fontSize = responsiveSp(24.0),
                lineHeight = responsiveSp(30.0),
                textAlign = TextAlign.Center
            )

            if (versionLine != null) {
                Spacer(modifier = Modifier.height(responsiveDp(8.dp)))
                Text(
                    text = versionLine,
                    color = border.copy(alpha = 0.7f),
                    fontSize = responsiveSp(20.0),
                    lineHeight = responsiveSp(24.0),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(responsiveDp(48.dp)))

            when (state) {
                is UpdateState.Downloading -> LinearProgressIndicator(
                    progress = { state.progressPct / 100f },
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(responsiveDp(10.dp)),
                    color = border,
                    trackColor = border.copy(alpha = 0.2f)
                )
                else -> CircularProgressIndicator(
                    modifier = Modifier.size(responsiveDp(80.dp)),
                    color = border,
                    strokeWidth = responsiveDp(6.dp)
                )
            }
        }
    }
}
