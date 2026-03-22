package com.sadaqah.kiosk.screens

import androidx.compose.foundation.BorderStroke
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
import com.sadaqah.kiosk.model.Settings
import com.sadaqah.kiosk.R
import com.sadaqah.kiosk.rememberStrings
import com.sadaqah.kiosk.responsiveDp
import com.sadaqah.kiosk.responsiveSp
import com.sadaqah.kiosk.components.NumpadButton

@Composable
fun CustomAmountNumpadScreen(
    amount: String,
    onAmountChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    settings: Settings
) {
    val strings = rememberStrings()
    val currencySymbol = when (settings.currency) {
        "USD" -> "$"
        "GBP" -> "£"
        else -> "€"
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
                .padding(responsiveDp(32.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = strings.enterAmount,
                color = Color(settings.buttonBorderColor),
                fontSize = responsiveSp(60.0),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(responsiveDp(32.dp)))

            Text(
                text = if (amount.isEmpty()) "${currencySymbol}0" else "$currencySymbol$amount",
                color = Color(settings.buttonBorderColor),
                fontSize = responsiveSp(120.0),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Text(
                text = strings.minMaxAmount,
                color = Color(settings.buttonBorderColor).copy(alpha = 0.7f),
                fontSize = responsiveSp(24.0),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(responsiveDp(48.dp)))

            Column(
                verticalArrangement = Arrangement.spacedBy(responsiveDp(16.dp)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                for (row in 0..2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(responsiveDp(16.dp))) {
                        for (col in 1..3) {
                            val number = row * 3 + col
                            NumpadButton(
                                text = number.toString(),
                                onClick = {
                                    // Max 4 digits enforces the 5000 upper bound
                                    if (amount.length < 4) onAmountChange(amount + number)
                                },
                                settings = settings
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(responsiveDp(16.dp))) {
                    NumpadButton(
                        text = "⌫",
                        onClick = { if (amount.isNotEmpty()) onAmountChange(amount.dropLast(1)) },
                        settings = settings,
                        isSpecial = true
                    )
                    NumpadButton(
                        text = "0",
                        onClick = {
                            // Disallow leading zeros (e.g. "05")
                            if (amount.length < 4 && amount.isNotEmpty()) onAmountChange(amount + "0")
                        },
                        settings = settings,
                        enabled = amount.isNotEmpty()
                    )
                    NumpadButton(
                        text = "✓",
                        onClick = onConfirm,
                        settings = settings,
                        isSpecial = true
                    )
                }
            }

            Spacer(modifier = Modifier.height(responsiveDp(32.dp)))

            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(containerColor = Color(settings.buttonColor)),
                shape = RoundedCornerShape(responsiveDp(16.dp)),
                border = BorderStroke(responsiveDp(4.dp), Color(settings.buttonBorderColor)),
                modifier = Modifier
                    .width(responsiveDp(300.dp))
                    .height(responsiveDp(70.dp))
            ) {
                Text(
                    strings.cancel,
                    color = Color(settings.buttonBorderColor),
                    fontSize = responsiveSp(24.0),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
