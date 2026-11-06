# Zoomify

Camera apps for **Android** (`android/`, Kotlin + Jetpack Compose + CameraX) and **iOS** (`Zoomify/`, Swift + Metal).

## Android

### Install the APK

**Download:** https://zoomify.houseli.site (or [GitHub Releases](https://github.com/Houssam-2021/Zoomify/releases/latest)).
Requires Android 11 or newer.

### Build, verify and publish (`.github/workflows/android.yml`)

**Every push** builds, signs, verifies and tests. Nothing public changes.

1. **Build** an unsigned release APK, plus a test-only "future update" build (`versionCode + 1`, never published).
2. **Sign** with the new private key from secrets plus the v3 rotation proof copied from v1.1
   (`android/signing/zoomify-v1.1.lineage`). **No old private key is involved.**
3. **Mandatory verification.** Any failure stops the run:
   - v3 signature present, with one signer: the new key `72:96:CD:BF…CC:CF`. The v2 signature is the new key too, so
     there is no signature by the compromised key anywhere in the APK.
   - The lineage is exactly *legacy → new*, and the legacy key has **rollback revoked**.
   - `versionCode` / `versionName` match the build, and the APK is not debuggable.
   - **Permission allow-list:** `CAMERA`, `RECORD_AUDIO`, `VIBRATE`. No `INTERNET`, and no app-defined permissions,
     because those break rotation updates on Android 11/12.
4. **Key-rotation tests on Android 11, 12 and 13 emulators** (`.github/scripts/rotation-test.sh`):
   - v1.0 → published v1.1 → this build → future build: all in-place updates, with app data kept at each step.
   - An APK signed only with the compromised key (versionCode 999999) is **rejected** with
     `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the same file installs on an empty device (control).
   - v1.0 → this build directly also works, and the attacker APK is rejected afterwards.
   - A clean install works, and a launch smoke test passes.
   - The v1.0 and attacker APKs come from a **draft** release (*PRIVATE test fixtures*), which only collaborators
     can see. Their SHA-256 checksums are pinned in the workflow. The test APK artifact is deleted after each run.

**Publishing** is manual: *Actions → Android APK → Run workflow → tick **publish***. It runs only after all of the
above pass, then:

- Creates the versioned release `v<versionName>` with security notes and the APK's SHA-256, and marks it Latest.
- Points the old `android-latest/Zoomify.apk` link at it (below).
- Checks that the published APK is byte-identical to the verified build.

For a new release, bump `zoomifyVersionCode` and `versionName` in `android/app/build.gradle.kts`.

### Signing keys

- **Compromised key** (`1a9f7202…c12c`, "Zoomify Sideload"): it was committed publicly on 2026-11-02, together with its
  password, and was removed from the source tree on 2026-11-06. It must be treated as **permanently compromised**:
  copies exist in the git history and anywhere the repo was cloned. Nothing builds or signs with it any more.
- **Release key** (`72:96:CD:BF…CC:CF`): only in repository secrets `ZOOMIFY_KEYSTORE_B64` /
  `ZOOMIFY_KEYSTORE_PASSWORD` and the owner's offline backup. **Back it up.** Losing it means existing users can never
  be updated again.
- `.gitignore` blocks keystores and credential files (`*.jks`, `*.keystore`, `*.p12`, `.env`, and similar).

### Old download link

`releases/download/android-latest/Zoomify.apk` was shared widely while it served 1.0. GitHub can't redirect
release-asset URLs, but it serves them by file name, so that release now holds the verified newest release as
`Zoomify.apk`. Every publish repeats this (`.github/scripts/refresh-legacy-link.sh`). The original 1.0 file was
removed from public downloads on 2026-11-06. To run the refresh on its own: *Actions → Point the old android-latest
link at a verified release*.

### Website on Cloudflare Pages (`site.yml`)

The website-only workflow doesn't build Android, run emulators or create releases. It uses the existing Pages
project `zoomify` (`zoomify-b0l.pages.dev`) and never creates another one.

**One-time setup, about 3 minutes** (never paste the token anywhere except the GitHub secret form):

1. Cloudflare dashboard → *My Profile → API Tokens → Create Token → Create Custom Token*, with these permissions:
   - **Account → Cloudflare Pages → Edit**
   - **Zone → DNS → Edit** and **Zone → Zone → Read**, with *Zone Resources* = `houseli.site` only. These two are
     needed only for the automatic domain switch.
2. Copy your **Account ID** from *Workers & Pages* (right-hand sidebar).
3. GitHub → this repo → *Settings → Secrets and variables → Actions → New repository secret*:
   `CLOUDFLARE_API_TOKEN`, then `CLOUDFLARE_ACCOUNT_ID`.

**Deploy:** *Actions → Website (Cloudflare Pages) → Run workflow*:

| Inputs | What happens |
|---|---|
| (none) | Deploys a **preview**, then tests every page, link, download redirect, the APK checksum, HTTPS, and phone and desktop layout (Chromium) |
| `production` | Preview, tests, **production** at `https://zoomify-b0l.pages.dev`, then the tests again |
| `production` + `switch_domain` | All of the above, then attaches `zoomify.houseli.site` to the project and changes its DNS record from Vercel to Pages. It logs the old record, waits until Pages serves the new build, and re-runs every test on the real domain. **If anything fails, it restores the old Vercel record automatically.** |

