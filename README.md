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

Planning is in place and the TV's OS is known (Android TV 12). The development Mac still needs the Android toolchain, and `adb` has not yet been connected to the TV. The Android app and Gradle project have **not** been generated yet. The first implementation is a testable local slideshow, followed by read-only image access through the Mac mini's existing SMB `Photos` share.

The default Google TV Ambient mode already supports Google Photos album selection and slideshow speed on supported devices. Our app is intended to add control over the slideshow and display available images from the Mac mini's Photos library using SMB. Direct SMB access does not reveal Photos album membership; album selection needs a separate export or index. Google also offers a purpose-built Photos Ambient API for third-party TV slideshows, but access requires acceptance into its partner program. See [photo source constraints](docs/PHOTO_SOURCES.md) before implementing cloud sign-in.

## First implementation target

Build a TV launcher app and a `DreamService` that display local sample photos, allow a user to pick an interval with the remote, and can be sideloaded on the TCL TV. Google TV on Android TV 12 is reported to have no settings menu for third-party screensavers, so the dream is selected with `adb` and needs the TCL Auto Launch permission. This TV also has TCL's own idle Art Mode, which may take precedence; verify that the dream starts on idle. The launcher slideshow remains useful even if the firmware does not allow replacing Ambient mode.

## Local prerequisites

- Android Studio with Android SDK and platform tools (`adb`).
- JDK 17 or the JDK bundled with a compatible Android Studio release.
- TCL TV with developer options and debugging enabled, on the same network as the development Mac.

The TV is already in developer mode. This Mac currently has no working JDK, Android SDK, or `adb` (the Xcode license is accepted), so an Android build and device test have not been run yet.

Start with the ordered [setup sequence](docs/DEVICE_TESTING.md#setup-sequence), then follow [the roadmap](docs/ROADMAP.md).
