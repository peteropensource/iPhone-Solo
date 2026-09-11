// Checks TiltFoldMath.kt against docs/ALGORITHM.md without any Android tooling.
//
// The Gradle unit tests in tiltfold/src/test cover the same ground and more, but they need a JDK,
// Gradle and the Android SDK. This file needs only kotlinc, so the arithmetic can be verified on
// a machine that cannot build the Android module at all. Run it with ../verify-math.sh.
//
// It lives outside the library's source set on purpose, so Gradle never compiles it.

import dev.tiltfold.*

var failures = 0
fun check(name: String, got: Double, want: Double, tol: Double = 0.005) {
    val ok = Math.abs(got - want) < tol
    if (!ok) failures++
    println((if (ok) "ok    " else "FAIL  ") + name.padEnd(22) +
            String.format("%10.4f   expected %.4f", got, want))
}

fun main() {
    // ALGORITHM.md section 10: 402 x 874, left root, roll -26, defaults.
    val W = 402.0; val H = 874.0
    val roll = -26.0; val deadzone = 3.0; val blackAt = 75.0; val softness = 1.0
    val tilt = clamp(Math.abs(roll) - deadzone, 0.0, 90.0)
    val progress = clamp(tilt / (blackAt - deadzone), 0.0, 1.0)
    check("tilt", tilt, 23.0)
    check("progress", progress, 0.3194)

    val g = FoldGeometry(W, H, Vector2.LEFT, progress, softness)
    check("front", g.front, 0.3611)
    check("phase right edge", g.phase(Vector2(W, H / 2)), 0.6389)
    check("phase centre", g.phase(Vector2(W / 2, H / 2)), 0.1389)
    check("hinge.x (left root)", g.hinge.x, 0.0)

    val c = DissolveCurves(4)
    check("blurIndex right", c.blurIndex(g.phase(Vector2(W, H / 2))), 2.9974)
    check("alpha right", c.alpha(g.phase(Vector2(W, H / 2))), 0.5238)

    // Section 5 endpoints
    val rest = FoldGeometry(W, H, Vector2.LEFT, 0.0, 1.0)
    check("phase at rest u=1", rest.phase(Vector2(W, H / 2)), 0.0)
    val full = FoldGeometry(W, H, Vector2.LEFT, 1.0, 1.0)
    check("phase full u=0 (root)", full.phase(Vector2(0.0, H / 2)), 1.0)

    // Section 7 ladder, must match Swift and the web exactly
    check("ladder L1", BlurLadder.sigmaFraction(1, 4), 0.0132, 0.0002)
    check("ladder L2", BlurLadder.sigmaFraction(2, 4), 0.0443, 0.0002)
    check("ladder L3", BlurLadder.sigmaFraction(3, 4), 0.0900, 0.0002)
    check("ladder resolution-free",
          BlurLadder.sigma(3, 4, 400.0) / BlurLadder.sigma(3, 4, 100.0), 4.0)

    // Section 2: Android gravity points away from the ground, opposite to iOS.
    check("roll flat", rollFromGravity(0.0, 0.0, 9.81), 0.0)
    check("roll pitched only", rollFromGravity(0.0, 9.0, 3.9), 0.0)
    check("roll left edge down", rollFromGravity(4.905, 0.0, 8.496), -30.0, 0.05)
    check("roll right edge down", rollFromGravity(-4.905, 0.0, 8.496), 30.0, 0.05)

    val rootLeft = directionForRoll(-30.0)
    check("direction left root", rootLeft.x, -1.0)
    check("direction right root", directionForRoll(30.0).x, 1.0)

    println(if (failures == 0) "\nALL MATCH ALGORITHM.md" else "\n$failures MISMATCHES")
    if (failures > 0) System.exit(1)
}
