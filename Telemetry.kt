package com.tonix.wifireader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

data class NearAp(val ssid: String, val bssid: String, val level: Int)

/**
 * REAL data only - nothing simulated:
 *  - dBm   : connected to the target -> read from the Wi-Fi chip every 500 ms (LIVE).
 *            not connected          -> from Android scan results (SCAN; Android throttles scans).
 *  - LINK  : real negotiated PHY speed (only when connected).
 *  - DOWN  : real download throughput measured over the Wi-Fi network, auto-repeating (only when connected).
 *  - PING  : real TCP connect time over the Wi-Fi network.
 */
@Suppress("DEPRECATION")
class WifiTelemetry(ctx: Context, private val targetBssid: String, val targetSsid: String) {
    private val app = ctx.applicationContext
    private val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val h = Handler(Looper.getMainLooper())

    @Volatile var rssi: Int? = null
    @Volatile var inRange = false
    @Volatile var connected = false
    @Volatile var source = "--"          // LIVE or SCAN
    @Volatile var scanAgeSec = -1
    @Volatile var locationOn = true
    @Volatile var linkMbps = 0
    @Volatile var txMbps = 0
    @Volatile var rxMbps = 0
    @Volatile var downMbps: Double? = null
    @Volatile var pingMs: Int? = null
    @Volatile var freq = 0
    @Volatile var standard = "--"
    @Volatile var width = "--"
    @Volatile var testNote = ""
    @Volatile var lastUpdate = 0L
    @Volatile var others: List<NearAp> = emptyList()
    val history = ArrayList<Float>()      // Mbps samples
    val rssiHistory = ArrayList<Int>()    // dBm samples

    @Volatile private var running = false
    @Volatile private var testing = false
    private var receiver: BroadcastReceiver? = null

    private val tick = object : Runnable {
        override fun run() { if (!running) return; poll(); h.postDelayed(this, 500) }
    }
    private val scanLoop = object : Runnable {
        override fun run() {
            if (!running) return
            try { wm.startScan() } catch (e: Exception) { }
            h.postDelayed(this, 3000)
        }
    }

