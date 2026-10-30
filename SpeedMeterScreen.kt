package com.tonix.wifireader

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Real-time speed meter: live KB/s (download or upload), animated gauge, ping, signal, distance,
 * 60-second signal+distance graph, REAL speed-vs-distance chart, and "connect to another Wi-Fi & measure it".
 */
@Suppress("DEPRECATION")
class SpeedMeterScreen(ctx: Context) : LinearLayout(ctx) {
    private val engine = SpeedEngine(ctx)
    private val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val h = Handler(Looper.getMainLooper())
    private val meter = MeterView(ctx, engine)
    private val runBtn: TextView
    private var alive = true
    private var state = 0            // 0 idle, 1 download, 2 upload, 3 done
    private val testMs = 8000L
    private var phaseStart = 0L

    // app-only connection to another Wi-Fi
    private var specCb: ConnectivityManager.NetworkCallback? = null
    @Volatile private var specSsid: String? = null
    @Volatile private var specRssi: Int? = null
    @Volatile private var specFreq = 0
    @Volatile private var specLink = 0

    private fun btn(t: String, accent: Int, w: Float) = TextView(context).apply {
        text = t; textSize = 12f; setTextColor(accent); typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
        setPadding(8, 26, 8, 26)
        background = GradientDrawable().apply { setColor(Color.parseColor("#0D1420")); cornerRadius = 18f; setStroke(3, accent) }
        layoutParams = LayoutParams(0, -2, w).apply { setMargins(6, 0, 6, 0) }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#05070D"))
        addView(ScrollView(ctx).apply { addView(meter) }, LayoutParams(-1, 0, 1f))

        val bar = LinearLayout(ctx).apply { orientation = HORIZONTAL; setPadding(8, 10, 8, 6) }
        runBtn = btn("▶ START TEST", Color.parseColor("#00E676"), 1.3f).apply {
            setOnClickListener { if (state == 1 || state == 2) cancelTest() else startTest() }
        }
        val conn = btn("📶 CONNECT WI-FI", Color.parseColor("#00FFC6"), 1f).apply { setOnClickListener { pickNetwork() } }
        bar.addView(runBtn); bar.addView(conn)
        addView(bar, LayoutParams(-1, -2))

        h.post(object : Runnable { override fun run() { if (!alive) return; tick(); h.postDelayed(this, 500) } })
        h.post(object : Runnable { override fun run() { if (!alive) return; advance(); h.postDelayed(this, 100) } })
    }

    private fun setBtn(text: String, hex: String) {
        runBtn.text = text; runBtn.setTextColor(Color.parseColor(hex))
        runBtn.background = GradientDrawable().apply { setColor(Color.parseColor("#0D1420")); cornerRadius = 18f; setStroke(3, Color.parseColor(hex)) }
    }

    // ONE test = download phase, then upload phase, then it stops and shows the result. No auto refresh.
    private fun startTest() {
        if (engine.wifiNet() == null) { meter.msg = "Not connected to Wi-Fi - connect first"; return }
        meter.msg = ""; meter.dlRes = -1.0; meter.ulRes = -1.0
        state = 1; meter.state = 1; meter.mode = 0
        phaseStart = SystemClock.elapsedRealtime()
        engine.start(0)
        setBtn("■ CANCEL", "#FF3B30")
    }

    private fun cancelTest() {
        engine.stop(); state = 0; meter.state = 0; meter.secLeft = 0.0
        setBtn("▶ START TEST", "#00E676")
    }

    private fun addBucket(m: Int, kbps: Double) {
        val b = meter.curBucket
        if (b >= 0 && kbps > 0) { meter.bSum[b + 5 * m] += kbps; meter.bCnt[b + 5 * m]++ }
    }

    private fun advance() {
        if (state != 1 && state != 2) return
        val el = SystemClock.elapsedRealtime() - phaseStart
        meter.secLeft = ((testMs - el) / 1000.0).coerceAtLeast(0.0)
        if (el < testMs) return
        val avg = engine.avgBps / 1024.0
        val note = engine.note
        engine.stop()
        addBucket(state - 1, avg)
        if (state == 1) {
            meter.dlRes = avg
            state = 2; meter.state = 2; meter.mode = 1
            phaseStart = SystemClock.elapsedRealtime()
            engine.start(1)
        } else {
            meter.ulRes = avg
            state = 3; meter.state = 3
            if (avg <= 0 && note.isNotEmpty()) meter.msg = note
            setBtn("↻ TEST AGAIN", "#00E676")
        }
    }

