package com.tonix.wifireader

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Geiger-style beeper. One single thread, one AudioTrack: reads the LIVE rssi before every beep,
 * so beep speed AND pitch follow the signal in real time. (Old version stacked many timers and
 * ToneGenerator beeps were too short to hear.)
 */
class Beeper(private val rssiProvider: () -> Int?) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val rate = 22050

    fun start() {
        if (running) return
        running = true
        thread = Thread { loop() }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        try { thread?.join(600) } catch (e: Exception) { }
        thread = null
    }

    private fun tone(freq: Double, ms: Int): ShortArray {
        val n = rate * ms / 1000
        val fade = (rate * 0.006).toInt().coerceAtLeast(1)
        return ShortArray(n) { i ->
            val env = min(1.0, min(i.toDouble() / fade, (n - i).toDouble() / fade))
            (sin(2 * PI * freq * i / rate) * 26000 * env).toInt().toShort()
        }
    }

    private fun loop() {
        var track: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBuf, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            while (running) {
                val r = rssiProvider()
                if (r == null) { track.write(ShortArray(rate / 10), 0, rate / 10); continue }
                val interval = SignalMath.beepIntervalMs(r).toInt()
                val toneMs = min(70, interval - 15).coerceAtLeast(30)
                val t = tone(SignalMath.beepFreqHz(r), toneMs)
                track.write(t, 0, t.size)
                val gap = ((interval - toneMs).coerceAtLeast(10)) * rate / 1000
                track.write(ShortArray(gap), 0, gap)
            }
        } catch (e: Exception) {
        } finally {
            try { track?.pause(); track?.flush(); track?.release() } catch (e: Exception) { }
        }
    }
}
