package com.sadaqah.kiosk.update

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.SharedPreferences
import android.os.BatteryManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.sadaqah.kiosk.BuildConfig
import com.sadaqah.kiosk.model.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI-facing events surfaced as Toasts / banners by MainActivity. The reasons
 * stay inside the manager for logs; UI maps these enums to localised strings.
 */
sealed class UpdateNotification {
    data object AlreadyLatest : UpdateNotification()
    data class UpdateAvailable(val release: ReleaseInfo) : UpdateNotification()
    data object CheckFailed : UpdateNotification()
    data object BatteryTooLow : UpdateNotification()
    data object NoNetwork : UpdateNotification()
    data object NotDeviceOwner : UpdateNotification()
    data object InstallFailed : UpdateNotification()
}

data class RepoCoords(val owner: String, val name: String) {
    fun toUrl() = "https://github.com/$owner/$name"
}

fun parseGitHubRepoUrl(url: String): RepoCoords? {
    val cleaned = url.trim().removeSuffix("/").removeSuffix(".git")
    val regex = Regex("^https?://(?:www\\.)?github\\.com/([^/\\s]+)/([^/\\s]+)$")
    val m = regex.matchEntire(cleaned) ?: return null
    return RepoCoords(m.groupValues[1], m.groupValues[2])
}