    // ---------------- live Wi-Fi info (real) ----------------
    private fun tick() {
        var rssi: Int? = null; var ssid = ""; var freq = 0; var link = 0; var viaApp = false
        val sp = specSsid
        if (sp != null && engine.network != null) {
            viaApp = true; ssid = sp; freq = specFreq; link = specLink
            rssi = specRssi ?: try { wm.scanResults.filter { it.SSID == sp }.maxByOrNull { it.level }?.level } catch (e: Exception) { null }
        } else {
            val info = try { wm.connectionInfo } catch (e: Exception) { null }
            if (info != null && info.rssi > -127 && info.supplicantState == SupplicantState.COMPLETED) {
                rssi = info.rssi; freq = info.frequency; link = info.linkSpeed
                ssid = info.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" } ?: "Wi-Fi (enable Location to see name)"
            }
        }
        meter.ssid = if (rssi == null && ssid.isEmpty()) "NOT CONNECTED" else ssid
        meter.rssi = rssi
        meter.freq = freq; meter.link = link; meter.viaApp = viaApp
        meter.distance = if (rssi != null) SignalMath.estimateDistanceMeters(rssi) else 0.0
        if (rssi != null) {
            meter.sig.add(rssi); meter.dist.add(meter.distance.toFloat())
            if (meter.sig.size > 120) { meter.sig.removeAt(0); meter.dist.removeAt(0) }
            val b = when { meter.distance < 2 -> 0; meter.distance < 5 -> 1; meter.distance < 10 -> 2; meter.distance < 20 -> 3; else -> 4 }
            meter.curBucket = b
        } else meter.curBucket = -1
    }

    // ---------------- connect to another Wi-Fi and measure it ----------------
    private fun pickNetwork() {
        val res = try { wm.scanResults } catch (e: Exception) { emptyList() }
        val uniq = res.filter { !it.SSID.isNullOrEmpty() }.groupBy { it.SSID }
            .map { e -> e.value.maxByOrNull { it.level }!! }.sortedByDescending { it.level }.take(25)
        val labels = ArrayList<String>()
        for (r in uniq) labels.add("${if (secured(r)) "🔒" else "🔓"}  ${r.SSID}   ${r.level} dBm")
        labels.add("⚙  Open phone Wi-Fi settings")
        AlertDialog.Builder(context).setTitle("Connect & measure speed")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i >= uniq.size) { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)); return@setItems }
                val r = uniq[i]
                if (Build.VERSION.SDK_INT < 29) {
                    meter.msg = "Android 9 or older: connect from Wi-Fi settings, speed shows automatically"
                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)); return@setItems
                }
                if (secured(r)) askPassword(r) else connect(r.SSID, "", false)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun secured(r: android.net.wifi.ScanResult): Boolean {
        val c = r.capabilities
        return c.contains("WPA") || c.contains("WEP") || c.contains("SAE") || c.contains("PSK")
    }

    private fun askPassword(r: android.net.wifi.ScanResult) {
        val et = EditText(context).apply {
            hint = "Wi-Fi password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(context).setTitle(r.SSID).setView(et)
            .setPositiveButton("Connect") { _, _ -> connect(r.SSID, et.text.toString(), r.capabilities.contains("SAE") && !r.capabilities.contains("PSK")) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun connect(ssid: String, pass: String, wpa3: Boolean) {
        if (Build.VERSION.SDK_INT < 29) return
        disconnectApp()
        meter.msg = "Connecting to $ssid... (approve the Android prompt)"
        try {
            val b = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (pass.isNotEmpty()) { if (wpa3) b.setWpa3Passphrase(pass) else b.setWpa2Passphrase(pass) }
            val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).setNetworkSpecifier(b.build()).build()
            val cb = makeCallback(ssid)
            specCb = cb
            cm.requestNetwork(req, cb, 40000)
        } catch (e: Exception) { meter.msg = "Connect failed: ${e.message}" }
    }

    private fun onAvail(n: Network, ssid: String) = h.post {
        specSsid = ssid; engine.network = n; meter.msg = "Connected to $ssid (app-only link) - press START TEST"
    }
    private fun onCaps(c: NetworkCapabilities) {
        if (Build.VERSION.SDK_INT >= 29) {
            val ti = c.transportInfo
            if (ti is WifiInfo && ti.rssi > -127) { specRssi = ti.rssi; specFreq = ti.frequency; specLink = ti.linkSpeed }
        }
    }
    private fun onGone(text: String) = h.post {
        engine.network = null; specSsid = null; specRssi = null; meter.msg = text
    }

    private fun makeCallback(ssid: String): ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= 31) object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(network: Network) { onAvail(network, ssid) }
            override fun onCapabilitiesChanged(network: Network, c: NetworkCapabilities) { onCaps(c) }
            override fun onLost(network: Network) { onGone("Lost connection to $ssid") }
            override fun onUnavailable() { onGone("Could not connect to $ssid (wrong password / out of range)") }
        } else object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { onAvail(network, ssid) }
            override fun onCapabilitiesChanged(network: Network, c: NetworkCapabilities) { onCaps(c) }
            override fun onLost(network: Network) { onGone("Lost connection to $ssid") }
            override fun onUnavailable() { onGone("Could not connect to $ssid (wrong password / out of range)") }
        }

    private fun disconnectApp() {
        try { specCb?.let { cm.unregisterNetworkCallback(it) } } catch (e: Exception) { }
        specCb = null; engine.network = null; specSsid = null; specRssi = null
    }

    fun resume() { }
    fun pause() { if (state == 1 || state == 2) cancelTest() }
    fun stop() { alive = false; h.removeCallbacksAndMessages(null); engine.stop(); disconnectApp() }
}

