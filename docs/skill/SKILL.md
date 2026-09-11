---
name: tiltfold
description: Implement the TiltFold effect in any UI framework - content that pivots around the device's lowest edge and dissolves, blur first then opacity, as the device is tilted. Use when asked to build a tilt-driven fold, unfold, dissolve or parallax-depth effect, to port TiltFold to a new platform (Flutter, Compose, React Native, web, Unity, WebGL), or to debug one that looks wrong.
---

# Implementing TiltFold

TiltFold makes on-screen content behave like a flat picture hanging in 3D space, seen through the
device as through a window. Tilt the device and the picture swings away around whichever side
edge is lowest, going out of focus and dissolving as it recedes.

## Before you write anything

Read `docs/ALGORITHM.md` in this repository. It is the complete specification: the geometry, the
phase field, the two response curves, the blur ladder and the edge feathering, plus a table of
reference numbers. Everything below assumes you have it open. Do not work from the Swift source
alone; the Swift source is one implementation of the spec, not the spec.

## The order to build it in

Build and check each stage before starting the next. Every stage is visible on screen, so you can
see which one broke.

1. **Roll.** Get a signed left/right tilt angle out of the platform, smoothed. Print it. Confirm
   that lying flat reads zero, that tipping the left edge down goes negative, and that pitching
   the device forward and back does not move it at all.
2. **Projection, no dissolve.** Rotate the content around the low edge with perspective. Confirm
   the hinge edge does not move, the far edge contracts symmetrically about the centreline, and
   the whole thing is identity at zero tilt.
3. **Opacity only.** Add the `alpha(s)` mask with no blur. You should get a directional wipe.
   Confirm it starts at the far edge and finishes at the root, and that at full progress nothing
   is left, including at the root.
4. **Blur ladder.** Add the levels and their cross-fade masks. This is where it starts looking
   like the real thing.
5. **Feathering.** Fix the hard edge on the blurred levels. See below; this is the step people
   skip and it is the difference between "nice" and "why does that look wrong".

## The five mistakes

These account for essentially every bad port.

**Fading and blurring on the same schedule.** The curves are deliberately offset: blur finishes
at phase 0.65, opacity does not start until 0.3. Content must be fully out of focus *before* it
starts to disappear. Line them up and you get an ordinary crossfade that looks cheap. If your
result looks like a dissolve rather than a recession, check this first.

**Clamping the blur to the content bounds.** The blurred levels then end in a hard straight line
exactly where the sharp content ends, and that line survives the 3D projection, so you get a
crisp geometric edge sitting in the middle of a soft dissolve. Let the blur read transparent
outside the bounds, render each level into a canvas grown by `3 * sigma`, and draw it at that
larger size on the same centre. Do not clip the container.

**Hinging the perspective.** Rotating around the hinge is correct; putting the vanishing point
there is not. The projection must be centred on the viewport, which means translating the origin
to the centre before applying the perspective term and back afterwards. Skip it and the content
shears toward a corner instead of receding.

**Dropping the `(1 + softness)` factor.** `front = 1 - progress * (1 + softness)`. Without the
factor the trailing end of the ramp never clears the root, so the root never fully dissolves and
the content refuses to go away at full tilt.

**Reacting to pitch.** Use only the roll component. A phone in the hand is never at zero pitch,
so an omnidirectional version fires constantly and feels broken even though the maths is right.
Ship left/right; offer omnidirectional as an option.

## Mapping the spec onto a framework

You need four capabilities. Almost every UI stack has all four.

| Spec needs | Look for |
|---|---|
| a signed roll angle | accelerometer or gravity vector; `atan2(gx, -gz)` |
| rotate a texture in 3D with perspective | 4x4 matrix with an `m34`-style term, or CSS `perspective` + `rotateY`, or a 3D transform node |
| a linear gradient used as an alpha mask | mask/clip layers, shader masks, `mask-image`, `BlendMode.dstIn` |
| N fixed-radius blurs | a blur filter you can apply per layer; radius need not be animatable |

If the stack has no per-layer blur, you can pre-render the ladder to bitmaps once and composite
those instead. That is exactly what the `TiltFoldPicture` path does and it is usually faster
anyway.

Two shortcuts worth knowing:

- **CSS does steps 1 and 3 through 5 of the transform for you.** `transform-origin: left center`
  handles the translate-rotate-translate, and `perspective` plus `perspective-origin: 50% 50%` on
  the parent handles the centred projection. The whole transform collapses to one `rotateY`.
- **Gradient stops beat per-pixel maths.** Sample each curve at about 24 points and emit them as
  colour stops, then let the GPU interpolate. You almost never need a shader.

## Checking your work

Numerically, reproduce section 10 of `ALGORITHM.md`. For a 402 x 874 viewport, a left root and a
roll of -26 degrees with default settings, you should get `progress` 0.32, `front` 0.36, phase
0.64 at the right edge, blur index 3.00 and alpha 0.52. If those match, the maths is right and
anything still wrong is in your compositing or your transform.

Visually, capture five states and look at each one:

- **Flat.** Sharp, fills the frame, pixel-identical to the content with the effect removed.
- **Roll -30.** Left edge sharp, right side blurred and fading. No hard edge anywhere, including
  at the top and bottom borders of the blurred region.
- **Roll +30.** The exact mirror. Getting one direction right and the other wrong means a sign
  error in the rotation axis.
- **Roll -80.** Essentially empty.
- **Coming back.** The root re-solidifies first and the far edge last.

## Deciding whether it should be live or pre-rendered

If the content does not change while the device is tilted, which covers wallpapers, photos,
album art and most screens, pre-render the blur ladder once and composite textures. It is
cheaper and the Gaussian is better.

Render the content once per level only when it genuinely has to stay live and interactive while
folded. Then keep the level count low, three is usually enough, and remember you are paying for
every level on every frame.

## Scope check before you start

This is a visual effect and it is small. If the request is "make the whole home screen do this",
say early that no mobile OS lets third-party code replace the home screen, that there is no
custom animated wallpaper API on iOS, and that widgets cannot read motion sensors in real time.
On iOS the effect lives inside an app. Android allows custom launchers and live wallpapers, which
is the only route to a real home screen.

The version worth building is usually the one where tilt is an **input** rather than decoration:
tilt to peek at a layer underneath, release to come back. That turns it from a garnish into a
gesture.
