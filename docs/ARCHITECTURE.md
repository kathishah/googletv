# Architecture

## Repository shape

`apps/screensaver/` will be one standalone Android application. Future TV apps can be siblings under `apps/`. Avoid a shared Android module until more than one app actually needs the same code.

## Setup decisions

These are the implemented choices for the first app.

| Decision | Status | Notes |
| --- | --- | --- |
| Application ID / package name | Implemented: `com.googletv.screensaver` | The screensaver setting stores `package/class`, so keep the ID stable. |
| `minSdk` | 31 | The TV runs Android TV 12 (API 31). Personal app; no need to support older devices. |
| `targetSdk`, `compileSdk`, AGP, Kotlin | Implemented: target 34, compile 37, AGP 9.2.0, Kotlin | Versions are recorded in the Gradle build; check official Android guidance before future upgrades. |
| Settings UI toolkit | Implemented: Android Views | The TV settings use a remote-friendly view layout; slideshow rendering is shared with the dream. |
| Slideshow renderer | Implemented: plain Android `View` | `DreamService` is not a `LifecycleOwner`; the same renderer works in the activity and the dream. |
| Image decoding and metadata | Implemented: sampled `BitmapFactory`, EXIF and native HEIF tile decode for large images | EXIF orientation, representative 24 MP HEICs, labels and color patterns were checked on the TV; broader real-photo color review remains an issue-driven follow-up. |
| SMB client library | SMBJ 0.15.0 | Supports SMB2/3 and explicit Guest auth. SMBJ rejects Guest when the server requires signing. With the Mac mini's signing requirement turned off, the TV listed and displayed photos from the share; see [the server change](PHOTO_SOURCES.md#mac-mini-signing-change-2026-10-12). |

## Screensaver components

| Component | Responsibility |
| --- | --- |
| TV activity | Remote-friendly setup, interval selection, source status, and slideshow preview |
| `DreamService` | System screensaver entry point and lifecycle handling |
| Slideshow controller | `SlideshowPlayer` selects and schedules items, handles pause/resume and source recovery. It is Android-coupled; a clock/task abstraction is deferred until useful for a concrete change. |
| Renderer | Display the current image and transitions; shared by the activity and the dream |
| Photo source interface | Supply image references independently of the renderer |
| Photo index | Persistent source-bound catalog for album selection; All mode still scans and samples the configured share at playback start |
| Settings store | Persist interval, source configuration, media type, selection mode, random pool size, layout and label mode |
| Source adapters | Bundled sample images and read-only SMB, with explicit Guest access |

Both the TV activity and `DreamService` should use the same slideshow controller and renderer. The dream must release work in `onDreamingStopped` and `onDetachedFromWindow`. Keep source authorization separate from the dream UI; a screensaver should never launch a sign-in flow while idle. Preload the next image before its transition so the screen never shows a blank frame.

## Data and privacy

Bundled local images provide the demo and fallback source. In **All** mode, the read-only SMB adapter enumerates supported files below `Photos Library.photoslibrary/originals/`, filters exclusions, and samples the requested pool for the current run; it does not persist a whole-tree index. In **Choose Albums** mode, the TV reads a source-bound metadata catalog built from a staged, read-only SQLite snapshot and uses its saved album membership. The user accepts that hidden and recently deleted originals can appear in All mode. For credential-free access to the tested share, use explicit Guest mode with no saved username or password; do not treat anonymous access or an arbitrary blank-password username as equivalent. SMBJ connects when the Mac mini's SMB signing requirement is disabled. Do not log photo filenames or host addresses. Internal Photos package directories must never be presented as albums.

The shared library is a clone of the live `Samsung_T5` library on a second external drive on the Mac mini. Incremental database/WAL synchronization supports explicit refresh; if the drive sleeps or is not mounted, the app reports an unavailable source and retries transient errors rather than presenting it as an empty library. A persistent All-mode index and periodic background refresh are deferred. Any image cache on the TV needs an explicit size and retention policy.

## Platform dependency

Android provides `DreamService`; on this TCL firmware, third-party selection uses `adb shell settings put secure screensaver_components`. The app dream activated at the default 10-minute timeout, including after a TV restart (restart result user-reported). No separate TCL Auto Launch control was found or needed in observed runs. The TCL's idle Art Mode is separate from Google's Ambient mode; the current firmware's behavior is recorded in [device testing](DEVICE_TESTING.md#device-findings). Recheck after firmware updates and keep the launcher preview available.
