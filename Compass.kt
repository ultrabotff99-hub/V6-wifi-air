package com.tonix.wifireader

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Direction the camera points (degrees, 0 = true north, 90 = east). NaN when the phone has no compass. */
class Compass(ctx: Context) : SensorEventListener {
    private val sm = ctx.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    @Volatile var heading = Float.NaN
    @Volatile var declination = 0f
    private val r1 = FloatArray(9)
    private val r2 = FloatArray(9)
    private val o = FloatArray(3)
    private var sinS = 0f
    private var cosS = 0f
    private var first = true
    private var on = false

    fun start() {
        if (on) return
        val s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        on = true
    }

    fun stop() { sm.unregisterListener(this); on = false }

    fun setLocation(l: Location) {
        try { declination = GeomagneticField(l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), System.currentTimeMillis()).declination } catch (e: Exception) { }
    }

    override fun onSensorChanged(e: SensorEvent) {
        try {
            SensorManager.getRotationMatrixFromVector(r1, e.values)
            // phone held upright, camera looking at the horizon
            SensorManager.remapCoordinateSystem(r1, SensorManager.AXIS_X, SensorManager.AXIS_Z, r2)
            SensorManager.getOrientation(r2, o)
            val rad = o[0]
            if (first) { sinS = sin(rad); cosS = cos(rad); first = false }
            else { sinS += (sin(rad) - sinS) * 0.18f; cosS += (cos(rad) - cosS) * 0.18f }   // smooth on the circle
            val deg = Math.toDegrees(atan2(sinS, cosS).toDouble()).toFloat() + declination
            heading = ((deg % 360f) + 360f) % 360f
        } catch (ex: Exception) { }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
