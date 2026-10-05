package com.shilapi.xcertplay.embed

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.SurfaceControlViewHost
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.AudioChannelPreview
import com.shilapi.xcertplay.DiPlayBootstrap
import com.shilapi.xcertplay.DiagnosticExportStore
import com.shilapi.xcertplay.DiagnosticReport
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.HotspotSwitch
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.ERROR_BAD_REQUEST
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.ERROR_UNSUPPORTED
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.KEY_DETAIL
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.KEY_DISPLAY_ID
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.KEY_ERROR
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.KEY_EVENT
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.KEY_HEIGHT
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.KEY_HOST_TOKEN
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.KEY_MISSING
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.KEY_PHASE
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.KEY_SCREEN_HEIGHT
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.KEY_SCREEN_WIDTH
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.KEY_SURFACE_PACKAGE
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.KEY_VERSION
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.KEY_VIDEO_ACTIVE
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.KEY_WIDTH
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.KEY_WIRELESS
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.MSG_ATTACH
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.MSG_ATTACHED
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.MSG_DETACH
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.MSG_ERROR
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.MSG_RESIZE
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.MSG_SIRI
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.MSG_STATE
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.MSG_STOP
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.MSG_TOUCH
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.MSG_CONFIGURE
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol.KEY_HOTSPOT_MODE
import com.shilapi.xcertplay.glance.CarPlayGlance

/**
 * Lets a host app (Revv) show CarPlay inside its own layout. The host binds with
 * [CarPlayEmbedProtocol.ACTION], sends ATTACH with its SurfaceView's host token and size, and gets
 * a SurfaceControlViewHost.SurfacePackage back to put into that SurfaceView; CarPlay then runs
 * inside the host's screen, touch included. Android 11+. See docs/REVV_INTEGRATION.md.
 *
 * The host also reads and changes RevvCarPlay's settings here (identity, link, display, audio,
 * location); those messages are accepted only from an app signed like RevvCarPlay or with a
 * certificate the build trusts (see [HostCertificates]).
 */
