# Google TV apps

Personal Android TV / Google TV apps, starting with a photo screensaver for a TCL TV.

## Repository layout

| Path | Purpose |
| --- | --- |
| `apps/screensaver/` | First app: TV photo slideshow and `DreamService` screensaver |
| `docs/PRODUCT.md` | User experience and acceptance criteria |
| `docs/ARCHITECTURE.md` | Proposed app structure and data flow |
| `docs/PHOTO_SOURCES.md` | What Google Photos and Apple Photos currently allow |
| `docs/APPS_RESEARCH.md` | Existing Google TV photo apps and their tradeoffs |
| `docs/DEVICE_TESTING.md` | Build, connect, and test on the TCL TV |
| `docs/ROADMAP.md` | Ordered implementation milestones |

## Current state

Repository planning and device setup are in place. The Android app and Gradle project have **not** been generated yet. The first implementation is a testable local slideshow, followed by read-only image access through the Mac mini's existing SMB `Photos` share.

The default Google TV Ambient mode already supports Google Photos album selection and slideshow speed on supported devices. Our app is intended to add control over the slideshow and display available images from the Mac mini's Photos library using SMB. Direct SMB access does not reveal Photos album membership; album selection needs a separate export or index. Google also offers a purpose-built Photos Ambient API for third-party TV slideshows, but access requires acceptance into its partner program. See [photo source constraints](docs/PHOTO_SOURCES.md) before implementing cloud sign-in.

## First implementation target

Build a TV launcher app and a `DreamService` that display local sample photos, allow a user to pick an interval with the remote, and can be sideloaded on the TCL TV. Then test whether this TV firmware exposes third-party screensavers in its settings. The launcher slideshow remains useful even if the firmware does not allow replacing Ambient mode.

## Local prerequisites

- Android Studio with Android SDK and platform tools (`adb`).
- JDK 17 or the JDK bundled with a compatible Android Studio release.
- TCL TV with developer options and debugging enabled, on the same network as the development Mac.

The TV is already in developer mode. This Mac currently has no working JDK, Android SDK, or `adb`, so an Android build and device test have not been run yet.

Start with [device testing](docs/DEVICE_TESTING.md) and [the roadmap](docs/ROADMAP.md).
