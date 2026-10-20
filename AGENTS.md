# Repository guidance

This repository contains personal Google TV apps. Keep each app under `apps/<app-name>/`, shared documentation under `docs/`, and the dated log of findings under `journal/`.

## Screensaver guardrails

- Use Kotlin and standard Android TV APIs. Provide a remote-friendly launcher/settings screen and an Android `DreamService`. Keep photo-source adapters separate from slideshow scheduling and rendering.
- Google Photos is not supported (see `docs/PHOTO_SOURCES.md`). If that is revisited: treat Google Photos Picker as explicit, time-limited selection. The purpose-built Photos Ambient API requires acceptance into Google's partner program. Do not assume normal Google sign-in grants continuous access to all albums or all photos.
- The real source is the Mac mini's SMB `Photos` share. Access it strictly read-only; never present the library package's internal directories as albums. Support explicit SMB Guest mode; Guest is not anonymous access and not an arbitrary username with a blank password. Do not log photo filenames or host addresses.
- `Photos Library.photoslibrary/originals` includes hidden and recently deleted photos and unedited originals. It is the default source by a recorded product decision in `docs/PRODUCT.md`; do not change that without a new decision.
- The TV is a TCL 65" A300 (NXTFRAME art TV) running Android TV 12 (API 31). It has TCL's own idle Art Mode in addition to Google's Ambient mode. Third-party screensavers are selected with `adb`; testing on this firmware found no separate TCL Auto Launch control and no permission was needed for observed idle activation. Recheck after firmware updates; see `docs/DEVICE_TESTING.md`.
- Treat Apple Photos access as a separate, unresolved integration. PhotoKit runs on Apple platforms; do not add an iCloud credential scraper or ask for an Apple ID password in the TV app.
- Keep credentials, OAuth tokens, signing keys, downloaded private photos, and device identifiers out of Git.
- Test on the actual TCL TV before claiming third-party screensaver integration works. Google TV Ambient mode behavior can vary by firmware.

## Working conventions

- Update `docs/PRODUCT.md` and `docs/PHOTO_SOURCES.md` when product or provider assumptions change. Keep `docs/PHOTO_SOURCES.md` to current state; record dated investigations and discoveries as new entries in `journal/` (see `journal/README.md`) and never rewrite old entries.
- Record dated device findings and test results as entries in `journal/`, without storing private IP addresses, pairing codes, or serial numbers. Keep `docs/DEVICE_TESTING.md` as the runbook and update each setup step's status there when it changes.
- Prefer small buildable milestones. Record exact build and device test commands with results in a journal entry.
- Do not add new dependencies or target SDK versions solely by guessing; check current official Android documentation first.
