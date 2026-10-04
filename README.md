# RevvCarPlay

**The CarPlay engine for [Revv](https://github.com/iamvivekkaushik/Revv).** A separate, GPL-3.0 companion
app that runs wired or wireless CarPlay on an Android head unit and shows it *inside* Revv's Auto
screen, in Revv's own layout. It also works on its own, full screen, with DiPlay's familiar UI.
Package `com.vivekkaushik.revvcarplay`.

Fork of [DiPlay](https://github.com/shihabal3amri/DiPlay) (itself based on
[xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0; home/settings UI from
[DiAuto](https://github.com/shihabal3amri/DiAuto), AGPL-3.0). All of DiPlay's receiver, wireless
and BYD features are still here; BYD-specific outputs stay gated to BYD firmware as upstream.

> **Not an Apple-certified product.** CarPlay requires an Apple accessory identity. **This app ships
> none** and the repository contains none. You import your own `identity.pk8` + `certificate.p7b`
> in Revv under Settings › CarPlay; without them CarPlay does not start. See
> [Identity](#identity) below. CarPlay and its icon belong to Apple Inc.; no Apple affiliation.

## What it adds to DiPlay

- **Embedded CarPlay for a host app.** A bound service hands the live CarPlay screen (video +
  touch) to another app's `SurfaceView` through `SurfaceControlViewHost`, sized to that view. The
  USB/Wi-Fi link, decoder, audio, microphone and foreground service stay in this process; the
  session keeps running while the host shows another screen. Android 11+.
  Protocol and host-side steps: [docs/REVV_INTEGRATION.md](docs/REVV_INTEGRATION.md).
- **Settings live in Revv.** Identity, link and car hotspot details, iPhone, CarPlay size,
  resolution, frame rate, video codec, audio routing, location and the diagnostic report are set in
  Revv's Settings › CarPlay, over the same service; only an app signed with this app's certificate
  may change them. This app's own settings screen keeps what concerns its full-screen window, its
  start-up, BYD head units and its language.
- **Car hotspot switch.** With the car hotspot link, this app turns the head unit's Wi-Fi hotspot
  on when the link is chosen and before every connection over it, once "Modify system settings"
  is allowed for it (Revv's Settings › CarPlay links there). Android 11+; it uses a hidden system
  API through [hiddenapibypass](https://github.com/LSPosed/AndroidHiddenApiBypass) (Apache-2.0).
- **Identity import.** In Revv's Settings › CarPlay: pick the two files from the head unit's
  storage; they are validated (P-256 key matching the certificate) and kept in this app's private
  storage. Remove them there too.
- No bundled identity, no website, Revv branding. Everything else is upstream DiPlay 0.2.10.

## Using it with Revv

1. Install this APK on the head unit (Android 9+ for the app, 11+ for the embedded view).
2. In Revv's Settings › CarPlay: import the identity files, pick the link (and for the car
   hotspot, its name and password) and, for wireless, your iPhone. Then open RevvCarPlay once to
   accept the VPN prompt for USB, or allow Bluetooth / nearby devices for wireless (Revv's
   **FINISH SETUP** opens it when needed). Revv and RevvCarPlay must be signed with the same key.
3. Open Revv → **AUTO**. CarPlay appears in the left panel; status, Siri and Disconnect on the
   right. Leaving the Auto screen keeps the session (music continues); the notification's
   Disconnect ends it.

Run one projection app at a time. Opening RevvCarPlay's own full-screen CarPlay while Revv shows
it takes the session over; attaching from Revv ends a full-screen session.

## Identity

The iPhone only starts CarPlay for an accessory that proves an Apple-issued identity. Real head
units hold it in an MFi coprocessor; this app instead signs with a key file ("offline MFi",
`LocalMfiAuthenticationClient`). Alternatives in the code: an I2C coprocessor on the head unit
(`MfiTarget.I2C`), a CH341 USB bridge to one, or a remote signing server (`MfiTarget.REMOTE`).

Where you get the files is your responsibility and your legal exposure. Nothing here downloads,
extracts or ships them.

## Build

Same as upstream ([docs/BUILD.md](docs/BUILD.md)): JDK 25 (Gradle toolchain), compileSdk 37, NDK
28.2. `./gradlew :mobile:assembleDebug` produces an identity-free APK unless you keep your own identity
locally:

- **Debug builds** bundle `.private/auth/offline-mfi/identity.pk8` and `certificate.p7b` when that
  folder exists. It is gitignored; never commit it.
- **Release builds** never bundle it implicitly, because anyone with the APK can extract the key. To
  bundle one deliberately, point `DIPLAY_AUTH_ASSETS_DIR` at a folder containing `offline-mfi/`.
- Without either, import the files on the head unit in Revv's Settings › CarPlay.

CI rejects any credential file in the tree.

### Signed release APK

The release build always signs with `ANDROID_KEYSTORE_*` and fails without them. It ships no accessory identity; import yours in Revv's Settings › CarPlay after installing.

Sign it with **Revv's release keystore**. Revv lets only an app signed with its own certificate change RevvCarPlay's settings and read its route guidance; with any other key, CarPlay still shows in Revv but Settings › CarPlay says it can't reach the companion's settings.

1. Build:
   ```bash
   JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_KEYSTORE_PATH=~/revv-release.jks ANDROID_KEYSTORE_PASSWORD='…' ANDROID_KEY_ALIAS='…' ANDROID_KEY_PASSWORD='…' ./gradlew :mobile:assembleRelease
   ```
   Output: `mobile/build/outputs/apk/release/mobile-release.apk`.
2. Check the signature:
   ```bash
   ~/Library/Android/sdk/build-tools/36.0.0/apksigner verify --print-certs mobile/build/outputs/apk/release/mobile-release.apk
   ```

### Releases

Pushing a tag such as `v0.2.0` runs [.github/workflows/release.yml](.github/workflows/release.yml): the unit
tests, then the release APK signed with Revv's keystore and versioned from the tag (`v1.2.3` is 1.2.3, code
1002003), checked for credential files and published as a GitHub release with its SHA-256. A tag with a suffix
(`v0.2.0-beta.1`) is a pre-release. It needs the Revv repository's four secrets added here too:
`REVV_KEYSTORE_BASE64` (the keystore, `base64 -i revv-release.jks`), `REVV_KEYSTORE_PASSWORD`, `REVV_KEY_ALIAS` and
`REVV_KEY_PASSWORD`.

## Documentation

Upstream docs under [docs/](docs/) still apply (install, compatibility, privacy, BYD navigation,
launcher map embedding, validation, testing). New: [REVV_INTEGRATION.md](docs/REVV_INTEGRATION.md).

## License

GPL-3.0 (see [LICENSE](LICENSE)); the DiAuto-derived UI is AGPL-3.0
([docs/licenses](docs/licenses)). Preserve these notices when distributing modifications.
Credits: [docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md).
