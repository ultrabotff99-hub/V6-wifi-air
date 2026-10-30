package com.tonix.wifireader

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Finds WHERE a Wi-Fi router is, using only real data: every time you move >1.5 m we store
 * (your GPS position, the signal measured there). Router position = signal-weighted centre of those
 * samples (strongest signal pulls hardest), so it sharpens as you walk around it.
 */
object ApLocator {
    class Estimate(val lat: Double, val lon: Double, val spreadM: Double, val samples: Int) {
        /** need a few samples spread over >= 4 m, otherwise the direction would be a guess */
        val locked: Boolean get() = samples >= 4 && spreadM >= 4.0
    }

    private class Track {
        val lat = ArrayList<Double>(); val lon = ArrayList<Double>(); val w = ArrayList<Double>()
        var lastLat = 0.0; var lastLon = 0.0; var has = false
    }

    private val tracks = HashMap<String, Track>()

    fun distM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val n = (lat2 - lat1) * 110540.0
        val e = (lon2 - lon1) * 111320.0 * cos(Math.toRadians(lat1))
        return sqrt(n * n + e * e)
    }

    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val n = (lat2 - lat1) * 110540.0
        val e = (lon2 - lon1) * 111320.0 * cos(Math.toRadians(lat1))
        return (Math.toDegrees(atan2(e, n)) + 360.0) % 360.0
    }

    fun add(bssid: String, level: Int, lat: Double, lon: Double) {
        val t = tracks.getOrPut(bssid) { Track() }
        if (t.has && distM(t.lastLat, t.lastLon, lat, lon) < 1.5) return
        t.lat.add(lat); t.lon.add(lon); t.w.add(10.0.pow(level / 5.0))   // sharpened power weight
        if (t.lat.size > 60) { t.lat.removeAt(0); t.lon.removeAt(0); t.w.removeAt(0) }
        t.lastLat = lat; t.lastLon = lon; t.has = true
    }

    fun estimate(bssid: String): Estimate? {
        val t = tracks[bssid] ?: return null
        val n = t.lat.size
        if (n < 1) return null
        var sl = 0.0; var so = 0.0; var sw = 0.0
        var minLa = 999.0; var maxLa = -999.0; var minLo = 999.0; var maxLo = -999.0
        for (i in 0 until n) {
            sl += t.lat[i] * t.w[i]; so += t.lon[i] * t.w[i]; sw += t.w[i]
            minLa = min(minLa, t.lat[i]); maxLa = max(maxLa, t.lat[i])
            minLo = min(minLo, t.lon[i]); maxLo = max(maxLo, t.lon[i])
        }
        val spread = distM(minLa, minLo, maxLa, maxLo)
        return Estimate(sl / sw, so / sw, spread, n)
    }

    fun clear() = tracks.clear()
}
