import CoreGraphics
import Foundation

#if canImport(UIKit)
import UIKit
#endif

#if canImport(CoreImage)
import CoreImage
#endif

/// Sigma values for the blur ladder, shared by every implementation.
///
/// Sigma is expressed as a fraction of the picture's width so the effect looks identical at any
/// resolution. Spacing is geometric: linear spacing wastes levels at the sharp end, where the
/// eye is most sensitive to a step between neighbours.
public enum BlurLadder {

    /// Blur of the last, blurriest level, as a fraction of picture width.
    public static let maxSigmaFraction: Double = 0.09

    /// Curvature of the ramp. 1 would be linear; higher pushes levels toward the sharp end.
    public static let spacingExponent: Double = 1.75

    /// Sigma for `level` of `levels`, as a fraction of picture width.
    /// Level 0 is always the sharp original. For the default 4 levels this yields
    /// approximately 0, 0.013, 0.044 and 0.090.
    public static func sigmaFraction(level: Int, of levels: Int) -> Double {
        guard level > 0, levels > 1 else { return 0 }
        let t = Double(min(level, levels - 1)) / Double(levels - 1)
        return maxSigmaFraction * pow(t, spacingExponent)
    }

    /// Sigma in points for `level` of `levels`, given the width the picture is drawn at.
    public static func sigma(level: Int, of levels: Int, pictureWidth: CGFloat) -> CGFloat {
        CGFloat(sigmaFraction(level: level, of: levels)) * pictureWidth
    }

    /// Sigma in points for a fractional ladder position, interpolating between neighbours.
    ///
    /// Live-blurred overlays use this to match the pre-rendered levels exactly.
    public static func sigma(forIndex index: Double, of levels: Int, pictureWidth: CGFloat) -> CGFloat {
        let i = clamp(index, 0, Double(levels - 1))
        let low = Int(i.rounded(.down))
        let high = min(low + 1, levels - 1)
        let f = i - Double(low)
        let a = sigmaFraction(level: low, of: levels)
        let b = sigmaFraction(level: high, of: levels)
        return CGFloat(a + (b - a) * f) * pictureWidth
    }

    /// Transparent margin kept around a blurred level so its feathered edge is not cut off.
    /// Three sigma captures essentially all of a Gaussian's energy.
    public static func margin(forSigma sigma: CGFloat) -> CGFloat {
        ceil(3 * sigma)
    }
}

#if canImport(CoreImage) && canImport(UIKit)

/// One pre-rendered step of the ladder.
public struct BlurLevel {
    /// The rendered bitmap, including its transparent feather margin.
    public let image: UIImage
    /// Margin baked into `image` on every side, as a fraction of the *picture's* width.
    public let marginFraction: CGFloat
}

/// A picture plus a pre-rendered ladder of progressively blurrier copies.
///
/// Use this when your content is a still image. It gives a true Gaussian at every level and a
/// correctly feathered edge, and costs nothing per frame because all the work happens once.
/// For live SwiftUI content use the `.tiltFold()` modifier instead.
public struct BlurredPicture {

    /// `levels[0]` is the sharp original.
    public let levels: [BlurLevel]

    /// Pixel size of the sharp original.
    public let pixelSize: CGSize

    /// Blurred levels are rendered at this fraction of the source resolution. They are blurry;
    /// nobody can tell, and it makes the Gaussian passes several times cheaper.
    public static let renderScale: CGFloat = 0.5

    /// Build the ladder off the main actor.
    public static func make(_ image: UIImage, levels: Int = 4) async -> BlurredPicture {
        await Task.detached(priority: .userInitiated) {
            BlurredPicture(image, levels: levels)
        }.value
    }

    /// Build the ladder from a named asset, off the main actor. Returns nil if the asset is missing.
    public static func make(named name: String, bundle: Bundle = .main, levels: Int = 4) async -> BlurredPicture? {
        guard let image = UIImage(named: name, in: bundle, compatibleWith: nil) else { return nil }
        return await make(image, levels: levels)
    }

    public init(_ image: UIImage, levels levelCount: Int = 4) {
        let sharp = BlurLevel(image: image, marginFraction: 0)
        guard let cgImage = image.cgImage, levelCount > 1 else {
            self.levels = [sharp]
            self.pixelSize = image.size
            return
        }

        let context = CIContext(options: [.useSoftwareRenderer: false])
        let source = CIImage(cgImage: cgImage)
        let scaled = source.transformed(by: CGAffineTransform(scaleX: Self.renderScale,
                                                             y: Self.renderScale))
        let extent = scaled.extent

        var built = [sharp]
        for level in 1..<levelCount {
            let sigma = BlurLadder.sigma(level: level, of: levelCount, pictureWidth: extent.width)
            let margin = BlurLadder.margin(forSigma: sigma)
            // Deliberately no clampedToExtent(): outside the picture Core Image sees transparent
            // black, so alpha falls off smoothly across the margin instead of ending in a line.
            let blurred = scaled.applyingGaussianBlur(sigma: Double(sigma))
            let padded = extent.insetBy(dx: -margin, dy: -margin)
            guard let rendered = context.createCGImage(blurred, from: padded) else { continue }
            built.append(BlurLevel(image: UIImage(cgImage: rendered),
                                   marginFraction: margin / extent.width))
        }

        self.levels = built
        self.pixelSize = CGSize(width: cgImage.width, height: cgImage.height)
    }

    /// Width in points the picture occupies when aspect-filled into `viewport`.
    public func filledWidth(in viewport: CGSize) -> CGFloat {
        guard pixelSize.width > 0, pixelSize.height > 0 else { return viewport.width }
        let fill = max(viewport.width / pixelSize.width, viewport.height / pixelSize.height)
        return pixelSize.width * fill
    }

    /// Height in points the picture occupies when aspect-filled into `viewport`.
    public func filledHeight(in viewport: CGSize) -> CGFloat {
        guard pixelSize.width > 0, pixelSize.height > 0 else { return viewport.height }
        let fill = max(viewport.width / pixelSize.width, viewport.height / pixelSize.height)
        return pixelSize.height * fill
    }
}

#endif
