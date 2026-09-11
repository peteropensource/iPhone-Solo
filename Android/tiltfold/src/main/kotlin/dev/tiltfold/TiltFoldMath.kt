package dev.tiltfold

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The whole of the TiltFold algorithm as plain Kotlin.
 *
 * Everything in this file is a faithful transcription of `docs/ALGORITHM.md` and deliberately
 * has no Android, no Compose and no framework imports of any kind: it compiles with `kotlinc`
 * on its own and can be unit tested on the JVM without an emulator. If the effect ever looks
 * wrong, this file is where you check the numbers; the Compose layer in `TiltFold.kt` only
 * turns these numbers into draw calls.
 *
 * Everything here is in `Double`. The Compose layer converts to `Float` at the boundary, which
 * is where the platform wants floats anyway.
 *
 * Coordinates are viewport coordinates with `+y` pointing **down**, matching both the spec
 * (section 3) and Compose's drawing space.
 */

// ---------------------------------------------------------------------------------------------
// Small helpers (spec section 6)
// ---------------------------------------------------------------------------------------------

/** The usual clamp. Present as a top-level function so the rest of the file reads like the spec. */
fun clamp(x: Double, lower: Double, upper: Double): Double = min(max(x, lower), upper)

/** Radians to degrees. Spelled out rather than reaching for `java.lang.Math`, to keep this file JVM-agnostic. */
const val DEGREES_PER_RADIAN: Double = 180.0 / PI

/**
 * The usual Hermite ramp: 0 at or below [edge0], 1 at or above [edge1], smooth in between.
 *
 * `t = clamp((x - edge0) / (edge1 - edge0), 0, 1)`, result `t * t * (3 - 2t)`.
 */
fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
    if (edge1 == edge0) return if (x < edge0) 0.0 else 1.0
    val t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}

/**
 * A 2D vector in viewport coordinates, `+y` down.
 *
 * Used for both directions and points; the spec treats them interchangeably and keeping one
 * type keeps the arithmetic below readable.
 */
data class Vector2(val x: Double, val y: Double) {

    val length: Double get() = sqrt(x * x + y * y)

    /** Normalised copy. Returns `(1, 0)` for a degenerate vector so callers never divide by zero. */
    fun normalized(): Vector2 {
        val l = length
        return if (l > 1e-9) Vector2(x / l, y / l) else Vector2(1.0, 0.0)
    }

    operator fun plus(other: Vector2) = Vector2(x + other.x, y + other.y)
    operator fun minus(other: Vector2) = Vector2(x - other.x, y - other.y)
    operator fun times(scalar: Double) = Vector2(x * scalar, y * scalar)

    fun dot(other: Vector2): Double = x * other.x + y * other.y

    companion object {
        val LEFT = Vector2(-1.0, 0.0)
        val RIGHT = Vector2(1.0, 0.0)
    }
}

// ---------------------------------------------------------------------------------------------
// FoldGeometry (spec sections 4 and 5)
// ---------------------------------------------------------------------------------------------

/**
 * All the geometry of the effect, for one frame, in one value.
 *
 * This is the sibling of `FoldGeometry.swift`. It is deliberately not a `data class`: the
 * constructor normalises [direction] and derives four more values from its inputs, which a data
 * class cannot do while keeping a useful `copy()`. It is cheap enough to rebuild every frame.
 *
 * @param width viewport width, in whatever unit you intend to draw in (this port passes pixels).
 * @param height viewport height, same unit.
 * @param direction points at the root, the edge that is currently lowest. Need not be normalised.
 * @param progress 0 leaves the content untouched, 1 dissolves all of it.
 * @param softness ramp length in units of the root-to-far-edge distance.
 */
