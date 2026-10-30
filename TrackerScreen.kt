package com.tonix.wifireader

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Hot/cold tracker: real RSSI + real beep (speed & pitch follow the signal). */
class TrackerScreen(ctx: Context, targetBssid: String, private val targetSsid: String) : LinearLayout(ctx) {
    private val h = Handler(Looper.getMainLooper())
    private val tele = WifiTelemetry(ctx, targetBssid, targetSsid)
    private val beeper = Beeper { if (tele.inRange) tele.rssi else null }
    private val signalTxt: TextView
    private val distTxt: TextView
    private val trendTxt: TextView
    private val infoTxt: TextView
    private val bar: ProgressBar
    private var running = true
    private val recent = ArrayList<Int>()

    private fun label(t: String, s: Float, c: Int, bold: Boolean = false) = TextView(ctx()).apply {
        text = t; textSize = s; setTextColor(c); typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun ctx(): Context = context

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#05070D"))
        setPadding(40, 60, 40, 40)
        gravity = Gravity.CENTER_HORIZONTAL

        addView(label("🎯 TARGETING", 13f, Color.parseColor("#FFC400")))
        addView(label(targetSsid, 20f, Color.parseColor("#00E5FF"), true).apply { setPadding(0, 6, 0, 30) })
        signalTxt = label("-- dBm", 46f, Color.WHITE, true).apply { setPadding(0, 10, 0, 10) }
        addView(signalTxt)
        bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
        addView(bar, LayoutParams(-1, 30))
        trendTxt = label("", 22f, Color.WHITE, true).apply { setPadding(0, 24, 0, 8) }
        addView(trendTxt)
        distTxt = label("", 16f, Color.parseColor("#7FDBFF"))
        addView(distTxt)
        infoTxt = label("", 12f, Color.parseColor("#80DEEA")).apply { setPadding(10, 40, 10, 0) }
        addView(infoTxt)

        tele.start(); beeper.start()
        h.post(object : Runnable { override fun run() { if (!running) return; refresh(); h.postDelayed(this, 300) } })
    }

    private fun refresh() {
        val r = tele.rssi
        if (!tele.inRange || r == null) {
            signalTxt.text = "NO SIGNAL"; trendTxt.text = ""; distTxt.text = "Out of range"; bar.progress = 0
            infoTxt.text = if (!tele.locationOn) "Turn ON Location (GPS): Android returns no Wi-Fi data when it is off." else "Searching..."
            return
        }
        signalTxt.text = "$r dBm"
        bar.progress = SignalMath.quality(r)
        distTxt.text = "~%.1f m away (estimated from signal)".format(SignalMath.estimateDistanceMeters(r))
        recent.add(r); if (recent.size > 12) recent.removeAt(0)
        if (recent.size >= 8) {
            val old = recent.take(4).average(); val new = recent.takeLast(4).average()
            when {
                new - old >= 1.5 -> { trendTxt.text = "🔥 GETTING CLOSER"; trendTxt.setTextColor(Color.parseColor("#00E676")) }
                old - new >= 1.5 -> { trendTxt.text = "❄ GETTING FARTHER"; trendTxt.setTextColor(Color.parseColor("#FF3B30")) }
                else -> { trendTxt.text = "• STEADY"; trendTxt.setTextColor(Color.WHITE) }
            }
        }
        infoTxt.text = if (tele.source == "LIVE")
            "LIVE from Wi-Fi chip (connected).\nBeep speed + pitch rise with signal."
        else
            "Signal from Android scan (${tele.scanAgeSec}s old). Android limits scans - for instant updates CONNECT to this Wi-Fi, or turn OFF \"Wi-Fi scan throttling\" in Developer options."
    }

    fun resume() { if (!running) return; tele.start(); beeper.start() }
    fun pause() { beeper.stop(); tele.stop() }
    fun stop() { running = false; h.removeCallbacksAndMessages(null); pause() }
}
