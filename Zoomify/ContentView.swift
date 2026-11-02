import AVFoundation
import SwiftUI
import UIKit

struct ContentView: View {
    @StateObject private var model = CameraModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if model.cameraAuthorized == false {
                PermissionView()
            } else {
                CameraScreen(model: model)
            }
        }
        .onAppear { model.start() }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { model.stopRecordingIfNeeded() }
        }
    }
}

private struct CameraScreen: View {
    @ObservedObject var model: CameraModel
    @State private var toastTask: Task<Void, Never>?

    private let topBarHeight: CGFloat = 52

    var body: some View {
        GeometryReader { geo in
            let width = geo.size.width
            let aspect: CGFloat = model.mode == .photo ? 4.0 / 3.0 : 16.0 / 9.0
            let previewHeight = min(width * aspect, geo.size.height)
            let previewTop = model.mode == .photo
                ? topBarHeight
                : max(0, (geo.size.height - previewHeight) / 2)

            ZStack(alignment: .top) {
                PreviewContainer(model: model)
                    .frame(width: width, height: previewHeight)
                    .clipped()
                    .offset(y: previewTop)

                VStack(spacing: 0) {
                    TopBar(model: model)
                        .frame(height: topBarHeight)
                    if let toast = model.toast {
                        Text(toast)
                            .font(.footnote.weight(.semibold))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 7)
                            .background(Capsule().fill(Color.black.opacity(0.7)))
                            .padding(.top, 8)
                            .transition(.opacity)
                    }
                    Spacer(minLength: 0)
                    BottomControls(model: model)
                }
            }
            .animation(.easeInOut(duration: 0.28), value: model.mode)
        }
        .onChange(of: model.toast) { _, newValue in
            guard newValue != nil else { return }
            toastTask?.cancel()
            toastTask = Task {
                try? await Task.sleep(for: .seconds(2.5))
                if !Task.isCancelled { model.toast = nil }
            }
        }
    }
}

// MARK: - Preview

private struct PreviewContainer: View {
    @ObservedObject var model: CameraModel
    @State private var pinching = false

    var body: some View {
        GeometryReader { geo in
            PreviewView(renderer: model.service.renderer)
                .overlay {
                    if let point = model.focusPoint {
                        FocusSquare()
                            .id("\(point.x),\(point.y)")
                            .position(point)
                    }
                }
                .overlay(
                    Color.black
                        .opacity(model.shutterBlink ? 0.85 : 0)
                        .animation(.linear(duration: 0.08), value: model.shutterBlink)
                        .allowsHitTesting(false)
                )
                .contentShape(Rectangle())
                .gesture(
                    MagnifyGesture()
                        .onChanged { value in
                            if !pinching {
                                pinching = true
                                model.pinchBegan()
                            }
                            model.pinchChanged(value.magnification)
                        }
                        .onEnded { _ in pinching = false }
                )
                .simultaneousGesture(
                    SpatialTapGesture()
                        .onEnded { value in model.focus(at: value.location, in: geo.size) }
                )
        }
    }
}

private struct FocusSquare: View {
    @State private var appeared = false

    var body: some View {
        Rectangle()
            .stroke(Color.yellow, lineWidth: 1.5)
            .frame(width: 72, height: 72)
            .scaleEffect(appeared ? 1 : 1.35)
            .onAppear {
                withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) { appeared = true }
            }
            .allowsHitTesting(false)
    }
}

// MARK: - Top bar

private struct TopBar: View {
    @ObservedObject var model: CameraModel

    var body: some View {
        HStack(spacing: 10) {
            if model.mode == .photo {
                Button { model.cycleFlash() } label: {
                    Image(systemName: model.flash.symbol)
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(model.flash == .off ? Color.white : Color.yellow)
                        .frame(width: 36, height: 36)
                        .background(Circle().fill(Color.white.opacity(0.12)))
                }
                Spacer()
                Chip(text: model.photoFormat.label, highlighted: model.photoFormat != .heif) {
                    model.cyclePhotoFormat()
                }
            } else {
                if model.hdrSupported {
                    Chip(text: "HDR", highlighted: model.hdrActive, strikethrough: !model.hdrVideo) {
                        model.toggleHDR()
                    }
                }
                Spacer()
                if model.isRecording, let start = model.recordingStarted {
                    RecordingTimer(start: start)
                }
                Spacer()
                Chip(text: model.codecLabel, highlighted: model.codec == .proRes) { model.cycleCodec() }
                Chip(text: "\(model.is4K ? "4K" : "HD") · 30", highlighted: false) {}
                    .allowsHitTesting(false)
            }
        }
        .padding(.horizontal, 14)
    }
}

