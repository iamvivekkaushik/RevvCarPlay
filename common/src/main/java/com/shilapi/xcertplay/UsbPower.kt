package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager

/**
 * How much current to offer a wired iPhone. A head unit runs off the car, and its port supplies
 * the 2.4 A CarPlay accessories offer. A phone hosting CarPlay runs off its own battery, and
 * Android cuts its port as soon as an iPhone starts charging from it (a Galaxy S23 reported
 * over-current 0.2 s after the offer, and the iPhone dropped off), so it offers none.
 */
internal object UsbPower {
    private const val HEAD_UNIT_MILLIAMPS = 2400

    fun availableCurrentMilliAmps(context: Context): Int = if (isPhone(context)) 0 else HEAD_UNIT_MILLIAMPS

    /** On its own battery, with an earpiece: head units have neither, or at most a battery they pretend to. */
    private fun isPhone(context: Context): Boolean {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (battery?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) != true) return false
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
    }
}
