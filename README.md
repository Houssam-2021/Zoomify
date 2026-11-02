# Zoomify

Camera apps for **Android** (`android/`, Kotlin + Jetpack Compose + CameraX) and **iOS** (`Zoomify/`, Swift + Metal).

## Android

### Install the APK

Every push that changes `android/` is built by GitHub Actions (`.github/workflows/android.yml`). The workflow
publishes the APK as the **`android-latest`** release:

1. On your phone, open the repo's **Releases** page, find *Zoomify for Android (latest build)* and download
   `Zoomify.apk`. It's also attached as an artifact on each Actions run.
2. Open the file and allow *Install unknown apps* for your browser or file manager when Android asks.
3. You need Android 11 or newer.

Every build is signed with the same sideloading key (`android/app/zoomify-sideload.keystore`), so a new APK
installs over the old one.

To build it yourself, open `android/` in Android Studio, or run `./gradlew assembleRelease`.

### What it does

| | |
|---|---|
| **Zoom** | A critically damped spring on log₂(zoom), ticked every display frame (up to 120 Hz). Lens buttons glide in about 0.4 s, pinch is smoothed, and dragging sideways across the buttons opens a logarithmic dial. |
| **Lens switching** | Uses the phone's *logical* multi-camera through `CONTROL_ZOOM_RATIO`, so 0.5× → 1× → tele is one continuous zoom handled by the camera itself. When `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` changes, the preview hides the jump with a short blur + cross-fade. |
| **Motion blur** | A CameraX `CameraEffect` (OpenGL ES) adds a radial zoom blur in linear light to the preview, and a zoom-aligned temporal trail that follows zoom speed. Recorded video stays sharp. |
| **50×** | Uses the camera's own zoom up to its limit. If the phone stops short of 50×, a GPU crop takes over for preview and SDR video, and photos are cropped after capture. |
| **Photos** | `CAPTURE_MODE_MAXIMIZE_QUALITY`, JPEG quality 100. Options: **MAX RES** (full-sensor resolution, e.g. 50 MP), **HDR** (Ultra HDR JPEG) and **RAW+JPEG** (DNG), each shown only if the phone supports it. |
| **Video** | 4K 30 fps (falls back to 1080p), with bitrate targets of **50 / 100 / 150 Mb/s** (stock cameras use about 40–50). Also 10-bit **HLG HDR** and stabilisation where supported. |

**Android limits:** colours and processing come from each phone maker's camera pipeline, so they can't be
identical to an iPhone's. How seamless the 0.5× switch is depends on the phone exposing a logical multi-camera.
Pixels and recent Samsung Galaxy phones do; some phones only expose the ultra-wide as a separate camera, and then
the app's zoom starts at 1×. HDR video bypasses the GL effect, which is 8-bit, so in HDR there's no preview blur
and zoom stops at the phone's own maximum.

## iOS

A native iOS camera app (Swift, SwiftUI, AVFoundation, Metal) built to feel like the stock Camera app:
smooth eased zoom with radial motion blur, a smooth 0.5× ↔ 1× ↔ tele lens switch, Apple's own photo
pipeline for the same quality and colours, up to 50× zoom, and 4K 30 fps video at high bitrates.

## Run it

1. Open `Zoomify.xcodeproj` in Xcode 16 or newer.
2. Choose your Team under *Signing & Capabilities* (and change the bundle id if needed).
3. Run it on a real iPhone with iOS 17 or later. The simulator has no camera.

## Features

| | |
|---|---|
| **Zoom** | Critically damped spring in log₂ space, ticked by a 120 Hz display link. Lens buttons glide in about 0.4 s, pinch follows your fingers with light smoothing, and dragging across the buttons opens a logarithmic zoom dial. The range runs from 0.5× to **50×**, capped at whatever the device's format allows. |
| **Motion blur** | The preview is drawn by a Metal compute shader that adds a radial zoom blur in linear light. Its strength follows zoom speed (14 jittered taps) and it adds a little temporal trail, aligned to the zoom change. |
| **Lens switching** | The app uses the virtual `builtInTripleCamera` / `builtInDualWideCamera` device, so the system hands off between ultra-wide, wide and tele lenses with no session restart. When `activePrimaryConstituent` changes, the preview runs a short blur + cross-fade pulse that hides the perspective jump. |
| **Photo quality / colour** | `.photo` session preset (Apple's tuned pipeline) in Display P3, `photoQualityPrioritization = .quality` (Smart HDR / Deep Fusion / Photonic Engine where the hardware has them), zero shutter lag, responsive capture, and lens distortion correction. Formats: **HEIF** (24/12 MP like the Camera app), **HEIF MAX** (48 MP) and **RAW MAX** (48 MP Apple ProRAW DNG). |
| **Video** | **4K 30 fps** (falls back to 1080p30). It records with AVAssetWriter, so the bitrate is set by the app: **HEVC 100 Mb/s** (120 Mb/s in HDR), **H.264 150 Mb/s**, or **ProRes 422 HQ** with 24-bit PCM audio on devices that can encode ProRes. **HDR** is 10-bit HLG / BT.2020, the same flavour the Camera app records, and the preview shows it in EDR. |
| **Other** | Tap to focus/expose, flash off/auto/on, front/back camera, saving to Photos, standard video stabilisation in video mode. |

## Notes and limits

- On a 4K30 frame, the stock Camera app's HEVC is roughly 40–50 Mb/s. Zoomify's defaults are about 2–3× that.
  ProRes 4K30 is about 6 GB per minute.
- Zoom past the telephoto lens's native factor is digital, so 50× is a crop and its quality is limited
  by the sensor, just like 25× in the Camera app.
- Apple's Camera app aligns lenses frame by frame with private calibration. With public APIs, Apple engineers suggest
  stabilisation or `extrinsicMatrix`-based alignment. Zoomify uses a cross-fade, which hides the jump well, but a
  calibrated warp would be a possible next step.
- The motion blur is applied to the **preview only**. Saved photos and videos are not blurred.

## Code map

| File | What it does |
|---|---|
| `CameraService.swift` | Session setup: device discovery, photo/4K format choice, HDR, photo capture, recording |
| `ZoomController.swift` | Spring-driven `videoZoomFactor` |
| `PreviewRenderer.swift` + `Shaders.metal` | YCbCr → linear RGB, zoom blur, lens-switch cross-fade, HLG → EDR |
| `MovieRecorder.swift` | AVAssetWriter with codec, bitrate and colour tags |
| `PhotoCaptureProcessor.swift` | Photo delegate, thumbnail, saving to Photos |
| `ContentView.swift`, `ZoomControls.swift` | SwiftUI camera UI, lens buttons and zoom dial |
