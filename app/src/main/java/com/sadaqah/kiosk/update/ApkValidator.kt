package com.sadaqah.kiosk.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import java.io.File
import java.security.MessageDigest

class ApkValidator(private val context: Context) {

    sealed class Result {
        data object Ok : Result()
        data class Reject(val reason: String) : Result()
    }

    /**
     * Validates [apkFile] against the currently-installed app.
     *
     * Always-checked:
     * - APK file exists and is readable.
     * - PackageManager can parse it.
     * - packageName matches the installed app.
     * - versionCode is >= installed (downgrade only allowed when [allowDowngrade]).
     *
     * Conditionally-checked (skipped if [skipSignatureCheck]):
     * - Signing certificate fingerprint matches installed app's.
     *
     * Note: skipping the signature check is a one-time escape hatch for migrating
     * to a fork signed with a different key. The caller is expected to reset the
     * underlying flag after a successful install regardless of outcome.
     */
    fun validate(
        apkFile: File,
        allowDowngrade: Boolean,
        skipSignatureCheck: Boolean
    ): Result {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return Result.Reject("APK missing or empty: ${apkFile.absolutePath}")
        }

        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val parsed: PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                )
            )
        } else {
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        if (parsed == null) return Result.Reject("PackageManager could not parse APK")

        val ourPkg = context.packageName
        if (parsed.packageName != ourPkg) {
            return Result.Reject("packageName mismatch: ${parsed.packageName} ≠ $ourPkg")
        }

        val installed: PackageInfo = try {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(ourPkg, PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                ))
            } else {
                pm.getPackageInfo(ourPkg, PackageManager.GET_SIGNING_CERTIFICATES)
            }
        } catch (e: Exception) {
            return Result.Reject("Cannot read installed package info: ${e.message}")
        }

        @Suppress("DEPRECATION")
        val installedCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) installed.longVersionCode
            else installed.versionCode.toLong()
        @Suppress("DEPRECATION")
        val newCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) parsed.longVersionCode
            else parsed.versionCode.toLong()

        if (newCode < installedCode && !allowDowngrade) {
            return Result.Reject("Downgrade not allowed (installed=$installedCode, new=$newCode)")
        }

        if (!skipSignatureCheck) {
            val installedFps = certFingerprints(installed)
            val newFps = certFingerprints(parsed)
            if (installedFps.isEmpty() || newFps.isEmpty()) {
                return Result.Reject("Could not extract signing certificates for comparison")
            }
            if (installedFps.intersect(newFps).isEmpty()) {
                return Result.Reject("Signing certificate mismatch — refusing install (toggle Skip Signature Check to override)")
            }
        } else {
            Log.w("ApkValidator", "Signature check SKIPPED (one-time override)")
        }
        return Result.Ok
    }

    private fun certFingerprints(info: PackageInfo): Set<String> {
        val sigs: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo
            if (signing == null) emptyArray()
            else if (signing.hasMultipleSigners()) signing.apkContentsSigners
            else signing.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION") info.signatures ?: emptyArray()
        }
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { md.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }
}
