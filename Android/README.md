# iPhone Solo for Android

A Jetpack Compose port of the TiltFold effect. Same specification as the SwiftUI and web
implementations: [`../docs/ALGORITHM.md`](../docs/ALGORITHM.md).

## Status

Built, tested and run. The library compiles, the 22 unit tests pass, and the sample app has been
installed on an Android 14 emulator (Pixel 7, arm64, API 34) and driven through its whole range
with the emulator's virtual gravity sensor. Screenshots at rest, at ±30° and at 50° all match the
specification: the root edge stays sharp and pinned, the far edge recedes and dissolves, and the
two directions mirror each other.

Two things were wrong when it was written blind, and both are fixed here. They are recorded
because they are exactly the kind of thing that only a screen tells you:

**The camera was 160 times too close.** `graphicsLayer.cameraDistance` is multiplied by the
display density before it reaches the platform layer, so it is `density` pixels per unit, not
`160 * density`. With the wrong divisor the camera sat 30 pixels from a 1080-pixel-wide view and
bent the content into an unreadable wedge. It compiled, the unit tests passed, and the arithmetic
was right; only the render showed it.

**The vanishing point was at the hinge.** `graphicsLayer` puts the camera wherever
`transformOrigin` is and gives you no separate perspective origin, so pivoting at the hinge drags
the vanishing point there too. The fix is to pivot at the centre, which is where the specification
wants the vanishing point, and then translate the layer to put the hinge back on its edge.
`hingeDriftFraction` in `TiltFoldMath.kt` computes that translation.

## Building it

The Gradle wrapper is checked in, so you need nothing but a JDK 17 and an Android SDK:

```sh
cd Android
./gradlew :tiltfold:testDebugUnitTest    # the spec conformance tests
./gradlew :sample:installDebug           # onto a device or a running emulator
```

Opening `Android/` in Android Studio works too. AGP 8.2 needs **JDK 17**; anything newer as the
Gradle JDK will fail with a version error rather than a useful message.

`verify-math.sh` still exists and still needs nothing but `kotlinc`, which is useful when you want
to check the arithmetic without an Android toolchain at all.

Versions: Kotlin 1.9.22, Compose BOM 2024.02.00, AGP 8.2.x, compileSdk 34, minSdk 26, targetSdk 34.
Dependencies are limited to AndroidX Compose, core-ktx, activity-compose and
lifecycle-runtime-compose, plus JUnit for tests.

## The blur, and why minSdk 26 still says API 31

`Modifier.blur` only does anything from API 31 (Android 12). Below that the platform has no
RenderEffect to hang a blur on and the modifier is documented as a no-op.

Rather than crash, refuse to run, or silently look broken, the library checks
`tiltFoldBlurSupported` and collapses to a single sharp level below API 31. You still get the
perspective fold and the directional fade, just without the progressive defocus. That is a
noticeably weaker effect, but it is a coherent one, and it means you can set `minSdk 26` without
branching at every call site.

If you need the full effect below API 31, the honest options are a RenderScript-era blur, a
pre-rendered blur ladder for static content (which is what `TiltFoldPicture` does on iOS), or
simply gating the feature on `Build.VERSION.SDK_INT`.

## Using it

Two entry points, matching the SwiftUI API as closely as Compose allows.

```kotlin
import dev.tiltfold.TiltFold
import dev.tiltfold.TiltFoldConfig
import dev.tiltfold.rememberTiltMonitor
import dev.tiltfold.tiltFold

// The real thing: the full blur ladder
val monitor = rememberTiltMonitor()
TiltFold(roll = monitor.roll) {
    MyScreen()
}

// One pass, for an overlay or for content too expensive to draw four times
SettingsButton(Modifier.tiltFold(roll = monitor.roll))
```

`roll` is in degrees, signed: negative puts the root on the left edge, positive on the right.
Nothing is drawn behind the content, so it dissolves into whatever is underneath. Put a black
surface behind it to match the video.

The two entry points are not interchangeable. `TiltFold` draws the whole blur ladder and is the
faithful implementation. `Modifier.tiltFold` is the sibling of the Swift port's
`tiltFoldOverlay`: same geometry, same opacity curve, same fold, but one uniform blur taken from
the ladder at the phase of the element's own centre, because a modifier has no way to draw its
content more than once at different radii.

Two things about `TiltFold` that will bite you:

* **Your content is composed once per blur level**, four times by default. Compose offers no
  public, stable way to re-draw one rendered subtree at several blur radii, so the `content`
  lambda is simply invoked `levels` times. Hoist any state it owns. A `LazyColumn` inside
  `TiltFold` becomes four lists with four independent scroll positions, which come apart the
  moment anyone scrolls.
* **Do not clip the parent.** Blurred levels deliberately spill past the layout bounds so their
  feathered edges melt into the background (spec section 8). A parent with `clip = true` cuts
  them off in a straight line, which is exactly the hard geometric edge the spec spends a section
  warning about.

Drive `roll` from something other than gravity and the same code becomes a transition:

```kotlin
var roll by remember { mutableFloatStateOf(0f) }

Box(
    Modifier
        .tiltFold(roll = roll)
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = { roll = 0f },
                onHorizontalDrag = { _, delta -> roll = (roll + delta / 3f).coerceIn(-90f, 90f) }
            )
        }
) { MyCard() }
```

