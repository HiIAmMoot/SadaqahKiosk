package com.sadaqah.kiosk.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sadaqah.kiosk.model.Settings
import com.sadaqah.kiosk.*

@Composable
fun ColorSettingRow(
    label: String,
    color: Color,
    colorKey: String,
    onClick: (String) -> Unit,
    settings: Settings
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color(settings.buttonBorderColor),
            fontSize = responsiveSp(14.0)
        )
        // Double border via nested backgrounds: black outer → white inner → color fill
        Box(
            modifier = Modifier
                .height(responsiveDp(50.dp).coerceAtLeast(36.dp))
                .width(responsiveDp(100.dp).coerceAtLeast(72.dp))
                .clip(RoundedCornerShape(responsiveDp(10.dp)))
                .background(Color.Black)
                .padding(responsiveDp(2.5.dp))
                .background(Color.White, RoundedCornerShape(responsiveDp(8.dp)))
                .padding(responsiveDp(2.5.dp))
                .background(color, RoundedCornerShape(responsiveDp(6.dp)))
                .clickable { onClick(colorKey) }
        )
    }
}

@Composable
fun SettingsSection(
    title: String,
    settings: Settings,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            color = Color(settings.buttonBorderColor),
            fontSize = responsiveSp(18.0),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = responsiveDp(8.dp))
        )
        content()
    }
}

@Composable
fun ActionButton(
    text: String,
    color: Color,
    borderColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(responsiveDp(12.dp)),
        border = BorderStroke(responsiveDp(3.dp), borderColor),
        contentPadding = PaddingValues(horizontal = responsiveDp(8.dp), vertical = 0.dp),
        modifier = modifier.height(responsiveDp(70.dp).coerceAtLeast(40.dp))
    ) {
        Text(
            text,
            color = borderColor,
            fontSize = responsiveSp(18.0),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