    fun start() {
        if (running) return
        running = true
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { poll() }
        }
        try { app.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) } catch (e: Exception) { }
        h.post(tick); h.post(scanLoop)
    }

    fun stop() {
        running = false
        h.removeCallbacksAndMessages(null)
        try { receiver?.let { app.unregisterReceiver(it) } } catch (e: Exception) { }
        receiver = null
    }

    fun bandName(): String = when {
        freq >= 5925 -> "6 GHz"
        freq >= 4900 -> "5 GHz"
        freq > 0 -> "2.4 GHz"
        else -> "--"
    }

    fun channel(): Int = when {
        freq <= 0 -> 0
        freq < 3000 -> (freq - 2407) / 5
        freq >= 5925 -> (freq - 5950) / 5
        else -> (freq - 5000) / 5
    }

    private fun stdName(r: ScanResult): String {
        if (Build.VERSION.SDK_INT < 30) return "--"
        return when (r.wifiStandard) {
            1 -> "Legacy"
            4 -> "Wi-Fi 4"
            5 -> "Wi-Fi 5"
            6 -> if (r.frequency >= 5925) "Wi-Fi 6E" else "Wi-Fi 6"
            8 -> "Wi-Fi 7"
            else -> "--"
        }
    }

    private fun widthName(r: ScanResult): String = when (r.channelWidth) {
        0 -> "20 MHz"
        1 -> "40 MHz"
        2 -> "80 MHz"
        3 -> "160 MHz"
        4 -> "80+80 MHz"
        5 -> "320 MHz"
        else -> "--"
    }

    private fun isLocationOn(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else true
    } catch (e: Exception) { true }

    private fun poll() {
        try {
            locationOn = isLocationOn()
            val info = try { wm.connectionInfo } catch (e: Exception) { null }
            val res: List<ScanResult> = try { wm.scanResults } catch (e: Exception) { emptyList() }
            val match = res.firstOrNull { it.BSSID.equals(targetBssid, true) }

            // connected to the target? match by BSSID (or by SSID if Android hides the BSSID)
            val cb = info?.bssid
            val bssidHidden = cb == null || cb == "02:00:00:00:00:00"
            val sameBssid = !bssidHidden && cb.equals(targetBssid, true)
            val cleanSsid = info?.ssid?.trim('"') ?: ""
            val sameSsid = bssidHidden && targetSsid.isNotEmpty() && cleanSsid == targetSsid
            val isConn = info != null && info.rssi > -127 && (sameBssid || sameSsid)
            connected = isConn

            if (isConn && info != null) {
                rssi = info.rssi; linkMbps = info.linkSpeed; freq = info.frequency; inRange = true
                source = "LIVE"; scanAgeSec = 0
                if (Build.VERSION.SDK_INT >= 29) { txMbps = info.txLinkSpeedMbps; rxMbps = info.rxLinkSpeedMbps }
                if (!testing) startSpeedThread()
            } else {
                linkMbps = 0; txMbps = 0; rxMbps = 0; downMbps = null; pingMs = null; testNote = ""
                if (match != null) {
                    rssi = match.level; freq = match.frequency; inRange = true; source = "SCAN"
                    scanAgeSec = ((SystemClock.elapsedRealtime() * 1000L - match.timestamp) / 1_000_000L).toInt().coerceAtLeast(0)
                } else { rssi = null; inRange = false; source = "--"; scanAgeSec = -1 }
            }
            if (match != null) { standard = stdName(match); width = widthName(match) }
            val r = rssi
            if (r != null) synchronized(rssiHistory) { rssiHistory.add(r); if (rssiHistory.size > 60) rssiHistory.removeAt(0) }
            others = res.filter { !it.BSSID.equals(targetBssid, true) }
                .sortedByDescending { it.level }.take(4)
                .map { NearAp(if (it.SSID.isNullOrEmpty()) "<hidden>" else it.SSID, it.BSSID, it.level) }
            lastUpdate = System.currentTimeMillis()
        } catch (e: Exception) { }
    }

    // ---------- real speed test over the Wi-Fi network (repeats forever, auto refresh) ----------
    private fun wifiNetwork(): Network? = try {
        cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    } catch (e: Exception) { null }

    private fun startSpeedThread() {
        testing = true
        Thread {
            try {
                while (running && connected) {
                    val net = wifiNetwork()
                    pingMs = ping(net)
                    val v = download(net)
                    if (v != null) {
                        downMbps = v; testNote = ""
                        synchronized(history) { history.add(v.toFloat()); if (history.size > 40) history.removeAt(0) }
                    } else {
                        testNote = "Connected, but this Wi-Fi has no internet - cannot measure Mbps"
                    }
                    Thread.sleep(300)
                }
            } catch (e: Exception) {
            } finally { testing = false }
        }.start()
    }

    private fun tcpTime(net: Network?, host: InetAddress, port: Int, allowRefused: Boolean): Int? {
        val s: Socket = if (net != null) net.socketFactory.createSocket() else Socket()
        val t0 = SystemClock.elapsedRealtimeNanos()
        return try {
            s.connect(InetSocketAddress(host, port), 1500)
            ((SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L).toInt()
        } catch (e: java.net.ConnectException) {
            // "connection refused" still proves a round trip to the router
            if (allowRefused && (e.message ?: "").contains("refused", true))
                ((SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L).toInt() else null
        } catch (e: Exception) { null } finally { try { s.close() } catch (e: Exception) { } }
    }

    private fun ping(net: Network?): Int? {
        val inet = tcpTime(net, InetAddress.getByName("1.1.1.1"), 443, false)
        if (inet != null) return inet
        return try {
            val gw = wm.dhcpInfo.gateway
            if (gw == 0) null else {
                val ip = InetAddress.getByAddress(byteArrayOf(
                    (gw and 0xff).toByte(), (gw shr 8 and 0xff).toByte(), (gw shr 16 and 0xff).toByte(), (gw shr 24 and 0xff).toByte()))
                tcpTime(net, ip, 80, true)
            }
        } catch (e: Exception) { null }
    }

    private val urls = arrayOf(
        "https://speed.cloudflare.com/__down?bytes=25000000",
        "https://proof.ovh.net/files/10Mb.dat",
        "https://ash-speed.hetzner.com/100MB.bin"
    )

    private fun download(net: Network?): Double? {
        for (u in urls) {
            val total = AtomicLong(0L)
            val first = AtomicLong(0L)
            val last = AtomicLong(0L)
            val deadline = SystemClock.elapsedRealtime() + 2200
            val workers = (0 until 3).map {
                Thread {
                    try {
                        val url = URL(u)
                        val c = (if (net != null) net.openConnection(url) else url.openConnection()) as HttpURLConnection
                        c.connectTimeout = 4000; c.readTimeout = 4000
                        c.setRequestProperty("User-Agent", "Mozilla/5.0")
                        c.inputStream.use { ins ->
                            val buf = ByteArray(65536)
                            while (running && connected && SystemClock.elapsedRealtime() < deadline) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                val now = SystemClock.elapsedRealtime()
                                first.compareAndSet(0L, now)
                                last.set(now)
                                total.addAndGet(n.toLong())
                            }
                        }
                        c.disconnect()
                    } catch (e: Exception) { }
                }.also { it.start() }
            }
            while (workers.any { it.isAlive }) {
                Thread.sleep(250)
                val f = first.get()
                if (f != 0L) {
                    val dt = (SystemClock.elapsedRealtime() - f) / 1000.0
                    if (dt > 0.3) downMbps = total.get() * 8 / 1e6 / dt   // live reading while testing
                }
            }
            val f = first.get()
            val dt = (last.get() - f) / 1000.0
            if (f != 0L && dt > 0.05 && total.get() > 0) return total.get() * 8 / 1e6 / dt
        }
        return null
    }
}
