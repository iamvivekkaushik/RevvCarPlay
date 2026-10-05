# DiPlay 0.2.10 — 2026-10-03

## RevvCarPlay 0.1.4

- When another Wi-Fi Direct connection holds the radio, such as a phone left connected to a TV for screen mirroring, embedded CarPlay names the device on its other end instead of saying a previous connection "needs a reset", and tells the host it can be reset (STATE `resetWifiDirect`). The host's new RESET_WIFI_DIRECT (protocol version 4) ends it, waits until it's gone and connects. RevvCarPlay still never ends another app's connection by itself.

## RevvCarPlay 0.1.3

- The connection service no longer crashes when Android refuses its microphone type. On Android 16 (seen on a Galaxy S23) a session started by Revv may not hold microphone or location in the background even with both permissions granted, so it carries on without them.
- When the carrier requires a tethering check, which only system apps may skip, RevvCarPlay asks the driver to turn the hotspot on in the head unit's settings instead of asking for "Modify system settings" again.
- The car hotspot link finds Samsung's hotspot (`swlan0`) and skips Wi-Fi calling and mobile data tunnels. The iPhone was sent a Wi-Fi calling tunnel's address instead and waited at "Wireless CarPlay control running".

## RevvCarPlay 0.1.2

- CarPlay's car button shows Revv's icon and the label "Revv" instead of BYD's, and the iPhone is told the car is a Revv (manufacturer and model) instead of DiPlay. A label, icon, manufacturer or model saved in the full-screen app's settings still wins. The iPhone may list the head unit as a new car once.
- The navigation widget shows RevvCarPlay rather than DiPlay while CarPlay isn't connected.

## RevvCarPlay 0.1.1

- Host apps signed with a certificate listed in `revvcarplay.trustedHostCertificates` (gradle.properties) may use the host settings and route guidance too, e.g. Revv as Google Play signs it when Play uses its own app signing key. Android checks them with `hasSigningCertificate`, across key rotation; a malformed fingerprint fails the build.

## RevvCarPlay 0.1.0 (fork of DiPlay 0.2.10)

- Embedded CarPlay for a host app: `CarPlayEmbedService` (`com.vivekkaushik.revvcarplay.action.EMBED_CARPLAY`) hands the live CarPlay screen, touch included, to a host's SurfaceView through SurfaceControlViewHost; `EmbeddedCarPlay` runs the session without a window and keeps it alive while the host shows another screen. Android 11+. See docs/REVV_INTEGRATION.md.
- Identity import: Settings → CarPlay identity imports and removes `identity.pk8` + `certificate.p7b`. The app ships no identity; a missing one is reported as such.
- Renamed to RevvCarPlay (`com.vivekkaushik.revvcarplay`); the connection notification opens the host app while CarPlay is embedded. DiPlay's website and site generator removed.
- Settings for the host (embed protocol 2): Revv reads and changes the identity, link, car hotspot, iPhone, display, audio and location settings and saves diagnostic reports, accepted only from an app signed like RevvCarPlay. The companion's own screen keeps window, startup and permission settings.
- Route guidance for the host (embed protocol 3): the destination's name, the distance and time left, the arrival time and the next maneuver from CarPlay's iAP2 route guidance, sent to signed hosts. CarPlay sends no coordinates for the destination.
- With "Modify system settings" granted, choosing the car hotspot link turns the head unit's Wi-Fi hotspot on (Android 11+, where the firmware allows it).
- Diagnostic reports are saved to Downloads/Revv/CarPlay.
- A launcher icon in Revv's style with a COMPANION badge; debug builds are no longer named "DiPlay HUD Test".
- Signed releases are built and published from version tags (`.github/workflows/release.yml`), with Revv's signing key.

