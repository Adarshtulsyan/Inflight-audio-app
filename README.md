# Inflight Audio Sync (Android)

A specialized Android application designed for synchronized multi-device audio and video playback, optimized for inflight environments where timing accuracy is critical.

## Overview
This application ensures that multiple passengers can experience the same content (e.g., the Rani Sati Dadi Mangal Path) at the exact same millisecond, regardless of their individual device clock drift.

## Key Features
- **High-Precision Synchronization**: Uses a remote server clock (via GitHub HTTP headers) to calculate and correct local device clock drift.
- **Remote Orchestration**: Playback start times are managed via a central `config.json`, allowing for real-time adjustments across all devices.
- **Resilient Polling**: Implements a 15-second polling cycle with automatic re-syncing if the configuration changes or significant drift is detected.
- **Network Awareness**: Live monitoring of connectivity status with visual indicators.
- **Media Safety**: Smooth transitions between placeholder images and video playback, with automatic seek-to-position for late joiners.

## Tech Stack
- **Language**: Kotlin
- **Networking**: OkHttp 4
- **Media**: Android `VideoView` and `MediaPlayer`
- **Architecture**: Android AppCompat with Handler-based task scheduling

## Setup
1. Clone the repository.
2. Ensure you have the latest Android Studio installed.
3. Place the media file in `app/src/main/res/raw/video.mp4`.
4. Build and run on an Android device or emulator.

## Synchronization Logic
The app fetches a timestamp from a remote JSON file. It compares the `Date` header from the HTTP response to the local system time to establish a `serverClockOffset`. All countdowns and playback triggers use `System.currentTimeMillis() + serverClockOffset` to ensure global parity.
