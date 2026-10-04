package com.shilapi.xcertplay.embed

/**
 * The Messenger protocol between a host app (Revv) and RevvCarPlay's [CarPlayEmbedService].
 * Every message the host sends must set `replyTo`. See docs/REVV_INTEGRATION.md.
 */
object CarPlayEmbedProtocol {
    /** Bind with this action; the package differs between release and debug builds. */
    const val ACTION = "com.vivekkaushik.revvcarplay.action.EMBED_CARPLAY"
    /**
     * 2 added the settings messages (MSG_GET_SETTINGS to MSG_SAVE_REPORT, MSG_SETTINGS, MSG_NOTICE);
     * 3 added MSG_GUIDANCE.
     */
    const val VERSION = 3

    // Host -> RevvCarPlay.
    /** KEY_HOST_TOKEN, KEY_DISPLAY_ID, KEY_WIDTH, KEY_HEIGHT, KEY_SCREEN_WIDTH, KEY_SCREEN_HEIGHT. */
    const val MSG_ATTACH = 1
    /**
     * KEY_WIDTH, KEY_HEIGHT, KEY_SETTLED (default true): the host view changed size. A settled size
     * that differs from the session's makes CarPlay reconnect at it; an unsettled one (the host's
     * window has lost focus, e.g. to the notification shade, which also shows the system bars)
     * only letterboxes the picture until the host settles again.
     */
    const val MSG_RESIZE = 2
    /** The view is gone. The CarPlay session keeps running until MSG_STOP or the notification. */
    const val MSG_DETACH = 3
    /** End the CarPlay session. */
    const val MSG_STOP = 4
    /** Open Siri, as the car's voice button does. */
    const val MSG_SIRI = 5
    /**
     * KEY_EVENT (MotionEvent, coordinates relative to the host view), KEY_WIDTH, KEY_HEIGHT (the
     * host view's size). The host window is above the embedded hierarchy in input order, so the
     * system gives it the touches; forward every one here.
     */
    const val MSG_TOUCH = 6
    /**
     * KEY_WIRELESS (boolean), KEY_HOTSPOT_MODE (HOTSPOT_P2P or HOTSPOT_MANUAL): choose the link.
     * Saved in the companion's settings; a running session reconnects over the new link. A host
     * signed like RevvCarPlay also gets MSG_SETTINGS with the new link.
     */
    const val MSG_CONFIGURE = 7

    // Settings (version 2). Only a host signed with the same certificate as RevvCarPlay, or with one
    // in revvcarplay.trustedHostCertificates (Revv's Play signing key), may send these; anyone else
    // gets MSG_ERROR with ERROR_UNTRUSTED.
    /** Asks for MSG_SETTINGS. */
    const val MSG_GET_SETTINGS = 8
    /**
     * KEY_SETTING (one of SETTING_*), KEY_VALUE (int or boolean, as listed there). Saved; a running
     * session reconnects to apply it, a moment after the last change.
     */
    const val MSG_SET_SETTING = 9
    /** KEY_SSID, KEY_PASSPHRASE (empty for an open network): the car hotspot's details; wireless then links over it. */
    const val MSG_SET_HOTSPOT = 10
    /** KEY_ADDRESS, KEY_NAME: the paired iPhone wireless CarPlay connects to. */
    const val MSG_SET_PHONE = 11
    /**
     * KEY_FILES: a Bundle of picked file name to its bytes (16 KB at most each). RevvCarPlay finds
     * identity.pk8 and certificate.p7b among them, by name or else by size, and installs them.
     */
    const val MSG_IMPORT_IDENTITY = 12
    /** Removes the installed identity; CarPlay cannot start until another is imported. */
    const val MSG_REMOVE_IDENTITY = 13
    /** Saves a diagnostic report to Downloads/Revv/CarPlay; MSG_NOTICE says where. */
    const val MSG_SAVE_REPORT = 14
    /**
     * Turns the head unit's Wi-Fi hotspot on now; MSG_NOTICE says if it could not. Choosing the car
     * hotspot link (MSG_CONFIGURE, MSG_SET_HOTSPOT) and every session over it do the same.
     */
    const val MSG_HOTSPOT_ON = 15

