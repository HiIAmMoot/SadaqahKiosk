package com.sadaqah.kiosk.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
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
import com.sadaqah.kiosk.update.ReleaseInfo
import com.sadaqah.kiosk.update.SemVer

@Composable
fun UpdateAvailableConfirmScreen(
    settings: Settings,
    currentVersion: SemVer,
    currentVersionReleasedAt: String,
    target: ReleaseInfo,
    onInstallNow: () -> Unit,
    onLater: () -> Unit
) {
    val strings = rememberStrings()
    val border = Color(settings.buttonBorderColor)
    val buttonColor = Color(settings.buttonColor)

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
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(responsiveDp(40.dp)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = strings.updateAvailableTitle,
            color = border,
            fontSize = responsiveSp(60.0),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = responsiveDp(20.dp), bottom = responsiveDp(20.dp))
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = responsiveDp(40.dp)),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            VersionBlock(
                label = strings.currentVersion,
                version = "v$currentVersion",
                releasedAt = currentVersionReleasedAt,
                releasedLabel = strings.updateReleasedOn,
                color = border
            )
            VersionBlock(
                label = strings.targetVersion,
                version = "v${target.version}",
                releasedAt = target.publishedAtIso.take(10),
                releasedLabel = strings.updateReleasedOn,
                color = border
            )
        }

        Spacer(modifier = Modifier.height(responsiveDp(24.dp)))

        Text(
            text = strings.updateChangelog,
            color = border,
            fontSize = responsiveSp(20.0),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Start)
        )
        Spacer(modifier = Modifier.height(responsiveDp(8.dp)))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(BorderStroke(responsiveDp(2.dp), border), RoundedCornerShape(responsiveDp(12.dp)))
                .padding(responsiveDp(12.dp))
        ) {
            val scroll = rememberScrollState()
            Text(
                text = target.body.ifBlank { "—" },
                color = border,
                fontSize = responsiveSp(15.0),
                modifier = Modifier.verticalScroll(scroll)
            )
        }

        Spacer(modifier = Modifier.height(responsiveDp(16.dp)))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(responsiveDp(16.dp))
        ) {
            Button(
                onClick = onLater,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                shape = RoundedCornerShape(responsiveDp(12.dp)),
                border = BorderStroke(responsiveDp(3.dp), border),
                modifier = Modifier.weight(1f).height(responsiveDp(60.dp))
            ) {
                Text(strings.updateLater, color = border, fontSize = responsiveSp(18.0), fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onInstallNow,
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                shape = RoundedCornerShape(responsiveDp(12.dp)),
                border = BorderStroke(responsiveDp(3.dp), border),
                modifier = Modifier.weight(1f).height(responsiveDp(60.dp))
            ) {
                Text(strings.updateNow, color = border, fontSize = responsiveSp(18.0), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun VersionBlock(
    label: String,
    version: String,
    releasedAt: String,
    releasedLabel: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = color.copy(alpha = 0.7f), fontSize = responsiveSp(18.0))
        Spacer(modifier = Modifier.height(responsiveDp(4.dp)))
        Text(version, color = color, fontSize = responsiveSp(32.0), fontWeight = FontWeight.Bold)
        if (releasedAt.isNotBlank()) {
            Spacer(modifier = Modifier.height(responsiveDp(2.dp)))
            Text("$releasedLabel $releasedAt", color = color.copy(alpha = 0.7f), fontSize = responsiveSp(14.0))
        }
    }
}
