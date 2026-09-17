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
import android.graphics.BitmapFactory
import android.net.Uri
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.sadaqah.kiosk.model.Settings
import com.sadaqah.kiosk.model.SettingsBootstrap
import com.sadaqah.kiosk.model.SettingsImport
import com.sadaqah.kiosk.provisioning.ProvisioningLoader
import com.sadaqah.kiosk.provisioning.ProvisioningOutcome
import com.sadaqah.kiosk.provisioning.ProvisioningResult
import com.sadaqah.kiosk.ui.theme.SadaqahKioskTheme
import com.google.gson.Gson
import com.sadaqah.kiosk.donations.DonationHistory
import com.sadaqah.kiosk.recovery.*
import com.sadaqah.kiosk.screens.*
import com.sadaqah.kiosk.settingsio.ImportResult
import com.sadaqah.kiosk.settingsio.SettingsExportFile
import com.sadaqah.kiosk.telemetry.*
import com.sadaqah.kiosk.update.ReleaseInfo
import com.sadaqah.kiosk.update.SemVer
import com.sadaqah.kiosk.update.UpdateManager
import com.sadaqah.kiosk.update.UpdateState
import com.sadaqah.kiosk.update.UpdateWatchdogReceiver
import java.time.Instant
import com.sumup.merchant.reader.api.SumUpAPI
import com.sumup.merchant.reader.api.SumUpLogin
import com.sumup.merchant.reader.api.SumUpPayment
import com.sumup.merchant.reader.ReaderModuleCoreState
import com.sumup.merchant.reader.api.SumUpState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.math.BigDecimal
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.Executors

private const val INACTIVITY_BOUNCE_MS = 5L * 60 * 1000 // 5 min on settings / custom-amount → back to donation

/** How often a flush is retried while the screensaver stays up. The screensaver
 *  coming up already flushes once; this exists because that attempt can be
 *  refused by a per-table backoff of up to 60 minutes, and only the tables
 *  actually backed off would otherwise sit untried until 02:00 — a donation
 *  queued behind a refused diagnostics table is not waiting on that ceiling
 *  at all. */
private const val TELEMETRY_FLUSH_TICK_MS = 30L * 60 * 1000 // 30 min

/** How long the Bluetooth radio may stay off before the watchdog switches it back on.
 *  The kiosk cannot take donations without it, so recovery is worth more than patience.
 *  Comfortably clear of the ~5 s deliberate cycle in [MainActivity.cycleBluetoothAdapter]. */
private const val BLUETOOTH_OFF_RECOVERY_MS = 60L * 1000

/** The two reads [AnalyticsPresenter.view] needs beyond `settings`, cached
 *  together so they land in Activity state as one atomic assignment rather
 *  than two (which would let composition observe one refreshed and one
 *  stale). Deliberately not a data class: [TelemetryConfig]'s own `toString`
 *  redacts the key, and a generated holder `toString`/`equals` would print or
 *  compare the config directly, defeating that. */
