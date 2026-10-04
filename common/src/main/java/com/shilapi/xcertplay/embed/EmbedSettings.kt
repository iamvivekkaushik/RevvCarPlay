package com.shilapi.xcertplay.embed

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.DiPlayBootstrap
import com.shilapi.xcertplay.DiPlayPreferences
import com.shilapi.xcertplay.airplay.CarPlaySize
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.MediaAudioBuffer
import com.shilapi.xcertplay.messageResource
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.network.HotspotSwitch
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.embed.CarPlayEmbedProtocol as P

/**
 * RevvCarPlay's settings as the host app (Revv) reads and changes them: the companion keeps no
 * settings screen of its own for these. Values live in [AirPlayPersistence] and [DiPlayPreferences]
 * as before, so the full-screen CarPlay screen uses them too.
 */
internal object EmbedSettings {
    private const val TAG = "RevvCarPlay-Settings"
    private val RESOLUTIONS = listOf(10, 8, 6)
    /** Picked identity files are a few hundred bytes; anything bigger is not one. */
    const val MAX_FILE_BYTES = 16 * 1024

    /** Every setting's current value, for MSG_SETTINGS. */
    fun snapshot(context: Context): Bundle = Bundle().apply {
        putInt(P.SETTING_CARPLAY_SIZE, CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(context)).widthMillimeters)
        putInt(P.SETTING_RESOLUTION, AirPlayPersistence.loadDisplayScaleTenths(context))
        putInt(P.SETTING_FRAME_RATE, if (AirPlayPersistence.loadFps(context) == 60) 60 else 30)
        putBoolean(P.SETTING_HEVC, AirPlayPersistence.loadHevcEnabled(context))
        putBoolean(P.SETTING_RIGHT_HAND_DRIVE, AirPlayPersistence.loadRightHandDrive(context))
        putBoolean(P.SETTING_AUDIO_FOCUS, AirPlayPersistence.loadAudioFocusEnabled(context))
        putInt(P.SETTING_MEDIA_STREAM, AirPlayPersistence.loadMediaAudioChannel(context))
        putInt(P.SETTING_NAVIGATION_STREAM, AirPlayPersistence.loadNavigationAudioChannel(context))
        putInt(P.SETTING_MUSIC_BUFFER, AirPlayPersistence.loadMediaBufferMillis(context))
        putBoolean(P.KEY_ADVANCED_AUDIO_AVAILABLE, advancedAudioAvailable(context))
        putBoolean(P.SETTING_ADVANCED_AUDIO, AirPlayPersistence.loadAdvancedAudioChannelMapping(context))
        putBoolean(P.SETTING_LOCATION_REPORTING, AirPlayPersistence.loadLocationReportingEnabled(context))
        putBoolean(P.KEY_LOCATION_PERMITTED, context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        putBoolean(P.KEY_IDENTITY_INSTALLED, DiPlayBootstrap.hasIdentity(context))
        putBoolean(P.KEY_WIRELESS, AirPlayPersistence.loadWirelessEnabled(context))
        putString(P.KEY_HOTSPOT_MODE, if (AirPlayPersistence.loadWirelessHotspotMode(context) == WirelessHotspotMode.MANUAL) P.HOTSPOT_MANUAL else P.HOTSPOT_P2P)
        val ssid = AirPlayPersistence.loadManualHotspotSsid(context)
        putString(P.KEY_HOTSPOT_SSID, ssid)
        putBoolean(P.KEY_HOTSPOT_READY, ManualHotspotValidation.error(ssid, AirPlayPersistence.loadManualHotspotPassphrase(context)) == null)
        CarHotspotStatus.isEnabled(context)?.let { putBoolean(P.KEY_HOTSPOT_ON, it) }
        putBoolean(P.KEY_HOTSPOT_SWITCH_ALLOWED, HotspotSwitch.allowed(context))
        putString(P.KEY_PHONE_ADDRESS, DiPlayPreferences.phoneAddress(context))
        putString(P.KEY_PHONE_NAME, DiPlayPreferences.phoneName(context))
    }

