package com.sadaqah.kiosk

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.Manifest
import android.app.admin.DevicePolicyManager
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager.Authenticators.*
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.sadaqah.kiosk.model.Settings
import com.sadaqah.kiosk.ui.theme.SadaqahKioskTheme
import com.google.gson.Gson
import com.sadaqah.kiosk.donations.DonationHistory
import com.sadaqah.kiosk.recovery.*
import com.sadaqah.kiosk.screens.*
import com.sadaqah.kiosk.update.ReleaseInfo
import com.sadaqah.kiosk.update.SemVer
import com.sadaqah.kiosk.update.UpdateManager
import com.sadaqah.kiosk.update.UpdateState
import com.sadaqah.kiosk.update.UpdateWatchdogReceiver
import com.sumup.merchant.reader.api.SumUpAPI
import com.sumup.merchant.reader.api.SumUpLogin
import com.sumup.merchant.reader.api.SumUpPayment
import com.sumup.merchant.reader.ReaderModuleCoreState
import com.sumup.merchant.reader.api.SumUpState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigDecimal

private const val INACTIVITY_BOUNCE_MS = 5L * 60 * 1000 // 5 min on settings / custom-amount → back to donation

class MainActivity : FragmentActivity() {
    private lateinit var prefs: SharedPreferences

    private var biometricPrompt: BiometricPrompt? = null
    private var authTimeoutJob: Job? = null
    private var connectivityManager: ConnectivityManager? = null
    private var autoPinJob: Job? = null
    private var pairingUnpinJob: Job? = null
    private var wifiReconnectJob: Job? = null
    private var bluetoothReceiver: BroadcastReceiver? = null
    private var networkDismissJob: Job? = null
    private var restartCountResetJob: Job? = null
    private var silentLoginWatchdogJob: Job? = null
    private var connectivityPollJob: Job? = null
    private var wifiCycleJob: Job? = null
    private var bluetoothCycleJob: Job? = null
    private var savedNetworkFallbackJob: Job? = null
    private var cardReaderPageTimeoutJob: Job? = null

    /** Amount of the most recently initiated payment, stashed at makePayment() time. */
    private var lastPaymentAmount: BigDecimal? = null
    lateinit var donationHistory: DonationHistory
        private set
    private lateinit var restartManager: RestartManager
    private lateinit var networkRecoveryManager: NetworkRecoveryManager

    var settings: Settings by mutableStateOf(Settings())
    var isLoggedIn by mutableStateOf(false)
    var affiliateKey by mutableStateOf("")
    var isEditingSettings by mutableStateOf(false)
    var isPickingColor by mutableStateOf(false)
    var colorLabel by mutableStateOf("")
    var resetState by mutableStateOf(false)
    var mustRefresh by mutableStateOf(false)
    var showThankYou by mutableStateOf(false)
    var maintenanceReason by mutableStateOf<MaintenanceReason?>(null)
    var showCustomAmountScreen by mutableStateOf(false)
    var customAmountInput by mutableStateOf("")
    var isScreensaverActive by mutableStateOf(false)
    var lastInteractionTime by mutableLongStateOf(System.currentTimeMillis())
    var isNetworkAvailable by mutableStateOf(true)
    var firstLogIn by mutableStateOf(true)
    var isPinned by mutableStateOf(false)
    var isBluetoothEnabled by mutableStateOf(false)
    var isCardReaderConnected by mutableStateOf(false)
    var isReconnectingWifi by mutableStateOf(false)
    var isConnectingCardReader by mutableStateOf(false)
    var showSetupStatus by mutableStateOf(false)
    var showDonationHistory by mutableStateOf(false)
    var setupStatusFromOffline by mutableStateOf(false)
    var showUpdateConfirm by mutableStateOf(false)
    var showUpdatingOverlay by mutableStateOf(false)
    lateinit var updateManager: UpdateManager
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        ColorHistory.init(prefs)

        // Whitelist this app for silent lock task mode (no blue notification)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, KioskDeviceAdminReceiver::class.java)
        if (dpm.isDeviceOwnerApp(packageName)) {
            // Whitelist SumUp so its login / card-reader activities can launch under
            // lock task without us first having to drop pinning.
            dpm.setLockTaskPackages(admin, arrayOf(packageName, "com.sumup.merchant.reader"))

            // Silently grant the wifi-scan permissions so the saved-network fallback
            // can call WifiManager.startScan() / scanResults without a runtime prompt.
            val grantList = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                grantList += Manifest.permission.NEARBY_WIFI_DEVICES
            }
            for (perm in grantList) {
                try {
                    dpm.setPermissionGrantState(admin, packageName, perm,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
                } catch (e: Exception) {
                    Log.w("DeviceOwner", "Could not auto-grant $perm: ${e.message}")
                }
            }
        }

        // Initialise connectivity manager up-front so the network gate inside
        // authenticate() and other SumUp calls reflects reality at startup.
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        isNetworkAvailable = isOnlineNow()

        if (settings.testMode) {
            isLoggedIn = true
            isCardReaderConnected = true
        } else {
            val storedKey = prefs.getString("affiliate_key", null)
            if (!storedKey.isNullOrEmpty()) {
                affiliateKey = storedKey
                authenticate(affiliateKey)
            }
        }

        val json = prefs.getString("settings", null)
        if (!json.isNullOrEmpty()) {
            settings = Gson().fromJson(json, Settings::class.java)
        } else {
            settings = Settings()
        }

        if (json.isNullOrEmpty()) {
            // First startup: auto-detect from device locale, default to English if no match
            val deviceLocale = java.util.Locale.getDefault().language
            val detectedLanguage = TranslationManager.fromCode(deviceLocale)
            TranslationManager.setLanguage(detectedLanguage)
            settings = settings.copy(language = detectedLanguage.code)
            saveSettings(settings)
        } else {
            TranslationManager.setLanguage(TranslationManager.fromCode(settings.language))
        }

        // Kick off logo colour extraction so the picker has swatches ready
        // by the time the operator opens it. Idempotent — repeat calls on
        // the same URI are no-ops.
        LogoColorExtractor.refresh(this, settings.logoUri)

        donationHistory = DonationHistory(this)
        // Lazy-init the donation-stats anchor. If never set, fix it to "now" so
        // throughput averages have a defined denominator. Reset Averages will
        // re-set it later.
        if (settings.donationStatsStartedAtMs == 0L) {
            settings = settings.copy(donationStatsStartedAtMs = System.currentTimeMillis())
            saveSettings(settings)
        }

        // Migration: GSON ignores Kotlin data-class defaults when deserialising, so
        // existing installs may still have the old 120s threshold (or 0 if the field
        // was never persisted). Bump anything below 5 min up to the new default.
        if (settings.longDowntimeThresholdSec < 300) {
            settings = settings.copy(longDowntimeThresholdSec = 300)
            saveSettings(settings)
        }

        // Migration: existing v1.3.0 installs saved settings JSON without the
        // auto-update fields. GSON returns Boolean=false / String="" for those.
        // Restore intended defaults if the saved JSON doesn't mention them.
        var migrated = settings
        var dirty = false
        if (json != null) {
            if (!json.contains("\"autoUpdateEnabled\"")) {
                migrated = migrated.copy(autoUpdateEnabled = true); dirty = true
            }
            if (!json.contains("\"autoUpdateTargetVersion\"") || migrated.autoUpdateTargetVersion.isBlank()) {
                migrated = migrated.copy(autoUpdateTargetVersion = "latest"); dirty = true
            }
            if (!json.contains("\"autoUpdateGraceDays\"") || migrated.autoUpdateGraceDays <= 0) {
                migrated = migrated.copy(autoUpdateGraceDays = 14); dirty = true
            }
            if (!json.contains("\"updateRepoUrl\"") || migrated.updateRepoUrl.isBlank()) {
                // Reconstruct from older split fields if they exist; otherwise default.
                val ownerMatch = Regex("\"updateRepoOwner\"\\s*:\\s*\"([^\"]+)\"").find(json)
                val nameMatch = Regex("\"updateRepoName\"\\s*:\\s*\"([^\"]+)\"").find(json)
                val url = if (ownerMatch != null && nameMatch != null) {
                    "https://github.com/${ownerMatch.groupValues[1]}/${nameMatch.groupValues[1]}"
                } else {
                    "https://github.com/HiIAmMoot/SadaqahKiosk"
                }
                migrated = migrated.copy(updateRepoUrl = url); dirty = true
            }
        }
        if (dirty) {
            settings = migrated
            saveSettings(settings)
        }

