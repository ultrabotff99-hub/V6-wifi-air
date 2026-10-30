package com.tonix.wifireader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Random
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * REAL continuous throughput meter. Counts the bytes that really cross the Wi-Fi link
 * (3 parallel streams) and converts them every 250 ms. NO multipliers, NO simulation.
 * mode 0 = download, 1 = upload. Uses the Wi-Fi network explicitly (never mobile data).
 */
class SpeedEngine(ctx: Context) {
    private val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile var network: Network? = null      // set when we connected to another Wi-Fi through the app
    @Volatile var running = false
    @Volatile var mode = 0
    @Volatile var instBps = 0.0                 // smoothed bytes/sec, refreshed every 250 ms
    @Volatile var peakBps = 0.0
    @Volatile var avgBps = 0.0                  // average over the test (after 1.5 s warm-up)
    private var baseBytes = 0L
    private var baseT = 0L
    @Volatile var totalBytes = 0L
    @Volatile var pingMs: Int? = null
    @Volatile var note = ""
    @Volatile var startedAt = 0L

    private val counter = AtomicLong(0)
    @Volatile private var gen = 0

    private val downUrls = arrayOf(
        "https://speed.cloudflare.com/__down?bytes=200000000",
        "https://proof.ovh.net/files/100Mb.dat",
        "https://ash-speed.hetzner.com/100MB.bin"
    )

    private fun alive(g: Int) = running && g == gen

    fun wifiNet(): Network? = network ?: try {
        cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    } catch (e: Exception) { null }

    fun warm(): Boolean = running && SystemClock.elapsedRealtime() - startedAt > 2000

    fun start(m: Int) {
        stop()
        mode = m; running = true
        val g = ++gen
        instBps = 0.0; peakBps = 0.0; avgBps = 0.0; baseBytes = 0L; baseT = 0L; totalBytes = 0L; counter.set(0); note = "Starting..."
        startedAt = SystemClock.elapsedRealtime()
        repeat(3) { Thread { worker(g) }.also { it.isDaemon = true; it.start() } }
        Thread { sampler(g) }.also { it.isDaemon = true; it.start() }
        Thread { pinger(g) }.also { it.isDaemon = true; it.start() }
    }

    fun stop() { running = false; gen++; instBps = 0.0 }

    private fun worker(g: Int) {
        while (alive(g)) {
            val net = wifiNet()
            if (net == null) { note = "Not connected to Wi-Fi - connect to a Wi-Fi first"; sleep(800); continue }
            val ok = try { if (mode == 0) downloadOnce(g, net) else uploadOnce(g, net) } catch (e: Exception) { false }
            if (!ok && alive(g)) { note = "No internet on this Wi-Fi (or server blocked)"; sleep(1500) }
        }
    }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (e: Exception) { } }

    private fun downloadOnce(g: Int, net: Network): Boolean {
        var ok = false
        for (u in downUrls) {
            if (!alive(g)) return ok
            try {
                val c = net.openConnection(URL(u)) as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 5000
                c.setRequestProperty("User-Agent", "Mozilla/5.0")
                c.inputStream.use { ins ->
                    val buf = ByteArray(65536)
                    while (alive(g)) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        counter.addAndGet(n.toLong()); ok = true
                    }
                }
                c.disconnect()
                if (ok) { note = ""; return true }
            } catch (e: Exception) { }
        }
        return ok
    }

    private fun uploadOnce(g: Int, net: Network): Boolean {
        val total = 50_000_000
        var ok = false
        try {
            val c = net.openConnection(URL("https://speed.cloudflare.com/__up")) as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.setFixedLengthStreamingMode(total.toLong())
            c.connectTimeout = 5000; c.readTimeout = 15000
            c.setRequestProperty("Content-Type", "application/octet-stream")
            val buf = ByteArray(32768).also { Random().nextBytes(it) }
            try {
                c.outputStream.use { os ->
                    var sent = 0
                    while (alive(g) && sent < total) {
                        val n = min(buf.size, total - sent)
                        os.write(buf, 0, n); sent += n
                        counter.addAndGet(n.toLong()); ok = true
                    }
                }
                if (alive(g)) c.responseCode
            } catch (e: Exception) { }
            c.disconnect()
            if (ok) note = ""
        } catch (e: Exception) { }
        return ok
    }

    private fun sampler(g: Int) {
        var last = 0L
        var lastT = SystemClock.elapsedRealtime()
        while (alive(g)) {
            sleep(250)
            val now = SystemClock.elapsedRealtime()
            val cur = counter.get()
            val dt = (now - lastT) / 1000.0
            if (dt > 0) {
                val raw = (cur - last) / dt
                instBps = if (instBps == 0.0) raw else instBps * 0.55 + raw * 0.45
                totalBytes = cur
                if (now - startedAt > 1500) {
                    if (instBps > peakBps) peakBps = instBps
                    if (baseT == 0L) { baseT = now; baseBytes = cur }
                    else if (now - baseT > 500) avgBps = (cur - baseBytes) / ((now - baseT) / 1000.0)
                }
            }
            last = cur; lastT = now
        }
    }

    private fun pinger(g: Int) {
        while (alive(g)) {
            val net = wifiNet()
            if (net != null) {
                val s = try { net.socketFactory.createSocket() } catch (e: Exception) { Socket() }
                val t0 = SystemClock.elapsedRealtimeNanos()
                pingMs = try {
                    s.connect(InetSocketAddress("1.1.1.1", 443), 1500)
                    ((SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L).toInt()
                } catch (e: Exception) { null }
                try { s.close() } catch (e: Exception) { }
            }
            sleep(1500)
        }
    }
}
