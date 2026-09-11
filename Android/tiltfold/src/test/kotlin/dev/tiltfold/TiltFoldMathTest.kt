package dev.tiltfold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These lock the implementation to `docs/ALGORITHM.md`. If one of them fails, either the code
 * drifted or the spec changed; fix whichever is wrong, but do not let them disagree.
 *
 * Everything here is plain JVM: no Robolectric, no instrumentation, no device. That is the point
 * of keeping the maths in `TiltFoldMath.kt` with no Android imports.
 */
class TiltFoldMathTest {

    private val tolerance = 1e-4

    // -----------------------------------------------------------------------------------------
    // Section 10, the reference values
    // -----------------------------------------------------------------------------------------

    /**
     * "For a 402 x 874 viewport with the defaults above, a left root and roll = -26 degrees."
     *
     * The spec rounds to two decimal places; these are the same numbers carried further, so a
     * port that agrees with the table to the digit it prints also agrees with these.
     */
    @Test
    fun `section 10 reference values`() {
        val width = 402.0
        val height = 874.0
        val config = TiltFoldConfig() // blackAt 75, deadzone 3, softness 1, levels 4
        val roll = -26.0

        assertEquals(23.0, config.tiltAngle(roll), 1e-9)
        assertEquals(0.3194, config.progress(roll), tolerance)

        val geometry = FoldGeometry(
            width = width,
            height = height,
            direction = Vector2.LEFT, // a left root, so u = 0 is the left edge
            progress = config.progress(roll),
            softness = config.safeSoftness
        )

        assertEquals(0.3611, geometry.front, tolerance)

        // u is measured from the root, which is the left edge here, so u = 1 is the right edge.
        val rightEdge = Vector2(width, height / 2.0)
        val centre = Vector2(width / 2.0, height / 2.0)
        assertEquals(0.6389, geometry.phase(rightEdge), tolerance)
        assertEquals(0.1389, geometry.phase(centre), tolerance)

        val curves = DissolveCurves(levels = 4)
        assertEquals(2.9974, curves.blurIndex(geometry.phase(rightEdge)), tolerance)
        assertEquals(0.5238, curves.alpha(geometry.phase(rightEdge)), tolerance)
    }

    // -----------------------------------------------------------------------------------------
    // Section 5, the phase field
    // -----------------------------------------------------------------------------------------

    @Test
    fun `nothing dissolves at rest`() {
        val geometry = geometry(progress = 0.0)
        forEachU { u ->
            assertEquals(
                "phase should be zero everywhere before the front starts moving",
                0.0, geometry.phase(pointAtU(u)), 1e-12
            )
        }
    }

    @Test
    fun `everything dissolves at full tilt, including the root`() {
        val geometry = geometry(progress = 1.0)
        forEachU { u ->
            assertEquals(
                "the (1 + softness) factor exists so the root finishes too",
                1.0, geometry.phase(pointAtU(u)), 1e-12
            )
        }
    }

    @Test
    fun `phase increases away from the root`() {
        val geometry = geometry(progress = 0.5)
        var previous = -1.0
        forEachU(step = 0.05) { u ->
            val phase = geometry.phase(pointAtU(u))
            assertTrue("phase must not decrease as u grows", phase >= previous)
            previous = phase
        }
        assertTrue("the far edge must have started dissolving by halfway", previous > 0.0)
    }

    @Test
    fun `root follows the sign of roll`() {
        val left = FoldGeometry(402.0, 874.0, Vector2.LEFT, 0.5, 1.0)
        val right = FoldGeometry(402.0, 874.0, Vector2.RIGHT, 0.5, 1.0)

        assertEquals(0.0, left.hinge.x, 1e-9)
        assertEquals(402.0, right.hinge.x, 1e-9)
        assertEquals(0.0, left.hingeFraction.x, 1e-9)
        assertEquals(1.0, right.hingeFraction.x, 1e-9)

        val farRight = Vector2(402.0, 437.0)
        assertTrue(
            "with the root on the left, the right edge dissolves first",
            left.phase(farRight) > right.phase(farRight)
        )

        assertEquals(Vector2.LEFT, directionForRoll(-26.0))
        assertEquals(Vector2.RIGHT, directionForRoll(26.0))
    }

