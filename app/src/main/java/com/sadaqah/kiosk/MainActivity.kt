package com.sadaqah.kiosk

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import com.sadaqah.kiosk.recovery.*
import com.sadaqah.kiosk.screens.*
import com.sumup.merchant.reader.api.SumUpAPI
import com.sumup.merchant.reader.api.SumUpLogin
import com.sumup.merchant.reader.api.SumUpPayment
import com.sumup.merchant.reader.ReaderModuleCoreState
import com.sumup.merchant.reader.api.SumUpState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal

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
    private lateinit var restartManager: RestartManager
    private lateinit var networkRecoveryManager: NetworkRecoveryManager

    var settings: Settings = Settings()
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
    var setupStatusFromOffline by mutableStateOf(false)

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

        // Migration: GSON ignores Kotlin data-class defaults when deserialising, so
        // existing installs may still have the old 120s threshold (or 0 if the field
        // was never persisted). Bump anything below 5 min up to the new default.
        if (settings.longDowntimeThresholdSec < 300) {
            settings = settings.copy(longDowntimeThresholdSec = 300)
            saveSettings(settings)
        }

        val store = SharedPreferencesStore(prefs)
        restartManager = RestartManager(store, settings)
        networkRecoveryManager = NetworkRecoveryManager(settings)

        enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        startConnectivityPolling()
        if (!isNetworkAvailable) startWifiCyclingWhileOffline()

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
                        }
                    },
                    onLogout = { logout() }
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
            Toast.makeText(this, "Test mode: card reader simulated", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Test mode: reinit skipped", Toast.LENGTH_SHORT).show()
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

        // Explicitly disconnect the card reader transport via the SDK before reinit.
        // The SDK keeps the BLE transport alive for Air/Solo readers (shouldKeepConnectionAlive=true),
        // so without this, SumUpState.init() resets higher-level state while the transport remains,
        // causing "transport already initialized" → TRANSPORT_ERROR loop on the next payment.
        disconnectCardReader()
        delay(1500L)

        authenticate(affiliateKey, silent = true)
        delay(5000L)

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
                        if (isReconnectingWifi) {
                            isReconnectingWifi = false
                            wifiReconnectJob?.cancel()
                            delay(2000L)
                            startAppPinning()
                        } else {
                            scheduleAutoPinIfReady()
                        }
                        handleNetworkRestored()
                    } else {
                        autoPinJob?.cancel()
                        handleNetworkLost()
                        startWifiCyclingWhileOffline()
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
        settings = newSettings
        saveSettings(settings)
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
    onLogout: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

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
                        onActivateScreensaver = onActivateScreensaver,
                        onTestModeChange = onTestModeChange,
                        onLogout = onLogout
                    )
                }
            }
            !isLoggedIn -> {
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
                    settings = settings
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