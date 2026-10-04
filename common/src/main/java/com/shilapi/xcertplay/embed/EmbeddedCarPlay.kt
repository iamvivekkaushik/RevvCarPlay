package com.shilapi.xcertplay.embed

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.CarPlayMediaKeys
import com.shilapi.xcertplay.CarPlaySessionDisplay
import com.shilapi.xcertplay.DiPlayBluetooth
import com.shilapi.xcertplay.DiPlayBootstrap
import com.shilapi.xcertplay.DiPlayPreferences
import com.shilapi.xcertplay.DiPlaySessionService
import com.shilapi.xcertplay.MapMirrors
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.network.HotspotSwitch
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.UsbDeviceId
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A CarPlay session for another app's view. It brings the stack up the way [com.shilapi.xcertplay.CarPlayHostActivity]
 * does (controller, media sink, foreground service, reconnects) but without a window of its own:
 * [CarPlayEmbedService] hands it the host's surface and touches, and the session keeps running
 * while the host shows another screen. One session per process; the companion's own full-screen
 * activity and this engine take turns through [CarPlayBackgroundSession]. Main thread unless noted.
 */
internal object EmbeddedCarPlay {
    enum class Phase { IDLE, SETUP_REQUIRED, STARTING, CONNECTING, CONNECTED, RECONNECTING, FAILED }

    data class Status(
        val phase: Phase = Phase.IDLE,
        val detail: String = "",
        val wireless: Boolean = false,
        /** CarPlayEmbedProtocol.HOTSPOT_P2P or HOTSPOT_MANUAL: how wireless CarPlay links. */
        val hotspotMode: String = CarPlayEmbedProtocol.HOTSPOT_P2P,
        /** The iPhone is streaming the main screen. */
        val videoActive: Boolean = false,
        /** What the driver must do in RevvCarPlay first (CarPlayEmbedProtocol.SETUP_*). */
        val missing: List<String> = emptyList(),
    )

    private const val TAG = "RevvCarPlay-Embed"
    private const val SCREEN_TYPE_MAIN = 110
    private const val SCREEN_TYPE_ALT = 111
    private const val RECONNECT_DELAY_MILLIS = 2_000L
    private const val IAP_TUNNEL_RECONNECT_DELAY_MILLIS = 15_000L
    private const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L
    // Long enough for the system bars to finish hiding again after the host regains focus.
    private const val RESIZE_DEBOUNCE_MILLIS = 800L
    private const val LAUNCH_SETTLE_MILLIS = 400L
    // Stepping through a setting's options reconnects once, after the last step.
    private const val SETTINGS_DEBOUNCE_MILLIS = 1_500L
    /** Relative aspect difference below which an adopted canvas is shown as is (a few pixels of bar at most). */
    private const val ASPECT_TOLERANCE = 0.01f

