import CoreGraphics
import SwiftUI
import XCTest
@testable import TiltFold

/// These lock the implementation to `docs/ALGORITHM.md`. If one of them fails, either the code
/// drifted or the spec changed; fix whichever is wrong, but do not let them disagree.
final class TiltFoldTests: XCTestCase {

    private let accuracy = 0.005

    // MARK: Section 10, the reference values

    func testReferenceValues() {
        let size = CGSize(width: 402, height: 874)
        let configuration = TiltFoldConfiguration()   // blackAt 75, deadzone 3, softness 1
        let roll = Angle.degrees(-26)

        XCTAssertEqual(configuration.tiltAngle(forRoll: roll) * 180 / .pi, 23, accuracy: accuracy)
        XCTAssertEqual(configuration.progress(forRoll: roll), 0.3194, accuracy: accuracy)

        let geometry = FoldGeometry(size: size,
                                    direction: CGVector(dx: -1, dy: 0),   // left root
                                    progress: configuration.progress(forRoll: roll),
                                    softness: configuration.softness)

        // u is measured from the root, which is the left edge here, so u = 1 is the right edge.
        let rightEdge = CGPoint(x: size.width, y: size.height / 2)
        let centre = CGPoint(x: size.width / 2, y: size.height / 2)
        XCTAssertEqual(geometry.phase(at: rightEdge), 0.6389, accuracy: accuracy)
        XCTAssertEqual(geometry.phase(at: centre), 0.1389, accuracy: accuracy)

        let curves = DissolveCurves(levels: 4)
        XCTAssertEqual(curves.blurIndex(geometry.phase(at: rightEdge)), 3.00, accuracy: 0.01)
        XCTAssertEqual(curves.alpha(geometry.phase(at: rightEdge)), 0.52, accuracy: 0.01)
    }

    // MARK: Section 5, the phase field

    func testNothingDissolvesAtRest() {
        let geometry = makeGeometry(progress: 0)
        for u in stride(from: 0.0, through: 1.0, by: 0.1) {
            XCTAssertEqual(geometry.phase(at: point(atU: u)), 0, accuracy: 1e-9,
                           "phase should be zero everywhere before the front starts moving")
        }
    }

    func testEverythingDissolvesIncludingTheRoot() {
        let geometry = makeGeometry(progress: 1)
        for u in stride(from: 0.0, through: 1.0, by: 0.1) {
            XCTAssertEqual(geometry.phase(at: point(atU: u)), 1, accuracy: 1e-9,
                           "the (1 + softness) factor exists so the root finishes too")
        }
    }

    func testPhaseIncreasesAwayFromTheRoot() {
        let geometry = makeGeometry(progress: 0.5)
        var previous = -1.0
        for u in stride(from: 0.0, through: 1.0, by: 0.05) {
            let phase = geometry.phase(at: point(atU: u))
            XCTAssertGreaterThanOrEqual(phase, previous)
            previous = phase
        }
        XCTAssertGreaterThan(previous, 0, "the far edge must have started dissolving by halfway")
    }

    func testRootFollowsTheSignOfRoll() {
        let size = CGSize(width: 400, height: 800)
        let left = FoldGeometry(size: size, direction: CGVector(dx: -1, dy: 0),
                                progress: 0.5, softness: 1)
        let right = FoldGeometry(size: size, direction: CGVector(dx: 1, dy: 0),
                                 progress: 0.5, softness: 1)
        XCTAssertEqual(left.hinge.x, 0, accuracy: 1e-6)
        XCTAssertEqual(right.hinge.x, size.width, accuracy: 1e-6)

        let farRight = CGPoint(x: size.width, y: size.height / 2)
        XCTAssertGreaterThan(left.phase(at: farRight), right.phase(at: farRight),
                             "with the root on the left, the right edge dissolves first")
    }

    // MARK: Section 6, the curves

    func testBlurLeadsTheFade() {
        let curves = DissolveCurves(levels: 4)
        // At the point where blur is complete, the content must still be clearly visible.
        XCTAssertEqual(curves.blurIndex(0.65), 3.0, accuracy: 0.01)
        XCTAssertGreaterThan(curves.alpha(0.65), 0.4,
                             "content should still be visible once it is fully out of focus")
        XCTAssertEqual(curves.alpha(0.3), 1.0, accuracy: 1e-9, "the fade has not started yet")
        XCTAssertEqual(curves.alpha(1.0), 0.0, accuracy: 1e-9)
        XCTAssertEqual(curves.blurIndex(0.0), 0.0, accuracy: 1e-9)
    }

