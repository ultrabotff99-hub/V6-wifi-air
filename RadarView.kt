package com.tonix.wifireader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.*

data class Blip(val angle: Float, val dist: Float, val label: String, val tier: Int) // 2=near(white) 1=mid(green) 0=far(red)

class RadarView(c: Context, a: AttributeSet? = null) : View(c, a) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private var sweep = 0f
    private var pulse = 0f
    var blips: List<Blip> = emptyList()

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { pulse = it.animatedValue as Float; sweep = pulse * 360f; invalidate() }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(cv: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = min(cx, cy) * 0.92f
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, r, 0x3300E5FF, 0x00000000, Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, r, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0x5500E5FF
        for (i in 1..4) cv.drawCircle(cx, cy, r * i / 4, p)
        cv.drawLine(cx - r, cy, cx + r, cy, p); cv.drawLine(cx, cy - r, cx, cy + r, p)
        p.color = 0xFF00E5FF.toInt(); p.alpha = ((1 - pulse) * 180).toInt(); p.strokeWidth = 4f
        cv.drawCircle(cx, cy, r * pulse, p); p.alpha = 255
        p.style = Paint.Style.FILL
        p.shader = SweepGradient(cx, cy, intArrayOf(0x0000E5FF, 0xAA00E5FF.toInt()), floatArrayOf(0.8f, 1f))
        cv.save(); cv.rotate(sweep, cx, cy); cv.drawCircle(cx, cy, r, p); cv.restore(); p.shader = null

        for (b in blips) {
            val x = cx + cos(Math.toRadians(b.angle.toDouble())).toFloat() * b.dist * r
            val y = cy + sin(Math.toRadians(b.angle.toDouble())).toFloat() * b.dist * r
            val diff = ((sweep - b.angle) % 360 + 360) % 360
            val glow = (1f - diff / 360f).pow(3)
            val radius = when (b.tier) { 2 -> 20f; 1 -> 16f; else -> 12f }
            p.color = when (b.tier) {
                2 -> 0xFFFFFFFF.toInt()
                1 -> 0xFF00E676.toInt()
                else -> 0xFFFF3B30.toInt()
            }
            p.alpha = (140 + 115 * glow).toInt()
            cv.drawCircle(x, y, radius + 6f * glow, p)
            p.alpha = 255; p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = Color.WHITE; p.alpha = 160
            cv.drawCircle(x, y, radius + 6f * glow, p); p.style = Paint.Style.FILL; p.alpha = 255
            tp.textSize = radius
            cv.drawText(b.label, x, y + radius * 0.35f, tp)
        }
        p.color = 0xFF00E5FF.toInt(); cv.drawCircle(cx, cy, 6f, p)
    }
}
