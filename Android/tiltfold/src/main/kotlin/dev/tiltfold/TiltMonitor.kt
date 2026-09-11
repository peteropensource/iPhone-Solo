package dev.tiltfold

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Publishes the device's roll: how far it is tilted left or right around its long axis, measured
 * from lying flat.
 *
 * Negative means the left edge is lower, positive means the right edge is lower. Front-to-back
 * pitch is deliberately ignored, because a phone held in the hand is almost never at zero pitch
 * and reacting to it makes the effect fire constantly. Set [omnidirectional] to opt in.
 *
 * [roll] and [direction] are Compose state, so reading them in a composable subscribes to them.
 * Both are writable: on a device with no sensors, and in previews and tests, assign [roll]
 * yourself and the effect follows.
 *
 * ### The sign convention, and how it differs from the spec
 *
 * Android's axes match the spec's: `+x` right, `+y` toward the top of the screen, `+z` out of the
 * screen. What differs is the *sense* of the reported vector. CoreMotion's `gravity`, which the
 * spec is written against, points the way gravity pulls, so a device lying flat face up reads
 * `(0, 0, -1)`. Android's `TYPE_GRAVITY` reports the opposite — the direction away from the
 * ground — so the same device reads `(0, 0, +9.81)`. The spec's `roll = atan2(gx, -gz)` therefore
 * becomes `roll = atan2(-gx, gz)` here. See [rollFromGravity], which is where it actually lives
 * and where it is unit tested.
 *
 * ### Threads
 *
 * `SensorManager.registerListener` without an explicit `Handler` delivers on the main looper, so
 * writing Compose state straight from [onSensorChanged] is correct.
 */
class TiltMonitor(private val sensorManager: SensorManager?) : SensorEventListener {

    /**
     * Signed roll in degrees. Drive this yourself when [isAvailable] is false, or whenever you
     * want the effect keyed to something other than gravity.
     */
    var roll: Float by mutableStateOf(0f)

    /** Unit vector pointing at the root. Always horizontal unless [omnidirectional] is set. */
    var direction: Vector2 by mutableStateOf(Vector2.RIGHT)
        private set

    /** React to tilt in any direction rather than only left and right. */
    var omnidirectional: Boolean = false

    /**
     * One-pole smoothing applied to the gravity vector, per sample. Higher is snappier, lower is
     * calmer. Raw values shimmer noticeably on a device sitting still.
     */
    var smoothing: Double
        get() = smoother.smoothing
        set(value) {
            smoother.smoothing = value
        }

    /**
     * The sensor this monitor will use, resolved once. `TYPE_GRAVITY` is a fused, gravity-only
     * signal and is what we want; `TYPE_ACCELEROMETER` is the fallback for devices that do not
     * offer it, and it still contains whatever the hand is doing, so it gets heavier smoothing.
     */
    private val sensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** True when this device can report something usable. */
    val isAvailable: Boolean = sensor != null

    /** True when we fell back to the raw accelerometer, which is noisier. */
    val usingAccelerometerFallback: Boolean = sensor?.type == Sensor.TYPE_ACCELEROMETER

    private val smoother = GravitySmoother(
        smoothing = if (sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            GravitySmoother.ACCELEROMETER_SMOOTHING
        } else {
            GravitySmoother.DEFAULT_SMOOTHING
        }
    )

    private var listening = false
    private var seeded = false

    /** Human readable name of the edge acting as the root. Handy in a demo, useless in shipping code. */
    val rootEdgeName: String
        get() {
            if (!omnidirectional) return if (roll < 0f) "left" else "right"
            var degrees = kotlin.math.atan2(direction.y, direction.x) * DEGREES_PER_RADIAN
            if (degrees < 0) degrees += 360.0
            return when {
                degrees < 45.0 -> "right"
                degrees < 135.0 -> "bottom"
                degrees < 225.0 -> "left"
                degrees < 315.0 -> "top"
                else -> "right"
            }
        }

    /** Begin publishing. Safe to call more than once. */
    fun start() {
        val manager = sensorManager ?: return
        val target = sensor ?: return
        if (listening) return
        // SENSOR_DELAY_GAME is about 50 Hz, close enough to the spec's 60 Hz assumption for the
        // one-pole constant, and far cheaper than SENSOR_DELAY_FASTEST.
        listening = manager.registerListener(this, target, SensorManager.SENSOR_DELAY_GAME)
    }

    /** Stop publishing. Safe to call when not started. */
    fun stop() {
        if (!listening) return
        sensorManager?.unregisterListener(this)
        listening = false
        // The next start() should not drift in from a stale vector.
        seeded = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val values = event?.values ?: return
        if (values.size < 3) return
        ingest(values[0].toDouble(), values[1].toDouble(), values[2].toDouble())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Nothing to do: a low-accuracy gravity reading is still a perfectly good direction.
    }

    /**
     * Feed one gravity sample in Android's convention: `+x` right, `+y` toward the top of the
     * screen, `+z` out of the screen, pointing away from the ground, so a device lying flat face
     * up is approximately `(0, 0, +9.81)`. Magnitude is ignored; only the direction matters.
     *
     * Exposed so you can drive it from your own source, or from a test.
     */
    fun ingest(gx: Double, gy: Double, gz: Double) {
        val sample = Gravity(gx, gy, gz)
        // The first sample jumps straight in. Filtering from the assumed flat vector would make
        // the effect swing in from zero every time the app returns to the foreground.
        val g = if (seeded) {
            smoother.update(sample)
        } else {
            seeded = true
            smoother.reset(sample)
            sample
        }

        if (omnidirectional) {
            roll = omnidirectionalTilt(g.x, g.y, g.z).toFloat()
            omnidirectionalDirection(g.x, g.y, g.z)?.let { direction = it }
        } else {
            val degrees = rollFromGravity(g.x, g.y, g.z)
            roll = degrees.toFloat()
            direction = directionForRoll(degrees)
        }
    }
}

/**
 * A [TiltMonitor] tied to the current composition, registered while the lifecycle is started and
 * unregistered when it stops or the composable leaves.
 *
 * ```kotlin
 * val tilt = rememberTiltMonitor()
 * TiltFold(roll = tilt.roll) { MyScreen() }
 * ```
 *
 * On a device with no gravity or accelerometer sensor this still returns a monitor;
 * [TiltMonitor.isAvailable] is false and [TiltMonitor.roll] stays at whatever you assign.
 */
@Composable
fun rememberTiltMonitor(
    omnidirectional: Boolean = false,
    smoothing: Double = GravitySmoother.DEFAULT_SMOOTHING
): TiltMonitor {
    val context = LocalContext.current
    val monitor = remember(context) {
        TiltMonitor(context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
    }

    // Applied in a SideEffect so a recomposition that changes the tuning does not mutate the
    // monitor while composition is still in flight.
    SideEffect {
        monitor.omnidirectional = omnidirectional
        monitor.smoothing = smoothing
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, monitor) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> monitor.start()
                Lifecycle.Event.ON_STOP -> monitor.stop()
                else -> Unit
            }
        }
        // addObserver replays whatever events are needed to catch the new observer up with the
        // current state, so a lifecycle that is already started calls start() immediately.
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            monitor.stop()
        }
    }

    return monitor
}