    func testLevelMasksCrossFadeExactly() {
        let curves = DissolveCurves(levels: 4)
        // Where the ladder sits at 1.7, level 1 is 30% over a solid level 2 and nothing sharper.
        let s = inverseBlurIndex(1.7, curves: curves)
        XCTAssertEqual(curves.levelMask(0, s), 0, accuracy: 0.02)
        XCTAssertEqual(curves.levelMask(1, s), 0.3, accuracy: 0.02)
        XCTAssertEqual(curves.levelMask(2, s), 1.0, accuracy: 0.02)
        XCTAssertEqual(curves.levelMask(3, s), 1.0, accuracy: 0.02)
    }

    func testGradientStopsAreMonotonicAndComplete() {
        let curves = DissolveCurves(levels: 4)
        let stops = curves.alphaStops
        XCTAssertEqual(stops.first?.location, 0)
        XCTAssertEqual(stops.last?.location, 1)
        XCTAssertEqual(stops.count, curves.sampleCount + 1)
    }

    // MARK: Section 7, the ladder

    func testLadderIsMonotonicAndStartsSharp() {
        XCTAssertEqual(BlurLadder.sigmaFraction(level: 0, of: 4), 0)
        var previous = -1.0
        for level in 0..<4 {
            let sigma = BlurLadder.sigmaFraction(level: level, of: 4)
            XCTAssertGreaterThan(sigma, previous)
            previous = sigma
        }
        XCTAssertEqual(previous, BlurLadder.maxSigmaFraction, accuracy: 1e-9)
    }

    func testLadderSpacingIsGeometric() {
        // Each gap should be wider than the one before it, so the fine end gets the resolution.
        let sigmas = (0..<5).map { BlurLadder.sigmaFraction(level: $0, of: 5) }
        let gaps = zip(sigmas.dropFirst(), sigmas).map(-)
        for (a, b) in zip(gaps.dropFirst(), gaps) {
            XCTAssertGreaterThan(a, b)
        }
    }

    func testFractionalSigmaInterpolatesBetweenLevels() {
        let width: CGFloat = 400
        let low = BlurLadder.sigma(level: 1, of: 4, pictureWidth: width)
        let high = BlurLadder.sigma(level: 2, of: 4, pictureWidth: width)
        let mid = BlurLadder.sigma(forIndex: 1.5, of: 4, pictureWidth: width)
        XCTAssertEqual(mid, (low + high) / 2, accuracy: 1e-6)

        XCTAssertEqual(BlurLadder.sigma(forIndex: -5, of: 4, pictureWidth: width), 0, accuracy: 1e-9)
        XCTAssertEqual(BlurLadder.sigma(forIndex: 99, of: 4, pictureWidth: width),
                       BlurLadder.sigma(level: 3, of: 4, pictureWidth: width), accuracy: 1e-9)
    }

    func testSigmaScalesWithPictureWidth() {
        let small = BlurLadder.sigma(level: 3, of: 4, pictureWidth: 100)
        let large = BlurLadder.sigma(level: 3, of: 4, pictureWidth: 400)
        XCTAssertEqual(large, small * 4, accuracy: 1e-6,
                       "the effect must look identical at any resolution")
    }

    // MARK: Section 4, the transform

    func testFlatTransformIsIdentity() {
        let size = CGSize(width: 402, height: 874)
        let geometry = makeGeometry(progress: 0)
        let transform = geometry.projection(angle: 0, eyeDistance: 1748,
                                            canvas: CGRect(origin: .zero, size: size))
        let corner = CGPoint(x: size.width, y: size.height).applying(transform)
        XCTAssertEqual(corner.x, size.width, accuracy: 1e-6)
        XCTAssertEqual(corner.y, size.height, accuracy: 1e-6)
    }

    func testHingeStaysPutAndFarEdgeContracts() {
        let size = CGSize(width: 402, height: 874)
        let geometry = FoldGeometry(size: size, direction: CGVector(dx: -1, dy: 0),
                                    progress: 0.3, softness: 1)
        let transform = geometry.projection(angle: 40 * .pi / 180, eyeDistance: 1748,
                                            canvas: CGRect(origin: .zero, size: size))

        // The hinge is the left edge, so points on x = 0 must not move.
        let onHinge = CGPoint(x: 0, y: size.height / 2).applying(transform)
        XCTAssertEqual(onHinge.x, 0, accuracy: 1e-6)
        XCTAssertEqual(onHinge.y, size.height / 2, accuracy: 1e-6)

        // The far edge recedes, so it must be drawn closer in and shorter.
        let topRight = CGPoint(x: size.width, y: 0).applying(transform)
        let bottomRight = CGPoint(x: size.width, y: size.height).applying(transform)
        XCTAssertLessThan(topRight.x, size.width)
        XCTAssertGreaterThan(topRight.y, 0)
        XCTAssertLessThan(bottomRight.y, size.height)
    }

