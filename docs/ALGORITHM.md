# The TiltFold algorithm

*TiltFold is the effect. iPhone Solo is the name on the box.*

This document is the portable specification. It describes the effect in terms of geometry and
two response curves, with no reference to Swift, SwiftUI or any other framework, so that it can
be reimplemented on any platform that can rotate a texture in 3D and mask it with a gradient.

The Swift package in `Sources/TiltFold` and the zero-dependency web port in `Web/` are both
implementations of exactly what follows. If they ever disagree with this document, this
document is right and the code is a bug.

## 1. The mental model

Do not think of it as a folding screen. Think of it like this:

> The content is a flat picture hanging in 3D space. The device is a window you look through.
> Tilting the device does not move the picture. It moves your eye.

Everything else follows from that sentence. Lying flat, you look at the picture head-on and it
fills the window. As you tilt, the picture swings away from you around the edge that is closest
to the ground, and the far part of it recedes, goes out of focus, and finally leaves the window
entirely. At 90 degrees you are looking at the picture edge-on and it is gone.

The blur is not decoration. It is the thing that sells the depth: your eye accepts that
something is receding when it also loses focus, in the same direction, at the same rate.

## 2. Inputs

| Symbol | Meaning | Range |
|---|---|---|
| `roll` | signed tilt of the device around its long axis, measured from lying flat | -90 to +90 degrees |
| `W`, `H` | size of the viewport in layout units (points, CSS pixels) | > 0 |
| `blackAt` | tilt at which the content is fully gone | 30 to 90 degrees, default 75 |
| `deadzone` | tilt below which nothing happens | 0 to 15 degrees, default 3 |
| `softness` | length of the dissolve front, in units of the root-to-far-edge distance | 0.1 to 2.0, default 1.0 |
| `eyeDistance` | distance from the viewer to the screen plane, as a multiple of `max(W, H)` | 0.8 to 5, default 2.0 |

`roll` is the only live input. Everything else is a tuning constant.

Only left/right tilt is used. Front/back pitch is deliberately ignored: a phone resting in your
hand is almost never at zero pitch, so reacting to it makes the effect fire constantly. If you
want an omnidirectional version, section 9 explains what changes.

### Getting `roll` from a gravity vector

Given gravity in the device frame `g = (gx, gy, gz)`, with `+x` right, `+y` toward the top of
the screen and `+z` out of the screen (so lying flat, screen up, is `g ≈ (0, 0, -1)`):

```
roll = atan2(gx, -gz)
```

`gy` does not appear. That is what restricts the effect to left/right. The sign of `roll` picks
the hinge: negative means the left edge is lower, positive means the right edge is lower.

Smooth the gravity vector before using it, or sensor noise will make the content shimmer while
the device sits still on a table. A one-pole filter is enough:

```
g_smoothed = g_smoothed * (1 - k) + g_raw * k        with k ≈ 0.3 at 60 Hz
```

## 3. Derived scalars

```
tilt      = clamp(|roll| - deadzone, 0, 90)                 // degrees of effective tilt
progress  = clamp(tilt / (blackAt - deadzone), 0, 1)        // 0 = untouched, 1 = fully gone
direction = roll < 0 ? (-1, 0) : (+1, 0)                    // unit vector, screen coords, +y down
```

`direction` points at the **root**: the edge that is currently lowest. The root is the hinge the
picture pivots around, the last place to blur, and the first place to come back. Every other
quantity in this document is measured relative to it.

Note that `tilt` (which drives the 3D rotation) and `progress` (which drives the dissolve) are
two different numbers derived from the same input. Keeping them separate is what lets you tune
"how far it leans" independently from "how fast it disappears".

## 4. The 3D transform

Let `C = (W/2, H/2)` be the viewport centre and `d = direction`.

The half-extent from the centre to the farthest point along `d`:

```
E = |d.x| * W/2 + |d.y| * H/2
```

The hinge is the point on the low edge, on the line through the centre along `d`:

```
Hinge = C + d * E
```

Build the matrix as a sequence of operations applied to the picture, in this order:

