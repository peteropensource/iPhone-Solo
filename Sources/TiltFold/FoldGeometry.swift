import CoreGraphics
import QuartzCore
import SwiftUI

/// All the geometry of the effect, for one frame, in one value.
///
/// This is a faithful implementation of `docs/ALGORITHM.md` sections 4 and 5. It is a plain
/// value type with no framework dependencies beyond CoreGraphics and QuartzCore, so it can be
/// unit tested directly, and it is cheap enough to rebuild every frame.
///
/// Coordinates are viewport points with `+y` pointing down, which is what SwiftUI uses.
public struct FoldGeometry: Equatable {

    /// Size of the viewport the effect is drawn into.
    public let size: CGSize

    /// Unit vector pointing at the root, the edge that is currently lowest.
    public let direction: CGVector

    /// Distance from the viewport centre to the farthest point along `direction`.
    public let halfExtent: Double

    /// Locus of dissolve phase 0, in viewport coordinates.
    public let gradientStart: CGPoint

    /// Locus of dissolve phase 1, in viewport coordinates.
    public let gradientEnd: CGPoint

    /// - Parameters:
    ///   - size: viewport size.
    ///   - direction: points at the root; need not be normalised.
    ///   - progress: 0 leaves the content untouched, 1 dissolves all of it.
    ///   - softness: ramp length in units of the root-to-far-edge distance.
    public init(size: CGSize, direction: CGVector, progress: Double, softness: Double) {
        let unit = direction.normalized
        let extent = abs(unit.dx) * size.width / 2 + abs(unit.dy) * size.height / 2

        self.size = size
        self.direction = unit
        self.halfExtent = extent

        // `u` is the normalised distance from the root: 0 on the low edge, 1 on the high edge.
        // The front starts beyond the far edge and sweeps back toward the root. The (1 + softness)
        // factor is what lets the trailing end of the ramp clear the root at progress == 1.
        let front = 1 - progress * (1 + softness)
        let point: (Double) -> CGPoint = { u in
            let t = 1 - 2 * u   // +1 at the low corner, -1 at the high corner
            return CGPoint(x: size.width / 2 + unit.dx * t * extent,
                           y: size.height / 2 + unit.dy * t * extent)
        }
        self.gradientStart = point(front)
        self.gradientEnd = point(front + softness)
    }

    /// The point on the hinge line, in viewport coordinates. The content pivots around this.
    public var hinge: CGPoint {
        CGPoint(x: size.width / 2 + direction.dx * halfExtent,
                y: size.height / 2 + direction.dy * halfExtent)
    }

    /// Dissolve phase at a viewport point: 0 untouched, 1 fully dissolved.
    ///
    /// This returns exactly the value the gradient mask produces at `point`, which is what lets
    /// an overlay dissolve in lockstep with the content underneath it.
    public func phase(at point: CGPoint) -> Double {
        let ax = gradientEnd.x - gradientStart.x
        let ay = gradientEnd.y - gradientStart.y
        let lengthSquared = ax * ax + ay * ay
        guard lengthSquared > 1e-9 else { return 0 }
        let t = ((point.x - gradientStart.x) * ax + (point.y - gradientStart.y) * ay) / lengthSquared
        return clamp(t, 0, 1)
    }

    /// The gradient endpoints as unit points of a view occupying `canvas` in viewport
    /// coordinates. Layers that overspill the viewport (the feathered blur levels) are larger
    /// than the viewport, so each needs the same line expressed in its own space.
    public func gradientUnitPoints(in canvas: CGRect) -> (start: UnitPoint, end: UnitPoint) {
        func unit(_ p: CGPoint) -> UnitPoint {
            UnitPoint(x: canvas.width > 0 ? (p.x - canvas.minX) / canvas.width : 0.5,
                      y: canvas.height > 0 ? (p.y - canvas.minY) / canvas.height : 0.5)
        }
        return (unit(gradientStart), unit(gradientEnd))
    }

    /// Rotation about the hinge followed by a perspective projection centred on the viewport.
    ///
    /// - Parameters:
    ///   - angle: rotation in radians, 0 for flat.
    ///   - eyeDistance: viewer distance in points.
    ///   - canvas: the frame, in viewport coordinates, of the view the transform is applied to.
    ///     Pass `CGRect(origin: .zero, size: size)` for a view that exactly fills the viewport.
    public func projection(angle: Double, eyeDistance: Double, canvas: CGRect) -> ProjectionTransform {
        let h = CGPoint(x: hinge.x - canvas.minX, y: hinge.y - canvas.minY)
        let c = CGPoint(x: size.width / 2 - canvas.minX, y: size.height / 2 - canvas.minY)

        var m = CATransform3DMakeTranslation(-h.x, -h.y, 0)
        // The axis lies along the hinge line, perpendicular to `direction`. With this handedness
        // a positive angle pushes the far edge to negative z, away from the viewer.
        m = CATransform3DConcat(m, CATransform3DMakeRotation(angle, direction.dy, -direction.dx, 0))
        m = CATransform3DConcat(m, CATransform3DMakeTranslation(h.x - c.x, h.y - c.y, 0))
        var perspective = CATransform3DIdentity
        perspective.m34 = -1 / max(eyeDistance, 1)
        m = CATransform3DConcat(m, perspective)
        m = CATransform3DConcat(m, CATransform3DMakeTranslation(c.x, c.y, 0))
        return ProjectionTransform(m)
    }
}