private struct Chip: View {
    let text: String
    var highlighted: Bool
    var strikethrough = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text)
                .font(.system(size: 12, weight: .bold, design: .rounded))
                .strikethrough(strikethrough)
                .foregroundStyle(highlighted ? Color.black : Color.white)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(Capsule().fill(highlighted ? Color.yellow : Color.white.opacity(0.14)))
        }
        .buttonStyle(.plain)
    }
}

private struct RecordingTimer: View {
    let start: Date

    var body: some View {
        TimelineView(.periodic(from: start, by: 1)) { context in
            let elapsed = max(0, Int(context.date.timeIntervalSince(start)))
            Text(String(format: "%02d:%02d:%02d", elapsed / 3600, (elapsed / 60) % 60, elapsed % 60))
                .font(.system(size: 15, weight: .semibold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(Color.white)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(RoundedRectangle(cornerRadius: 5).fill(Color.red))
        }
    }
}

// MARK: - Bottom controls

private struct BottomControls: View {
    @ObservedObject var model: CameraModel

    var body: some View {
        VStack(spacing: 12) {
            ZoomControls(
                state: model.zoomUI,
                onSelect: { model.selectPreset($0) },
                onDialBegan: { model.dialBegan() },
                onDialChanged: { model.dialChanged($0) },
                onDialEnded: { model.dialEnded() }
            )

            ModeSelector(mode: model.mode, disabled: model.isRecording) { model.setMode($0) }

            HStack {
                ThumbnailButton(image: model.thumbnail)
                Spacer()
                ShutterButton(mode: model.mode, isRecording: model.isRecording,
                              busy: model.capturesInFlight > 0) {
                    model.shutter()
                }
                Spacer()
                Button { model.flipCamera() } label: {
                    Image(systemName: "arrow.triangle.2.circlepath")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(Color.white)
                        .frame(width: 52, height: 52)
                        .background(Circle().fill(Color.white.opacity(0.14)))
                }
                .disabled(model.isRecording)
                .opacity(model.isRecording ? 0 : 1)
            }
            .padding(.horizontal, 28)
        }
        .padding(.top, 8)
        .padding(.bottom, 10)
        .background(
            LinearGradient(colors: [.clear, .black.opacity(model.mode == .video ? 0.55 : 0.9)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
        )
    }
}

private struct ModeSelector: View {
    let mode: CaptureMode
    let disabled: Bool
    let onSelect: (CaptureMode) -> Void

    var body: some View {
        HStack(spacing: 26) {
            ForEach(CaptureMode.allCases) { item in
                Button { onSelect(item) } label: {
                    Text(item.rawValue)
                        .font(.system(size: 13, weight: .semibold))
                        .kerning(1)
                        .foregroundStyle(item == mode ? Color.yellow : Color.white.opacity(0.85))
                }
                .buttonStyle(.plain)
            }
        }
        .opacity(disabled ? 0 : 1)
        .disabled(disabled)
        .gesture(
            DragGesture(minimumDistance: 20).onEnded { value in
                guard !disabled else { return }
                if value.translation.width < -30 { onSelect(.photo) }
                if value.translation.width > 30 { onSelect(.video) }
            }
        )
    }
}

private struct ShutterButton: View {
    let mode: CaptureMode
    let isRecording: Bool
    let busy: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle()
                    .stroke(Color.white, lineWidth: 4)
                    .frame(width: 78, height: 78)
                if mode == .photo {
                    Circle()
                        .fill(Color.white)
                        .frame(width: 64, height: 64)
                        .opacity(busy ? 0.55 : 1)
                } else {
                    RoundedRectangle(cornerRadius: isRecording ? 7 : 32)
                        .fill(Color.red)
                        .frame(width: isRecording ? 30 : 64, height: isRecording ? 30 : 64)
                }
            }
            .frame(width: 80, height: 80)
        }
        .buttonStyle(ShutterPressStyle())
        .animation(.spring(response: 0.3, dampingFraction: 0.8), value: isRecording)
        .animation(.easeInOut(duration: 0.2), value: mode)
    }
}

private struct ShutterPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.92 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

private struct ThumbnailButton: View {
    let image: UIImage?

    var body: some View {
        Button {
            if let url = URL(string: "photos-redirect://") { UIApplication.shared.open(url) }
        } label: {
            Group {
                if let image {
                    Image(uiImage: image).resizable().scaledToFill()
                } else {
                    Color.white.opacity(0.12)
                }
            }
            .frame(width: 52, height: 52)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.white.opacity(0.6), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Permission

private struct PermissionView: View {
    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "camera.fill").font(.system(size: 40))
            Text("Zoomify needs camera access").font(.headline)
            Text("Allow camera access in Settings to start shooting.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button("Open Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
            .buttonStyle(.borderedProminent)
            .tint(.yellow)
            .foregroundStyle(.black)
        }
        .padding(32)
        .foregroundStyle(.white)
    }
}
