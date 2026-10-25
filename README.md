# Google TV apps

Personal Android TV / Google TV apps. There is one app so far: a photo screensaver for the author's TCL 65" A300 (Android TV 12) that plays an Apple Photos library from a Mac over the home network.

> **Stance: a personal project, published as-is.** It is written for one TV and one Mac, and it is not hardened for anyone else's network. The code is MIT-licensed, so you are welcome to read it, borrow from it or fork it, but pull requests and support are not expected. Use it at your own risk.
>
> **Status:** works on the author's TV, verified on one firmware version; the last device checks are recorded in the [journal](journal/2026-10-19-1337-device-findings-log.md) (2026-10-19). There are JVM unit tests (`:app:testDebugUnitTest`) but no CI, so the build is only as verified as the author's last local run.

## Photo screensaver (`apps/screensaver/`)

A Kotlin app with a remote-friendly settings screen, a manual preview, and an Android `DreamService` that the TV starts on idle. It runs on the TCL and is installed there as the system screensaver. There are no screenshots yet; the screens are designed in [`docs/mockups/`](docs/mockups/) (static HTML files, open them in a browser).

**It only works with an Apple Photos library**, read from a Mac's SMB share. Google Photos and other sources are not supported. See [photo sources](docs/PHOTO_SOURCES.md) for why. Three bundled artworks are used as a demo and as a fallback when the share is unreachable, so the app runs with no Mac and no photos: choose **Sample pics** as the photo source.

## Build and install

Prerequisites:

- Android Studio (its bundled JDK is used) and the Android SDK with platform 37, plus the NDK `28.2.13676358` and CMake `3.31.6` (install both from the SDK Manager). The Android Gradle plugin is 9.2.0 and Gradle is 9.4.1 (downloaded by the wrapper).
- **Network access on the first build.** CMake fetches the pinned sources of libheif and libde265 and Gradle downloads dependencies.
- `adb`, connected to a TV running Android TV 12 or later (`minSdk 31`; the native decoder is built for the 32-bit `armeabi-v7a` ABI only).

```sh
cd apps/screensaver
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Use `:app:lintDebug`. A plain `lintDebug` also checks the native-probe module and fails. The prerequisites above were written from the build files and the author's setup notes and have not been tried on a clean machine; if the build fails, check them first.

Select the app as the screensaver (a one-time step, since Android TV 12 has no screensaver picker):

```sh
adb shell settings put secure screensaver_components com.googletv.screensaver/.dream.SlideshowDreamService
```

TV setup, how `adb` is connected, and how to restore the original screensaver are in [device testing](docs/DEVICE_TESTING.md#setup-sequence).

## Configure it for your own setup

Nothing about the author's network is stored in the code or the docs. You supply it:

| Placeholder | What it is | Where you set it |
| --- | --- | --- |
| `MAC_HOST` | Address or hostname of the Mac that shares the library | On the TV: the app's **SMB settings** screen (host) |
| `PHOTOS_SHARE` | The SMB share name (default `Photos`) | **SMB settings** screen (share name) |
| library path | Default `Photos Library.photoslibrary/originals` | **SMB settings** screen (library path) |
| `TV_IP` | The TV's address for `adb` | Your shell, for `adb connect` |
| `LIVE_VOLUME` | Mount point of the drive that holds the live library | Environment variable (or the placeholder at the top of your copy) for the scripts in [`tools/smb_access_probe/`](tools/smb_access_probe/README.md) |

Host addresses, drive names, firmware strings and album names stay out of Git; keep them in your own notes or environment. The Mac side (an SMB share of the library clone that allows read-only Guest access, with SMB signing not required) is described in [photo sources](docs/PHOTO_SOURCES.md#the-mac-mini-source).

## Things to know before using it

- **The library permissions are a decision, not a default.** Guest can read the photos only because a script on the Mac opens up file permissions, and the Mac's SMB signing requirement is turned off. Any device on the LAN that can reach the share can read every photo, including hidden and recently deleted ones. The accepted trade-offs are in [product decisions](docs/PRODUCT.md#product-decisions). Do not point this at a library or a network you are not comfortable exposing that way.
- **The SMB host is expected to be on your internal LAN.** Guest access with signing off is a deliberate choice for a trusted home network. The app is not designed for an SMB host reached over the internet or on a network you do not control.
- **Private data stays out of Git.** No credentials, host addresses, device identifiers or photo filenames, in code, docs or logs.
- **Re-check after TV firmware updates.** Idle activation, Art Mode behavior and the `adb` setup were verified on one firmware version. Legacy network `adb` stays enabled once USB debugging is on; turn it off when you are not working on the TV.

## Repository layout

| Path | Contents |
| --- | --- |
| `apps/screensaver/` | The Android app. See [its README](apps/screensaver/README.md) for internals. |
| `tools/` | Diagnostics and one-off scripts (SMB checks, permission script, probes), except that **`tools/heif_tile_prototype/` is part of the app build**: its `CMakeLists.txt` and `heif_tile_probe.cpp` are compiled into the APK as the native HEIF decoder, and the rest of that folder is a probe app and runner scripts. |
| `journal/` | Dated log of investigations, test results, and retired planning docs (the code review, the app survey, the roadmap history). See its [README](journal/README.md) for the rules. |
| `docs/PRODUCT.md` | The product spec: behavior, settings, playback rules, and decisions |
| `docs/ARCHITECTURE.md` | How the app is built: runtime flow, threads, storage, failure model, build, and testing |
| `docs/PHOTO_SOURCES.md` | Why only Apple Photos, and how the Mac mini source is set up |
| `docs/engineering/` | Per-feature design docs: album catalog and sync, video playback, portrait pairs |
| `docs/DEVICE_TESTING.md` | TV setup steps, device facts, and the release checklist |
| `docs/ROADMAP.md` | What's done and what's left |
| `AGENTS.md` | Rules for working in this repo |

## Licence and third-party material

The original code and documentation are under the [MIT licence](LICENSE). The licence does **not** cover:

- **The three bundled artworks** in `apps/screensaver/app/src/main/res/drawable-nodpi/`. Their sources, credits and licence status are recorded in [`apps/screensaver/ASSETS.md`](apps/screensaver/ASSETS.md).
- **Dependencies that are fetched at build time**, which keep their own licences: [SMBJ](https://github.com/hierynomus/smbj) (Apache-2.0) and the pinned [libheif](https://github.com/strukturag/libheif) and [libde265](https://github.com/strukturag/libde265) (both LGPL-3.0, linked statically into the native decoder). If you distribute a built APK, the LGPL terms apply to it; this repository does not publish APKs.
