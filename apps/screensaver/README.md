# Photo screensaver

First planned Google TV app.

## Current state

The app uses Kotlin and Android Views, with a remote-friendly settings activity, manual preview, and a shared Android `DreamService` slideshow. It supports bundled samples, read-only SMB Guest access, a local regular-album catalog with explicit refresh, photo/video type selection, single and portrait-pair layouts, and location/date or no-label modes. Legacy Date-only preference values are unsupported and fall back to Location + date.

The app DreamService starts on idle on the TCL A300 at the default timeout. Testing on this firmware found no separate TCL Auto Launch control and none was needed for observed activation. SMB photos, large HEICs, video playback, catalog refresh, and picker behavior have device findings in [DEVICE_TESTING.md](../../docs/DEVICE_TESTING.md#device-findings).

The bundled landscape and portrait sample photos are generated demo assets. The blocking `PhotoSource` interface runs on the slideshow worker. In **All** mode, the read-only SMB adapter scans and uniformly samples up to the selected 100, 250, 500, or 1,000 supported items, then shuffles them; it does not persist an index. In **Choose Albums** mode, playback uses all usable photos in the saved selection from the TV's persistent local catalog. Per-file unreadable photos are skipped; transient SMB listing/read failures retry with backoff, and after three consecutive failures stored artworks appear until the source recovers. Location + date uses EXIF GPS and capture-date metadata; geocoding is best effort, and coordinates remain when no place name resolves. No label suppresses captions. The tested share requires explicit Guest access and must not require SMB signing; check it with `python3 tools/check_smb_signing.py "$MAC_MINI_IP"`. The default configured folder is `Photos Library.photoslibrary/originals`; hidden and recently deleted photos can appear by user decision.

Build and lint from this directory:

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :app:assembleDebug :app:lintDebug
```

Install or update the debug build:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Initial build

- TV launcher screen with remote navigation and a manual slideshow preview.
- Android `DreamService` using the same slideshow engine.
- Local sample photos, a user-selected interval, an empty state, and asynchronous source error handling.
- On-device verification on the TCL TV (Android TV 12, `minSdk 31`), including selecting the dream with `adb`. The app started on idle at the default timeout; no separate TCL Auto Launch control was found or needed for observed activation. See the [device findings](../../docs/DEVICE_TESTING.md#device-findings).

The first real source reads available image files under `Photos Library.photoslibrary/originals/` through the existing SMB share, strictly read-only. The tested share allows explicit SMB Guest access and rejects anonymous access. SMBJ needs the Mac mini's SMB server not to require signing for Guest sessions. With that server preference set, Guest listing and image loading work from the TV in manual preview. `originals` can include hidden and recently deleted photos; the user accepts that without a warning or opt-in. See the [product spec](../../docs/PRODUCT.md), [album selection](../../docs/ALBUM_SELECTION.md), and [photo source constraints](../../docs/PHOTO_SOURCES.md).

## Regular Photos album catalog

On first **Choose Albums** use, the TV stages `Photos.sqlite` and any matching WAL file from the read-only SMB share, queries the local SQLite snapshot in read-only mode, and atomically saves a metadata-only JSON catalog in persistent app-private storage. A determinate progress bar reports bytes copied across both database files. Reopening the picker and selected-album playback use the local catalog; **Refresh** rebuilds it. If the cache is lost or invalid, the next picker open rebuilds it from SQLite. No JSON sidecar is generated on the Mac or stored on the share, and no photo files are exported or duplicated. `tools/generate_photos_album_index.py` remains research tooling only. The current implementation validates supported, contained relative paths while building the catalog and verifies readability when building the selected playback set. The catalog and cached cover thumbnails have been displayed on the TCL. Exact TV transfer/query timings and database/WAL consistency during an actual CCC refresh remain open; see the [album selection investigation](../../docs/ALBUM_SELECTION.md#refresh-performance-investigation).

The launcher settings use a compact table for source, photo selection, pool, interval, and label. SMB connection fields are on a separate screen. Main settings changes are staged until **Save**; **Cancel** restores saved settings. The album carousel saves selection changes immediately; **Done** or remote Back returns to settings. See the [album selection spec](../../docs/ALBUM_SELECTION.md).
