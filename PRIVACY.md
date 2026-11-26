# WhatStick Privacy Policy

**Effective Date:** November 23, 2026

WhatStick is an offline-first Android application designed with strict privacy by default.

## Data Collection
- **No Personal Data Collected:** WhatStick does not collect, store, transmit, or sell any personal data.
- **No Internet Access:** The app does not request the `INTERNET` permission and never connects to remote servers or cloud APIs.
- **No Analytics or Tracking:** There are no analytics SDKs, crash reporters, advertising networks, or telemetry beacons.
- **No Accounts:** No sign-in, registration, or subscription is required.

## Media & Local Storage
- WhatStick uses Android's zero-permission **Photo Picker** and **Storage Access Framework (SAF)** so the app only reads the specific photos, GIFs, or videos you explicitly select.
- All image editing, background removal, video trimming, and WebP encoding happen **100% locally on your device**.
- Created stickers and packs are stored solely in your app's private local storage and exposed only to WhatsApp (`com.whatsapp` / `com.whatsapp.w4b`) via `StickerContentProvider` protected by `com.whatsapp.sticker.READ` when you choose **Add to WhatsApp**.

## Contact & Source Code
WhatStick is open-source under the Apache License 2.0. You can inspect the full source code in this repository.
