package com.sadaqah.kiosk.update

/**
 * Versioning + release metadata shared across the update subsystem.
 * Kept deliberately minimal — only fields we actually consume.
 */

data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int = when {
        major != other.major -> major - other.major
        minor != other.minor -> minor - other.minor
        else -> patch - other.patch
    }
    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /** Accepts "1.2.3" or "v1.2.3". Returns null for non-semver tags. */
        fun parse(s: String): SemVer? {
            val cleaned = s.removePrefix("v").removePrefix("V")
            val parts = cleaned.split('.')
            if (parts.size != 3) return null
            val ints = parts.mapNotNull { it.toIntOrNull() }
            if (ints.size != 3) return null
            return SemVer(ints[0], ints[1], ints[2])
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
    val apkSizeBytes: Long
)

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class Downloading(val target: ReleaseInfo, val progressPct: Int) : UpdateState()
    data class ReadyToInstall(val target: ReleaseInfo, val apkPath: String) : UpdateState()
    data class Installing(val target: ReleaseInfo) : UpdateState()
}
