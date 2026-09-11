import Combine
import Foundation
import SwiftUI

#if os(iOS) && canImport(CoreMotion)
import CoreMotion
#endif

/// Publishes the device's roll: how far it is tilted left or right around its long axis,
/// measured from lying flat.
///
/// Negative means the left edge is lower, positive means the right edge is lower. Front-to-back
/// pitch is deliberately ignored, because a phone held in the hand is almost never at zero pitch
/// and reacting to it makes the effect fire constantly. Set `omnidirectional` to opt in.
///
/// On platforms without motion hardware, and in SwiftUI previews, `roll` simply stays at whatever
/// you assign, so you can drive it yourself.
public final class TiltMonitor: ObservableObject {

    /// Signed roll. Drive this yourself when `isAvailable` is false, or whenever you want the
    /// effect keyed to something other than gravity.
    @Published public var roll: Angle = .zero

    /// Unit vector pointing at the root. Always horizontal unless `omnidirectional` is set.
    @Published public private(set) var direction: CGVector = CGVector(dx: 1, dy: 0)

    /// True when this device can report device motion.
    public let isAvailable: Bool

    /// React to tilt in any direction rather than only left and right.
    public var omnidirectional: Bool = false

    /// One-pole smoothing applied to the gravity vector, per 60 Hz sample. Higher is snappier,
    /// lower is calmer. Raw values shimmer noticeably on a device sitting still.
    public var smoothing: Double = 0.3

    /// Human readable name of the edge acting as the root.
    public var rootEdgeName: String {
        if !omnidirectional { return roll.degrees < 0 ? "left" : "right" }
        var degrees = atan2(direction.dy, direction.dx) * 180 / .pi
        if degrees < 0 { degrees += 360 }
        switch degrees {
        case 45..<135: return "bottom"
        case 135..<225: return "left"
        case 225..<315: return "top"
        default: return "right"
        }
    }

    #if os(iOS) && canImport(CoreMotion)
    private let motion = CMMotionManager()
    #endif
    private var smoothedGravity = SIMD3<Double>(0, 0, -1)

    public init() {
        #if os(iOS) && canImport(CoreMotion)
        isAvailable = CMMotionManager().isDeviceMotionAvailable
        #else
        isAvailable = false
        #endif
    }

    deinit { stop() }

    /// Begin publishing. Safe to call more than once.
    public func start() {
        #if os(iOS) && canImport(CoreMotion)
        guard isAvailable, !motion.isDeviceMotionActive else { return }
        motion.deviceMotionUpdateInterval = 1.0 / 60.0
        // Delivered on the main queue, so touching @Published from the closure is correct.
        motion.startDeviceMotionUpdates(to: .main) { [weak self] data, _ in
            guard let self, let gravity = data?.gravity else { return }
            self.ingest(SIMD3(gravity.x, gravity.y, gravity.z))
        }
        #endif
    }

    public func stop() {
        #if os(iOS) && canImport(CoreMotion)
        motion.stopDeviceMotionUpdates()
        #endif
    }

    /// Gravity in the device frame: +x right, +y toward the top of the screen, +z out of the
    /// screen, so lying flat face up is approximately (0, 0, -1).
    ///
    /// Exposed so you can feed it from your own source, or from a test.
    public func ingest(_ gravity: SIMD3<Double>) {
        smoothedGravity = smoothedGravity * (1 - smoothing) + gravity * smoothing
        let gx = smoothedGravity.x
        let gy = smoothedGravity.y
        let gz = smoothedGravity.z

        if omnidirectional {
            let planar = (gx * gx + gy * gy).squareRoot()
            roll = .radians(atan2(planar, -gz))
            if planar > 0.02 {
                // Flip y because screen coordinates point down.
                direction = CGVector(dx: gx / planar, dy: -gy / planar)
            }
        } else {
            // gy does not appear: that is what restricts the effect to left and right.
            let radians = atan2(gx, -gz)
            roll = .radians(radians)
            direction = CGVector(dx: radians < 0 ? -1 : 1, dy: 0)
        }
    }
}