private class AnalyticsSnapshot(val config: TelemetryConfig?, val status: TelemetryStatus)

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
    private var bluetoothWatchdogJob: Job? = null

    /** Labels for a finishActivity() the app issues itself, so the result can be
     *  told from one the operator caused. One slot per request code: a reinit can
     *  have a login and a reader close in flight at once, and a single slot would
     *  let the later arm evict the earlier and then be discarded as a mismatch.
     *
     *  Armed ONLY when that activity is actually outstanding. finishActivity is a
     *  no-op otherwise, and an arm with no callback coming would survive to
     *  mislabel the next genuine failure — which is exactly what resetScreensaver
     *  would do on every screensaver dismissal. The login slot is additionally
     *  cleared immediately before each openLoginActivity launch (see authenticate()),
     *  closing that window for request code 1; the reader slot has no such clear
     *  and relies on isConnectingCardReader instead. */
    private var syntheticCloseReader: String? = null   // request code 2
    private var syntheticCloseLogin: String? = null    // request code 1

    private val bluetoothRecoveryManager = BluetoothRecoveryManager(BLUETOOTH_OFF_RECOVERY_MS)

    /** Amount of the most recently initiated payment, stashed at makePayment() time. */
    private var lastPaymentAmount: BigDecimal? = null
    lateinit var donationHistory: DonationHistory
        private set
    private lateinit var restartManager: RestartManager
    private lateinit var networkRecoveryManager: NetworkRecoveryManager

    // Telemetry (phase 2c wiring — see TelemetryManager for the send sequence).
    // Constructed lazily, off the same filesDir/{context} mechanisms the rest of
    // the app already uses: a subdirectory beside DonationHistory's, the Keystore
    // for credentials, plain prefs for status.
    private val telemetryOutbox: TelemetryOutbox by lazy {
        TelemetryOutbox(
            File(File(filesDir, "telemetry").apply { mkdirs() }, "outbox.jsonl"),
            protectedTables = setOf(TelemetryTables.DONATIONS),
            onDropped = ::onOutboxDropped
        )
    }
    // Backs both the crash handler and the diagnostic helper below. A field
    // assigned in onCreate, not a property initializer: filesDir is unusable
    // before attachBaseContext runs, which is before field initializers do.
    // Shares onOutboxDropped with telemetryOutbox above: 3c-i turned this
    // instance from a dying-thread-only crash sink into a routine append path
    // for every recovery diagnostic, so a compaction it triggers can now shed
    // donation rows exactly as easily as the other instance can, and
    // droppedCount is the operator's only visibility into that loss.
    private lateinit var crashOutbox: TelemetryOutbox

    /** Can fire from a background thread and from inside the outbox's own
     *  lock, so this stays a small, non-blocking update on the status store
     *  and never calls back into the outbox. Going through update() rather
     *  than a hand-written read-then-write also closes the race between two
     *  of these landing concurrently (Ruling BA) and the larger one against an
     *  in-flight flush (TelemetryManager.flush reads its own snapshot before a
     *  network call that can run for minutes) — both are now the same
     *  "someone else wrote first" case update() exists to handle. */
    private fun onOutboxDropped(count: Int) {
        telemetryStatusStore.update { it.copy(droppedCount = it.droppedCount + count) }
    }

    private val telemetryCredentials: TelemetryCredentials by lazy {
        TelemetryCredentials(KeystoreSecretStore(this))
    }
    private val telemetryStatusStore: TelemetryStatusStore by lazy { PrefsStatusStore(this) }
    private val telemetryManager: TelemetryManager by lazy {
        TelemetryManager(
            outbox = telemetryOutbox,
            credentials = telemetryCredentials,
            statusStore = telemetryStatusStore,
            posterFor = { UrlConnectionPoster() },
            runtime = {
                TelemetryRuntime(
                    enabled = settings.analyticsEnabled,
                    activated = settings.analyticsActivatedAtMs != 0L,
                    identity = EventIdentity.from(settings, BuildConfig.VERSION_NAME),
                    privacyPolicyUrl = settings.analyticsPrivacyPolicyUrl,
                    termsUrl = settings.analyticsTermsUrl
                )
            },
            networkAvailable = { isOnlineNow() }
        )
    }

    /**
     * Serialises every flush against every other flush and against `activate()`.
     *
     * [TelemetryManager] documents itself as unsafe for concurrent callers: it
     * keeps its last upload outcome and last attempted ids in plain fields, and
     * `activate()` reads them to tell a row it never sent apart from one it sent
     * and could not confirm. Before this phase only the Test button called into
     * it; now a timer does too, and two callers on `Dispatchers.IO` could
     * interleave and let one read the other's outcome. One thread makes that
     * impossible by construction rather than by a lock no test can reach.
     *
     * Held through a `lazy` rather than `by lazy` so [onDestroy] can close it
     * only if something actually used it — a kiosk that never configures
     * analytics never spawns the thread.
     */
    private val telemetryFlushDispatcherLazy = lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "telemetry-flush") }
            .asCoroutineDispatcher()
    }
    private val telemetryFlushDispatcher get() = telemetryFlushDispatcherLazy.value
    private var telemetryFlushTickerJob: Job? = null

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
    var showAnalyticsSettings by mutableStateOf(false)
    // Null means "no disclosure to show" — set only from onShowAnalyticsSettings's
    // exit branch (consuming disclosurePendingUrl) or from the explicit reopen row,
    // never restored across process death: the trigger is a save, not a visit.
    // Cleared wherever the settings stack is torn down out from under the
    // operator (inactivity bounce, screensaver) so a stale disclosure never
    // opens on a later, unrelated visit.
    var disclosureView by mutableStateOf<DisclosureView?>(null)
    // Armed by a successful destination save, carrying the exact URL the save
    // just verified — not a boolean re-deriving it later from analyticsSnapshot,
    // which is only refreshed asynchronously (Keystore decrypt + outbox parse)
    // and can still hold the *previous* destination, or none yet, at the moment
    // this is consumed. Consumed (and cleared) the moment the operator
    // deliberately leaves the analytics screen, not on every keystroke that
    // follows in the policy-URL fields.
    var disclosurePendingUrl by mutableStateOf<String?>(null)
    // The two expensive reads behind the analytics screen (Keystore decrypt,
    // full outbox parse) cached in Activity state so composition only ever
    // does the pure AnalyticsPresenter.view call. Null means "not loaded" —
    // either the screen has never been opened, or closeAnalyticsSettings()
    // just cleared it so the decrypted key isn't held for the process's life.
    // Refreshed by refreshAnalyticsSnapshot(); never read from a Keystore or
    // the outbox directly inside setContent.
    private var analyticsSnapshot by mutableStateOf<AnalyticsSnapshot?>(null)
    // "Now" as the presenter sees it — stamped alongside analyticsSnapshot, and
    // ticked once a second by the backoff ticker (see startAnalyticsBackoffTicker)
    // instead of being read fresh from System.currentTimeMillis() in composition.
    private var analyticsNowMs by mutableLongStateOf(System.currentTimeMillis())
    private var analyticsBackoffTickerJob: Job? = null
    var analyticsTestState by mutableStateOf<TestConnectionState>(TestConnectionState.Idle)
    var setupStatusFromOffline by mutableStateOf(false)
    var showUpdateConfirm by mutableStateOf(false)
    var showUpdatingOverlay by mutableStateOf(false)
    lateinit var updateManager: UpdateManager
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        ColorHistory.init(prefs)

        // Provision, then restart. Deliberately not applied into this onCreate:
        // SecretsCrypto runs 600_000 PBKDF2 iterations, which is seconds on kiosk
        // hardware and a guaranteed ANR on the main thread — and onCreate reads
        // `settings` into seven consumers with contradictory ordering needs
        // (SettingsBootstrap must follow provisioning, authenticate() and
        // LogoColorExtractor must precede it, and RestartManager /
        // NetworkRecoveryManager / UpdateManager each snapshot settings by value
        // at construction). There is no slot that satisfies all of them.
        //
        // Restarting dissolves that instead of solving it: boot 2 is an ordinary
        // startup reading provisioned settings from disk in the normal order. It
        // also fixes the migration blocks below, which string-match the STORED
        // json — after a restart that json already holds the provisioned values,
        // so they no longer overwrite what provisioning just applied.
        if (isProvisioningRequested(intent)) {
            runProvisioning(intent)
            return
        }

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

        val json = prefs.getString("settings", null)
        settings = parseStoredSettings(json)

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

        // Deliberately below the settings load above, not beside the
        // connectivity setup where it used to sit. `settings` is a field with a
        // default initialiser, so reading settings.testMode before the stored
        // JSON is parsed always saw `false`: the bypass never fired on a cold
        // start, the else branch always ran, and authenticate() went out to
        // SumUp for real — opening the SDK's own login activity on top of a
        // kiosk that was supposed to be in test mode.
        //
        // authenticate() carries the same guard internally and was reading the
        // same unloaded value, so neither layer caught it.
        if (settings.testMode) {
            isLoggedIn = true
            isCardReaderConnected = true
        } else {
            val storedKey = prefs.getString("affiliate_key", null)
            if (!storedKey.isNullOrEmpty()) {
                affiliateKey = storedKey
                CrashContext.affiliateKey = affiliateKey
                authenticate(affiliateKey)
            }
        }

        // Kick off logo colour extraction so the picker has swatches ready
        // by the time the operator opens it. Idempotent — repeat calls on
        // the same URI are no-ops.
        LogoColorExtractor.refresh(this, settings.logoUri)

        donationHistory = DonationHistory(this)
        // Bootstrap this device's own installId and donation-stats anchor. Runs
        // unconditionally — not gated on "did we have stored settings?" — so a
        // genuinely fresh install gets both on its first boot, not its second.
        // Reset Averages will re-set the anchor later.
        val bootstrap = SettingsBootstrap.apply(settings, System.currentTimeMillis()) {
            java.util.UUID.randomUUID().toString()
        }
        if (bootstrap.changed) {
            settings = bootstrap.settings
            saveSettings(settings)
        }

        // The crash handler is process-global and outlives this Activity, which
        // is recreated on any configuration change. It reads this holder rather
        // than the Activity so it can never report against a dead instance.
        // Kept as an explicit seed rather than relying on saveSettings alone: on
        // a plain restart with no migration due, settings is loaded but never
        // saved this onCreate, and the handler installed below must still have
        // a holder to read.
        CrashContext.settings = settings

        // Install once: onCreate runs again on every Activity recreation, and a
        // handler whose `previous` is the last one would build a chain that
        // grows with each. Freshness comes from CrashContext, refreshed above.
        // Assigned unconditionally, ahead of the handler-install check below:
        // this Activity instance is recreated on every configuration change,
        // and a diagnostic reported from the new instance needs an outbox even
        // on a recreation that finds a handler already installed and skips
        // that block.
        // Its own instance, constructed eagerly: sharing the lazy telemetryOutbox
        // would let a crash — or a diagnostic — be what triggers its
        // initialisation, and lazy's SYNCHRONIZED mode can block if another
        // thread is mid-init. The outbox's lock is keyed on the file, so two
        // instances over one path are safe.
        //
        // The onDropped lambda below reads only CrashContext, making it
        // non-capturing. But the slot assigned here holds a bound reference to
        // this Activity's method, retaining one instance by design. Repointing
        // on every recreation prevents the reference from going stale across
        // configuration changes — a problem CrashContext.kt documents in detail.
        // The touch this closure makes when it fires — telemetryStatusStore's
        // `by lazy` and a possible first-load getSharedPreferences — runs from
        // inside the outbox's own monitor, on whatever thread dropped the
        // event, including a dying one. It can fire from any compaction now —
        // an interval sweep or a peek, not only an append that crossed
        // compactSlack — but it is still bounded by KioskCrashHandler's own
        // catch(Throwable), so it can delay the chain but never break it.
        CrashContext.onOutboxDropped = ::onOutboxDropped
        crashOutbox = TelemetryOutbox(
            File(File(filesDir, "telemetry").apply { mkdirs() }, "outbox.jsonl"),
            protectedTables = setOf(TelemetryTables.DONATIONS),
            onDropped = { CrashContext.onOutboxDropped?.invoke(it) }
        )

        val existingHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (existingHandler !is KioskCrashHandler) {
            Thread.setDefaultUncaughtExceptionHandler(
                KioskCrashHandler(
                    previous = existingHandler,
                    outbox = crashOutbox,
                    settings = { CrashContext.settings },
                    affiliateKey = { CrashContext.affiliateKey },
                    appVersion = BuildConfig.VERSION_NAME
                )
            )
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
            if (!json.contains("\"analyticsEnabled\"")) {
                migrated = migrated.copy(analyticsEnabled = false); dirty = true
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

        drainUpdateDiagnostics()

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
        startTelemetryFlushTicker()
        if (!isNetworkAvailable) {
            startWifiCyclingWhileOffline()
            startSavedNetworkFallback()
        }

        isBluetoothEnabled = bluetoothAdapter()?.isEnabled == true
        // Seed the watchdog so a device that boots with the radio already off
        // still recovers, rather than waiting for a STATE_OFF that never comes.
        if (!isBluetoothEnabled) bluetoothRecoveryManager.onBluetoothOff()

        bluetoothReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                isBluetoothEnabled = state == BluetoothAdapter.STATE_ON
                if (!isBluetoothEnabled) {
                    autoPinJob?.cancel()
                    bluetoothRecoveryManager.onBluetoothOff()
                } else {
                    bluetoothRecoveryManager.onBluetoothOn()
                    scheduleAutoPinIfReady()
                }
            }
        }
        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        startBluetoothWatchdog()

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
                            // The app's own definition of "nobody is using this",
                            // and it has already released the card reader on the
                            // line above — so a flush here cannot contend for the
                            // network with a live transaction.
                            flushTelemetry("screensaver")
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
                            closeAnalyticsSettings()
                            // A stale disclosure or a stale arm-to-show-it must not
                            // survive this — the trigger is a save during a real
                            // visit, not whatever happened to be sitting in state
                            // when a forgotten session got bounced.
                            disclosureView = null
                            disclosurePendingUrl = null
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
                    onAffiliateKeyChange = { affiliateKey = it; CrashContext.affiliateKey = affiliateKey },
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
                    onExportSettings = { include, password -> exportSettings(include, password) },
                    onImportSettings = { json, password -> importSettings(json, password) },
                    onImportSettingsOnly = { json -> importSettingsOnly(json) },
                    isNetworkAvailable = isNetworkAvailable,
                    isPinned = isPinned,
                    isBluetoothEnabled = isBluetoothEnabled,
                    isCardReaderConnected = isCardReaderConnected,
                    showSetupStatus = showSetupStatus,
                    onShowSetupStatus = { showSetupStatus = it },
                    showDonationHistory = showDonationHistory,
                    onShowDonationHistory = { showDonationHistory = it },
                    donationHistory = donationHistory,
                    showAnalyticsSettings = showAnalyticsSettings,
                    onShowAnalyticsSettings = { opening ->
                        if (opening) {
                            openAnalyticsSettings()
                        } else {
                            // Consumed here, on the deliberate exit, not inside
                            // closeAnalyticsSettings() itself — that function is
                            // also called by the inactivity bounce and by the
                            // screensaver path, and firing the disclosure there
                            // would throw it up during a teardown to the
                            // donation grid rather than a real visit. Reads the
                            // URL the arming save already verified, not the
                            // async-refreshed snapshot — that can still hold
                            // nothing, or the previous destination, at this point.
                            disclosurePendingUrl?.let { url ->
                                disclosureView = DisclosurePresenter.view(settings, url)
                            }
                            disclosurePendingUrl = null
                            closeAnalyticsSettings()
                        }
                    },
                    // Pure and cheap: both expensive inputs are already sitting in
                    // analyticsSnapshot/analyticsNowMs, refreshed by the entry points
                    // above rather than read here. See AnalyticsSnapshot's KDoc.
                    analyticsView = AnalyticsPresenter.view(
                        settings,
                        analyticsSnapshot?.config,
                        analyticsSnapshot?.status ?: TelemetryStatus(),
                        analyticsNowMs,
                        ZoneId.systemDefault(),
                        Locale.getDefault()
                    ),
                    analyticsTestState = analyticsTestState,
                    onAnalyticsToggleEnabled = { enabled -> onSettingsChange(settings.copy(analyticsEnabled = enabled)) },
                    onAnalyticsSaveDestination = { url, key ->
                        val verdict = telemetryCredentials.save(url, key)
                        refreshAnalyticsSnapshot()
                        // Only a stored destination is worth disclosing: a save
                        // the credentials layer rejected configured nothing, so
                        // naming a destination that was never kept would be
                        // worse than showing nothing. Armed with the URL itself,
                        // not shown yet — see onShowAnalyticsSettings's exit
                        // branch, which is what actually computes and shows the
                        // view once the visit that made this save is really
                        // over. Carrying verdict.normalised here, rather than
                        // re-deriving it from analyticsSnapshot at exit, matters:
                        // that snapshot only refreshes asynchronously behind a
                        // Keystore decrypt and a full outbox parse, so at exit it
                        // can still be blank (first-ever configuration) or the
                        // previous destination (re-pointing an existing kiosk).
                        if (verdict is UrlVerdict.Valid) {
                            disclosurePendingUrl = verdict.normalised
                        }
                        verdict
                    },
                    onAnalyticsTestConnection = ::onAnalyticsTestConnection,
                    // Normalised on the way in, not just validated. KioskCode's
                    // advisory check trims before matching, so an untrimmed code
                    // "looks conventional", raises no warning, and then ships on
                    // every row that kiosk sends — splitting one kiosk into two
                    // groups for whoever queries the table, with nothing on
                    // screen saying so.
                    onAnalyticsKioskCodeChange = { code ->
                        onSettingsChange(
                            settings.copy(
                                kioskCode = KioskCode.normalize(code),
                                // Typing here is the operator claiming the code for
                                // this kiosk, which is exactly the condition the
                                // imported-code warning exists to flag. Clearing it
                                // on edit is what stops the warning outliving it.
                                kioskCodeFromImport = false
                            )
                        )
                    },
                    onAnalyticsPolicyUrlsChange = { privacy, terms ->
                        onSettingsChange(settings.copy(analyticsPrivacyPolicyUrl = privacy, analyticsTermsUrl = terms))
                    },
                    onShowDisclosure = {
                        disclosureView = DisclosurePresenter.view(settings, analyticsSnapshot?.config?.baseUrl.orEmpty())
                    },
                    onAnalyticsClearCredentials = ::onAnalyticsClearCredentials,
                    disclosureView = disclosureView,
                    onDismissDisclosure = { disclosureView = null },
                    setupStatusFromOffline = setupStatusFromOffline,
                    onExitSetupStatus = ::exitSetupStatus,
                    onUnpinApp = ::unpinApp,
                    onPinApp = ::startAppPinning,
                    onReconnectWifi = ::startWifiReconnectFlow,
                    onEnableBluetooth = ::enableBluetooth,
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

        Log.d("ActivityResult", "requestCode=$requestCode resultCode=$resultCode")

        // Consumed before any branching, for the code being delivered only — the
        // other slot belongs to an activity that has not returned yet.
        val closedBy = when (requestCode) {
            1 -> syntheticCloseLogin.also { syntheticCloseLogin = null }
            2 -> syntheticCloseReader.also { syntheticCloseReader = null }
            else -> null
        }

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
                    Log.e("SumUpLogin", "Login failed - Code: $errorCode")
                    Toast.makeText(this, "${strings.logInFailed}: $errorMessage", Toast.LENGTH_LONG).show()
                    val result = restartManager.recordReinitFailure()
                    reportRestart(
                        result,
                        DiagnosticKind.SUMUP_REINIT_FAILED,
                        {
                            DiagnosticEvents.sumUpFailureDetail(
                                errorCode,
                                DiagnosticEvents.truncateWrappedMessage(TelemetryRedactor.scrub(errorMessage, CrashContext.affiliateKey)),
                                closedBy = closedBy
                            )
                        },
                        "reinit_failures"
                    )
                    handleRestartResult(result, "reinit_failures")
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
                    val result = restartManager.recordCardReaderFailure()
                    reportRestart(
                        result,
                        DiagnosticKind.CARD_READER_CONNECT_FAILED,
                        {
                            DiagnosticEvents.sumUpFailureDetail(
                                errorCode,
                                DiagnosticEvents.truncateWrappedMessage(TelemetryRedactor.scrub(errorMessage, CrashContext.affiliateKey)),
                                closedBy = closedBy
                            )
                        },
                        "card_reader_failures"
                    )
                    handleRestartResult(result, "card_reader_failures")
                }
            }
            3 -> {
                if (resultCode == 1 && data != null) {
                    Toast.makeText(this, strings.paymentSuccessful, Toast.LENGTH_SHORT).show()
                    restartManager.clearCounters()
                    scheduleRestartCounterReset()
                    // Append to donation history if tracking is on.
                    if (settings.donationTrackingEnabled) {
                        lastPaymentAmount?.let { donationHistory.append(it) }
                    }
                    // A sibling of the line above, never nested inside it:
                    // donationTrackingEnabled governs the on-panel history
                    // screen and nothing else. Tying telemetry to it would mean
                    // an operator who hides the local history for privacy at the
                    // panel silently stops all remote reporting too. Telemetry
                    // has its own master switch, checked inside eventFor.
                    lastPaymentAmount?.let { appendDonationTelemetry(it) }
                    lastPaymentAmount = null
                    showThankYouScreen()
                } else {
                    val errorMessage = data?.getStringExtra(SumUpAPI.Response.MESSAGE) ?: "Unknown error"
                    val errorCode = data?.getIntExtra(SumUpAPI.Response.RESULT_CODE, -1) ?: -1
                    Log.e("SumUpPayment", "Payment failed - Code: $errorCode")

                    val userMessage = when (errorCode) {
                        SumUpAPI.Response.ResultCode.ERROR_TRANSACTION_FAILED -> strings.transactionDeclined
                        SumUpAPI.Response.ResultCode.ERROR_NO_CONNECTIVITY -> strings.noInternetConnection
                        SumUpAPI.Response.ResultCode.ERROR_NOT_LOGGED_IN -> strings.notLoggedIn
                        SumUpAPI.Response.ResultCode.ERROR_GEOLOCATION_REQUIRED -> strings.locationRequired
                        else -> "${strings.paymentFailed}: $errorMessage"
                    }
                    Toast.makeText(this, userMessage, Toast.LENGTH_LONG).show()
                    // isCardReaderConnected (the field) is unusable here: disconnectCardReader()
                    // sets it false from the idle screensaver path and never restores it, so it
                    // reads false in steady state and would fire on every declined card. Ask the
                    // SDK directly, same call as the poll and the code-2 arm above.
                    val readerPresent = try {
                        ReaderModuleCoreState.Instance()?.mReaderCoreManager?.isCardReaderConnected() == true
                    } catch (t: Throwable) { false }
                    if (!readerPresent) {
                        reportDiagnostic(DiagnosticKind.CHECKOUT_NO_READER, detail = {
                            DiagnosticEvents.checkoutNoReaderDetail(
                                errorCode,
                                DiagnosticEvents.truncateWrappedMessage(TelemetryRedactor.scrub(errorMessage, CrashContext.affiliateKey))
                            )
                        })
                    }
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
                if (isConnectingCardReader) syntheticCloseReader = "reader_poll"
                finishActivity(2)
            } else {
                scheduleCardReaderPoll(btManager, gattBefore)
            }
        }
    }

    fun startWifiReconnectFlow() {
        openSystemSettings(Intent(AndroidSettings.Panel.ACTION_WIFI))
    }

    private fun isDeviceOwner(): Boolean =
        (getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager)
            ?.isDeviceOwnerApp(packageName) == true

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter

    /**
     * Flips the radio without any UI. Only device owners may do this silently,
     * so callers that can't fall back gracefully must check [isDeviceOwner]
     * first. Returns whether the adapter accepted the request.
     */
    @SuppressLint("MissingPermission")
    private fun setBluetoothEnabledHeadless(enabled: Boolean): Boolean {
        val adapter = bluetoothAdapter()
        if (adapter == null) {
            Log.w("Bluetooth", "BluetoothAdapter unavailable")
            return false
        }
        return try {
            @Suppress("DEPRECATION")
            val ok = if (enabled) adapter.enable() else adapter.disable()
            Log.d("Bluetooth", "adapter.${if (enabled) "enable" else "disable"}() → $ok")
            ok
        } catch (e: SecurityException) {
            Log.e("Bluetooth", "SecurityException toggling bluetooth: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e("Bluetooth", "Error toggling bluetooth: ${e.message}")
            false
        }
    }

    /**
     * Operator tapped the Bluetooth button on the setup-status screen. On a
     * device-owner install this flips the radio in place; otherwise we hand off
     * to the system Bluetooth panel exactly as before, since a plain install
     * has no way to do it silently.
     */
    private fun onBluetoothToggleRequested(enable: Boolean) {
        if (isDeviceOwner() && setBluetoothEnabledHeadless(enable)) return
        openSystemSettings(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS))
    }

    fun enableBluetooth() = onBluetoothToggleRequested(enable = true)

    fun disableBluetooth() = onBluetoothToggleRequested(enable = false)

    /**
     * Switches the radio back on when it has been off longer than
     * [BLUETOOTH_OFF_RECOVERY_MS]. Runs only on device-owner installs — without
     * that privilege the only recovery is the system panel, and opening it
     * unattended would drop an unmanned kiosk onto an Android settings screen.
     */
    private fun startBluetoothWatchdog() {
        if (!isDeviceOwner()) {
            Log.d("BluetoothWatchdog", "Not device owner — auto re-enable unavailable")
            return
        }
        bluetoothWatchdogJob?.cancel()
        bluetoothWatchdogJob = lifecycleScope.launch {
            while (true) {
                delay(10_000L)
                // Never race the deliberate off → on cycle; it re-enables by itself.
                val action = bluetoothRecoveryManager.evaluate(
                    cycleInProgress = bluetoothCycleJob?.isActive == true
                )
                if (action == BluetoothRecoveryAction.ReEnable) {
                    Log.w("BluetoothWatchdog", "Bluetooth off too long — re-enabling")
                    setBluetoothEnabledHeadless(true)
                    // Only the first re-enable of an outage. The manager counts
                    // them; a radio that stays dead must not fill the queue.
                    if (bluetoothRecoveryManager.reEnablesThisOutage == 1) {
                        reportDiagnostic(DiagnosticKind.BLUETOOTH_WATCHDOG_FIRED, detail = {
                            DiagnosticEvents.bluetoothWatchdogDetail(
                                bluetoothRecoveryManager.lastOffMs
                            )
                        })
                    }
                }
            }
        }
    }

    fun unpinApp() {
        autoPinJob?.cancel()
        stopAppPinning()
    }

    fun activateScreensaver() {
        isEditingSettings = false
        // Same reasoning as the inactivity bounce: leaving the settings stack
        // here is not a dismissal, so a disclosure (or an armed-but-unshown one)
        // must not linger to surface on whatever settings screen is opened next.
        disclosureView = null
        disclosurePendingUrl = null
        isScreensaverActive = true
        if (isConnectingCardReader) syntheticCloseReader = "screensaver"
        finishActivity(2)
        disconnectCardReader()
        // Same reasoning as the idle path: the reader is released, so this is a
        // safe moment. Not funnelled through a shared helper with that site —
        // one lives inside a composition-scoped LaunchedEffect and this is an
        // activity method, and a wrapper across that boundary costs more than
        // the duplicated call.
        flushTelemetry("screensaver")
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

    private fun cycleBluetoothAdapter() {
        bluetoothCycleJob?.cancel()
        bluetoothCycleJob = lifecycleScope.launch {
            if (bluetoothAdapter() == null) {
                Log.w("BluetoothCycle", "BluetoothAdapter unavailable — skip cycle")
                return@launch
            }
            setBluetoothEnabledHeadless(false)
            // Wait for the STATE_OFF broadcast (updates isBluetoothEnabled = false)
            // before turning it back on, so the radio is genuinely off mid-cycle.
            val offDeadline = System.currentTimeMillis() + 5_000L
            while (isBluetoothEnabled && System.currentTimeMillis() < offDeadline) {
                delay(200L)
            }
            delay(1000L)
            setBluetoothEnabledHeadless(true)
            // Wait for STATE_ON so callers that join() this job know the radio
            // is actually back up, not just that enable() was queued.
            val onDeadline = System.currentTimeMillis() + 8_000L
            while (!isBluetoothEnabled && System.currentTimeMillis() < onDeadline) {
                delay(200L)
            }
            Log.d("BluetoothCycle", "Cycle complete — adapter ON: $isBluetoothEnabled")
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
        // The watchdog's own arming is deferred past the launch below (silent
        // branch only cancels here) — arming before openLoginActivity could
        // never return would leave a stale job if init or the launch threw.
        if (silent) {
            silentLoginWatchdogJob?.cancel()
        } else {
            startPairingUnpin()
        }
        // If openLoginActivity ever returns without launching, the watchdog
        // below arms a label that finishActivity cannot clear — and the next
        // genuine login failure then reports as self-inflicted. Whether the SDK
        // can do that is unknowable from this repo, and this does not need to
        // know: every genuine code-1 result comes from a launch, and every
        // launch now clears first — placed ahead of init/builder too, so a throw
        // from either still leaves no stale label (though also no launch, so no
        // result to mislabel either way).
        //
        // One case it does not cover: a re-entrant authenticate while a login is
        // still outstanding erases a legitimately armed label, so a synthetic
        // close reads as genuine. That is the safe direction — a missing
        // discriminator, not a false one.
        syntheticCloseLogin = null
        SumUpState.init(this)
        val sumupLogin = SumUpLogin.builder(affiliateKey).build()
        SumUpAPI.openLoginActivity(this@MainActivity, sumupLogin, 1)
        if (silent) {
            // Reinit / scheduled refresh: keep pinning, rely on cached SumUp credentials
            // for a transparent re-auth behind the maintenance screen. Whitelisted SumUp
            // package can launch under lock task. Watchdog dismisses the activity if it
            // stalls (e.g. cached creds expired and SumUp shows the real login form).
            silentLoginWatchdogJob = lifecycleScope.launch {
                delay(10_000L)
                Log.w("SumUpLogin", "Silent login watchdog — forcing finish on stuck login")
                // No isOutstanding check needed: none of this job's three cancel
                // sites can leave this line reachable with no login outstanding.
                // cancelSilentLoginWatchdog() (code-1 arm) only runs once the
                // result has arrived, so the login is already settled by then; the
                // re-arm above (this function, silent branch) replaces this job
                // with one that arms in its own right; onDestroy's cancel stops
                // the job before it ever reaches here. Reaching this line
                // uncancelled therefore still means the login is outstanding —
                // and this job is only ever scheduled after openLoginActivity
                // above returns, the same ordering that closed the analogous gap
                // on the code-2 side (isConnectingCardReader moved past
                // openCardReaderPage), so a throw from the call itself can never
                // arm it. The no-launch case is covered by the clear right before
                // openLoginActivity above.
                syntheticCloseLogin = "login_watchdog"
                finishActivity(1)
            }
        }
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
        startPairingUnpin()
        SumUpAPI.openCardReaderPage(this@MainActivity, 2)
        // Set after the launch, not before: arming first would latch this
        // true for the life of the process if openCardReaderPage returned
        // without starting an activity, mislabelling every later screensaver
        // arm that reads it (:1005, :1772).
        isConnectingCardReader = true
        scheduleCardReaderPoll(btManager, gattBefore)
        // 10-minute timeout: if the operator walks away from the SumUp pairing
        // dialog, force-close it. prepareCardReader() runs every 5 min and
        // will pick the reader up silently in the background once it's nearby.
        cardReaderPageTimeoutJob?.cancel()
        cardReaderPageTimeoutJob = lifecycleScope.launch {
            delay(10 * 60 * 1000L)
            if (isConnectingCardReader) {
                Log.d("SumUpReader", "Card reader page timed out after 10 min — closing")
                reportDiagnostic(DiagnosticKind.CARD_READER_PAGE_TIMEOUT, detail = {
                    DiagnosticEvents.pageTimeoutDetail()
                })
                syntheticCloseReader = "pairing_timeout"
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
            // The floor. A kiosk busy enough never to idle for
            // screensaverIdleTimeoutSec during opening hours reports here and
            // nowhere else, so this must sit outside the try above — an update
            // maintenance failure must not also cost the nightly flush.
            flushTelemetry("nightly")
        }
    }

    fun onOfflineSettingsClick() {
        authenticateWithBiometrics(
            this,
            onSuccess = {
                // Leave maintenanceReason untouched — AppUI now renders the
                // settings tree above the NoInternet branch, so this open is
                // non-destructive and exiting settings lands back on
                // NoInternetScreen until the network is restored.
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
                        // isLoggedIn is already true here (onNetworkLost's own gate), so the
                        // only login that can still be outstanding is a silent reinit — the
                        // interactive flow only runs pre-login. The watchdog job is that
                        // reinit's own outstanding-marker; re-checked each pass since the
                        // first finishActivity may need a few passes to land.
                        if (silentLoginWatchdogJob?.isActive == true) syntheticCloseLogin = "teardown"
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
                reportDiagnostic(DiagnosticKind.NETWORK_OUTAGE, detail = {
                    DiagnosticEvents.networkOutageDetail(
                        downtimeMs = networkRecoveryManager.lastOutageMs,
                        // The manager's own threshold, not the Activity's live
                        // Settings field: a provisioning import can change the
                        // latter mid-session, after the comparison already ran.
                        thresholdMs = networkRecoveryManager.longDowntimeThresholdMs
                    )
                })
                lifecycleScope.launch {
                    delay(2000L) // Brief pause for network to stabilise
                    performReinit()
                }
            }
        }
        // Outside the `when`, so every restore path flushes: exactly when a
        // backlog can finally move. Edge-triggered — the connectivity poll calls
        // this only on a genuine online/offline transition (`online !=
        // isNetworkAvailable`), so it does not fire every second while online.
        // The kiosk was offline a moment ago, so it was not mid-transaction.
        flushTelemetry("network-restored")
    }

    // ── Auto-restart on unrecoverable conditions ─────────────────────────────

    /**
     * Executes [RestartReporting.restartReport]'s decision; makes none of its
     * own.
     *
     * The whole body is wrapped, not just the marker write: this call sits
     * immediately before [handleRestartResult] may trigger [hardRestart], and
     * a throw anywhere in here — the decision, the write, or the dispatch —
     * must not be what stops the kiosk from restarting. [detail] is a lambda
     * for the same reason [reportDiagnostic]'s is: built eagerly, it would run
     * in the caller's frame, before this guard exists.
     */
    private fun reportRestart(
        result: RestartResult,
        kind: DiagnosticKind,
        detail: () -> String,
        reason: String
    ) {
        try {
            val now = System.currentTimeMillis()
            val report = RestartReporting.restartReport(
                result = result,
                causing = PendingDiagnostic(java.util.UUID.randomUUID().toString(), kind, now, detail()),
                reason = reason,
                alreadyGaveUp = restartManager.gaveUpReported,
                nowMs = now,
                idFor = { java.util.UUID.randomUUID().toString() }
            )
            // toMarker is empty on arms that do not mark (everything except RESTART),
            // so this guard skips a synchronous disk write on those.
            if (report.toMarker.isNotEmpty()) {
                // Same prefs file drainUpdateDiagnostics() reads at startup —
                // see PendingDiagnosticStore's own comment on why that file,
                // not `prefs`.
                PendingDiagnosticStore(UpdateWatchdogReceiver.prefs(this)).add(report.toMarker)
            }
            report.toReportNow.forEach { entry ->
                reportDiagnostic(entry.kind, { entry.detailJson }, entry.occurredAtMs)
            }
            if (report.gaveUpReported) restartManager.markGaveUpReported()
        } catch (t: Throwable) {
            Log.e("AutoRestart", "reportRestart failed: ${t::class.java.name}")
        }
    }

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

    /** Restart after provisioning. Separate from [hardRestart] so the restart
     *  does not read as an auto-recovery event: hardRestart logs under
     *  "AutoRestart" against restartManager's counter, and a provisioning
     *  relaunch is neither a failure nor something the give-up latch should
     *  ever see. */
    private fun relaunchAfterProvisioning() {
        Log.i("Provisioning", "Provisioning complete, restarting into a normal boot")
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    /** Parses the persisted settings JSON, or `Settings()` when nothing is
     *  stored yet (a genuinely fresh device). Pulled out of onCreate's own load
     *  so provisioning's decision can read the exact same thing rather than
     *  the field's construction-time default.
     *
     *  `current` matters here specifically because `SettingsImport.merge`
     *  preserves `installId`, `donationStatsStartedAtMs` and
     *  `analyticsActivatedAtMs` FROM `current` — never from the imported file.
     *  Handing it a blank `Settings()` makes a second provisioning run on an
     *  already-booted kiosk look like a brand new device: `SettingsBootstrap`
     *  then mints a fresh installId, silently splitting that device's history
     *  in two, which is exactly what those fields' preservation exists to
     *  prevent. This file has shipped that exact class of bug before — `:343`
     *  once read `settings.testMode` before the stored JSON was parsed, so the
     *  branch was dead on every cold start — so do not move this call back
     *  above the settings load, and do not let the provisioning gate above
     *  read `settings` directly again. */
    private fun parseStoredSettings(json: String?): Settings =
        if (!json.isNullOrEmpty()) Gson().fromJson(json, Settings::class.java) else Settings()

    private val provisioningDir: File
        get() = File(getExternalFilesDir(null), "provisioning")

    private fun isProvisioningRequested(source: Intent): Boolean =
        !source.getStringExtra("provision_run_id").isNullOrBlank() &&
            source.getStringExtra("provision_password") != null

    private fun runProvisioning(source: Intent) {
        val runId = source.getStringExtra("provision_run_id").orEmpty()
        val password = source.getStringExtra("provision_password")
        val codeOverride = source.getStringExtra("provision_kiosk_code")
        val nameOverride = source.getStringExtra("provision_kiosk_name")

        // Consumed immediately. getIntent() otherwise returns these extras for
        // the life of the Activity AND across every recreation — a rotation
        // after a successful provision would re-enter this path, find the
        // payload deleted, and overwrite an `applied` result with
        // `failed / no_payload`.
        setIntent(Intent(source).apply { replaceExtras(Bundle()) })

        setContent {
            Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) {
                Text("Provisioning…", color = Color.White, fontSize = 24.sp)
            }
        }

        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                // Off the main thread because SecretsCrypto runs 600_000 PBKDF2
                // iterations — seconds on kiosk hardware, and an ANR here would
                // kill the process with settings possibly written and the result
                // certainly not.
                val payloadFile = File(provisioningDir, "kiosk.json")
                val json = if (payloadFile.isFile) payloadFile.readText() else null
                // The device's REAL stored settings, not the `settings` field's
                // construction-time default — see parseStoredSettings's doc.
                val current = parseStoredSettings(prefs.getString("settings", null))
                ProvisioningLoader.decide(current, json, password, codeOverride, nameOverride)
            }
            applyProvisioning(runId, outcome, password)
            relaunchAfterProvisioning()
        }
    }

    private suspend fun applyProvisioning(
        runId: String,
        outcome: ProvisioningOutcome,
        password: String?
    ) {
        val now = Instant.now().toString()
        if (outcome is ProvisioningOutcome.Failed) {
            // The payload is deliberately kept so the operator can retry with a
            // corrected password rather than re-pushing.
            writeProvisioningResult(
                ProvisioningResult(
                    runId = runId, status = ProvisioningResult.FAILED, at = now,
                    appVersion = BuildConfig.VERSION_NAME, reason = outcome.reason
                )
            )
            return
        }

        val apply = outcome as ProvisioningOutcome.Apply
        val payloadFile = File(provisioningDir, "kiosk.json")

        // Routed through importSettings rather than reimplemented: :1979 sets
        // CrashContext.affiliateKey, and a parallel path that skipped it would
        // disarm the crash handler's scrub for exactly the key just imported,
        // shipping the payment credential into crash reports on every kiosk.
        val imported = withContext(Dispatchers.Default) {
            importSettings(payloadFile.readText(), password)
        }
        val credentialsOk = imported is ImportResult.Success

        // importSettings merges without the overrides, so re-apply them over it.
        settings = apply.settings
        // installId must exist before the result names it: on a first provision
        // the device has never completed a boot, so SettingsBootstrap has never
        // run and the field is still blank.
        val bootstrapped = SettingsBootstrap.apply(settings, System.currentTimeMillis()) {
            java.util.UUID.randomUUID().toString()
        }
        settings = bootstrapped.settings

        val logo = copyProvisionedLogo()
        settings = settings.copy(logoUri = logo?.uri)
        saveSettings(settings)

        writeProvisioningResult(
            ProvisioningResult(
                runId = runId, status = ProvisioningResult.APPLIED, at = now,
                appVersion = BuildConfig.VERSION_NAME,
                installId = settings.installId,
                kioskCode = settings.kioskCode,
                kioskName = settings.kioskName.orEmpty(),
                affiliateKeyRestored = credentialsOk && affiliateKey.isNotBlank(),
                destinationConfigured = telemetryCredentials.isConfigured(),
                logoApplied = logo != null,
                logoDecodable = logo?.decodable == true
            )
        )

        payloadFile.delete()
        File(provisioningDir, "logo").delete()
    }

    private fun writeProvisioningResult(result: ProvisioningResult) {
        try {
            provisioningDir.mkdirs()
            File(provisioningDir, "provision-result.json").writeText(result.toJson())
        } catch (e: Exception) {
            // Not rolled back: the import succeeded, and undoing it would be
            // worse than an unreported success.
            Log.w("Provisioning", "Could not write the result file: ${e.message}")
        }
    }

    private data class ProvisionedLogo(val uri: String, val decodable: Boolean)

    /** Copies the pushed image into internal storage and returns where it
     *  landed, or null when no image was pushed.
     *
     *  The destination is a FIXED name with NO extension, and the directory is
     *  emptied first: a preserved extension would defeat the fixed name, so
     *  re-provisioning a .png over a .jpg would leave the .jpg behind forever,
     *  unreferenced. LogoColorExtractor.decode infers format from content. */
    private fun copyProvisionedLogo(): ProvisionedLogo? {
        val source = File(provisioningDir, "logo")
        if (!source.isFile) return null
        val dir = File(filesDir, "logo").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val dest = File(dir, "kiosk-logo")
        return try {
            source.copyTo(dest, overwrite = true)
            ProvisionedLogo(
                uri = Uri.fromFile(dest).toString(),
                decodable = BitmapFactory.decodeFile(dest.absolutePath) != null
            )
        } catch (e: Exception) {
            Log.w("Provisioning", "Could not copy the logo: ${e.message}")
            null
        }
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
        bluetoothWatchdogJob?.cancel()
        telemetryFlushTickerJob?.cancel()
        // Only if something actually flushed — otherwise this would spawn the
        // thread purely in order to shut it down.
        if (telemetryFlushDispatcherLazy.isInitialized()) telemetryFlushDispatcher.close()
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
            // This runs on every dismissal, almost always with no pairing page open —
            // finishActivity is then a no-op and no callback ever arrives, so the arm
            // must be conditional or a stale "screensaver" label would sit here forever
            // and get approved by the next genuine code-2 failure.
            if (isConnectingCardReader) syntheticCloseReader = "screensaver"
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
            closeAnalyticsSettings()
            // Load-bearing, not just consistent with the other two teardown
            // sites (the inactivity bounce, and activateScreensaver()):
            // disclosureView's own render branch (`disclosureView != null`)
            // and disclosurePendingUrl's only arming site (onAnalyticsSaveDestination,
            // reachable only while showAnalyticsSettings is true) both sit
            // nested inside isEditingSettings — they are siblings, not one
            // gated on the other, but neither can be non-null once
            // isEditingSettings is false. isEditingSettings is set false a few
            // lines above, so whenever the settings stack got torn down out
            // from under the operator with one of these still set, this is
            // one of the three places that clears it. Without that, a
            // disclosure armed or shown during this visit would still be
            // sitting in state the next time settings is opened, on a later,
            // unrelated visit. Guarded by the enclosing `if
            // (isScreensaverActive)`, so it can only run once the screensaver
            // has actually taken over — never wiping a disclosure a
            // still-present operator is looking at.
            disclosureView = null
            disclosurePendingUrl = null
            prepareCardReader()
        }
    }

    fun prepareCardReader() {
        if (!settings.testMode && isLoggedIn && !isCardReaderConnected && isNetworkAvailable) {
            SumUpAPI.prepareForCheckout()
            Log.d("SumUpPayment", "Card reader prepared for checkout")
        }
    }

    /**
     * Serialises settings, encrypting secrets under [password] when
     * [includeSecrets] is set. Runs key derivation, so call it off the UI thread.
     */
    fun exportSettings(includeSecrets: Boolean, password: String): String {
        val secrets = if (includeSecrets) {
            buildMap {
                if (affiliateKey.isNotBlank()) put(SettingsExportFile.KEY_AFFILIATE, affiliateKey)
                // The reporting destination travels with the credentials it
                // belongs to. Without it, the documented fleet workflow —
                // configure one kiosk, export, import onto the rest — produced
                // kiosks that looked configured and silently never reported,
                // because the destination lives in the Keystore and nothing
                // carried it across. `build` drops blank values, so an
                // unconfigured kiosk simply contributes nothing here.
                telemetryCredentials.load()?.let { config ->
                    put(SettingsExportFile.KEY_TELEMETRY_URL, config.baseUrl)
                    put(SettingsExportFile.KEY_TELEMETRY_KEY, config.publishableKey)
                }
            }
        } else {
            emptyMap()
        }
        return SettingsExportFile.build(settings, secrets, password.ifBlank { null })
    }

    /**
     * Applies an exported file. Nothing is written until the whole file has been
     * parsed and decrypted, so a wrong password leaves the device untouched.
     * Runs key derivation, so call it off the UI thread.
     */
    fun importSettings(jsonString: String, password: String?): ImportResult {
        val result = SettingsExportFile.parse(jsonString, password)
        if (result !is ImportResult.Success) return result

        settings = SettingsImport.merge(settings, result.settings)
        saveSettings(settings)
        TranslationManager.setLanguage(TranslationManager.fromCode(settings.language))

        result.secrets[SettingsExportFile.KEY_AFFILIATE]?.takeIf { it.isNotBlank() }?.let { key ->
            affiliateKey = key
            prefs.edit { putString("affiliate_key", key) }
            // A stale key here disarms the crash handler's exact-match scrub
            // for exactly the key that was just imported.
            CrashContext.affiliateKey = key
        }

        // Goes through the credentials layer rather than writing the Keystore
        // directly, so an imported URL faces exactly the same validation an
        // operator-typed one does. A file carrying a destination this build
        // rejects leaves the previous one in place rather than half-applying.
        val importedUrl = result.secrets[SettingsExportFile.KEY_TELEMETRY_URL]
        val importedKey = result.secrets[SettingsExportFile.KEY_TELEMETRY_KEY]
        if (!importedUrl.isNullOrBlank() && !importedKey.isNullOrBlank()) {
            when (val verdict = telemetryCredentials.save(importedUrl, importedKey)) {
                is UrlVerdict.Valid -> refreshAnalyticsSnapshot()
                is UrlVerdict.Invalid ->
                    Log.w("SettingsImport", "Imported telemetry URL rejected: ${verdict.reason}")
            }
        }
        return result
    }

    /** Applies configuration from an export while leaving its encrypted secrets behind. */
    fun importSettingsOnly(jsonString: String): ImportResult {
        val result = SettingsExportFile.parseSettingsOnly(jsonString)
        if (result !is ImportResult.Success) return result
        settings = SettingsImport.merge(settings, result.settings)
        saveSettings(settings)
        TranslationManager.setLanguage(TranslationManager.fromCode(settings.language))
        return result
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
        saveSettings(settings) // also refreshes CrashContext.settings
        if (::updateManager.isInitialized) updateManager.refreshSettings(settings)
        if (newSettings.logoUri != previousLogoUri) {
            LogoColorExtractor.refresh(this, newSettings.logoUri)
        }
    }

    // ── Analytics settings entry points (called from UI) ───────────────────────

    /**
     * Loads the two expensive analytics inputs (Keystore decrypt, full outbox
     * parse) off the main thread and stamps `analyticsNowMs` alongside them as
     * one atomic assignment, then runs [then]. Every action that used to bump
     * `analyticsRefreshVersion` — save, test, clear — now goes through this
     * instead, so those reads happen once per action rather than once per
     * recomposition of the whole app.
     */
    private fun refreshAnalyticsSnapshot(then: (() -> Unit)? = null) {
        lifecycleScope.launch {
            // Checked BEFORE the work, not only after it: since phase 3a every
            // automatic flush calls this, and `load()` is a Keystore decrypt while
            // `status()` parses the whole outbox file. Returning here is what makes
            // those costs belong to the analytics screen rather than to every
            // flush a closed-screen kiosk performs. The identical check below is
            // NOT redundant — it catches the operator backing out mid-flight.
            if (then == null && !showAnalyticsSettings) return@launch
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    AnalyticsSnapshot(telemetryCredentials.load(), telemetryManager.status())
                }
                // Guards the one case `then` doesn't cover: onAnalyticsTestConnection's
                // network call can outlive the screen (operator backs out mid-test).
                // Without this, its completion would land here and repopulate the
                // holder with a freshly-decrypted key after closeAnalyticsSettings()
                // nulled it — reviving exactly what that null was for. `then != null`
                // is the open flow, where showAnalyticsSettings is still false at this
                // point by design (see openAnalyticsSettings), so it must not be caught
                // by this check.
                if (then == null && !showAnalyticsSettings) return@launch
                analyticsSnapshot = snapshot
                analyticsNowMs = System.currentTimeMillis()
                then?.invoke()
                startAnalyticsBackoffTickerIfNeeded()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // status() reads the outbox file and load() touches the Keystore;
                // this coroutine is its own, so flushTelemetry's catch cannot cover
                // it, and nothing above has a caller left to handle a throw. An
                // escape reaches the default handler and kills an unattended kiosk.
                // Only the class name — never lastError, never a key, never a payload.
                Log.e("Telemetry", "analytics snapshot refresh failed: ${t::class.java.name}")
            }
        }
    }

    /** Refreshes *before* showing the screen, in the continuation, so it never
     *  renders a frame from a null/stale holder — a configured kiosk flashing
     *  "Not configured" for one frame would be worse than the few milliseconds
     *  of delay this costs. */
    fun openAnalyticsSettings() {
        refreshAnalyticsSnapshot { showAnalyticsSettings = true }
    }

    /** Nulls the holder so the decrypted key is not held for the rest of the
     *  process's life, clears a test-connection result that may describe a
     *  press from long before this visit, and stops the backoff ticker rather
     *  than leaving it running against a screen nobody can see. */
    fun closeAnalyticsSettings() {
        showAnalyticsSettings = false
        analyticsSnapshot = null
        analyticsTestState = TestConnectionState.Idle
        analyticsBackoffTickerJob?.cancel()
        analyticsBackoffTickerJob = null
    }

    /**
     * Ticks `analyticsNowMs` once a second while the screen is open and the
     * kiosk is actually backing off, so "Retrying in 45s" counts down instead
     * of freezing at whatever it read on entry. The presenter call in
     * composition is pure, so this is nearly free — but it is not free enough
     * to run unconditionally: that would re-execute the whole `setContent`
     * scope every second for a countdown nobody is looking at. So this only
     * starts when there is something to count down, and stops itself the
     * moment the backoff elapses.
     */
    private fun startAnalyticsBackoffTickerIfNeeded() {
        val backoffUntilMs = analyticsSnapshot?.status?.effectiveBackoffUntilMs(analyticsNowMs) ?: 0L
        if (!showAnalyticsSettings || backoffUntilMs <= analyticsNowMs) return
        if (analyticsBackoffTickerJob?.isActive == true) return
        analyticsBackoffTickerJob = lifecycleScope.launch {
            while (true) {
                delay(1000L)
                analyticsNowMs = System.currentTimeMillis()
                val stillBackingOff =
                    (analyticsSnapshot?.status?.effectiveBackoffUntilMs(analyticsNowMs) ?: 0L) > analyticsNowMs
                if (!stillBackingOff) break
            }
        }
    }

    /**
     * "Test connection" and activation are the same operator action. activate()
     * does network I/O (see UrlConnectionPoster's KDoc), so it must not run on
     * the main thread — done here via lifecycleScope + Dispatchers.IO, the same
     * mechanism the rest of MainActivity uses for background work.
     */
    fun onAnalyticsTestConnection() {
        analyticsTestState = TestConnectionState.Running
        lifecycleScope.launch {
            // The flush dispatcher, not Dispatchers.IO: activate() reads the
            // manager's last-outcome fields, and a scheduled flush running
            // concurrently on a shared pool could overwrite them between this
            // call's own flush and its read.
            val result = withContext(telemetryFlushDispatcher) { telemetryManager.activate() }
            val strings = TranslationManager.currentStrings()
            analyticsTestState = when (result) {
                is ActivationResult.Succeeded -> {
                    // The screen would otherwise keep reporting "not yet
                    // reporting" after a proven-good test.
                    // Stamped once. The field names when this kiosk began reporting, and
                    // overwriting it on every later test press would make that drift
                    // forward forever, so it would never answer the question it exists for.
                    if (settings.analyticsActivatedAtMs == 0L) {
                        onSettingsChange(settings.copy(analyticsActivatedAtMs = System.currentTimeMillis()))
                    }
                    TestConnectionState.Succeeded(strings.analyticsTestSucceeded)
                }
                is ActivationResult.Queued -> TestConnectionState.Queued(strings.analyticsTestQueued)
                is ActivationResult.Blocked -> TestConnectionState.Blocked(analyticsBlockedMessage(result.reason, strings))
                // result.error is already redacted by the uploader, but the screen
                // renders view.error (from status) for that — never the raw string here.
                is ActivationResult.Failed -> TestConnectionState.Failed(strings.analyticsTestFailed)
            }
            refreshAnalyticsSnapshot()
        }
    }

    /** Copy for a [FlushBlock] reason that only becomes true at the moment of the
     *  press — [AnalyticsPresenter] already covers NOT_CONFIGURED/DISABLED before
     *  the press via [AnalyticsView.testUnavailable].
     *
     *  FIX (I2): `TelemetryManager.activate()` now evaluates the gate *before*
     *  mutating anything, against inputs that force `activated = true` and a
     *  queue depth that already counts the row about to be appended.
     *  `GateInputs` no longer carries a backoff field at all — the gate stopped
     *  knowing about backoff in phase 3d-i — so there is nothing to force there;
     *  what stands in for it is `activate()`'s reset, which clears every
     *  table's failure state before the internal `flush()` call ever re-reads
     *  them for its exclusion set. That pre-check is the only source of a
     *  `Blocked` result today, and it can only ever produce `DISABLED`,
     *  `NOT_CONFIGURED` or `NO_NETWORK` — the other inputs can't fail. So
     *  `BACKING_OFF`, `NOT_ACTIVATED`, `EMPTY_QUEUE` and `NONE` are all
     *  unreachable out of `activate()`: the internal `flush()` call that
     *  follows a passing pre-check sees the same forced-true inputs and a
     *  freshly-cleared backoff state (`TelemetryManager` is documented as not
     *  meant for concurrent callers, so nothing else can change them in
     *  between) and cannot itself return a non-`NONE` block for this call to
     *  wrap. They still get one honest, destination-agnostic message rather
     *  than being treated as unreachable `when` branches that might one day
     *  silently start firing. */
    private fun analyticsBlockedMessage(reason: FlushBlock, strings: Strings): String = when (reason) {
        FlushBlock.NO_NETWORK -> strings.noInternetConnection
        FlushBlock.NOT_CONFIGURED -> strings.analyticsTestUnavailableNotConfigured
        FlushBlock.DISABLED -> strings.analyticsTestUnavailableDisabled
        FlushBlock.BACKING_OFF, FlushBlock.NOT_ACTIVATED, FlushBlock.EMPTY_QUEUE, FlushBlock.NONE ->
            strings.analyticsBackingOff
    }

    fun onAnalyticsClearCredentials() {
        TelemetryTeardown.clearEverything(telemetryCredentials, telemetryOutbox, telemetryStatusStore)
        refreshAnalyticsSnapshot()
        // An arm from a destination saved earlier in this same visit must not
        // outlive a clear: DisclosurePresenter.view only needs a non-blank URL
        // and a non-blank privacy policy URL, neither of which this touches, so
        // without this the exit branch would still show a destination this
        // function just tore down. disclosureView needs no clearing here — a
        // non-null one wins the switch chain, so this control is unreachable
        // while one is showing.
        disclosurePendingUrl = null
    }

    /**
     * Queues one completed donation for telemetry, off the main thread, and
     * never fails the donation flow.
     *
     * Every decision lives in [DonationEvents.eventFor] — including whether to
     * report at all — because this method cannot be unit-tested and a decision
     * here is a decision nothing checks.
     *
     * `settings` is read on the main thread and captured before the launch:
     * it is Compose state, and reading it from a background dispatcher would be
     * a cross-thread read of a `mutableStateOf`.
     *
     * Deliberately NOT on `telemetryFlushDispatcher`: [TelemetryOutbox] is
     * synchronized per file path so a concurrent append is already safe, and a
     * flush holds that lock only across `peek` and `remove`, never across the
     * upload. Sharing the flush thread would park this row behind an upload
     * that can run for minutes in the per-row fallback, for no benefit. A row
     * appended between a flush's `peek` and `remove` is harmless — `remove`
     * deletes only the ids the uploader named.
     */
    private fun appendDonationTelemetry(amount: BigDecimal) {
        val settingsNow = settings
        val occurredAtMs = System.currentTimeMillis()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                when (val result = DonationEvents.eventFor(
                    settingsNow, BuildConfig.VERSION_NAME, amount, occurredAtMs
                )) {
                    // The normal state of most kiosks. Nothing is written to disk.
                    DonationEventResult.NotEnabled -> Unit

                    DonationEventResult.AmountUnrepresentable -> {
                        // The scale, never the value: a log line is not a redacted
                        // sink, and the amount is donor-adjacent data.
                        Log.e("Telemetry", "donation not representable in cents (scale=${amount.scale()})")
                        recordTelemetryLoss()
                    }

                    is DonationEventResult.Report -> try {
                        telemetryOutbox.append(
                            result.event.id,
                            result.event.table,
                            result.event.payloadJson()
                        )
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        // TelemetryOutbox.append documents that it throws on a full
                        // disk or a failed mkdirs, that the caller owns that
                        // decision, and that the caller sits on the donation path.
                        // This is that caller, so the only correct decision is to
                        // lose the row quietly and record that it happened.
                        Log.e("Telemetry", "outbox append failed: ${t::class.java.name}")
                        recordTelemetryLoss()
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // Last resort, and not redundant with the inner catch: recording
                // the loss is itself capable of throwing. telemetryStatusStore is
                // `by lazy`, so on a kiosk whose Analytics screen was never opened
                // the first touch constructs PrefsStatusStore, whose initialiser
                // calls getSharedPreferences — which fails on exactly the full disk
                // that sent us into the inner catch. Nothing here has a caller left
                // to handle it: lifecycleScope installs no CoroutineExceptionHandler,
                // so an escape reaches the default handler and kills the process on
                // the thank-you screen. The donation flow is sacred; telemetry dies
                // silently instead.
                Log.e("Telemetry", "donation telemetry failed outright: ${t::class.java.name}")
            }
        }
    }

    /**
     * One event lost locally and unrecoverably. Folded into
     * [TelemetryStatus.droppedCount] rather than a field of its own: for anyone
     * who eventually reads the screen it is the same fact as an eviction, and
     * this kiosk can run unattended for weeks, so nothing here waits on a human
     * to see or clear it.
     *
     * Through `update {}` rather than a read-then-write, because this runs on a
     * background thread and races the flush's own status writes, which straddle
     * a network call that can run for minutes (finding I4, Ruling BA).
     */
    private fun recordTelemetryLoss() {
        telemetryStatusStore.update { it.copy(droppedCount = it.droppedCount + 1) }
    }

    /**
     * Asks the manager to flush, off the main thread and serialised.
     *
     * Decides nothing. [TelemetryGate] owns whether a flush may happen — it
     * refuses a disabled, unconfigured, unactivated, offline, empty-queued or
     * backing-off kiosk — so the call sites only pick moments worth asking at.
     * [reason] exists so the log says which moment.
     */
    private fun flushTelemetry(reason: String) {
        lifecycleScope.launch {
            try {
                val block = withContext(telemetryFlushDispatcher) { telemetryManager.flush() }
                // A FlushBlock, never an error string: lastError may carry a server
                // response body, and it is redacted for the screen, not for logcat.
                Log.d("Telemetry", "flush($reason) -> $block")
                // Keeps an open analytics screen current after a flush the operator
                // did not trigger. Cheap when the screen is closed: refreshAnalyticsSnapshot
                // returns before its Keystore decrypt and outbox parse, not after.
                refreshAnalyticsSnapshot()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // flush() reads the outbox file (IOException) and first-touches the
                // lazily-built telemetry stack, whose stores call getSharedPreferences.
                // Nothing above has a caller left to handle that: an escape reaches
                // the default handler and kills a kiosk that is, at 02:00, unattended.
                // Only the class name — never lastError, never a response body.
                Log.e("Telemetry", "flush($reason) threw: ${t::class.java.name}")
            }
        }
    }

    /**
     * Turns the two update markers into events, once, at startup.
     *
     * Wrapped for the same reason appendDonationTelemetry and flushTelemetry
     * are: lifecycleScope installs no CoroutineExceptionHandler, so an escaping
     * throwable reaches the default handler and kills the process. Here that is
     * worse than a crash — if the death lands inside the 60s watchdog window
     * and ahead of recordHeartbeat's asynchronous apply(), the watchdog reads a
     * stale heartbeat from disk and rolls back a build that was fine.
     */
    private fun drainUpdateDiagnostics() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val prefs = UpdateWatchdogReceiver.prefs(this@MainActivity)

                    // A restart's own diagnostics, ahead of the update markers below:
                    // both drains report against "this startup", but a pending entry
                    // was written by the process this one replaced, so it is the
                    // older fact of the two.
                    val pendingStore = PendingDiagnosticStore(prefs)
                    val pending = pendingStore.read()
                    val drainedIds = mutableSetOf<String>()
                    pending.forEach { entry ->
                        val result = DiagnosticEvents.forKind(
                            CrashContext.settings, BuildConfig.VERSION_NAME, entry.kind,
                            entry.occurredAtMs, entry.detailJson, affiliateKey = null, id = entry.id
                        )
                        (result as? DiagnosticEventResult.Report)?.let {
                            telemetryOutbox.append(it.event.id, it.event.table, it.event.payloadJson())
                        }
                        // Drained whether or not it produced an event: a gate-declined
                        // entry (NotEnabled) left in the store would sit there forever,
                        // pinning it at MAX_ENTRIES and being re-read on every boot.
                        drainedIds += entry.id
                    }
                    // Unreached if the loop above throws — an append that fails
                    // leaves its entry (and every later one) behind, to be retried
                    // next startup, exactly like the update markers below.
                    // Guarded the same way reportRestart guards its own write
                    // (`if (report.toMarker.isNotEmpty())`): skipping an empty
                    // drain avoids a commit() of "[]" on every single boot, which
                    // is the overwhelmingly common case.
                    if (drainedIds.isNotEmpty()) pendingStore.removeDrained(drainedIds)

                    val rollbackAt = prefs.getLong(UpdateWatchdogReceiver.KEY_ROLLBACK_AT, 0L)
                    val rollbackFrom =
                        prefs.getString(UpdateWatchdogReceiver.KEY_ROLLBACK_FROM_VERSION, null)
                    val storedVersion =
                        prefs.getString(UpdateWatchdogReceiver.KEY_REPORTED_VERSION, "") ?: ""
                    val now = System.currentTimeMillis()

                    val events = mutableListOf<TelemetryEvent.Diagnostic>()
                    // Rollback first, so a reader scanning by insertion order
                    // sees cause before effect: a rollback is also a version
                    // change, so both fire at the same startup.
                    val rollback = DiagnosticEvents.updateRollback(
                        CrashContext.settings, BuildConfig.VERSION_NAME, rollbackAt, rollbackFrom
                    )
                    (rollback as? DiagnosticEventResult.Report)?.let { events += it.event }
                    val installed = DiagnosticEvents.updateInstalled(
                        CrashContext.settings, BuildConfig.VERSION_NAME, storedVersion, now
                    )
                    (installed.result as? DiagnosticEventResult.Report)?.let { events += it.event }

                    events.forEach { telemetryOutbox.append(it.id, it.table, it.payloadJson()) }

                    // Cleared only once every append has returned. An append
                    // that throws leaves the markers and the event is reported
                    // next startup instead — duplicating a diagnostic is cheap,
                    // losing the only record that a kiosk reverted is not.
                    prefs.edit()
                        .remove(UpdateWatchdogReceiver.KEY_ROLLBACK_AT)
                        .remove(UpdateWatchdogReceiver.KEY_ROLLBACK_FROM_VERSION)
                        .putString(
                            UpdateWatchdogReceiver.KEY_REPORTED_VERSION, installed.versionToStore
                        )
                        .apply()
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    Log.e("Telemetry", "update diagnostics drain failed: ${t::class.java.name}")
                }
            }
        }
    }

    /**
     * Records a diagnostic and returns. Never throws, whatever happens inside —
     * every caller is a recovery path, and a diagnostic that breaks the recovery
     * it reports is worse than no diagnostic.
     *
     * [detail] is a lambda rather than a value on purpose: an eager argument
     * would be built *before* this guard is entered, and two callers sit inside
     * while(true) loops on lifecycleScope, which installs no
     * CoroutineExceptionHandler — an escape there kills the loop and the process.
     */
    private fun reportDiagnostic(
        kind: DiagnosticKind,
        detail: (() -> String?)? = null,
        occurredAtMs: Long = System.currentTimeMillis()
    ) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                DiagnosticReporter.record(
                    settings = { CrashContext.settings },
                    appVersion = BuildConfig.VERSION_NAME,
                    kind = kind,
                    occurredAtMs = occurredAtMs,
                    detail = detail,
                    affiliateKey = { CrashContext.affiliateKey },
                    append = { event ->
                        // 3b's eagerly-constructed instance, not the `by lazy`
                        // field: lazy's SYNCHRONIZED mode can block if another
                        // thread is mid-init, and a diagnostic is not worth a
                        // stall on a recovery path.
                        crashOutbox.append(event.id, event.table, event.payloadJson())
                    },
                    onError = { t ->
                        Log.e("Telemetry", "diagnostic ${kind.wire} not recorded: ${t::class.java.name}")
                    }
                )
            }
        }
    }

    /**
     * Retries a flush every 30 minutes for as long as the screensaver is up.
     *
     * One always-running loop that reads the flag, rather than a job started and
     * stopped alongside the screensaver: entering the screensaver already
     * produces its own flush, so this loop's only job is the later retry, and a
     * loop with no lifecycle coupling has no start/stop ordering to get wrong.
     */
    private fun startTelemetryFlushTicker() {
        telemetryFlushTickerJob?.cancel()
        telemetryFlushTickerJob = lifecycleScope.launch {
            while (true) {
                delay(TELEMETRY_FLUSH_TICK_MS)
                if (isScreensaverActive) flushTelemetry("idle-tick")
            }
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
        // Ahead of the toast, and outside runOnUiThread: this is a record, not
        // something the operator is waiting on.
        if (n is com.sadaqah.kiosk.update.UpdateNotification.InstallFailed) {
            reportDiagnostic(DiagnosticKind.UPDATE_INSTALL_FAILED, detail = {
                DiagnosticEvents.installFailedDetail(n.reason)
            })
        }
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
        // Seeded here rather than at each writer: the crash handler is
        // process-global and reads this holder, and a writer that forgot to
        // refresh it would let a kiosk with analytics off still report a crash.
        // Every settings writer already calls this one function.
        CrashContext.settings = settings
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
        // Clear SumUp's cached email/password but keep the affiliate key —
        // operators almost always log back in under the same merchant, so
        // re-entering the key would be needless friction. The next login
        // attempt will still prompt for SumUp credentials.
        SumUpAPI.logout()
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
    onExportSettings: (Boolean, String) -> String,
    onImportSettings: (String, String?) -> ImportResult,
    onImportSettingsOnly: (String) -> ImportResult,
    isNetworkAvailable: Boolean,
    isPinned: Boolean,
    isBluetoothEnabled: Boolean,
    isCardReaderConnected: Boolean,
    showSetupStatus: Boolean,
    showDonationHistory: Boolean,
    onShowDonationHistory: (Boolean) -> Unit,
    donationHistory: DonationHistory,
    showAnalyticsSettings: Boolean,
    onShowAnalyticsSettings: (Boolean) -> Unit,
    analyticsView: AnalyticsView,
    analyticsTestState: TestConnectionState,
    onAnalyticsToggleEnabled: (Boolean) -> Unit,
    onAnalyticsSaveDestination: (String, String) -> UrlVerdict,
    onAnalyticsTestConnection: () -> Unit,
    onAnalyticsKioskCodeChange: (String) -> Unit,
    onAnalyticsPolicyUrlsChange: (String, String) -> Unit,
    onShowDisclosure: () -> Unit,
    onAnalyticsClearCredentials: () -> Unit,
    disclosureView: DisclosureView?,
    onDismissDisclosure: () -> Unit,
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
    } else if (isEditingSettings) {
        // Settings outranks the NoInternet overlay so the operator can navigate
        // into Settings from the NoInternetScreen and back out again without
        // dropping through to the login/donation tree. Exiting settings leaves
        // maintenanceReason intact, so the NoInternetScreen reappears
        // automatically until the network is restored.
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
            disclosureView != null -> DisclosureScreen(
                view = disclosureView,
                settings = settings,
                strings = rememberStrings(),
                onDismiss = onDismissDisclosure
            )
            showAnalyticsSettings -> AnalyticsSettingsScreen(
                view = analyticsView,
                settings = settings,
                strings = rememberStrings(),
                testState = analyticsTestState,
                onBack = { onShowAnalyticsSettings(false) },
                onToggleEnabled = onAnalyticsToggleEnabled,
                onSaveDestination = onAnalyticsSaveDestination,
                onTestConnection = onAnalyticsTestConnection,
                onKioskCodeChange = onAnalyticsKioskCodeChange,
                onPolicyUrlsChange = onAnalyticsPolicyUrlsChange,
                onShowDisclosure = onShowDisclosure,
                onClearCredentials = onAnalyticsClearCredentials
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
                // Both toggles now flip the radio in place on a device-owner
                // install, so the operator stays on the checklist and watches
                // the row update instead of being bounced out of it.
                onEnableBluetooth = onEnableBluetooth,
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
                onImportSettingsOnly = onImportSettingsOnly,
                connectCardReader = connectCardReader,
                isLoggedIn = isLoggedIn,
                isPinned = isPinned,
                onUnpinApp = onUnpinApp,
                onPinApp = onPinApp,
                onShowSetupStatus = { onShowSetupStatus(true) },
                onShowDonationHistory = { onShowDonationHistory(true) },
                onShowAnalyticsSettings = { onShowAnalyticsSettings(true) },
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
    } else if (maintenanceReason == MaintenanceReason.NetworkOutage) {
        NoInternetScreen(
            settings = settings,
            onOpenSettings = onOfflineSettingsClick
        )
    } else if (!isLoggedIn) {
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
    } else if (showCustomAmountScreen) {
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
    } else if (showThankYou) {
        ThankYouScreen(settings = settings)
    } else if (isScreensaverActive) {
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