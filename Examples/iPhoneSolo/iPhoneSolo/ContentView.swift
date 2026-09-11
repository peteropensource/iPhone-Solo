import SwiftUI
import TiltFold

/// The demo from the video: a home screen screenshot that dissolves around whichever side edge
/// is lowest, with a tuning panel hidden behind a fake home-screen icon.
///
/// The effect itself is three lines. Everything else in this file is the demo furniture.
struct ContentView: View {

    @StateObject private var monitor = TiltMonitor()
    @State private var picture: BlurredPicture?
    @State private var showControls = false

    @State private var blackAt: Double = 75
    @State private var softness: Double = 1.0
    @State private var eyeDistance: Double = 2.0
    @State private var deadzone: Double = 3

    /// Lines up with the home-screen grid of the bundled screenshot: last column, first empty row.
    private let iconPosition = UnitPoint(x: 0.844, y: 0.609)

    private var configuration: TiltFoldConfiguration {
        TiltFoldConfiguration(blackAt: .degrees(blackAt),
                              deadzone: .degrees(deadzone),
                              softness: softness,
                              eyeDistance: eyeDistance)
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let picture {
                TiltFoldPicture(picture, roll: monitor.roll, configuration: configuration)
                    .ignoresSafeArea()
                    .contentShape(Rectangle())
                    .onTapGesture { if showControls { setControls(false) } }
            } else {
                ProgressView("Preparing blur levels…").tint(.white)
            }

            if !showControls {
                ControlIcon { setControls(true) }
                    .tiltFoldOverlay(roll: monitor.roll,
                                     at: iconPosition,
                                     configuration: configuration)
                    .ignoresSafeArea()
                    .transition(.opacity.combined(with: .scale(scale: 0.92)))
            }

            if showControls {
                VStack {
                    Spacer()
                    controls.transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
        }
        .statusBarHidden(true)
        .task {
            picture = await BlurredPicture.make(named: "HomeBase")
            monitor.start()
        }
    }

    private func setControls(_ open: Bool) {
        withAnimation(.easeInOut(duration: 0.22)) { showControls = open }
    }

    // MARK: Tuning panel

    private var controls: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: "arrow.right")
                    .font(.title3.weight(.bold))
                    .rotationEffect(.degrees(monitor.roll.degrees < 0 ? 180 : 0))
                    .frame(width: 28, height: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text(String(format: "roll %.1f°  ·  root: %@  ·  dissolve %.2f",
                                abs(monitor.roll.degrees), monitor.rootEdgeName,
                                configuration.progress(forRoll: monitor.roll)))
                        .font(.system(.footnote, design: .monospaced))
                    Text(monitor.isAvailable ? "live sensor" : "no motion sensor – drag the Roll slider")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }

            if !monitor.isAvailable {
                HStack(spacing: 8) {
                    Text("Roll").font(.caption).frame(width: 66, alignment: .leading)
                    Text("L").font(.caption2).foregroundStyle(.secondary)
                    Slider(value: Binding(get: { monitor.roll.degrees },
                                          set: { monitor.roll = .degrees($0) }), in: -90...90)
                    Text("R").font(.caption2).foregroundStyle(.secondary)
                    Text(String(format: "%.0f°", monitor.roll.degrees))
                        .font(.system(.caption, design: .monospaced))
                        .frame(width: 44, alignment: .trailing)
                }
            }

            Divider().overlay(.white.opacity(0.2))

            slider("Black at", $blackAt, 30...90, "%.0f°")
            slider("Softness", $softness, 0.1...2.0, "%.2f")
            slider("Eye dist", $eyeDistance, 0.8...5, "%.1f×")
            slider("Deadzone", $deadzone, 0...15, "%.0f°")

            HStack {
                Text("Tap anywhere outside to close.")
                    .font(.caption2).foregroundStyle(.secondary)
                Spacer()
                Button("Done") { setControls(false) }
                    .font(.caption.weight(.semibold))
            }
        }
        .padding(14)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .padding(.horizontal, 12)
        .padding(.bottom, 24)
    }

    private func slider(_ title: String, _ value: Binding<Double>,
                        _ range: ClosedRange<Double>, _ format: String) -> some View {
        HStack(spacing: 8) {
            Text(title).font(.caption).frame(width: 66, alignment: .leading)
            Slider(value: value, in: range)
            Text(String(format: format, value.wrappedValue))
                .font(.system(.caption, design: .monospaced))
                .frame(width: 44, alignment: .trailing)
        }
    }
}

#Preview {
    ContentView().preferredColorScheme(.dark)
}
