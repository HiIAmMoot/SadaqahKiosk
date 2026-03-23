package com.sadaqah.kiosk.screens

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

@Composable
fun SetupStatusScreen(
    isNetworkAvailable: Boolean,
    isBluetoothEnabled: Boolean,
    isLoggedIn: Boolean,
    isCardReaderConnected: Boolean,
    settings: Settings,
    onBack: () -> Unit,
    onConfigureWifi: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onDisableBluetooth: () -> Unit,
    showBack: Boolean = true
) {
    val strings = rememberStrings()
    val kioskNameSet = !settings.kioskName.isNullOrBlank()

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
                .padding(responsiveDp(40.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = strings.setupStatus,
                color = Color(settings.buttonBorderColor),
                fontSize = responsiveSp(60.0),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(responsiveDp(40.dp)))

            Column(
                modifier = Modifier.fillMaxWidth(0.65f),
                verticalArrangement = Arrangement.spacedBy(responsiveDp(16.dp))
            ) {
                ChecklistRow("WiFi / Internet", isNetworkAvailable, settings)
                ChecklistRow("Bluetooth", isBluetoothEnabled, settings)
                ChecklistRow(strings.logIn, isLoggedIn, settings)
                ChecklistRow(strings.cardReader, isCardReaderConnected, settings)
                ChecklistRow(strings.kioskName, kioskNameSet, settings)
            }

            Spacer(modifier = Modifier.height(responsiveDp(40.dp)))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(responsiveDp(16.dp))
            ) {
                // WiFi configure button always visible
                Button(
                    onClick = onConfigureWifi,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(settings.buttonColor)),
                    shape = RoundedCornerShape(responsiveDp(16.dp)),
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(responsiveDp(70.dp))
                ) {
                    Text(
                        text = strings.configureWifi,
                        color = Color(settings.buttonBorderColor),
                        fontSize = responsiveSp(22.0),
                        fontWeight = FontWeight.Bold
                    )
                }

                // Bluetooth toggle button — always visible
                Button(
                    onClick = if (isBluetoothEnabled) onDisableBluetooth else onEnableBluetooth,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(settings.buttonColor)),
                    shape = RoundedCornerShape(responsiveDp(16.dp)),
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(responsiveDp(70.dp))
                ) {
                    Text(
                        text = if (isBluetoothEnabled) strings.disableBluetooth else strings.enableBluetooth,
                        color = Color(settings.buttonBorderColor),
                        fontSize = responsiveSp(22.0),
                        fontWeight = FontWeight.Bold
                    )
                }

                if (showBack) {
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(settings.buttonColor)),
                        shape = RoundedCornerShape(responsiveDp(16.dp)),
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .height(responsiveDp(70.dp))
                    ) {
                        Text(
                            text = strings.back,
                            color = Color(settings.buttonBorderColor),
                            fontSize = responsiveSp(22.0),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChecklistRow(label: String, isOk: Boolean, settings: Settings) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color(settings.buttonBorderColor),
            fontSize = responsiveSp(28.0),
            fontWeight = FontWeight.Medium
        )
        Text(
            text = if (isOk) "✓" else "✗",
            color = if (isOk) Color(0xFF4CAF50) else Color(0xFFF44336),
            fontSize = responsiveSp(32.0),
            fontWeight = FontWeight.Bold
        )
    }
}
