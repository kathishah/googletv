# Roadmap

Milestones are in order. Each has an exit condition; do not start the next milestone until it is met or the gap is recorded in [device testing](DEVICE_TESTING.md#device-findings).

## 0. Development Mac and TV setup

Follow [setup steps 1–5](DEVICE_TESTING.md#setup-sequence):

1. Install the Android toolchain on the development Mac.
2. Connect `adb` to the TV over the network (Android TV 12 has no pairing-code wireless debugging).
3. Record the TV model and the original `screensaver_components` and `screen_off_timeout` values.
4. Check the Settings menus and the `adb` method for selecting a screensaver, and whether TCL's Art Mode runs instead of it on idle.
5. Learn where the TCL Auto Launch permission is set.

Also, on the Mac mini, check Photos → Settings → iCloud for "Download Originals" versus "Optimize Mac Storage", and record it in [photo sources](PHOTO_SOURCES.md).

Exit: `adb devices` shows the TV as `device`, the original screensaver settings are recorded, and it is known whether Art Mode must be turned off for a third-party screensaver to run.

## 1. Optional: validate the TV path with an existing app

Follow [setup step 6](DEVICE_TESTING.md#6-optional-prove-the-whole-path-with-aerial-views): set Aerial Views to show photos from the SMB share as the screensaver. This tests idle activation, Auto Launch, SMB Guest from the TV, and HEIC decoding without writing code. Restore the original screensaver afterward.

Exit: each item recorded as working or not working. A failure here changes the plan for milestones 2–4; record it before continuing. If Aerial Views already meets the need, decide whether the custom app is still worth building and record why.

## 2. Buildable TV skeleton

Before generating the project, decide and record in [architecture](ARCHITECTURE.md#setup-decisions):

- Application ID / package name.
- `minSdk 31` (the TV runs Android TV 12); choose `targetSdk`, `compileSdk`, Android Gradle Plugin, and Kotlin versions from current official documentation.
- UI toolkit for the settings activity and the renderer shared by the activity and the dream.

Then:

- Generate the Android Gradle project in `apps/screensaver/`.
- Add a TV launcher activity, `LEANBACK_LAUNCHER` entry, `DreamService` declaration, and placeholder graphics.
- Install a debug build on the TCL TV ([setup step 7](DEVICE_TESTING.md#7-build-and-install-our-app)), select the dream with `adb`, allow Auto Launch, and check whether it starts on idle.

Exit: the debug build installs and opens from the launcher, and the placeholder dream starts on idle (or the failure is recorded).

## 3. Local slideshow

- Add sample images, image-fit behavior, and a shared slideshow controller written as plain Kotlin with an injectable clock so it can be unit-tested.
- Add remote-friendly interval settings and persist them.
- Test launcher preview, dream start/stop lifecycle, and idle start on the TV.

Exit: the [first build acceptance criteria](PRODUCT.md#first-build-acceptance-criteria) pass on the TV.

## 4. Read-only SMB Photos source

- Decide what the default SMB source is: the full `originals` directory, or a Mac-side export folder. `originals` includes hidden and recently deleted photos and unedited originals; see [what originals contains](PHOTO_SOURCES.md#what-originals-contains).
- Choose an Android SMB client library after checking current documentation: SMB2/3 support (do not rely on obsolete SMB1), explicit Guest authentication, license, and maintenance status.
- Configure the TV with the Mac mini SMB host, `Photos` share, and an explicit SMB authentication mode. The tested share allows read-only Guest access; support Guest without saving a username or password. If registered-user access is offered, store credentials only on the TV. Anonymous SMB is a separate mode and was rejected by the server during testing.
- Build a saved file index refreshed in the background; the dream reads the index rather than scanning the share on start.
- Enumerate supported image files below the chosen root without writing to the share or reading the Photos database.
- Downsample images, honor EXIF orientation, skip unsupported files, preload the next image, and show a clear state when the share or the `Samsung_T5` drive is unavailable.
- Test JPEG and HEIC display on the TCL TV.
- For album selection, use exported album folders or a separate album index on the SMB share. Do not treat package directories as albums.

## 5. Google Photos proof of concept

- Use built-in Google TV Ambient mode for Google-only album playback where it meets the need.
- If integration into the custom app is still wanted, review Picker API policy for a persistent screensaver use case and prototype explicit selection with session expiry handling.
- Google Ambient API is designed for TV slideshows but requires partner-program acceptance; do not assume personal sideloading removes that requirement.

## 6. Polish

- Add shuffle/order controls, transitions, image metadata options, and test on the actual TV over long sessions.

## Backlog

- [Browser-only Apple Photos check](DEVICE_TESTING.md#optional-browser-only-apple-photos-check) on the TV. Lower priority now that SMB is the chosen source.
