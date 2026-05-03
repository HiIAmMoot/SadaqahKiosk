package com.sadaqah.kiosk.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
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

enum class MaintenanceReason {
    Reinitializing,
    NetworkOutage
}

@Composable
fun MaintenanceScreen(
    settings: Settings
) {
    val strings = rememberStrings()
    
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
            .padding(horizontal = responsiveDp(50.dp)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = strings.maintenance,
            color = Color(settings.buttonBorderColor),
            fontSize = responsiveSp(80.0),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        
        Spacer(modifier = Modifier.height(responsiveDp(40.dp)))
        
        Text(
            text = strings.pleaseWait,
            color = Color(settings.buttonBorderColor),
            fontSize = responsiveSp(40.0),
            textAlign = TextAlign.Center,
        )
        
        Spacer(modifier = Modifier.height(responsiveDp(60.dp)))
        
        CircularProgressIndicator(
            modifier = Modifier.size(responsiveDp(100.dp)),
            color = Color(settings.buttonBorderColor),
            strokeWidth = responsiveDp(8.dp)
        )
    }
}