    // RevvCarPlay -> host.
    /** KEY_SURFACE_PACKAGE, KEY_VERSION: put the package into your SurfaceView. */
    const val MSG_ATTACHED = 101
    /** KEY_PHASE, KEY_DETAIL, KEY_WIRELESS, KEY_HOTSPOT_MODE, KEY_VIDEO_ACTIVE, KEY_MISSING (when PHASE_SETUP_REQUIRED). */
    const val MSG_STATE = 102
    /** KEY_SETTINGS: every setting's current value (KEY_* below SETTING_*). Sent on request and after each change. */
    const val MSG_SETTINGS = 103
    /** KEY_NOTICE (a short line for the driver), KEY_OK: how an import, a hotspot save or a report went. */
    const val MSG_NOTICE = 104
    /**
     * CarPlay's route guidance, to a host signed like RevvCarPlay once it has asked for the settings,
     * and again on every change: KEY_DESTINATION (the destination's name as Apple Maps shows it, empty
     * without a route; CarPlay sends no coordinates), KEY_ROUTE_METERS, KEY_MANEUVER_TYPE (Apple's
     * RouteGuidanceManeuverType, absent without a next maneuver), KEY_MANEUVER_METERS, KEY_ROAD,
     * KEY_DRIVING_SIDE (1 for left-hand traffic), KEY_ARRIVAL, KEY_REMAINING_SECONDS. Numbers are
     * absent when unknown.
     */
    const val MSG_GUIDANCE = 105
    /** KEY_ERROR: the view cannot be shown, or (ERROR_UNTRUSTED) a settings message was refused. */
    const val MSG_ERROR = 199

    const val KEY_HOST_TOKEN = "hostToken"
    const val KEY_DISPLAY_ID = "displayId"
    const val KEY_WIDTH = "width"
    const val KEY_HEIGHT = "height"
    const val KEY_SETTLED = "settled"
    const val KEY_SCREEN_WIDTH = "screenWidth"
    const val KEY_SCREEN_HEIGHT = "screenHeight"
    const val KEY_SURFACE_PACKAGE = "surfacePackage"
    const val KEY_VERSION = "version"
    const val KEY_PHASE = "phase"
    const val KEY_DETAIL = "detail"
    const val KEY_WIRELESS = "wireless"
    const val KEY_VIDEO_ACTIVE = "videoActive"
    const val KEY_MISSING = "missing"
    const val KEY_ERROR = "error"
    const val KEY_EVENT = "event"
    const val KEY_HOTSPOT_MODE = "hotspotMode"
    const val KEY_SETTING = "setting"
    const val KEY_VALUE = "value"
    const val KEY_SETTINGS = "settings"
    const val KEY_SSID = "ssid"
    const val KEY_PASSPHRASE = "passphrase"
    const val KEY_ADDRESS = "address"
    const val KEY_NAME = "name"
    const val KEY_FILES = "files"
    const val KEY_NOTICE = "notice"
    const val KEY_OK = "ok"
    const val KEY_DESTINATION = "destination"
    /** long, metres left on the route. */
    const val KEY_ROUTE_METERS = "routeMeters"
    /** int. */
    const val KEY_MANEUVER_TYPE = "maneuverType"
    /** int, metres to the next maneuver. */
    const val KEY_MANEUVER_METERS = "maneuverMeters"
    /** The road the next maneuver turns onto, else the current one. */
    const val KEY_ROAD = "road"
    const val KEY_DRIVING_SIDE = "drivingSide"
    /** long, estimated arrival in epoch seconds. */
    const val KEY_ARRIVAL = "arrival"
    /** long. */
    const val KEY_REMAINING_SECONDS = "remainingSeconds"

