package com.tonix.wifireader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.pow

data class ApPin(val ssid: String, val point: GeoPoint, val approximate: Boolean, val distanceMeters: Double, val level: Int)

class PulseOverlay(private val pins: () -> List<ApPin>) : Overlay() {
    private var phase = 0f
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FFC6"); textSize = 26f; isFakeBoldText = true
    }
    fun tick() { phase = (phase + 0.02f) % 1f }

    override fun draw(c: Canvas, proj: Projection) {
        for (pin in pins()) {
            val p = proj.toPixels(pin.point, null)
            val rangePx = proj.metersToEquatorPixels(pin.distanceMeters.toFloat())
            fill.color = Color.parseColor("#1500E5FF")
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), rangePx, fill)
            val base = Color.parseColor(if (pin.approximate) "#FFC400" else "#00E5FF")
            ring.color = base; ring.alpha = (255 * (1 - phase)).toInt()
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 14f + 40f * phase, ring)
            dot.color = base; dot.style = Paint.Style.FILL; dot.alpha = 255
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 10f, dot)
            dot.color = Color.WHITE; dot.style = Paint.Style.STROKE; dot.strokeWidth = 2f; dot.alpha = 200
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 10f, dot)
            c.drawText("${pin.ssid}  ${pin.level} dBm", p.x + 18f, p.y + 8f, label)
        }
    }
}

class SelfPulseOverlay(private val point: () -> GeoPoint?) : Overlay() {
    private var phase = 0f
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.parseColor("#00FFC6") }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    fun tick() { phase = (phase + 0.03f) % 1f }
    override fun draw(c: Canvas, proj: Projection) {
        val gp = point() ?: return
        val p = proj.toPixels(gp, null)
        ring.alpha = (255 * (1 - phase)).toInt()
        c.drawCircle(p.x.toFloat(), p.y.toFloat(), 16f + 50f * phase, ring)
        core.shader = RadialGradient(p.x.toFloat(), p.y.toFloat(), 22f, Color.WHITE, Color.parseColor("#0000E5FF"), Shader.TileMode.CLAMP)
        c.drawCircle(p.x.toFloat(), p.y.toFloat(), 22f, core); core.shader = null
        core.color = Color.parseColor("#00E5FF")
        c.drawCircle(p.x.toFloat(), p.y.toFloat(), 10f, core)
        core.color = Color.WHITE
        c.drawCircle(p.x.toFloat(), p.y.toFloat(), 4f, core)
    }
}

/**
 * Live map: your position follows GPS continuously, and EVERY nearby Wi-Fi device is tracked live
 * (new scan every few seconds). A network's pin is estimated from real (GPS position, signal) samples
 * collected while you move - walk around and the pins sharpen. No random/fake positions.
 */
@Suppress("DEPRECATION")
class MapScreen(ctx: Context, myLocation: GeoPoint, networks: List<ScanResult>) : FrameLayout(ctx) {
    private class Track(var ssid: String) {
        var level = -100
        var seen = 0L
        val samples = ArrayList<Pair<GeoPoint, Double>>()
    }

    private val map = MapView(ctx)
    private val tracks = LinkedHashMap<String, Track>()
    private val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val locMgr = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var selfPoint: GeoPoint? = myLocation
    private val pulse = PulseOverlay { buildPins() }
    private val selfPulse = SelfPulseOverlay { selfPoint }
    private val note: TextView
    private val followBtn: TextView
    private var follow = true
    private var running = false
    private var receiver: BroadcastReceiver? = null

    private val listener = LocationListener { loc: Location ->
        selfPoint = GeoPoint(loc.latitude, loc.longitude)
        if (follow) map.controller.animateTo(selfPoint)
    }

    private val pulseLoop = object : Runnable {
        override fun run() {
            if (!running) return
            pulse.tick(); selfPulse.tick(); map.invalidate()
            handler.postDelayed(this, 40)
        }
    }
    private val scanLoop = object : Runnable {
        override fun run() {
            if (!running) return
            try { wm.startScan() } catch (e: Exception) { }
            handler.postDelayed(this, 4000)
        }
    }

