# CarPlay inside a host app (Revv)

RevvCarPlay can run CarPlay inside another app's layout. The host app (Revv's Auto screen) gives
RevvCarPlay a `SurfaceView`; RevvCarPlay draws the CarPlay picture into it and receives touch
from it directly, while the USB/Wi-Fi link, decoder, audio, microphone and foreground service all
live in RevvCarPlay's own process. The host sees pixels and a few status messages, nothing else.

Requirements:

- Android 11 (API 30) or newer on the head unit (`SurfaceControlViewHost`);
- RevvCarPlay installed, opened once for setup: import the accessory identity (Settings → CarPlay
  identity), accept the VPN consent for USB, grant Bluetooth / nearby-devices permissions for
  wireless. The embed service reports `setup_required` with what is missing until then;
- the host declares package visibility for the service action (below).

The companion's own full-screen screen and the embedded view take turns: attaching from a host ends
a session the full-screen screen started, and opening the full-screen screen adopts a running
embedded session. Run one at a time.

## Protocol

RevvCarPlay exports a bound service, action `com.vivekkaushik.revvcarplay.action.EMBED_CARPLAY`
(the package differs between release and debug builds, so find the service by its action). Talk to
it with [`Messenger`](https://developer.android.com/reference/android/os/Messenger); set `replyTo`
on every message. Constants live in `CarPlayEmbedProtocol`.

| Direction | `what` | Data (`Bundle`) | Meaning |
|---|---|---|---|
| host → companion | `1` ATTACH | `hostToken` (IBinder), `displayId` (int), `width`, `height` (px), `screenWidth`, `screenHeight` (px, optional) | Show CarPlay in this view, at this size |
| host → companion | `2` RESIZE | `width`, `height`, `settled` (boolean, default true) | The view changed size. Settled: CarPlay reconnects at the new size after a short pause. Not settled (your window lost focus, e.g. to the notification shade): the picture is letterboxed, no reconnect; send the size again with `settled` true when focus returns |
| host → companion | `3` DETACH | — | The view is gone. The session keeps running (audio continues) |
| host → companion | `4` STOP | — | End the CarPlay session |
| host → companion | `5` SIRI | — | Open Siri |
| host → companion | `7` CONFIGURE | `wireless` (boolean), `hotspotMode` (`p2p` or `manual`) | Choose USB, Wi-Fi Direct or the car hotspot; saved in the companion, a running session reconnects. A host signed like RevvCarPlay also gets SETTINGS |
| host → companion | `6` TOUCH | `event` (MotionEvent, relative to the view), `width`, `height` (the view's size) | A touch on the host view, forwarded to the iPhone |
| host → companion | `8` GET_SETTINGS | — | Send SETTINGS; from now on also send STATE on every change, view or not |
| host → companion | `9` SET_SETTING | `setting` (a `SETTING_*` name), `value` (int or boolean) | Save one setting; a running session reconnects 1.5 s after the last change |
| host → companion | `10` SET_HOTSPOT | `ssid`, `passphrase` (empty for an open hotspot) | Save the car hotspot's details and link over it; NOTICE says whether they were usable |
| host → companion | `11` SET_PHONE | `address`, `name` | The paired iPhone wireless CarPlay connects to |
| host → companion | `12` IMPORT_IDENTITY | `files` (Bundle: file name → bytes, 16 KB at most each) | Find identity.pk8 and certificate.p7b among the files and install them; NOTICE says how it went |
| host → companion | `13` REMOVE_IDENTITY | — | End any session and remove the identity |
| host → companion | `14` SAVE_REPORT | — | Save a diagnostic report to Downloads/Revv/CarPlay; NOTICE says where |
| host → companion | `15` HOTSPOT_ON | — | Turn the head unit's Wi-Fi hotspot on now; NOTICE says if it could not |
| host → companion | `16` RESET_WIFI_DIRECT | — | End the device's Wi-Fi Direct connection, whichever app made it (screen mirroring to a TV, say), then connect CarPlay (version 4). Send only when the driver asks, after STATE's `resetWifiDirect`; NOTICE says if it could not |
| companion → host | `101` ATTACHED | `surfacePackage` (SurfacePackage), `version` (int) | Put the package into your SurfaceView |
| companion → host | `102` STATE | `phase`, `detail`, `hotspotMode` (strings), `wireless`, `videoActive`, `resetWifiDirect` (booleans), `missing` (string[]) | Status, sent on every change and once on attach |
| companion → host | `103` SETTINGS | `settings` (Bundle, below) | Every setting's value, on request and after each change |
| companion → host | `104` NOTICE | `notice` (string), `ok` (boolean) | A line for the driver about an import, a hotspot save or a report |
| companion → host | `105` GUIDANCE | `destination` (string), `routeMeters`, `arrival` (epoch s), `remainingSeconds` (longs), `maneuverType`, `maneuverMeters`, `drivingSide` (ints), `road` (string); numbers absent when unknown | CarPlay's route guidance (version 3), to a host signed like RevvCarPlay after GET_SETTINGS, then on every change. See below |
| companion → host | `199` ERROR | `error`: `unsupported`, `bad_request` or `untrusted` | The view cannot be shown, or a settings message was refused |

`phase` is one of `setup_required`, `idle`, `starting`, `connecting`, `connected`, `reconnecting`,
`failed`. `detail` is a short human-readable line (localised to the companion's language).
`videoActive` is true while the iPhone streams the main screen. With `setup_required`, `missing`
lists `identity`, `vpn`, `wireless_permissions` and/or `hotspot` (car-hotspot mode without saved hotspot details).
With `failed`, `resetWifiDirect` (version 4) means another Wi-Fi Direct connection holds the radio:
Android runs one at a time, and the companion never ends someone else's by itself. `detail` names
the device on its other end when known; offer the driver RESET_WIFI_DIRECT.

The iPhone draws CarPlay at the view's pixel size (scaled by the companion's Resolution setting),
so the picture fills the view without letterboxing. The host's window is above the embedded
hierarchy in input order, so the system gives touches to the host: forward your SurfaceView's
MotionEvents with TOUCH and the companion maps them to CarPlay touches. Three-finger gestures and the companion's own menu
are not available in the embedded view.

## Route guidance (protocol version 3)

While the iPhone guides a route, GUIDANCE carries what iAP2 route guidance tells the car: the
destination's **name** as Apple Maps shows it (CarPlay never sends its coordinates), the distance
and time left, the arrival time, and the next maneuver (`maneuverType` is Apple's
RouteGuidanceManeuverType, `drivingSide` 1 for left-hand traffic). An empty `destination` and no
`maneuverType` mean no route. A route the iPhone stops updating for 30 s counts as ended. Revv
looks the name up near the car and routes its own map there when the place's distance fits
`routeMeters`; otherwise it shows these values as they are.

## Settings (protocol version 2)

RevvCarPlay keeps no settings screen of its own for what affects the CarPlay session; the host
shows them. Messages 8–15 (and GUIDANCE) are only for an app signed with RevvCarPlay's certificate
(`PackageManager.checkSignatures`) or with one listed in `revvcarplay.trustedHostCertificates` in
gradle.properties (`PackageManager.hasSigningCertificate`, which follows key rotation); others get
ERROR `untrusted`. So build Revv and RevvCarPlay with the same key, and list Revv's Google Play app
signing certificate there, since Play signs Revv with that instead. The SETTINGS bundle holds:

| Key | Type | Changeable | Meaning |
|---|---|---|---|
| `carPlaySize` | int | yes | Assumed CarPlay width in mm: 250 large, 300 medium, 350 small icons and text |
| `resolution` | int | yes | Tenths of the view's resolution the iPhone draws at: 10, 8 or 6 |
| `frameRate` | int | yes | 30 or 60 |
| `hevc` | boolean | yes | HEVC instead of H.264 |
| `rightHandDrive` | boolean | yes | CarPlay's controls on the right |
| `audioFocus` | boolean | yes | CarPlay media takes Android audio focus |
| `mediaStream`, `navigationStream` | int | yes | 0 routes automatically, 1–20 a legacy Android stream; a preview tone plays on change |
| `musicBuffer` | int | yes | 300, 500 or 1000 ms |
| `advancedAudio` | boolean | when `advancedAudioAvailable` | Usage / content-type routing |
| `locationReporting` | boolean | yes | Head-unit GPS to the iPhone; needs `locationPermitted` (granted on Android's page for RevvCarPlay) |
| `identityInstalled` | boolean | no | An identity is installed |
| `wireless`, `hotspotMode` | boolean, string | via CONFIGURE | The link |
| `hotspotSsid`, `hotspotReady` | string, boolean | via SET_HOTSPOT | Saved car hotspot name, and whether the saved details are usable. The password never leaves RevvCarPlay |
| `hotspotOn` | boolean | no | The head unit's hotspot is on; absent when the firmware hides it |
| `hotspotSwitchAllowed` | boolean | no | RevvCarPlay may turn the hotspot on: Android 11+ and "Modify system settings" granted to it |
| `phoneAddress`, `phoneName` | string | via SET_PHONE | The iPhone for wireless CarPlay |

Runtime permissions and the VPN consent belong to RevvCarPlay and can only be asked for by its own
screen: when STATE says `setup_required` with `vpn` or `wireless_permissions`, open RevvCarPlay.

With the car hotspot link, RevvCarPlay turns the head unit's Wi-Fi hotspot on when the link is
chosen (CONFIGURE, SET_HOTSPOT) and before every session over it. Android keeps that switch in a
hidden system API (`TetheringManager.startTethering`, reached through LSPosed's
hiddenapibypass) and allows it for an app with "Modify system settings"
(`Settings.ACTION_MANAGE_WRITE_SETTINGS` for RevvCarPlay's package) when the carrier asks for no
tethering check. The hotspot keeps the name and password set in the head unit's settings, so the
details saved with SET_HOTSPOT must match them. RevvCarPlay never turns the hotspot off.

## Steps

1. Package visibility (Android 11+):

   ```xml
   <queries>
       <intent>
           <action android:name="com.vivekkaushik.revvcarplay.action.EMBED_CARPLAY" />
       </intent>
   </queries>
   ```

2. Find and bind the service:

   ```kotlin
   val intent = Intent("com.vivekkaushik.revvcarplay.action.EMBED_CARPLAY")
   val info = packageManager.queryIntentServices(intent, 0).firstOrNull()?.serviceInfo
       ?: return // RevvCarPlay is not installed
   intent.setClassName(info.packageName, info.name)
   bindService(intent, connection, Context.BIND_AUTO_CREATE)
   ```

3. Once the service is connected and the `SurfaceView` has its surface and size, send ATTACH:

   ```kotlin
   service.send(Message.obtain(null, 1 /* ATTACH */).apply {
       data = Bundle().apply {
           putBinder("hostToken", surfaceView.hostToken)
           putInt("displayId", surfaceView.display.displayId)
           putInt("width", surfaceView.width)
           putInt("height", surfaceView.height)
           putInt("screenWidth", resources.displayMetrics.widthPixels)
           putInt("screenHeight", resources.displayMetrics.heightPixels)
       }
       replyTo = myMessenger
   })
   ```

4. On ATTACHED, `surfaceView.setChildSurfacePackage(package)`. Send RESIZE from
   `surfaceChanged`, DETACH from `surfaceDestroyed`, and unbind when your screen goes away.
5. Forward touches: `surfaceView.setOnTouchListener { v, e -> send TOUCH with e, v.width, v.height; true }`.

Revv's implementation is `CarPlayCompanion` (client) and `CarPlayScreen` (view) in the Revv app.
The companion side is `CarPlayEmbedService`, `EmbeddedCarPlayView` and `EmbeddedCarPlay` under
`common/src/main/java/com/shilapi/xcertplay/embed/`.

## Behaviour notes

- The CarPlay session is sized to the host view. Rotating the head unit or resizing the view
  reconnects CarPlay (a few seconds), as changing the display size does in the full-screen app.
- While the host shows another screen (DETACH), the session, music and navigation audio continue.
  The connection notification opens the host app; its Disconnect action ends the session.
- The dashboard-map embed (`EMBED_MAP`, see LAUNCHER_INTEGRATION.md) still works alongside, when
  the cluster map is enabled in the companion.