### Tuning

```kotlin
TiltFoldConfig(
    blackAt = 75.0,        // tilt at which the content is fully gone
    deadzone = 3.0,        // below this nothing happens; hides sensor noise
    softness = 1.0,        // length of the dissolve front
    eyeDistance = 2.0,     // smaller means stronger perspective
    levels = 4,            // steps in the blur ladder
    omnidirectional = false
)
```

`TiltFoldConfig.subtle` and `TiltFoldConfig.dramatic` are starting points.

## Where Android forced a departure from the spec

**Gravity points the other way.** `TYPE_GRAVITY` on Android returns a vector pointing *away* from
the ground, so a device lying flat face-up reads roughly `(0, 0, +9.81)`. The spec is written
against the iOS convention, where the same pose reads `(0, 0, -1)`. Substituting `g_ios =
-g_android` turns the spec's `atan2(gx, -gz)` into `atan2(-gx, gz)`, which is what
`rollFromGravity` does. This is the single most likely thing to be backwards if the effect leans
the wrong way on a device, so it is asserted in both the unit test and `verify-math.sh`.

**Perspective is expressed as a camera distance, in its own units.**
`graphicsLayer.cameraDistance` is not a distance in pixels. Compose multiplies it by the display
density on its way to the platform layer, so `distanceInPixels = cameraDistance * density`.
`composeCameraDistance()` does that conversion. Get this wrong and the effect still compiles, the
unit tests still pass, and the render is unusable: see Status above.

**The pivot and the camera are the same point.** Spec section 4 steps 3 to 5 put the pivot at the
hinge and the vanishing point at the viewport centre. CSS can do both at once, with
`transform-origin` on one and `perspective-origin` on the other. `graphicsLayer` has only
`transformOrigin`, and the camera goes wherever it goes. So this port pivots at the centre, which
is the half the eye notices, and then translates the layer by `hingeDriftFraction` to put the
hinge back on its edge. That is not identical to a true rotation about the hinge, since the
translation happens after the projection rather than before it, but with the camera at the
specified distance the two differ by well under a pixel at the hinge and by a few tenths of a
percent elsewhere.

**Blur radius is not Gaussian sigma.** The ladder is specified in sigma. `Modifier.blur` takes a
"radius" that ends up in `RenderEffect.createBlurEffect`, where Skia converts it as roughly
`sigma ≈ 0.577 · radius + 0.5`. This port passes the ladder's sigma values straight through as
radii, exactly as the Swift port passes them to SwiftUI's `.blur(radius:)`, so in absolute terms
the Android ladder is a little softer than a Core Image one built from the same numbers. What the
effect depends on is the *ratios* between levels, and those are untouched. The `3 · sigma` feather
margin becomes a generous over-estimate rather than an exact one, which is the safe direction to
be wrong in.

**Half-resolution blur levels are not implemented.** Spec section 7 suggests rendering the blurred
levels at half resolution to make the Gaussian passes cheaper. `RenderEffect` blurs happen on the
layer as drawn and there is no stable Compose API for rendering a subtree at reduced resolution.
The real cost here is composing the content N times, which is a different problem.

**`TYPE_ACCELEROMETER` is the fallback when there is no `TYPE_GRAVITY`.** At rest they read the
same thing; in the hand the accelerometer also carries whatever you are doing with it, so the
fallback gets a heavier one-pole constant (0.12 rather than the spec's 0.3). Sampling is at
`SENSOR_DELAY_GAME`, roughly 50 Hz against the spec's 60 Hz assumption, which makes the filter
marginally slower in wall-clock terms and is not worth converting for.

## What actually broke, and what did not

Written blind, then compiled and run for the first time. For anyone porting this to another
framework, the split is worth knowing.

Compiled first try, no changes: the Compose masking with `saveLayer` and `BlendMode.DstIn`, the
`BlurredEdgeTreatment.Unbounded` overspill, `Brush.linearGradient` with a spread 25-element array
of stops, `Modifier.composed`, `LocalLifecycleOwner`'s import, the `src/main/kotlin` source set,
the sample's theme and manifest, and the Kotlin 1.9.22 / Compose BOM 2024.02.00 pairing. The
rotation sign was right, which was the thing predicted most likely to be wrong.

Broke, and only on a screen: the two transform problems in Status above. Both are cases where
every automated check available was green and the picture was still wrong. If you port this
somewhere new, budget for looking at it rather than only testing it.

## Layout

```
Android/
  settings.gradle.kts, build.gradle.kts, gradle.properties
  gradle/wrapper/gradle-wrapper.properties   (no jar; generate it)
  verify-math.sh                             (runs without any Android tooling)
  tiltfold/                                  the library, package dev.tiltfold
    src/main/kotlin/dev/tiltfold/
      TiltFoldMath.kt      pure Kotlin, no Android imports, independently verified
      TiltFoldConfig.kt    tuning
      TiltMonitor.kt       SensorManager, TYPE_GRAVITY with an accelerometer fallback
      TiltFold.kt          the Compose layer
    src/test/kotlin/...    unit tests against the spec, never executed
  sample/                  a one-Activity demo, package dev.tiltfold.sample
```
