# Walkthrough - Audio File Replacement & Sync Update

I have replaced the audio files and updated the fallback duration constants for both the Android and iOS applications to accommodate the new 102-minute audio file.

## Changes Made

### Media Assets
- **Android**: Replaced `app/src/main/res/raw/dadi_audio.mp3` with the new `Compressed_Output.mp3`.
- **iOS**: Replaced `Dadi Flight App/audio.mp3` with the new `Compressed_Output.mp3`.
- The file size for both has increased from ~21 MB to **94.1 MB**.

### Android Code
- **File**: [MainActivity.kt](file:///C:/Users/adars/Desktop/Adarsh Personal Dadi App/Inflight-audio-app/app/src/main/java/com/silentflight/experience/MainActivity.kt)
- **Change**: Updated the fallback duration from 20 minutes (1,200,000 ms) to **6,167,248 ms** (~102.8 mins).

### iOS Code
- **File**: [ContentView.swift](file:///C:/Users/adars/Desktop/Adarsh Personal Dadi App/Dadi-Flight-iOS/Dadi Flight App/ContentView.swift)
- **Change**: Updated the fallback duration from 20 minutes (1,200 seconds) to **6,167 seconds** (~102.8 mins).

## Verification Results
- **File Size Check**: Verified that both files now report ~94.1 MB on disk.
- **Code Check**: Verified that the constants in `MainActivity.kt` and `ContentView.swift` have been updated correctly.

> [!IMPORTANT]
> Since the audio is now much longer, please ensure you update your `config.json` on GitHub with the correct `startTime` to test the synchronization. If you start a journey now, late joiners will correctly seek up to 102 minutes into the audio.
