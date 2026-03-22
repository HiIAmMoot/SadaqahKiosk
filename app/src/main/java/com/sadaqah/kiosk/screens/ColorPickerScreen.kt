package com.sadaqah.kiosk.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.sadaqah.kiosk.model.Settings
import com.github.skydoves.colorpicker.compose.*
import com.sadaqah.kiosk.ColorHistory
import com.sadaqah.kiosk.R
import com.sadaqah.kiosk.rememberStrings
import com.sadaqah.kiosk.responsiveDp
import com.sadaqah.kiosk.responsiveSp

@Composable
fun ColorPickerScreen(
    initialColor: Color,
    label: String,
    onColorSelected: (String, Color) -> Unit,
    onBack: (String) -> Unit,
    settings: Settings
) {
    val strings = rememberStrings()
    val controller = rememberColorPickerController()
    var newColor by remember { mutableStateOf(initialColor) }
    val historyColors by ColorHistory.colors.collectAsState()

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
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(responsiveDp(24.dp))
        ) {
            HsvColorPicker(
                initialColor = initialColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(responsiveDp(400.dp))
                    .padding(responsiveDp(10.dp)),
                controller = controller,
                onColorChanged = { colorEnvelope: ColorEnvelope ->
                    newColor = colorEnvelope.color
                }
            )

            AlphaSlider(
                initialColor = newColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(responsiveDp(10.dp))
                    .height(responsiveDp(35.dp)),
                controller = controller,
            )

            BrightnessSlider(
                initialColor = newColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(responsiveDp(10.dp))
                    .height(responsiveDp(35.dp)),
                controller = controller,
            )

            AlphaTile(
                modifier = Modifier
                    .size(responsiveDp(60.dp))
                    .padding(start = responsiveDp(10.dp)),
                controller = controller
            )

            Spacer(modifier = Modifier.height(responsiveDp(12.dp)))

            ColorSwatchRow(
                label = strings.suggestedColors,
                colors = ColorHistory.suggested,
                settings = settings,
                onColorPick = { colorLong ->
                    val picked = Color(colorLong)
                    newColor = picked
                    controller.selectByColor(picked, false)
                }
            )

            if (historyColors.isNotEmpty()) {
                Spacer(modifier = Modifier.height(responsiveDp(12.dp)))
                ColorSwatchRow(
                    label = strings.recentColors,
                    colors = historyColors,
                    settings = settings,
                    onColorPick = { colorLong ->
                        val picked = Color(colorLong)
                        newColor = picked
                        controller.selectByColor(picked, false)
                    }
                )
            }

            Spacer(modifier = Modifier.height(responsiveDp(12.dp)))

            RgbaInputs(
                color = newColor,
                controller = controller,
                onColorChange = { adjustedColor ->
                    newColor = adjustedColor
                }
            )

            Spacer(modifier = Modifier.height(responsiveDp(16.dp)))

            Row(horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = {
                    ColorHistory.addColor(newColor.toArgb().toLong())
                    onColorSelected(label, newColor)
                    onBack(label)
                }) {
                    Text(strings.apply)
                }
            }
        }
    }
}

@Composable
fun ColorSwatchRow(
    label: String,
    colors: List<Long>,
    settings: Settings,
    onColorPick: (Long) -> Unit
) {
    Column {
        Text(
            text = label,
            color = Color(settings.buttonBorderColor).copy(alpha = 0.7f),
            fontSize = responsiveSp(12.0),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = responsiveDp(4.dp), bottom = responsiveDp(6.dp))
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(responsiveDp(8.dp))
        ) {
            colors.forEach { colorLong ->
                ColorSwatch(
                    colorLong = colorLong,
                    onClick = { onColorPick(colorLong) }
                )
            }
        }
    }
}

@Composable
fun ColorSwatch(
    colorLong: Long,
    onClick: () -> Unit
) {
    // Double border: black outer → white inner → color fill (same pattern as settings swatches)
    Box(
        modifier = Modifier
            .size(responsiveDp(44.dp))
            .clip(RoundedCornerShape(responsiveDp(8.dp)))
            .background(Color.Black)
            .padding(responsiveDp(2.dp))
            .background(Color.White, RoundedCornerShape(responsiveDp(6.dp)))
            .padding(responsiveDp(2.dp))
            .background(
                Color(colorLong and 0xFFFFFFFFL),
                RoundedCornerShape(responsiveDp(4.dp))
            )
            .clickable { onClick() }
    )
}

@Composable
fun RgbaInputs(
    color: Color,
    controller: ColorPickerController,
    onColorChange: (Color) -> Unit
) {
    var r by remember { mutableStateOf(color.red) }
    var g by remember { mutableStateOf(color.green) }
    var b by remember { mutableStateOf(color.blue) }
    var a by remember { mutableStateOf(color.alpha) }

    // Sync with external color changes (from picker or swatches)
    LaunchedEffect(color) {
        r = color.red
        g = color.green
        b = color.blue
        a = color.alpha
    }

    fun updateColorFromInputs() {
        val updated = Color(
            r.coerceIn(0f, 1f),
            g.coerceIn(0f, 1f),
            b.coerceIn(0f, 1f),
            a.coerceIn(0f, 1f)
        )
        controller.selectByColor(updated, false)
        onColorChange(updated)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            "R" to r,
            "G" to g,
            "B" to b,
            "A" to a
        ).forEachIndexed { index, (label, value) ->
            var textFieldValue by remember(value) {
                mutableStateOf(
                    TextFieldValue(
                        text = ((value * 255).toInt()).toString(),
                        selection = TextRange(((value * 255).toInt()).toString().length)
                    )
                )
            }

            OutlinedTextField(
                value = textFieldValue,
                onValueChange = { newValue ->
                    if (newValue.text.isEmpty()) {
                        textFieldValue = newValue.copy(text = "")
                        when (index) {
                            0 -> r = 0f
                            1 -> g = 0f
                            2 -> b = 0f
                            3 -> a = 0f
                        }
                        updateColorFromInputs()
                    } else {
                        val intVal = newValue.text.toIntOrNull()?.coerceIn(0, 255)
                        if (intVal != null) {
                            textFieldValue = newValue
                            when (index) {
                                0 -> r = intVal / 255f
                                1 -> g = intVal / 255f
                                2 -> b = intVal / 255f
                                3 -> a = intVal / 255f
                            }
                            updateColorFromInputs()
                        }
                    }
                },
                label = { Text(label) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword
                )
            )
        }
    }
}
