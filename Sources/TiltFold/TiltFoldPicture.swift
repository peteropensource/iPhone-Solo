#if canImport(UIKit) && canImport(CoreImage)
import SwiftUI
import UIKit

/// The still-image fast path.
///
/// Where `tiltFold()` re-renders live content once per blur level, this draws a ladder that was
/// rendered once with Core Image. That buys a true Gaussian at every level and a properly
/// feathered edge, and costs nothing per frame beyond compositing a handful of textures. Use it
/// for wallpapers, album art, photos, or any screen whose content does not change while tilted.
///
/// ```swift
/// @State private var picture: BlurredPicture?
///
/// var body: some View {
///     ZStack {
///         Color.black
///         if let picture {
///             TiltFoldPicture(picture, roll: monitor.roll)
///         }
///     }
///     .task { picture = await BlurredPicture.make(named: "Wallpaper") }
/// }
/// ```
public struct TiltFoldPicture: View {

    private let picture: BlurredPicture
    private let roll: Angle
    private let direction: CGVector
    private let configuration: TiltFoldConfiguration

    public init(
        _ picture: BlurredPicture,
        roll: Angle,
        direction: CGVector? = nil,
        configuration: TiltFoldConfiguration = .default
    ) {
        self.picture = picture
        self.roll = roll
        self.direction = direction ?? CGVector(dx: roll.degrees < 0 ? -1 : 1, dy: 0)
        self.configuration = configuration
    }

    public var body: some View {
        GeometryReader { proxy in
            let size = proxy.size
            if size.width > 0, size.height > 0 {
                layers(in: size)
            }
        }
    }

    @ViewBuilder
    private func layers(in size: CGSize) -> some View {
        let geometry = FoldGeometry(size: size,
                                    direction: direction,
                                    progress: configuration.progress(forRoll: roll),
                                    softness: configuration.softness)
        let curves = DissolveCurves(levels: picture.levels.count)
        let base = CGSize(width: picture.filledWidth(in: size), height: picture.filledHeight(in: size))
        let widestMargin = (picture.levels.map(\.marginFraction).max() ?? 0) * base.width
        let outerCanvas = centred(CGSize(width: base.width + 2 * widestMargin,
                                         height: base.height + 2 * widestMargin), in: size)
        let outerPoints = geometry.gradientUnitPoints(in: outerCanvas)

        ZStack {
            ForEach(Array(picture.levels.indices.reversed()), id: \.self) { index in
                let level = picture.levels[index]
                let margin = level.marginFraction * base.width
                let canvas = centred(CGSize(width: base.width + 2 * margin,
                                            height: base.height + 2 * margin), in: size)
                let points = geometry.gradientUnitPoints(in: canvas)
                Image(uiImage: level.image)
                    .resizable()
                    .frame(width: canvas.width, height: canvas.height)
                    .mask(
                        LinearGradient(stops: curves.levelStops(index),
                                       startPoint: points.start, endPoint: points.end)
                    )
            }
        }
        .frame(width: outerCanvas.width, height: outerCanvas.height)
        .mask(
            LinearGradient(stops: curves.alphaStops,
                           startPoint: outerPoints.start, endPoint: outerPoints.end)
        )
        .projectionEffect(
            geometry.projection(angle: configuration.tiltAngle(forRoll: roll),
                                eyeDistance: configuration.eyeDistance * Double(max(size.width, size.height)),
                                canvas: outerCanvas)
        )
        .frame(width: size.width, height: size.height)
    }

    /// `size` centred inside `viewport`, in viewport coordinates. May extend outside it.
    private func centred(_ size: CGSize, in viewport: CGSize) -> CGRect {
        CGRect(x: (viewport.width - size.width) / 2,
               y: (viewport.height - size.height) / 2,
               width: size.width, height: size.height)
    }
}
#endif
