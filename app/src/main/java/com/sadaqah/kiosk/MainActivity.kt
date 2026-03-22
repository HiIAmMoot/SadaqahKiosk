package com.sadaqah.kiosk

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
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
import com.sadaqah.kiosk.ui.theme.SumUpAppTheme
import com.google.gson.Gson
import com.sadaqah.kiosk.screens.*
import com.sumup.merchant.reader.api.SumUpAPI
import com.sumup.merchant.reader.api.SumUpLogin
import com.sumup.merchant.reader.api.SumUpPayment
import com.sumup.merchant.reader.api.SumUpState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal

class MainActivity : FragmentActivity() {
    private lateinit var prefs: SharedPreferences

    private var biometricPrompt: BiometricPrompt? = null
    private var authTimeoutJob: Job? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var connectivityManager: android.net.ConnectivityManager? = null
    private var autoPinJob: Job? = null
    private var wifiReconnectJob: Job? = null
    private var bluetoothReceiver: BroadcastReceiver? = null

    var settings: Settings = Settings()
    var isLoggedIn by mutableStateOf(false)
    var affiliateKey by mutableStateOf("")
    var isEditingSettings by mutableStateOf(false)
    var isPickingColor by mutableStateOf(false)
    var colorLabel by mutableStateOf("")
    var resetState by mutableStateOf(false)
    var mustRefresh by mutableStateOf(false)
    var showThankYou by mutableStateOf(false)
    var showMaintenanceScreen by mutableStateOf(false)
    var showCustomAmountScreen by mutableStateOf(false)
    var customAmountInput by mutableStateOf("")
    var isScreensaverActive by mutableStateOf(false)
    var lastInteractionTime by mutableStateOf(System.currentTimeMillis())
    var isNetworkAvailable by mutableStateOf(true)
    var firstLogIn by mutableStateOf(true)
    var isPinned by mutableStateOf(false)
    var isBluetoothEnabled by mutableStateOf(false)
    var isCardReaderConnected by mutableStateOf(false)
    var isReconnectingWifi by mutableStateOf(false)
    var isConnectingCardReader by mutableStateOf(false)
    var showSetupStatus by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        ColorHistory.init(prefs)

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

        enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                isNetworkAvailable = true
                Log.d("NetworkStatus", "Network available: $network")
                if (isReconnectingWifi) {
                    isReconnectingWifi = false
                    wifiReconnectJob?.cancel()
                    lifecycleScope.launch {
                        delay(2000L) // Brief pause for network to stabilise
                        startAppPinning()
                    }
                } else {
                    scheduleAutoPinIfReady()
                }
            }

            override fun onLost(network: android.net.Network) {
                // A single network was lost — check if any other network still has internet.
                // On phones with both WiFi and cellular, Android de-prioritises cellular when
                // WiFi is active, which fires onLost for cellular even though WiFi is fine.
                val cm = connectivityManager ?: return
                val stillConnected = cm.allNetworks.any { remaining ->
                    remaining != network &&
                    cm.getNetworkCapabilities(remaining)
                        ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                }
                isNetworkAvailable = stillConnected
                if (!stillConnected) autoPinJob?.cancel()
                Log.d("NetworkStatus", "Network lost: $network — still connected: $stillConnected")
            }
        }

        val networkRequest = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager!!.registerNetworkCallback(networkRequest, networkCallback!!)

        // Check current connectivity at startup instead of assuming true
        isNetworkAvailable = connectivityManager!!.allNetworks.any { network ->
            connectivityManager!!.getNetworkCapabilities(network)
                ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }

        @Suppress("DEPRECATION")
        isBluetoothEnabled = BluetoothAdapter.getDefaultAdapter()?.isEnabled == true

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

        setContent {
            SumUpAppTheme {
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(10000L) // Check every 10 seconds
                        val idleTime = System.currentTimeMillis() - lastInteractionTime
                        val screensaverDelay = settings.screensaverIdleTimeoutSec * 1000L

                        if (idleTime > screensaverDelay && !isScreensaverActive && isLoggedIn && !isEditingSettings && !showThankYou && !showMaintenanceScreen) {
                            isScreensaverActive = true
                            Log.d("Screensaver", "Activated after 5 minutes idle")
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
                    showMaintenanceScreen = showMaintenanceScreen,
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
                    onUnpinApp = ::unpinApp,
                    onPinApp = ::startAppPinning,
                    onReconnectWifi = ::startWifiReconnectFlow,
                    onEnableBluetooth = ::openBluetoothSettings,
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

        when (requestCode) {
            1 -> {
                if (resultCode == 1 && data != null) {
                    Toast.makeText(this, strings.logInSuccessful, Toast.LENGTH_SHORT).show()
                    prefs.edit() { putString("affiliate_key", affiliateKey) }
                    isLoggedIn = true
                    if (firstLogIn) {
                        connectCardReader()
                        firstLogIn = false
                    }
                } else {
                    val errorMessage = data?.getStringExtra(SumUpAPI.Response.MESSAGE) ?: "Unknown error"
                    val errorCode = data?.getIntExtra(SumUpAPI.Response.RESULT_CODE, -1) ?: -1
                    Log.e("SumUpLogin", "Login failed - Code: $errorCode, Message: $errorMessage")
                    Toast.makeText(this, "${strings.logInFailed}: $errorMessage", Toast.LENGTH_LONG).show()
                }
            }
            2 -> {
                if (resultCode == 1 && data != null) {
                    isCardReaderConnected = true
                    Toast.makeText(this, strings.deviceConnectionSuccessful, Toast.LENGTH_SHORT).show()
                    prepareCardReader()
                } else {
                    isCardReaderConnected = false
                    val errorMessage = data?.getStringExtra(SumUpAPI.Response.MESSAGE) ?: "Unknown error"
                    val errorCode = data?.getIntExtra(SumUpAPI.Response.RESULT_CODE, -1) ?: -1
                    Log.e("SumUpReader", "Connection failed - Code: $errorCode, Message: $errorMessage")

                    val userMessage = when {
                        errorMessage.contains("timeout", ignoreCase = true) -> strings.cardReaderTimeout
                        errorMessage.contains("not found", ignoreCase = true) -> strings.cardReaderNotFound
                        errorCode == SumUpAPI.Response.ResultCode.ERROR_NO_CONNECTIVITY -> strings.noConnection
                        else -> "${strings.connectionFailed}: $errorMessage"
                    }
                    Toast.makeText(this, userMessage, Toast.LENGTH_LONG).show()
                }
            }
            3 -> {
                if (resultCode == 1 && data != null) {
                    val txCode = data.getStringExtra(SumUpAPI.Response.TX_CODE)
                    Log.d("SumUpPayment", "Payment successful - TX Code: $txCode")
                    Toast.makeText(this, strings.paymentSuccessful, Toast.LENGTH_SHORT).show()
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

    fun startWifiReconnectFlow() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(AndroidSettings.Panel.ACTION_WIFI)
        } else {
            Intent(AndroidSettings.ACTION_WIFI_SETTINGS)
        }
        openSystemSettings(intent)
    }

    fun openBluetoothSettings() {
        openSystemSettings(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS))
    }

    fun unpinApp() {
        autoPinJob?.cancel()
        stopAppPinning()
    }

    fun activateScreensaver() {
        isEditingSettings = false
        isScreensaverActive = true
    }

    fun authenticate(affiliateKey: String) {
        if (settings.testMode) {
            isLoggedIn = true
            isCardReaderConnected = true
            return
        }
        SumUpState.init(this)
        val sumupLogin = SumUpLogin.builder(affiliateKey).build()
        SumUpAPI.openLoginActivity(this@MainActivity, sumupLogin, 1)
        Log.d("SumUpTest", "Login started...")
        scheduleDailyLoginReset(affiliateKey)
    }

    fun connectCardReader() {
        if (settings.testMode) {
            isCardReaderConnected = true
            Toast.makeText(this, "Test mode: card reader simulated", Toast.LENGTH_SHORT).show()
            return
        }
        isConnectingCardReader = true
        autoPinJob?.cancel()
        stopAppPinning()
        SumUpAPI.openCardReaderPage(this@MainActivity, 2)
    }

    fun scheduleDailyLoginReset(affiliateKey: String) {
        lifecycleScope.launch {
            val now = java.time.LocalDateTime.now()
            val today2am = now.toLocalDate().atTime(2, 0)
            val next2am = if (now < today2am) today2am else today2am.plusDays(1)

            val durationUntil2am = java.time.Duration.between(now, next2am).toMillis()

            delay(durationUntil2am)

            showMaintenanceScreen = true
            Log.d("SumUpDebug", "Scheduled reinit at 2am - showing maintenance screen")

            delay(500L)
            authenticate(affiliateKey)
            delay(5000L)


            showMaintenanceScreen = false
            Log.d("SumUpDebug", "Maintenance complete - returning to normal operation")
        }
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
        val strings = TranslationManager.currentStrings()
        if (settings.testMode) {
            Toast.makeText(this, "Test mode: reinit skipped", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, strings.reinitializing, Toast.LENGTH_SHORT).show()
            Log.d("SumUpDebug", "Manual reinit triggered")

            showMaintenanceScreen = true
            delay(500L)
            authenticate(affiliateKey)
            delay(5000L)


            showMaintenanceScreen = false
            Toast.makeText(this@MainActivity, strings.reinitialized, Toast.LENGTH_SHORT).show()
            Log.d("SumUpDebug", "Manual reinit complete")
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (isReconnectingWifi) {
            isReconnectingWifi = false
            wifiReconnectJob?.cancel()
            if (isNetworkAvailable && isBluetoothEnabled) {
                startAppPinning()
            }
        }
        if (isConnectingCardReader) {
            isConnectingCardReader = false
            scheduleAutoPinIfReady()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        super.onDestroy()
        networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        bluetoothReceiver?.let { unregisterReceiver(it) }
        autoPinJob?.cancel()
        wifiReconnectJob?.cancel()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    fun resetScreensaver() {
        lastInteractionTime = System.currentTimeMillis()
        if (isScreensaverActive) {
            isScreensaverActive = false
            Log.d("Screensaver", "Deactivated by user interaction")
            prepareCardReader()
        }
    }

    fun prepareCardReader() {
        if (!settings.testMode && isLoggedIn) {
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
        isEditingSettings = !isEditingSettings
        resetState = false
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
    showMaintenanceScreen: Boolean,
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
    onUnpinApp: () -> Unit,
    onPinApp: () -> Unit,
    onReconnectWifi: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onActivateScreensaver: () -> Unit,
    onTestModeChange: (Boolean) -> Unit,
    onLogout: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    if (mustRefresh) {
        RefreshBackground(settings = settings, onRefresh = onRefresh)
    } else if (showMaintenanceScreen) {
        MaintenanceScreen(settings = settings)
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
                        onBack = { onShowSetupStatus(false) },
                        onConfigureWifi = {
                            onShowSetupStatus(false)
                            onReconnectWifi()
                        },
                        onEnableBluetooth = {
                            onShowSetupStatus(false)
                            onEnableBluetooth()
                        }
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
                        isNetworkAvailable = isNetworkAvailable,
                        onNetworkWarningClick = {
                            onResetScreensaver()
                            onReconnectWifi()
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