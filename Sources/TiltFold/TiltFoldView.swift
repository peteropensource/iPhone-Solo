import SwiftUI

/// The engine: takes any SwiftUI content and dissolves it around the low edge.
///
/// The content is drawn once per blur level, each copy blurred by a fixed amount and masked so
/// that neighbouring levels cross-fade. That is what produces a blur whose radius varies across
/// the picture, which no single blur call can do. The whole stack is then masked by the opacity
/// curve and projected in 3D around the hinge.
///
/// Layout is left alone: the view takes exactly the size the content would have taken on its own.
/// The size is measured in a background probe rather than with a `GeometryReader` wrapper, which
/// would make the content greedily fill its parent.
///
/// Content is rendered `configuration.levels` times, four by default. For an expensive hierarchy,
/// either lower `levels` or use `TiltFoldPicture`, which pre-renders the ladder once from a still
/// image and costs nothing per frame.
///
/// Nothing is painted behind the content: it dissolves into transparency, so whatever you put
/// behind it shows through. Put a black background behind it to match the original demo.
public struct TiltFoldView<Content: View>: View {

    private let configuration: TiltFoldConfiguration
    private let roll: Angle
    private let direction: CGVector
    private let content: Content

    /// Measured size of the content. Zero until the first layout pass lands.
    @State private var size: CGSize = .zero

    public init(
        roll: Angle,
        direction: CGVector = CGVector(dx: 1, dy: 0),
        configuration: TiltFoldConfiguration = .default,
        @ViewBuilder content: () -> Content
    ) {
        self.roll = roll
        self.direction = direction
        self.configuration = configuration
        self.content = content()
    }

    public var body: some View {
        levelStack
            .background(sizeProbe)
            .mask(outerMask)
            .modifier(Projection(transform: projectionTransform))
    }

    private var isMeasured: Bool { size.width > 0 && size.height > 0 }

    private var geometry: FoldGeometry {
        FoldGeometry(size: size,
                     direction: direction,
                     progress: configuration.progress(forRoll: roll),
                     softness: configuration.softness)
    }

    /// Widest feather, so the outer mask can be grown enough not to clip the overspill.
    private var widestMargin: CGFloat {
        guard isMeasured else { return 0 }
        return BlurLadder.margin(
            forSigma: BlurLadder.sigma(level: configuration.levels - 1,
                                       of: configuration.levels,
                                       pictureWidth: size.width)
        )
    }

    // MARK: Pieces

    /// The blur ladder. Sizes itself to the content, since every copy is the same size.
    @ViewBuilder
    private var levelStack: some View {
        if isMeasured {
            let geometry = self.geometry
            let curves = DissolveCurves(levels: configuration.levels)
            ZStack {
                ForEach(Array((0..<configuration.levels).reversed()), id: \.self) { level in
                    let sigma = BlurLadder.sigma(level: level,
                                                 of: configuration.levels,
                                                 pictureWidth: size.width)
                    content
                        .blur(radius: sigma)
                        .mask(mask(curves.levelStops(level),
                                   geometry: geometry,
                                   grownBy: BlurLadder.margin(forSigma: sigma)))
                }
            }
        } else {
            // One frame before the size is known. Drawing the content plain beats drawing it
            // through a degenerate gradient.
            content
        }
    }

    @ViewBuilder
    private var outerMask: some View {
        if isMeasured {
            mask(DissolveCurves(levels: configuration.levels).alphaStops,
                 geometry: geometry,
                 grownBy: widestMargin)
        } else {
            Color.white
        }
    }

    private var projectionTransform: ProjectionTransform? {
        guard isMeasured else { return nil }
        return geometry.projection(
            angle: configuration.tiltAngle(forRoll: roll),
            eyeDistance: configuration.eyeDistance * Double(max(size.width, size.height)),
            canvas: CGRect(origin: .zero, size: size)
        )
    }

    /// Reports the content's natural size without influencing it.
    private var sizeProbe: some View {
        GeometryReader { proxy in
            Color.clear.preference(key: SizeKey.self, value: proxy.size)
        }
        .onPreferenceChange(SizeKey.self) { newSize in
            if newSize != size { size = newSize }
        }
    }

    /// A mask that extends `margin` beyond the content on every side. The gradient line stays in
    /// viewport coordinates, so every level lines up with every other one however far it spills.
    private func mask(_ stops: [Gradient.Stop],
                      geometry: FoldGeometry,
                      grownBy margin: CGFloat) -> some View {
        let canvas = CGRect(origin: .zero, size: size).insetBy(dx: -margin, dy: -margin)
        let points = geometry.gradientUnitPoints(in: canvas)
        return LinearGradient(stops: stops, startPoint: points.start, endPoint: points.end)
            .padding(-margin)
    }
}

private struct SizeKey: PreferenceKey {
    static var defaultValue: CGSize = .zero
    static func reduce(value: inout CGSize, nextValue: () -> CGSize) {
        let next = nextValue()
        if next != .zero { value = next }
    }
}

/// Applies a projection only once there is one to apply, so the unmeasured first frame is not
/// pushed through an identity-but-not-quite transform.
private struct Projection: ViewModifier {
    let transform: ProjectionTransform?

    func body(content: Content) -> some View {
        if let transform {
            content.projectionEffect(transform)
        } else {
            content
        }
    }
}
