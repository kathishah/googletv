# Photo screensaver

First planned Google TV app.

## Progress (2026-10-12)

- Android Studio, Android SDK command-line tools, and `adb` are installed on the development Mac.
- The TCL TV is authorized for network ADB. Its Android version, original screensaver component, idle timeout, and installed built-in dreams are recorded in [device testing](../../docs/DEVICE_TESTING.md).
- A Kotlin Android TV app now builds with AGP 9.2.0 and Gradle 9.4.1. It targets API 34, compiles against API 37, and supports API 31 and later. API 34 meets [Google Play's current Android TV target requirement](https://developer.android.com/google/play/requirements/target-sdk); the compile SDK and plugin versions are supported by the official [AGP release notes](https://developer.android.com/build/releases/agp-9-2-0-release-notes).
- The app is installed on the TCL TV. Android lists its `SlideshowDreamService` alongside the built-in dreams. The launcher/settings screen has been opened on the TV.
- D-pad interval selection, manual preview, normal dream brightness, landscape and portrait fit, 10-second timing, Back behavior, and interval persistence after force-stop/relaunch were verified on the TV.
- The `DreamService` was selected temporarily and started on idle. Both photos advanced in dream mode. The original Backdrop selection and 10-minute timeout were restored after the test.
- Photo-source listing and decoding now run on a worker thread. The sample-photo preview regression passed on the TV. The first SMB Guest attempts failed during session setup because SMBJ rejects Guest when the server requires signing. After the Mac mini's signing requirement was turned off, the TV manual preview displayed and advanced through share photos; idle activation with the SMB source also started and displayed its first photo. Multi-photo progression in DreamService and HEIC display in dream mode remain unverified. See [the server change](../../docs/PHOTO_SOURCES.md#mac-mini-signing-change-2026-10-12) and [TV test log](../../docs/DEVICE_TESTING.md#device-test-log).
- On the TCL TV (API 31), a temporary GPS-tagged bundled sample displayed “Mountain View, California” in **Location + date** preview. A real JPEG from the SMB share also displayed a resolved place name and capture date after the SMB metadata read was bounded to 512 KiB. The temporary test apps were removed afterward. Lookup failure and coordinate fallback remain to be checked on the TV; see [device findings](../../docs/DEVICE_TESTING.md#device-findings).

The bundled landscape and portrait sample photos are generated demo assets. The current `PhotoSource` interface is blocking by design and `SlideshowPlayer` invokes it on a single worker thread. In **All photos** mode the read-only SMB adapter scans the configured folder tree and uniformly samples up to the selected 100, 250, 500, or 1,000 supported images, then shuffles that pool for playback; that path does not persist an index. In **Choose Albums** mode the TV uses its persistent local album catalog and includes all usable photos in the saved selection. Images that fail to load are removed from the current run and skipped without showing an interruption. The label setting can show a best-effort place name and capture date from EXIF GPS/date metadata, capture date only, or no label. If Android's Geocoder cannot resolve a place, the location label uses the existing coordinates; missing metadata is omitted. Only **Location + date** invokes Geocoder, and a small in-memory cache avoids repeating coordinates during the current run. The source works against the Mac mini share from the TV in manual preview, provided the server does not require SMB signing (check with `python3 tools/check_smb_signing.py "$MAC_MINI_IP"`). The default configured folder is `Photos Library.photoslibrary/originals`; the user accepts hidden and recently deleted photos appearing, without a separate warning or opt-in.

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
- Local sample photos, a user-selected interval, an empty state, and asynchronous source error handling.
- On-device verification on the TCL TV (Android TV 12, `minSdk 31`), including selecting the dream with `adb`. The app started on idle during one test; the TCL Auto Launch setting and Art Mode behavior remain unverified. See the [setup sequence](../../docs/DEVICE_TESTING.md#setup-sequence).

The first real source reads available image files under `Photos Library.photoslibrary/originals/` through the existing SMB share, strictly read-only. The tested share allows explicit SMB Guest access and rejects anonymous access. SMBJ needs the Mac mini's SMB server not to require signing for Guest sessions. With that server preference set, Guest listing and image loading work from the TV in manual preview. `originals` can include hidden and recently deleted photos; the user accepts that without a warning or opt-in. See the [product spec](../../docs/PRODUCT.md), [album selection](../../docs/ALBUM_SELECTION.md), and [photo source constraints](../../docs/PHOTO_SOURCES.md).

## Regular Photos album catalog

On first **Choose Albums** use, the TV stages `Photos.sqlite` and any matching WAL file from the read-only SMB share, queries the local SQLite snapshot in read-only mode, and atomically saves a metadata-only JSON catalog in persistent app-private storage. A determinate progress bar reports bytes copied across both database files. Reopening the picker and selected-album playback use the local catalog; **Refresh** rebuilds it. If the cache is lost or invalid, the next picker open rebuilds it from SQLite. No JSON sidecar is generated on the Mac or stored on the share, and no photo files are exported or duplicated. `tools/generate_photos_album_index.py` remains research tooling only. The current implementation validates supported, contained relative paths while building the catalog and verifies readability when building the selected playback set. The catalog and cached cover thumbnails have been displayed on the TCL. Exact TV transfer/query timings and database/WAL consistency during an actual CCC refresh remain open; see the [album selection investigation](../../docs/ALBUM_SELECTION.md#refresh-performance-investigation).

The launcher settings use a compact table for source, photo selection, pool, interval, and label. SMB connection fields are on a separate screen. Main settings changes are staged until **Save**; **Cancel** restores saved settings. The album carousel saves selection changes immediately; **Done** or remote Back returns to settings. See the [album selection spec](../../docs/ALBUM_SELECTION.md).
