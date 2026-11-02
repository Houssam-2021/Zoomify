import SwiftUI

/// Lens buttons (.5 / 1 / 2 / 3 ...) that turn into a zoom dial when you drag across them.
struct ZoomControls: View {
    @ObservedObject var state: ZoomUIState
    let onSelect: @MainActor (CGFloat) -> Void
    let onDialBegan: @MainActor () -> Void
    let onDialChanged: @MainActor (CGFloat) -> Void
    let onDialEnded: @MainActor () -> Void

    @State private var dragging = false

    var body: some View {
        ZStack(alignment: .bottom) {
            if state.dialVisible {
                ZoomDial(zoom: state.zoom, minZoom: state.minZoom, maxZoom: state.maxZoom, presets: state.presets)
                    .transition(.opacity.combined(with: .scale(scale: 0.95, anchor: .bottom)))
            } else {
                presetButtons
                    .transition(.opacity)
            }
        }
        .frame(height: 88, alignment: .bottom)
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
        .gesture(
            DragGesture(minimumDistance: 8)
                .onChanged { value in
                    if !dragging {
                        dragging = true
                        onDialBegan()
                    }
                    onDialChanged(value.translation.width)
                }
                .onEnded { _ in
                    dragging = false
                    onDialEnded()
                }
        )
        .animation(.easeOut(duration: 0.2), value: state.dialVisible)
    }

    private var activeIndex: Int {
        var index = 0
        for (i, preset) in state.presets.enumerated() where state.zoom >= preset * 0.995 {
            index = i
        }
        return index
    }

    private var presetButtons: some View {
        let active = activeIndex
        return HStack(spacing: 4) {
            ForEach(Array(state.presets.enumerated()), id: \.offset) { index, preset in
                let isActive = index == active
                Button {
                    onSelect(preset)
                } label: {
                    Text(isActive ? Self.label(state.zoom, active: true) : Self.label(preset, active: false))
                        .font(.system(size: isActive ? 13 : 11, weight: .semibold, design: .rounded))
                        .monospacedDigit()
                        .foregroundStyle(isActive ? Color.yellow : Color.white)
                        .frame(width: isActive ? 38 : 30, height: isActive ? 38 : 30)
                        .background(Circle().fill(Color.black.opacity(0.5)))
                        .frame(width: 40, height: 40)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(3)
        .background(Capsule().fill(Color.white.opacity(0.1)))
        .animation(.spring(response: 0.25, dampingFraction: 0.85), value: active)
    }

    /// "0.5×", "1×", "1.7×", "12×" for the active button, ".5", "1", "2" for the others.
    static func label(_ zoom: CGFloat, active: Bool) -> String {
        let rounded = zoom >= 10 ? zoom.rounded() : (zoom * 10).rounded() / 10
        var text = rounded.truncatingRemainder(dividingBy: 1) == 0
            ? String(format: "%.0f", rounded)
            : String(format: "%.1f", rounded)
        if !active, text.hasPrefix("0.") { text.removeFirst() }
        return active ? text + "×" : text
    }
}

/// Logarithmic zoom ruler: each doubling of zoom is the same distance on the dial.
struct ZoomDial: View {
    static let pointsPerOctave: CGFloat = 110

    let zoom: CGFloat
    let minZoom: CGFloat
    let maxZoom: CGFloat
    let presets: [CGFloat]

    var body: some View {
        VStack(spacing: 4) {
            Text(ZoomControls.label(zoom, active: true))
                .font(.system(size: 15, weight: .semibold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(Color.yellow)
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(Capsule().fill(Color.black.opacity(0.5)))

            Canvas { context, size in
                let centerX = size.width / 2
                let current = log2(Double(max(zoom, 0.01)))
                let lowest = log2(Double(max(minZoom, 0.01)))
                let highest = log2(Double(max(maxZoom, 0.01)))
                let ppo = Double(Self.pointsPerOctave)

                // Ticks every 1/10 octave, longer every half octave.
                let firstTick = Int((lowest * 10).rounded(.up))
                let lastTick = Int((highest * 10).rounded(.down))
                if firstTick <= lastTick {
                    for tick in firstTick...lastTick {
                        let x = centerX + CGFloat((Double(tick) / 10 - current) * ppo)
                        guard x > -4, x < size.width + 4 else { continue }
                        let major = tick % 5 == 0
                        var path = Path()
                        path.move(to: CGPoint(x: x, y: size.height - (major ? 16 : 9)))
                        path.addLine(to: CGPoint(x: x, y: size.height))
                        context.stroke(path, with: .color(.white.opacity(major ? 0.9 : 0.45)), lineWidth: major ? 1.5 : 1)
                    }
                }

                // Labels for the lenses and a few long-range stops.
                var marks = Set(presets)
                for stop in [10.0, 25.0, 50.0] as [CGFloat] where stop <= maxZoom + 0.01 { marks.insert(stop) }
                for mark in marks {
                    let x = centerX + CGFloat((log2(Double(mark)) - current) * ppo)
                    guard x > -20, x < size.width + 20 else { continue }
                    context.draw(
                        Text(ZoomControls.label(mark, active: false))
                            .font(.system(size: 11, weight: .semibold, design: .rounded))
                            .foregroundColor(.white),
                        at: CGPoint(x: x, y: size.height - 28)
                    )
                }

                // Centre indicator.
                var needle = Path()
                needle.move(to: CGPoint(x: centerX, y: size.height - 22))
                needle.addLine(to: CGPoint(x: centerX, y: size.height))
                context.stroke(needle, with: .color(.yellow), lineWidth: 2)
            }
            .frame(height: 44)
            .mask(
                LinearGradient(colors: [.clear, .black, .black, .clear], startPoint: .leading, endPoint: .trailing)
            )
        }
    }
}