    @Test
    fun `a degenerate geometry does not divide by zero`() {
        val zero = FoldGeometry(0.0, 0.0, Vector2(0.0, 0.0), 0.5, 1.0)
        assertEquals(0.0, zero.phase(Vector2(0.0, 0.0)), 1e-12)
        assertEquals(0.5, zero.hingeFraction.x, 1e-12)
    }

    // -----------------------------------------------------------------------------------------
    // Section 6, the curves
    // -----------------------------------------------------------------------------------------

    @Test
    fun `blur leads the fade`() {
        val curves = DissolveCurves(levels = 4)
        // At the point where blur is complete, the content must still be clearly visible.
        assertEquals(3.0, curves.blurIndex(0.65), 1e-9)
        assertTrue(
            "content should still be visible once it is fully out of focus",
            curves.alpha(0.65) > 0.4
        )
        assertEquals("the fade has not started yet", 1.0, curves.alpha(0.3), 1e-12)
        assertEquals(0.0, curves.alpha(1.0), 1e-12)
        assertEquals(0.0, curves.blurIndex(0.0), 1e-12)
    }

    @Test
    fun `level masks cross-fade exactly`() {
        val curves = DissolveCurves(levels = 4)
        // Where the ladder sits at 1.7, level 1 is 30% over a solid level 2 and nothing sharper.
        val s = inverseBlurIndex(1.7, curves)
        assertEquals(0.0, curves.levelMask(0, s), 0.02)
        assertEquals(0.3, curves.levelMask(1, s), 0.02)
        assertEquals(1.0, curves.levelMask(2, s), 0.02)
        assertEquals(1.0, curves.levelMask(3, s), 0.02)
    }

    @Test
    fun `level masks sum to full coverage`() {
        // Source-over compositing of the stack must never leave a hole: at any phase the visible
        // levels have to add up to a fully covered pixel before the alpha curve is applied.
        val curves = DissolveCurves(levels = 4)
        for (step in 0..100) {
            val s = step / 100.0
            var covered = 0.0
            for (level in 3 downTo 0) {
                val mask = curves.levelMask(level, s)
                covered = covered * (1 - mask) + mask
            }
            assertEquals("no hole in the stack at s = $s", 1.0, covered, 1e-9)
        }
    }

    @Test
    fun `gradient samples are complete and bounded`() {
        val curves = DissolveCurves(levels = 4, sampleCount = 24)
        val alpha = curves.alphaSamples()
        assertEquals(25, alpha.size)
        assertEquals(1.0, alpha.first(), 1e-12)
        assertEquals(0.0, alpha.last(), 1e-12)
        for (value in alpha) assertTrue(value in 0.0..1.0)

        val level = curves.levelMaskSamples(0)
        assertEquals(25, level.size)
        assertEquals(1.0, level.first(), 1e-12)
    }

    // -----------------------------------------------------------------------------------------
    // Section 7, the ladder
    // -----------------------------------------------------------------------------------------

    @Test
    fun `ladder is monotonic and starts sharp`() {
        assertEquals(0.0, BlurLadder.sigmaFraction(0, 4), 1e-12)
        var previous = -1.0
        for (level in 0 until 4) {
            val sigma = BlurLadder.sigmaFraction(level, 4)
            assertTrue("sigma must increase with level", sigma > previous)
            previous = sigma
        }
        assertEquals(BlurLadder.MAX_SIGMA_FRACTION, previous, 1e-12)

        // The four defaults the spec spells out: 0, 0.0132, 0.0443, 0.09. The first two are
        // rounded in the document, so the tolerance is a rounding tolerance, not a slack one.
        assertEquals(0.0132, BlurLadder.sigmaFraction(1, 4), 1e-4)
        assertEquals(0.0443, BlurLadder.sigmaFraction(2, 4), 1e-4)
        assertEquals(0.0900, BlurLadder.sigmaFraction(3, 4), 1e-12)
    }

