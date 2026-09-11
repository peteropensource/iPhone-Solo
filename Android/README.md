# iPhone Solo for Android

A Jetpack Compose port of the TiltFold effect. Same specification as the SwiftUI and web
implementations: [`../docs/ALGORITHM.md`](../docs/ALGORITHM.md).

## Read this before you trust it

**This module has never been compiled or run.** It was written on a machine with no JDK, no
Gradle, no Android SDK and no emulator, so the Android half of it could not be built, and nothing
here has been seen on a screen.

What *was* verified, and how:

| Part | Status |
|---|---|
| `TiltFoldMath.kt`, `TiltFoldConfig.kt` | Compiled clean with `kotlinc`, no warnings. They import nothing but `kotlin.math`, which is exactly why they are separate files. Checked against the reference table in `ALGORITHM.md` section 10 — all twenty checks in `verify-math.sh` pass, including the Android gravity sign convention. |
| `TiltFoldMathTest.kt` | Never run by JUnit, which needs Gradle. Every assertion in it was executed by an equivalent standalone runner instead: 231 checks, all passing. The file itself was type-checked against a stub of the JUnit API, so its syntax and overload resolution are sound. |
| `TiltFold.kt`, `TiltMonitor.kt`, the sample app, every Gradle file | Parsed by `kotlinc` far enough to confirm there are no syntax errors. Not type-checked, not compiled, not run: there is no Compose on the classpath here. |

So: the arithmetic is right, and everything above the arithmetic is an educated first draft.
Expect to fix small things on first open, most likely an import, a dependency version or a
Compose API signature. If something is off, `ALGORITHM.md` is the source of truth and the Swift
implementation in `../Sources/TiltFold` is a working reference for the same decisions.

`verify-math.sh` re-runs the part that can be checked without any Android tooling:

```sh
./verify-math.sh          # needs only kotlinc, e.g. brew install kotlin
```

## Building it

There is no Gradle wrapper jar in this repository, because fabricating one would have meant
shipping a binary that was never run. Generate it, or let Android Studio do it for you:

```sh
cd Android
gradle wrapper          # if you have a system Gradle; writes gradlew, gradlew.bat and the jar
./gradlew :tiltfold:testDebugUnitTest
./gradlew :sample:installDebug
```

Opening `Android/` in Android Studio (Hedgehog or newer) also works and will offer to create the
wrapper itself. AGP 8.2 needs **JDK 17** to run.

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

**Perspective is expressed as a camera distance.** `graphicsLayer.cameraDistance` is not a
distance in pixels; Compose multiplies it by `DisplayMetrics.densityDpi`, and `densityDpi ==
160 * density`, so `distanceInPixels = cameraDistance * 160 * density`. `composeCameraDistance()`
does that conversion. The sanity check is Compose's own default of `8.0f`, which under this
relation is 1280dp, exactly the platform `View` default.

**The vanishing point sits at the hinge, not at the viewport centre.** Spec section 4 steps 3–5
move the perspective origin back to the centre of the viewport; CSS spells that
`perspective-origin: 50% 50%` while `transform-origin` stays at the hinge. Android's camera is
positioned *at* the transform origin, and `graphicsLayer` exposes no separate perspective origin,
so you get one or the other. Keeping the hinge pinned matters more than the symmetry of the
recession, so the hinge is the pivot and the far edge shears very slightly toward one corner
instead of contracting symmetrically about the midline. A larger `eyeDistance` reduces it. Fixing
it properly would mean building the 4×4 matrix by hand and pushing it through `Canvas.concat`,
which is much less certain API than `graphicsLayer`. Apart from that, `rotationY`,
`transformOrigin` and `cameraDistance` give the same result CSS gets from `transform-origin` and
`perspective`, without a hand-built matrix.

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

## Things most likely to need a fix on first compile

Listed honestly, roughly in order of suspicion:

1. **The fold leans the wrong way.** The most likely failure of the lot, and the one the spec
   predicts (section 4 step 2: *"if your effect leans the wrong way, negate this axis"*). The sign
   was derived, not observed: Compose's `rotationZ` turns content clockwise, which implies the
   rotations use right-handed formulas in a space with `+y` down, and therefore `+z` pointing
   *into* the screen — the opposite of the CoreAnimation frame the spec is written in, which
   negates the rotation about any in-plane axis. If the near edge recedes instead of the far one,
   flip `ROTATION_SIGN` in `TiltFold.kt`. It is a single named constant for exactly this reason.
2. **Compose BOM and Kotlin compiler extension versions.** They are tightly coupled. If the build
   complains, take the pairing from the official compatibility table rather than bumping one.
3. **`Canvas.saveLayer` and `BlendMode.DstIn` masking.** `drawMasked()` saves a layer that is
   deliberately *larger* than the element so the blur overspill survives, draws the content, then
   punches the gradient through with `DstIn`. Standard approach, but if the stack clips that layer
   to the element's bounds anyway it eats the feathered edge and you get the hard line from spec
   section 8.
4. **`BlurredEdgeTreatment.Unbounded`.** Same symptom, different cause: it is what lets the feather
   spill past the bounds in the first place. If the feather is clipped, try giving each level its
   own layout box grown by its margin instead of relying on unbounded overspill.
5. **`Brush.linearGradient(vararg Pair<Float, Color>, start, end, tileMode)`.** The vararg-of-pairs
   overload exists, but the spread of a 25-element `Array<Pair<...>>` into it is the kind of thing
   the compiler may argue about. Fall back to a named `colorStops` argument.
6. **`Modifier.composed`.** `Modifier.tiltFold` uses it. Stable in Compose 1.6, but it is the old
   way of writing a stateful modifier; bump Compose far forward and expect a deprecation warning
   here first.
7. **`LocalLifecycleOwner`'s package.** `TiltMonitor.kt` imports it from
   `androidx.compose.ui.platform`, correct for Compose 1.6. It moved to `androidx.lifecycle.compose`
   with lifecycle 2.8 / Compose 1.7, so it is the first import that breaks on a BOM bump.
8. **`src/main/kotlin` as a source directory.** The Kotlin Android plugin registers it
   automatically. If it somehow does not, rename the directories to `java` or add a `sourceSets`
   block.
9. **The sample's theme and manifest.** Minimal by design, and minimal Android resource files are
   exactly where a missing attribute bites.
10. **Hit testing through the 3D transform.** Anything interactive inside a `TiltFold` is touched
    through a perspective-projected layer. Compose maps pointer input through layer matrices, but
    do not assume it is exact under a `rotationY` with a short camera distance. The sample keeps
    its only control outside the fold.

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
