# Repository guidance

This repository contains personal Google TV apps. Keep each app under `apps/<app-name>/` and shared documentation under `docs/`.

## Screensaver goals

- Use Kotlin and standard Android TV APIs for the first app. Provide a remote-friendly launcher/settings screen and an Android `DreamService`.
- Build the first vertical slice with local sample photos and a configurable slide interval. Keep photo-source adapters separate from slideshow scheduling and rendering.
- Treat Google Photos Picker as explicit, time-limited selection. The purpose-built Photos Ambient API requires acceptance into Google's partner program. Do not assume normal Google sign-in grants continuous access to all albums or all photos.
- Treat Apple Photos access as a separate, unresolved integration. PhotoKit runs on Apple platforms; do not add an iCloud credential scraper or ask for an Apple ID password in the TV app.
- Keep credentials, OAuth tokens, signing keys, downloaded private photos, and device identifiers out of Git.
- Test on the actual TCL TV before claiming third-party screensaver integration works. Google TV Ambient mode behavior can vary by firmware.

## Working conventions

- Update `docs/PRODUCT.md` and `docs/PHOTO_SOURCES.md` when product or provider assumptions change.
- Document device-specific findings in `docs/DEVICE_TESTING.md`, without storing private IP addresses or pairing codes.
- Prefer small buildable milestones. Record exact build and device test commands with results.
- Do not add new dependencies or target SDK versions solely by guessing; check current official Android documentation first.
