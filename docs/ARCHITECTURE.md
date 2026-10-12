# Proposed architecture

## Repository shape

`apps/screensaver/` will be one standalone Android application. Future TV apps can be siblings under `apps/`. Avoid a shared Android module until more than one app actually needs the same code.

## Setup decisions

Settle these before generating the Gradle project (roadmap milestone 2) and record the result here.

| Decision | Status | Notes |
| --- | --- | --- |
| Application ID / package name | Open | Hard to change after the dream is selected with `adb`, because the screensaver setting stores `package/class`. |
| `minSdk` | 31 | The TV runs Android TV 12 (API 31). Personal app; no need to support older devices. |
| `targetSdk`, `compileSdk`, AGP, Kotlin | Open | Choose from current official Android documentation, not by guessing. |
| Settings UI toolkit | Open | Compose for TV or Android Views. Check current official TV guidance before choosing. |
| Slideshow renderer | Proposed: plain Android `View` | `DreamService` is not a `LifecycleOwner`; hosting Compose inside it requires wiring lifecycle and saved-state owners by hand. A plain `View` renderer works the same in the activity and the dream. |
| Image decoding | Open | Platform `ImageDecoder` or an image-loading library. Must downsample to display size, honor EXIF orientation, and decode HEIC. |
| SMB client library | Open (milestone 4) | Android has no built-in SMB client. Requirements: SMB2/3 (do not rely on obsolete SMB1), explicit Guest authentication, acceptable license, and active maintenance. |

## Screensaver components

| Component | Responsibility |
| --- | --- |
| TV activity | Remote-friendly setup, interval selection, source status, and slideshow preview |
| `DreamService` | System screensaver entry point and lifecycle handling |
| Slideshow controller | Select the next image, schedule transitions, pause/resume. Plain Kotlin with an injectable clock so it can be unit-tested on the JVM. |
| Renderer | Display the current image and transitions; shared by the activity and the dream |
| Photo source interface | Supply image references independently of the renderer |
| Photo index | Saved list of image references for a source, refreshed in the background so the dream never scans the share on start |
| Settings store | Persist interval and source choices |
| Source adapters | Local test images first; read-only SMB files next |

Both the TV activity and `DreamService` should use the same slideshow controller and renderer. The dream must release work in `onDreamingStopped` and `onDetachedFromWindow`. Keep source authorization separate from the dream UI; a screensaver should never launch a sign-in flow while idle. Preload the next image before its transition so the screen never shows a blank frame.

## Data and privacy

Start with bundled local test images. The SMB adapter will enumerate supported image files below the configured root, read them without writing to the share, and downsample for the TV display. When the root is `Photos Library.photoslibrary/originals/`, it must ignore databases, caches, hidden files, and other package content, and the product must account for [what originals contains](PHOTO_SOURCES.md#what-originals-contains) (hidden and recently deleted photos, unedited originals). Keep the SMB host, share, root path, and authentication mode in TV settings. For credential-free access to the tested share, use explicit Guest mode with no saved username or password; do not treat anonymous access or an arbitrary blank-password username as equivalent. If registered-user access is offered, never put credentials in Git or logs and protect stored credentials with Android Keystore. Do not log photo filenames or host addresses. A future album adapter can read user-exported album folders over the same SMB share. The package's internal directory names must never be presented as Photos albums. Any image cache on the TV needs an explicit size and retention policy.

Enumerating a large library over SMB is slow. Build the photo index in the background (for example when settings change and periodically), keep the last good index when the share is unreachable, and let the dream start from the saved index immediately. The library lives on the Mac mini's external `Samsung_T5` drive; if the drive sleeps or is not mounted the share can disappear, and the app must show a clear unavailable state rather than an empty slideshow.

## Platform dependency

Android provides `DreamService`, but Google TV devices on Android TV 12 and later are reported to have no settings menu for choosing a third-party screensaver. The [device testing sequence](DEVICE_TESTING.md#setup-sequence) uses `adb shell settings put secure screensaver_components` and the TCL Auto Launch permission instead. The TV is a TCL A300 (NXTFRAME), which also has its own idle Art Mode separate from Google's Ambient mode; whether it lets a selected `DreamService` run is unknown. The first on-device milestone must verify selection and idle activation. Keep a launcher-based slideshow path so the project is usable if system integration is restricted or a firmware update removes it.
