package com.sadaqah.kiosk.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sadaqah.kiosk.R
import com.sadaqah.kiosk.Strings
import com.sadaqah.kiosk.components.SettingsSection
import com.sadaqah.kiosk.model.Settings
import com.sadaqah.kiosk.responsiveDp
import com.sadaqah.kiosk.responsiveSp
import com.sadaqah.kiosk.telemetry.AnalyticsView
import com.sadaqah.kiosk.telemetry.TestUnavailable
import com.sadaqah.kiosk.telemetry.UrlVerdict
import java.text.DateFormat
import java.util.Date

/**
 * What the "Test connection" button is currently doing, decided by whatever
 * wires this screen up (it drives a live network call, which is not something
 * a presenter can own). Everything else this screen shows is decided in
 * [com.sadaqah.kiosk.telemetry.AnalyticsPresenter] and arrives via [AnalyticsView].
 */
sealed class TestConnectionState {
    object Idle : TestConnectionState()
    object Running : TestConnectionState()
    data class Succeeded(val message: String) : TestConnectionState()
    data class Queued(val message: String) : TestConnectionState()
    data class Failed(val message: String) : TestConnectionState()

    /**
     * Never attempted — something refused before the request was made, such as no
     * network or an active backoff. Deliberately distinct from [Failed]: rendering
     * "waiting before retrying" in red tells an operator their destination is
     * broken when it may be perfectly fine, and sends them to re-check settings
     * that were never the problem.
     */
    data class Blocked(val message: String) : TestConnectionState()
}

/**
 * The analytics settings screen.
 *
 * This composable renders [view] and reacts to input; it does not judge
 * anything. Every "is this configured / does this look right / what should
 * this say" question is already answered on [view] or in [strings] — see
 * [AnalyticsView] for the exact fields. A Compose screen cannot be
 * unit-tested here (instrumented tests need a device, and Robolectric would
 * be a new dependency), so any judgement left inside it is judgement nothing
 * checks.
 */
