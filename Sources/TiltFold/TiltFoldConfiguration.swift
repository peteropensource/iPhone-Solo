import CoreGraphics
import SwiftUI

/// Tuning for the effect. Every value has a sensible default; you can ship without touching this.
///
/// See `docs/ALGORITHM.md` section 2 for what each one means geometrically.
public struct TiltFoldConfiguration: Equatable, Sendable {

    /// Tilt at which the content has completely dissolved. Larger means the content survives
    /// further into the tilt.
    public var blackAt: Angle

    /// Tilt below which nothing happens at all. This hides sensor noise: without it a device
    /// resting on a table shimmers. Set to zero when driving `roll` manually.
    public var deadzone: Angle

    /// Length of the dissolve front, in units of the root-to-far-edge distance. 1.0 means the
    /// ramp spans the whole picture; smaller values give a tighter, more visible wavefront.
    public var softness: Double

    /// Viewer distance from the screen plane, as a multiple of the longer viewport side.
    /// Smaller means stronger perspective.
    public var eyeDistance: Double

    /// Number of steps in the blur ladder, including the sharp original. Three is enough for
    /// small content; four is the default; more costs proportionally more compositing.
    public var levels: Int

    /// When true the effect responds to tilt in any direction and the root can be any edge or
    /// corner. Spectacular in a demo, hard to hold still in real use, so it is off by default.
    public var omnidirectional: Bool

    public init(
        blackAt: Angle = .degrees(75),
        deadzone: Angle = .degrees(3),
        softness: Double = 1.0,
        eyeDistance: Double = 2.0,
        levels: Int = 4,
        omnidirectional: Bool = false
    ) {
        self.blackAt = blackAt
        self.deadzone = deadzone
        self.softness = max(0.05, softness)
        self.eyeDistance = max(0.1, eyeDistance)
        self.levels = max(2, levels)
        self.omnidirectional = omnidirectional
    }

    public static let `default` = TiltFoldConfiguration()

    /// Dissolves late and stays legible longer. Good when the content is being read.
    public static let subtle = TiltFoldConfiguration(blackAt: .degrees(88), softness: 1.4)

    /// Disappears fast and dramatically. Good for a demo or a transition.
    public static let dramatic = TiltFoldConfiguration(blackAt: .degrees(50), softness: 0.6,
                                                       eyeDistance: 1.2)

    /// Effective tilt in radians for a given signed roll, with the deadzone removed.
    public func tiltAngle(forRoll roll: Angle) -> Double {
        let degrees = clamp(abs(roll.degrees) - deadzone.degrees, 0, 90)
        return degrees * .pi / 180
    }

    /// Dissolve progress, 0 (untouched) to 1 (fully gone), for a given signed roll.
    public func progress(forRoll roll: Angle) -> Double {
        let degrees = clamp(abs(roll.degrees) - deadzone.degrees, 0, 90)
        let span = max(blackAt.degrees - deadzone.degrees, 1)
        return clamp(degrees / span, 0, 1)
    }
}