Keep the domain on Vercel until the switch has passed. Then remove it from the Vercel project `zoomify-site`.

### AdSense (prepared, not active)

Nothing ad-related is served until you configure it. Use repository **variables** (Settings → Secrets and
variables → Actions → Variables), not secrets:

| Variable | Effect |
|---|---|
| `ADSENSE_PUBLISHER_ID` | Your real ID (`pub-` plus 16 digits). Adds the AdSense script and `ads.txt`. Needed for site review. |
| `ADSENSE_ENABLE_ADS` = `true` | Shows ad units. Only set this after approval. |
| `ADSENSE_SLOT_HOME`, `ADSENSE_SLOT_HELP` | Ad unit IDs for the two slots, both placed away from download buttons |
| `CONTACT_EMAIL` | Shows a contact email on the contact page |

- **Account:** AdSense requires you to be 18 or older. Otherwise a parent or guardian signs up with *their own*
  Google account, and payments go to them.
- **Site and ads.txt:** Google uses the **root domain's** `ads.txt`. Add `houseli.site` in AdSense, and make sure
  `https://houseli.site/ads.txt` contains the publisher line. Setting `ADSENSE_PUBLISHER_ID` adds a copy on
  `zoomify.houseli.site`, but the root file is the one that counts.
- In AdSense, turn on **Privacy & messaging → a Google-certified consent message for the EEA, UK and Switzerland**
  before ads are shown there.

### What it does

The Android app keeps **Velora Pro's behaviour**, rebuilt natively (Velora ran its camera inside a WebView):

| Velora behaviour | How Zoomify does it |
|---|---|
| Zoom spring (ω = 11 + 12·smoothness on log₂ zoom), presets 1/2/5/10/20/50×, log-scale dial, pinch ratio^1.4, double-tap 1× ↔ 2×, tap to focus | Same maths, ticked on every vsync by `Choreographer` (Velora used a 26/33 ms timer, so its cadence was uneven). A **.5×** preset is added when the phone has an ultra-wide. |
| Radial motion blur while zooming (strength slider) | Same formula, on a zero-copy OpenGL `CameraEffect`, blended in linear light and jittered so it streaks instead of ghosting |
| Color science Neutral / Natural / Vivid | Same shader maths. Photos get it at full sensor resolution on the CPU. |
| Cinematic vs Sensor HQ recording | Cinematic records zoom, colour, LUT and blur at full resolution, **including 4K** (Velora capped this at 1080p). Sensor HQ records the camera stream untouched and allows 10-bit HDR. |
| Full-res colors vs Native JPEG | Starts from the camera's full-resolution still (Velora used a video frame) |
| Exposure, bitrate (12–150 Mb/s), resolution, preview performance, hardware zoom assist, mirror, mic, timer, grid, torch, status pills, record badge, last-capture sheet | All kept. Exposure is real camera EV, and hardware zoom is applied every frame instead of in 200 ms steps. |

**What fixes Velora's problems**

- **Lag and stutter:** camera frames go straight to the GPU with no CPU copies, the zoom is driven once per display refresh, and video goes to the hardware encoder.
- **Oversharpening:** Velora used Chrome's camera stream, which carries the phone's strongest sharpening, and then zoomed into it digitally, magnifying the halos. Zoomify turns off edge enhancement on the live stream; this is the *Sharpening: Natural* setting.

**Added on top of Velora**

- **Zoomify Log:** a hardware log tone curve (`TONEMAP_MODE_CONTRAST_CURVE`), recorded 1 EV under. Mid-grey sits at ~0.40, black at 0.06 and clip at 0.94. Phones without custom tone curves get a GPU-emulated version. *Log view assist* shows Rec.709 in the viewfinder, and the matching `zoomify_log_to_rec709.cube` can be saved to Downloads for grading.
- **Built-in LUTs:** Teal & Orange, Warm Film, Cool Matte, Mono Classic, Bleach Bypass and Golden Hour. They're 33-point 3D LUTs applied on the GPU in real time, for the preview only or baked into captures. They are generated by `android/tools/generate_luts.py`.
- **Stabilisation:** Off / Standard (camera EIS) / **Horizon lock**. Horizon lock reads the gravity sensor and counter-rotates every frame on the GPU, with a fixed crop that fully corrects up to ±15°.
- **Telephoto:** phones with a tele lens get its native zoom (e.g. 3×) as a preset. A tele fused into the main camera is reached by plain zoom. A tele exposed as a separate camera gets the same freeze-frame lens hop as the ultra-wide. Phones without one (e.g. Galaxy A36) are unaffected.
- **0.5× ↔ 1× lens switching:** on phones with a fused multi-camera this is one continuous zoom through the lenses, with a blur and cross-fade at the switch. Phones that expose the ultra-wide only as a separate camera get a blurred freeze-frame that fades into the new lens.
- **Photos:** JPEG, Max res, Ultra HDR, RAW + JPEG and **RAW DNG (16-bit)**.

**Limits on Android:** colours and processing come from each phone maker's camera pipeline, so they can't be
identical to an iPhone's. **16-bit RAW video isn't possible:** a full-sensor RAW stream is about 700 MB/s at
30 fps, which phones can't store, and CameraX can't stream RAW frames continuously. Video therefore uses Log
or 10-bit HLG instead. The GPU effects are 8-bit, so 10-bit HDR uses Sensor HQ (no blur, LUT or zoom past the
lens in the recording).

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
