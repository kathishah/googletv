# Photo screensaver

First planned Google TV app. The Android project will live here.

## Progress (2026-10-12)

- Android Studio, Android SDK command-line tools, and `adb` are installed on the development Mac.
- The TCL TV is authorized for network ADB. Its Android version, original screensaver component, idle timeout, and installed built-in dreams are recorded in [device testing](../../docs/DEVICE_TESTING.md).
- A Kotlin Android TV app now builds with AGP 9.2.0 and Gradle 9.4.1. It targets API 34, compiles against API 37, and supports API 31 and later. API 34 meets [Google Play's current Android TV target requirement](https://developer.android.com/google/play/requirements/target-sdk); the compile SDK and plugin versions are supported by the official [AGP release notes](https://developer.android.com/build/releases/agp-9-2-0-release-notes).
- The app is installed on the TCL TV. Android lists its `SlideshowDreamService` alongside the built-in dreams. The launcher/settings screen has been opened on the TV.
- D-pad interval selection, manual preview, landscape and portrait fit, 10-second slide changes, Back behavior, and interval persistence after force-stop/relaunch were verified on the TV.
- The `DreamService` was selected temporarily and started on idle. Both photos advanced in dream mode. The original Backdrop selection and 10-minute timeout were restored after the test.

The bundled landscape and portrait sample photos are generated demo assets. `PhotoSource`, `SlideshowScheduler`, and `SlideshowPlayer` are separate so the later SMB adapter can reuse playback and rendering without mixing in source access.

Build and lint from this directory:

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew assembleDebug lintDebug
```

Install or update the debug build:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Initial build

- TV launcher screen with remote navigation and a manual slideshow preview.
- Android `DreamService` using the same slideshow engine.
- Local sample photos, a user-selected interval, and a clear empty/error state.
- On-device verification on the TCL TV (Android TV 12, `minSdk 31`), including selecting the dream with `adb` and allowing TCL Auto Launch. See the [setup sequence](../../docs/DEVICE_TESTING.md#setup-sequence).

The first real source will read available image files under `Photos Library.photoslibrary/originals/` through the existing SMB share, strictly read-only. The tested share allows explicit SMB Guest access and rejects anonymous access, so the SMB client needs a Guest authentication mode. Album selection will need an export or index because the package folders are not Photos albums. `originals` also includes hidden and recently deleted photos; decide the default source before building it ([details](../../docs/PHOTO_SOURCES.md#what-originals-contains)). See [the product spec](../../docs/PRODUCT.md) and [photo source constraints](../../docs/PHOTO_SOURCES.md).