    private val main = Handler(Looper.getMainLooper())
    private val teardown: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "revv-carplay-teardown").apply { isDaemon = true }
    }
    private val listeners = CopyOnWriteArraySet<(Status) -> Unit>()

    private var appContext: Context? = null
    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var sessionDisplay: CarPlaySessionDisplay? = null
    private var surface: Surface? = null
    private var viewSize = Size(0, 0)
    private var screenSize = Size(0, 0)
    private var rotation = Surface.ROTATION_0
    private var generation = 0
    private var reconnectAttempts = 0
    private var reconnectScheduled = false
    private var restarting = false
    private var touchOutsideContent = false
    private val activeStreams = mutableSetOf<Int>()
    private val mirrorSink: (String, Surface?) -> Unit = { key, mirror -> sink?.setMirrorSurface(SCREEN_TYPE_ALT, key, mirror) }
    private val applyResize = Runnable { resizeNow() }
    private val applySettings = Runnable {
        if (controller != null && CarPlayBackgroundSession.isOwner(this)) restart("Settings changed")
    }
    // The first launch waits for the host view to settle: a view laid out once at a passing size
    // (rotation, first layout) would otherwise start a session only to tear it down and rebuild it,
    // and a Wi-Fi Direct group created right after one was removed is refused for a while.
    private var launchPending = false
    private val pendingLaunch = Runnable { launchPending = false; launch() }

    private fun scheduleLaunch() {
        launchPending = true
        main.removeCallbacks(pendingLaunch)
        main.postDelayed(pendingLaunch, LAUNCH_SETTLE_MILLIS)
    }

    /** The app whose view shows CarPlay; the connection notification opens it. */
    @Volatile var hostPackage: String? = null
        private set

    var status = Status()
        private set

    /**
     * The host forwards its view's touches (MSG_TOUCH) because the system gives them to the host
     * window, which is above the embedded hierarchy in input order. Where the system delivers them
     * to the embedded view instead, that view forwards them itself. Only one path ever sees a touch;
     * this flag just stops the embedded view from guessing once the host has spoken.
     */
    @Volatile var hostForwardsTouch = false

    /** A session exists or is being rebuilt. */
    val running: Boolean get() = controller != null || restarting

    fun addListener(listener: (Status) -> Unit) { listeners.add(listener); listener(status) }
    fun removeListener(listener: (Status) -> Unit) { listeners.remove(listener) }

    /** What the driver must still do in RevvCarPlay's own UI before a session can start. */
    fun missingSetup(context: Context): List<String> {
        val missing = mutableListOf<String>()
        if (runCatching { DiPlayBootstrap.ensure(context) }.isFailure) missing += CarPlayEmbedProtocol.SETUP_IDENTITY
        if (AirPlayPersistence.loadWirelessEnabled(context)) {
            if (requiredWirelessPermissions().any { !granted(context, it) }) missing += CarPlayEmbedProtocol.SETUP_WIRELESS_PERMISSIONS
            // Car-hotspot mode cannot start without the car's hotspot name and password; the
            // companion's Connection setup collects them (or switches to Wi-Fi Direct).
            if (AirPlayPersistence.loadWirelessHotspotMode(context) == WirelessHotspotMode.MANUAL &&
                ManualHotspotValidation.error(AirPlayPersistence.loadManualHotspotSsid(context), AirPlayPersistence.loadManualHotspotPassphrase(context)) != null
            ) missing += CarPlayEmbedProtocol.SETUP_HOTSPOT
        } else if (CarPlayVpnService.prepare(context) != null) {
            missing += CarPlayEmbedProtocol.SETUP_VPN
        }
        return missing
    }

    /**
     * Starts CarPlay for a host view of [view] pixels on a [screen]-pixel display, or keeps the
     * running session when it already matches. [host] is the host app's package.
     */
    fun start(context: Context, host: String?, view: Size, screen: Size, rotation: Int) {
        val app = context.applicationContext
        appContext = app
        hostPackage = host
        viewSize = view
        screenSize = screen
        this.rotation = rotation
        main.removeCallbacks(applyResize)
        val missing = missingSetup(app)
        if (missing.isNotEmpty()) {
            publish(Phase.SETUP_REQUIRED, app.getString(R.string.embed_setup_required), wireless = AirPlayPersistence.loadWirelessEnabled(app), missing = missing)
            return
        }
        if (controller?.isClosed() == true) forget()
        val current = controller
        if (current != null && CarPlayBackgroundSession.isOwner(this)) {
            val display = sessionDisplay
            if (display != null && display.windowWidth == view.width && display.windowHeight == view.height && display.rotation == rotation) {
                publish(status.phase, status.detail) // re-announce to the new attachment
                return
            }
            restart("Host view is now ${view.width}x${view.height}")
            return
        }
        if (CarPlayBackgroundSession.hasSession()) {
            // A session already runs: started from RevvCarPlay's own screen, or ours that that
            // screen adopted meanwhile. Take it over rather than rebuild it: tearing down a Wi-Fi
            // Direct group and creating a new one right away fails with "busy" for a while, and
            // the iPhone would drop and re-pair for nothing.
            val snapshot = CarPlayBackgroundSession.snapshot()
            if (snapshot != null && !snapshot.controller.isClosed()) {
                adopt(snapshot)
                return
            }
            // Being stopped, or stale: wait for it, then start fresh.
            forget()
            publish(Phase.STARTING, app.getString(R.string.embed_taking_over))
            restarting = true
            CarPlayBackgroundSession.stop {
                main.post {
                    restarting = false
                    if (controller == null) scheduleLaunch()
                }
            }
            return
        }
        scheduleLaunch()
    }

    /**
     * Continues a session another owner started. Its canvas keeps its size (the view letterboxes
     * it) until the host view actually changes size; the callbacks and the surface become ours.
     */
    private fun adopt(snapshot: CarPlayBackgroundSession.Snapshot) {
        val app = appContext ?: return
        val gen = ++generation
        restarting = false
        reconnectScheduled = false
        activeStreams.clear()
        controller = snapshot.controller
        sink = snapshot.sink
        // Remember the host view as this session's window so a same-size re-attach does not restart it.
        sessionDisplay = snapshot.display.copy(rotation = rotation, windowWidth = viewSize.width, windowHeight = viewSize.height)
        CarPlayBackgroundSession.store(snapshot.controller, snapshot.sink, viewSize.width, viewSize.height, this, sessionDisplay!!) { completion ->
            main.post { shutdown("Disconnect from the notification", completion) }
        }
        val wireless = AirPlayPersistence.loadWirelessEnabled(app)
        snapshot.controller.attachUi(sessionListener(gen), statusReporter(gen, wireless))
        snapshot.sink.setScreenStreamActiveChangedListener { type, active -> onStreamState(gen, type, active) }
        CarPlayMediaKeys.attach(app, snapshot.controller)
        MapMirrors.sink = mirrorSink
        MapMirrors.reapply()
        surface?.let { attach(snapshot.sink, it) }
        Log.i(TAG, "adopted running CarPlay session canvas=${snapshot.display.width}x${snapshot.display.height} view=${viewSize.width}x${viewSize.height}")
        publish(
            if (CarPlayBackgroundSession.active) Phase.CONNECTED else Phase.CONNECTING,
            app.getString(if (CarPlayBackgroundSession.active) R.string.embed_connected else R.string.embed_starting),
            wireless = wireless,
        )
        // A canvas shaped for another window would sit letterboxed in this view for the rest of
        // the session: the iPhone only takes a size at connection time. Reconnect once at the
        // view's own size; same-shape sessions carry on as they are.
        val canvasAspect = snapshot.display.width.toFloat() / snapshot.display.height
        val viewAspect = viewSize.width.toFloat() / viewSize.height
        if (kotlin.math.abs(canvasAspect - viewAspect) / viewAspect > ASPECT_TOLERANCE) {
            restart("Canvas ${snapshot.display.width}x${snapshot.display.height} does not fit the ${viewSize.width}x${viewSize.height} view")
        }
    }

    /**
     * The host view's size changed. A [settled] size reconnects CarPlay at it after a short pause
     * (the iPhone only takes a size at connection time); an unsettled one, such as the host
     * shrinking while the notification shade is down, is letterboxed and never reconnects.
     */
    fun resize(view: Size, settled: Boolean = true) {
        if (view.width <= 0 || view.height <= 0) return
        viewSize = view
        if (launchPending) {
            // Nothing runs yet: launch once, at the settled size.
            scheduleLaunch()
            return
        }
        main.removeCallbacks(applyResize)
        if (settled) main.postDelayed(applyResize, RESIZE_DEBOUNCE_MILLIS)
    }

    private fun resizeNow() {
        val display = sessionDisplay ?: return
        if (display.windowWidth == viewSize.width && display.windowHeight == viewSize.height) return
        if (controller == null || !CarPlayBackgroundSession.isOwner(this)) return
        restart("Host view resized ${display.windowWidth}x${display.windowHeight} -> ${viewSize.width}x${viewSize.height}")
    }

    fun attachSurface(surface: Surface) {
        this.surface = surface
        sink?.let { attach(it, surface) }
    }

    fun clearSurface(surface: Surface) {
        sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
        sink?.clearSurface(SCREEN_TYPE_ALT, surface)
        if (this.surface === surface) this.surface = null
    }

    /** Where the CarPlay canvas sits inside a host view of this size (letterboxed when shapes differ). */
    fun videoLayout(viewWidth: Int, viewHeight: Int): CarPlayVideoLayout? {
        val display = sessionDisplay ?: return null
        if (viewWidth <= 0 || viewHeight <= 0) return null
        return CarPlayVideoLayout.fit(display.width, display.height, viewWidth, viewHeight)
    }

    /** Forwards a touch from a host view of the given size to the iPhone. */
    fun sendTouch(event: MotionEvent, viewWidth: Int, viewHeight: Int): Boolean {
        val content = videoLayout(viewWidth, viewHeight) ?: return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) touchOutsideContent = !content.contains(event.x, event.y)
        if (touchOutsideContent) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) touchOutsideContent = false
            return false
        }
        return controller?.sendTouch(CarPlayTouchMapper.contacts(event, content)) ?: false
    }

    fun requestSiri(): Boolean = controller?.requestSiri() ?: false

    /**
     * Saves the link the host chose and applies it: a running session reconnects over it, an
     * attached view without a session tries again (setup may now be missing, e.g. VPN consent).
     * The car hotspot link also turns the head unit's hotspot on; [onHotspot] hears how that went.
     */
    fun configure(context: Context, wireless: Boolean, hotspotMode: String?, onHotspot: (HotspotSwitch.Result) -> Unit = {}) {
        val app = context.applicationContext
        appContext = app
        AirPlayPersistence.saveWirelessEnabled(app, wireless)
        hotspotMode?.let {
            AirPlayPersistence.saveWirelessHotspotMode(
                app,
                if (it == CarPlayEmbedProtocol.HOTSPOT_MANUAL) WirelessHotspotMode.MANUAL else WirelessHotspotMode.WIFI_P2P,
            )
        }
        Log.i(TAG, "link configured wireless=$wireless hotspotMode=${hotspotMode ?: "unchanged"}")
        if (wireless && AirPlayPersistence.loadWirelessHotspotMode(app) == WirelessHotspotMode.MANUAL) HotspotSwitch.turnOn(app, onHotspot)
        main.removeCallbacks(applyResize)
        val missing = missingSetup(app)
        if (missing.isNotEmpty()) {
            shutdown("Link changed; setup needed") {
                publish(Phase.SETUP_REQUIRED, app.getString(R.string.embed_setup_required), missing = missing)
            }
            return
        }
        when {
            controller != null && CarPlayBackgroundSession.isOwner(this) -> restart("Link changed")
            CarPlayBackgroundSession.hasSession() -> {
                restarting = true
                CarPlayBackgroundSession.stop { main.post { restarting = false; if (controller == null) launch() } }
            }
            viewSize.width > 0 -> scheduleLaunch()
            else -> publish(Phase.IDLE, app.getString(R.string.embed_idle))
        }
    }

    /**
     * The host changed a setting. A session this engine runs reconnects to apply it when
     * [reconnect], a moment after the last change; without a session the host hears whether setup
     * is now complete (an identity imported, hotspot details saved) or missing again.
     */
    fun settingsChanged(context: Context, reconnect: Boolean) {
        val app = context.applicationContext
        appContext = app
        if (!running) {
            val missing = missingSetup(app)
            when {
                missing.isNotEmpty() -> publish(Phase.SETUP_REQUIRED, app.getString(R.string.embed_setup_required), missing = missing)
                status.phase == Phase.SETUP_REQUIRED -> publish(Phase.IDLE, app.getString(R.string.embed_idle))
                else -> publish(status.phase, status.detail)
            }
            return
        }
        main.removeCallbacks(applySettings)
        if (reconnect) main.postDelayed(applySettings, SETTINGS_DEBOUNCE_MILLIS)
        else publish(status.phase, status.detail)
    }

    /** Ends the session, whoever owns it. */
    fun stop(completion: () -> Unit = {}) {
        main.removeCallbacks(applyResize)
        if (CarPlayBackgroundSession.isOwner(this) || controller != null) shutdown("Host asked to disconnect", completion)
        else CarPlayBackgroundSession.stop { main.post(completion) }
    }

    // --- Session bring-up ---------------------------------------------------------------------

    private fun launch() {
        val app = appContext ?: return
        // One session at a time: a second attach or a late pending launch must not start another
        // bring-up on top of a running or rebuilding one.
        if (controller != null || restarting) return
        val view = viewSize
        if (view.width <= 0 || view.height <= 0) return
        val gen = ++generation
        reconnectScheduled = false
        activeStreams.clear()
        val settings = Settings(app)
        // The head unit may have switched its hotspot off since the link was chosen (a restart does).
        if (settings.wireless && settings.hotspotMode == WirelessHotspotMode.MANUAL) {
            HotspotSwitch.turnOn(app) { Log.i(TAG, "car hotspot before connecting: $it") }
        }
        val identity = AirPlayPersistence.loadIdentity(app)
        val runtime = runtimeConfig(app, settings, identity)
        val airPlay = airPlayConfig(app, settings, identity, view)
        Log.i(TAG, "starting embedded CarPlay view=${view.width}x${view.height} canvas=${airPlay.main.widthPixels}x${airPlay.main.heightPixels} " +
            "transport=${runtime.transport} hevc=${airPlay.hevc} microphone=${airPlay.microphone} location=${runtime.locationReportingEnabled}")
        val renderer = AndroidMediaSink(
            surface = null,
            videoWidth = airPlay.main.widthPixels,
            videoHeight = airPlay.main.heightPixels,
            preferSoftwareHevcDecoder = settings.hevcSoftwareDecoder,
            advancedAudioChannelMapping = settings.advancedAudioChannelMapping,
            audioFocusEnabled = AirPlayPersistence.loadAudioFocusEnabled(app),
            mediaChannel = AirPlayPersistence.loadMediaAudioChannel(app),
            navigationChannel = AirPlayPersistence.loadNavigationAudioChannel(app),
            context = app,
            navigationStreamType = settings.navigationStreamType,
            onScreenStreamActiveChanged = { type, active -> onStreamState(gen, type, active) },
            mediaBufferMillis = AirPlayPersistence.loadMediaBufferMillis(app),
            onAudioDiagnostic = { message -> Log.d(TAG, message) },
            onMediaAudioChanged = CarPlayMediaKeys::onMediaAudioChanged,
        )
        sink = renderer
        surface?.let { attach(renderer, it) }
        MapMirrors.sink = mirrorSink
        MapMirrors.reapply()
        val media = CarPlayMediaEngine(sink = renderer, microphoneEnabled = settings.microphone, audioCaptureDirectory = null)
        val pairings = AirPlayPersistence.loadPairings(app) { id, key -> AirPlayPersistence.savePairing(app, id, key) }
        val next = CarPlayController(
            context = app,
            config = runtime,
            airPlayConfig = airPlay,
            identity = identity,
            pairings = pairings,
            listener = sessionListener(gen),
            media = media,
            reportStatus = statusReporter(gen, settings.wireless),
            loadPairRecord = { AirPlayPersistence.loadLockdownRecord(app) },
            savePairRecord = { record -> AirPlayPersistence.saveLockdownRecord(app, record) },
            clearPairRecord = { AirPlayPersistence.clearLockdownRecord(app) },
            locationProvider = if (runtime.locationReportingEnabled) AndroidCarPlayLocationProvider(app) else null,
        )
        controller = next
        CarPlayMediaKeys.attach(app, next)
        val display = CarPlaySessionDisplay(
            airPlay.main.widthPixels, airPlay.main.heightPixels, rotation,
            hideTopBar = true, hideBottomBar = true, windowWidth = view.width, windowHeight = view.height,
        )
        sessionDisplay = display
        CarPlayBackgroundSession.store(next, renderer, view.width, view.height, this, display) { completion ->
            main.post { shutdown("Disconnect from the notification", completion) }
        }
        publish(Phase.STARTING, app.getString(R.string.embed_starting), wireless = settings.wireless)
        try {
            app.startForegroundService(Intent(app, DiPlaySessionService::class.java))
            next.start()
        } catch (error: RuntimeException) {
            Log.w(TAG, "embedded CarPlay could not start", error)
            shutdown("CarPlay could not start: ${error.javaClass.simpleName}")
            publish(Phase.FAILED, app.getString(R.string.embed_could_not_start))
        }
    }

    private fun attach(renderer: AndroidMediaSink, surface: Surface) {
        renderer.setSurface(SCREEN_TYPE_MAIN, surface)
        // Without a cluster display the alternate screen, when the iPhone sends one, shares the view.
        if (!AirPlayPersistence.loadClusterMapEnabled(appContext ?: return)) renderer.setSurface(SCREEN_TYPE_ALT, surface)
    }

    private fun sessionListener(gen: Int): AirPlaySessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            main.post {
                if (gen != generation) return@post
                CarPlayBackgroundSession.active = true
                reconnectAttempts = 0
                publish(Phase.CONNECTED, appContext?.getString(R.string.embed_connected) ?: "")
            }
        }

        override fun onSessionEnded(session: AirPlaySession) {
            main.post {
                if (gen != generation) return@post
                CarPlayBackgroundSession.active = false
                activeStreams.clear()
                reconnectAfterLoss("AirPlay session ended")
            }
        }

        override fun onTransportError(message: String) {
            main.post {
                if (gen != generation) return@post
                activeStreams.clear()
                reconnectAfterLoss("CarPlay transport error: $message")
            }
        }

        override fun onDebugLog(message: String) {
            Log.d(TAG, message)
        }
    }

    private fun statusReporter(gen: Int, wireless: Boolean): (CarPlayStatus) -> Unit = { report ->
        main.post {
            if (gen == generation) {
                val app = appContext
                val detail = app?.let { report.describe(it, wireless) } ?: report.toString()
                when {
                    report is CarPlayStatus.Failed && report.wifiResetRequired -> publish(Phase.FAILED, detail)
                    report is CarPlayStatus.Failed -> { publish(Phase.RECONNECTING, detail); reconnectAfterLoss(detail) }
                    CarPlayBackgroundSession.active -> publish(Phase.CONNECTED, detail)
                    else -> publish(Phase.CONNECTING, detail)
                }
            }
        }
    }

    private fun onStreamState(gen: Int, type: Int, active: Boolean) {
        main.post {
            if (gen != generation) return@post
            if (active) activeStreams.add(type) else activeStreams.remove(type)
            if (type == SCREEN_TYPE_ALT) MapMirrors.setStreamActive(active)
            publish(status.phase, status.detail)
        }
    }

    private fun reconnectAfterLoss(reason: String) {
        if (!CarPlayBackgroundSession.isOwner(this) || restarting || reconnectScheduled) return
        reconnectScheduled = true
        val gen = generation
        val delayMillis = if (reason.contains("AirPlay iAP tunnel", ignoreCase = true)) {
            IAP_TUNNEL_RECONNECT_DELAY_MILLIS
        } else {
            (RECONNECT_DELAY_MILLIS * (1L shl reconnectAttempts.coerceAtMost(4))).coerceAtMost(30_000L)
        }
        reconnectAttempts += 1
        Log.i(TAG, "$reason; retrying in ${delayMillis}ms")
        publish(Phase.RECONNECTING, appContext?.getString(R.string.embed_reconnecting) ?: reason)
        main.postDelayed({
            reconnectScheduled = false
            if (gen == generation && !restarting && controller != null) restart("Reconnecting after $reason")
        }, delayMillis)
    }

    /** Tears the stack down and brings it up again at the current view size. */
    private fun restart(reason: String) {
        val app = appContext ?: return
        if (!CarPlayBackgroundSession.isOwner(this) || restarting) return
        Log.i(TAG, "$reason; rebuilding at ${viewSize.width}x${viewSize.height}")
        val gen = ++generation
        restarting = true
        activeStreams.clear()
        val oldController = controller
        val oldSink = sink
        CarPlayMediaKeys.detach(oldController)
        CarPlayBackgroundSession.clear(oldController, keepOwner = true)
        controller = null
        sink = null
        sessionDisplay = null
        publish(Phase.RECONNECTING, app.getString(R.string.embed_reconnecting))
        teardown.execute {
            oldController?.close()
            oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
            oldSink?.close()
            main.post {
                restarting = false
                if (gen == generation) launch()
            }
        }
    }

    private fun shutdown(reason: String, completion: () -> Unit = {}) {
        generation += 1
        main.removeCallbacks(applyResize)
        main.removeCallbacks(applySettings)
        main.removeCallbacks(pendingLaunch)
        launchPending = false
        reconnectScheduled = false
        restarting = false
        activeStreams.clear()
        val oldController = controller
        val oldSink = sink
        CarPlayMediaKeys.detach(oldController)
        CarPlayBackgroundSession.clear(oldController)
        CarPlayBackgroundSession.active = false
        forget()
        if (MapMirrors.sink === mirrorSink) {
            MapMirrors.sink = null
            MapMirrors.setStreamActive(false)
        }
        Log.i(TAG, "shutdown reason=$reason")
        publish(Phase.IDLE, appContext?.getString(R.string.embed_idle) ?: "")
        val app = appContext
        teardown.execute {
            oldController?.close()
            oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
            oldSink?.close()
            app?.stopService(Intent(app, DiPlaySessionService::class.java))
            main.post(completion)
        }
    }

    private fun forget() {
        controller = null
        sink = null
        sessionDisplay = null
    }

    private fun publish(phase: Phase, detail: String, wireless: Boolean = status.wireless, missing: List<String> = emptyList()) {
        val app = appContext
        status = Status(
            phase = phase,
            detail = detail,
            wireless = if (app != null) AirPlayPersistence.loadWirelessEnabled(app) else wireless,
            hotspotMode = if (app != null && AirPlayPersistence.loadWirelessHotspotMode(app) == WirelessHotspotMode.MANUAL) {
                CarPlayEmbedProtocol.HOTSPOT_MANUAL
            } else {
                CarPlayEmbedProtocol.HOTSPOT_P2P
            },
            videoActive = phase == Phase.CONNECTED && SCREEN_TYPE_MAIN in activeStreams,
            missing = missing,
        )
        listeners.forEach { it(status) }
    }

    // --- Configuration ------------------------------------------------------------------------

    private class Settings(context: Context) {
        val displayScaleTenths = AirPlayPersistence.loadDisplayScaleTenths(context)
        val hevcEnabled = AirPlayPersistence.loadHevcEnabled(context)
        val hevcSoftwareDecoder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && AirPlayPersistence.loadHevcSoftwareDecoderEnabled(context)
        val advancedAudioChannelMapping = context.resources.getBoolean(R.bool.config_advanced_audio_channel_mapping) &&
            AirPlayPersistence.loadAdvancedAudioChannelMapping(context)
        val navigationStreamType = AirPlayPersistence.loadNavigationStreamType(context)
        val manufacturer = AirPlayPersistence.loadManufacturer(context).trim().ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }
        val model = AirPlayPersistence.loadModel(context).trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL }
        val oemLabel = AirPlayPersistence.loadOemLabel(context)
        val fps = AirPlayPersistence.loadFps(context)
        val widthPhysicalMm = AirPlayPersistence.loadWidthPhysicalMm(context)
        val physicalSizeBasis = AirPlayPersistence.loadPhysicalSizeBasis(context)
        val rightHandDrive = AirPlayPersistence.loadRightHandDrive(context)
        val safeAreaDrawOutside = AirPlayPersistence.loadSafeAreaDrawOutside(context)
        val locationReporting = AirPlayPersistence.loadLocationReportingEnabled(context) && granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val microphone = granted(context, Manifest.permission.RECORD_AUDIO)
        val wireless = AirPlayPersistence.loadWirelessEnabled(context)
        val mfiTarget = AirPlayPersistence.loadMfiTarget(context)
        val mfiI2cPath = AirPlayPersistence.loadMfiI2cPath(context)
        val remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(context)
        val remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(context)
        val hotspotMode = AirPlayPersistence.loadWirelessHotspotMode(context)
        val manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(context)
        val manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(context)
        val manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(context)
        val manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(context)
        val manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(context)
    }

    private fun runtimeConfig(context: Context, settings: Settings, identity: AirPlayIdentity): CarPlayRuntimeConfig {
        val deviceId = DiPlayBootstrap.deviceId(identity)
        return CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.LOCAL,
            ch341Devices = if (settings.mfiTarget == MfiTarget.USB_CH341) listOf(UsbDeviceId(0x1a86, 0x5512)) else emptyList(),
            ch341MfiResetGpio = null,
            linuxI2cPath = if (settings.mfiTarget == MfiTarget.I2C) settings.mfiI2cPath.trim() else null,
            remoteMfiServer = settings.remoteMfiServer.trim().takeIf { it.isNotEmpty() },
            remoteMfiToken = settings.remoteMfiToken.takeIf { it.isNotEmpty() },
            identification = Iap2IdentificationConfig(
                name = context.getString(R.string.app_name),
                modelIdentifier = settings.model,
                manufacturer = settings.manufacturer,
                serialNumber = "REVV-" + deviceId.replace(":", ""),
                firmwareVersion = "0.1.0",
                hardwareVersion = "1.0",
                carPlayUsbInterfaceNumber = 3,
                locationInformationEnabled = settings.locationReporting,
                vehicleStatusEnabled = false,
                vehicleSpeedEnabled = false,
            ),
            label = context.getString(R.string.app_name),
            hostName = "revv-" + deviceId.replace(":", "").lowercase(),
            hostMac = deviceId.split(":").map { it.toInt(16).toByte() }.toByteArray(),
            wirelessBluetoothDeviceAddress = DiPlayPreferences.phoneAddress(context),
            transport = if (settings.wireless) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
            wirelessHotspotMode = settings.hotspotMode,
            manualHotspotSsid = settings.manualHotspotSsid,
            manualHotspotPassphrase = settings.manualHotspotPassphrase,
            manualHotspotBand = settings.manualHotspotBand,
            manualHotspotChannel = settings.manualHotspotChannel,
            manualHotspotSecurity = settings.manualHotspotSecurity,
            locationReportingEnabled = settings.locationReporting,
        )
    }

    private fun airPlayConfig(context: Context, settings: Settings, identity: AirPlayIdentity, view: Size): AirPlayConfig {
        // The host view is a part of the screen: its physical size follows from the screen's.
        val physical = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = view.width,
            currentHeightPixels = view.height,
            maximumWidthPixels = maxOf(screenSize.width, view.width),
            maximumHeightPixels = maxOf(screenSize.height, view.height),
            referenceMillimeters = settings.widthPhysicalMm,
            basis = settings.physicalSizeBasis,
        )
        val base = AirPlayDisplayConfig(
            widthPixels = view.width,
            heightPixels = view.height,
            widthPhysicalMm = physical.widthMm,
            heightPhysicalMm = physical.heightMm,
            fps = settings.fps,
        )
        val scaled = CarPlayDisplayScale.apply(base, settings.displayScaleTenths)
        val display = scaled.copy(
            safeArea = AirPlaySafeArea.toInsets(
                mapping = AirPlayPersistence.loadSafeAreaRect(context, view.width, view.height),
                activityWidthPixels = view.width,
                activityHeightPixels = view.height,
                displayWidthPixels = scaled.widthPixels,
                displayHeightPixels = scaled.heightPixels,
            ),
            safeAreaDrawOutside = settings.safeAreaDrawOutside,
        )
        return AirPlayConfig(
            deviceName = context.getString(R.string.app_name),
            deviceId = DiPlayBootstrap.deviceId(identity),
            btMac = DiPlayBluetooth.localAddress(context) ?: DiPlayBootstrap.deviceId(identity),
            sourceVersion = "950.7.1",
            main = display,
            cluster = null,
            rightHandDrive = settings.rightHandDrive,
            hevc = settings.hevcEnabled,
            microphone = settings.microphone,
            manufacturer = settings.manufacturer,
            model = settings.model,
            oemLabel = settings.oemLabel,
            icons = listOf(loadIcon(context)),
            videoInCar = false,
        )
    }

    private fun loadIcon(context: Context): AirPlayIcon {
        val custom = runCatching { AirPlayPersistence.loadCustomAirPlayIconFile(context)?.readBytes() }.getOrNull()
        val bytes = custom?.takeIf { decode(it) != null } ?: context.resources.openRawResource(R.raw.ic_car_home).use { it.readBytes() }
        return decode(bytes) ?: error("Packaged AirPlay icon is invalid")
    }

    private fun decode(encoded: ByteArray): AirPlayIcon? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth != bounds.outHeight) return null
        return AirPlayIcon(bounds.outWidth, bounds.outHeight, encoded)
    }

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun requiredWirelessPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    private fun CarPlayStatus.describe(context: Context, wireless: Boolean): String = when (this) {
        CarPlayStatus.DiscoveringMfi -> context.getString(R.string.preparing_mfi_authentication)
        CarPlayStatus.WaitingForMfi -> context.getString(R.string.waiting_for_mfi_coprocessor)
        CarPlayStatus.RequestingMfiPermission -> context.getString(R.string.requesting_mfi_usb_permission)
        CarPlayStatus.MfiReady -> context.getString(R.string.mfi_authentication_ready)
        CarPlayStatus.StartingHotspot -> context.getString(R.string.starting_wireless_hotspot)
        is CarPlayStatus.HotspotReady ->
            context.getString(R.string.status_hotspot_ready, backend, ssid, band, if (channel == 0) context.getString(R.string.auto_value) else channel.toString())
        CarPlayStatus.WaitingForPairedIphone -> context.getString(R.string.waiting_for_paired_iphone)
        CarPlayStatus.ConnectingBluetooth -> context.getString(R.string.connecting_bluetooth)
        CarPlayStatus.RunningWireless -> context.getString(R.string.wireless_carplay_control_running)
        CarPlayStatus.WirelessActive -> context.getString(R.string.wireless_carplay_active)
        CarPlayStatus.DiscoveringIphone -> context.getString(R.string.discovering_iphone)
        CarPlayStatus.WaitingForIphone -> context.getString(R.string.waiting_for_iphone_over_usb)
        CarPlayStatus.RequestingIphonePermission -> context.getString(R.string.requesting_iphone_usb_permission)
        CarPlayStatus.WaitingForReenumeration -> context.getString(R.string.status_waiting_reenumeration)
        CarPlayStatus.SelectingConfiguration -> context.getString(R.string.selecting_carplay_configuration)
        CarPlayStatus.OpeningDataPaths -> context.getString(R.string.opening_usb_data_paths)
        CarPlayStatus.Pairing -> context.getString(R.string.pairing_with_iphone)
        CarPlayStatus.ConnectingControl -> context.getString(R.string.connecting_iap2_control)
        CarPlayStatus.AttachingNetwork ->
            if (wireless) context.getString(R.string.starting_airplay_service) else context.getString(R.string.status_attaching_ncm)
        CarPlayStatus.RunningControl -> context.getString(R.string.carplay_control_running)
        CarPlayStatus.ControlEnded -> context.getString(R.string.carplay_control_window_ended)
        is CarPlayStatus.Failed -> context.getString(R.string.status_failed, message)
    }
}
