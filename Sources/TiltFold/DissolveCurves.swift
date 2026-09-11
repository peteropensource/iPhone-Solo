import SwiftUI

/// The two response curves, as functions of the dissolve phase `s`.
///
/// See `docs/ALGORITHM.md` section 6. The important property is that blur finishes early
/// (by `s = 0.65`) while opacity starts late (at `s = 0.3`), so content is already fully out of
/// focus before it begins to disappear. Make the two overlap evenly and the effect collapses
/// into an ordinary crossfade.
public struct DissolveCurves: Equatable {

    /// Number of steps in the blur ladder, including the sharp original.
    public let levels: Int

    /// How many stops to emit when converting a curve into a gradient.
    public var sampleCount: Int = 24

    public init(levels: Int) {
        self.levels = max(2, levels)
    }

    /// Fractional position in the blur ladder: 0 is sharp, `levels - 1` is blurriest.
    public func blurIndex(_ s: Double) -> Double {
        Double(levels - 1) * smoothstep(0, 0.65, s)
    }

    /// Opacity over the background.
    public func alpha(_ s: Double) -> Double {
        1 - smoothstep(0.3, 1.0, s)
    }

    /// Visibility of blur level `index`.
    ///
    /// Levels are stacked blurriest first, sharp last. Level `i` is fully visible while
    /// `blurIndex <= i` and fades out as `blurIndex` travels from `i` to `i + 1`. Under ordinary
    /// source-over compositing that is an exact cross-fade between neighbouring levels.
    public func levelMask(_ index: Int, _ s: Double) -> Double {
        1 - clamp(blurIndex(s) - Double(index), 0, 1)
    }

    // MARK: Gradient stops

    public func levelStops(_ index: Int) -> [Gradient.Stop] {
        stops { levelMask(index, $0) }
    }

    public var alphaStops: [Gradient.Stop] {
        stops(alpha)
    }

    private func stops(_ curve: (Double) -> Double) -> [Gradient.Stop] {
        (0...sampleCount).map { step in
            let s = Double(step) / Double(sampleCount)
            return Gradient.Stop(color: .white.opacity(curve(s)), location: s)
        }
    }
}
