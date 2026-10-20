# Google TV apps

Personal Android TV / Google TV apps, starting with a photo screensaver for a TCL 65" A300 Google TV.

## Repository layout

| Path | Purpose |
| --- | --- |
| `apps/screensaver/` | First app: TV photo slideshow and `DreamService` screensaver |
| `docs/PRODUCT.md` | User experience and acceptance criteria |
| `docs/ARCHITECTURE.md` | Proposed app structure and data flow |
| `docs/PHOTO_SOURCES.md` | What Google Photos and Apple Photos currently allow |
| `docs/APPS_RESEARCH.md` | Existing Google TV photo apps and their tradeoffs |
| `docs/DEVICE_TESTING.md` | Ordered setup steps, TV facts, and the device test log |
| `docs/ROADMAP.md` | Ordered implementation milestones |

## Current state

The Android app is implemented in `apps/screensaver/` and runs on the TCL A300 (Android TV 12). It includes a remote-friendly settings screen, local preview, `DreamService`, read-only SMB Guest source, regular-album catalog and picker, media type selection, video playback, and single/portrait-pair layouts. Device findings and remaining checks are in [`docs/DEVICE_TESTING.md`](docs/DEVICE_TESTING.md); the current work order is in [`docs/ROADMAP.md`](docs/ROADMAP.md).

The default Google TV Ambient mode already supports Google Photos album selection and slideshow speed on supported devices. Our app is intended to add control over the slideshow and display available images from the Mac mini's Photos library using SMB. Direct SMB access does not reveal Photos album membership; album selection needs a separate export or index. Google also offers a purpose-built Photos Ambient API for third-party TV slideshows, but access requires acceptance into its partner program. See [photo source constraints](docs/PHOTO_SOURCES.md) before implementing cloud sign-in.

## First implementation target

The app DreamService is selected with `adb` and has started on idle at the default timeout. Testing found no separate TCL Auto Launch control and none was needed for observed activation; recheck after firmware updates. The launcher preview remains available alongside system screensaver integration.

## Local prerequisites

- Android Studio with Android SDK and platform tools (`adb`).
- JDK 17 or the JDK bundled with a compatible Android Studio release.
- TCL TV with developer options and debugging enabled, on the same network as the development Mac.

The TV is already in developer mode. This Mac currently has no working JDK, Android SDK, or `adb` (the Xcode license is accepted), so an Android build and device test have not been run yet.

Start with the ordered [setup sequence](docs/DEVICE_TESTING.md#setup-sequence), then follow [the roadmap](docs/ROADMAP.md).