class UpdateManager(
    private val context: Context,
    initialSettings: Settings,
    private val isNetworkAvailable: () -> Boolean,
    private val onStartInstall: (ReleaseInfo) -> Unit,
    private val onFinishInstall: () -> Unit,
    private val persistSettings: (Settings) -> Unit,
    private val onNotification: (UpdateNotification) -> Unit,
    private val prepareForInstall: () -> Unit = {}
) {
    private var settings: Settings = initialSettings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs: SharedPreferences =
        context.getSharedPreferences("update_state", Context.MODE_PRIVATE)
    private val downloader = ApkDownloader(context)
    private val validator = ApkValidator(context)
    private val installer = ApkInstaller(context)
    private val backup = BackupStore(context)

    /**
     * Tracks the currently in-flight check so a rapid second tap from the UI
     * cancels the first instead of racing for the result.
     */
    private var inflightCheck: Job? = null

    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set
    var latestKnown by mutableStateOf<ReleaseInfo?>(null)
        private set

    /** All eligible releases sorted newest-first. Powers the target-version dropdown. */
    var availableReleases by mutableStateOf<List<ReleaseInfo>>(emptyList())
        private set

    val currentVersion: SemVer = SemVer.parse(BuildConfig.VERSION_NAME)
        ?: SemVer(0, 0, 0)

    val updateAvailableSince: Long
        get() = prefs.getLong(KEY_UPDATE_DETECTED_AT, 0L)

    fun refreshSettings(s: Settings) {
        settings = s
    }

    fun hasActionableUpdate(): Boolean {
        val tgt = latestKnown ?: return false
        return tgt.version != currentVersion
    }

    fun isPinned(): Boolean = settings.autoUpdateTargetVersion != "latest"

    fun pinnedTarget(): SemVer? = SemVer.parse(settings.autoUpdateTargetVersion)?.let {
        if (it < MIN_AUTO_UPDATE_VERSION) MIN_AUTO_UPDATE_VERSION else it
    }

    private fun repoCoords(): RepoCoords? = parseGitHubRepoUrl(settings.updateRepoUrl)

    /**
     * True when [target] can be installed silently from the current build.
     *
     * Our release convention is that all releases sharing the same
     * `major.minor` also share the same `versionCode` — so installing any
     * `1.3.X` from any other `1.3.Y` is treated by Android as a reinstall, not
     * a downgrade. Cross-track changes (`1.3.X → 1.4.X`) bump versionCode.
     *
     * As a result:
     *   - Same `(major, minor)` → reinstall (allowed, exact patch level chosen)
     *   - Higher `(major, minor)` → upgrade (allowed)
     *   - Lower `(major, minor)` → real versionCode downgrade (blocked — Android
     *     won't accept it, and the watchdog couldn't roll back from one either)
     */
    private fun isInstallable(target: SemVer): Boolean {
        if (target.major != currentVersion.major) return target.major > currentVersion.major
        if (target.minor != currentVersion.minor) return target.minor > currentVersion.minor
        return true // same major.minor — any patch level is a reinstall
    }

    /** Cancels all in-flight work. Must be called from the owning Activity's onDestroy. */
    fun dispose() {
        scope.cancel()
    }

    /**
     * Wall-clock time the auto-update path will fire, or null if nothing is
     * scheduled. Only returns non-null when auto-update is enabled AND target
     * is "latest" — pinned versions are user-driven so we don't claim them as
     * "scheduled" in the UI.
     */
    fun nextScheduledInstallTime(): Long? {
        if (!hasActionableUpdate()) return null
        if (!settings.autoUpdateEnabled) return null
        if (isPinned()) return null
        val detectedAt = updateAvailableSince
        if (detectedAt == 0L) return null
        val graceExpiry = detectedAt + settings.autoUpdateGraceDays * 24L * 60 * 60 * 1000
        return nextDailyMaintenanceAfter(graceExpiry)
    }

    /** Returns the next 02:00 wall-clock instant at-or-after [fromMillis]. */
    private fun nextDailyMaintenanceAfter(fromMillis: Long): Long {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = fromMillis
            set(java.util.Calendar.HOUR_OF_DAY, 2)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis < fromMillis) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    // ── Check ──────────────────────────────────────────────────────────────────

    /**
     * Single-flight. A second call while one is in-flight cancels the first
     * before starting the new one, so [state], [latestKnown], and the
     * detected-at marker stay consistent.
     *
     * [silent] = no UI notifications (used for the startup and 02:00 checks).
     */
    fun checkForUpdate(silent: Boolean = true): Job {
        inflightCheck?.cancel()
        val job = scope.launch {
            state = UpdateState.Checking
            val coords = repoCoords()
            if (coords == null) {
                Log.e("UpdateManager", "Invalid repo URL: '${settings.updateRepoUrl}'")
                if (!silent) onNotification(UpdateNotification.CheckFailed)
                state = UpdateState.Idle
                return@launch
            }
            try {
                val client = GitHubReleasesClient(coords.owner, coords.name)
                val all = client.listReleases()
                // Eligible = anything we could actually install on this device.
                // Patch-level "downgrades" within the same major.minor track are
                // fine because they share versionCode (Android sees them as
                // reinstalls). Cross-track downgrades stay blocked.
                val eligible = all
                    .filter { it.version >= MIN_AUTO_UPDATE_VERSION }
                    .filter { isInstallable(it.version) }
                    .sortedByDescending { it.version }
                availableReleases = eligible

                val pinned = pinnedTarget()
                val target = if (pinned != null) {
                    eligible.firstOrNull { it.version == pinned }
                } else {
                    eligible.firstOrNull()
                }
                if (target == null) {
                    Log.d("UpdateManager", "No eligible release found")
                    latestKnown = null
                    prefs.edit { remove(KEY_UPDATE_DETECTED_AT) }
                    if (!silent) onNotification(UpdateNotification.AlreadyLatest)
                    state = UpdateState.Idle
                    return@launch
                }

                // Drop any stale download AND reset the grace timer if the
                // resolved target version changed since we last looked.
                val prev = latestKnown
                if (prev != null && prev.version != target.version) {
                    Log.d("UpdateManager", "Resolved target changed (${prev.version} → ${target.version}) — cleaning download + resetting grace timer")
                    downloader.cleanupExcept(target.version)
                    prefs.edit { remove(KEY_UPDATE_DETECTED_AT) }
                }
                latestKnown = target

                val isActionable = target.version != currentVersion
                if (isActionable) {
                    if (updateAvailableSince == 0L) {
                        prefs.edit { putLong(KEY_UPDATE_DETECTED_AT, System.currentTimeMillis()) }
                    }
                    if (!silent) onNotification(UpdateNotification.UpdateAvailable(target))
                } else {
                    prefs.edit { remove(KEY_UPDATE_DETECTED_AT) }
                    if (!silent) onNotification(UpdateNotification.AlreadyLatest)
                }
                state = UpdateState.Idle
            } catch (e: Exception) {
                Log.e("UpdateManager", "checkForUpdate failed: ${e.message}")
                if (!silent) onNotification(UpdateNotification.CheckFailed)
                state = UpdateState.Idle
            }
        }
        inflightCheck = job
        return job
    }

    // ── Daily maintenance (02:00) ──────────────────────────────────────────────

    suspend fun runDailyMaintenance() {
        checkForUpdate(silent = true).join()
        val target = latestKnown ?: return
        if (target.version == currentVersion) return
        if (!preflightOk(silent = true)) return

        // Skip everything when auto-update is off and there's no pin — no
        // download, no install. Pin acts as opt-in consent.
        if (!settings.autoUpdateEnabled && !isPinned()) {
            Log.d("UpdateManager", "Auto-update disabled and not pinned — skipping daily maintenance")
            return
        }

        val shouldInstall = if (isPinned()) {
            true
        } else {
            val detectedAt = updateAvailableSince
            val grace = settings.autoUpdateGraceDays * 24L * 60 * 60 * 1000
            detectedAt > 0 && (System.currentTimeMillis() - detectedAt) >= grace
        }

        val apk = downloader.download(target) { /* nightly silent */ }
        if (apk == null) {
            Log.w("UpdateManager", "Daily download failed for v${target.version}")
            return
        }
        if (shouldInstall) installResolved(target, apk)
        else Log.d("UpdateManager", "v${target.version} downloaded; install deferred (grace window)")
    }

    // ── User-initiated install ─────────────────────────────────────────────────

    fun startUpdateNow(): Job = scope.launch {
        val target = latestKnown
        if (target == null) {
            Log.w("UpdateManager", "startUpdateNow with no target — run a check first")
            onNotification(UpdateNotification.CheckFailed)
            withContext(Dispatchers.Main) { onFinishInstall() }
            return@launch
        }
        if (!preflightOk(silent = false)) {
            withContext(Dispatchers.Main) { onFinishInstall() }
            return@launch
        }

        state = UpdateState.Downloading(target, 0)
        val apk = downloader.download(target) { pct ->
            state = UpdateState.Downloading(target, pct)
        }
        if (apk == null) {
            Log.e("UpdateManager", "Download failed for v${target.version}")
            onNotification(UpdateNotification.InstallFailed)
            state = UpdateState.Idle
            withContext(Dispatchers.Main) { onFinishInstall() }
            return@launch
        }
        installResolved(target, apk)
    }

    private suspend fun installResolved(target: ReleaseInfo, apkFile: java.io.File) {
        // Safety net for stale pins to a different major.minor track that
        // would slip past the dropdown filter.
        if (!isInstallable(target.version)) {
            Log.w("UpdateManager", "Cross-track downgrade refused — target=${target.version} current=$currentVersion")
            onNotification(UpdateNotification.InstallFailed)
            state = UpdateState.Idle
            withContext(Dispatchers.Main) { onFinishInstall() }
            return
        }

        // Within the same major.minor track the new APK shares versionCode
        // with the installed one, so the validator's `newCode < installedCode`
        // check passes without needing allowDowngrade. If a build accidentally
        // bumped versionCode within a track, this keeps validation strict.
        val validation = validator.validate(
            apkFile = apkFile,
            allowDowngrade = false,
            skipSignatureCheck = settings.skipApkSignatureCheckOnce
        )
        if (validation is ApkValidator.Result.Reject) {
            Log.e("UpdateManager", "Validation rejected: ${validation.reason}")
            onNotification(UpdateNotification.InstallFailed)
            state = UpdateState.Idle
            withContext(Dispatchers.Main) { onFinishInstall() }
            return
        }

        state = UpdateState.ReadyToInstall(target, apkFile.absolutePath)
        withContext(Dispatchers.Main) { onStartInstall(target) }
        state = UpdateState.Installing(target)

        backup.saveCurrentApk()
        UpdateWatchdogReceiver.arm(context, delayMs = 60_000L)

        // UI callback — switch to main thread because the body touches the
        // Window/Activity (system bars, lock task). Synchronous so the install
        // commit doesn't race the teardown.
        try {
            withContext(Dispatchers.Main) { prepareForInstall() }
        } catch (e: Exception) {
            Log.w("UpdateManager", "prepareForInstall threw: ${e.message}")
        }

        if (settings.skipApkSignatureCheckOnce) {
            settings = settings.copy(skipApkSignatureCheckOnce = false)
            try { persistSettings(settings) } catch (e: Exception) {
                Log.e("UpdateManager", "persistSettings (clear skip-sig) failed: ${e.message}")
            }
        }

        val result = withContext(Dispatchers.IO) { installer.install(apkFile) }
        when (result) {
            is ApkInstaller.Result.Success -> {
                Log.d("UpdateManager", "Install commit returned SUCCESS")
                downloader.cleanupAll()
                // Process dies here; PackageReplacedReceiver relaunches us.
            }
            is ApkInstaller.Result.Failed -> {
                Log.e("UpdateManager", "Install failed status=${result.status} msg=${result.message}")
                UpdateWatchdogReceiver.disarm(context)
                onNotification(UpdateNotification.InstallFailed)
                state = UpdateState.Idle
                withContext(Dispatchers.Main) { onFinishInstall() }
            }
        }
    }

    // ── Preflight ──────────────────────────────────────────────────────────────

    private fun preflightOk(silent: Boolean): Boolean {
        if (!isDeviceOwner()) {
            Log.w("UpdateManager", "Not device-owner — silent install unavailable, skipping")
            if (!silent) onNotification(UpdateNotification.NotDeviceOwner)
            return false
        }
        if (!isNetworkAvailable()) {
            Log.d("UpdateManager", "Preflight: no network")
            if (!silent) onNotification(UpdateNotification.NoNetwork)
            return false
        }
        val battery = batteryPct()
        if (battery < 30) {
            Log.d("UpdateManager", "Preflight: battery too low ($battery%)")
            if (!silent) onNotification(UpdateNotification.BatteryTooLow)
            return false
        }
        return true
    }

    private fun isDeviceOwner(): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    private fun batteryPct(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return 100
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (pct < 0) 100 else pct
    }

    companion object {
        const val KEY_UPDATE_DETECTED_AT = "update_detected_at_ms"
    }
}
