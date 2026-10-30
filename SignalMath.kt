package com.tonix.wifireader

import kotlin.math.pow

/** Only distance + beep helpers. NO fake speed estimates anymore - Mbps comes from real measurement. */
object SignalMath {
    /** Rough distance from RSSI (path-loss model). Always shown with "~". */
    fun estimateDistanceMeters(rssi: Int): Double {
        val txPowerAt1m = -40.0
        val pathLossExponent = 2.7
        return 10.0.pow((txPowerAt1m - rssi) / (10 * pathLossExponent))
    }

    fun quality(rssi: Int): Int = ((rssi + 100) * 2).coerceIn(0, 100)

    /** Beep interval in ms: faster beeps as the live signal gets stronger. */
    fun beepIntervalMs(rssi: Int): Long {
        val frac = ((rssi + 95f) / 65f).coerceIn(0f, 1f)
        return (900 - frac * 820).toLong().coerceIn(80, 900)
    }

    /** Pitch also rises with signal strength: 500 Hz (weak) .. 1800 Hz (strong). */
    fun beepFreqHz(rssi: Int): Double {
        val frac = ((rssi + 95f) / 65f).coerceIn(0f, 1f)
        return 500.0 + frac * 1300.0
    }
}