    @Test
    fun `ladder spacing is geometric`() {
        // Each gap should be wider than the one before it, so the fine end gets the resolution.
        val sigmas = (0 until 5).map { BlurLadder.sigmaFraction(it, 5) }
        val gaps = sigmas.zipWithNext { a, b -> b - a }
        for ((previous, next) in gaps.zipWithNext()) {
            assertTrue("spacing must widen toward the blurry end", next > previous)
        }
    }

    @Test
    fun `ladder is resolution independent`() {
        val small = BlurLadder.sigma(3, 4, 100.0)
        val large = BlurLadder.sigma(3, 4, 400.0)
        assertEquals("the effect must look identical at any resolution", small * 4, large, 1e-9)
    }

    @Test
    fun `fractional sigma interpolates between levels`() {
        val width = 400.0
        val low = BlurLadder.sigma(1, 4, width)
        val high = BlurLadder.sigma(2, 4, width)
        assertEquals((low + high) / 2, BlurLadder.sigma(1.5, 4, width), 1e-9)

        assertEquals(0.0, BlurLadder.sigma(-5.0, 4, width), 1e-12)
        assertEquals(BlurLadder.sigma(3, 4, width), BlurLadder.sigma(99.0, 4, width), 1e-12)
    }

    @Test
    fun `feather margin covers three sigma`() {
        assertEquals(0.0, BlurLadder.margin(0.0), 1e-12)
        assertEquals(30.0, BlurLadder.margin(10.0), 1e-12)
        assertEquals(4.0, BlurLadder.margin(1.1), 1e-12) // ceil(3.3)
    }

    // -----------------------------------------------------------------------------------------
    // Section 2, the sensor
    // -----------------------------------------------------------------------------------------

    @Test
    fun `pitch does not leak into roll`() {
        // Android convention throughout: the vector points away from the ground, so a device
        // lying flat face up reads (0, 0, +9.81).
        assertEquals(0.0, rollFromGravity(0.0, 0.0, 9.81), 1e-9)

        // Pitched back 45 degrees, not rolled at all. gy is large and must change nothing.
        assertEquals(
            "pitch must not drive the effect",
            0.0, rollFromGravity(0.0, -6.94, 6.94), 1e-9
        )
        assertEquals(0.0, rollFromGravity(0.0, 6.94, 6.94), 1e-9)
    }

    @Test
    fun `tipping the left edge down gives a negative roll`() {
        // Left edge down by 30 degrees: the device's +x axis swings up, so the away-from-ground
        // vector gains a positive x component.
        assertEquals(-30.0, rollFromGravity(0.5, 0.0, 0.866025), 1e-3)
        assertEquals(30.0, rollFromGravity(-0.5, 0.0, 0.866025), 1e-3)

        // Magnitude is irrelevant; only the direction is used.
        assertEquals(-30.0, rollFromGravity(4.905, 0.0, 8.4959), 1e-3)

        assertEquals(Vector2.LEFT, directionForRoll(rollFromGravity(0.5, 0.0, 0.866025)))
        assertEquals(Vector2.RIGHT, directionForRoll(rollFromGravity(-0.5, 0.0, 0.866025)))
    }

    @Test
    fun `the omnidirectional variation picks the lowest edge`() {
        // Bottom edge down: the device's +y axis (toward the top of the screen) swings up.
        val tilt = omnidirectionalTilt(0.0, 0.5, 0.866025)
        assertEquals(30.0, tilt, 1e-3)

        val direction = omnidirectionalDirection(0.0, 0.5, 0.866025)
        assertNotNull(direction)
        assertEquals(0.0, direction!!.x, 1e-9)
        // Drawing +y is down, so a root at the bottom of the screen is +1.
        assertEquals(1.0, direction.y, 1e-9)

        // Too close to flat for a direction to mean anything.
        assertNull(omnidirectionalDirection(0.0, 0.0, 9.81))
        assertNull(omnidirectionalDirection(0.0, 0.0, 0.0))
    }