    init {
        Configuration.getInstance().userAgentValue = ctx.packageName
        val darkTiles = XYTileSource("TonixDark", 0, 19, 256, ".png",
            arrayOf("https://a.basemaps.cartocdn.com/dark_all/", "https://b.basemaps.cartocdn.com/dark_all/", "https://c.basemaps.cartocdn.com/dark_all/"))
        map.setTileSource(darkTiles)
        map.setMultiTouchControls(true)
        map.controller.setZoom(18.0)
        map.controller.setCenter(myLocation)
        map.overlays.add(pulse)
        map.overlays.add(selfPulse)
        map.setOnTouchListener { _, ev -> if (ev.action == MotionEvent.ACTION_DOWN) setFollow(false); false }
        addView(map, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        note = TextView(ctx).apply {
            setTextColor(Color.parseColor("#B2EBF2")); textSize = 11f; setPadding(20, 14, 20, 14)
            setBackgroundColor(Color.parseColor("#CC05070D"))
        }
        addView(note, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        followBtn = TextView(ctx).apply {
            textSize = 12f; setTextColor(Color.parseColor("#00E5FF")); setPadding(28, 16, 28, 16)
            setBackgroundColor(Color.parseColor("#CC05070D"))
            setOnClickListener { setFollow(true); selfPoint?.let { map.controller.animateTo(it) } }
        }
        addView(followBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, 16, 16, 0) })
        setFollow(true)

        absorb(networks)
        onResumeMap()
    }

    private fun setFollow(on: Boolean) {
        follow = on
        followBtn.text = if (on) "◎ FOLLOWING YOU" else "◌ TAP TO FOLLOW"
    }

    /** add one real sample (my GPS position + the signal I measured there) for each network */
    private fun absorb(list: List<ScanResult>) {
        val now = SystemClock.elapsedRealtime()
        val me = selfPoint
        for (r in list) {
            val t = tracks.getOrPut(r.BSSID) { Track(r.SSID) }
            t.ssid = if (r.SSID.isNullOrEmpty()) "<hidden>" else r.SSID
            t.level = r.level; t.seen = now
            if (me != null) {
                t.samples.add(Pair(GeoPoint(me.latitude, me.longitude), 10.0.pow(r.level / 10.0)))
                if (t.samples.size > 40) t.samples.removeAt(0)
            }
        }
        val it = tracks.entries.iterator()
        while (it.hasNext()) if (now - it.next().value.seen > 30000) it.remove()
        note.text = "● You: live GPS   ● ${tracks.size} Wi-Fi devices live (rescans every 4s)\n" +
            "Pins refine as you walk. Yellow = rough, blue = refined. Ring = signal range."
    }

    private fun buildPins(): List<ApPin> {
        val me = selfPoint
        val out = ArrayList<ApPin>(tracks.size)
        for (t in tracks.values) {
            var lat = 0.0; var lon = 0.0; var wsum = 0.0
            for ((p, w) in t.samples) { lat += p.latitude * w; lon += p.longitude * w; wsum += w }
            val pt = if (wsum > 0) GeoPoint(lat / wsum, lon / wsum) else me ?: continue
            out.add(ApPin(t.ssid, pt, t.samples.size < 4, SignalMath.estimateDistanceMeters(t.level), t.level))
        }
        return out
    }

    private fun startSelfUpdates() {
        try {
            val last = locMgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locMgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: locMgr.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            if (last != null) { selfPoint = GeoPoint(last.latitude, last.longitude); if (follow) map.controller.animateTo(selfPoint) }
        } catch (e: SecurityException) { }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try { locMgr.requestLocationUpdates(p, 1000L, 0f, listener) } catch (e: Exception) { }
        }
    }

    fun onResumeMap() {
        if (running) return
        running = true
        map.onResume()
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                try { absorb(wm.scanResults) } catch (e: Exception) { }
            }
        }
        try { context.applicationContext.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) } catch (e: Exception) { }
        startSelfUpdates()
        handler.post(pulseLoop); handler.post(scanLoop)
    }

    fun onPauseMap() {
        running = false
        handler.removeCallbacksAndMessages(null)
        map.onPause()
        try { locMgr.removeUpdates(listener) } catch (e: Exception) { }
        try { receiver?.let { context.applicationContext.unregisterReceiver(it) } } catch (e: Exception) { }
        receiver = null
    }
}
