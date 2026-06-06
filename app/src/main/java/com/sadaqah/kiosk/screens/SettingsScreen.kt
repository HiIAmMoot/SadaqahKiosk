package com.sadaqah.kiosk.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.focus.onFocusChanged
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
    onShowDonationHistory: () -> Unit = {},
    onActivateScreensaver: () -> Unit,
    onTestModeChange: (Boolean) -> Unit,
    onLogout: () -> Unit,
    isNetworkAvailable: Boolean = false,
    isBluetoothEnabled: Boolean = false,
    isCardReaderConnected: Boolean = false,
    currentVersion: String = "",
    latestVersion: String = "",
    updateAvailable: Boolean = false,
    availableReleases: List<com.sadaqah.kiosk.update.ReleaseInfo> = emptyList(),
    scheduledInstallAtMs: Long? = null,
    onCheckForUpdates: () -> Unit = {},
    onUpdateNow: () -> Unit = {}
) {
    val context = LocalContext.current
    val strings = rememberStrings()

    // Auto-save throughout — local input mirrors only exist for text/numeric
    // fields that need validation on blur. Toggles, dropdowns, and selection
    // buttons read directly from `settings` and write through onSettingsChange
    // on every interaction.
    var screensaverIdleTimeoutInput by remember(settings.screensaverIdleTimeoutSec) {
        mutableStateOf(settings.screensaverIdleTimeoutSec.toString())
    }
    var screensaverDurationInput by remember(settings.screensaverDurationSec) {
        mutableStateOf(settings.screensaverDurationSec.toString())
    }
    var thankYouDurationInput by remember(settings.thankYouDurationSec) {
        mutableStateOf(settings.thankYouDurationSec.toString())
    }

    var dangerZoneExpanded by remember { mutableStateOf(false) }
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
            onSettingsChange(settings.copy(logoUri = it.toString()))
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
                // ── LEFT COLUMN ─────────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(responsiveDp(20.dp))
                ) {
                    // ─ Branding ─────────────────────────────────────────────
                    BandHeader(strings.bandBranding, settings)

                    SettingsSection(title = strings.kioskName, settings = settings) {
                        OutlinedTextField(
                            value = settings.kioskName ?: "",
                            onValueChange = { onSettingsChange(settings.copy(kioskName = it)) },
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

                    SettingsSection(title = strings.colors, settings = settings) {
                        Column(verticalArrangement = Arrangement.spacedBy(responsiveDp(12.dp))) {
                            ColorSettingRow(strings.background, Color(settings.backgroundColor), "backgroundColor", openColorPicker, settings)
                            ColorSettingRow(strings.pattern, Color(settings.patternColor), "patternColor", openColorPicker, settings)
                            ColorSettingRow(strings.buttons, Color(settings.buttonColor), "buttonColor", openColorPicker, settings)
                            ColorSettingRow(strings.textBorder, Color(settings.buttonBorderColor), "buttonBorderColor", openColorPicker, settings)
                        }
                    }

                    // ─ Donor experience ─────────────────────────────────────
                    BandHeader(strings.bandDonorExperience, settings)

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
                                            color = if (settings.language == lang.code) Color(settings.buttonColor) else Color.Gray,
                                            borderColor = Color(settings.buttonBorderColor),
                                            onClick = {
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
                            listOf("EUR" to "€", "USD" to "$", "GBP" to "£").forEach { (code, symbol) ->
                                ActionButton(
                                    text = symbol,
                                    color = if (settings.currency == code) Color(settings.buttonColor) else Color.Gray,
                                    borderColor = Color(settings.buttonBorderColor),
                                    onClick = { onSettingsChange(settings.copy(currency = code)) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    SettingsSection(title = strings.thankYouToggleLabel, settings = settings) {
                        ToggleRow(
                            description = strings.thankYouToggleDesc,
                            checked = settings.useArabicThankYou,
                            onChange = { onSettingsChange(settings.copy(useArabicThankYou = it)) },
                            border = Color(settings.buttonBorderColor),
                            buttonColor = Color(settings.buttonColor)
                        )
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
                                            color = if (settings.screensaverStyle == style) Color(settings.buttonColor) else Color.Gray,
                                            borderColor = Color(settings.buttonBorderColor),
                                            onClick = { onSettingsChange(settings.copy(screensaverStyle = style)) },
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
                                value = settings.screensaverCustomMessage,
                                onValueChange = { onSettingsChange(settings.copy(screensaverCustomMessage = it)) },
                                placeholder = {
                                    Text(
                                        strings.screensaverCustomMessagePlaceholder,
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
                        }
                    }

                    SettingsSection(title = strings.timers, settings = settings) {
                        Column(verticalArrangement = Arrangement.spacedBy(responsiveDp(8.dp))) {
                            TimerInputRow(
                                strings.screensaverIdleTimeout, screensaverIdleTimeoutInput, strings.seconds, settings,
                                onValueChange = { screensaverIdleTimeoutInput = it },
                                onCommit = {
                                    val v = screensaverIdleTimeoutInput.toIntOrNull()?.coerceIn(30, 3600) ?: 300
                                    onSettingsChange(settings.copy(screensaverIdleTimeoutSec = v))
                                    screensaverIdleTimeoutInput = v.toString()
                                }
                            )
                            TimerInputRow(
                                strings.screensaverDuration, screensaverDurationInput, strings.seconds, settings,
                                onValueChange = { screensaverDurationInput = it },
                                onCommit = {
                                    val v = screensaverDurationInput.toIntOrNull()?.coerceIn(60, 7200) ?: 600
                                    onSettingsChange(settings.copy(screensaverDurationSec = v))
                                    screensaverDurationInput = v.toString()
                                }
                            )
                            TimerInputRow(
                                strings.thankYouDuration, thankYouDurationInput, strings.seconds, settings,
                                onValueChange = { thankYouDurationInput = it },
                                onCommit = {
                                    val v = thankYouDurationInput.toIntOrNull()?.coerceIn(1, 30) ?: 3
                                    onSettingsChange(settings.copy(thankYouDurationSec = v))
                                    thankYouDurationInput = v.toString()
                                }
                            )
                        }
                    }

                    // ─ Card reader & connectivity ───────────────────────────
                    BandHeader(strings.bandConnectivity, settings)

                    SettingsSection(title = strings.cardReader, settings = settings) {
                        Column(verticalArrangement = Arrangement.spacedBy(responsiveDp(6.dp))) {
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
                            val status = when {
                                !isLoggedIn -> strings.loginFirstToConnect
                                isCardReaderConnected -> strings.cardReaderConnected
                                else -> strings.cardReaderNotConnected
                            }
                            val statusColor = when {
                                !isLoggedIn -> Color(settings.buttonBorderColor).copy(alpha = 0.7f)
                                isCardReaderConnected -> Color(0xFF2E7D32)
                                else -> Color(settings.buttonBorderColor).copy(alpha = 0.7f)
                            }
                            Text(
                                text = status,
                                color = statusColor,
                                fontSize = responsiveSp(11.0),
                                lineHeight = responsiveSp(13.0)
                            )
                        }
                    }

                    // ─ Updates ──────────────────────────────────────────────
                    BandHeader(strings.updates, settings)

                    UpdateSettingsSection(
                        settings = settings,
                        onSettingsChange = onSettingsChange,
                        currentVersion = currentVersion,
                        latestVersion = latestVersion,
                        updateAvailable = updateAvailable,
                        availableReleases = availableReleases,
                        scheduledInstallAtMs = scheduledInstallAtMs,
                        onCheckForUpdates = onCheckForUpdates,
                        onUpdateNow = onUpdateNow
                    )

                    // ─ Diagnostics ──────────────────────────────────────────
                    BandHeader(strings.bandDiagnostics, settings)

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
                }

                // ── RIGHT COLUMN ────────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(responsiveDp(16.dp)),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!settings.logoUri.isNullOrBlank()) {
                        val logoUri = remember(settings.logoUri) { settings.logoUri.toUri() }
                        Card(
                            modifier = Modifier.size(responsiveDp(220.dp)),
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
                                .size(responsiveDp(220.dp))
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
                                fontSize = responsiveSp(14.0),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    StatusPanel(
                        isNetworkAvailable = isNetworkAvailable,
                        isBluetoothEnabled = isBluetoothEnabled,
                        isCardReaderConnected = isCardReaderConnected,
                        isLoggedIn = isLoggedIn,
                        settings = settings,
                        strings = strings
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    Column(
                        verticalArrangement = Arrangement.spacedBy(responsiveDp(10.dp)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
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

                        ActionButton(
                            text = strings.donationHistory,
                            color = Color(settings.buttonColor),
                            borderColor = Color(settings.buttonBorderColor),
                            onClick = onShowDonationHistory,
                            modifier = Modifier.fillMaxWidth()
                        )

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
                            text = strings.back,
                            color = Color(settings.buttonColor),
                            borderColor = Color(settings.buttonBorderColor),
                            onClick = onBack,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Danger zone — collapsed by default so Log Out / Reset App
                        // need a deliberate reveal tap before they can be touched.
                        Spacer(modifier = Modifier.height(responsiveDp(12.dp)))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { dangerZoneExpanded = !dangerZoneExpanded }
                                .padding(vertical = responsiveDp(4.dp))
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(responsiveDp(1.dp))
                                    .background(Color.Red.copy(alpha = 0.4f))
                            )
                            Text(
                                text = "  ${if (dangerZoneExpanded) "▾" else "▸"} ${strings.dangerZone}  ",
                                color = Color.Red.copy(alpha = 0.8f),
                                fontSize = responsiveSp(11.0),
                                fontWeight = FontWeight.Bold
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(responsiveDp(1.dp))
                                    .background(Color.Red.copy(alpha = 0.4f))
                            )
                        }

                        if (dangerZoneExpanded) {
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
    settings: Settings,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit
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
            modifier = Modifier
                .width(responsiveDp(80.dp))
                .onFocusChanged { state -> if (!state.isFocused) onCommit() }
        )
        Text(
            text = unit,
            color = Color(settings.buttonBorderColor).copy(alpha = 0.7f),
            fontSize = responsiveSp(12.0)
        )
    }
}

@Composable
fun BandHeader(title: String, settings: Settings) {
    val border = Color(settings.buttonBorderColor)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = responsiveDp(8.dp))
    ) {
        Text(
            text = title.uppercase(),
            color = border,
            fontSize = responsiveSp(13.0),
            fontWeight = FontWeight.Black,
            letterSpacing = androidx.compose.ui.unit.TextUnit(1.5f, androidx.compose.ui.unit.TextUnitType.Sp)
        )
        Spacer(modifier = Modifier.width(responsiveDp(12.dp)))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(responsiveDp(1.dp))
                .background(border.copy(alpha = 0.3f))
        )
    }
}

@Composable
fun StatusPanel(
    isNetworkAvailable: Boolean,
    isBluetoothEnabled: Boolean,
    isCardReaderConnected: Boolean,
    isLoggedIn: Boolean,
    settings: Settings,
    strings: Strings
) {
    val border = Color(settings.buttonBorderColor)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(responsiveDp(2.dp), border.copy(alpha = 0.5f)), RoundedCornerShape(responsiveDp(12.dp)))
            .padding(horizontal = responsiveDp(16.dp), vertical = responsiveDp(10.dp)),
        verticalArrangement = Arrangement.spacedBy(responsiveDp(4.dp))
    ) {
        StatusRow(strings.statusNetwork, isNetworkAvailable, border)
        StatusRow(strings.statusBluetooth, isBluetoothEnabled, border)
        StatusRow(strings.statusReader, isCardReaderConnected, border)
        StatusRow(strings.statusLoggedIn, isLoggedIn, border)
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, border: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = border, fontSize = responsiveSp(12.0))
        Text(
            text = if (ok) "✓" else "✗",
            color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828),
            fontWeight = FontWeight.Black,
            fontSize = responsiveSp(16.0)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSettingsSection(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    currentVersion: String,
    latestVersion: String,
    updateAvailable: Boolean,
    availableReleases: List<com.sadaqah.kiosk.update.ReleaseInfo>,
    scheduledInstallAtMs: Long?,
    onCheckForUpdates: () -> Unit,
    onUpdateNow: () -> Unit
) {
    val context = LocalContext.current
    val strings = rememberStrings()
    val border = Color(settings.buttonBorderColor)
    val button = Color(settings.buttonColor)
    var repoUrlInput by remember(settings.updateRepoUrl) { mutableStateOf(settings.updateRepoUrl) }
    var graceDaysInput by remember(settings.autoUpdateGraceDays) {
        mutableStateOf(settings.autoUpdateGraceDays.toString())
    }
    var versionDropdownExpanded by remember { mutableStateOf(false) }

    val targetOptions: List<String> = remember(availableReleases) {
        buildList {
            add("latest")
            addAll(availableReleases.map { it.version.toString() })
        }
    }

    var advancedExpanded by remember { mutableStateOf(false) }
    var repoUrlError by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(responsiveDp(10.dp))) {

        // Current / latest version line
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${strings.currentVersion}: $currentVersion",
                color = border,
                fontSize = responsiveSp(13.0)
            )
            if (latestVersion.isNotBlank() && latestVersion != currentVersion) {
                Text(
                    text = "${strings.latestVersion}: $latestVersion",
                    color = if (updateAvailable) Color(0xFFE53935) else border,
                    fontSize = responsiveSp(13.0),
                    fontWeight = if (updateAvailable) FontWeight.Bold else FontWeight.Normal
                )
            }
        }

        // Scheduled install date/time — only when auto-update is on and target is "latest".
        if (scheduledInstallAtMs != null) {
            val formatted = remember(scheduledInstallAtMs) {
                val fmt = java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.MEDIUM,
                    java.text.DateFormat.SHORT
                )
                fmt.format(java.util.Date(scheduledInstallAtMs))
            }
            Text(
                text = "${strings.updateWillInstallOn} $formatted",
                color = border.copy(alpha = 0.8f),
                fontSize = responsiveSp(12.0),
                fontWeight = FontWeight.Bold
            )
        }

        // Manual check + install buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(responsiveDp(8.dp)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = onCheckForUpdates,
                colors = ButtonDefaults.buttonColors(containerColor = button),
                shape = RoundedCornerShape(responsiveDp(8.dp)),
                border = BorderStroke(responsiveDp(2.dp), border),
                modifier = Modifier.weight(1f).height(responsiveDp(48.dp))
            ) {
                Text(strings.checkForUpdates, color = border, fontSize = responsiveSp(12.0))
            }
            if (updateAvailable) {
                Button(
                    onClick = onUpdateNow,
                    colors = ButtonDefaults.buttonColors(containerColor = button),
                    shape = RoundedCornerShape(responsiveDp(8.dp)),
                    border = BorderStroke(responsiveDp(2.dp), Color(0xFFE53935)),
                    modifier = Modifier.weight(1f).height(responsiveDp(48.dp))
                ) {
                    Text(strings.updateNow, color = border, fontSize = responsiveSp(12.0), fontWeight = FontWeight.Bold)
                }
            }
        }

        ToggleRow(
            label = strings.autoUpdateEnabled,
            description = strings.autoUpdateDesc,
            checked = settings.autoUpdateEnabled,
            onChange = { onSettingsChange(settings.copy(autoUpdateEnabled = it)) },
            border = border,
            buttonColor = button
        )

        // Target version dropdown
        Text(
            text = strings.targetVersion,
            color = border,
            fontSize = responsiveSp(13.0),
            fontWeight = FontWeight.Bold
        )
        ExposedDropdownMenuBox(
            expanded = versionDropdownExpanded,
            onExpandedChange = { versionDropdownExpanded = !versionDropdownExpanded }
        ) {
            val currentLabel = if (settings.autoUpdateTargetVersion == "latest")
                strings.latestVersion
            else
                "v${settings.autoUpdateTargetVersion}"
            OutlinedTextField(
                value = currentLabel,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = versionDropdownExpanded) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = border, unfocusedBorderColor = border,
                    cursorColor = border, focusedTextColor = border, unfocusedTextColor = border
                ),
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = versionDropdownExpanded,
                onDismissRequest = { versionDropdownExpanded = false }
            ) {
                targetOptions.forEach { opt ->
                    DropdownMenuItem(
                        text = {
                            Text(if (opt == "latest") strings.latestVersion else "v$opt")
                        },
                        onClick = {
                            onSettingsChange(settings.copy(autoUpdateTargetVersion = opt))
                            versionDropdownExpanded = false
                        }
                    )
                }
            }
        }
        if (availableReleases.isEmpty()) {
            Text(
                text = strings.checkForUpdates + " →",
                color = border.copy(alpha = 0.6f),
                fontSize = responsiveSp(10.0)
            )
        }

        ToggleRow(
            label = strings.hideUpdatePrompts,
            description = strings.hideUpdatePromptsDesc,
            checked = settings.hideUpdatePrompts,
            onChange = { onSettingsChange(settings.copy(hideUpdatePrompts = it)) },
            border = border,
            buttonColor = button
        )

        // Advanced — collapsed by default; holds the dangerous / rarely-changed bits
        CollapsibleHeader(
            title = strings.advanced,
            expanded = advancedExpanded,
            onToggle = { advancedExpanded = !advancedExpanded },
            border = border
        )
        if (advancedExpanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(responsiveDp(10.dp)),
                modifier = Modifier.padding(start = responsiveDp(12.dp))
            ) {
                // Repo URL — auto-save on blur with inline validation feedback
                Text(
                    text = strings.updateRepository,
                    color = border,
                    fontSize = responsiveSp(13.0),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = strings.updateRepositoryHelp,
                    color = border.copy(alpha = 0.6f),
                    fontSize = responsiveSp(10.0)
                )
                OutlinedTextField(
                    value = repoUrlInput,
                    onValueChange = { repoUrlInput = it; repoUrlError = false },
                    placeholder = { Text("https://github.com/owner/repo", fontSize = responsiveSp(12.0)) },
                    singleLine = true,
                    isError = repoUrlError,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = border, unfocusedBorderColor = border,
                        cursorColor = border, focusedTextColor = border, unfocusedTextColor = border,
                        errorBorderColor = Color.Red
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { state ->
                            if (!state.isFocused) {
                                val trimmed = repoUrlInput.trim()
                                if (trimmed == settings.updateRepoUrl) return@onFocusChanged
                                if (com.sadaqah.kiosk.update.parseGitHubRepoUrl(trimmed) != null) {
                                    onSettingsChange(settings.copy(updateRepoUrl = trimmed))
                                    repoUrlError = false
                                } else if (trimmed.isNotBlank()) {
                                    repoUrlError = true
                                    Toast.makeText(context, strings.invalidGitHubRepoUrl, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                )

                ToggleRow(
                    label = strings.skipSignatureCheckOnce,
                    description = strings.skipSignatureCheckOnceDesc,
                    checked = settings.skipApkSignatureCheckOnce,
                    onChange = { onSettingsChange(settings.copy(skipApkSignatureCheckOnce = it)) },
                    border = border,
                    buttonColor = button
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = strings.updateGracePeriodLabel,
                        color = border,
                        fontSize = responsiveSp(12.0),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = graceDaysInput,
                        onValueChange = { v ->
                            // Save on every valid keystroke. Blur-based save was
                            // unreliable when the operator navigated away
                            // (Back button, section collapse) before the field
                            // lost focus — the typed value got dropped and the
                            // field reverted to whatever was last persisted.
                            if (v.length <= 3 && v.all { c -> c.isDigit() }) {
                                graceDaysInput = v
                                val parsed = v.toIntOrNull()
                                if (parsed != null && parsed in 0..90 &&
                                    parsed != settings.autoUpdateGraceDays) {
                                    onSettingsChange(settings.copy(autoUpdateGraceDays = parsed))
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = border, unfocusedBorderColor = border,
                            cursorColor = border, focusedTextColor = border, unfocusedTextColor = border
                        ),
                        modifier = Modifier.width(responsiveDp(70.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun CollapsibleHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    border: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = responsiveDp(6.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (expanded) "▾" else "▸",
            color = border.copy(alpha = 0.6f),
            fontSize = responsiveSp(14.0),
            modifier = Modifier.padding(end = responsiveDp(8.dp))
        )
        Text(
            text = title,
            color = border.copy(alpha = 0.8f),
            fontSize = responsiveSp(13.0),
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ToggleRow(
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    border: Color,
    buttonColor: Color = Color.Unspecified,
    label: String = ""
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = responsiveDp(8.dp))) {
            if (label.isNotBlank()) {
                Text(label, color = border, fontSize = responsiveSp(13.0), fontWeight = FontWeight.Bold)
            }
            Text(description, color = border.copy(alpha = 0.6f), fontSize = responsiveSp(10.0), lineHeight = responsiveSp(12.0))
        }
        val switchColors = if (buttonColor == Color.Unspecified) SwitchDefaults.colors()
            else SwitchDefaults.colors(
                checkedThumbColor = buttonColor,
                checkedTrackColor = border,
                uncheckedThumbColor = Color.Gray,
                uncheckedTrackColor = Color.DarkGray
            )
        Switch(checked = checked, onCheckedChange = onChange, colors = switchColors)
    }
}