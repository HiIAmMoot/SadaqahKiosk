package com.sadaqah.kiosk.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.rememberAsyncImagePainter
import com.sadaqah.kiosk.model.Settings
import com.sadaqah.kiosk.R
import com.sadaqah.kiosk.donationGridColumns
import com.sadaqah.kiosk.rememberStrings
import com.sadaqah.kiosk.responsiveDp
import com.sadaqah.kiosk.responsiveSp

@Composable
fun DonationGridScreen(
    onAmountSelected: (String) -> Unit,
    onSettingsClick: () -> Unit,
    settings: Settings,
    onShowCustomAmountScreen: (Boolean) -> Unit,
    onReinitSumUp: () -> Unit,
    isNetworkAvailable: Boolean,
    onNetworkWarningClick: () -> Unit
) {
    val amounts = listOf("5", "10", "15", "20", "25", "30", "40", "50", "75", "100", "150")
    val columns = donationGridColumns()
    val strings = rememberStrings()
    val currencySymbol = getCurrencySymbol(settings.currency)

    val backgroundColor  = Color(settings.backgroundColor)
    val patternColor     = Color(settings.patternColor)
    val buttonColor      = Color(settings.buttonColor)
    val buttonBorderColor = Color(settings.buttonBorderColor)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Image(
            painter = painterResource(id = R.drawable.pattern),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.tint(
                patternColor,
                blendMode = BlendMode.Modulate
            )
        )

        Button(
            onClick = onSettingsClick,
            modifier = Modifier
                .padding(top = responsiveDp(16.dp), start = responsiveDp(8.dp))
                .size(responsiveDp(96.dp)),
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = buttonBorderColor
            ),
        ) {
            Image(
                painter = painterResource(id = R.drawable.gear_icon),
                contentDescription = "Instellingen",
                modifier = Modifier.fillMaxSize(),
                colorFilter = ColorFilter.tint(
                    buttonBorderColor,
                    blendMode = BlendMode.Modulate
                )
            )
        }

        if (!isNetworkAvailable) {
            Button(
                onClick = onNetworkWarningClick,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = responsiveDp(16.dp), end = responsiveDp(8.dp))
                    .size(responsiveDp(96.dp)),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.9f)),
                shape = RoundedCornerShape(responsiveDp(8.dp)),
                border = BorderStroke(responsiveDp(2.dp), Color.White)
            ) {
                Text(
                    text = "⚠",
                    color = Color.White,
                    fontSize = responsiveSp(56.0),
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Sits below the network warning when visible (96dp box + 16dp top pad + 8dp gap)
        val refreshTopPadding = if (!isNetworkAvailable) responsiveDp(120.dp) else responsiveDp(16.dp)
        IconButton(
            onClick = onReinitSumUp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = refreshTopPadding, end = responsiveDp(8.dp))
                .size(responsiveDp(96.dp))
        ) {
            Text(
                text = "⟳",
                color = buttonBorderColor.copy(alpha = 0.35f),
                fontSize = responsiveSp(72.0)
            )
        }

Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = responsiveDp(24.dp), vertical = responsiveDp(32.dp)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = strings.chooseAmount,
                color = buttonBorderColor,
                fontSize = responsiveSp(80.0),
                lineHeight = responsiveSp(80.0),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = responsiveDp(180.dp), bottom = responsiveDp(20.dp))
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(horizontal = responsiveDp(40.dp), vertical = responsiveDp(20.dp)),
                horizontalArrangement = Arrangement.spacedBy(responsiveDp(20.dp)),
                verticalArrangement = Arrangement.spacedBy(responsiveDp(20.dp)),
                modifier = Modifier.padding(bottom = responsiveDp(1.dp))
            ) {
                items(amounts.size) { index ->
                    DonationButton(
                        amount = amounts[index],
                        onClick = { onAmountSelected(amounts[index]) },
                        buttonColor = buttonColor,
                        buttonBorderColor = buttonBorderColor,
                        currencySymbol = currencySymbol
                    )
                }

                item {
                    CustomAmountButton(
                        onClick = { onShowCustomAmountScreen(true) },
                        buttonColor = buttonColor,
                        buttonBorderColor = buttonBorderColor
                    )
                }
            }

            if (!settings.logoUri.isNullOrBlank()) {
                val logoUri = remember(settings.logoUri) {
                    settings.logoUri.toUri()
                }
                Image(
                    painter = rememberAsyncImagePainter(logoUri),
                    contentDescription = "Logo",
                    modifier = Modifier
                        .size(responsiveDp(200.dp))
                        .clip(RoundedCornerShape(responsiveDp(12.dp))),
                    contentScale = ContentScale.Crop
                )
            } else {
                Spacer(modifier = Modifier.height(responsiveDp(200.dp)))
            }
            Spacer(modifier = Modifier.height(responsiveDp(50.dp)))
        }

        if (settings.testMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xFFFF6F00))
                    .padding(vertical = responsiveDp(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = strings.testModeActive,
                    color = Color.White,
                    fontSize = responsiveSp(18.0),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun DonationButton(
    amount: String,
    onClick: () -> Unit,
    buttonColor: Color,
    buttonBorderColor: Color,
    currencySymbol: String
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
        shape = RoundedCornerShape(responsiveDp(16.dp)),
        border = BorderStroke(responsiveDp(6.dp), buttonBorderColor),
        modifier = Modifier
            .fillMaxWidth()
            .height(responsiveDp(123.dp))
    ) {
        Text("$currencySymbol$amount", fontSize = responsiveSp(50.0), fontWeight = FontWeight.Bold, color = buttonBorderColor)
    }
}

fun getCurrencySymbol(currencyCode: String): String = when (currencyCode) {
    "USD" -> "$"
    "GBP" -> "£"
    else  -> "€"
}

@Composable
fun CustomAmountButton(
    onClick: () -> Unit,
    buttonColor: Color,
    buttonBorderColor: Color
) {
    val strings = rememberStrings()

    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
        shape = RoundedCornerShape(responsiveDp(16.dp)),
        border = BorderStroke(responsiveDp(6.dp), buttonBorderColor),
        modifier = Modifier
            .fillMaxWidth()
            .height(responsiveDp(123.dp))
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                strings.customAmountLine1,
                fontSize = responsiveSp(25.0),
                fontWeight = FontWeight.Bold,
                color = buttonBorderColor
            )
            Text(
                strings.customAmountLine2,
                fontSize = responsiveSp(25.0),
                fontWeight = FontWeight.Bold,
                color = buttonBorderColor
            )
        }
    }
}