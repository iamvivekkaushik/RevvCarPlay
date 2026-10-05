package com.shilapi.xcertplay.embed

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Ends the device's Wi-Fi Direct connection, whichever app made it, as RevvCarPlay's own wireless
 * recovery page does: Android runs one at a time, so screen mirroring to a TV keeps CarPlay from
 * making its group. Only on the driver's say-so. Main thread.
 */
internal object WifiDirectReset {
    private const val TAG = "RevvCarPlay-Embed"
    private const val GONE_WITHIN_MILLIS = 4_000L
    private const val POLL_MILLIS = 200L

    /** [done] says whether no group is left, on the main thread. */
    fun clear(context: Context, done: (Boolean) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val manager = context.getSystemService(WifiP2pManager::class.java) ?: return done(false)
        val channel = manager.initialize(context, Looper.getMainLooper(), null) ?: return done(false)
        fun finish(cleared: Boolean) {
            channel.close()
            done(cleared)
        }
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) return@requestGroupInfo finish(true)
                manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = SystemClock.elapsedRealtime() + GONE_WITHIN_MILLIS
                        fun check() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> finish(true)
                                    SystemClock.elapsedRealtime() >= deadline -> finish(false)
                                    else -> main.postDelayed(::check, POLL_MILLIS)
                                }
                            }
                        }
                        check()
                    }

                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "Wi-Fi Direct reset refused: $reason")
                        finish(false)
                    }
                })
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Wi-Fi Direct reset not allowed", error)
            finish(false)
        }
    }
}
