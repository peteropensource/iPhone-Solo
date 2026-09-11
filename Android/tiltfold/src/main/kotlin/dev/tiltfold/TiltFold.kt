package dev.tiltfold

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.sqrt

/**
 * TiltFold for Jetpack Compose. See `docs/ALGORITHM.md`; this file is only the drawing.
 *
 * The interesting decisions, all of them forced by the platform rather than chosen:
 *
 * **The ladder means composing your content N times.** Compose has no way to take one rendered
 * subtree and re-draw it at several blur radii with public, stable API, so [TiltFold] invokes the
 * `content` lambda once per blur level. Hoist any state your content owns (scroll positions
 * especially) or each copy will keep its own and they will drift apart.
 *
 * **The vanishing point sits at the hinge, not at the viewport centre.** Spec section 4 steps 3
 * to 5 move the perspective origin back to the centre, which CSS spells `perspective-origin: 50%
 * 50%`. Android's camera is positioned at the transform origin, and `graphicsLayer` exposes no
 * separate perspective origin, so with the hinge as the pivot the far edge shears slightly toward
 * one corner instead of contracting symmetrically about the midline. Pinning the hinge matters
 * more than the shear, so the hinge is what we keep. A larger `eyeDistance` reduces the
 * difference.
 *
 * **Blur radius is not Gaussian sigma.** `Modifier.blur` ends up in `RenderEffect.createBlurEffect`,
 * whose radius relates to sigma roughly as `sigma ~= 0.577 * radius + 0.5`. We pass the ladder's
 * sigma values straight through as radii, exactly as the Swift port passes them to SwiftUI's
 * `.blur(radius:)`, so the Android ladder is a little softer in absolute terms than a Core Image
 * one at the same numbers. The effect depends on the *ratios* between levels, and those are
 * preserved; the `3 * sigma` feather margin is then a safe over-estimate rather than an exact one.
 */

/**
 * Whether this device can actually blur.
 *
 * `Modifier.blur` is implemented with `RenderEffect`, which is API 31 (Android 12) and up; on
 * anything older Compose silently ignores it. Rather than paying to compose the content four
 * times for four identical sharp copies, [TiltFold] collapses to a single level below 31: you
 * still get the fold and the dissolve, just without the defocus. Say so in your release notes —
 * the blur is what sells the depth (spec section 1).
 */
val tiltFoldBlurSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Direction of positive rotation, isolated so there is exactly one thing to flip if the fold
 * leans the wrong way. The spec expects this (section 4, step 2: "if your effect leans the wrong
 * way, negate this axis; the handedness of your coordinate system decides the sign").
 *
 * The reasoning behind `+1`: Compose's `rotationZ` turns content clockwise on screen, which means
 * the rotation matrices are the ordinary right-handed formulas evaluated in a space where `+x` is
 * right and `+y` is **down** — and that makes `+z` point *into* the screen, away from the viewer.
 * The spec's frame is CoreAnimation's, where `+z` points at the viewer. Flipping the z axis
 * negates the rotation about any in-plane axis, so the spec's "rotate by `+tilt` about
 * `(d.y, -d.x, 0)`" becomes "rotate by `-tilt`" here, which is `rotationY = +d.x * tilt` and
 * `rotationX = -d.y * tilt` below.
 *
 * Sanity check on the common case: a left root has `d = (-1, 0)`, giving `rotationY = -tilt`, and
 * negative `rotationY` should swing the right-hand (far) edge away from the viewer.
 */
private const val ROTATION_SIGN = 1f

/** Below this many pixels a blur is not worth a layer. */
private const val MIN_USEFUL_SIGMA_PX = 0.5f

// ---------------------------------------------------------------------------------------------
// The composable: the full blur ladder
// ---------------------------------------------------------------------------------------------

/**
 * Dissolves [content] around whichever of its edges is currently lowest.
 *
 * ```kotlin
 * val tilt = rememberTiltMonitor()
 * Box(Modifier.fillMaxSize().background(Color.Black)) {
 *     TiltFold(roll = tilt.roll) { MyHomeScreen() }
 * }
 * ```
 *
 * The content dissolves into transparency rather than into a colour, so put whatever you want
 * revealed behind it.
 *
 * Layout is left alone: the composable takes exactly the size the content would have taken on its
 * own, and the blurred levels are allowed to spill outside those bounds so their feathered edges
 * melt into the background (spec section 8). Do not wrap it in something that clips, or you will
 * get the hard edge the spec warns about.
 *
 * @param roll signed tilt **in degrees**. Negative puts the root on the left edge, positive on the
 *   right. Drive it from [rememberTiltMonitor], a drag, a scroll offset or an animation.
 * @param config tuning.
 * @param direction unit vector pointing at the root, for full control. Defaults to deriving it
 *   from the sign of [roll].
 * @param content composed once per blur level. Hoist its state.
 */
@Composable
fun TiltFold(
    roll: Float,
    modifier: Modifier = Modifier,
    config: TiltFoldConfig = TiltFoldConfig(),
    direction: Vector2? = null,
    content: @Composable () -> Unit
) {
    val rollDegrees = roll.toDouble()
    val root = direction?.normalized() ?: directionForRoll(rollDegrees)
    val progress = config.progress(rollDegrees)
    val tilt = config.tiltAngle(rollDegrees).toFloat()
    val softness = config.safeSoftness
    val curves = remember(config.safeLevels) { DissolveCurves(config.safeLevels) }

    // The ladder collapses to the sharp original where there is no blur to be had.
    val levelCount = if (tiltFoldBlurSupported) config.safeLevels else 1

    // Only the blur radii need the size in advance, because Modifier.blur takes a fixed Dp.
    // Everything else is computed inside a deferred lambda, where the exact size is in hand.
    var measured by remember { mutableStateOf(IntSize.Zero) }
    // Pixels per dp. Read as a Float rather than holding the Density object, because inside a
    // graphicsLayer lambda the scope's own `density` member would shadow a captured `Density`.
    val densityScale = LocalDensity.current.density
    val widthPx = measured.width.toDouble()

    val widestSigmaPx = BlurLadder.sigma(levelCount - 1, config.safeLevels, widthPx)
    val widestMarginPx = BlurLadder.margin(widestSigmaPx).toFloat()
    val alphaSamples = remember(curves) { curves.alphaSamples() }

    Box(
        modifier = modifier
            .onSizeChanged { if (it != measured) measured = it }
            // Outermost of the three, so the transform is applied to the finished, masked stack.
            .graphicsLayer {
                if (size.width <= 0f || size.height <= 0f) return@graphicsLayer
                val geometry = foldGeometry(size, root, progress, softness)
                val hinge = geometry.hingeFraction
                transformOrigin = TransformOrigin(hinge.x.toFloat(), hinge.y.toFloat())
                rotationY = ROTATION_SIGN * geometry.direction.x.toFloat() * tilt
                rotationX = -ROTATION_SIGN * geometry.direction.y.toFloat() * tilt
                // Spec section 4 step 4: the eye sits `eyeDistance * max(W, H)` in front of the
                // screen plane. `size` here is in pixels, and `density` is pixels per dp, so
                // composeCameraDistance() converts into the units graphicsLayer wants. See its
                // documentation for why that divisor is 160 * density.
                cameraDistance = composeCameraDistance(
                    eyeDistancePixels = config.safeEyeDistance * max(size.width, size.height).toDouble(),
                    density = this.density.toDouble()
                ).toFloat()
            }
            // Then the opacity curve, over the whole stack, grown to cover the widest feather.
            .drawWithContent {
                val geometry = foldGeometry(size, root, progress, softness)
                drawMasked(gradientBrush(geometry, alphaSamples), widestMarginPx)
            }
    ) {
        // Blurriest at the bottom, sharp on top: under ordinary source-over compositing the level
        // masks then cross-fade exactly between neighbours (spec section 7).
        for (level in levelCount - 1 downTo 0) {
            key(level) {
                val sigmaPx = BlurLadder.sigma(level, config.safeLevels, widthPx)
                val marginPx = BlurLadder.margin(sigmaPx).toFloat()
                val levelSamples: DoubleArray? = remember(curves, level, levelCount) {
                    // With a single level there is nothing to cross-fade with, and masking it
                    // would delete the content wherever the ladder wanted a blurrier copy.
                    if (levelCount == 1) null else curves.levelMaskSamples(level)
                }

                Box(
                    modifier = Modifier
                        .drawWithContent {
                            if (levelSamples == null) {
                                drawContent()
                            } else {
                                val geometry = foldGeometry(size, root, progress, softness)
                                drawMasked(gradientBrush(geometry, levelSamples), marginPx)
                            }
                        }
                        .blurIfUseful(sigmaPx, densityScale)
                ) {
                    content()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The modifier: one pass, for content you cannot afford to compose N times
// ---------------------------------------------------------------------------------------------

/**
 * Dissolves this element around whichever of its edges is currently lowest, in a single pass.
 *
 * This is the sibling of the Swift port's `tiltFoldOverlay`. It applies the same geometry, the
 * same opacity curve and the same 3D fold as [TiltFold], but it cannot vary the blur across the
 * element: a modifier has no way to draw its content more than once at different blur radii. It
 * uses one blur, taken from the ladder at the phase of the element's own centre, which keeps it
 * registered with folded content underneath it.
 *
 * Use it for overlays — a button, a badge, a toolbar — that must dissolve in step with a
 * [TiltFold]ed backdrop, and for content that is too expensive to compose four times. Use
 * [TiltFold] when you want the real thing.
 *
 * @param roll signed tilt **in degrees**, the same value driving the content underneath.
 * @param config the same tuning as the content underneath.
 * @param direction unit vector pointing at the root, if you are not deriving it from [roll].
 */
fun Modifier.tiltFold(
    roll: Float,
    config: TiltFoldConfig = TiltFoldConfig(),
    direction: Vector2? = null
): Modifier = composed {
    val rollDegrees = roll.toDouble()
    val root = direction?.normalized() ?: directionForRoll(rollDegrees)
    val progress = config.progress(rollDegrees)
    val tilt = config.tiltAngle(rollDegrees).toFloat()
    val softness = config.safeSoftness
    val curves = remember(config.safeLevels) { DissolveCurves(config.safeLevels) }
    val alphaSamples = remember(curves) { curves.alphaSamples() }

    var measured by remember { mutableStateOf(IntSize.Zero) }
    val densityScale = LocalDensity.current.density

    // One blur for the whole element, matching what the ladder would have been at its centre.
    val sigmaPx = if (measured.width > 0 && tiltFoldBlurSupported) {
        val geometry = FoldGeometry(
            measured.width.toDouble(), measured.height.toDouble(), root, progress, softness
        )
        BlurLadder.sigma(
            index = curves.blurIndex(geometry.phase(geometry.centre)),
            levels = config.safeLevels,
            pictureWidth = measured.width.toDouble()
        )
    } else {
        0.0
    }
    val marginPx = BlurLadder.margin(sigmaPx).toFloat()

    Modifier
        .onSizeChanged { if (it != measured) measured = it }
        .graphicsLayer {
            if (size.width <= 0f || size.height <= 0f) return@graphicsLayer
            val geometry = foldGeometry(size, root, progress, softness)
            val hinge = geometry.hingeFraction
            transformOrigin = TransformOrigin(hinge.x.toFloat(), hinge.y.toFloat())
            rotationY = ROTATION_SIGN * geometry.direction.x.toFloat() * tilt
            rotationX = -ROTATION_SIGN * geometry.direction.y.toFloat() * tilt
            cameraDistance = composeCameraDistance(
                eyeDistancePixels = config.safeEyeDistance * max(size.width, size.height).toDouble(),
                density = this.density.toDouble()
            ).toFloat()
        }
        .drawWithContent {
            val geometry = foldGeometry(size, root, progress, softness)
            drawMasked(gradientBrush(geometry, alphaSamples), marginPx)
        }
        .blurIfUseful(sigmaPx, densityScale)
}

// ---------------------------------------------------------------------------------------------
// Plumbing
// ---------------------------------------------------------------------------------------------

/** [FoldGeometry] for a pixel [Size], which is what every draw and layer scope hands us. */
private fun foldGeometry(
    size: Size,
    direction: Vector2,
    progress: Double,
    softness: Double
): FoldGeometry = FoldGeometry(
    width = size.width.toDouble(),
    height = size.height.toDouble(),
    direction = direction,
    progress = progress,
    softness = softness
)

/**
 * `Modifier.blur`, but only when there is a blur worth having.
 *
 * `BlurredEdgeTreatment.Unbounded` is the whole of spec section 8 part 1: the blur is not clamped
 * to the element's bounds, so alpha falls off smoothly across the border instead of ending in a
 * hard line. Part 2, keeping the overspill, is handled by the mask rectangle in [drawMasked],
 * which is grown by the same `3 * sigma` margin so it does not erase what spilled out.
 */
private fun Modifier.blurIfUseful(sigmaPx: Double, density: Float): Modifier {
    val sigma = sigmaPx.toFloat()
    if (sigma < MIN_USEFUL_SIGMA_PX || density <= 0f || !tiltFoldBlurSupported) return this
    return this.blur(
        radius = Dp(sigma / density),
        edgeTreatment = BlurredEdgeTreatment.Unbounded
    )
}

/**
 * Draws the content into an offscreen layer and punches the [brush] through its alpha channel.
 *
 * `BlendMode.DstIn` needs somewhere to blend into, hence the explicit `saveLayer`. The layer and
 * the mask rectangle are both grown by [marginPx] on every side so that a blurred level's
 * feathered overspill survives instead of being cut off at the layout bounds.
 *
 * The brush is built in viewport coordinates and the local coordinate space here has its origin
 * at the same place, so no conversion is needed and every level lines up with every other one
 * however far it spills (spec section 8: "build the gradient masks in viewport coordinates and
 * convert to each level's local coordinates, rather than the other way around").
 */
private fun ContentDrawScope.drawMasked(brush: Brush, marginPx: Float) {
    val left = -marginPx
    val top = -marginPx
    val width = size.width + 2f * marginPx
    val height = size.height + 2f * marginPx

    val canvas = drawContext.canvas
    canvas.saveLayer(Rect(left, top, left + width, top + height), Paint())
    drawContent()
    drawRect(
        brush = brush,
        topLeft = Offset(left, top),
        size = Size(width, height),
        blendMode = BlendMode.DstIn
    )
    canvas.restore()
}

/**
 * One of the two curves, sampled into gradient stops along the dissolve axis.
 *
 * The graphics stack interpolates between the stops, which is how a per-pixel field gets
 * evaluated without us ever writing a shader (spec section 5).
 */
private fun gradientBrush(geometry: FoldGeometry, samples: DoubleArray): Brush {
    val start = Offset(geometry.gradientStart.x.toFloat(), geometry.gradientStart.y.toFloat())
    val end = Offset(geometry.gradientEnd.x.toFloat(), geometry.gradientEnd.y.toFloat())

    val dx = end.x - start.x
    val dy = end.y - start.y
    if (sqrt(dx * dx + dy * dy) < 1f) {
        // Degenerate axis: zero-sized element, or a softness of zero. A gradient with no length
        // is undefined; an opaque mask leaves the content alone, which is the safe answer.
        return SolidColor(Color.White)
    }

    val last = samples.size - 1
    val stops = Array(samples.size) { i ->
        val location = i.toFloat() / last.toFloat()
        location to Color.White.copy(alpha = samples[i].toFloat().coerceIn(0f, 1f))
    }
    return Brush.linearGradient(*stops, start = start, end = end, tileMode = TileMode.Clamp)
}
