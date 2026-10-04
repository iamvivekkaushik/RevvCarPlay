package com.shilapi.xcertplay

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The diagnostic report: saved settings, the last display negotiation, startup results and the
 * redacted session logs. Built off the main thread; nothing is sent anywhere.
 */
internal object DiagnosticReport {
    fun fileName(): String = "DiPlay-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    /** Builds the report and saves it to Downloads/Revv/CarPlay as [fileName]. Blocking. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToDownloads(context: Context, fileName: String = fileName()): Uri =
        DiagnosticExportStore.saveToDownloads(context.contentResolver, fileName, build(context))

    fun build(context: Context): String {
        val app = context.applicationContext
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "0.1.0-beta.1"
        val setupReady = runCatching { DiPlayBootstrap.ensure(app) }.isSuccess
        return buildString {
            appendLine("DiPlay $version · private beta diagnostic report")
            appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(app)) "wireless" else "USB"}")
            appendLine("Authentication: local experimental beta identity; no remote fallback")
            appendLine("CarPlay setup: ${if (setupReady) "ready" else "authentication unavailable"}")
            appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(app)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(app)} fps")
            appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(app)).label}")
            appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScaleTenths(app) * 10}%")
            appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
            appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
            appendLine()
            appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
            appendLine(DisplayDiagnosticSnapshot.report(app))
            appendLine()
            appendLine("--- Last received boot and app-launch result ---")
            appendLine(StartupDiagnosticSnapshot.report(app))
            appendLine("Startup settings: openAfterBoot=${AirPlayPersistence.loadAutoStartOnBoot(app)} " +
                "connectWhenOpened=${DiPlayPreferences.autoConnect(app)}")
            appendLine()
            for (name in SessionLogFile.REPORT_NAMES) {
                val file = File(app.filesDir, "logs/$name")
                if (file.isFile) {
                    appendLine("--- $name ---")
                    file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
                }
            }
        }
    }
}