1. Translate by `-Hinge`, putting the hinge at the origin.
2. Rotate by `tilt` radians about the axis `(d.y, -d.x, 0)`. That axis lies **along** the hinge
   line, perpendicular to the tilt direction. With this orientation a positive angle pushes the
   far edge to negative z, which is away from the viewer. If your effect leans the wrong way,
   negate this axis; the handedness of your coordinate system decides the sign.
3. Translate by `Hinge - C`, so the hinge returns to its place relative to the centre.
4. Apply perspective: the identity matrix with `m34 = -1 / (eyeDistance * max(W, H))`. This is
   the standard "one over eye distance" entry that makes `w` grow with depth.
5. Translate by `+C`, moving the origin back to the viewport centre.

Steps 3 through 5 are what make the perspective vanishing point sit at the centre of the
viewport rather than at the hinge. If you skip them the picture will skew instead of recede.

In CSS the same thing is expressed far more briefly, because `transform-origin` does steps 1
and 3 for you and `perspective-origin` does steps 4 and 5:

```css
/* for a left root */
perspective: calc(2 * var(--max-dimension));
perspective-origin: 50% 50%;
transform-origin: left center;
transform: rotateY(var(--tilt));
```

## 5. The dissolve phase field

Every point in the picture gets a scalar **phase** `s`:

- `s = 0` means untouched: sharp and fully opaque.
- `s = 1` means fully dissolved: gone, showing the background.

Let `u` be the normalised distance from the root, so `u = 0` on the low edge and `u = 1` on the
high edge. A straight **front** sweeps from the far edge toward the root as `progress` runs from
0 to 1. The front's leading position is:

```
front = 1 - progress * (1 + softness)
```

and phase is a linear ramp of width `softness` behind it:

```
s(u) = clamp((u - front) / softness, 0, 1)
```

Check the endpoints. At `progress = 0`, `front = 1`, so `s = 0` everywhere: nothing has started.
At `progress = 1`, `front = -softness`, so `s = 1` everywhere including at `u = 0`: everything is
gone, root included. That is why the `(1 + softness)` factor is there. Drop it and the root
never finishes dissolving.

### Expressing the field as a linear gradient

You almost never want to evaluate `s` per pixel yourself. Both implementations express it as a
linear gradient whose start point is the locus of `s = 0` and whose end point is the locus of
`s = 1`, then let the graphics stack interpolate. In viewport coordinates, with
`t = 1 - 2u` running from `+1` at the low corner to `-1` at the high corner:

```
point(u) = C + d * (1 - 2u) * E

gradientStart = point(front)             // s = 0
gradientEnd   = point(front + softness)  // s = 1
```

Sample your curves at, say, 24 evenly spaced values of `s` and emit them as gradient colour
stops. The masks then come for free and are interpolated on the GPU.

### Phase at an arbitrary point

To make an overlay (a button, a badge) dissolve in step with the content, project its centre
onto the gradient axis:

```
a = gradientEnd - gradientStart
s = clamp(dot(p - gradientStart, a) / dot(a, a), 0, 1)
```

This returns exactly the value the gradient mask produces at `p`, so the overlay and the content
stay locked together.

## 6. The two response curves

Both are functions of `s` alone. `smoothstep(e0, e1, x)` is the usual Hermite ramp:
`t = clamp((x - e0) / (e1 - e0), 0, 1)`, result `t * t * (3 - 2t)`.

**Blur.** With `N` levels in the ladder (see section 7), the fractional level index is:

```
blurIndex(s) = (N - 1) * smoothstep(0, 0.65, s)
```

**Opacity.**

```
alpha(s) = 1 - smoothstep(0.3, 1.0, s)
```

The two constants that matter are the `0.65` and the `0.3`, and the relationship between them is
the whole trick:

- Blur finishes early, at `s = 0.65`. By the time a region starts disappearing it is already
  maximally out of focus.
- Opacity starts late, at `s = 0.3`. Content softens for a while before it begins to fade.

So the order of events at any given point is **sharp → blurred → blurred and fading → gone**. If
you make these overlap evenly the content just fades out and looks cheap. The offset is what
makes it read as depth instead of a crossfade.

## 7. The blur ladder

