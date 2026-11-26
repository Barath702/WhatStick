# WhatStick — Offline WhatsApp Sticker Maker

**WhatStick** is an intuitive, lightweight, 100% offline Android application for creating static and animated WhatsApp stickers from photos, GIFs, and videos, organizing them into packs, and installing them directly into WhatsApp.

## Features

- **100% Offline & Private:** Zero accounts, zero ads, zero analytics, zero cloud uploads, and zero Android `<uses-permission>` declarations (`INTERNET` and broad storage permissions are never requested).
- **Static Sticker Studio:**
  - Import JPG, PNG (with alpha transparency), WebP, HEIC/HEIF, and other Android-decodable images via Android Photo Picker.
  - Crop (`1:1`, `4:3`, `3:4`, `Free`), pinch-zoom, pan, double-tap zoom, rotate (`-180°..+180°` & `90°` steps), and **Horizontal / Vertical Stretch** sliders.
  - **Smart Object & Manual Brushes:** Local on-device Smart Object cutout, smooth anti-aliased Manual Erase and Restore brushes with Undo/Redo and Reset.
  - Transparent or colored rounded backdrops, plus adjustable sticker outlines (White, Black, or custom colors, `0–24px`).
  - Multi-font text overlays (6 bundled Google Fonts with live previews, bold/italic, alignment, color, outline, tap-select, drag, pinch-resize & twist-rotate) and freehand drawing/eraser brushes.
  - Simulated WhatsApp chat bubble preview.
- **Animated Sticker Studio:**
  - Import GIF (`GIF87a`/`GIF89a`), animated WebP, MP4, and WebM files.
  - Draggable trim timeline (`<= 10.0s`), playback speed (`0.5x`, `1.0x`, `1.5x`, `2.0x`), square crop/fit, zoom/pan, **Horizontal / Vertical Stretch** sliders, and text overlays.
  - Pure-Kotlin RIFF/WebP animated container muxer (`VP8X` + `ANIM` + `ANMF`) preserving frame timing and transparency under WhatsApp's `500 KB` limit.
- **Direct WhatsApp Integration:**
  - Official `StickerContentProvider` (`com.whatsapp.sticker.READ`) and `ENABLE_STICKER_PACK` intent contract supporting both WhatsApp and WhatsApp Business.
  - Automatic pre-install validation (`512×512` WebP, `<= 100 KB` static / `<= 500 KB` animated, `96×96` tray icon `<= 50 KB`, 3–30 stickers per pack).
- **Theme Personalization:**
  - Five accent themes: **Blue** (default), **Purple**, **Green**, **Pink**, and **Orange**.
  - System, Light, and Dark modes persisted locally via DataStore.

## Supported Media Formats

- **Static Input:** JPG / JPEG, PNG (with alpha channel), static WebP, HEIC / HEIF (Android 9+ / device codec support)
- **Animated Input:** GIF (`GIF87a` / `GIF89a`), Animated WebP, MP4, WebM
- **Export Output:** `512×512` WebP (static `<= 100 KB`, animated `<= 500 KB`) with `96×96` tray icon (`<= 50 KB`)

## Installation (End Users)

1. Download the APK from the repository's **Releases** page (or build from source below).
2. On an Android 7.0+ (API 24+) device, open the APK file and allow installation from your browser/files app if prompted.
3. Ensure **WhatsApp** (`com.whatsapp`) or **WhatsApp Business** (`com.whatsapp.w4b`) is installed to import created sticker packs directly.

## Offline Functionality & Privacy

- All image/video decoding, Smart Object extraction, manual masking, and WebP encoding execute 100% locally on-device.
- Media access uses Android's system **Photo Picker** and **Storage Access Framework (SAF)** so the app only reads files you explicitly select.
- Sticker assets are stored in private app storage and exposed read-only via `StickerContentProvider` guarded by `android:readPermission="com.whatsapp.sticker.READ"`.
- See [PRIVACY.md](PRIVACY.md) for the full privacy policy.

## Screenshots

> Add real device or emulator screenshots in `docs/screenshots/` before tagging a release:
> - `docs/screenshots/home.png` — Home Studio
> - `docs/screenshots/static_editor.png` — Static Sticker Studio & Brushes
> - `docs/screenshots/animated_editor.png` — Animated GIF/Video Trimmer
> - `docs/screenshots/pack_detail.png` — Pack Manager & WhatsApp Install

## Build Instructions

### Prerequisites
- Android Studio Ladybug / Meerkat or newer (AGP 9.1+ / Gradle 9.3.1)
- JDK 17+ (or 21)
- Android SDK Platform 36 (`compileSdk = 36`, `minSdk = 24`, `targetSdk = 36`)

### Build & Test via CLI
*(Open the project in Android Studio, or run with Gradle 9.3.1 on your `PATH`; if you want a local wrapper script, generate it with `gradle wrapper`.)*

```bash
# Run unit & Robolectric tests
gradle :app:testDebugUnitTest

# Run Android Lint checks
gradle :app:lintDebug

# Build debug APK
gradle :app:assembleDebug

# Build signed release APK (requires keystore env vars below)
export KEYSTORE_PATH=/path/to/upload-key.jks
export STORE_PASSWORD=your_store_password
export KEY_PASSWORD=your_key_password
gradle :app:assembleRelease
```

*(For signed releases in CI, configure `KEYSTORE_BASE64`, `STORE_PASSWORD`, and `KEY_PASSWORD` in GitHub Repository Secrets — never commit `.jks` / `.keystore` files or passwords.)*

## Architecture & Dependencies

- **Language & UI:** Kotlin, Jetpack Compose, Material Design 3, Navigation Compose (`Apache-2.0`)
- **State & Storage:** MVVM (`ViewModel` + `StateFlow`), Room SQLite, DataStore Preferences (`Apache-2.0`)
- **Media & Serialization:** Coil & Coil-GIF, Kotlinx Coroutines & Kotlinx Serialization (`Apache-2.0`), pure-Kotlin LZW GIF decoder and RIFF Animated WebP muxer, Android `MediaMetadataRetriever`
- **Bundled Fonts:** Outfit, Plus Jakarta Sans, Pacifico, Bangers, Playfair Display, JetBrains Mono ([SIL Open Font License 1.1](https://openfontlicense.org/))

## Known Limitations

- WhatsApp requires **3 to 30 stickers per pack**, separate static and animated packs, `512×512` WebP files under **100 KB** (static) or **500 KB** (animated), and animated durations up to **10 seconds**.
- Sending packs directly requires WhatsApp or WhatsApp Business to be installed on the device.
- Building `:app:assembleRelease` requires providing a valid signing keystore via `KEYSTORE_PATH` (default `./my-upload-key.jks`), `STORE_PASSWORD`, and `KEY_PASSWORD`.

## License

Licensed under the [Apache License 2.0](LICENSE).