class CarPlayEmbedService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { handle(it); true })
    private val embeds = HashMap<IBinder, Embed>()
    // Hosts that read the settings also hear every status change, with or without a view attached.
    private val subscribers = HashMap<IBinder, (EmbeddedCarPlay.Status) -> Unit>()
    // They follow CarPlay's route guidance too, e.g. to route their own map to the same place.
    private val guidanceClients = HashMap<IBinder, Messenger>()
    private var lastGuidance: Guidance? = null
    private val glanceListener: (CarPlayGlance.Snapshot) -> Unit = { snapshot -> main.post { sendGuidance(snapshot) } }
    // A route goes stale when the iPhone stops updating it without ending it; looking again notices.
    private val refreshGuidance = object : Runnable {
        override fun run() {
            CarPlayGlance.snapshot()
            if (guidanceClients.isNotEmpty()) main.postDelayed(this, GUIDANCE_REFRESH_MILLIS)
        }
    }
    private var previewClient: Messenger? = null
    private val preview by lazy {
        AudioChannelPreview { channel ->
            previewClient?.let { notice(it, getString(R.string.contrib_audio_home_channel_preview_unavailable, channel), ok = false) }
        }
    }
    private var previewing = false

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onCreate() {
        super.onCreate()
        EmbeddedCarPlay.onHostUiRequested = { embeds.values.toList().forEach { it.hostUiRequested() } }
    }

    override fun onDestroy() {
        EmbeddedCarPlay.onHostUiRequested = null
        embeds.values.toList().forEach { it.release() }
        embeds.clear()
        subscribers.values.forEach(EmbeddedCarPlay::removeListener)
        subscribers.clear()
        guidanceClients.clear()
        CarPlayGlance.removeListener(glanceListener)
        main.removeCallbacks(refreshGuidance)
        if (previewing) preview.close()
        super.onDestroy()
    }

    private fun handle(message: Message) {
        val client = message.replyTo ?: return
        val caller = packageManager.getNameForUid(message.sendingUid) ?: "uid ${message.sendingUid}"
        if (message.what in SETTINGS_MESSAGES) {
            if (trusted(message.sendingUid)) settings(client, caller, message.what, message.data)
            else refuse(client, caller, CarPlayEmbedProtocol.ERROR_UNTRUSTED)
            return
        }
        when (message.what) {
            MSG_ATTACH -> attach(client, caller, message.data)
            MSG_RESIZE -> embeds[client.binder]?.resize(
                message.data.getInt(KEY_WIDTH),
                message.data.getInt(KEY_HEIGHT),
                settled = message.data.getBoolean(CarPlayEmbedProtocol.KEY_SETTLED, true),
            )
            MSG_DETACH -> embeds.remove(client.binder)?.release()
            MSG_STOP -> EmbeddedCarPlay.stop()
            MSG_SIRI -> EmbeddedCarPlay.requestSiri()
            MSG_CONFIGURE -> {
                val trusted = trusted(message.sendingUid)
                EmbeddedCarPlay.configure(
                    this,
                    wireless = message.data.getBoolean(KEY_WIRELESS),
                    hotspotMode = message.data.getString(KEY_HOTSPOT_MODE),
                    onHotspot = { result -> if (trusted) hotspotTurned(client, result) },
                )
                // The link is a setting too: a host that may read them sees the change at once.
                if (trusted) sendSettings(client)
            }
            MSG_TOUCH -> {
                @Suppress("DEPRECATION")
                val event = message.data.getParcelable<MotionEvent>(KEY_EVENT) ?: return
                EmbeddedCarPlay.hostForwardsTouch = true
                EmbeddedCarPlay.sendTouch(event, message.data.getInt(KEY_WIDTH), message.data.getInt(KEY_HEIGHT))
                event.recycle()
            }
        }
    }

    private fun attach(client: Messenger, caller: String, data: Bundle) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            refuse(client, caller, ERROR_UNSUPPORTED)
            return
        }
        val token = data.getBinder(KEY_HOST_TOKEN)
        val display = getSystemService(DisplayManager::class.java)?.getDisplay(data.getInt(KEY_DISPLAY_ID))
        val width = data.getInt(KEY_WIDTH)
        val height = data.getInt(KEY_HEIGHT)
        if (token == null || display == null || width <= 0 || height <= 0) {
            refuse(client, caller, ERROR_BAD_REQUEST)
            return
        }
        embeds.remove(client.binder)?.release()
        val embed = Embed(client, caller, token, display, width, height)
        embeds[client.binder] = embed
        runCatching { client.binder.linkToDeath({ main.post { embeds.remove(client.binder)?.release() } }, 0) }
        Log.i(TAG, "$caller shows CarPlay ${width}x$height")
        send(client, MSG_ATTACHED, Bundle().apply {
            putParcelable(KEY_SURFACE_PACKAGE, embed.surfacePackage)
            putInt(KEY_VERSION, CarPlayEmbedProtocol.VERSION)
        })
        val screen = Size(
            data.getInt(KEY_SCREEN_WIDTH).takeIf { it > 0 } ?: display.mode.physicalWidth,
            data.getInt(KEY_SCREEN_HEIGHT).takeIf { it > 0 } ?: display.mode.physicalHeight,
        )
        EmbeddedCarPlay.start(this, caller.substringBefore(':'), Size(width, height), screen, display.rotation)
    }

    /**
     * The caller is signed like RevvCarPlay, or with one of [HostCertificates.trusted], such as
     * Revv's Google Play app signing certificate. Android checks the latter across key rotation.
     */
    private fun trusted(uid: Int): Boolean =
        uid == Process.myUid() ||
            packageManager.checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH ||
            HostCertificates.trusted.any { packageManager.hasSigningCertificate(uid, it, PackageManager.CERT_INPUT_SHA256) }

    private fun settings(client: Messenger, caller: String, what: Int, data: Bundle) {
        subscribe(client)
        when (what) {
            CarPlayEmbedProtocol.MSG_GET_SETTINGS -> sendSettings(client)
            CarPlayEmbedProtocol.MSG_SET_SETTING -> {
                val name = data.getString(CarPlayEmbedProtocol.KEY_SETTING)
                @Suppress("DEPRECATION") val value = data.get(CarPlayEmbedProtocol.KEY_VALUE)
                if (name == null || !EmbedSettings.set(this, name, value)) {
                    refuse(client, caller, ERROR_BAD_REQUEST)
                    return
                }
                // Choosing an audio stream plays a short tone on it, so the driver hears where it goes.
                if (name == CarPlayEmbedProtocol.SETTING_MEDIA_STREAM || name == CarPlayEmbedProtocol.SETTING_NAVIGATION_STREAM) {
                    previewClient = client
                    previewing = true
                    preview.play(value as Int, navigation = name == CarPlayEmbedProtocol.SETTING_NAVIGATION_STREAM)
                }
                EmbeddedCarPlay.settingsChanged(this, reconnect = true)
                sendSettings(client)
            }
            CarPlayEmbedProtocol.MSG_SET_HOTSPOT -> {
                val error = EmbedSettings.saveHotspot(this, data.getString(CarPlayEmbedProtocol.KEY_SSID).orEmpty(), data.getString(CarPlayEmbedProtocol.KEY_PASSPHRASE).orEmpty())
                if (error != null) {
                    notice(client, error, ok = false)
                } else {
                    // Details saved for the car hotspot mean linking over it, as the companion's own setup did.
                    EmbeddedCarPlay.configure(this, wireless = true, hotspotMode = CarPlayEmbedProtocol.HOTSPOT_MANUAL) { result -> hotspotTurned(client, result) }
                    notice(client, getString(R.string.hotspot_details_saved), ok = true)
                }
                sendSettings(client)
            }
            CarPlayEmbedProtocol.MSG_SET_PHONE -> {
                val address = data.getString(CarPlayEmbedProtocol.KEY_ADDRESS)
                if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
                    refuse(client, caller, ERROR_BAD_REQUEST)
                    return
                }
                EmbedSettings.savePhone(this, address, data.getString(CarPlayEmbedProtocol.KEY_NAME).orEmpty())
                EmbeddedCarPlay.settingsChanged(this, reconnect = AirPlayPersistence.loadWirelessEnabled(this))
                sendSettings(client)
            }
            CarPlayEmbedProtocol.MSG_IMPORT_IDENTITY -> {
                val files = data.getBundle(CarPlayEmbedProtocol.KEY_FILES)
                if (files == null) {
                    refuse(client, caller, ERROR_BAD_REQUEST)
                    return
                }
                val (text, ok) = EmbedSettings.importIdentity(this, files)
                notice(client, text, ok)
                if (ok) EmbeddedCarPlay.settingsChanged(this, reconnect = true)
                sendSettings(client)
            }
            CarPlayEmbedProtocol.MSG_REMOVE_IDENTITY -> EmbeddedCarPlay.stop {
                DiPlayBootstrap.remove(this)
                Log.i(TAG, "$caller removed the CarPlay identity")
                EmbeddedCarPlay.settingsChanged(this, reconnect = false)
                notice(client, getString(R.string.identity_missing), ok = true)
                sendSettings(client)
            }
            CarPlayEmbedProtocol.MSG_SAVE_REPORT -> saveReport(client)
            CarPlayEmbedProtocol.MSG_HOTSPOT_ON -> HotspotSwitch.turnOn(this) { result -> hotspotTurned(client, result) }
            CarPlayEmbedProtocol.MSG_RESET_WIFI_DIRECT -> {
                Log.i(TAG, "$caller asked to end the other Wi-Fi Direct connection")
                EmbeddedCarPlay.resetWifiDirect(this) { cleared ->
                    if (!cleared) notice(client, getString(R.string.embed_wifi_direct_reset_failed), ok = false)
                }
            }
        }
    }

    /** Says why the hotspot stayed off, and sends its state once Android has had time to bring it up. */
    private fun hotspotTurned(client: Messenger, result: HotspotSwitch.Result) {
        EmbedSettings.hotspotNotice(this, result)?.let { notice(client, it, ok = false) }
        sendSettings(client)
        if (result == HotspotSwitch.Result.ON) main.postDelayed({ sendSettings(client) }, HOTSPOT_SETTLE_MILLIS)
    }

    private fun subscribe(client: Messenger) {
        val binder = client.binder
        if (binder in subscribers) return
        val listener: (EmbeddedCarPlay.Status) -> Unit = { status -> sendState(client, status) }
        subscribers[binder] = listener
        runCatching { binder.linkToDeath({ main.post { unsubscribe(binder) } }, 0) }
        EmbeddedCarPlay.addListener(listener)
        if (guidanceClients.isEmpty()) {
            CarPlayGlance.addListener(glanceListener)
            main.postDelayed(refreshGuidance, GUIDANCE_REFRESH_MILLIS)
        }
        guidanceClients[binder] = client
        val snapshot = CarPlayGlance.snapshot()
        sendGuidance(client, Guidance.of(snapshot))
    }

    private fun unsubscribe(binder: IBinder) {
        subscribers.remove(binder)?.let(EmbeddedCarPlay::removeListener)
        if (guidanceClients.remove(binder) != null && guidanceClients.isEmpty()) {
            CarPlayGlance.removeListener(glanceListener)
            main.removeCallbacks(refreshGuidance)
            lastGuidance = null
        }
    }

    /** Main thread. Song changes also come through here; only a change to the route is sent. */
    private fun sendGuidance(snapshot: CarPlayGlance.Snapshot) {
        val guidance = Guidance.of(snapshot)
        if (guidance == lastGuidance) return
        lastGuidance = guidance
        guidanceClients.values.toList().forEach { sendGuidance(it, guidance) }
    }

    private fun sendGuidance(client: Messenger, guidance: Guidance) {
        send(client, CarPlayEmbedProtocol.MSG_GUIDANCE, Bundle().apply {
            putString(CarPlayEmbedProtocol.KEY_DESTINATION, guidance.destination)
            guidance.routeMeters?.let { putLong(CarPlayEmbedProtocol.KEY_ROUTE_METERS, it) }
            guidance.maneuverType?.let {
                putInt(CarPlayEmbedProtocol.KEY_MANEUVER_TYPE, it)
                putInt(CarPlayEmbedProtocol.KEY_MANEUVER_METERS, guidance.maneuverMeters)
            }
            putString(CarPlayEmbedProtocol.KEY_ROAD, guidance.road)
            putInt(CarPlayEmbedProtocol.KEY_DRIVING_SIDE, guidance.drivingSide)
            guidance.arrivalEpochSeconds?.let { putLong(CarPlayEmbedProtocol.KEY_ARRIVAL, it) }
            guidance.remainingSeconds?.let { putLong(CarPlayEmbedProtocol.KEY_REMAINING_SECONDS, it) }
        })
    }

    /** The route part of a [CarPlayGlance.Snapshot], as MSG_GUIDANCE carries it. */
    private data class Guidance(
        val destination: String,
        val routeMeters: Long?,
        val maneuverType: Int?,
        val maneuverMeters: Int,
        val road: String,
        val drivingSide: Int,
        val arrivalEpochSeconds: Long?,
        val remainingSeconds: Long?,
    ) {
        companion object {
            fun of(snapshot: CarPlayGlance.Snapshot) = Guidance(
                destination = snapshot.destination,
                routeMeters = snapshot.routeMeters,
                maneuverType = snapshot.maneuverType,
                maneuverMeters = snapshot.distanceMeters,
                road = snapshot.road,
                drivingSide = snapshot.drivingSide,
                arrivalEpochSeconds = snapshot.arrivalEpochSeconds,
                remainingSeconds = snapshot.remainingSeconds,
            )
        }
    }

    private fun sendSettings(client: Messenger) =
        send(client, CarPlayEmbedProtocol.MSG_SETTINGS, Bundle().apply { putBundle(CarPlayEmbedProtocol.KEY_SETTINGS, EmbedSettings.snapshot(this@CarPlayEmbedService)) })

    private fun notice(client: Messenger, text: String, ok: Boolean) =
        send(client, CarPlayEmbedProtocol.MSG_NOTICE, Bundle().apply {
            putString(CarPlayEmbedProtocol.KEY_NOTICE, text)
            putBoolean(CarPlayEmbedProtocol.KEY_OK, ok)
        })

    private fun saveReport(client: Messenger) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            notice(client, getString(R.string.could_not_save_the_report), ok = false)
            return
        }
        val app = applicationContext
        val fileName = DiagnosticReport.fileName()
        Thread({
            val saved = runCatching { DiagnosticReport.saveToDownloads(app, fileName) }
                .onFailure { Log.w(TAG, "diagnostic report not saved", it) }
                .isSuccess
            main.post {
                notice(client, if (saved) "${getString(R.string.diagnostic_report_saved)} · Downloads/${DiagnosticExportStore.FOLDER}/$fileName" else getString(R.string.could_not_save_the_report), saved)
            }
        }, "revv-carplay-report").start()
    }

    private fun refuse(client: Messenger, caller: String, error: String) {
        Log.w(TAG, "$caller asked for CarPlay: $error")
        send(client, MSG_ERROR, Bundle().apply { putString(KEY_ERROR, error) })
    }

    private fun send(client: Messenger, what: Int, data: Bundle) {
        try {
            client.send(Message.obtain(null, what).apply { this.data = data })
        } catch (_: RemoteException) {
            embeds.remove(client.binder)?.release()
            unsubscribe(client.binder)
        }
    }

    private fun sendState(client: Messenger, status: EmbeddedCarPlay.Status) {
        send(client, MSG_STATE, Bundle().apply {
            putString(KEY_PHASE, status.phase.wire())
            putString(KEY_DETAIL, status.detail)
            putBoolean(KEY_WIRELESS, status.wireless)
            putString(KEY_HOTSPOT_MODE, status.hotspotMode)
            putBoolean(KEY_VIDEO_ACTIVE, status.videoActive)
            putStringArray(KEY_MISSING, status.missing.toTypedArray())
            putBoolean(CarPlayEmbedProtocol.KEY_RESET_WIFI_DIRECT, status.resetWifiDirect)
        })
    }

    /** CarPlay in one host view. Main thread. */
    @RequiresApi(Build.VERSION_CODES.R)
    private inner class Embed(
        private val client: Messenger,
        private val caller: String,
        hostToken: IBinder,
        display: android.view.Display,
        width: Int,
        height: Int,
    ) {
        private val host = SurfaceControlViewHost(createDisplayContext(display), display, hostToken)
        private var released = false
        private val view = EmbeddedCarPlayView(createDisplayContext(display))
        private val statusListener: (EmbeddedCarPlay.Status) -> Unit = { status -> sendState(client, status) }

        val surfacePackage: SurfaceControlViewHost.SurfacePackage? get() = host.surfacePackage

        init {
            host.setView(view, width.coerceAtLeast(1), height.coerceAtLeast(1))
            EmbeddedCarPlay.hostForwardsTouch = false
            EmbeddedCarPlay.addListener(statusListener)
            EmbeddedCarPlay.hostViews.incrementAndGet()
        }

        /** The driver tapped the car's icon in CarPlay: the host shows its own screen. */
        fun hostUiRequested() {
            if (!released) send(client, CarPlayEmbedProtocol.MSG_HOST_UI, Bundle())
        }

        fun resize(width: Int, height: Int, settled: Boolean) {
            if (released || width <= 0 || height <= 0) return
            host.relayout(width, height)
            EmbeddedCarPlay.resize(Size(width, height), settled)
        }

        fun release() {
            if (released) return
            released = true
            EmbeddedCarPlay.hostViews.decrementAndGet()
            EmbeddedCarPlay.removeListener(statusListener)
            host.release()
            Log.i(TAG, "$caller no longer shows CarPlay")
        }
    }

    private fun EmbeddedCarPlay.Phase.wire(): String = when (this) {
        EmbeddedCarPlay.Phase.IDLE -> CarPlayEmbedProtocol.PHASE_IDLE
        EmbeddedCarPlay.Phase.SETUP_REQUIRED -> CarPlayEmbedProtocol.PHASE_SETUP_REQUIRED
        EmbeddedCarPlay.Phase.STARTING -> CarPlayEmbedProtocol.PHASE_STARTING
        EmbeddedCarPlay.Phase.CONNECTING -> CarPlayEmbedProtocol.PHASE_CONNECTING
        EmbeddedCarPlay.Phase.CONNECTED -> CarPlayEmbedProtocol.PHASE_CONNECTED
        EmbeddedCarPlay.Phase.RECONNECTING -> CarPlayEmbedProtocol.PHASE_RECONNECTING
        EmbeddedCarPlay.Phase.FAILED -> CarPlayEmbedProtocol.PHASE_FAILED
    }

    private companion object {
        const val TAG = "RevvCarPlay-EmbedService"
        val SETTINGS_MESSAGES = setOf(
            CarPlayEmbedProtocol.MSG_GET_SETTINGS, CarPlayEmbedProtocol.MSG_SET_SETTING, CarPlayEmbedProtocol.MSG_SET_HOTSPOT, CarPlayEmbedProtocol.MSG_SET_PHONE,
            CarPlayEmbedProtocol.MSG_IMPORT_IDENTITY, CarPlayEmbedProtocol.MSG_REMOVE_IDENTITY, CarPlayEmbedProtocol.MSG_SAVE_REPORT,
            CarPlayEmbedProtocol.MSG_HOTSPOT_ON, CarPlayEmbedProtocol.MSG_RESET_WIFI_DIRECT,
        )
        // Android reports tethering started a moment before the hotspot reads as on.
        const val HOTSPOT_SETTLE_MILLIS = 2_000L
        const val GUIDANCE_REFRESH_MILLIS = 5_000L
    }
}
