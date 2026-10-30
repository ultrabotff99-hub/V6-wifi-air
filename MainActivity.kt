package com.tonix.wifireader

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import org.osmdroid.util.GeoPoint
import kotlin.math.abs

class MainActivity : Activity() {
    private lateinit var radar: RadarView
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var content: FrameLayout
    private val h = Handler(Looper.getMainLooper())
    private var lastResults: List<ScanResult> = emptyList()
    private var mapScreen: MapScreen? = null
    private var trackerScreen: TrackerScreen? = null
    private var arScreen: ARScreen? = null
    private var speedScreen: SpeedMeterScreen? = null
    private var pendingArTarget: ScanResult? = null

    private fun tv(t: String, s: Float, c: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = s; setTextColor(c); typeface = Typeface.MONOSPACE
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun security(r: ScanResult): String {
        val c = r.capabilities
        return when {
            c.contains("WPA3") -> "WPA3"
            c.contains("WPA2") -> "WPA2"
            c.contains("WPA") -> "WPA"
            c.contains("WEP") -> "WEP"
            else -> "OPEN"
        }
    }

    private fun borderColor(level: Int): Int = when {
        level >= -55 -> Color.parseColor("#FFFFFF")
        level >= -75 -> Color.parseColor("#00E676")
        else -> Color.parseColor("#FF3B30")
    }

    @Suppress("DEPRECATION")
    private fun realSpeedLine(r: ScanResult): String {
        val info = try { (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo } catch (e: Exception) { null }
        return if (info != null && info.bssid.equals(r.BSSID, true)) "${info.linkSpeed} Mbps link (connected, real)"
        else "connect to this Wi-Fi to measure real Mbps"
    }

    private fun showDetails(r: ScanResult) {
        @Suppress("DEPRECATION") val name = r.SSID.ifEmpty { "<hidden network>" }
        val ch = if (r.frequency < 3000) (r.frequency - 2407) / 5 else (r.frequency - 5000) / 5
        val msg = """
            SSID: $name
            BSSID: ${r.BSSID}
            Security: ${security(r)}
            Signal: ${r.level} dBm
            Channel: $ch
            Frequency: ${r.frequency} MHz
            Speed: ${realSpeedLine(r)}
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("Network Details")
            .setMessage(msg)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showMenu() {
        AlertDialog.Builder(this)
            .setTitle("Menu")
            .setItems(arrayOf("Home", "Map", "Speed Meter", "About")) { _, which ->
                when (which) {
                    1 -> showMap()
                    2 -> showSpeed()
                    3 -> AlertDialog.Builder(this)
                        .setTitle("About")
                        .setMessage("TONIX WiFi Reader\nDeveloper: Tonix\n\nReal-time WiFi scanner: live AR camera view, hot/cold beep tracker and live map. No simulated values.")
                        .setPositiveButton("OK", null)
                        .show()
                    else -> showHome()
                }
            }.show()
    }

    override fun onPause() {
        super.onPause()
        mapScreen?.onPauseMap()
        arScreen?.pause()
        trackerScreen?.pause()
        speedScreen?.pause()
    }

    override fun onResume() {
        super.onResume()
        mapScreen?.onResumeMap()
        arScreen?.resume()
        trackerScreen?.resume()
        speedScreen?.resume()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopActiveScreens()
    }

    private fun stopActiveScreens() {
        mapScreen?.onPauseMap(); mapScreen = null
        trackerScreen?.stop(); trackerScreen = null
        arScreen?.stop(); arScreen = null
        speedScreen?.stop(); speedScreen = null
    }

    private fun showHome() { stopActiveScreens(); content.removeAllViews(); content.addView(buildHome()) }

    private fun deviceLocation(): GeoPoint {
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null) GeoPoint(loc.latitude, loc.longitude) else GeoPoint(20.5937, 78.9629)
        } catch (e: SecurityException) { GeoPoint(20.5937, 78.9629) }
    }

    private fun showMap() {
        stopActiveScreens()
        content.removeAllViews()
        val m = MapScreen(this, deviceLocation(), lastResults)
        mapScreen = m
        content.addView(m)
    }

    private fun showSpeed() {
        stopActiveScreens()
        content.removeAllViews()
        val sp = SpeedMeterScreen(this)
        speedScreen = sp
        content.addView(sp)
    }

    private fun showTracker(r: ScanResult) {
        stopActiveScreens()
        content.removeAllViews()
        @Suppress("DEPRECATION") val ssid = r.SSID.ifEmpty { "<hidden>" }
        val t = TrackerScreen(this, r.BSSID, ssid)
        trackerScreen = t
        content.addView(t)
    }

    private fun showAr(r: ScanResult) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingArTarget = r
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 2)
            return
        }
        stopActiveScreens()
        content.removeAllViews()
        @Suppress("DEPRECATION") val ssid = r.SSID.ifEmpty { "<hidden>" }
        val a = ARScreen(this, r.BSSID, ssid)
        arScreen = a
        content.addView(a)
    }

    private fun buildHome(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        radar = RadarView(this)
        root.addView(radar, LinearLayout.LayoutParams(-1, 0, 1.1f))
        status = tv("READY", 12f, Color.parseColor("#00FFC6")).apply { gravity = Gravity.CENTER }
        root.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 16, 0, 0) }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply { text = "SCAN"; setOnClickListener { scan() } }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply { text = "⚡ SPEED METER"; setOnClickListener { showSpeed() } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)
        if (lastResults.isNotEmpty()) render(lastResults) else status.text = "READY"
        return root
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#05070D")); setPadding(40, 90, 40, 30)
        }
        val topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topBar.addView(tv("☰", 20f, Color.parseColor("#00E5FF")).apply {
            setPadding(0, 0, 24, 0); setOnClickListener { showMenu() }
        })
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(tv("TONIX", 30f, Color.parseColor("#00E5FF"), true).apply { gravity = Gravity.CENTER; letterSpacing = 0.4f })
        titleCol.addView(tv("WIFI SCANNING ANIMATION", 12f, Color.parseColor("#7FDBFF")).apply { gravity = Gravity.CENTER; letterSpacing = 0.25f })
        topBar.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        outer.addView(topBar)
        content = FrameLayout(this)
        outer.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(outer)
        showHome()
        scan()
    }

    private fun scan() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES), 1); return
        }
        status.text = "SCANNING..."
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION") wm.startScan()
        h.postDelayed({ val res = try { wm.scanResults } catch (e: SecurityException) { emptyList() }; lastResults = res; render(res) }, 2500)
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        if (r == 2) {
            val target = pendingArTarget; pendingArTarget = null
            if (g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED && target != null) showAr(target)
            return
        }
        if (g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED) scan() else status.text = "LOCATION PERMISSION NEEDED"
    }

    private fun showItemOptions(r: ScanResult) {
        @Suppress("DEPRECATION") val ssid = r.SSID.ifEmpty { "<hidden>" }
        AlertDialog.Builder(this)
            .setTitle(ssid)
            .setItems(arrayOf("Details", "Target & Track (beep)", "AR Live View")) { _, which ->
                when (which) {
                    0 -> showDetails(r)
                    1 -> showTracker(r)
                    else -> showAr(r)
                }
            }.show()
    }

    private fun render(res: List<ScanResult>) {
        status.text = if (res.isEmpty()) "NO NETWORKS - turn ON Location (GPS) and allow permission" else "${res.size} NETWORKS FOUND"
        radar.blips = res.map {
            @Suppress("DEPRECATION") val initial = it.SSID.ifEmpty { "?" }.take(1).uppercase()
            val tier = when { it.level >= -55 -> 2; it.level >= -75 -> 1; else -> 0 }
            Blip((abs(it.BSSID.hashCode()) % 360).toFloat(), (1f - (it.level + 100) / 70f).coerceIn(0.12f, 0.95f), initial, tier)
        }
        list.removeAllViews()
        for (r in res.sortedByDescending { it.level }) {
            @Suppress("DEPRECATION") val name = r.SSID.ifEmpty { "<hidden>" }
            val ch = if (r.frequency < 3000) (r.frequency - 2407) / 5 else (r.frequency - 5000) / 5
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(20, 16, 20, 16)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0D1420")); cornerRadius = 14f
                    setStroke(3, borderColor(r.level))
                }
                val lp = LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 0, 0, 12); layoutParams = lp
                setOnClickListener { showItemOptions(r) }
            }
            item.addView(tv(name, 13f, Color.parseColor("#E0FFFA"), true))
            item.addView(tv("${security(r)}  |  ${r.level} dBm  |  CH $ch", 11f, Color.parseColor("#80DEEA")))
            list.addView(item)
        }
    }
}
