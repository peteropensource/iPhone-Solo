import SwiftUI
import TiltFold

/// Shows `tiltFold` applied to ordinary, live SwiftUI content rather than to a still image.
///
/// Everything inside the modifier keeps working while it dissolves: the list scrolls, the toggle
/// toggles, the progress ring animates. That is the difference between this and `TiltFoldPicture`,
/// which is faster but freezes whatever it was given.
struct ContentView: View {

    @StateObject private var monitor = TiltMonitor()
    @State private var dragRoll: Angle = .zero
    @State private var notificationsOn = true
    @State private var selection = 1

    /// Use the sensor when there is one, otherwise let the user drag.
    private var roll: Angle { monitor.isAvailable ? monitor.roll : dragRoll }

    var body: some View {
        ZStack {
            // Whatever you put behind shows through: the effect dissolves into transparency,
            // not into a colour.
            LinearGradient(colors: [Color(white: 0.06), .black],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()

            card
                .tiltFold(roll: roll, configuration: .init(blackAt: .degrees(70), softness: 1.1))

            if !monitor.isAvailable {
                VStack {
                    Spacer()
                    Text("Drag left or right")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .padding(.bottom, 28)
                }
            }
        }
        .contentShape(Rectangle())
        .gesture(
            DragGesture()
                .onChanged { value in
                    guard !monitor.isAvailable else { return }
                    dragRoll = .degrees(clamp(Double(value.translation.width) / 3, -90, 90))
                }
                .onEnded { _ in
                    guard !monitor.isAvailable else { return }
                    withAnimation(.spring(response: 0.5, dampingFraction: 0.8)) {
                        dragRoll = .zero
                    }
                }
        )
        .onAppear { monitor.start() }
        .onDisappear { monitor.stop() }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Image(systemName: "sparkles")
                    .font(.title2)
                    .foregroundStyle(.tint)
                Text("Live content")
                    .font(.title2.weight(.semibold))
                Spacer()
            }
            .padding(20)

            Divider().overlay(.white.opacity(0.1))

            Picker("", selection: $selection) {
                Text("One").tag(0)
                Text("Two").tag(1)
                Text("Three").tag(2)
            }
            .pickerStyle(.segmented)
            .padding(20)

            Toggle("Still interactive while folded", isOn: $notificationsOn)
                .padding(.horizontal, 20)

            ProgressView(value: 0.62)
                .padding(20)

            ForEach(0..<4) { index in
                HStack(spacing: 14) {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(.tint.opacity(0.25))
                        .frame(width: 40, height: 40)
                        .overlay(Text("\(index + 1)").font(.headline))
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Row \(index + 1)").font(.body.weight(.medium))
                        Text("Blurs and fades with everything else")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 8)
            }

            Spacer(minLength: 20)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(white: 0.13), in: RoundedRectangle(cornerRadius: 28, style: .continuous))
        .padding(20)
    }
}

#Preview {
    ContentView().preferredColorScheme(.dark)
}
