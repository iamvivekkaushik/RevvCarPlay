package com.shilapi.xcertplay.network

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Turns the head unit's own Wi-Fi hotspot on, for the "Car hotspot" link. Android keeps the switch
 * in a hidden system API (TetheringManager.startTethering) but lets an app call it when the app
 * holds "Modify system settings" and the carrier asks for no tethering check. Android 11+.
 * The hotspot's name and password stay whatever the head unit's settings say.
 */
object HotspotSwitch {
    enum class Result {
        ON,
        ALREADY_ON,
        /** "Modify system settings" is not granted to this app. */
        NO_ACCESS,
        /** Android older than 11, or a firmware that hides the switch. */
        UNSUPPORTED,
        /** The system refused, e.g. a tethering check the carrier requires. */
        FAILED,
    }

    private const val TAG = "RevvCarPlay-Hotspot"
    private const val TETHERING_WIFI = 0
    private const val TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION = 14
    private val main = Handler(Looper.getMainLooper())

    /** Whether this app may switch the hotspot: Android 11+ and "Modify system settings" granted. */
    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Settings.System.canWrite(context)

    /** Turns the hotspot on unless it already is. [done] gets the outcome on the main thread. */
    fun turnOn(context: Context, done: (Result) -> Unit = {}) {
        val app = context.applicationContext
        when {
            CarHotspotStatus.isEnabled(app) == true -> done(Result.ALREADY_ON)
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> done(Result.UNSUPPORTED)
            !Settings.System.canWrite(app) -> done(Result.NO_ACCESS)
            else -> runCatching { start(app) { result -> main.post { done(result) } } }.onFailure {
                Log.w(TAG, "the hotspot switch is not reachable", it)
                done(Result.UNSUPPORTED)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun start(context: Context, reply: (Result) -> Unit) {
        HiddenApiBypass.addHiddenApiExemptions("Landroid/net/TetheringManager")
        val manager = context.getSystemService("tethering") ?: error("No tethering service")
        val managerClass = Class.forName("android.net.TetheringManager")
        val requestClass = Class.forName("android.net.TetheringManager\$TetheringRequest")
        val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
        val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        val builder = builderClass.getConstructor(Int::class.java).newInstance(TETHERING_WIFI)
        builderClass.getMethod("setShouldShowEntitlementUi", Boolean::class.java).invoke(builder, false)
        val request = builderClass.getMethod("build").invoke(builder)
        val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { proxy, method, args ->
            when (method.name) {
                "onTetheringStarted" -> {
                    Log.i(TAG, "hotspot turned on")
                    reply(Result.ON)
                    null
                }
                "onTetheringFailed" -> {
                    val error = args?.firstOrNull() as? Int
                    Log.w(TAG, "hotspot refused, tethering error $error")
                    // "Modify system settings" was checked above, so a permission refusal here means the
                    // carrier requires a tethering check, which only system apps may skip; asking the
                    // driver for the setting again would not help.
                    reply(if (error == TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION && !Settings.System.canWrite(context)) Result.NO_ACCESS else Result.FAILED)
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "HotspotSwitch callback"
                else -> null
            }
        }
        managerClass.getMethod("startTethering", requestClass, Executor::class.java, callbackClass)
            .invoke(manager, request, Executor { it.run() }, callback)
    }
}
