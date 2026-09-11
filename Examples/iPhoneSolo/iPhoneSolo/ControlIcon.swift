import SwiftUI

/// A fake home-screen app icon. Styled like any other iOS icon so the demo screen still reads as
/// a plain home screen, which is the whole joke.
struct ControlIcon: View {
    let action: () -> Void

    private let side: CGFloat = 60
    /// Apple's squircle ratio for home-screen icons.
    private var radius: CGFloat { side * 0.2237 }

    var body: some View {
        Button(action: action) {
            VStack(spacing: 5) {
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .fill(LinearGradient(colors: [Color(red: 0.31, green: 0.33, blue: 0.39),
                                                  Color(red: 0.10, green: 0.11, blue: 0.14)],
                                         startPoint: .top, endPoint: .bottom))
                    .frame(width: side, height: side)
                    .overlay {
                        Image(systemName: "slider.horizontal.3")
                            .font(.system(size: 26, weight: .medium))
                            .foregroundStyle(.white)
                    }
                    .overlay {
                        RoundedRectangle(cornerRadius: radius, style: .continuous)
                            .strokeBorder(.white.opacity(0.14), lineWidth: 0.5)
                    }
                    .shadow(color: .black.opacity(0.28), radius: 5, y: 2)

                Text("Tilt")
                    .font(.system(size: 11))
                    .foregroundStyle(.white)
                    .shadow(color: .black.opacity(0.55), radius: 2, y: 0.5)
            }
        }
        .buttonStyle(.plain)
    }
}
