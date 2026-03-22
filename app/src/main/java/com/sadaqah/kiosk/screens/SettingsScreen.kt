package com.sadaqah.kiosk.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import com.sadaqah.kiosk.model.Settings
import com.sadaqah.kiosk.*
import com.sadaqah.kiosk.R
import com.sadaqah.kiosk.components.ActionButton
import com.sadaqah.kiosk.components.ColorSettingRow
import com.sadaqah.kiosk.components.SettingsSection
import com.sadaqah.kiosk.screens.ScreensaverStyle

@Composable
fun SettingsScreen(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    openColorPicker: (String) -> Unit,
    onResetApp: () -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onExportSettings: (Boolean) -> String,
    onImportSettings: (String) -> Boolean,
    connectCardReader: () -> Unit,
    isLoggedIn: Boolean,
    isPinned: Boolean,
    onUnpinApp: () -> Unit,
    onPinApp: () -> Unit,
    onShowSetupStatus: () -> Unit,
    onActivateScreensaver: () -> Unit,
    onTestModeChange: (Boolean) -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val strings = rememberStrings()
    var kioskNameInput by remember { mutableStateOf(settings.kioskName ?: "") }

    // Track current language, currency and toggles locally for UI updates
    var currentLanguage by remember { mutableStateOf(settings.language) }
    var currentCurrency by remember { mutableStateOf(settings.currency) }
    var useArabicThankYou by remember { mutableStateOf(settings.useArabicThankYou) }
    var currentScreensaverStyle by remember { mutableStateOf(settings.screensaverStyle) }
    var screensaverCustomMessageInput by remember { mutableStateOf(settings.screensaverCustomMessage) }
    var screensaverCycleMessages by remember { mutableStateOf(settings.screensaverCycleMessages) }
    var screensaverIdleTimeoutInput by remember { mutableStateOf(settings.screensaverIdleTimeoutSec.toString()) }
    var screensaverDurationInput by remember { mutableStateOf(settings.screensaverDurationSec.toString()) }
    var screensaverCustomMessageHoldInput by remember { mutableStateOf(settings.screensaverCustomMessageHoldSec.toString()) }
    var screensaverMessageHoldInput by remember { mutableStateOf(settings.screensaverMessageHoldSec.toString()) }
    var thankYouDurationInput by remember { mutableStateOf(settings.thankYouDurationSec.toString()) }

    var usedUri: Uri = if (!settings.logoUri.isNullOrBlank()) {
        remember(settings.logoUri) { settings.logoUri.toUri() }
    } else {
        "".toUri()
    }

    var showLogoutDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var includeAffiliateKey by remember { mutableStateOf(false) }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }

    val exportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            try {
                pendingExportJson?.let { jsonData ->
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(jsonData.toByteArray())
                    }

                    // Also copy to clipboard for convenience
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Settings", jsonData)
                    clipboard.setPrimaryClip(clip)

                    Toast.makeText(
                        context,
                        strings.settingsExportedAndCopied,
                        Toast.LENGTH_LONG
                    ).show()
                }
                pendingExportJson = null
                showExportDialog = false
                includeAffiliateKey = false
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "${strings.exportFailed}${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                Log.e("ExportSettings", "Error writing file", e)
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(it, takeFlags)
            } catch (e: SecurityException) {
                Log.e("ImagePicker", "Could not persist URI permission: ${e.message}")
            }
            usedUri = it
            onSettingsChange(settings.copy(logoUri = usedUri.toString()))
            onRefresh()
        }
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
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = strings.settings,
                color = Color(settings.buttonBorderColor),
                fontSize = responsiveSp(70.0),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = responsiveDp(16.dp), bottom = responsiveDp(16.dp))
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(responsiveDp(32.dp))
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(responsiveDp(20.dp))
                ) {
                    SettingsSection(title = strings.kioskName, settings = settings) {
                        OutlinedTextField(
                            value = kioskNameInput,
                            onValueChange = { kioskNameInput = it },
                            placeholder = { Text(strings.kioskNamePlaceholder, fontSize = responsiveSp(14.0)) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(settings.buttonBorderColor),
                                unfocusedBorderColor = Color(settings.buttonBorderColor),
                                cursorColor = Color(settings.buttonBorderColor),
                                focusedTextColor = Color(settings.buttonBorderColor),
                                unfocusedTextColor = Color(settings.buttonBorderColor)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    SettingsSection(title = strings.logoImage, settings = settings) {
                        Button(
                            onClick = { imagePickerLauncher.launch(arrayOf("image/png", "image/jpeg")) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(settings.buttonColor)),
                            shape = RoundedCornerShape(responsiveDp(12.dp)),
                            border = BorderStroke(responsiveDp(3.dp), Color(settings.buttonBorderColor)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(responsiveDp(60.dp).coerceAtLeast(40.dp))
                        ) {
                            Text(strings.selectLogo, color = Color(settings.buttonBorderColor), fontSize = responsiveSp(16.0))
                        }
                    }

                    SettingsSection(title = strings.language, settings = settings) {
                        Column(verticalArrangement = Arrangement.spacedBy(responsiveDp(8.dp))) {
                            Language.entries.chunked(4).forEach { rowLanguages ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(responsiveDp(8.dp)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    rowLanguages.forEach { lang ->
                                        ActionButton(
                                            text = "${lang.flag} ${lang.shortCode}",
                                            color = if (currentLanguage == lang.code) Color(settings.buttonColor) else Color.Gray,
                                            borderColor = Color(settings.buttonBorderColor),
                                            onClick = {
                                                currentLanguage = lang.code
                                                TranslationManager.setLanguage(lang)
                                                onSettingsChange(settings.copy(language = lang.code))
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    repeat(4 - rowLanguages.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    SettingsSection(title = strings.currency, settings = settings) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(responsiveDp(12.dp)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            ActionButton(
                                text = "€",
                                color = if (currentCurrency == "EUR") Color(settings.buttonColor) else Color.Gray,
                                borderColor = Color(settings.buttonBorderColor),
                                onClick = {
                                    currentCurrency = "EUR"
                                    onSettingsChange(settings.copy(currency = "EUR"))
                                },
                                modifier = Modifier.weight(1f)
                            )
                            ActionButton(
                                text = "$",
                                color = if (currentCurrency == "USD") Color(settings.buttonColor) else Color.Gray,
                                borderColor = Color(settings.buttonBorderColor),
                                onClick = {
                                    currentCurrency = "USD"
                                    onSettingsChange(settings.copy(currency = "USD"))
                                },
                                modifier = Modifier.weight(1f)
                            )
                            ActionButton(
                                text = "£",
                                color = if (currentCurrency == "GBP") Color(settings.buttonColor) else Color.Gray,
                                borderColor = Color(settings.buttonBorderColor),
                                onClick = {
                                    currentCurrency = "GBP"
                                    onSettingsChange(settings.copy(currency = "GBP"))
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Connect Card Reader Button - DISABLED if not logged in
                    SettingsSection(title = strings.cardReader, settings = settings) {
                        Button(
                            onClick = { connectCardReader() },
                            enabled = isLoggedIn,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isLoggedIn) Color(settings.buttonColor) else Color.Gray,
                                disabledContainerColor = Color.Gray
                            ),
                            shape = RoundedCornerShape(responsiveDp(12.dp)),
                            border = BorderStroke(responsiveDp(3.dp), Color(settings.buttonBorderColor)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(responsiveDp(60.dp).coerceAtLeast(40.dp))
                        ) {
                            Text(
                                text = strings.connectCardReaderButton,
                                color = if (isLoggedIn) Color(settings.buttonBorderColor) else Color.White,
                                fontSize = responsiveSp(16.0),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (!isLoggedIn) {
                            Spacer(modifier = Modifier.height(responsiveDp(4.dp)))
                            Text(
                                text = strings.loginFirstToConnect,
                                color = Color(settings.buttonBorderColor).copy(alpha = 0.7f),
                                fontSize = responsiveSp(11.0),
                                lineHeight = responsiveSp(13.0)
                            )
                        }
                    }

                    // Thank You Toggle
                    SettingsSection(title = strings.thankYouToggleLabel, settings = settings) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = strings.thankYouToggleDesc,
                                color = Color(settings.buttonBorderColor).copy(alpha = 0.7f),
                                fontSize = responsiveSp(10.0),
                                lineHeight = responsiveSp(12.0),
                                modifier = Modifier.weight(1f).padding(end = responsiveDp(8.dp))
                            )
                            Switch(
                                checked = useArabicThankYou,
                                onCheckedChange = { enabled ->
                                    useArabicThankYou = enabled
                                    onSettingsChange(settings.copy(useArabicThankYou = enabled))
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(settings.buttonColor),
                                    checkedTrackColor = Color(settings.buttonBorderColor),
                                    uncheckedThumbColor = Color.Gray,
                                    uncheckedTrackColor = Color.DarkGray
                                )
                            )
                        }
                    }

                    SettingsSection(title = strings.screensaver, settings = settings) {
                        Column(verticalArrangement = Arrangement.spacedBy(responsiveDp(8.dp))) {
                            ScreensaverStyle.all.chunked(3).forEach { rowStyles ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(responsiveDp(8.dp)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    rowStyles.forEach { style ->
                                        ActionButton(
                                            text = ScreensaverStyle.label(style),
                                            color = if (currentScreensaverStyle == style) Color(settings.buttonColor) else Color.Gray,
                                            borderColor = Color(settings.buttonBorderColor),
                                            onClick = {
                                                currentScreensaverStyle = style
                                                onSettingsChange(settings.copy(screensaverStyle = style))
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    repeat(3 - rowStyles.size) { Spacer(modifier = Modifier.weight(1f)) }
                                }
                            }
                            ActionButton(
                                text = strings.previewNow,
                                color = Color(settings.buttonColor),
                                borderColor = Color(settings.buttonBorderColor),
                                onClick = onActivateScreensaver,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(responsiveDp(4.dp)))
                            Text(
                                text = strings.screensaverCustomMessage,
                                color = Color(settings.buttonBorderColor).copy(alpha = 0.7f),
                                fontSize = responsiveSp(12.0)
                            )
                            OutlinedTextField(
                                value = screensaverCustomMessageInput,
                                onValueChange = { screensaverCustomMessageInput = it },
                                placeholder = {
                                    Text(
                                        "e.g. Support Masjid Arrahman",
                                        fontSize = responsiveSp(12.0),
                                        color = Color(settings.buttonBorderColor).copy(alpha = 0.4f)
                                    )
                                },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(settings.buttonBorderColor),
                                    unfocusedBorderColor = Color(settings.buttonBorderColor),
                                    cursorColor = Color(settings.buttonBorderColor),
                                    focusedTextColor = Color(settings.buttonBorderColor),
                                    unfocusedTextColor = Color(settings.buttonBorderColor)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (screensaverCustomMessageInput.isNotBlank()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = strings.screensaverCycleMessages,
                                        color = Color(settings.buttonBorderColor).copy(alpha = 0.7f),
                                        fontSize = responsiveSp(12.0),
                                        modifier = Modifier.weight(1f).padding(end = responsiveDp(8.dp))
                                    )
                                    Switch(
                                        checked = screensaverCycleMessages,
                                        onCheckedChange = { screensaverCycleMessages = it },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color(settings.buttonColor),
                                            checkedTrackColor = Color(settings.buttonBorderColor),
                                            uncheckedThumbColor = Color.Gray,
                                            uncheckedTrackColor = Color.DarkGray
                                        )
                                    )
                                }
                            }
                        }
                    }

                    SettingsSection(title = strings.colors, settings = settings) {
                        Column(verticalArrangement = Arrangement.spacedBy(responsiveDp(12.dp))) {
                            ColorSettingRow(strings.background, Color(settings.backgroundColor), "backgroundColor", openColorPicker, settings)
                            ColorSettingRow(strings.pattern, Color(settings.patternColor), "patternColor", openColorPicker, settings)
                            ColorSettingRow(strings.buttons, Color(settings.buttonColor), "buttonColor", openColorPicker, settings)
                            ColorSettingRow(strings.textBorder, Color(settings.buttonBorderColor), "buttonBorderColor", openColorPicker, settings)
                        }
                    }

                    SettingsSection(title = strings.timers, settings = settings) {
                        Column(verticalArrangement = Arrangement.spacedBy(responsiveDp(8.dp))) {
                            TimerInputRow(strings.screensaverIdleTimeout, screensaverIdleTimeoutInput, strings.minutes, { screensaverIdleTimeoutInput = it }, settings)
                            TimerInputRow(strings.screensaverDuration, screensaverDurationInput, strings.minutes, { screensaverDurationInput = it }, settings)
                            TimerInputRow(strings.screensaverCustomMessageHold, screensaverCustomMessageHoldInput, strings.seconds, { screensaverCustomMessageHoldInput = it }, settings)
                            TimerInputRow(strings.screensaverMessageHold, screensaverMessageHoldInput, strings.seconds, { screensaverMessageHoldInput = it }, settings)
                            TimerInputRow(strings.thankYouDuration, thankYouDurationInput, strings.seconds, { thankYouDurationInput = it }, settings)
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(responsiveDp(20.dp)),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!settings.logoUri.isNullOrBlank()) {
                        val logoUri = remember(settings.logoUri) { settings.logoUri.toUri() }
                        Card(
                            modifier = Modifier.size(responsiveDp(300.dp)),
                            shape = RoundedCornerShape(responsiveDp(16.dp)),
                            border = BorderStroke(responsiveDp(3.dp), Color(settings.buttonBorderColor))
                        ) {
                            AsyncImage(
                                model = logoUri,
                                contentDescription = strings.logoImage,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                onError = { errorState ->
                                    Log.e("LogoImage", "Image load failed", errorState.result.throwable)
                                }
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(responsiveDp(300.dp))
                                .border(
                                    responsiveDp(3.dp),
                                    Color(settings.buttonBorderColor).copy(alpha = 0.3f),
                                    RoundedCornerShape(responsiveDp(16.dp))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                strings.noLogoSelected,
                                color = Color(settings.buttonBorderColor).copy(alpha = 0.5f),
                                fontSize = responsiveSp(16.0),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Column(
                        verticalArrangement = Arrangement.spacedBy(responsiveDp(12.dp)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Test mode at the top
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(responsiveDp(12.dp)))
                                .background(Color(0xFFFF6F00).copy(alpha = 0.15f))
                                .padding(horizontal = responsiveDp(16.dp), vertical = responsiveDp(10.dp)),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = strings.testMode,
                                color = Color(0xFFFF6F00),
                                fontSize = responsiveSp(14.0),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f).padding(end = responsiveDp(8.dp))
                            )
                            Switch(
                                checked = settings.testMode,
                                onCheckedChange = { onTestModeChange(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFFFF6F00),
                                    uncheckedThumbColor = Color.Gray,
                                    uncheckedTrackColor = Color.DarkGray
                                )
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(responsiveDp(12.dp)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            ActionButton(
                                text = strings.setupStatus,
                                color = Color(settings.buttonColor),
                                borderColor = Color(settings.buttonBorderColor),
                                onClick = onShowSetupStatus,
                                modifier = Modifier.weight(1f)
                            )
                            ActionButton(
                                text = if (isPinned) strings.unpinApp else strings.pinApp,
                                color = if (isPinned) Color(0xFF8B0000) else Color(settings.buttonColor),
                                borderColor = Color(settings.buttonBorderColor),
                                onClick = { if (isPinned) onUnpinApp() else onPinApp() },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(responsiveDp(12.dp)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            ActionButton(
                                text = strings.exportSettings,
                                color = Color(settings.buttonColor),
                                borderColor = Color(settings.buttonBorderColor),
                                onClick = { showExportDialog = true },
                                modifier = Modifier.weight(1f)
                            )
                            ActionButton(
                                text = strings.importSettings,
                                color = Color(settings.buttonColor),
                                borderColor = Color(settings.buttonBorderColor),
                                onClick = { showImportDialog = true },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        ActionButton(
                            text = strings.saveAndBack,
                            color = Color(settings.buttonColor),
                            borderColor = Color(settings.buttonBorderColor),
                            onClick = {
                                onSettingsChange(settings.copy(
                                    logoUri = usedUri.toString(),
                                    kioskName = kioskNameInput,
                                    language = currentLanguage,
                                    currency = currentCurrency,
                                    screensaverCustomMessage = screensaverCustomMessageInput.trim(),
                                    screensaverCycleMessages = screensaverCycleMessages,
                                    screensaverIdleTimeoutSec = screensaverIdleTimeoutInput.toIntOrNull()?.coerceIn(30, 3600) ?: 300,
                                    screensaverDurationSec = screensaverDurationInput.toIntOrNull()?.coerceIn(60, 7200) ?: 600,
                                    screensaverCustomMessageHoldSec = screensaverCustomMessageHoldInput.toIntOrNull()?.coerceIn(10, 600) ?: 120,
                                    screensaverMessageHoldSec = screensaverMessageHoldInput.toIntOrNull()?.coerceIn(3, 60) ?: 6,
                                    thankYouDurationSec = thankYouDurationInput.toIntOrNull()?.coerceIn(1, 30) ?: 3
                                ))
                                onBack()
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        ActionButton(
                            text = strings.logOut,
                            color = Color.Red,
                            borderColor = Color.White,
                            onClick = { showLogoutDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        )

                        ActionButton(
                            text = strings.resetApp,
                            color = Color.Red,
                            borderColor = Color.White,
                            onClick = onResetApp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    if (showExportDialog) {
        ExportDialog(
            strings = strings,
            settings = settings,
            includeAffiliateKey = includeAffiliateKey,
            onIncludeKeyChange = { includeAffiliateKey = it },
            onConfirm = {
                val jsonData = onExportSettings(includeAffiliateKey)
                pendingExportJson = jsonData
                exportFileLauncher.launch("kiosk_settings.json")
            },
            onDismiss = {
                showExportDialog = false
                includeAffiliateKey = false
            }
        )
    }

    if (showImportDialog) {
        ImportDialog(
            strings = strings,
            settings = settings,
            onImport = { jsonInput ->
                val success = onImportSettings(jsonInput)
                if (success) {
                    Toast.makeText(context, strings.settingsImportedSuccessfully, Toast.LENGTH_SHORT).show()
                    onRefresh()
                    showImportDialog = false
                } else {
                    Toast.makeText(context, strings.failedToImportSettings, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showImportDialog = false }
        )
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            containerColor = Color(settings.backgroundColor),
            title = {
                Text(
                    strings.logOutConfirmTitle,
                    color = Color.Red,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    strings.logOutConfirmMessage,
                    color = Color(settings.buttonBorderColor)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutDialog = false
                        onLogout()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text(strings.logOut, color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Button(
                    onClick = { showLogoutDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                ) {
                    Text(strings.cancel, color = Color.White)
                }
            }
        )
    }
}

@Composable
fun TimerInputRow(
    label: String,
    value: String,
    unit: String,
    onValueChange: (String) -> Unit,
    settings: Settings
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(responsiveDp(8.dp)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            color = Color(settings.buttonBorderColor).copy(alpha = 0.7f),
            fontSize = responsiveSp(12.0),
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = value,
            onValueChange = { if (it.length <= 5 && it.all { c -> c.isDigit() }) onValueChange(it) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(settings.buttonBorderColor),
                unfocusedBorderColor = Color(settings.buttonBorderColor),
                cursorColor = Color(settings.buttonBorderColor),
                focusedTextColor = Color(settings.buttonBorderColor),
                unfocusedTextColor = Color(settings.buttonBorderColor)
            ),
            modifier = Modifier.width(responsiveDp(80.dp))
        )
        Text(
            text = unit,
            color = Color(settings.buttonBorderColor).copy(alpha = 0.7f),
            fontSize = responsiveSp(12.0)
        )
    }
}

@Composable
fun ExportDialog(
    strings: Strings,
    settings: Settings,
    includeAffiliateKey: Boolean,
    onIncludeKeyChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(settings.backgroundColor),
        title = {
            Text(
                strings.exportTitle,
                color = Color(settings.buttonBorderColor),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    strings.exportMessage,
                    color = Color(settings.buttonBorderColor)
                )
                Spacer(modifier = Modifier.height(responsiveDp(16.dp)))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = includeAffiliateKey,
                        onCheckedChange = onIncludeKeyChange
                    )
                    Text(
                        strings.includeAffiliateKey,
                        color = Color(settings.buttonBorderColor)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color(settings.buttonColor)),
                border = BorderStroke(responsiveDp(2.dp), Color(settings.buttonBorderColor))
            ) {
                Text(strings.export, color = Color(settings.buttonBorderColor))
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
            ) {
                Text(strings.cancel, color = Color.White)
            }
        }
    )
}

@Composable
fun ImportDialog(
    strings: Strings,
    settings: Settings,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var importJsonInput by remember { mutableStateOf("") }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(importJsonInput) {
        if (importJsonInput.isNotBlank()) {
            validationError = try {
                com.google.gson.Gson().fromJson(importJsonInput, Map::class.java)
                null // Valid JSON
            } catch (e: Exception) {
                strings.invalidJsonFormat
            }
        } else {
            validationError = null
        }
    }

    val jsonFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val cursor = context.contentResolver.query(it, null, null, null, null)
                val filename = cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIndex = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) c.getString(nameIndex) else null
                    } else null
                }

                val inputStream = context.contentResolver.openInputStream(it)
                val jsonContent = inputStream?.bufferedReader()?.use { reader -> reader.readText() }

                if (!jsonContent.isNullOrBlank()) {
                    importJsonInput = jsonContent
                    selectedFileName = filename ?: "unknown.json"

                    Toast.makeText(
                        context,
                        "${strings.fileLoaded}$selectedFileName",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        strings.fileIsEmpty,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                val errorMsg = "${strings.failedToReadFile}${e.message}"
                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                Log.e("ImportDialog", "Error reading JSON file", e)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(settings.backgroundColor),
        title = {
            Text(
                strings.importTitle,
                color = Color(settings.buttonBorderColor),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    strings.importMessage,
                    color = Color(settings.buttonBorderColor)
                )
                Spacer(modifier = Modifier.height(responsiveDp(12.dp)))

                Button(
                    onClick = { jsonFilePickerLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(settings.buttonColor)),
                    shape = RoundedCornerShape(responsiveDp(8.dp)),
                    border = BorderStroke(responsiveDp(2.dp), Color(settings.buttonBorderColor)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = strings.browseForJsonFile,
                        color = Color(settings.buttonBorderColor),
                        fontSize = responsiveSp(14.0)
                    )
                }

                if (selectedFileName != null) {
                    Spacer(modifier = Modifier.height(responsiveDp(8.dp)))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "📄 $selectedFileName",
                            color = Color(settings.buttonBorderColor),
                            fontSize = responsiveSp(12.0),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                importJsonInput = ""
                                selectedFileName = null
                            },
                            modifier = Modifier.size(responsiveDp(24.dp))
                        ) {
                            Text("✕", color = Color.Red, fontSize = responsiveSp(16.0))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(responsiveDp(12.dp)))

                Text(
                    text = strings.orPasteJsonBelow,
                    color = Color(settings.buttonBorderColor).copy(alpha = 0.7f),
                    fontSize = responsiveSp(12.0)
                )

                Spacer(modifier = Modifier.height(responsiveDp(8.dp)))

                OutlinedTextField(
                    value = importJsonInput,
                    onValueChange = {
                        importJsonInput = it
                        selectedFileName = null // Clear filename if user edits manually
                    },
                    placeholder = { Text(strings.pasteJsonHere) },
                    maxLines = 5,
                    isError = validationError != null && importJsonInput.isNotBlank(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = if (validationError != null && importJsonInput.isNotBlank())
                            Color.Red
                        else
                            Color(settings.buttonBorderColor),
                        unfocusedBorderColor = if (validationError != null && importJsonInput.isNotBlank())
                            Color.Red
                        else
                            Color(settings.buttonBorderColor),
                        cursorColor = Color(settings.buttonBorderColor),
                        focusedTextColor = Color(settings.buttonBorderColor),
                        unfocusedTextColor = Color(settings.buttonBorderColor),
                        errorBorderColor = Color.Red
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (validationError != null && importJsonInput.isNotBlank()) {
                    Spacer(modifier = Modifier.height(responsiveDp(8.dp)))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⚠️ ",
                            fontSize = responsiveSp(14.0)
                        )
                        Text(
                            text = validationError!!,
                            color = Color.Red,
                            fontSize = responsiveSp(12.0),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (validationError == null && importJsonInput.isNotBlank() && importJsonInput.length > 10) {
                    Spacer(modifier = Modifier.height(responsiveDp(8.dp)))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "✅ ",
                            fontSize = responsiveSp(14.0)
                        )
                        Text(
                            text = strings.validJson,
                            color = Color(0xFF00AA00),
                            fontSize = responsiveSp(12.0),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (validationError == null && importJsonInput.isNotBlank()) {
                        isImporting = true
                        onImport(importJsonInput)
                    }
                },
                enabled = importJsonInput.isNotBlank() && validationError == null && !isImporting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(settings.buttonColor),
                    disabledContainerColor = Color.Gray
                ),
                border = BorderStroke(responsiveDp(2.dp), Color(settings.buttonBorderColor))
            ) {
                if (isImporting) {
                    Text(
                        text = strings.importing,
                        color = Color(settings.buttonBorderColor)
                    )
                } else {
                    Text(strings.import, color = Color(settings.buttonBorderColor))
                }
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                enabled = !isImporting,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
            ) {
                Text(strings.cancel, color = Color.White)
            }
        }
    )
}