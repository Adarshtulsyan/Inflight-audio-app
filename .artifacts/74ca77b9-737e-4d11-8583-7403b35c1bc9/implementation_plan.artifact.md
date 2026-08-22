# Implementation Plan - Replace Audio with Final Mangal Path

The goal is to replace the audio files in both the Android and iOS applications with the new `Final - MANGAL PATH -18AUG26 - 160 TK4 -FMIX2.mp3` file. The fallback duration constants will be updated to match the new duration (~103 minutes).

## Proposed Changes

### Assets Replacement

#### [MODIFY] [dadi_audio.mp3](file:///C:/Users/adars/Desktop/Adarsh Personal Dadi App/Inflight-audio-app/app/src/main/res/raw/dadi_audio.mp3)
Replace with `../Final - MANGAL PATH -18AUG26 - 160 TK4 -FMIX2.mp3`.

#### [MODIFY] [audio.mp3](file:///C:/Users/adars/Desktop/Adarsh Personal Dadi App/Dadi-Flight-iOS/Dadi Flight App/audio.mp3)
Replace with `../Final - MANGAL PATH -18AUG26 - 160 TK4 -FMIX2.mp3`.

---

### Android Component

#### [MODIFY] [MainActivity.kt](file:///C:/Users/adars/Desktop/Adarsh Personal Dadi App/Inflight-audio-app/app/src/main/java/com/silentflight/experience/MainActivity.kt)
Update the fallback duration in `schedulePlayback` to `6178612L` (~103 mins).

---

### iOS Component

#### [MODIFY] [ContentView.swift](file:///C:/Users/adars/Desktop/Adarsh Personal Dadi App/Dadi-Flight-iOS/Dadi Flight App/ContentView.swift)
Update the fallback duration in `schedulePlayback` to `6178` (~103 mins).

---

### Git & PR

- Create a new branch `update-audio-final-mangal-path` in both repositories.
- Commit and push the changes.
- Provide links to create Pull Requests.

## Verification Plan

### Manual Verification
- Verify file sizes in resource folders.
- Confirm duration constants match the calculated duration of the new file.
- (User) Verify playback and sync on devices.