class FoldGeometry(
    val width: Double,
    val height: Double,
    direction: Vector2,
    val progress: Double,
    val softness: Double
) {

    /** Unit vector pointing at the root. */
    val direction: Vector2 = direction.normalized()

    /** Distance from the viewport centre to the farthest point along [direction]. `E` in the spec. */
    val halfExtent: Double =
        abs(this.direction.x) * width / 2.0 + abs(this.direction.y) * height / 2.0

    /** Viewport centre. `C` in the spec. */
    val centre: Vector2 = Vector2(width / 2.0, height / 2.0)

    /**
     * Leading position of the dissolve front, in units of `u` (0 at the root, 1 at the far edge).
     *
     * `front = 1 - progress * (1 + softness)`. The `(1 + softness)` factor is what lets the
     * trailing end of the ramp clear the root at `progress == 1`; drop it and the root never
     * finishes dissolving.
     */
    val front: Double = 1.0 - progress * (1.0 + softness)

    /** Locus of dissolve phase 0, in viewport coordinates. */
    val gradientStart: Vector2 = pointAt(front, this.direction, halfExtent, width, height)

    /** Locus of dissolve phase 1, in viewport coordinates. */
    val gradientEnd: Vector2 = pointAt(front + softness, this.direction, halfExtent, width, height)

    /** The point on the hinge line, in viewport coordinates. The content pivots around this. */
    val hinge: Vector2
        get() = Vector2(
            width / 2.0 + direction.x * halfExtent,
            height / 2.0 + direction.y * halfExtent
        )

    /**
     * The hinge as a fraction of the viewport, which is the form Compose's `transformOrigin`
     * wants. `(0, 0.5)` for a left root, `(1, 0.5)` for a right root.
     */
    val hingeFraction: Vector2
        get() = Vector2(
            if (width > 0) hinge.x / width else 0.5,
            if (height > 0) hinge.y / height else 0.5
        )

    /**
     * The point at normalised distance [u] from the root, in viewport coordinates.
     * `u = 0` is the low edge, `u = 1` is the high edge.
     */
    fun point(u: Double): Vector2 = pointAt(u, direction, halfExtent, width, height)

    /**
     * Dissolve phase at a viewport point: 0 untouched, 1 fully dissolved.
     *
     * This returns exactly the value the gradient mask produces at [point], which is what lets an
     * overlay dissolve in lockstep with the content underneath it (spec section 5).
     */
    fun phase(at: Vector2): Double {
        val a = gradientEnd - gradientStart
        val lengthSquared = a.dot(a)
        if (lengthSquared <= 1e-9) return 0.0
        return clamp((at - gradientStart).dot(a) / lengthSquared, 0.0, 1.0)
    }

    private companion object {
        /** `point(u) = C + d * (1 - 2u) * E`, spec section 5. */
        fun pointAt(
            u: Double,
            direction: Vector2,
            halfExtent: Double,
            width: Double,
            height: Double
        ): Vector2 {
            val t = 1.0 - 2.0 * u // +1 at the low corner, -1 at the high corner
            return Vector2(
                width / 2.0 + direction.x * t * halfExtent,
                height / 2.0 + direction.y * t * halfExtent
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// DissolveCurves (spec section 6)
// ---------------------------------------------------------------------------------------------

/**
 * The two response curves, as functions of the dissolve phase `s`.
 *
 * The important property is that blur finishes early (by `s = 0.65`) while opacity starts late
 * (at `s = 0.3`), so content is already fully out of focus before it begins to disappear. Make
 * the two overlap evenly and the effect collapses into an ordinary crossfade.
 *
 * @param levels number of steps in the blur ladder, including the sharp original.
 * @param sampleCount how many stops to emit when converting a curve into a gradient.
 */
data class DissolveCurves(val levels: Int = 4, val sampleCount: Int = 24) {

    /** [levels] is clamped here rather than in the constructor so `copy()` keeps working. */
    private val n: Int get() = max(2, levels)

    /** Fractional position in the blur ladder: 0 is sharp, `levels - 1` is blurriest. */
    fun blurIndex(s: Double): Double = (n - 1).toDouble() * smoothstep(0.0, 0.65, s)

    /** Opacity over the background. */
    fun alpha(s: Double): Double = 1.0 - smoothstep(0.3, 1.0, s)

    /**
     * Visibility of blur level [index].
     *
     * Levels are stacked blurriest first, sharp last. Level `i` is fully visible while
     * `blurIndex <= i` and fades out as `blurIndex` travels from `i` to `i + 1`. Under ordinary
     * source-over compositing that is an exact cross-fade between neighbouring levels.
     */
    fun levelMask(index: Int, s: Double): Double =
        1.0 - clamp(blurIndex(s) - index.toDouble(), 0.0, 1.0)

    /**
     * [sampleCount] + 1 evenly spaced samples of [levelMask] for [index], from `s = 0` to `s = 1`.
     *
     * The drawing layer turns these into gradient colour stops, which is how the per-pixel field
     * gets evaluated on the GPU rather than by us.
     */
    fun levelMaskSamples(index: Int): DoubleArray = sample { levelMask(index, it) }

    /** [sampleCount] + 1 evenly spaced samples of [alpha]. */
    fun alphaSamples(): DoubleArray = sample { alpha(it) }

    private inline fun sample(curve: (Double) -> Double): DoubleArray {
        val count = max(1, sampleCount)
        return DoubleArray(count + 1) { step -> curve(step.toDouble() / count.toDouble()) }
    }
}

// ---------------------------------------------------------------------------------------------
// BlurLadder (spec section 7)
// ---------------------------------------------------------------------------------------------

/**
 * Sigma values for the blur ladder, shared by every implementation.
 *
 * Sigma is expressed as a fraction of the picture's width so the effect looks identical at any
 * resolution. Spacing is geometric: linear spacing wastes levels at the sharp end, where the eye
 * is most sensitive to a step between neighbours, and leaves a visible jump at the blurry end.
 */
object BlurLadder {

    /** Blur of the last, blurriest level, as a fraction of picture width. */
    const val MAX_SIGMA_FRACTION: Double = 0.09

    /** Curvature of the ramp. 1 would be linear; higher pushes levels toward the sharp end. */
    const val SPACING_EXPONENT: Double = 1.75

    /**
     * Sigma for [level] of [levels], as a fraction of picture width.
     *
     * Level 0 is always the sharp original. For the default 4 levels this yields approximately
     * 0, 0.0132, 0.0443 and 0.0900.
     */
    fun sigmaFraction(level: Int, levels: Int): Double {
        if (level <= 0 || levels <= 1) return 0.0
        val t = min(level, levels - 1).toDouble() / (levels - 1).toDouble()
        return MAX_SIGMA_FRACTION * t.pow(SPACING_EXPONENT)
    }

    /** Sigma for [level] of [levels], given the width the picture is drawn at. */
    fun sigma(level: Int, levels: Int, pictureWidth: Double): Double =
        sigmaFraction(level, levels) * pictureWidth

    /**
     * Sigma for a fractional ladder position, interpolating between neighbours.
     *
     * The single-pass [dev.tiltfold.tiltFold] modifier uses this to pick one blur that matches
     * where the pre-rendered ladder would have been at that phase.
     */
    fun sigma(index: Double, levels: Int, pictureWidth: Double): Double {
        if (levels <= 1) return 0.0
        val i = clamp(index, 0.0, (levels - 1).toDouble())
        val low = floor(i).toInt()
        val high = min(low + 1, levels - 1)
        val f = i - low.toDouble()
        val a = sigmaFraction(low, levels)
        val b = sigmaFraction(high, levels)
        return (a + (b - a) * f) * pictureWidth
    }

    /**
     * Transparent margin kept around a blurred level so its feathered edge is not cut off
     * (spec section 8). Three sigma captures essentially all of a Gaussian's energy.
     */
    fun margin(sigma: Double): Double = ceil(3.0 * sigma)
}

// ---------------------------------------------------------------------------------------------
// Gravity (spec section 2 and section 9), with Android's sign convention
// ---------------------------------------------------------------------------------------------

/** A gravity sample in the device frame. Only its direction matters; magnitude is ignored. */
data class Gravity(val x: Double, val y: Double, val z: Double) {
    companion object {
        /** A device lying flat, face up, as Android reports it. */
        val FLAT_FACE_UP = Gravity(0.0, 0.0, 1.0)
    }
}

/**
 * Signed roll **in degrees** from an Android gravity vector.
 *
 * Negative means the left edge is lower, positive means the right edge is lower. Front-to-back
 * pitch is deliberately ignored: `gy` does not appear, and that is the whole of what restricts
 * the effect to left and right.
 *
 * **Sign convention, and how it differs from the spec.** The spec (section 2) writes
 * `roll = atan2(gx, -gz)` for iOS, where CoreMotion's `gravity` is the direction gravity
 * *pulls*, so a device lying flat face up reads `(0, 0, -1)`. Android's `TYPE_GRAVITY` (and
 * `TYPE_ACCELEROMETER` at rest) reports the opposite vector — the one pointing away from the
 * ground — so the same device reads `(0, 0, +9.81)`. The axes themselves agree between the two
 * platforms (`+x` right, `+y` toward the top of the screen, `+z` out of the screen); only the
 * vector's sense is flipped. Substituting `g_ios = -g_android` into the spec's formula gives:
 *
 * ```
 * roll = atan2(-gx, gz)
 * ```
 *
 * Check it by hand: tip the **left** edge down and the device's `+x` axis swings upward, so the
 * away-from-ground vector gains a positive `x`; at 30 degrees the sample is `(0.5, 0, 0.866)` and
 * this returns `-30`, which is the negative roll the spec asks for.
 */
@Suppress("UNUSED_PARAMETER") // gy is taken and ignored on purpose; see above.
fun rollFromGravity(gx: Double, gy: Double, gz: Double): Double = atan2(-gx, gz) * DEGREES_PER_RADIAN

/** Convenience overload. */
fun rollFromGravity(gravity: Gravity): Double = rollFromGravity(gravity.x, gravity.y, gravity.z)

/**
 * Unit vector pointing at the root for the left/right case: left for a negative roll, right for a
 * positive one (spec section 3, `direction`).
 */
fun directionForRoll(roll: Double): Vector2 = if (roll < 0) Vector2.LEFT else Vector2.RIGHT

/**
 * Tilt away from flat **in degrees**, ignoring direction, for the omnidirectional variation
 * (spec section 9). Always non-negative; [omnidirectionalDirection] carries the sense.
 *
 * Spec: `tilt = atan2(hypot(gx, gy), -gz)`, with the same `-gz` sign flip as [rollFromGravity].
 */
fun omnidirectionalTilt(gx: Double, gy: Double, gz: Double): Double =
    atan2(sqrt(gx * gx + gy * gy), gz) * DEGREES_PER_RADIAN

/**
 * Unit vector pointing at the root for the omnidirectional variation, or `null` when the device
 * is too close to flat for the direction to be meaningful (the spec's `planar > 0.02` guard,
 * applied to the normalised vector so it does not depend on whether gravity arrives as 1 or 9.81).
 *
 * Spec: `direction = (gx / planar, -gy / planar)`, again with both components negated for
 * Android's flipped vector, which leaves `(-gx, +gy) / planar`. The `y` flip that survives is the
 * spec's own: sensor `+y` points at the top of the screen, drawing `+y` points down.
 */
fun omnidirectionalDirection(gx: Double, gy: Double, gz: Double): Vector2? {
    val magnitude = sqrt(gx * gx + gy * gy + gz * gz)
    if (magnitude <= 1e-9) return null
    val nx = gx / magnitude
    val ny = gy / magnitude
    val planar = sqrt(nx * nx + ny * ny)
    if (planar <= 0.02) return null
    return Vector2(-nx / planar, ny / planar)
}

/**
 * The one-pole smoother from spec section 2.
 *
 * `smoothed = smoothed * (1 - k) + raw * k`. Without it a device sitting still on a table
 * shimmers, because raw sensor values wobble in the last couple of digits.
 */
fun onePole(previous: Double, sample: Double, k: Double): Double {
    val a = clamp(k, 0.0, 1.0)
    return previous * (1.0 - a) + sample * a
}

/**
 * A one-pole filter over a gravity vector.
 *
 * [DEFAULT_SMOOTHING] is the spec's `k ~= 0.3 at 60 Hz`. Android's `SENSOR_DELAY_GAME` is nearer
 * 50 Hz, which makes the filter very slightly slower in wall-clock terms; the difference is not
 * worth a time-constant conversion, and [smoothing] is public if you disagree.
 */
class GravitySmoother(
    var smoothing: Double = DEFAULT_SMOOTHING,
    initial: Gravity = Gravity.FLAT_FACE_UP
) {

    var value: Gravity = initial
        private set

    /** Jump straight to [sample], skipping the filter. Use it for the first sample. */
    fun reset(sample: Gravity) {
        value = sample
    }

    fun update(sample: Gravity): Gravity {
        value = Gravity(
            onePole(value.x, sample.x, smoothing),
            onePole(value.y, sample.y, smoothing),
            onePole(value.z, sample.z, smoothing)
        )
        return value
    }

    fun update(gx: Double, gy: Double, gz: Double): Gravity = update(Gravity(gx, gy, gz))

    companion object {
        /** Spec section 2: `k ~= 0.3 at 60 Hz`. */
        const val DEFAULT_SMOOTHING: Double = 0.3

        /**
         * A calmer constant for the `TYPE_ACCELEROMETER` fallback, which unlike `TYPE_GRAVITY`
         * still contains whatever the hand is doing and needs more averaging to sit still.
         */
        const val ACCELEROMETER_SMOOTHING: Double = 0.12
    }
}

// ---------------------------------------------------------------------------------------------
// Platform arithmetic that is still pure maths (see TiltFold.kt for how it is used)
// ---------------------------------------------------------------------------------------------

/**
 * How far the hinge drifts when the camera is centred, as a fraction of its own offset from the
 * viewport centre. Multiply by that offset to get the translation that puts it back.
 *
 * `graphicsLayer` places the camera at `transformOrigin` and exposes no separate perspective
 * origin, so pivoting at the hinge drags the vanishing point there too. Spec section 4 steps 3 to
 * 5 want it at the viewport centre. So: pivot at the centre, then translate the layer to put the
 * hinge back on its edge, or the fold reads as a slide.
 *
 * Rotating about the centre swings the hinge (always the near edge) toward the viewer by
 * `E * sin(tilt)` and shrinks its in-plane offset by `cos(tilt)`, so its projected offset becomes
 * `E * cos(tilt) * D / (D - E * sin(tilt))`. What is left over is the drift. The hinge is on the
 * near side whichever edge it is, so this needs no sign of its own.
 *
 * @param halfExtentPx `E` in the spec: centre to the farthest point along the root direction.
 * @param tiltRadians the rotation applied, 0 for flat.
 * @param cameraDistancePx eye distance in pixels, the same number fed to [composeCameraDistance].
 */
fun hingeDriftFraction(
    halfExtentPx: Double,
    tiltRadians: Double,
    cameraDistancePx: Double
): Double {
    val d = max(cameraDistancePx, 1.0)
    val towardViewer = halfExtentPx * kotlin.math.sin(tiltRadians)
    val denominator = max(d - towardViewer, 1.0)
    return 1.0 - kotlin.math.cos(tiltRadians) * d / denominator
}

/**
 * Compose's `graphicsLayer.cameraDistance` is not a distance in pixels. The value is multiplied by
 * the display density before it reaches the platform layer:
 *
 * ```
 * distanceInPixels = cameraDistance * density
 * ```
 *
 * This was established by experiment, not by reading: on a 1080 x 2400 emulator at density 2.625,
 * feeding the value that this function returns for an eye distance of 4800px produces the mild
 * foreshortening the specification asks for, while treating the units as `160 * density` put the
 * camera 30 pixels from a 1080-pixel-wide view and bent the content into an unreadable wedge.
 *
 * Compose's default of `8f` is therefore about 21px at that density, which is only sensible
 * because the default is meant for small, lightly rotated elements rather than whole screens.
 * Anything the size of a screen needs an explicit value, which is exactly what the docs advise.
 */
fun composeCameraDistance(eyeDistancePixels: Double, density: Double): Double {
    if (density <= 0.0) return 8.0
    return max(1e-3, eyeDistancePixels / density)
}
