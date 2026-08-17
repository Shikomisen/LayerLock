package com.shikomisen.layerlock.lockscreen

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect

/**
 * Reports whether something is covering the proximity sensor — a pocket, a bag, or the phone lying
 * face down (OPEN-1).
 *
 * ## Why the default is "not covered"
 *
 * A sensor cannot be polled synchronously; the first reading only arrives as an event after
 * registration. Not every device has a `TYPE_PROXIMITY` sensor at all, and on those the state stays
 * at its initial value forever. Both cases therefore have to start *uncovered*, so a missing or
 * slow sensor can never leave the lock screen refusing to unlock — the guard only ever suppresses
 * input on a positive "near" reading. On-change sensors deliver their current value immediately on
 * registration, so the uncovered window on a device that does have one is a few milliseconds.
 *
 * ## Lifetime
 *
 * Registration is bound to the visible window via [LifecycleStartEffect]: listening starts at
 * `onStart` and stops at `onStop` or composition disposal, whichever comes first.
 * [LockScreenActivity] is `noHistory` and is recreated on every screen-on, so a listener that
 * outlived its Activity would accumulate one leak per wake.
 */
@Composable
fun rememberScreenCovered(): State<Boolean> {
    val context = LocalContext.current
    val covered = remember { mutableStateOf(false) }

    LifecycleStartEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        if (sensor == null) {
            onStopOrDispose { }
        } else {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    covered.value = isNear(event.values.firstOrNull(), sensor.maximumRange)
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }

            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)

            onStopOrDispose {
                sensorManager.unregisterListener(listener)
                // No readings arrive while stopped, so a stale "covered" must not be what the next
                // start sees before its first event.
                covered.value = false
            }
        }
    }

    return covered
}

/**
 * Whether a raw proximity reading counts as covered.
 *
 * Proximity sensors are inconsistent by OEM. Many are effectively binary and report `0` for near
 * and [maximumRange] for far; others report a real distance in centimetres over a range of several
 * centimetres. Comparing against the smaller of [maximumRange] and [NEAR_THRESHOLD_CM] handles
 * both: a binary sensor's far value is never below its own range, and a continuous sensor still
 * reads "near" for anything pressed against the glass without treating a hand hovering some
 * distance away as a pocket.
 */
private fun isNear(value: Float?, maximumRange: Float): Boolean {
    if (value == null) return false
    val threshold = if (maximumRange > 0f) minOf(maximumRange, NEAR_THRESHOLD_CM) else NEAR_THRESHOLD_CM
    return value < threshold
}

private const val NEAR_THRESHOLD_CM = 5f