        val store = SharedPreferencesStore(prefs)
        restartManager = RestartManager(store, settings)
        networkRecoveryManager = NetworkRecoveryManager(settings)

        // Heartbeat for the update watchdog: prove that the freshly-installed APK
        // (or any startup, really) reached running state. The watchdog rolls back
        // if this isn't bumped within 60s of an install attempt.
        UpdateWatchdogReceiver.recordHeartbeat(this)

        updateManager = UpdateManager(
            context = this,
            initialSettings = settings,
            isNetworkAvailable = { isNetworkAvailable },
            onStartInstall = { showUpdatingOverlay = true },
            onFinishInstall = { restoreAfterFailedUpdate() },
            persistSettings = { newSettings -> onSettingsChange(newSettings) },
            onNotification = { n -> showUpdateNotification(n) },
            prepareForInstall = { prepareForSelfUpdate() }
        )

        // Fire an initial check 30s after startup so we don't slow boot or step
        // on the rest of the startup work.
        lifecycleScope.launch {
            delay(30_000L)
            updateManager.checkForUpdate()
        }

        enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        startConnectivityPolling()
        if (!isNetworkAvailable) {
            startWifiCyclingWhileOffline()
            startSavedNetworkFallback()
        }

        isBluetoothEnabled = (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
            ?.adapter?.isEnabled == true

        bluetoothReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                isBluetoothEnabled = state == BluetoothAdapter.STATE_ON
                if (!isBluetoothEnabled) autoPinJob?.cancel()
                else scheduleAutoPinIfReady()
            }
        }
        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        scheduleAutoPinIfReady()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 100)
        }

        setContent {
            SadaqahKioskTheme {
                LaunchedEffect(Unit) {
                    var prepareCounter = 0
                    while (true) {
                        delay(10000L) // Check every 10 seconds
                        val idleTime = System.currentTimeMillis() - lastInteractionTime
                        val screensaverDelay = settings.screensaverIdleTimeoutSec * 1000L

                        if (idleTime > screensaverDelay && !isScreensaverActive && isLoggedIn && !isEditingSettings && !showThankYou && maintenanceReason == null) {
                            isScreensaverActive = true
                            Log.d("Screensaver", "Activated after idle")
                            disconnectCardReader()
                        }

                        // Inactivity bounce: after 5 min of no interaction on either
                        // the settings stack or the custom-amount numpad, drop back
                        // to the donation grid so a forgotten/abandoned session
                        // doesn't hang the kiosk in those screens.
                        val onSettingsStack = isEditingSettings
                        val onCustomAmount = showCustomAmountScreen
                        if (idleTime > INACTIVITY_BOUNCE_MS && (onSettingsStack || onCustomAmount)) {
                            Log.d("InactivityBounce", "Returning to donation grid after ${idleTime}ms idle")
                            showCustomAmountScreen = false
                            customAmountInput = ""
                            isEditingSettings = false
                            isPickingColor = false
                            showSetupStatus = false
                            setupStatusFromOffline = false
                            showDonationHistory = false
                            // Reset so the screensaver doesn't immediately fire on top of the bounce.
                            lastInteractionTime = System.currentTimeMillis()
                        }

                        val onDonationScreen = isLoggedIn && !isEditingSettings && !showThankYou && maintenanceReason == null
                        if (onDonationScreen || isScreensaverActive) {
                            prepareCounter++
                            if (prepareCounter >= 30) { // 30 × 10s = 5 minutes
                                prepareCounter = 0
                                prepareCardReader()
                            }
                        } else {
                            prepareCounter = 0
                        }
                    }
                }

                AppUI(
                    isLoggedIn = isLoggedIn,
                    isEditingSettings = isEditingSettings,
                    isPickingColor = isPickingColor,
                    onToggleSettings = { onToggleSettings() },
                    affiliateKey = affiliateKey,
                    onAffiliateKeyChange = { affiliateKey = it },
                    onLogin = { },
                    authenticate = { authenticate(affiliateKey) },
                    connectCardReader = { connectCardReader() },
                    makePayment = { amount -> makePayment(amount) },
                    settings = settings,
                    onSettingsChange = { onSettingsChange(it) },
                    openColorPicker = { label -> openColorPicker(label) },
                    onResetApp = { resetApp() },
                    getColorSetting = { label -> getColorSettingFromLabel(label) },
                    setColorSetting = { label, color -> setColorSettingFromLabel(label, color) },
                    colorLabel = colorLabel,
                    mustRefresh = mustRefresh,
                    onRefresh = { setRefresh() },
                    showThankYou = showThankYou,
                    authenticateWithBiometrics = ::authenticateWithBiometrics,
                    showCustomAmountScreen = showCustomAmountScreen,
                    customAmountInput = customAmountInput,
                    onCustomAmountChange = { customAmountInput = it },
                    onCustomAmountSubmit = { processCustomAmount(it) },
                    onShowCustomAmountScreen = { showCustomAmountScreen = it },
                    reinitSumUp = ::reinitSumUp,
                    maintenanceReason = maintenanceReason,
                    onOfflineSettingsClick = ::onOfflineSettingsClick,
                    isScreensaverActive = isScreensaverActive,
                    onResetScreensaver = ::resetScreensaver,
                    onExportSettings = ::exportSettings,
                    onImportSettings = ::importSettings,
                    isNetworkAvailable = isNetworkAvailable,
                    isPinned = isPinned,
                    isBluetoothEnabled = isBluetoothEnabled,
                    isCardReaderConnected = isCardReaderConnected,
                    showSetupStatus = showSetupStatus,
                    onShowSetupStatus = { showSetupStatus = it },
                    showDonationHistory = showDonationHistory,
                    onShowDonationHistory = { showDonationHistory = it },
                    donationHistory = donationHistory,
                    setupStatusFromOffline = setupStatusFromOffline,
                    onExitSetupStatus = ::exitSetupStatus,
                    onUnpinApp = ::unpinApp,
                    onPinApp = ::startAppPinning,
                    onReconnectWifi = ::startWifiReconnectFlow,
                    onEnableBluetooth = ::openBluetoothSettings,
                    onDisableBluetooth = ::disableBluetooth,
                    onActivateScreensaver = ::activateScreensaver,
                    onTestModeChange = { enabled ->
                        onSettingsChange(settings.copy(testMode = enabled))
                        if (enabled) {
                            isLoggedIn = true
                            isCardReaderConnected = true
                        } else {
                            isLoggedIn = false
                            isCardReaderConnected = false
                            // If a real affiliate key is still cached and we're
                            // online, kick a SumUp login so leaving test mode
                            // drops the operator back onto the live grid instead
                            // of into the offline / login fallback.
                            if (affiliateKey.isNotBlank() && isNetworkAvailable) {
                                authenticate(affiliateKey)
                            }
                        }
                    },
                    onLogout = { logout() },
                    updateState = updateManager.state,
                    showUpdatingOverlay = showUpdatingOverlay,
                    showUpdateConfirm = showUpdateConfirm,
                    latestUpdate = updateManager.latestKnown,
                    hasUpdateAvailable = updateManager.hasActionableUpdate() && !settings.hideUpdatePrompts,
                    currentVersionLabel = updateManager.currentVersion,
                    availableReleases = updateManager.availableReleases,
                    scheduledInstallAtMs = updateManager.nextScheduledInstallTime(),
                    onUpdateBadgeTapped = ::onUpdateBadgeTapped,
                    onUpdateConfirmInstall = ::onUpdateConfirmInstall,
                    onUpdateConfirmLater = ::onUpdateConfirmLater,
                    onManualCheckForUpdates = ::manualCheckForUpdates
                )
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val strings = TranslationManager.currentStrings()

        Log.d("ActivityResult", "requestCode=$requestCode resultCode=$resultCode extras=${data?.extras}")

        when (requestCode) {
            1 -> {
                cancelSilentLoginWatchdog()
                // If network is down, this was an unwanted SumUp login popup — suppress it
                if (!isNetworkAvailable) {
                    Log.d("SumUpLogin", "Login result while offline — suppressing")
                    stopPairingUnpin()
                    scheduleAutoPinIfReady()
                } else if (resultCode == 1 && data != null) {
                    Toast.makeText(this, strings.logInSuccessful, Toast.LENGTH_SHORT).show()
                    prefs.edit() { putString("affiliate_key", affiliateKey) }
                    isLoggedIn = true
                    restartManager.clearCounters()
                    scheduleRestartCounterReset()
                    if (firstLogIn) {
                        connectCardReader()
                        firstLogIn = false
                    } else {
                        stopPairingUnpin()
                        scheduleAutoPinIfReady()
                    }
                } else {
                    stopPairingUnpin()
                    scheduleAutoPinIfReady()
                    val errorMessage = data?.getStringExtra(SumUpAPI.Response.MESSAGE) ?: "Unknown error"
                    val errorCode = data?.getIntExtra(SumUpAPI.Response.RESULT_CODE, -1) ?: -1
                    Log.e("SumUpLogin", "Login failed - Code: $errorCode, Message: $errorMessage")
                    Toast.makeText(this, "${strings.logInFailed}: $errorMessage", Toast.LENGTH_LONG).show()
                    handleRestartResult(restartManager.recordReinitFailure(), "reinit_failures")
                }
            }
            2 -> {
                cardReaderPageTimeoutJob?.cancel()
                val readerConnected = try {
                    ReaderModuleCoreState.Instance()?.mReaderCoreManager?.isCardReaderConnected() == true
                } catch (e: Exception) { false }
                isConnectingCardReader = false
                stopPairingUnpin()
                scheduleAutoPinIfReady()
                if (resultCode == 1 || readerConnected) {
                    isCardReaderConnected = true
                    restartManager.clearCardReaderFailures()
                    scheduleRestartCounterReset()
                    Toast.makeText(this, strings.deviceConnectionSuccessful, Toast.LENGTH_SHORT).show()
                    lifecycleScope.launch {
                        delay(3000)
                        prepareCardReader()
                    }
                } else {
                    isCardReaderConnected = false
                    val errorMessage = data?.getStringExtra(SumUpAPI.Response.MESSAGE) ?: "Unknown error"
                    val errorCode = data?.getIntExtra(SumUpAPI.Response.RESULT_CODE, -1) ?: -1
                    val userMessage = when {
                        errorMessage.contains("timeout", ignoreCase = true) -> strings.cardReaderTimeout
                        errorMessage.contains("not found", ignoreCase = true) -> strings.cardReaderNotFound
                        errorCode == SumUpAPI.Response.ResultCode.ERROR_NO_CONNECTIVITY -> strings.noConnection
                        else -> "${strings.connectionFailed}: $errorMessage"
                    }
                    Toast.makeText(this, userMessage, Toast.LENGTH_LONG).show()
                    handleRestartResult(restartManager.recordCardReaderFailure(), "card_reader_failures")
                }
            }
            3 -> {
                if (resultCode == 1 && data != null) {
                    val txCode = data.getStringExtra(SumUpAPI.Response.TX_CODE)
                    Log.d("SumUpPayment", "Payment successful - TX Code: $txCode")
                    Toast.makeText(this, strings.paymentSuccessful, Toast.LENGTH_SHORT).show()
                    restartManager.clearCounters()
                    scheduleRestartCounterReset()
                    // Append to donation history if tracking is on.
                    if (settings.donationTrackingEnabled) {
                        lastPaymentAmount?.let { donationHistory.append(it) }
                    }
                    lastPaymentAmount = null
                    showThankYouScreen()
                } else {
                    val errorMessage = data?.getStringExtra(SumUpAPI.Response.MESSAGE) ?: "Unknown error"
                    val errorCode = data?.getIntExtra(SumUpAPI.Response.RESULT_CODE, -1) ?: -1
                    Log.e("SumUpPayment", "Payment failed - Code: $errorCode, Message: $errorMessage")

                    val userMessage = when (errorCode) {
                        SumUpAPI.Response.ResultCode.ERROR_TRANSACTION_FAILED -> strings.transactionDeclined
                        SumUpAPI.Response.ResultCode.ERROR_NO_CONNECTIVITY -> strings.noInternetConnection
                        SumUpAPI.Response.ResultCode.ERROR_NOT_LOGGED_IN -> strings.notLoggedIn
                        SumUpAPI.Response.ResultCode.ERROR_GEOLOCATION_REQUIRED -> strings.locationRequired
                        else -> "${strings.paymentFailed}: $errorMessage"
                    }
                    Toast.makeText(this, userMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    fun startAppPinning() {
        if (!isPinned) {
            startLockTask()
            isPinned = true
            Log.d("ScreenPin", "App pinned")
        }
    }

    fun stopAppPinning() {
        if (isPinned) {
            stopLockTask()
            isPinned = false
            Log.d("ScreenPin", "App unpinned")
        }
    }

    fun startPairingUnpin() {
        autoPinJob?.cancel()
        stopAppPinning()
        showSystemBars()
        pairingUnpinJob?.cancel()
        pairingUnpinJob = lifecycleScope.launch {
            while (true) {
                delay(5000L)
                if (isPinned) {
                    stopLockTask()
                    isPinned = false
                    Log.d("ScreenPin", "Pairing unpin: forced unpin")
                }
            }
        }
        Log.d("ScreenPin", "Pairing unpin started")
    }

    fun stopPairingUnpin() {
        pairingUnpinJob?.cancel()
        pairingUnpinJob = null
        hideSystemBars()
        Log.d("ScreenPin", "Pairing unpin stopped")
    }

    fun scheduleAutoPinIfReady() {
        if (!isNetworkAvailable || !isBluetoothEnabled || isPinned || isConnectingCardReader) return
        autoPinJob?.cancel()
        autoPinJob = lifecycleScope.launch {
            delay(60_000L)
            if (isNetworkAvailable && isBluetoothEnabled && !isPinned) {
                startAppPinning()
            }
        }
        Log.d("ScreenPin", "Auto-pin scheduled in 60s")
    }

    fun openSystemSettings(intent: Intent) {
        isReconnectingWifi = true
        stopAppPinning()
        startActivity(intent)

        wifiReconnectJob?.cancel()
        wifiReconnectJob = lifecycleScope.launch {
            delay(120_000L)
            if (isReconnectingWifi) {
                isReconnectingWifi = false
                startAppPinning()
            }
        }
        Log.d("ScreenPin", "Opened system settings — 2min re-pin timeout started")
    }

    fun scheduleCardReaderPoll(btManager: android.bluetooth.BluetoothManager?, gattBefore: Set<String>) {
        lifecycleScope.launch {
            delay(500)
            if (!isConnectingCardReader) return@launch
            val sdkConnected = try {
                ReaderModuleCoreState.Instance()?.mReaderCoreManager?.isCardReaderConnected() == true
            } catch (e: Exception) { false }
            val gattNow = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            ) {
                @SuppressLint("MissingPermission")
                val devices = btManager?.getConnectedDevices(BluetoothProfile.GATT)
                    ?.map { it.address }?.toSet() ?: emptySet()
                devices
            } else emptySet()
            val newGattDevice = (gattNow - gattBefore).isNotEmpty()
            if (sdkConnected || newGattDevice) {
                delay(3000)
                finishActivity(2)
            } else {
                scheduleCardReaderPoll(btManager, gattBefore)
            }
        }
    }

    fun startWifiReconnectFlow() {
        openSystemSettings(Intent(AndroidSettings.Panel.ACTION_WIFI))
    }

    fun openBluetoothSettings() {
        openSystemSettings(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS))
    }

    @SuppressLint("MissingPermission")
    fun disableBluetooth() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        @Suppress("DEPRECATION")
        adapter?.disable()
    }

    fun unpinApp() {
        autoPinJob?.cancel()
        stopAppPinning()
    }

    fun activateScreensaver() {
        isEditingSettings = false
        isScreensaverActive = true
        finishActivity(2)
        disconnectCardReader()
    }

    fun disconnectCardReader() {
        ReaderModuleCoreState.Instance()?.mReaderCoreManager?.let { rm ->
            Log.d("CardReader", "Disconnecting card reader")
            rm.disconnect()
        }
        isCardReaderConnected = false
        // SDK-level disconnect alone has been unreliable: the reader sometimes
        // believes it is still connected and silently stops accepting commands.
        // Power-cycling the BT radio (device-owner privilege, no user prompt)
        // is what reliably clears that stuck state.
        cycleBluetoothAdapter()
    }

    @SuppressLint("MissingPermission")
    private fun cycleBluetoothAdapter() {
        bluetoothCycleJob?.cancel()
        bluetoothCycleJob = lifecycleScope.launch {
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
            if (adapter == null) {
                Log.w("BluetoothCycle", "BluetoothAdapter unavailable — skip cycle")
                return@launch
            }
            try {
                @Suppress("DEPRECATION")
                val offOk = adapter.disable()
                Log.d("BluetoothCycle", "adapter.disable() → $offOk")
                // Wait for the STATE_OFF broadcast (updates isBluetoothEnabled = false)
                // before turning it back on, so the radio is genuinely off mid-cycle.
                val offDeadline = System.currentTimeMillis() + 5_000L
                while (isBluetoothEnabled && System.currentTimeMillis() < offDeadline) {
                    delay(200L)
                }
                delay(1000L)
                @Suppress("DEPRECATION")
                val onOk = adapter.enable()
                Log.d("BluetoothCycle", "adapter.enable() → $onOk")
                // Wait for STATE_ON so callers that join() this job know the radio
                // is actually back up, not just that enable() was queued.
                val onDeadline = System.currentTimeMillis() + 8_000L
                while (!isBluetoothEnabled && System.currentTimeMillis() < onDeadline) {
                    delay(200L)
                }
                Log.d("BluetoothCycle", "Cycle complete — adapter ON: $isBluetoothEnabled")
            } catch (e: SecurityException) {
                Log.e("BluetoothCycle", "SecurityException toggling bluetooth: ${e.message}")
            } catch (e: Exception) {
                Log.e("BluetoothCycle", "Error toggling bluetooth: ${e.message}")
            }
        }
    }

    fun authenticate(affiliateKey: String, silent: Boolean = false) {
        if (settings.testMode) {
            isLoggedIn = true
            isCardReaderConnected = true
            return
        }
        // Hard gate: any SumUp call while offline can clobber the cached login
        // and force a manual email/password re-entry next time. Bail early.
        if (!isNetworkAvailable) {
            Log.w("SumUpLogin", "authenticate skipped — no network (silent=$silent)")
            if (!silent) {
                val strings = TranslationManager.currentStrings()
                Toast.makeText(this, strings.noInternetConnection, Toast.LENGTH_LONG).show()
            }
            return
        }
        if (silent) {
            // Reinit / scheduled refresh: keep pinning, rely on cached SumUp credentials
            // for a transparent re-auth behind the maintenance screen. Whitelisted SumUp
            // package can launch under lock task. Watchdog dismisses the activity if it
            // stalls (e.g. cached creds expired and SumUp shows the real login form).
            silentLoginWatchdogJob?.cancel()
            silentLoginWatchdogJob = lifecycleScope.launch {
                delay(10_000L)
                Log.w("SumUpLogin", "Silent login watchdog — forcing finish on stuck login")
                finishActivity(1)
            }
        } else {
            startPairingUnpin()
        }
        SumUpState.init(this)
        val sumupLogin = SumUpLogin.builder(affiliateKey).build()
        SumUpAPI.openLoginActivity(this@MainActivity, sumupLogin, 1)
        Log.d("SumUpTest", "Login started (silent=$silent)...")
        scheduleDailyLoginReset(affiliateKey)
    }

    fun connectCardReader() {
        if (settings.testMode) {
            isCardReaderConnected = true
            Toast.makeText(this, TranslationManager.currentStrings().testModeCardReaderSimulated, Toast.LENGTH_SHORT).show()
            return
        }
        if (!isNetworkAvailable) {
            val strings = TranslationManager.currentStrings()
            Toast.makeText(this, strings.noInternetConnection, Toast.LENGTH_LONG).show()
            Log.w("SumUpReader", "Card reader connection attempted with no network")
            return
        }
        if (!isBluetoothEnabled) {
            val strings = TranslationManager.currentStrings()
            Toast.makeText(this, strings.enableBluetooth, Toast.LENGTH_SHORT).show()
            Log.w("SumUpReader", "Card reader connection attempted with Bluetooth disabled")
            return
        }
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val gattBefore = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        ) {
            @SuppressLint("MissingPermission")
            val devices = btManager?.getConnectedDevices(BluetoothProfile.GATT)
                ?.map { it.address }?.toSet() ?: emptySet()
            devices
        } else emptySet()
        isConnectingCardReader = true
        startPairingUnpin()
        SumUpAPI.openCardReaderPage(this@MainActivity, 2)
        scheduleCardReaderPoll(btManager, gattBefore)
        // 10-minute timeout: if the operator walks away from the SumUp pairing
        // dialog, force-close it. prepareCardReader() runs every 5 min and
        // will pick the reader up silently in the background once it's nearby.
        cardReaderPageTimeoutJob?.cancel()
        cardReaderPageTimeoutJob = lifecycleScope.launch {
            delay(10 * 60 * 1000L)
            if (isConnectingCardReader) {
                Log.d("SumUpReader", "Card reader page timed out after 10 min — closing")
                finishActivity(2)
            }
        }
    }

    fun scheduleDailyLoginReset(affiliateKey: String) {
        lifecycleScope.launch {
            val now = java.time.LocalDateTime.now()
            val today2am = now.toLocalDate().atTime(2, 0)
            val next2am = if (now < today2am) today2am else today2am.plusDays(1)

            val durationUntil2am = java.time.Duration.between(now, next2am).toMillis()

            delay(durationUntil2am)

            Log.d("SumUpDebug", "Scheduled reinit at 2am")
            performReinit()
            // After the nightly reinit, run update maintenance: check, download,
            // install if grace expired / pinning differs. UpdateManager handles
            // all preflight (battery, network, device-owner).
            try {
                updateManager.refreshSettings(settings)
                updateManager.runDailyMaintenance()
            } catch (e: Exception) {
                Log.e("UpdateManager", "Daily maintenance threw: ${e.message}")
            }
        }
    }

    fun onOfflineSettingsClick() {
        authenticateWithBiometrics(
            this,
            onSuccess = {
                maintenanceReason = null
                isEditingSettings = true
                showSetupStatus = true
                setupStatusFromOffline = true
            },
            onError = { error -> Toast.makeText(this, error, Toast.LENGTH_SHORT).show() }
        )
    }

    fun exitSetupStatus() {
        showSetupStatus = false
        setupStatusFromOffline = false
        isEditingSettings = false
    }

    private fun cancelSilentLoginWatchdog() {
        silentLoginWatchdogJob?.cancel()
        silentLoginWatchdogJob = null
    }

    fun makePayment(amount: String) {
        val strings = TranslationManager.currentStrings()

        if (settings.testMode) {
            Log.d("TestMode", "Simulated payment: $amount ${settings.currency}")
            showThankYouScreen()
            return
        }

        if (!isNetworkAvailable) {
            Toast.makeText(this, strings.noInternetConnection, Toast.LENGTH_LONG).show()
            Log.w("SumUpPayment", "Payment attempted with no network")
            return
        }

        val title = if (!settings.kioskName.isNullOrBlank()) {
            "SK - ${settings.kioskName}"
        } else {
            "SK Donatie"
        }

        val currency = when (settings.currency) {
            "USD" -> SumUpPayment.Currency.USD
            "GBP" -> SumUpPayment.Currency.GBP
            else -> SumUpPayment.Currency.EUR
        }

        // Stash so the success callback can append to the history log.
        lastPaymentAmount = runCatching { BigDecimal(amount) }.getOrNull()

        val paymentBuilder = SumUpPayment.builder()
            .total(BigDecimal(amount))
            .currency(currency)
            .title(title)
            .skipSuccessScreen()
            .skipFailedScreen()

        if (!settings.kioskName.isNullOrBlank()) {
            paymentBuilder.addAdditionalInfo("KioskNaam", settings.kioskName)
        }

        val payment = paymentBuilder.build()
        SumUpAPI.checkout(this@MainActivity, payment, 3)
        Log.d("SumUpPayment", "Payment initiated - Amount: \u20ac$amount, Title: $title")
    }

    fun processCustomAmount(amount: String) {
        val strings = TranslationManager.currentStrings()
        val numericAmount = amount.toIntOrNull()

        when {
            numericAmount == null || numericAmount < 1 -> {
                Toast.makeText(this, strings.minimumDonation, Toast.LENGTH_SHORT).show()
            }
            numericAmount > 5000 -> {
                Toast.makeText(this, strings.maximumDonation, Toast.LENGTH_SHORT).show()
            }
            else -> {
                makePayment(amount)
                showCustomAmountScreen = false
                customAmountInput = ""
            }
        }
    }

    fun reinitSumUp() {
        if (settings.testMode) {
            Toast.makeText(this, TranslationManager.currentStrings().testModeReinitSkipped, Toast.LENGTH_SHORT).show()
            return
        }
        val strings = TranslationManager.currentStrings()
        if (!isNetworkAvailable) {
            Toast.makeText(this, strings.noInternetConnection, Toast.LENGTH_LONG).show()
            Log.w("SumUpDebug", "Manual reinit skipped — no network")
            return
        }
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, strings.reinitializing, Toast.LENGTH_SHORT).show()
            Log.d("SumUpDebug", "Manual reinit triggered")
            performReinit()
            Toast.makeText(this@MainActivity, strings.reinitialized, Toast.LENGTH_SHORT).show()
        }
    }

    suspend fun performReinit() {
        // Last line of defense: caller already gates, but the 02:00 scheduled
        // reinit and the post-outage AutoReinit path land here directly.
        if (!isNetworkAvailable) {
            Log.w("SumUpDebug", "performReinit skipped — no network")
            return
        }
        maintenanceReason = MaintenanceReason.Reinitializing

        authenticate(affiliateKey, silent = true)
        delay(5000L)

        // Reorder rationale: do auth FIRST while BT is still up, then tear the
        // card reader down and power-cycle the radio. This avoids the SumUp SDK
        // re-authing against a freshly-cycled BT stack mid-bring-up.
        disconnectCardReader() // fires the BT cycle (off → STATE_OFF → on → STATE_ON)
        bluetoothCycleJob?.join() // wait until adapter is verifiably back ON
        delay(2000L) // 2s grace after BT is up before prepareForCheckout

        // Don't clobber a NetworkOutage state — if connectivity died mid-reinit, keep showing
        // the offline screen instead of flashing back to the donation grid.
        if (maintenanceReason == MaintenanceReason.Reinitializing) {
            maintenanceReason = null
        }
        Log.d("SumUpDebug", "Reinit complete")
        prepareCardReader()
    }

    override fun onResume() {
        super.onResume()
        if (pairingUnpinJob == null) hideSystemBars()
        if (isReconnectingWifi) {
            isReconnectingWifi = false
            wifiReconnectJob?.cancel()
            if (isNetworkAvailable && isBluetoothEnabled) {
                startAppPinning()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pairingUnpinJob == null) hideSystemBars()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        // Refresh the global idle timer on every touch DOWN so screen-level
        // timeouts (screensaver, inactivity bounce) reflect actual user
        // interaction rather than only the few buttons that explicitly call
        // resetScreensaver().
        if (ev?.action == android.view.MotionEvent.ACTION_DOWN) {
            lastInteractionTime = System.currentTimeMillis()
        }
        return super.dispatchTouchEvent(ev)
    }

    // ── Network disconnect / restore handling ──────────────────────────────────

    /**
     * True only when the active network is both connected AND has been validated
     * by Android's captive-portal probe. Catches "wifi connected but no internet"
     * (router up, upstream dead) which a NET_CAPABILITY_INTERNET check misses.
     */
    private fun isOnlineNow(): Boolean {
        val cm = connectivityManager ?: return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun startConnectivityPolling() {
        connectivityPollJob?.cancel()
        connectivityPollJob = lifecycleScope.launch {
            while (true) {
                val online = isOnlineNow()
                if (online != isNetworkAvailable) {
                    isNetworkAvailable = online
                    Log.d("NetworkPoll", "Connectivity changed → online=$online")
                    if (online) {
                        wifiCycleJob?.cancel()
                        savedNetworkFallbackJob?.cancel()
                        if (isReconnectingWifi) {
                            isReconnectingWifi = false
                            wifiReconnectJob?.cancel()
                            delay(2000L)
                            startAppPinning()
                        } else {
                            scheduleAutoPinIfReady()
                        }
                        handleNetworkRestored()
                        // Booted offline (or test mode was toggled off offline)
                        // with a cached key — now that we have internet, try the
                        // login that we deliberately skipped when we were offline.
                        if (!isLoggedIn && affiliateKey.isNotBlank() && !settings.testMode) {
                            Log.d("NetworkPoll", "Network restored — authenticating cached key")
                            authenticate(affiliateKey)
                        }
                    } else {
                        autoPinJob?.cancel()
                        handleNetworkLost()
                        startWifiCyclingWhileOffline()
                        startSavedNetworkFallback()
                    }
                }
                delay(1_000L)
            }
        }
    }

    private fun startWifiCyclingWhileOffline() {
        wifiCycleJob?.cancel()
        wifiCycleJob = lifecycleScope.launch {
            while (!isNetworkAvailable) {
                delay(30_000L)
                if (isNetworkAvailable) break
                cycleWifi()
            }
            Log.d("WifiCycle", "Stopped — network restored")
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun cycleWifi() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm == null) {
            Log.w("WifiCycle", "WifiManager unavailable")
            return
        }
        try {
            val offOk = wm.setWifiEnabled(false)
            Log.d("WifiCycle", "setWifiEnabled(false) → $offOk")
            delay(3000L)
            val onOk = wm.setWifiEnabled(true)
            Log.d("WifiCycle", "setWifiEnabled(true) → $onOk")
        } catch (e: SecurityException) {
            Log.e("WifiCycle", "SecurityException toggling wifi: ${e.message}")
        } catch (e: Exception) {
            Log.e("WifiCycle", "Error toggling wifi: ${e.message}")
        }
    }

    /**
     * Fallback for devices that stop attempting to reconnect to wifi on their own.
     * 5 minutes after going offline (and every 5 minutes thereafter while still offline),
     * scan for in-range networks and try each saved config one-by-one. Won't prompt for
     * new networks or passwords — only attempts configs already saved on the device.
     */
    private fun startSavedNetworkFallback() {
        savedNetworkFallbackJob?.cancel()
        savedNetworkFallbackJob = lifecycleScope.launch {
            delay(5 * 60 * 1000L)
            while (!isNetworkAvailable) {
                tryConnectToSavedInRangeNetworks()
                if (isNetworkAvailable) break
                delay(5 * 60 * 1000L)
            }
            Log.d("WifiFallback", "Stopped — network restored or job cancelled")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun tryConnectToSavedInRangeNetworks() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm == null) {
            Log.w("WifiFallback", "WifiManager unavailable")
            return
        }

        // Pause the radio-toggle cycle so it doesn't fight our scan / enableNetwork calls.
        // Will be resumed below if we exit without restoring connectivity.
        wifiCycleJob?.cancel()

        if (!wm.isWifiEnabled) {
            @Suppress("DEPRECATION")
            wm.setWifiEnabled(true)
            delay(3000L)
        }

        val savedConfigs: List<WifiConfiguration> = try {
            @Suppress("DEPRECATION")
            wm.configuredNetworks ?: emptyList()
        } catch (e: Exception) {
            Log.e("WifiFallback", "Cannot read configuredNetworks: ${e.message}")
            if (!isNetworkAvailable) startWifiCyclingWhileOffline()
            return
        }
        if (savedConfigs.isEmpty()) {
            Log.d("WifiFallback", "No saved networks on device")
            if (!isNetworkAvailable) startWifiCyclingWhileOffline()
            return
        }
        Log.d("WifiFallback", "Device has ${savedConfigs.size} saved network configs")

        val scanResults = scanForWifi(wm)
        if (scanResults.isEmpty()) {
            Log.d("WifiFallback", "Scan returned no in-range networks")
            if (!isNetworkAvailable) startWifiCyclingWhileOffline()
            return
        }

        val savedBySsid: Map<String, WifiConfiguration> = savedConfigs
            .filter { !it.SSID.isNullOrBlank() }
            .associateBy { it.SSID.removeSurrounding("\"") }

        val candidates: List<Pair<String, WifiConfiguration>> = scanResults
            .sortedByDescending { it.level }
            .mapNotNull { sr ->
                val ssid = sr.SSID?.removeSurrounding("\"") ?: return@mapNotNull null
                savedBySsid[ssid]?.let { ssid to it }
            }
            .distinctBy { it.first }

        if (candidates.isEmpty()) {
            Log.d("WifiFallback", "No saved networks are currently in range")
            if (!isNetworkAvailable) startWifiCyclingWhileOffline()
            return
        }
        Log.d("WifiFallback", "Will try ${candidates.size} saved in-range networks: ${candidates.map { it.first }}")

        for ((ssid, config) in candidates) {
            if (isNetworkAvailable) {
                Log.d("WifiFallback", "Network restored during attempts — stopping")
                return
            }
            Log.d("WifiFallback", "Attempting $ssid (netId=${config.networkId})")
            val enableOk = try {
                @Suppress("DEPRECATION")
                wm.enableNetwork(config.networkId, true)
            } catch (e: Exception) {
                Log.e("WifiFallback", "enableNetwork($ssid) threw: ${e.message}")
                false
            }
            Log.d("WifiFallback", "enableNetwork($ssid) → $enableOk")
            if (!enableOk) continue

            var waited = 0L
            while (waited < 25_000L) {
                delay(2_000L)
                waited += 2_000L
                if (isNetworkAvailable) {
                    Log.d("WifiFallback", "Connected via $ssid after ${waited}ms")
                    return
                }
            }
            Log.d("WifiFallback", "Timed out on $ssid (likely wrong password or weak signal) — trying next")
        }

        Log.d("WifiFallback", "Exhausted all saved in-range networks without success")
        if (!isNetworkAvailable) startWifiCyclingWhileOffline()
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanForWifi(wm: WifiManager): List<ScanResult> {
        val deferred = CompletableDeferred<List<ScanResult>>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true)
                val results: List<ScanResult> = try {
                    if (success) wm.scanResults ?: emptyList() else emptyList()
                } catch (e: Exception) {
                    Log.e("WifiFallback", "scanResults read failed: ${e.message}")
                    emptyList()
                }
                try { unregisterReceiver(this) } catch (_: Exception) {}
                deferred.complete(results)
            }
        }
        registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))

        val started = try {
            @Suppress("DEPRECATION")
            wm.startScan()
        } catch (e: Exception) {
            Log.e("WifiFallback", "startScan threw: ${e.message}")
            false
        }
        Log.d("WifiFallback", "startScan() → $started")

        val result = withTimeoutOrNull(15_000L) { deferred.await() }
        if (result == null) {
            try { unregisterReceiver(receiver) } catch (_: Exception) {}
            Log.w("WifiFallback", "Scan broadcast timed out — using cached scanResults")
            return try { wm.scanResults ?: emptyList() } catch (_: Exception) { emptyList() }
        }
        return result
    }

    fun handleNetworkLost() {
        when (networkRecoveryManager.onNetworkLost(isLoggedIn, settings.testMode)) {
            NetworkLostAction.Ignore -> return
            NetworkLostAction.ShowMaintenanceAndDismissSumUp -> {
                maintenanceReason = MaintenanceReason.NetworkOutage
                Log.d("NetworkRecovery", "Network lost — showing offline screen, dismissing SumUp activities")
                networkDismissJob?.cancel()
                networkDismissJob = lifecycleScope.launch {
                    repeat(6) { // Try for ~30 seconds
                        finishActivity(1)
                        delay(5000L)
                    }
                }
            }
        }
    }

    fun handleNetworkRestored() {
        networkDismissJob?.cancel()
        val wasTracking = networkRecoveryManager.isTrackingOutage
        when (networkRecoveryManager.onNetworkRestored(isLoggedIn)) {
            NetworkRestoredAction.Ignore -> {
                if (wasTracking && maintenanceReason == MaintenanceReason.NetworkOutage) {
                    maintenanceReason = null
                }
            }
            NetworkRestoredAction.ResumeNormally -> {
                if (maintenanceReason == MaintenanceReason.NetworkOutage) {
                    maintenanceReason = null
                }
                Log.d("NetworkRecovery", "Short downtime — resuming normally")
            }
            NetworkRestoredAction.AutoReinit -> {
                Log.d("NetworkRecovery", "Long downtime — auto-reinitializing")
                lifecycleScope.launch {
                    delay(2000L) // Brief pause for network to stabilise
                    performReinit()
                }
            }
        }
    }

    // ── Auto-restart on unrecoverable conditions ─────────────────────────────

    fun handleRestartResult(result: RestartResult, reason: String) {
        when (result) {
            RestartResult.BELOW_THRESHOLD -> {}
            RestartResult.RESTART -> {
                Log.w("AutoRestart", "Threshold reached — hard restart — reason: $reason")
                hardRestart(reason)
            }
            RestartResult.COOLDOWN_ACTIVE ->
                Log.w("AutoRestart", "Threshold reached but cooldown active — reason: $reason")
            RestartResult.MAX_RESTARTS -> {
                Log.e("AutoRestart", "Threshold reached but max restarts hit — giving up — reason: $reason")
                maintenanceReason = null
            }
        }
    }

    fun scheduleRestartCounterReset() {
        restartCountResetJob?.cancel()
        restartCountResetJob = lifecycleScope.launch {
            delay(settings.restartCountResetSec * 1000L)
            restartManager.clearCounters()
            Log.d("AutoRestart", "Restart counters cleared — system healthy")
        }
    }

    fun hardRestart(reason: String) {
        Log.w("AutoRestart", "Hard restarting app (restart #${restartManager.restartCount}) — reason: $reason")
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothReceiver?.let { unregisterReceiver(it) }
        autoPinJob?.cancel()
        pairingUnpinJob?.cancel()
        wifiReconnectJob?.cancel()
        networkDismissJob?.cancel()
        restartCountResetJob?.cancel()
        silentLoginWatchdogJob?.cancel()
        connectivityPollJob?.cancel()
        wifiCycleJob?.cancel()
        bluetoothCycleJob?.cancel()
        savedNetworkFallbackJob?.cancel()
        cardReaderPageTimeoutJob?.cancel()
        if (::updateManager.isInitialized) updateManager.dispose()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun showSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    fun resetScreensaver() {
        lastInteractionTime = System.currentTimeMillis()
        if (isScreensaverActive) {
            isScreensaverActive = false
            Log.d("Screensaver", "Deactivated by user interaction")
            finishActivity(2)
            // Always land on the donation grid when dismissing the screensaver,
            // regardless of where the operator had navigated to before walking
            // away. Anything left open in the settings stack is dropped.
            showCustomAmountScreen = false
            customAmountInput = ""
            isEditingSettings = false
            isPickingColor = false
            showSetupStatus = false
            setupStatusFromOffline = false
            showDonationHistory = false
            prepareCardReader()
        }
    }

    fun prepareCardReader() {
        if (!settings.testMode && isLoggedIn && !isCardReaderConnected && isNetworkAvailable) {
            SumUpAPI.prepareForCheckout()
            Log.d("SumUpPayment", "Card reader prepared for checkout")
        }
    }

    fun exportSettings(includeAffiliateKey: Boolean): String {
        val exportData = if (includeAffiliateKey) {
            mapOf(
                "settings" to settings,
                "affiliateKey" to affiliateKey
            )
        } else {
            mapOf(
                "settings" to settings
            )
        }
        return Gson().toJson(exportData)
    }

    fun importSettings(jsonString: String): Boolean {
        return try {
            val importData = Gson().fromJson(jsonString, Map::class.java)

            val settingsJson = Gson().toJson(importData["settings"])
            val importedSettings = Gson().fromJson(settingsJson, Settings::class.java)
            settings = importedSettings.copy(logoUri = null)
            saveSettings(settings)

            TranslationManager.setLanguage(TranslationManager.fromCode(settings.language))

            val importedKey = importData["affiliateKey"] as? String
            if (!importedKey.isNullOrBlank()) {
                affiliateKey = importedKey
                prefs.edit() { putString("affiliate_key", affiliateKey) }
            }

            true
        } catch (e: Exception) {
            Log.e("SettingsImport", "Error importing settings: ${e.message}")
            false
        }
    }

    fun authenticateWithBiometrics(context: Context, onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (settings.testMode) {
            onSuccess()
            return
        }
        val activity = context as? FragmentActivity ?: run {
            onSuccess()
            return
        }

        val executor = ContextCompat.getMainExecutor(context)

        biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    cancelAuthTimeout()
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    biometricPrompt?.cancelAuthentication()
                    onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    biometricPrompt?.cancelAuthentication()
                    onError("Authentication failed")
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Verify Identity")
            .setSubtitle("Please authenticate to edit settings")
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build()

        biometricPrompt?.authenticate(promptInfo)

        authTimeoutJob = lifecycleScope.launch {
            delay(30_000L)
            biometricPrompt?.cancelAuthentication()
            isEditingSettings = false
            val strings = TranslationManager.currentStrings()
            Toast.makeText(this@MainActivity, strings.authenticationTimedOut, Toast.LENGTH_SHORT).show()
        }
    }

    fun cancelAuthTimeout() {
        authTimeoutJob?.cancel()
    }

    fun onToggleSettings() {
        val wasEditing = isEditingSettings
        isEditingSettings = !isEditingSettings
        resetState = false
        if (wasEditing && isLoggedIn && isCardReaderConnected) {
            prepareCardReader()
        }
    }

    fun onSettingsChange(newSettings: Settings) {
        val previousLogoUri = settings.logoUri
        settings = newSettings
        saveSettings(settings)
        if (::updateManager.isInitialized) updateManager.refreshSettings(settings)
        if (newSettings.logoUri != previousLogoUri) {
            LogoColorExtractor.refresh(this, newSettings.logoUri)
        }
    }

    // ── Update flow entry points (called from UI) ──────────────────────────────

    fun onUpdateBadgeTapped() {
        authenticateWithBiometrics(
            this,
            onSuccess = { showUpdateConfirm = true },
            onError = { error -> Toast.makeText(this, error, Toast.LENGTH_SHORT).show() }
        )
    }

    fun onUpdateConfirmInstall() {
        showUpdateConfirm = false
        // Show the overlay BEFORE startUpdateNow so the user doesn't see the
        // settings screen briefly while preflight + download runs. Preflight
        // failures will flip it back off via onFinishInstall.
        showUpdatingOverlay = true
        // Tear down any pinning/lock-task state and the jobs that would re-pin
        // us, so the post-install relaunch isn't competing with a stale lock
        // task. Without this, MY_PACKAGE_REPLACED can land while the screen
        // is still in pinned mode and we end up at the lock screen.
        prepareForSelfUpdate()
        lifecycleScope.launch {
            updateManager.startUpdateNow().join()
        }
    }

    /**
     * Drops anything that would interfere with a clean install + relaunch:
     * - Cancels auto-pin and pairing-unpin coroutines so they can't fire mid-install.
     * - Stops any active lock task (app-pinning) so MY_PACKAGE_REPLACED's startActivity
     *   isn't blocked by a stale pinning state.
     * - Shows system bars so the relaunched app starts in a known UI state.
     */
    fun prepareForSelfUpdate() {
        Log.d("UpdatePrep", "Tearing down pinning/lock-task before self-install")
        autoPinJob?.cancel()
        pairingUnpinJob?.cancel()
        pairingUnpinJob = null
        if (isPinned) {
            try { stopLockTask() } catch (e: Exception) {
                Log.w("UpdatePrep", "stopLockTask failed: ${e.message}")
            }
            isPinned = false
        }
        showSystemBars()
    }

    /**
     * Inverse of [prepareForSelfUpdate]: restores the kiosk UI + pin schedule
     * after a failed install. The successful-install path doesn't need this
     * (process dies and the new build re-establishes everything in onCreate).
     */
    fun restoreAfterFailedUpdate() {
        Log.d("UpdatePrep", "Install failed — restoring system bars + auto-pin schedule")
        showUpdatingOverlay = false
        hideSystemBars()
        scheduleAutoPinIfReady()
    }

    fun onUpdateConfirmLater() {
        showUpdateConfirm = false
    }

    fun manualCheckForUpdates() {
        updateManager.refreshSettings(settings)
        updateManager.checkForUpdate(silent = false)
    }

    /** Translates [UpdateNotification] into a localised Toast on the main thread. */
    fun showUpdateNotification(n: com.sadaqah.kiosk.update.UpdateNotification) {
        runOnUiThread {
            val s = TranslationManager.currentStrings()
            val msg = when (n) {
                is com.sadaqah.kiosk.update.UpdateNotification.AlreadyLatest ->
                    s.noUpdatesAvailable
                is com.sadaqah.kiosk.update.UpdateNotification.UpdateAvailable ->
                    "${s.updateAvailable}: v${n.release.version}"
                is com.sadaqah.kiosk.update.UpdateNotification.CheckFailed ->
                    s.updateCheckFailedToast
                is com.sadaqah.kiosk.update.UpdateNotification.BatteryTooLow ->
                    s.updateBatteryTooLow
                is com.sadaqah.kiosk.update.UpdateNotification.NoNetwork ->
                    s.updateNoNetwork
                is com.sadaqah.kiosk.update.UpdateNotification.NotDeviceOwner ->
                    s.autoUpdateRequiresDeviceOwner
                is com.sadaqah.kiosk.update.UpdateNotification.InstallFailed ->
                    s.updateFailedToast
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    fun saveSettings(settings: Settings) {
        val json = Gson().toJson(settings)
        prefs.edit() { putString("settings", json) }
    }

    fun openColorPicker(label: String) {
        resetState = false
        isPickingColor = !isPickingColor
        if (isPickingColor) {
            colorLabel = label
        }
    }

    fun getColorSettingFromLabel(label: String): androidx.compose.ui.graphics.Color {
        return when (label) {
            "backgroundColor" -> androidx.compose.ui.graphics.Color(settings.backgroundColor)
            "patternColor" -> androidx.compose.ui.graphics.Color(settings.patternColor)
            "buttonColor" -> androidx.compose.ui.graphics.Color(settings.buttonColor)
            "buttonBorderColor" -> androidx.compose.ui.graphics.Color(settings.buttonBorderColor)
            else -> androidx.compose.ui.graphics.Color(0xFFFFFFFF)
        }
    }

    fun setColorSettingFromLabel(label: String, newColor: androidx.compose.ui.graphics.Color) {
        when (label) {
            "backgroundColor" -> onSettingsChange(settings.copy(backgroundColor = newColor.toArgb().toLong()))
            "patternColor" -> onSettingsChange(settings.copy(patternColor = newColor.toArgb().toLong()))
            "buttonColor" -> onSettingsChange(settings.copy(buttonColor = newColor.toArgb().toLong()))
            "buttonBorderColor" -> onSettingsChange(settings.copy(buttonBorderColor = newColor.toArgb().toLong()))
        }
    }

    fun setRefresh() {
        mustRefresh = !mustRefresh
    }

    fun showThankYouScreen() {
        showThankYou = true
        lifecycleScope.launch {
            delay(settings.thankYouDurationSec * 1000L)
            showThankYou = false
        }
    }

    fun logout() {
        prefs.edit() { remove("affiliate_key") }
        affiliateKey = ""
        isLoggedIn = false
        isCardReaderConnected = false
        firstLogIn = true
        isEditingSettings = false
        Log.d("Auth", "User logged out")
    }

    fun resetApp() {
        val strings = TranslationManager.currentStrings()
        if (!resetState) {
            Toast.makeText(this, strings.tapAgainToReset, Toast.LENGTH_SHORT).show()
            resetState = true
        } else {
            prefs.edit() { clear() }
            val intent = Intent(applicationContext, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            finish()
        }
    }
}

@Composable
fun AppUI(
    isLoggedIn: Boolean,
    isEditingSettings: Boolean,
    isPickingColor: Boolean,
    onToggleSettings: () -> Unit,
    affiliateKey: String,
    onAffiliateKeyChange: (String) -> Unit,
    onLogin: () -> Unit,
    authenticate: (String) -> Unit,
    connectCardReader: () -> Unit,
    makePayment: (String) -> Unit,
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    openColorPicker: (String) -> Unit,
    onResetApp: () -> Unit,
    getColorSetting: (String) -> androidx.compose.ui.graphics.Color,
    setColorSetting: (String, androidx.compose.ui.graphics.Color) -> Unit,
    colorLabel: String,
    mustRefresh: Boolean,
    onRefresh: () -> Unit,
    showThankYou: Boolean,
    authenticateWithBiometrics: (Context, () -> Unit, (String) -> Unit) -> Unit,
    showCustomAmountScreen: Boolean,
    customAmountInput: String,
    onCustomAmountChange: (String) -> Unit,
    onCustomAmountSubmit: (String) -> Unit,
    onShowCustomAmountScreen: (Boolean) -> Unit,
    reinitSumUp: () -> Unit,
    maintenanceReason: MaintenanceReason?,
    onOfflineSettingsClick: () -> Unit,
    isScreensaverActive: Boolean,
    onResetScreensaver: () -> Unit,
    onExportSettings: (Boolean) -> String,
    onImportSettings: (String) -> Boolean,
    isNetworkAvailable: Boolean,
    isPinned: Boolean,
    isBluetoothEnabled: Boolean,
    isCardReaderConnected: Boolean,
    showSetupStatus: Boolean,
    showDonationHistory: Boolean,
    onShowDonationHistory: (Boolean) -> Unit,
    donationHistory: DonationHistory,
    onShowSetupStatus: (Boolean) -> Unit,
    setupStatusFromOffline: Boolean,
    onExitSetupStatus: () -> Unit,
    onUnpinApp: () -> Unit,
    onPinApp: () -> Unit,
    onReconnectWifi: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onDisableBluetooth: () -> Unit,
    onActivateScreensaver: () -> Unit,
    onTestModeChange: (Boolean) -> Unit,
    onLogout: () -> Unit,
    updateState: UpdateState,
    showUpdatingOverlay: Boolean,
    showUpdateConfirm: Boolean,
    latestUpdate: ReleaseInfo?,
    hasUpdateAvailable: Boolean,
    currentVersionLabel: SemVer,
    availableReleases: List<ReleaseInfo>,
    scheduledInstallAtMs: Long?,
    onUpdateBadgeTapped: () -> Unit,
    onUpdateConfirmInstall: () -> Unit,
    onUpdateConfirmLater: () -> Unit,
    onManualCheckForUpdates: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // Update overlays take priority over everything else — once we commit an
    // install, the system will replace the app and kill the process; we want
    // the user to see "updating" rather than the donation grid going dark.
    if (showUpdatingOverlay) {
        UpdatingScreen(settings = settings, state = updateState)
        return
    }
    if (showUpdateConfirm && latestUpdate != null) {
        UpdateAvailableConfirmScreen(
            settings = settings,
            currentVersion = currentVersionLabel,
            currentVersionReleasedAt = "",
            target = latestUpdate,
            onInstallNow = onUpdateConfirmInstall,
            onLater = onUpdateConfirmLater
        )
        return
    }

    if (mustRefresh) {
        RefreshBackground(settings = settings, onRefresh = onRefresh)
    } else if (maintenanceReason == MaintenanceReason.Reinitializing) {
        MaintenanceScreen(settings = settings)
    } else if (maintenanceReason == MaintenanceReason.NetworkOutage) {
        NoInternetScreen(
            settings = settings,
            onOpenSettings = onOfflineSettingsClick
        )
    } else {
        when {
            isEditingSettings -> {
                when {
                    isPickingColor -> ColorPickerScreen(
                        initialColor = getColorSetting(colorLabel),
                        label = colorLabel,
                        onColorSelected = { label, color -> setColorSetting(label, color) },
                        onBack = { openColorPicker(colorLabel) },
                        settings = settings
                    )
                    showDonationHistory -> DonationHistoryScreen(
                        settings = settings,
                        history = donationHistory,
                        onSettingsChange = onSettingsChange,
                        onClearHistory = { donationHistory.clearAll() },
                        onBack = { onShowDonationHistory(false) }
                    )
                    showSetupStatus -> SetupStatusScreen(
                        isNetworkAvailable = isNetworkAvailable,
                        isBluetoothEnabled = isBluetoothEnabled,
                        isLoggedIn = isLoggedIn,
                        isCardReaderConnected = isCardReaderConnected,
                        settings = settings,
                        onBack = {
                            // When opened from the offline screen, "back" should
                            // exit the settings stack entirely (back to NoInternet
                            // or DonationGrid depending on connectivity), not drop
                            // into the regular SettingsScreen the user never asked for.
                            if (setupStatusFromOffline) onExitSetupStatus()
                            else onShowSetupStatus(false)
                        },
                        onConfigureWifi = {
                            onShowSetupStatus(false)
                            onReconnectWifi()
                        },
                        onEnableBluetooth = {
                            onShowSetupStatus(false)
                            onEnableBluetooth()
                        },
                        onDisableBluetooth = onDisableBluetooth
                    )
                    else -> SettingsScreen(
                        settings = settings,
                        onSettingsChange = onSettingsChange,
                        openColorPicker = openColorPicker,
                        onResetApp = onResetApp,
                        onBack = onToggleSettings,
                        onRefresh = onRefresh,
                        onExportSettings = onExportSettings,
                        onImportSettings = onImportSettings,
                        connectCardReader = connectCardReader,
                        isLoggedIn = isLoggedIn,
                        isPinned = isPinned,
                        onUnpinApp = onUnpinApp,
                        onPinApp = onPinApp,
                        onShowSetupStatus = { onShowSetupStatus(true) },
                        onShowDonationHistory = { onShowDonationHistory(true) },
                        onActivateScreensaver = onActivateScreensaver,
                        onTestModeChange = onTestModeChange,
                        onLogout = onLogout,
                        isNetworkAvailable = isNetworkAvailable,
                        isBluetoothEnabled = isBluetoothEnabled,
                        isCardReaderConnected = isCardReaderConnected,
                        currentVersion = "v$currentVersionLabel",
                        latestVersion = latestUpdate?.let { "v${it.version}" } ?: "",
                        updateAvailable = hasUpdateAvailable,
                        availableReleases = availableReleases,
                        scheduledInstallAtMs = scheduledInstallAtMs,
                        onCheckForUpdates = onManualCheckForUpdates,
                        onUpdateNow = onUpdateBadgeTapped
                    )
                }
            }
            // Offline guard: never show the login screen when we have no internet.
            // We can't validate a key offline anyway, and a SumUp login attempt
            // here would clobber the cached session. Test mode bypasses this so
            // the operator can demo the kiosk without WiFi.
            !isNetworkAvailable && !settings.testMode -> {
                NoInternetScreen(
                    settings = settings,
                    onOpenSettings = onOfflineSettingsClick
                )
            }
            // Login screen is only meaningful when there is no affiliate key.
            // A cached key + isLoggedIn=false (e.g. booted offline, just came
            // online) is handled by the auto-authenticate path in the polling
            // loop — we don't bounce the operator through a manual login form.
            affiliateKey.isBlank() && !settings.testMode -> {
                AffiliateLoginScreen(
                    affiliateKey = affiliateKey,
                    onKeyChange = onAffiliateKeyChange,
                    onLogin = onLogin,
                    authenticate = authenticate,
                    connectCardReader = connectCardReader,
                    onSettingsClick = {
                        authenticateWithBiometrics(
                            context,
                            { onToggleSettings() },
                            { error -> Toast.makeText(context, error, Toast.LENGTH_SHORT).show() }
                        )
                    },
                    settings = settings,
                    versionLabel = "v$currentVersionLabel"
                )
            }
            showCustomAmountScreen -> {
                CustomAmountNumpadScreen(
                    amount = customAmountInput,
                    onAmountChange = onCustomAmountChange,
                    onConfirm = { onCustomAmountSubmit(customAmountInput) },
                    onCancel = {
                        onShowCustomAmountScreen(false)
                        onCustomAmountChange("")
                    },
                    settings = settings
                )
            }
            showThankYou -> {
                ThankYouScreen(settings = settings)
            }
            else -> {
                if (isScreensaverActive) {
                    ScreensaverScreen(
                        style = settings.screensaverStyle,
                        settings = settings,
                        onTouch = onResetScreensaver
                    )
                } else if (!isNetworkAvailable || !isBluetoothEnabled) {
                    SetupStatusScreen(
                        isNetworkAvailable = isNetworkAvailable,
                        isBluetoothEnabled = isBluetoothEnabled,
                        isLoggedIn = isLoggedIn,
                        isCardReaderConnected = isCardReaderConnected,
                        settings = settings,
                        showBack = false,
                        onBack = {},
                        onConfigureWifi = onReconnectWifi,
                        onEnableBluetooth = onEnableBluetooth,
                        onDisableBluetooth = onDisableBluetooth
                    )
                } else {
                    DonationGridScreen(
                        onAmountSelected = { amount ->
                            onResetScreensaver()
                            makePayment(amount)
                        },
                        onSettingsClick = {
                            onResetScreensaver()
                            authenticateWithBiometrics(
                                context,
                                { onToggleSettings() },
                                { error -> Toast.makeText(context, error, Toast.LENGTH_SHORT).show() }
                            )
                        },
                        settings = settings,
                        onShowCustomAmountScreen = {
                            onResetScreensaver()
                            onShowCustomAmountScreen(it)
                        },
                        onReinitSumUp = {
                            onResetScreensaver()
                            reinitSumUp()
                        },
                        versionLabel = "v$currentVersionLabel",
                        updateAvailable = hasUpdateAvailable,
                        onUpdateBadgeTap = {
                            onResetScreensaver()
                            onUpdateBadgeTapped()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun RefreshBackground(
    settings: Settings,
    onRefresh: () -> Unit
) {
    androidx.compose.foundation.layout.Box(
        modifier = androidx.compose.ui.Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(settings.backgroundColor))
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(id = R.drawable.pattern),
            contentDescription = null,
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                androidx.compose.ui.graphics.Color(settings.patternColor),
                blendMode = androidx.compose.ui.graphics.BlendMode.Modulate
            )
        )
    }
    onRefresh()
}