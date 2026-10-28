package com.netpulse.app.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Keeps the Wi-Fi radio out of power saving while a test runs.
 *
 * On Android 10+ this uses the low-latency lock, which is only active while the
 * screen is on and the app is in the foreground. Always call [release] when the
 * test stops or the app leaves the foreground.
 */
class WifiAwakeLock(context: Context) {

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(WifiManager::class.java)

    private var lock: WifiManager.WifiLock? = null

    val isHeld: Boolean
        get() = lock?.isHeld == true

    /** Returns true if the lock is held after this call. */
    fun acquire(): Boolean {
        if (isHeld) return true
        val manager = wifiManager ?: return false

        return try {
            val newLock = manager.createWifiLock(lockMode(), TAG)
            newLock.setReferenceCounted(false)
            newLock.acquire()
            lock = newLock
            true
        } catch (e: RuntimeException) {
            lock = null
            false
        }
    }

    fun release() {
        try {
            lock?.takeIf { it.isHeld }?.release()
        } catch (e: RuntimeException) {
            // Nothing useful to do: the lock is dropped either way.
        } finally {
            lock = null
        }
    }

    @Suppress("DEPRECATION")
    private fun lockMode(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }

private companion object {
        const val TAG = "NetPulse:WifiAwake"
    }
}
