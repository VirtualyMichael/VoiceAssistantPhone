# Voice Assistant: phone app

An Android app that turns your phone into the microphone, speaker and camera for the Voice Assistant running on your PC.
The PC does the listening, thinking and speaking; the phone streams your voice to it over [Tailscale](https://tailscale.com)
and plays the spoken reply. Nothing leaves your own devices unless you chose a cloud model on the PC.

## What it does

- **Talk** by holding a button, or hands-free: say a wake phrase (wake word and voice detection run on the phone with
  [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)), even with the screen off (a foreground service shows a notification while it listens).
- **Interrupt** a reply with the wake phrase or the Stop button.
- **Camera**: when you ask "what am I looking at?", the assistant opens the phone's camera (0.5×, 1×, 2×, 5× buttons and a smooth zoom
  slider, tap to focus, optional 3 s timer); the PC describes the photo.
- **Approvals**: confirmation questions from the assistant can be answered on the phone.
- **Share to the assistant**: share pictures, videos, PDFs, text or links from any app ("Ask the assistant" in the share sheet), pick or type a
  question, and the file is attached on the PC for the assistant to read.
- **Phone actions**: the assistant can set alarms and timers, add calendar events, write texts and start calls on the phone. Each one is
  shown first and only happens if you tap Yes; events, texts and calls open in your own calendar, messaging app or dialler, where you save,
  send or call yourself. Names ("text Mom") are looked up in the phone's contacts (asked for the first time) and never leave the phone.
- **Notifications**: with "Read my notifications" on (Settings, This phone) and Android's notification access given, the assistant can
  read the notifications on the phone when you ask ("what did I miss?").
- **Settings**: every setting of the assistant that is safe to change remotely, searchable, with a "?" help line on each. Safety settings
  are locked unless the PC owner allows them on the PC, and then still ask for the phone's fingerprint or PIN.
- **Phone-only settings**: hands-free, wake phrase and sensitivity, camera timer, vibration.

The PC side lives in the assistant's repository (`voice_assistant/remote.py` and friends); the messages between the two are described at the
top of `remote.py`.

## Build

Requirements: Android Studio (it brings its own Java), an Android 8+ phone, the PC assistant with the phone link on and Tailscale on both.

1. Restore the two files that are not in git (below).
2. Open this folder in Android Studio, or run `gradlew assembleDebug` with Java set to Android Studio's `jbr`.
3. `gradlew testDebugUnitTest` runs the unit tests (wake/voice state machine, photo sizing, settings parsing).
4. Install the debug APK; pair it with the PC (`python -m voice_assistant phone pair` on the PC prints the code to paste in the app).

### Files that are not in git

| File | Why | Where to get it |
|---|---|---|
| `app/libs/sherpa-onnx-2.0.0.aar` (49,236,482 bytes) | large binary | <https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.0/sherpa-onnx-2.0.0.aar>, SHA-256 `f0f8716982d27cb4ce695e3934018b1342d9d3d6fdb6ef1f1658fe8bad50b4fc` |
| `app/src/main/assets/kws/*` (about 5 MB) | the wake-word model's licence has not been confirmed for redistribution | the five files listed in `app/src/main/assets/kws/README.txt`, from the PC assistant's models folder or the sherpa-onnx KWS release |

The voice-detection model (`assets/vad/silero_vad.onnx`, MIT licence) is included.

## Developer shortcuts (debug builds only)

Ignored in any build that is not debuggable:

- `adb shell am start -S -n com.voiceassistant.phone/.MainActivity --es pairing_b64 <base64 of the pairing code>` pairs without typing.
- `adb shell am start -S -n com.voiceassistant.phone/.MainActivity --ez demo_camera true` opens the camera screen without the assistant asking
  (the log tag `VoiceAssistant` prints the phone's zoom range and lenses).

Remove these before any public release.

## Notes

- The link to the PC is plain `ws://` because it only ever runs inside Tailscale's encrypted tunnel; the PC accepts only paired phones.
- Pairing tokens are stored in the app's private preferences; the PC stores only a hash of each token.