    /** Saves one setting (P.SETTING_*); false when the name or value is not one RevvCarPlay takes. */
    fun set(context: Context, name: String, value: Any?): Boolean {
        val number = value as? Int
        val on = value as? Boolean
        when (name) {
            P.SETTING_CARPLAY_SIZE -> AirPlayPersistence.saveWidthPhysicalMm(context, CarPlaySize.fromWidthMillimeters(number ?: return false).widthMillimeters)
            P.SETTING_RESOLUTION -> {
                if (number == null || number !in RESOLUTIONS) return false
                AirPlayPersistence.saveDisplayScaleTenths(context, number)
            }
            P.SETTING_FRAME_RATE -> AirPlayPersistence.saveFps(context, if ((number ?: return false) == 60) 60 else 30)
            P.SETTING_HEVC -> AirPlayPersistence.saveHevcEnabled(context, on ?: return false)
            P.SETTING_RIGHT_HAND_DRIVE -> AirPlayPersistence.saveRightHandDrive(context, on ?: return false)
            P.SETTING_AUDIO_FOCUS -> AirPlayPersistence.saveAudioFocusEnabled(context, on ?: return false)
            P.SETTING_MEDIA_STREAM -> {
                if (number == null || number !in AirPlayPersistence.AUDIO_CHANNELS) return false
                AirPlayPersistence.saveMediaAudioChannel(context, number)
            }
            P.SETTING_NAVIGATION_STREAM -> {
                if (number == null || number !in AirPlayPersistence.AUDIO_CHANNELS) return false
                AirPlayPersistence.saveNavigationAudioChannel(context, number)
            }
            P.SETTING_MUSIC_BUFFER -> {
                if (number == null || number !in MediaAudioBuffer.presets) return false
                AirPlayPersistence.saveMediaBufferMillis(context, number)
            }
            P.SETTING_ADVANCED_AUDIO -> {
                if (!advancedAudioAvailable(context)) return false
                AirPlayPersistence.saveAdvancedAudioChannelMapping(context, on ?: return false)
            }
            P.SETTING_LOCATION_REPORTING -> AirPlayPersistence.saveLocationReportingEnabled(context, on ?: return false)
            else -> return false
        }
        Log.i(TAG, "$name = $value")
        return true
    }

    /** Saves the car hotspot's details; the error to show when they are not usable, else null. */
    fun saveHotspot(context: Context, ssid: String, passphrase: String): String? {
        val name = ssid.trim()
        ManualHotspotValidation.error(name, passphrase)?.let { return context.getString(it.messageResource()) }
        AirPlayPersistence.saveManualHotspotSsid(context, name)
        AirPlayPersistence.saveManualHotspotPassphrase(context, passphrase)
        AirPlayPersistence.saveManualHotspotSecurity(context, ManualHotspotValidation.securityFor(passphrase))
        AirPlayPersistence.saveManualHotspotBand(context, ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(context, 0)
        Log.i(TAG, "car hotspot details saved")
        return null
    }

    fun savePhone(context: Context, address: String, name: String) {
        DiPlayPreferences.savePhone(context, address, name.ifBlank { "iPhone" })
    }

    /**
     * Installs the identity among [files] (name to bytes): identity.pk8 and certificate.p7b by name,
     * else the smaller file as the key. The message to show, and whether it worked.
     */
    fun importIdentity(context: Context, files: Bundle): Pair<String, Boolean> {
        val picked = files.keySet().mapNotNull { name -> files.getByteArray(name)?.takeIf { it.size <= MAX_FILE_BYTES }?.let { name to it } }
        var key = picked.firstOrNull { it.first.endsWith(".pk8", true) || it.first.endsWith(".key", true) }
        var cert = picked.firstOrNull { it.first.endsWith(".p7b", true) || it.first.endsWith(".cer", true) || it.first.endsWith(".der", true) }
        if ((key == null || cert == null) && picked.size >= 2) {
            val bySize = picked.sortedBy { it.second.size }
            if (key == null) key = bySize.first()
            if (cert == null) cert = bySize.last()
        }
        if (key == null || cert == null || key === cert) return context.getString(R.string.identity_pick_both) to false
        val chosen = mapOf("identity.pk8" to key.second, "certificate.p7b" to cert.second)
        val failure = runCatching { DiPlayBootstrap.import(context) { name -> chosen.getValue(name).inputStream() } }.exceptionOrNull()
        if (failure != null) {
            Log.e(TAG, "Identity import failed", failure)
            return context.getString(R.string.identity_import_failed) to false
        }
        Log.i(TAG, "identity imported")
        return context.getString(R.string.identity_imported) to true
    }

    /** What to tell the driver about turning the hotspot on, or null when it is on. */
    fun hotspotNotice(context: Context, result: HotspotSwitch.Result): String? = when (result) {
        HotspotSwitch.Result.ON, HotspotSwitch.Result.ALREADY_ON -> null
        HotspotSwitch.Result.NO_ACCESS -> context.getString(R.string.hotspot_switch_no_access)
        HotspotSwitch.Result.UNSUPPORTED -> context.getString(R.string.hotspot_switch_unsupported)
        HotspotSwitch.Result.FAILED -> context.getString(R.string.hotspot_switch_failed)
    }

    private fun advancedAudioAvailable(context: Context) =
        context.resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)
}
