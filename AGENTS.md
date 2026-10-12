# Repository guidance

This repository contains personal Google TV apps. Keep each app under `apps/<app-name>/` and shared documentation under `docs/`.

## Screensaver goals

- Use Kotlin and standard Android TV APIs for the first app. Provide a remote-friendly launcher/settings screen and an Android `DreamService`.
- Build the first vertical slice with local sample photos and a configurable slide interval. Keep photo-source adapters separate from slideshow scheduling and rendering.
- Treat Google Photos Picker as explicit, time-limited selection. The purpose-built Photos Ambient API requires acceptance into Google's partner program. Do not assume normal Google sign-in grants continuous access to all albums or all photos.
- The first real source is the Mac mini's SMB `Photos` share. Access it strictly read-only: never write to the share, never read or parse the Photos library database, and never present the library package's internal directories as albums. Support explicit SMB Guest mode; Guest is not anonymous access and not an arbitrary username with a blank password. Do not log photo filenames or host addresses.
- `Photos Library.photoslibrary/originals` includes hidden and recently deleted photos and unedited originals. Do not make it the default source without a recorded product decision in `docs/PRODUCT.md`.
- The TV is a TCL 65" A300 (NXTFRAME art TV) running Android TV 12 (API 31). It has TCL's own idle Art Mode in addition to Google's Ambient mode. Third-party screensavers are selected with `adb` and need the TCL Auto Launch permission; follow the setup sequence in `docs/DEVICE_TESTING.md`.
- Treat Apple Photos access as a separate, unresolved integration. PhotoKit runs on Apple platforms; do not add an iCloud credential scraper or ask for an Apple ID password in the TV app.
- Keep credentials, OAuth tokens, signing keys, downloaded private photos, and device identifiers out of Git.
- Test on the actual TCL TV before claiming third-party screensaver integration works. Google TV Ambient mode behavior can vary by firmware.

## Working conventions

- Update `docs/PRODUCT.md` and `docs/PHOTO_SOURCES.md` when product or provider assumptions change.
- Document device-specific findings in `docs/DEVICE_TESTING.md`, without storing private IP addresses, pairing codes, or serial numbers. Update each setup step's status when it changes.
- Prefer small buildable milestones. Record exact build and device test commands with results.
- Do not add new dependencies or target SDK versions solely by guessing; check current official Android documentation first.