    func testPerspectiveIsCentredNotHinged() {
        // With the vanishing point at the viewport centre, the far edge contracts symmetrically
        // about the horizontal midline rather than shearing toward one corner.
        let size = CGSize(width: 402, height: 874)
        let geometry = FoldGeometry(size: size, direction: CGVector(dx: -1, dy: 0),
                                    progress: 0.3, softness: 1)
        let transform = geometry.projection(angle: 40 * .pi / 180, eyeDistance: 1748,
                                            canvas: CGRect(origin: .zero, size: size))
        let top = CGPoint(x: size.width, y: 0).applying(transform)
        let bottom = CGPoint(x: size.width, y: size.height).applying(transform)
        XCTAssertEqual(top.y, size.height - bottom.y, accuracy: 1e-6)
    }

    // MARK: Section 2, the sensor

    func testRollFromGravityIgnoresPitch() {
        let monitor = TiltMonitor()
        monitor.smoothing = 1   // no filtering, so one sample is enough

        monitor.ingest(SIMD3(0, 0, -1))                       // flat, face up
        XCTAssertEqual(monitor.roll.degrees, 0, accuracy: 0.01)

        monitor.ingest(SIMD3(0, 0.7, -0.714))                 // pitched back, not rolled
        XCTAssertEqual(monitor.roll.degrees, 0, accuracy: 0.01,
                       "pitch must not drive the effect")

        monitor.ingest(SIMD3(-0.5, 0, -0.866))                // rolled left 30 degrees
        XCTAssertEqual(monitor.roll.degrees, -30, accuracy: 0.01)
        XCTAssertEqual(monitor.direction.dx, -1, accuracy: 1e-9)
        XCTAssertEqual(monitor.rootEdgeName, "left")

        monitor.ingest(SIMD3(0.5, 0, -0.866))                 // rolled right 30 degrees
        XCTAssertEqual(monitor.roll.degrees, 30, accuracy: 0.01)
        XCTAssertEqual(monitor.direction.dx, 1, accuracy: 1e-9)
        XCTAssertEqual(monitor.rootEdgeName, "right")
    }

    func testSmoothingConvergesAndDamps() {
        let monitor = TiltMonitor()
        monitor.smoothing = 0.3
        monitor.ingest(SIMD3(-0.5, 0, -0.866))
        let afterOne = monitor.roll.degrees
        XCTAssertGreaterThan(afterOne, -30, "one sample must not jump the whole way")
        for _ in 0..<60 { monitor.ingest(SIMD3(-0.5, 0, -0.866)) }
        XCTAssertEqual(monitor.roll.degrees, -30, accuracy: 0.1, "but it must converge")
    }

    func testDeadzoneAndClamping() {
        let configuration = TiltFoldConfiguration(blackAt: .degrees(75), deadzone: .degrees(3))
        XCTAssertEqual(configuration.progress(forRoll: .degrees(2)), 0, accuracy: 1e-9)
        XCTAssertEqual(configuration.progress(forRoll: .degrees(-2)), 0, accuracy: 1e-9)
        XCTAssertEqual(configuration.progress(forRoll: .degrees(75)), 1, accuracy: 1e-9)
        XCTAssertEqual(configuration.progress(forRoll: .degrees(-90)), 1, accuracy: 1e-9)
        XCTAssertEqual(configuration.progress(forRoll: .degrees(-39)), 0.5, accuracy: 1e-9)
    }

    func testDegenerateInputsDoNotCrash() {
        let zero = FoldGeometry(size: .zero, direction: CGVector(dx: 0, dy: 0),
                                progress: 0.5, softness: 1)
        XCTAssertEqual(zero.phase(at: .zero), 0, accuracy: 1e-9)
        _ = zero.gradientUnitPoints(in: .zero)
        _ = zero.projection(angle: 1, eyeDistance: 0, canvas: .zero)

        XCTAssertEqual(TiltFoldConfiguration(softness: -5).softness, 0.05, accuracy: 1e-9)
        XCTAssertEqual(TiltFoldConfiguration(levels: 0).levels, 2)
    }

    // MARK: Helpers

    private func makeGeometry(progress: Double) -> FoldGeometry {
        FoldGeometry(size: CGSize(width: 402, height: 874),
                     direction: CGVector(dx: -1, dy: 0),
                     progress: progress, softness: 1)
    }

    /// A point at normalised distance `u` from the root, for a left root.
    private func point(atU u: Double) -> CGPoint {
        CGPoint(x: 402 * u, y: 437)
    }

    private func inverseBlurIndex(_ target: Double, curves: DissolveCurves) -> Double {
        var low = 0.0, high = 1.0
        for _ in 0..<60 {
            let mid = (low + high) / 2
            if curves.blurIndex(mid) < target { low = mid } else { high = mid }
        }
        return (low + high) / 2
    }
}
