import SwiftUI

/// Namespace for package-level conveniences.
public enum TiltFold {
    /// Version of the algorithm this package implements. See `docs/ALGORITHM.md`.
    public static let algorithmVersion = 1
}

// MARK: - The modifier

public extension View {

    /// Dissolves this view around whichever of its side edges is currently lowest, driven by the
    /// device's own motion sensors.
    ///
    /// ```swift
    /// ZStack {
    ///     Color.black
    ///     MyHomeScreen()
    ///         .tiltFold()
    /// }
    /// .ignoresSafeArea()
    /// ```
    ///
    /// The content dissolves into transparency rather than into a colour, so put whatever you
    /// want revealed behind it. On a device with no motion hardware, and in previews, the view
    /// stays flat and sharp; use ``SwiftUI/View/tiltFold(roll:direction:configuration:)`` to
    /// drive it yourself there.
    ///
    /// - Important: the content is rendered `configuration.levels` times, four by default.
    ///   Lower that, or reach for `TiltFoldPicture`, if the hierarchy is expensive.
    func tiltFold(_ configuration: TiltFoldConfiguration = .default) -> some View {
        modifier(SensorTiltFoldModifier(configuration: configuration))
    }

    /// Dissolves this view around a root you choose, driven by a value you supply.
    ///
    /// Use this to key the effect to a drag, a scroll offset or an animation rather than to
    /// gravity, and to get a stable result in previews, tests and screenshots.
    ///
    /// - Parameters:
    ///   - roll: signed tilt. Negative puts the root on the left edge, positive on the right.
    ///   - direction: unit vector pointing at the root, for full control. Defaults to deriving
    ///     it from the sign of `roll`.
    ///   - configuration: tuning.
    func tiltFold(
        roll: Angle,
        direction: CGVector? = nil,
        configuration: TiltFoldConfiguration = .default
    ) -> some View {
        TiltFoldView(roll: roll,
                     direction: direction ?? CGVector(dx: roll.degrees < 0 ? -1 : 1, dy: 0),
                     configuration: configuration) { self }
    }
}

private struct SensorTiltFoldModifier: ViewModifier {
    let configuration: TiltFoldConfiguration
    @StateObject private var monitor = TiltMonitor()

    func body(content: Content) -> some View {
        TiltFoldView(roll: monitor.roll,
                     direction: monitor.direction,
                     configuration: configuration) { content }
            .onAppear {
                monitor.omnidirectional = configuration.omnidirectional
                monitor.start()
            }
            .onDisappear { monitor.stop() }
    }
}

// MARK: - Overlays that stay glued to the content

public extension View {

    /// Dissolves this overlay in lockstep with a `tiltFold`ed view underneath it.
    ///
    /// An overlay placed on top of folded content would otherwise float over it, sharp and
    /// opaque, while everything behind it recedes. This samples the dissolve at the overlay's own
    /// position and applies the same blur, the same opacity and the same projection, so the
    /// overlay behaves like it is printed on the content.
    ///
    /// ```swift
    /// SettingsButton()
    ///     .tiltFoldOverlay(roll: monitor.roll, at: UnitPoint(x: 0.84, y: 0.61))
    /// ```
    ///
    /// - Parameters:
    ///   - roll: the same value driving the content underneath.
    ///   - position: where the overlay sits, as a fraction of the container.
    ///   - direction: unit vector pointing at the root, if you are not deriving it from `roll`.
    ///   - configuration: the same tuning as the content underneath.
    ///   - hitTestThreshold: the overlay stops accepting input once it is more than this far
    ///     gone, so a nearly invisible control cannot be tapped by accident.
    func tiltFoldOverlay(
        roll: Angle,
        at position: UnitPoint,
        direction: CGVector? = nil,
        configuration: TiltFoldConfiguration = .default,
        hitTestThreshold: Double = 0.5
    ) -> some View {
        TiltFoldOverlay(roll: roll,
                        position: position,
                        direction: direction ?? CGVector(dx: roll.degrees < 0 ? -1 : 1, dy: 0),
                        configuration: configuration,
                        hitTestThreshold: hitTestThreshold) { self }
    }
}

private struct TiltFoldOverlay<Content: View>: View {
    let roll: Angle
    let position: UnitPoint
    let direction: CGVector
    let configuration: TiltFoldConfiguration
    let hitTestThreshold: Double
    @ViewBuilder let content: () -> Content

    var body: some View {
        GeometryReader { proxy in
            let size = proxy.size
            let geometry = FoldGeometry(size: size,
                                        direction: direction,
                                        progress: configuration.progress(forRoll: roll),
                                        softness: configuration.softness)
            let centre = CGPoint(x: size.width * position.x, y: size.height * position.y)
            let curves = DissolveCurves(levels: configuration.levels)
            let phase = geometry.phase(at: centre)
            let alpha = curves.alpha(phase)

            content()
                .blur(radius: BlurLadder.sigma(forIndex: curves.blurIndex(phase),
                                               of: configuration.levels,
                                               pictureWidth: size.width))
                .opacity(alpha)
                .position(centre)
                .projectionEffect(
                    geometry.projection(angle: configuration.tiltAngle(forRoll: roll),
                                        eyeDistance: configuration.eyeDistance * Double(max(size.width, size.height)),
                                        canvas: CGRect(origin: .zero, size: size))
                )
                .allowsHitTesting(alpha > hitTestThreshold)
        }
    }
}
