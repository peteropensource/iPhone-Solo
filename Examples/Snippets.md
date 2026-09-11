# Copy-paste snippets

## The whole thing

```swift
import TiltFold

ZStack {
    Color.black
    MyView()
        .tiltFold()
}
.ignoresSafeArea()
```

## Tilt as a gesture rather than as ambience

Peek at a layer underneath by tilting, and let it snap back.

```swift
@State private var roll: Angle = .zero

ZStack {
    SecondLayer()
    FrontLayer()
        .tiltFold(roll: roll)
}
.gesture(
    DragGesture()
        .onChanged { roll = .degrees(clamp($0.translation.width / 3, -90, 90)) }
        .onEnded { _ in
            withAnimation(.spring(response: 0.5, dampingFraction: 0.8)) { roll = .zero }
        }
)
```

## As a navigation transition

```swift
MyPage()
    .tiltFold(roll: .degrees(isLeaving ? -75 : 0),
              configuration: .dramatic)
    .animation(.easeInOut(duration: 0.45), value: isLeaving)
```

## Reading the tilt yourself

```swift
@StateObject private var monitor = TiltMonitor()

Text(String(format: "%.1f° · root: %@", monitor.roll.degrees, monitor.rootEdgeName))
    .onAppear { monitor.start() }
    .onDisappear { monitor.stop() }
```

`TiltMonitor.roll` is settable, so you can drive it from anything: a slider, a test, a recorded
trace. `ingest(_:)` takes a raw gravity vector if you already have one from somewhere else.

## Matching an overlay to the content

```swift
ZStack {
    Wallpaper().tiltFold(roll: monitor.roll)

    Badge()
        .tiltFoldOverlay(roll: monitor.roll, at: UnitPoint(x: 0.84, y: 0.61))
}
```

## Dissolving into a second layer instead of into black

There is no built-in for this yet; section 9 of `docs/ALGORITHM.md` gives the mirrored curves.
Put the second layer underneath with the inverted masks and the two hand over at phase 0.5.