    @Test
    fun `the one-pole filter converges and damps`() {
        val smoother = GravitySmoother(smoothing = 0.3, initial = Gravity.FLAT_FACE_UP)
        val target = Gravity(0.5, 0.0, 0.866025)

        val afterOne = rollFromGravity(smoother.update(target))
        assertTrue("one sample must not jump the whole way", afterOne > -30.0)
        assertTrue("but it must move", afterOne < 0.0)

        repeat(120) { smoother.update(target) }
        assertEquals("and it must converge", -30.0, rollFromGravity(smoother.value), 0.1)

        // reset() skips the filter, which is what the first sample after start() does.
        smoother.reset(target)
        assertEquals(-30.0, rollFromGravity(smoother.value), 1e-3)
    }

    // -----------------------------------------------------------------------------------------
    // Section 3, the derived scalars
    // -----------------------------------------------------------------------------------------

    @Test
    fun `deadzone and clamping`() {
        val config = TiltFoldConfig(blackAt = 75.0, deadzone = 3.0)
        assertEquals(0.0, config.progress(2.0), 1e-12)
        assertEquals(0.0, config.progress(-2.0), 1e-12)
        assertEquals(1.0, config.progress(75.0), 1e-12)
        assertEquals(1.0, config.progress(-90.0), 1e-12)
        assertEquals(0.5, config.progress(-39.0), 1e-12)

        assertEquals(0.0, config.tiltAngle(3.0), 1e-12)
        assertEquals(90.0, config.tiltAngle(180.0), 1e-12) // clamped at 90
        assertEquals(Math.PI / 4, config.tiltRadians(48.0), 1e-9)
    }

    @Test
    fun `presets differ from the defaults in the documented way`() {
        assertEquals(88.0, TiltFoldConfig.subtle.blackAt, 1e-12)
        assertEquals(1.4, TiltFoldConfig.subtle.softness, 1e-12)
        assertEquals(50.0, TiltFoldConfig.dramatic.blackAt, 1e-12)
        assertEquals(1.2, TiltFoldConfig.dramatic.eyeDistance, 1e-12)
        assertEquals(TiltFoldConfig(), TiltFoldConfig.default)

        // Hostile values are floored rather than allowed to produce a degenerate gradient.
        assertEquals(TiltFoldConfig.MIN_SOFTNESS, TiltFoldConfig(softness = -5.0).safeSoftness, 1e-12)
        assertEquals(2, TiltFoldConfig(levels = 0).safeLevels)
        assertEquals(0.1, TiltFoldConfig(eyeDistance = 0.0).safeEyeDistance, 1e-12)
    }

    // -----------------------------------------------------------------------------------------
    // Section 4, the transform, as far as it survives the platform
    // -----------------------------------------------------------------------------------------

    @Test
    fun `camera distance lands near Compose's own default`() {
        // A 402 x 874 dp viewport at 3x, with the default eyeDistance of 2.
        val density = 3.0
        val maxDimensionPx = 874.0 * density
        val distance = composeCameraDistance(2.0 * maxDimensionPx, density)

        // Compose's DefaultCameraDistance is 8, which under the same relation means 1280dp.
        // Ours works out at about 10.9, i.e. 1748dp, which is 2 x 874dp as the spec asks.
        assertEquals(10.925, distance, 0.01)
        assertEquals(
            "the round trip must give back the spec's distance in dp",
            1748.0, distance * COMPOSE_CAMERA_DISTANCE_UNIT, 0.1
        )
        assertTrue("a sane camera distance is the same order as Compose's default", distance in 1.0..100.0)
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    private fun geometry(progress: Double) =
        FoldGeometry(402.0, 874.0, Vector2.LEFT, progress, 1.0)

    /** A point at normalised distance `u` from the root, for a left root on a 402 x 874 viewport. */
    private fun pointAtU(u: Double) = Vector2(402.0 * u, 437.0)

    private inline fun forEachU(step: Double = 0.1, body: (Double) -> Unit) {
        var u = 0.0
        while (u <= 1.0 + 1e-9) {
            body(minOf(u, 1.0))
            u += step
        }
    }

    private fun inverseBlurIndex(target: Double, curves: DissolveCurves): Double {
        var low = 0.0
        var high = 1.0
        repeat(60) {
            val mid = (low + high) / 2
            if (curves.blurIndex(mid) < target) low = mid else high = mid
        }
        return (low + high) / 2
    }
}
