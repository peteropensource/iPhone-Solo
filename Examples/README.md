# Examples

Both are plain Xcode projects that depend on the package by relative path (`../..`), so open
either one and hit Run. No package resolution, no checkout, no setup. You will need to pick your
own Team under **Signing & Capabilities** to run on a device; the Simulator needs nothing.

Neither example has a motion sensor in the Simulator, so both give you a way to drive the roll
by hand there. On a real device they switch to the gyroscope automatically.

## iPhoneSolo

The app from the video. A home-screen screenshot that folds around whichever side edge is
lowest, with the tuning panel hidden behind a fake home-screen app icon labelled *Tilt*.

Shows:

- `TiltFoldPicture`, the pre-rendered path, for still-image content.
- `BlurredPicture.make(named:)` building the blur ladder off the main actor at launch.
- `tiltFoldOverlay`, which keeps the fake icon glued to the content instead of floating over it.
- Live tuning of every knob, so you can find values you like before hard-coding them.

Swap `Assets.xcassets/HomeBase.imageset` for your own picture; any size works.

## LiveContent

`tiltFold()` applied to ordinary SwiftUI content rather than to an image: a card with a segmented
picker, a toggle, a progress bar and a list. Everything stays interactive while it folds, which
is the difference between this path and `TiltFoldPicture`.

Drag horizontally to drive it in the Simulator. It springs back when you let go.

Shows:

- The `tiltFold(roll:)` form, driven by a gesture rather than by gravity.
- That the effect dissolves into transparency, so the gradient behind it shows through.
- `TiltMonitor.isAvailable` used to choose between the sensor and a fallback control.