Rendering a live Gaussian blur whose radius varies per pixel is expensive and, on most stacks,
not directly expressible. Instead, pre-render `N` copies of the picture at fixed blur radii and
cross-fade between neighbours.

Sigma is given as a fraction of the picture's width, so the effect looks identical at any
resolution. For level `i` of `N`:

```
sigmaFraction(0) = 0                                  // the original, sharp
sigmaFraction(i) = 0.09 * (i / (N - 1)) ^ 1.75
```

With the default `N = 4` that is `0`, `0.0132`, `0.0443`, `0.09`.

The exponent makes the spacing geometric rather than linear. Linear spacing wastes levels at the
sharp end, where the eye is most sensitive to a step between neighbours, and leaves a visible
jump at the blurry end. Anything between 1.5 and 2 looks fine; below 1.2 the ladder starts to
band.

**Compositing.** Stack the levels with the blurriest at the bottom and the sharp one on top.
Mask level `i` with:

```
levelMask(i, s) = 1 - clamp(blurIndex(s) - i, 0, 1)
```

Under ordinary source-over compositing this produces an exact cross-fade between neighbouring
levels: where `blurIndex` is 1.7, level 1 is 30% visible over a fully visible level 2, and
everything blurrier is hidden behind them. Then mask the whole stack with `alpha(s)`.

Render the blurred levels at half resolution. Nobody can tell, and it makes the Gaussian passes
several times cheaper.

## 8. Edge feathering, the part everyone gets wrong

A naive implementation clamps the blur to the picture's bounds and crops the result. The blurred
levels then end in a hard straight line exactly where the sharp picture ends, and because that
line survives the 3D projection you get a crisp geometric edge sitting in the middle of an
otherwise soft dissolve. It looks like a photograph of a screen, not like something receding.

The fix has two halves:

1. **Do not clamp.** Let the blur read transparent black outside the picture's bounds, so the
   alpha channel falls off smoothly across the border.
2. **Keep the overspill.** Render each blurred level into a canvas expanded by a margin of
   `3 * sigma` on every side, and draw it at that larger size, centred on the same point. The
   feathered edge then extends past where the sharp picture ends and melts into the background.

Every level has a different margin, so each is drawn at a different size. They all share a
centre and a scale, so they stay registered. Build the gradient masks in viewport coordinates
and convert to each level's local coordinates, rather than the other way around.

The sharp level keeps its hard edge, but it is only ever visible near the root, where it
coincides with the viewport border anyway.

## 9. Variations

**Omnidirectional.** Use the full in-plane gravity component instead of just `gx`:

```
planar   = hypot(gx, gy)
tilt     = atan2(planar, -gz)
direction = (gx / planar, -gy / planar)     // guard planar > 0.02
```

Everything downstream already takes `direction` as an arbitrary unit vector, so nothing else
changes. The hinge becomes whichever corner or edge is lowest, and the dissolve front tilts with
it. It looks impressive and is almost unusable in the hand, which is why the default is
left/right only.

**Dissolving into content instead of black.** Put a second picture underneath and give it the
mirrored curves: `alpha' = smoothstep(0, 0.5, s)` and `blurIndex' = (N-1) * (1 - smoothstep(0.4, 1, s))`.
The two layers hand over at `s = 0.5` so the composite never dips to the background. This is
closer to what a real folding phone does when it reveals a second screen.

**Tilt as input rather than decoration.** Nothing here requires the driving value to come from a
sensor. Drive `roll` from a drag gesture, a scroll offset, or an animation and the same code
becomes a transition rather than an ambient effect.

## 10. Reference values

For a 402 x 874 viewport with the defaults above, a left root and `roll = -26°`:

| Quantity | Value |
|---|---|
| `tilt` | 23° |
| `progress` | 0.32 |
| `front` | 0.36 |
| `s` at the right edge (`u = 1`) | 0.64 |
| `s` at the centre (`u = 0.5`) | 0.14 |
| `blurIndex` at the right edge | 3.00 |
| `alpha` at the right edge | 0.52 |

If your port produces these numbers, the maths is right and anything that still looks wrong is
in the compositing or the transform.
