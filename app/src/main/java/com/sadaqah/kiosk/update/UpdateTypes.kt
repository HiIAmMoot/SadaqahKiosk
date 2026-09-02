package com.sadaqah.kiosk.update

/**
 * Versioning + release metadata shared across the update subsystem.
 * Kept deliberately minimal — only fields we actually consume.
 */

data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** Suffix after the dash, e.g. "preview" in 1.3.5-preview. Null for a normal release. */
    val preRelease: String? = null
) : Comparable<SemVer> {

    /** Pre-release builds are installable by hand but never resolve as the automatic target. */
    val isPreview: Boolean get() = preRelease != null

    override fun compareTo(other: SemVer): Int = when {
        major != other.major -> major - other.major
        minor != other.minor -> minor - other.minor
        patch != other.patch -> patch - other.patch
        // Semver rule: a pre-release ranks below its own release, so
        // 1.3.4 < 1.3.5-preview < 1.3.5.
        preRelease == other.preRelease -> 0
        preRelease == null -> 1
        other.preRelease == null -> -1
        else -> preRelease.compareTo(other.preRelease)
    }

    override fun toString(): String =
        if (preRelease == null) "$major.$minor.$patch" else "$major.$minor.$patch-$preRelease"

    companion object {
        /**
         * Accepts "1.2.3", "v1.2.3", and pre-releases like "1.2.3-preview".
         * Returns null for non-semver tags.
         *
         * Must round-trip with [toString] — the settings dropdown persists
         * `version.toString()` into `autoUpdateTargetVersion` and re-parses it.
         */
        fun parse(s: String): SemVer? {
            val cleaned = s.trim().removePrefix("v").removePrefix("V")
            val dash = cleaned.indexOf('-')
            val core = if (dash >= 0) cleaned.substring(0, dash) else cleaned
            val preRelease = if (dash >= 0) cleaned.substring(dash + 1) else null
            if (preRelease != null && preRelease.isEmpty()) return null

            val parts = core.split('.')
            if (parts.size != 3) return null
            val ints = parts.mapNotNull { it.toIntOrNull() }
            if (ints.size != 3) return null
            return SemVer(ints[0], ints[1], ints[2], preRelease)
        }
    }
}

/** Lowest version that supports auto-update. Used to clamp pinning + filter releases. */
val MIN_AUTO_UPDATE_VERSION = SemVer(1, 3, 0)

data class ReleaseInfo(
    val tag: String,
    val version: SemVer,
    val name: String,
    val body: String,          // markdown changelog (raw)
    val publishedAtIso: String, // ISO-8601 from GitHub
    val apkUrl: String,
    val apkSizeBytes: Long,
    /**
     * True when the tag carries a pre-release suffix OR GitHub marks the
     * release as a pre-release. Either marker keeps the build out of the
     * automatic update path while leaving it selectable in Settings.
     */
    val isPreview: Boolean = false
)

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class Downloading(val target: ReleaseInfo, val progressPct: Int) : UpdateState()
    data class ReadyToInstall(val target: ReleaseInfo, val apkPath: String) : UpdateState()
    data class Installing(val target: ReleaseInfo) : UpdateState()
}
