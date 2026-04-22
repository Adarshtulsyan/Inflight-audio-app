# Rani Sati Dadi Mangal Path - Inflight Audio Experience

A premium, synchronized Android application designed for a collective spiritual experience during inflight journeys. This app facilitates a unified "Mangal Path" for passengers, ensuring everyone hears the same audio at the exact same moment, regardless of when they start the app.

## ✨ Features

### 1. Two-Screen Premium Flow
*   **Spiritual Welcome Gate:** A dedicated introduction screen featuring "Jai Dadi Ki" branding and organizational credits for the Marwari Samaj.
*   **Smooth Transitions:** Professional 800ms cross-fade animations between the welcome screen and the cabin control center.

### 2. Cabin Synchronization (Remote Time Sync)
*   **Global Synchronization:** The audio playback is synced to a specific master start time (Target: April 22, 2026).
*   **Live GitHub Integration:** The app polls a remote `config.json` on GitHub every 15 seconds to fetch updated start times.
*   **Real-time Status Badge:** A dynamic UI indicator that toggles between **LIVE** (Online/Synced) and **OFFLINE** (Disconnected) based on cabin connectivity.
*   **Multi-Year Accuracy:** Uses IST (Asia/Kolkata) time parsing and `Long` arithmetic to handle precision countdowns over long durations.

### 3. Audio Privacy
*   **Headset Requirement:** To maintain a quiet cabin environment, users are prompted to connect their headsets before commencing the journey.
*   **Simplified Access:** A manual confirmation step ensures passengers have prepared their audio device, maintaining the "Privacy First" spiritual atmosphere.

### 4. Smart Inflight Controls
*   **Dynamic Countdown:** Displays a real-time countdown (Days, Hours, Minutes, Seconds) until the collective journey begins.
*   **Automatic Seek:** If a passenger joins late, the app automatically calculates the correct offset and seeks the audio to the exact millisecond to match the cabin's progress.
*   **Progress Tracking:** Visual progress bar with current and remaining time indicators.
*   **Graceful Completion:** Upon finishing the journey, the app automatically replaces playback controls with a spiritual "Thank You" message and a blessing, ensuring a respectful conclusion.

## 🛠 Technical Stack
*   **Language:** Kotlin
*   **Architecture:** View-based with smooth AlphaAnimations.
*   **Network:** OkHttp and ConnectivityManager for real-time sync and status monitoring.
*   **Audio:** Android MediaPlayer API with localized raw resource management.
*   **CI/CD:** GitHub Actions workflow (`android-ci.yml`) to verify builds on all Pull Requests, with `config.json` skip-logic to allow fast configuration updates.

## 🚀 Deployment
The app is configured to build a specialized APK: `Rani_Sati_Dadi.apk`.

---
*Organized by Marwari Samaj*
