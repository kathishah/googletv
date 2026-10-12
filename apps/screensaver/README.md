# Photo screensaver

First planned Google TV app. The Android project will live here.

## Progress (2026-10-12)

- Android Studio, Android SDK command-line tools, and `adb` are installed on the development Mac.
- The TCL TV is authorized for network ADB. Its Android version, original screensaver component, idle timeout, and installed built-in dreams are recorded in [device testing](../../docs/DEVICE_TESTING.md).
- The Android app project has not been created yet. The next milestone is a small Kotlin launcher and `DreamService` that can be installed and tested for idle activation before adding the SMB source.

## Initial build

- TV launcher screen with remote navigation and a manual slideshow preview.
- Android `DreamService` using the same slideshow engine.
- Local sample photos, a user-selected interval, and a clear empty/error state.
- On-device verification on the TCL TV (Android TV 12, `minSdk 31`), including selecting the dream with `adb` and allowing TCL Auto Launch. See the [setup sequence](../../docs/DEVICE_TESTING.md#setup-sequence).

The first real source will read available image files under `Photos Library.photoslibrary/originals/` through the existing SMB share, strictly read-only. The tested share allows explicit SMB Guest access and rejects anonymous access, so the SMB client needs a Guest authentication mode. Album selection will need an export or index because the package folders are not Photos albums. `originals` also includes hidden and recently deleted photos; decide the default source before building it ([details](../../docs/PHOTO_SOURCES.md#what-originals-contains)). See [the product spec](../../docs/PRODUCT.md) and [photo source constraints](../../docs/PHOTO_SOURCES.md).