    // Settings the host may change (MSG_SET_SETTING) and read back in KEY_SETTINGS.
    /** int, the CarPlay picture's assumed width in mm: 250 large, 300 medium, 350 small icons and text. */
    const val SETTING_CARPLAY_SIZE = "carPlaySize"
    /** int, tenths of the view's resolution the iPhone draws at: 10, 8 or 6. */
    const val SETTING_RESOLUTION = "resolution"
    /** int, 30 or 60. */
    const val SETTING_FRAME_RATE = "frameRate"
    /** boolean, HEVC video instead of H.264. */
    const val SETTING_HEVC = "hevc"
    /** boolean, CarPlay's controls on the right. */
    const val SETTING_RIGHT_HAND_DRIVE = "rightHandDrive"
    /** boolean, CarPlay media takes Android audio focus. */
    const val SETTING_AUDIO_FOCUS = "audioFocus"
    /** int 0–20: 0 routes media automatically, 1–20 pick a legacy Android stream. A preview tone plays on change. */
    const val SETTING_MEDIA_STREAM = "mediaStream"
    /** int 0–20, as SETTING_MEDIA_STREAM for navigation prompts. */
    const val SETTING_NAVIGATION_STREAM = "navigationStream"
    /** int, ms of music buffered: 300, 500 or 1000. */
    const val SETTING_MUSIC_BUFFER = "musicBuffer"
    /** boolean, usage/content-type audio routing; only when KEY_ADVANCED_AUDIO_AVAILABLE. */
    const val SETTING_ADVANCED_AUDIO = "advancedAudio"
    /** boolean, the head unit's GPS goes to the iPhone; needs RevvCarPlay's location permission (KEY_LOCATION_PERMITTED). */
    const val SETTING_LOCATION_REPORTING = "locationReporting"

    // Read-only entries of KEY_SETTINGS.
    const val KEY_IDENTITY_INSTALLED = "identityInstalled"
    /** The saved car hotspot name, empty when none. The password never leaves RevvCarPlay. */
    const val KEY_HOTSPOT_SSID = "hotspotSsid"
    /** The saved car hotspot details are complete and valid. */
    const val KEY_HOTSPOT_READY = "hotspotReady"
    /** The head unit's hotspot is on. Absent when the firmware hides its state. */
    const val KEY_HOTSPOT_ON = "hotspotOn"
    /** RevvCarPlay may turn the hotspot on: Android 11+ and "Modify system settings" granted to it. */
    const val KEY_HOTSPOT_SWITCH_ALLOWED = "hotspotSwitchAllowed"
    const val KEY_PHONE_ADDRESS = "phoneAddress"
    const val KEY_PHONE_NAME = "phoneName"
    const val KEY_ADVANCED_AUDIO_AVAILABLE = "advancedAudioAvailable"
    const val KEY_LOCATION_PERMITTED = "locationPermitted"

    /** Wi-Fi Direct group owned by the head unit (Android 10+). */
    const val HOTSPOT_P2P = "p2p"
    /** The car's own hotspot; its name and password are saved in the companion's Connection setup. */
    const val HOTSPOT_MANUAL = "manual"

    /** Open RevvCarPlay once to finish setup; KEY_MISSING lists what (SETUP_*). */
    const val PHASE_SETUP_REQUIRED = "setup_required"
    const val PHASE_IDLE = "idle"
    const val PHASE_STARTING = "starting"
    const val PHASE_CONNECTING = "connecting"
    const val PHASE_CONNECTED = "connected"
    const val PHASE_RECONNECTING = "reconnecting"
    const val PHASE_FAILED = "failed"

    /** No accessory identity: import identity.pk8 and certificate.p7b in RevvCarPlay's settings. */
    const val SETUP_IDENTITY = "identity"
    /** Wired CarPlay needs the one-time VPN consent dialog. */
    const val SETUP_VPN = "vpn"
    /** Wireless CarPlay needs Bluetooth / nearby-devices permissions. */
    const val SETUP_WIRELESS_PERMISSIONS = "wireless_permissions"
    /** Wireless in car-hotspot mode without saved hotspot details: finish Connection setup. */
    const val SETUP_HOTSPOT = "hotspot"

    const val ERROR_UNSUPPORTED = "unsupported" // Android 10 or older
    const val ERROR_BAD_REQUEST = "bad_request"
    /** A settings message from an app not signed like RevvCarPlay. */
    const val ERROR_UNTRUSTED = "untrusted"
}
