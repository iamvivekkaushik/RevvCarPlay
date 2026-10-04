package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Installs and loads the accessory identity (`identity.pk8` + `certificate.p7b`) that the iPhone
 * checks before it starts CarPlay. RevvCarPlay ships none: the driver imports their own pair in
 * Settings, or a local build bundles one under `assets/offline-mfi/` (see docs/BUILD.md).
 */
internal object DiPlayBootstrap {
    /** No identity has been imported and the build bundles none. */
    class MissingIdentityException : IllegalStateException("No CarPlay accessory identity is installed")

    private val FILES = listOf("identity.pk8", "certificate.p7b")
    @Volatile private var ready = false

    private fun target(context: Context) = File(context.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)

    /** Whether an identity is installed; [ensure] still has to load it. */
    fun hasIdentity(context: Context): Boolean = FILES.all { File(target(context), it).isFile }

    @Synchronized fun ensure(context: Context) {
        if (ready) return
        val target = target(context)
        if (!target.exists()) {
            if (!bundled(context)) throw MissingIdentityException()
            install(context) { name -> context.assets.open("offline-mfi/$name") }
        }
        LocalMfiAuthenticationClient.load(target)
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.LOCAL)
        AirPlayPersistence.saveDebugLogsEnabled(context, false)
        ready = true
    }

    /**
     * Installs the identity from [open] (called with each file name), replacing any installed one.
     * The pair is validated (key matches certificate, P-256) before it replaces anything.
     */
    @Synchronized fun import(context: Context, open: (String) -> InputStream) {
        install(context, open)
        ready = false
        ensure(context)
    }

    @Synchronized fun remove(context: Context) {
        target(context).deleteRecursively()
        ready = false
    }

    private fun bundled(context: Context): Boolean =
        runCatching { context.assets.open("offline-mfi/${FILES.first()}").close(); true }.getOrDefault(false)

    private fun install(context: Context, open: (String) -> InputStream) {
        val target = target(context)
        val staging = File(context.noBackupFilesDir, "offline-mfi-staging")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not prepare local authentication" }
        staging.setReadable(false, false); staging.setReadable(true, true)
        staging.setExecutable(false, false); staging.setExecutable(true, true)
        try {
            for (name in FILES) {
                val file = File(staging, name)
                open(name).use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                file.setReadable(false, false); file.setReadable(true, true)
                file.setWritable(false, false); file.setWritable(true, true)
            }
            LocalMfiAuthenticationClient.load(staging)
            target.deleteRecursively()
            check(staging.renameTo(target)) { "Could not install local authentication" }
        } finally {
            staging.deleteRecursively()
        }
    }

    fun deviceId(identity: AirPlayIdentity): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).take(6).toByteArray()
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }
}

internal object DiPlayPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
    fun phoneAddress(context: Context): String? = prefs(context).getString("phone_address", null)
    fun phoneName(context: Context): String = prefs(context).getString("phone_name", null) ?: "Your iPhone"
    fun savePhone(context: Context, address: String, name: String) {
        prefs(context).edit().putString("phone_address", address).putString("phone_name", name).apply()
    }
    fun autoConnect(context: Context) = prefs(context).getBoolean("auto_connect", false)
    fun saveAutoConnect(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto_connect", value).apply()
    }
}
