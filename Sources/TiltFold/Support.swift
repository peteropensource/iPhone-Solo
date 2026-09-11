import CoreGraphics
import Foundation

@inline(__always)
public func clamp<T: Comparable>(_ x: T, _ lower: T, _ upper: T) -> T {
    min(max(x, lower), upper)
}

/// The usual Hermite ramp: 0 at or below `edge0`, 1 at or above `edge1`, smooth in between.
@inline(__always)
public func smoothstep(_ edge0: Double, _ edge1: Double, _ x: Double) -> Double {
    guard edge1 != edge0 else { return x < edge0 ? 0 : 1 }
    let t = clamp((x - edge0) / (edge1 - edge0), 0, 1)
    return t * t * (3 - 2 * t)
}

extension CGVector {
    /// Normalised copy. Returns `(1, 0)` for a degenerate vector so callers never divide by zero.
    var normalized: CGVector {
        let length = hypot(dx, dy)
        guard length > 1e-9 else { return CGVector(dx: 1, dy: 0) }
        return CGVector(dx: dx / length, dy: dy / length)
    }
}
