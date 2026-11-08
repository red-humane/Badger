# Badger

> **Supercharge your physical button: instant ringer toggles, voice shortcuts, and on-screen text capture.**

Badger is a native Android utility that transforms your phone's physical hardware key (such as the Nothing Phone Essential Key) into a fluid, multi-action shortcut button. Everything runs 100% locally on-device with zero tracking and zero cloud dependencies.

---

## Features

- **Dynamic Ringer**  
  Switch smoothly between Ring and Vibrate right from the edge of your screen with tactile haptic feedback and dynamic animations.

- **Say It (Voice Search & Actions)**  
  Hold to speak. Instantly launch web searches on Google, open installed apps directly by name (e.g., *"open Gallery"* or *"Spotify"*), or schedule reminders using natural speech.

- **Natural Language Reminders**  
  Say *"Remind me to wake up at 12:30 PM"* or *"Remind me to call John at 5pm"*. Badger extracts the task and schedules a precise on-device alarm using Android `AlarmManager`.

- **Get Content**  
  Select and copy text, phone numbers, links, and images directly from any app on your screen with a single press.

- **Feature Tray & Voice Notes**  
  A sleek, minimal bezel dock providing 1-tap access to your most-used actions and instant on-device voice recording.

---

## Getting Started

### 1. Download & Install
Download the latest `Badger-1.0.apk` from the [Releases](https://github.com/red-humane/Badger/releases) tab and install it on your device.

Or build from source:
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/Badger-1.0.apk
```

### 2. Button Conflict Prevention (Nothing OS)
By default, Nothing OS intercepts the physical button for Essential Space. To give Badger exclusive control without duplicate app launches, run this one-time command from your computer or using Shizuku/LADB on your phone:

```bash
adb shell pm disable-user --user 0 com.nothing.ketchum
```

*(To restore default Nothing OS behavior at any time: `adb shell pm enable com.nothing.ketchum`)*

### 3. Complete Setup
Open Badger and follow the interactive 3-step setup guide:
1. **Enable Badger Button Service** in Android Accessibility Settings.
2. **Confirm Button Optimization** (ADB command above or via Phone Settings).
3. **Press the physical button once** to test and calibrate your key.

---

## Requirements & Tech Stack

- **Target SDK**: Android 15 (API 35), Compile SDK 36, Min SDK 26 (Android 8.0+)
- **UI**: Jetpack Compose & Material 3
- **Audio / Voice**: Android SpeechRecognizer & AudioRecord
- **Architecture**: Kotlin Coroutines, StateFlow, Android Jetpack
- **On-Device ML**: Google ML Kit (Text Recognition & Language Identification)

---

## Privacy

Badger operates **100% offline and on-device**. 
- No accounts or logins
- No analytics or telemetry
- Screen text accessed via Get Content is processed in memory and never leaves your device.

---

## License

Apache License 2.0. See [LICENSE](LICENSE) for details.
