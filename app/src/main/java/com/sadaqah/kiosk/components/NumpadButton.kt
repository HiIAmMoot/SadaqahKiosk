package com.sadaqah.kiosk.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sadaqah.kiosk.model.Settings
import com.sadaqah.kiosk.*

@Composable
fun NumpadButton(
    text: String,
    onClick: () -> Unit,
    settings: Settings,
    isSpecial: Boolean = false,
    enabled: Boolean = true
) {
    // Special buttons (⌫, ✓) use inverted colors
    val buttonColor = if (isSpecial) {
        Color(settings.buttonBorderColor)
    } else {
        Color(settings.buttonColor)
    }
    
    val textColor = if (isSpecial) {
        Color(settings.buttonColor)
    } else {
        Color(settings.buttonBorderColor)
    }
    
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = buttonColor,
            disabledContainerColor = buttonColor.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(responsiveDp(16.dp)),
        border = BorderStroke(responsiveDp(4.dp), Color(settings.buttonBorderColor)),
        modifier = Modifier
            .size(responsiveDp(140.dp))
    ) {
        Text(
            text = text,
            color = if (enabled) textColor else textColor.copy(alpha = 0.3f),
            fontSize = responsiveSp(50.0),
            fontWeight = FontWeight.Bold
        )
    }
}