@Composable
fun AnalyticsSettingsScreen(
    view: AnalyticsView,
    settings: Settings,
    strings: Strings,
    testState: TestConnectionState,
    onBack: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onSaveDestination: (url: String, key: String) -> UrlVerdict,
    onTestConnection: () -> Unit,
    onKioskCodeChange: (String) -> Unit,
    onPolicyUrlsChange: (privacy: String, terms: String) -> Unit,
    onClearCredentials: () -> Unit
) {
    val border = Color(settings.buttonBorderColor)
    val button = Color(settings.buttonColor)
    val warningColor = Color(0xFFFFA000)
    val errorColor = Color(0xFFC62828)

    // Local mirrors for the two editable destination fields only — every
    // other field on screen reads straight from `view`. Reset whenever the
    // underlying value changes (e.g. after a successful save or a clear),
    // same pattern the rest of this app uses for text-entry mirrors.
    var urlInput by remember(view.baseUrl) { mutableStateOf(view.baseUrl) }
    // Deliberately plain `remember`, not `rememberSaveable`: this mirrors a
    // plaintext secret the operator is typing, and `rememberSaveable` would
    // parcel it into the Activity's savedInstanceState bundle on every onStop —
    // outside the encrypted SecretStore that is the one thing this screen
    // promises about the key. Do not "fix" this back to rememberSaveable.
    var keyInput by remember(view.maskedKey) { mutableStateOf("") }
    var saveError by remember { mutableStateOf<String?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

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
            colorFilter = ColorFilter.tint(Color(settings.patternColor), blendMode = BlendMode.Modulate)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(responsiveDp(24.dp))
        ) {
            // ── Header ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = strings.analyticsTitle,
                    color = border,
                    fontSize = responsiveSp(40.0),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = button),
                    shape = RoundedCornerShape(responsiveDp(10.dp)),
                    border = BorderStroke(responsiveDp(2.dp), border)
                ) {
                    Text(strings.back, color = border, fontSize = responsiveSp(14.0))
                }
            }

            Spacer(modifier = Modifier.height(responsiveDp(16.dp)))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(responsiveDp(20.dp))
            ) {
                // ── Master toggle ─────────────────────────────────────────
                BandHeader(strings.analyticsSettings, settings)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = responsiveDp(8.dp))) {
                        Text(strings.analyticsEnabledLabel, color = border, fontSize = responsiveSp(14.0), fontWeight = FontWeight.Bold)
                        Text(
                            text = strings.analyticsEnabledHint,
                            color = border.copy(alpha = 0.6f),
                            fontSize = responsiveSp(11.0),
                            lineHeight = responsiveSp(13.0)
                        )
                    }
                    Switch(
                        checked = view.enabled,
                        onCheckedChange = onToggleEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = button,
                            checkedTrackColor = border,
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color.DarkGray
                        )
                    )
                }

                // ── Destination ────────────────────────────────────────────
                SettingsSection(title = strings.analyticsDestination, settings = settings) {
                    Column(verticalArrangement = Arrangement.spacedBy(responsiveDp(8.dp))) {
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            label = { Text(strings.analyticsUrlLabel) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            colors = analyticsFieldColors(border),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = { keyInput = it },
                            label = { Text(strings.analyticsKeyLabel) },
                            placeholder = { Text(view.maskedKey) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            colors = analyticsFieldColors(border),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = strings.analyticsKeyHint,
                            color = border.copy(alpha = 0.6f),
                            fontSize = responsiveSp(11.0)
                        )
                        if (view.keyLooksUnusual) {
                            Text(strings.analyticsKeyUnusual, color = warningColor, fontSize = responsiveSp(11.0))
                        }
                        if (saveError != null) {
                            Text(saveError.orEmpty(), color = errorColor, fontSize = responsiveSp(11.0))
                        }
                        Button(
                            onClick = {
                                when (val verdict = onSaveDestination(urlInput, keyInput)) {
                                    is UrlVerdict.Invalid -> saveError = verdict.reason
                                    is UrlVerdict.Valid -> {
                                        saveError = null
                                        keyInput = ""
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = button),
                            shape = RoundedCornerShape(responsiveDp(10.dp)),
                            border = BorderStroke(responsiveDp(2.dp), border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(strings.analyticsSave, color = border, fontSize = responsiveSp(14.0))
                        }
                    }
                }

                // ── Test connection ────────────────────────────────────────
                SettingsSection(title = strings.analyticsTestConnection, settings = settings) {
                    Column(verticalArrangement = Arrangement.spacedBy(responsiveDp(6.dp))) {
                        Button(
                            onClick = onTestConnection,
                            enabled = view.canTestConnection && testState !is TestConnectionState.Running,
                            colors = ButtonDefaults.buttonColors(containerColor = button, disabledContainerColor = Color.Gray),
                            shape = RoundedCornerShape(responsiveDp(10.dp)),
                            border = BorderStroke(responsiveDp(2.dp), border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(strings.analyticsTestConnection, color = border, fontSize = responsiveSp(14.0))
                        }
                        if (!view.canTestConnection) {
                            val reason = when (view.testUnavailable) {
                                TestUnavailable.NOT_CONFIGURED -> strings.analyticsTestUnavailableNotConfigured
                                TestUnavailable.ANALYTICS_OFF -> strings.analyticsTestUnavailableDisabled
                                null -> null
                            }
                            if (reason != null) {
                                Text(reason, color = border.copy(alpha = 0.6f), fontSize = responsiveSp(11.0))
                            }
                        }
                        when (testState) {
                            is TestConnectionState.Idle -> {}
                            is TestConnectionState.Running -> Text(strings.analyticsTesting, color = border, fontSize = responsiveSp(12.0))
                            is TestConnectionState.Succeeded -> Text(testState.message, color = Color(0xFF2E7D32), fontSize = responsiveSp(12.0))
                            is TestConnectionState.Queued -> Text(testState.message, color = border, fontSize = responsiveSp(12.0))
                            is TestConnectionState.Failed -> Text(testState.message, color = errorColor, fontSize = responsiveSp(12.0))
                            is TestConnectionState.Blocked -> Text(testState.message, color = border, fontSize = responsiveSp(12.0))
                        }
                    }
                }

                // ── Status ──────────────────────────────────────────────────
                SettingsSection(title = strings.analyticsStatus, settings = settings) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(BorderStroke(responsiveDp(2.dp), border.copy(alpha = 0.5f)), RoundedCornerShape(responsiveDp(12.dp)))
                            .padding(horizontal = responsiveDp(16.dp), vertical = responsiveDp(10.dp)),
                        verticalArrangement = Arrangement.spacedBy(responsiveDp(4.dp))
                    ) {
                        AnalyticsStatusLine(strings.analyticsQueued, view.queued.toString(), border)
                        AnalyticsStatusLine(
                            strings.analyticsLastUpload,
                            if (view.neverUploaded) strings.analyticsNeverUploaded else formatTimestamp(view.lastSuccessMs),
                            border
                        )
                        if (view.error != null) {
                            AnalyticsStatusLine(strings.analyticsLastError, view.error, errorColor)
                        }
                        AnalyticsStatusLine(strings.analyticsFailedAttempts, view.consecutiveFailures.toString(), border)
                        if (view.dropped > 0) {
                            AnalyticsStatusLine(strings.analyticsDropped, view.dropped.toString(), errorColor)
                        }
                        if (view.backingOff) {
                            AnalyticsStatusLine(
                                strings.analyticsBackingOff,
                                "${strings.analyticsRetryIn} ${view.backoffRemainingSeconds} ${strings.seconds}",
                                warningColor
                            )
                        }
                        Text(
                            text = if (view.activated) strings.analyticsActivated else strings.analyticsNotActivated,
                            color = if (view.activated) Color(0xFF2E7D32) else border.copy(alpha = 0.7f),
                            fontSize = responsiveSp(12.0),
                            fontWeight = FontWeight.Bold
                        )
                        AnalyticsStatusLine(strings.analyticsInstallId, view.installId, border.copy(alpha = 0.7f))
                    }
                }

                // ── Kiosk code ──────────────────────────────────────────────
                SettingsSection(title = strings.analyticsKioskCode, settings = settings) {
                    Column(verticalArrangement = Arrangement.spacedBy(responsiveDp(6.dp))) {
                        OutlinedTextField(
                            value = view.kioskCode,
                            onValueChange = onKioskCodeChange,
                            singleLine = true,
                            colors = analyticsFieldColors(border),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (view.kioskCodeLooksUnusual) {
                            Text(strings.analyticsKioskCodeUnusual, color = warningColor, fontSize = responsiveSp(11.0))
                        }
                    }
                }

                // ── Policy links ────────────────────────────────────────────
                SettingsSection(title = strings.analyticsPolicyUrls, settings = settings) {
                    Column(verticalArrangement = Arrangement.spacedBy(responsiveDp(8.dp))) {
                        OutlinedTextField(
                            value = view.privacyPolicyUrl,
                            onValueChange = { onPolicyUrlsChange(it, view.termsUrl) },
                            label = { Text(strings.analyticsPrivacyUrlLabel) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            colors = analyticsFieldColors(border),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = view.termsUrl,
                            onValueChange = { onPolicyUrlsChange(view.privacyPolicyUrl, it) },
                            label = { Text(strings.analyticsTermsUrlLabel) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            colors = analyticsFieldColors(border),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (view.policyUrlsMissing) {
                            Text(strings.analyticsPolicyUrlsMissing, color = warningColor, fontSize = responsiveSp(11.0))
                        }
                    }
                }

                // ── Clear credentials ──────────────────────────────────────
                SettingsSection(title = strings.analyticsClearCredentials, settings = settings) {
                    Button(
                        onClick = { showClearConfirm = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        shape = RoundedCornerShape(responsiveDp(10.dp)),
                        border = BorderStroke(responsiveDp(2.dp), Color.White),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(strings.analyticsClearCredentials, color = Color.White, fontWeight = FontWeight.Bold, fontSize = responsiveSp(14.0))
                    }
                }

                Spacer(modifier = Modifier.height(responsiveDp(16.dp)))
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = Color(settings.backgroundColor),
            title = { Text(strings.analyticsClearCredentials, color = Color.Red, fontWeight = FontWeight.Bold) },
            text = { Text(strings.analyticsClearWarning, color = border) },
            confirmButton = {
                Button(
                    onClick = {
                        onClearCredentials()
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text(strings.analyticsClearCredentials, color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Button(onClick = { showClearConfirm = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)) {
                    Text(strings.cancel, color = Color.White)
                }
            }
        )
    }
}

// ── Subcomponents ────────────────────────────────────────────────────────────

@Composable
private fun AnalyticsStatusLine(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = color, fontSize = responsiveSp(12.0))
        Text(value, color = color, fontSize = responsiveSp(12.0), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun analyticsFieldColors(border: Color) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = border,
    unfocusedBorderColor = border,
    cursorColor = border,
    focusedTextColor = border,
    unfocusedTextColor = border
)

private fun formatTimestamp(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))
