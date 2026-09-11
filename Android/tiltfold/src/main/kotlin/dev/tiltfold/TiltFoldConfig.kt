package dev.tiltfold

import kotlin.math.abs
import kotlin.math.max

/**
 * Tuning for the effect. Every value has a sensible default; you can ship without touching this.
 *
 * See `docs/ALGORITHM.md` section 2 for what each one means geometrically. Like `TiltFoldMath.kt`
 * this file is plain Kotlin with no Android imports, so it can be unit tested on the JVM.
 *
 * Angles are in **degrees**, because Kotlin has no `Angle` type and half the platform (sensor
 * output, `graphicsLayer.rotationY`) is in degrees anyway. The Swift sibling uses `Angle` and can
 * afford to be unit-agnostic; here every name that carries an angle says so.
 *
 * Values are not clamped in the constructor. A `data class` cannot normalise its own properties
 * without giving up `copy()`, so instead the maths below reads the [safeSoftness], [safeLevels]
 * and [safeEyeDistance] accessors, which is also what the drawing layer uses.
 */
data class TiltFoldConfig(

    /**
     * Tilt in degrees at which the content has completely dissolved. Larger means the content
     * survives further into the tilt. Spec range 30 to 90.
     */
    val blackAt: Double = 75.0,

    /**
     * Tilt in degrees below which nothing happens at all. This hides sensor noise: without it a
     * device resting on a table shimmers. Set it to zero when driving `roll` manually.
     */
    val deadzone: Double = 3.0,

    /**
     * Length of the dissolve front, in units of the root-to-far-edge distance. 1.0 means the ramp
     * spans the whole picture; smaller values give a tighter, more visible wavefront.
     */
    val softness: Double = 1.0,

    /**
     * Viewer distance from the screen plane, as a multiple of the longer viewport side. Smaller
     * means stronger perspective.
     */
    val eyeDistance: Double = 2.0,

    /**
     * Number of steps in the blur ladder, including the sharp original. Three is enough for small
     * content; four is the default; more costs proportionally more compositing.
     *
     * In this port the content composable is invoked once per level, so this is also how many
     * times your content is composed. See the note on `TiltFold` about hoisting state.
     */
    val levels: Int = 4,

    /**
     * When true the effect responds to tilt in any direction and the root can be any edge or
     * corner. Spectacular in a demo, hard to hold still in real use, so it is off by default.
     */
    val omnidirectional: Boolean = false
) {

    /** [softness], floored at a value the gradient maths can still use. */
    val safeSoftness: Double get() = max(MIN_SOFTNESS, softness)

    /** [levels], floored at 2, which is the smallest ladder that can cross-fade. */
    val safeLevels: Int get() = max(2, levels)

    /** [eyeDistance], floored so the perspective divide cannot explode. */
    val safeEyeDistance: Double get() = max(0.1, eyeDistance)

    /**
     * Effective tilt **in degrees** for a signed roll, with the deadzone removed and the result
     * clamped to 90. This is what drives the 3D rotation.
     *
     * Note this is degrees where the Swift sibling's `tiltAngle(forRoll:)` returns radians: the
     * value goes straight into `graphicsLayer.rotationY`, which is in degrees. Use
     * [tiltRadians] if you want the spec's units.
     */
    fun tiltAngle(roll: Double): Double = clamp(abs(roll) - deadzone, 0.0, 90.0)

    /** [tiltAngle] in radians, for anyone building the matrix by hand. */
    fun tiltRadians(roll: Double): Double = tiltAngle(roll) / DEGREES_PER_RADIAN

    /**
     * Dissolve progress, 0 (untouched) to 1 (fully gone), for a signed roll in degrees.
     *
     * [tiltAngle] and [progress] are two different numbers from the same input on purpose: it is
     * what lets you tune "how far it leans" independently of "how fast it disappears".
     */
    fun progress(roll: Double): Double {
        val span = max(blackAt - deadzone, 1.0)
        return clamp(tiltAngle(roll) / span, 0.0, 1.0)
    }

    /** Unit vector pointing at the root for this roll: left when negative, right when positive. */
    fun direction(roll: Double): Vector2 = directionForRoll(roll)

    companion object {

        /** Below this the dissolve ramp collapses to a hard line and the gradient degenerates. */
        const val MIN_SOFTNESS: Double = 0.05

        /** The defaults, spelled out for symmetry with [subtle] and [dramatic]. */
        val default = TiltFoldConfig()

        /** Dissolves late and stays legible longer. Good when the content is being read. */
        val subtle = TiltFoldConfig(blackAt = 88.0, softness = 1.4)

        /** Disappears fast and dramatically. Good for a demo or a transition. */
        val dramatic = TiltFoldConfig(blackAt = 50.0, softness = 0.6, eyeDistance = 1.2)
    }
}
