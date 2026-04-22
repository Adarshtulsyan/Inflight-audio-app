# Rani Sati Dadi Mangal Path - Inflight Audio Experience

A premium, synchronized Android application designed for a collective spiritual experience during inflight journeys. This app facilitates a unified "Mangal Path" for passengers, ensuring everyone hears the same audio at the exact same moment, regardless of when they start the app.

## ✨ Features

### 1. Two-Screen Premium Flow
*   **Spiritual Welcome Gate:** A dedicated introduction screen featuring "Jai Dadi Ki" branding and organizational credits for the Marwari Samaj.
*   **Smooth Transitions:** Professional 800ms cross-fade animations between the welcome screen and the cabin control center.

### 2. Cabin Synchronization (Remote Time Sync)
*   **Global Synchronization:** The audio playback is synced to a specific master start time (Target: April 22, 2026).
*   **Live GitHub Integration:** The app polls a remote `config.json` on GitHub every 15 seconds to fetch updated start times.
*   **Multi-Year Accuracy:** Uses IST (Asia/Kolkata) time parsing and `Long` arithmetic to handle precision countdowns over long durations.

### 3. Audio Enforcement (Earphone Only)
*   **Privacy First:** Audio playback is strictly restricted to headsets. The app detects Wired, Bluetooth (A2DP/SCO), and USB headphones.
*   **Speaker Blocking:** Playback will not start unless a headset is confirmed.
*   **Auto-Pause Safety:** If headphones are unplugged during the session, audio immediately stops and the user is redirected to the confirmation screen.

### 4. Smart Inflight Controls
*   **Dynamic Countdown:** Displays a real-time countdown (Days, Hours, Minutes, Seconds) until the collective journey begins.
*   **Automatic Seek:** If a passenger joins late, the app automatically calculates the correct offset and seeks the audio to the exact millisecond to match the cabin's progress.
*   **Progress Tracking:** Visual progress bar with current and remaining time indicators.

## 🛠 Technical Stack
*   **Language:** Kotlin
*   **Architecture:** View-based with smooth AlphaAnimations.
*   **Network:** OkHttp for efficient polling of GitHub API.
*   **Audio:** Android MediaPlayer API with localized raw resource management.
*   **CI/CD:** GitHub Actions workflow (`android-ci.yml`) to verify builds on all Pull Requests, with `config.json` skip-logic to allow fast configuration updates.

## 🚀 Deployment
The app is configured to build a specialized APK: `Rani_Sati_Dadi.apk`.

---
*Organized by Marwari Samaj*
