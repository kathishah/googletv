# Google TV apps

Personal Android TV / Google TV apps. There is one app so far: a photo screensaver for a TCL 65" A300 (Android TV 12).

## Photo screensaver (`apps/screensaver/`)

A Kotlin app with a remote-friendly settings screen, a manual preview, and an Android `DreamService` that the TV starts on idle. It runs on the TCL and is installed there as the system screensaver.

**It only works with an Apple Photos library**, read from a Mac's SMB share. Google Photos and other sources are not supported. (Three bundled artworks are used as a demo and as a fallback when the share is unreachable.) See [photo sources](docs/PHOTO_SOURCES.md) for why.

## Build and install

Requires Android Studio (for its bundled JDK and the Android SDK) and `adb` connected to the TV.

```sh
cd apps/screensaver
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Use `:app:lintDebug`. A plain `lintDebug` also checks the native-probe module and fails.

Select the app as the screensaver (a one-time step, since Android TV 12 has no screensaver picker):

```sh
adb shell settings put secure screensaver_components com.googletv.screensaver/.dream.SlideshowDreamService
```

TV setup, how `adb` is connected, and how to restore the original screensaver are in [device testing](docs/DEVICE_TESTING.md#setup-sequence).

## Things to know before using it

- **The library permissions are a decision, not a default.** Guest can read the photos only because a script on the Mac mini opens up file permissions, and the Mac mini's SMB signing requirement is turned off. Any device on the LAN that can reach the share can read every photo, including hidden and recently deleted ones. The accepted trade-offs are in [product decisions](docs/PRODUCT.md#product-decisions).
- **Private data stays out of Git.** No credentials, host addresses, device identifiers or photo filenames, in code, docs or logs.
- **Re-check after TV firmware updates.** Idle activation, Art Mode behavior and the `adb` setup were verified on one firmware version.

## Repository layout

| Path | Contents |
| --- | --- |
| `apps/screensaver/` | The Android app. See [its README](apps/screensaver/README.md) for internals. |
| `tools/` | Diagnostics and one-off scripts: SMB checks, permission script, HEIF decoder prototype. Not shipped in the app. |
| `journal/` | Dated log of investigations, test results, and retired planning docs (the code review, the app survey, the roadmap history). See its [README](journal/README.md) for the rules. |
| `docs/PRODUCT.md` | The product spec: behavior, settings, playback rules, and decisions |
| `docs/ARCHITECTURE.md` | How the app is built: runtime flow, threads, storage, failure model, build, and testing |
| `docs/PHOTO_SOURCES.md` | Why only Apple Photos, and how the Mac mini source is set up |
| `docs/engineering/` | Per-feature design docs: album catalog and sync, video playback, portrait pairs |
| `docs/DEVICE_TESTING.md` | TV setup steps, device facts, and the release checklist |
| `docs/ROADMAP.md` | What's done and what's left |
| `AGENTS.md` | Rules for working in this repo |