/** Animated gauge + graphs, everything fed by SpeedEngine / live Wi-Fi readings. */
class MeterView(ctx: Context, private val eng: SpeedEngine) : View(ctx) {
    var ssid = "NOT CONNECTED"; var rssi: Int? = null; var freq = 0; var link = 0
    var viaApp = false; var distance = 0.0; var mode = 0; var msg = ""; var curBucket = -1
    var state = 0; var secLeft = 0.0; var dlRes = -1.0; var ulRes = -1.0
    val sig = ArrayList<Int>(); val dist = ArrayList<Float>()
    val bSum = DoubleArray(10); val bCnt = IntArray(10)      // 5 distance buckets x (download, upload)

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val path = Path()
    private var phase = 0f
    private var tAnim = 0L
    private var shown = 0.0           // KB/s drawn (smoothed)
    private var gMax = 256.0
    private val steps = doubleArrayOf(128.0, 256.0, 512.0, 1024.0, 2048.0, 5120.0, 10240.0, 20480.0, 51200.0, 102400.0, 256000.0)
    private val bucketNames = arrayOf("<2m", "2-5m", "5-10m", "10-20m", "20m+")

    private val loop = object : Runnable {
        override fun run() { tAnim = System.currentTimeMillis(); phase = (tAnim % 3000L) / 3000f; invalidate(); postDelayed(this, 33) }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(loop) }
    override fun onDetachedFromWindow() { removeCallbacks(loop); super.onDetachedFromWindow() }
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), (935 * d).toInt())
    }

    private fun sigColor(r: Int) = when {
        r >= -55 -> Color.parseColor("#00E676"); r >= -70 -> Color.parseColor("#FFB300"); else -> Color.parseColor("#FF3B30")
    }

    private fun glass(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, accent: Int) {
        rect.set(l, t, r, b)
        fill.style = Paint.Style.FILL; fill.shader = null; fill.color = Color.argb(150, 10, 18, 30)
        c.drawRoundRect(rect, rad, rad, fill)
        fill.shader = LinearGradient(0f, t, 0f, b, Color.argb(30, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, rad, rad, fill); fill.shader = null
        line.pathEffect = null
        for (i in 3 downTo 1) { line.color = accent; line.alpha = 22 * (4 - i); line.strokeWidth = (1f + i * 2f) * d; c.drawRoundRect(rect, rad, rad, line) }
        line.color = accent; line.alpha = 200; line.strokeWidth = 1.2f * d; c.drawRoundRect(rect, rad, rad, line)
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, a: Paint.Align = Paint.Align.CENTER) {
        txt.textSize = size * d; txt.color = color; txt.textAlign = a
        txt.typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        c.drawText(s, x, y, txt)
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%,.0f", v)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val cyan = Color.parseColor("#00E5FF"); val violet = Color.parseColor("#D500F9")
        val accent = if (mode == 0) cyan else violet
        val kbps = eng.instBps / 1024.0
        shown += (kbps - shown) * 0.22
        val want = steps.firstOrNull { it >= max(eng.peakBps / 1024.0 * 1.15, shown * 1.05) } ?: steps.last()
        gMax += (want - gMax) * 0.08

        // ---------- header ----------
        glass(c, 12 * d, 10 * d, w - 12 * d, 74 * d, 14 * d, if (rssi != null) sigColor(rssi!!) else Color.parseColor("#FF3B30"))
        var nm = ssid; if (nm.length > 24) nm = nm.take(23) + "…"
        text(c, (if (viaApp) "🔗 " else "📶 ") + nm, w / 2, 36 * d, 16f, Color.WHITE, true)
        val band = when { freq >= 5925 -> "6G"; freq >= 4900 -> "5G"; freq > 0 -> "2.4G"; else -> "--" }
        val sub = if (rssi != null) "$band · ${rssi} dBm · link $link Mbps" + if (viaApp) " · app-only link" else "" else "connect to a Wi-Fi to measure"
        text(c, sub, w / 2, 58 * d, 10f, Color.parseColor("#7FDBFF"))

        // ---------- gauge ----------
        val cx = w / 2; val cy = 215 * d; val rad = min(w * 0.36f, 130 * d)
        val running = eng.running
        if (running) {                                          // pulsing glow behind number
            fill.style = Paint.Style.FILL; fill.color = accent
            fill.alpha = (28 + 22 * sin(phase * 2 * Math.PI)).toInt()
            c.drawCircle(cx, cy, rad * (0.62f + 0.06f * sin(phase * 2 * Math.PI).toFloat()), fill); fill.alpha = 255
        }
        rect.set(cx - rad, cy - rad, cx + rad, cy + rad)
        line.pathEffect = null; line.strokeCap = Paint.Cap.ROUND; line.strokeWidth = 16 * d
        line.color = Color.argb(40, 255, 255, 255); line.alpha = 40
        c.drawArc(rect, 150f, 240f, false, line)
        val frac = (shown / gMax).coerceIn(0.0, 1.0).toFloat()
        c.save(); c.rotate(150f, cx, cy)
        line.shader = SweepGradient(cx, cy, intArrayOf(cyan, Color.parseColor("#00E676"), Color.parseColor("#FFB300"), Color.parseColor("#FF3B30"), Color.parseColor("#FF3B30")),
            floatArrayOf(0f, 0.22f, 0.44f, 0.667f, 1f))
        line.alpha = 255
        if (frac > 0.003f) c.drawArc(rect, 0f, 240f * frac, false, line)
        line.shader = null; c.restore()
        line.strokeCap = Paint.Cap.BUTT
        // flowing dashes on outer ring while measuring
        if (running) {
            val ro = rad + 20 * d
            rect.set(cx - ro, cy - ro, cx + ro, cy + ro)
            line.strokeWidth = 2.5f * d; line.color = accent; line.alpha = 150
            line.pathEffect = DashPathEffect(floatArrayOf(5 * d, 11 * d), -(tAnim % 4000L) / 4000f * 16 * d * 12)
            c.drawArc(rect, 150f, 240f, false, line); line.pathEffect = null
        }
        // ticks + labels
        for (i in 0..10) {
            val ang = Math.toRadians((150 + 24.0 * i))
            val r1 = rad - 16 * d; val r2 = rad - (if (i % 5 == 0) 28 else 22) * d
            line.strokeWidth = 1.5f * d; line.color = Color.WHITE; line.alpha = if (i % 5 == 0) 200 else 90
            c.drawLine((cx + r1 * Math.cos(ang)).toFloat(), (cy + r1 * Math.sin(ang)).toFloat(), (cx + r2 * Math.cos(ang)).toFloat(), (cy + r2 * Math.sin(ang)).toFloat(), line)
            if (i % 5 == 0) {
                val r3 = rad - 42 * d
                text(c, fmt(gMax * i / 10), (cx + r3 * Math.cos(ang)).toFloat(), (cy + r3 * Math.sin(ang)).toFloat() + 4 * d, 8f, Color.parseColor("#7FDBFF"))
            }
        }
        // needle
        val na = Math.toRadians(150.0 + 240.0 * frac)
        line.strokeWidth = 3f * d; line.color = Color.WHITE; line.alpha = 255; line.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx, cy, (cx + (rad - 34 * d) * Math.cos(na)).toFloat(), (cy + (rad - 34 * d) * Math.sin(na)).toFloat(), line)
        line.strokeCap = Paint.Cap.BUTT
        fill.style = Paint.Style.FILL; fill.color = accent; c.drawCircle(cx, cy, 6 * d, fill)

        // big numbers
        text(c, when (state) { 1 -> "DOWNLOAD"; 2 -> "UPLOAD"; 3 -> "DONE"; else -> "READY" }, cx, cy + 36 * d, 10f, accent, true)
        text(c, fmt(shown), cx, cy + 74 * d, 36f, Color.WHITE, true)
        text(c, "KB/s", cx, cy + 94 * d, 12f, accent, true)
        text(c, String.format(Locale.US, "%.2f Mbps", shown * 1024 * 8 / 1e6), cx, cy + 116 * d, 12f, Color.parseColor("#B2EBF2"))

        // status
        val st = when {
            msg.isNotEmpty() -> msg
            state == 0 -> "Press START TEST - runs once (~16 s), no auto refresh"
            state == 1 || state == 2 -> (if (eng.note.isNotEmpty()) eng.note else (if (state == 1) "Testing download" else "Testing upload")) + " ... ${secLeft.toInt() + 1}s"
            else -> "Test complete - result below"
        }
        text(c, st, cx, 362 * d, 10f, if (eng.note.isNotEmpty() && eng.running) Color.parseColor("#FFC400") else Color.parseColor("#B2EBF2"))

        // ---------- RESULT card: one test = one result ----------
        val sy = 376 * d; val sh = 88 * d
        glass(c, 12 * d, sy, w - 12 * d, sy + sh, 14 * d, accent)
        val half = (w - 24 * d) / 2
        fun res(i: Int, label: String, v: Double, col: Int) {
            val x = 12 * d + half * i + half / 2
            text(c, label, x, sy + 22 * d, 10f, col, true)
            if (v >= 0) {
                text(c, if (v > 0) fmt(v) else "FAILED", x, sy + 54 * d, 24f, Color.WHITE, true)
                text(c, if (v > 0) "KB/s · " + String.format(Locale.US, "%.2f Mbps", v * 1024 * 8 / 1e6) else "no internet?", x, sy + 74 * d, 9f, Color.parseColor("#B2EBF2"))
            } else text(c, "--", x, sy + 54 * d, 24f, Color.parseColor("#55FFFFFF"))
        }
        res(0, "⬇ DOWNLOAD", dlRes, cyan)
        res(1, "⬆ UPLOAD", ulRes, violet)

        val s2 = 472 * d; val sh2 = 46 * d
        glass(c, 12 * d, s2, w - 12 * d, s2 + sh2, 12 * d, cyan)
        val cw = (w - 24 * d) / 3
        fun cell(i: Int, label: String, value: String, col: Int) {
            val x = 12 * d + cw * i + cw / 2
            text(c, label, x, s2 + 17 * d, 8.5f, Color.parseColor("#7FDBFF"))
            text(c, value, x, s2 + 37 * d, 13f, col, true)
        }
        cell(0, "PING", eng.pingMs?.let { "$it ms" } ?: "--", Color.WHITE)
        cell(1, "SIGNAL", rssi?.let { "$it dBm" } ?: "--", if (rssi != null) sigColor(rssi!!) else Color.WHITE)
        cell(2, "≈ DISTANCE", if (rssi != null) String.format(Locale.US, "%.1f m", distance) else "--", Color.parseColor("#FFB300"))

        // ---------- graph A: signal + distance, last 60 s ----------
        val ay = 528 * d; val ah = 186 * d
        glass(c, 12 * d, ay, w - 12 * d, ay + ah, 14 * d, cyan)
        text(c, "SIGNAL & DISTANCE · LAST 60 s", 24 * d, ay + 22 * d, 10f, cyan, true, Paint.Align.LEFT)
        val rr = rssi
        if (rr != null) text(c, "$rr dBm · ≈${String.format(Locale.US, "%.1f", distance)} m", w - 24 * d, ay + 22 * d, 10f, sigColor(rr), true, Paint.Align.RIGHT)
        val pl = 44 * d; val pr = w - 28 * d; val pt = ay + 36 * d; val pb = ay + ah - 16 * d
        for (g in intArrayOf(-40, -60, -80)) {
            val y = pb - (pb - pt) * ((g + 100) / 70f)
            line.color = Color.WHITE; line.alpha = 30; line.strokeWidth = 1f * d; c.drawLine(pl, y, pr, y, line)
            text(c, "$g", pl - 6 * d, y + 3 * d, 8f, Color.parseColor("#7FDBFF"), false, Paint.Align.RIGHT)
        }
        if (sig.size >= 2) {
            val n = sig.size
            val lc = sigColor(sig.last())
            path.reset()
            for (i in 0 until n) {
                val x = pl + (pr - pl) * (120 - n + i) / 119f
                val y = pb - (pb - pt) * ((sig[i].coerceIn(-100, -30) + 100) / 70f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            line.color = lc; line.alpha = 255; line.strokeWidth = 2.4f * d; c.drawPath(path, line)
            val md = max(10f, dist.max())
            path.reset()
            for (i in 0 until n) {
                val x = pl + (pr - pl) * (120 - n + i) / 119f
                val y = pb - (pb - pt) * (dist[i] / md)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            line.color = Color.parseColor("#00E5FF"); line.alpha = 200; line.strokeWidth = 1.6f * d
            line.pathEffect = DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f); c.drawPath(path, line); line.pathEffect = null
            text(c, "— dBm   - - distance (0-${md.toInt()} m)", pr, pb + 12 * d, 8f, Color.parseColor("#7FDBFF"), false, Paint.Align.RIGHT)
        } else text(c, "waiting for Wi-Fi signal...", (pl + pr) / 2, (pt + pb) / 2, 10f, Color.parseColor("#FFC400"))

        // ---------- graph B: speed vs distance (each finished test adds a real point) ----------
        val by = 724 * d; val bh = 196 * d
        glass(c, 12 * d, by, w - 12 * d, by + bh, 14 * d, cyan)
        text(c, "SPEED vs DISTANCE", 24 * d, by + 22 * d, 10f, cyan, true, Paint.Align.LEFT)
        text(c, "■ down  ", w - 70 * d, by + 22 * d, 9f, cyan, false, Paint.Align.RIGHT)
        text(c, "■ up", w - 28 * d, by + 22 * d, 9f, violet, false, Paint.Align.RIGHT)
        var mx = 1.0
        for (k in 0 until 10) if (bCnt[k] > 0) mx = max(mx, bSum[k] / bCnt[k])
        val bl = 28 * d; val br = w - 28 * d; val bb = by + bh - 34 * d; val bt = by + 46 * d
        val slot = (br - bl) / 5
        var any = false
        for (i in 0 until 5) {
            for (m in 0..1) {
                val k = i + 5 * m
                val has = bCnt[k] > 0
                val avg = if (has) bSum[k] / bCnt[k] else 0.0
                if (has) any = true
                val x0 = bl + slot * i + slot * 0.10f + m * slot * 0.40f
                val x1 = x0 + slot * 0.38f
                val top = bb - (bb - bt) * (avg / mx).toFloat()
                rect.set(x0, if (has) top else bb - 3 * d, x1, bb)
                fill.style = Paint.Style.FILL
                fill.color = if (m == 0) cyan else violet
                fill.alpha = if (has) (if (i == curBucket) 255 else 170) else 40
                c.drawRoundRect(rect, 5 * d, 5 * d, fill); fill.alpha = 255
                if (has) text(c, fmt(avg), (x0 + x1) / 2, top - 4 * d, 7.5f, Color.WHITE, true)
            }
            text(c, bucketNames[i], bl + slot * i + slot / 2, bb + 15 * d, 9f, if (i == curBucket) Color.WHITE else Color.parseColor("#7FDBFF"), i == curBucket)
        }
        if (!any) {
            text(c, "Run a test, walk closer/farther, run again:", w / 2, by + 92 * d, 9.5f, Color.parseColor("#FFC400"))
            text(c, "bars fill with your REAL KB/s per distance", w / 2, by + 108 * d, 9.5f, Color.parseColor("#FFC400"))
        }
    }
}