- Publish CarPlay song metadata, position and artwork to Android media sessions; bound artwork queues and reject stale work across sessions (#82).
- Preserve normal USBMUX frames while handling narrowly validated handshake padding (#114); let USB connect without saved wireless-hotspot credentials (#130).
- Handle unknown reported Wi-Fi Direct security types, retry busy channels and allow bounded 5 GHz fallback (#121).
- Select an available AirPlay port and advertise it over Bonjour and wired/wireless iAP2; close sockets on failed setup/notification (#143).
- Enable available platform echo cancellation and noise suppression for calls, restoring the previous mode afterward (#116).
- Detect BYD CAN/CANFD battery protocols and clear unsupported/stale readings (#123).
- Add a saved show/hide setting for the home-screen dashboard-map mirror (#133).
- Improve optional parked video with seeking and ten-second skip controls; validate media URLs and redirects (#129).
- Extend Ukrainian translations, including the new map-mirror setting (#128 and release localization).
- Add bounded anonymous Bluetooth/USB/boot and microphone capture/encode/send diagnostics to exported reports; omit audio and packet contents.

See [0.2.10 release notes](docs/RELEASE-NOTES-0.2.10.md) for contributor credits, requirements and validation limits. Android 9 remains the minimum supported version.

# DiPlay 0.2.9 — 2026-10-02

- Follow BYD head-unit day/night changes while CarPlay is visible, including firmware that does not reliably deliver Android configuration callbacks.
- Restore media and navigation audio stream selection to 0–20 and inherit older saved navigation settings when no new selection exists. Vendor-specific outputs depend on head-unit support.
- Keep CarPlay connected through normal surround-view window changes, preserving video proportions and touch alignment. A connection started in a narrow camera window reconnects once when the window grows to restore the full-screen canvas.
- Add Ukrainian to the app language picker, Android app-language settings, and website. Correct its audio help to describe streams 1–20.
- Add an optional CarPlay song title, artist, and play/pause display on the BYD instrument cluster, using the existing network ADB connection.
- Add a CarPlay navigation widget for launchers that host standard Android widgets, with the next turn, road, distance, arrival information, and song. Clear expired guidance and explicitly cleared song titles.
- Add an optional floating copy of the dashboard map on the centre screen, with drag, pinch-to-resize, and tap-to-open controls. Requires permission to draw over other apps; Usage Access restricts it to home screens.
- Fix floating-map resizing on head units that ignore small pinch gestures.
- Let compatible launchers embed the live dashboard map on Android 11 and newer. Sharing is off by default; turning it off closes existing shared map views.
- Add map-host and DiPlay Home sample apps for developers. DiPlay Home combines the live map, standard Android widgets, a clock, and an app list; sample builds, lint, and Home back-navigation tests are checked in CI.
- Leave the GPS course empty when its direction is unknown, instead of reporting north. Valid GPS directions are preserved.
- Thanks to @lpcheng1208 for PRs [#71](https://github.com/shihabal3amri/DiPlay/pull/71), [#88](https://github.com/shihabal3amri/DiPlay/pull/88), and [#89](https://github.com/shihabal3amri/DiPlay/pull/89).
- Thanks to @romanchukg-cloud for PRs [#93](https://github.com/shihabal3amri/DiPlay/pull/93), [#101](https://github.com/shihabal3amri/DiPlay/pull/101), [#105](https://github.com/shihabal3amri/DiPlay/pull/105), [#106](https://github.com/shihabal3amri/DiPlay/pull/106), [#107](https://github.com/shihabal3amri/DiPlay/pull/107), [#108](https://github.com/shihabal3amri/DiPlay/pull/108), and [#109](https://github.com/shihabal3amri/DiPlay/pull/109).

See [0.2.9 release notes](docs/RELEASE-NOTES-0.2.9.md) for the merged changes and validation limits.

# DiPlay 0.2.8 — 2026-09-30

- Keep iPhone location reporting active across the wireless Bluetooth-to-Wi-Fi CarPlay handoff; limit location updates to one per second on wireless and USB.
- Add optional ADB wheel-speed and gear reporting for iPhone dead reckoning when GPS is unavailable. Tunnel use has not yet been verified.
- Add optional iOS 27 video playback on the car screen while parked, with iPhone, touchscreen and steering-wheel controls; close playback when leaving P.
- Explain unsupported DRM-protected video such as Apple TV+, which requires a licensed FairPlay receiver.
- Improve playback error reporting and preserve CarPlay when the head unit cannot play a video.

# DiPlay 0.2.7 — 2026-09-29

- App interface in English, Simplified Chinese, Arabic, Russian and Spanish; synchronized Android app-language settings.
- Steering-wheel media controls and long-press Siri on supported BYD firmware while CarPlay is on screen.
- Dashboard display choices: map, turn card, or both; corrected dashboard keyframe recovery.
- Optional ADB feature on supported DiLink 5.0: pause the dashboard map stream when its display mode hides the map.
- Optional ADB battery reporting for Apple Maps, with warning threshold, charging-connector selection and a checked reconnect action.
- Audio playback reliability fixes and clearer dashboard settings.
- Clarify the BYD-only support scope on the README and all five website editions.

# 0.2.0 — BYD navigation and connection improvements

- Standalone windshield HUD arrows, distance and street names on the verified DiLink5.1 firmware; no ADB, root or computer helper.
- Retain contributor cluster/SOME-IP navigation, route parsing, BYD CarPlay icon and display-size presets.
- Fix Car hotspot startup by using scoped IPv6 when available and binding discovery/probing to the AP interface. Physically confirmed on the development car.
- Drain asynchronously decoded audio during packet gaps and rebuild the music buffer after starvation. Wi-Fi Direct is much better in the user retest; occasional audio cutouts remain for a later version.
- Preserve bounded music-buffer choices, USB read improvements and decoder recovery; fix USB request/close races and keep vendor output outside phone callbacks.
- Save audio/video/receive timing and discovery diagnostics without road names or protocol payloads.
- HUD cleanup on normal end/disconnect/off/stale input; interrupted sessions recover on the next app launch. Force-stop may leave guidance visible until reopening.
- Thanks to @romanchukg-cloud and @georgiyrr for PR #3 and vehicle testing.

# 0.1.0 release restored — 2026-09-25

- Rebuilt and signed the APK locally with explicitly supplied runtime authentication assets.
- Restored release downloads; no app behavior or version-code change from 0.1.0.
- Accessory identity remains in the APK only. No credential files enter Git or the source archive.
- Retained generated test identities and public-source credential checks.
- Source/CI builds omit runtime identity assets by default; local packaging requires an explicit external directory.

# Source reset — 2026-09-25

- Withdrew the 0.1.0 APK and removed its release tag.
- Reset the public branch after preserving restricted local incident records.
- Removed static synthetic test private keys; generate test identities at runtime.
- Removed automatic private-asset packaging and disabled the old release build script.
- Added a build guard rejecting credential asset files.
- Replaced the download site with a five-language suspension notice.

The APK was subsequently rebuilt and restored as described above. Existing copies cannot be recalled by a Git history reset.
