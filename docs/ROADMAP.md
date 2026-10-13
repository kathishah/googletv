# Roadmap

Milestones are in order. Each has an exit condition; do not start the next milestone until it is met or the gap is recorded in [device testing](DEVICE_TESTING.md#device-findings).

## Current work order

1. **Reconcile documentation and close source-operation questions.** Keep the HEIC permission/decode findings consistent across docs. The Mac mini uses Download Originals; the permission script and CCC clone run daily, with the script first. Full live-library/clone content equality remains unverified and is recorded as an open verification gap.
2. **Correct photo orientation.** Done: apply EXIF orientation in the shared decode path. Two portrait 12 MP HEICs selected from the recreated first-25 sample set displayed upright on the TV; 24 MP decoding remains a separate item below.
3. **Soften label rendering.** Done: photo labels use 85% text opacity and 44% shadow opacity; status messages remain fully opaque. Reviewed in the TV preview and confirmed the label stayed readable with a softer appearance.
4. **Continue the 24 MP HEIC investigation.** TV diagnostics found the native HEIF frame extraction rejects 5712×4284 files before creating a decoder. A read-only sample converts on the development Mac with both `sips` and libheif; libheif also exports its 45 tiles. Decide whether to prove in-app tile decoding, use a Mac-side conversion/export, or skip these files.
5. **Finish TV integration checks.** Verify TCL Art Mode precedence and Auto Launch behavior, and complete the pending label-mode checks.
6. **Consider later features.** Saved file index, album export/index, video support, Google Photos, and visual transitions remain later work.

## 0. Development Mac and TV setup

Follow [setup steps 1–5](DEVICE_TESTING.md#setup-sequence):

1. Install the Android toolchain on the development Mac.
2. Connect `adb` to the TV over the network (Android TV 12 has no pairing-code wireless debugging).
3. Record the TV model and the original `screensaver_components` and `screen_off_timeout` values.
4. Check the Settings menus and the `adb` method for selecting a screensaver, and whether TCL's Art Mode runs instead of it on idle.
5. Learn where the TCL Auto Launch permission is set.

The Mac mini's Photos iCloud setting, CCC clone cadence, and timing of the manual photo-permission script are recorded in [photo sources](PHOTO_SOURCES.md). Whether the clone contains every file from the live library remains unverified.

Exit: `adb devices` shows the TV as `device`, the original screensaver settings are recorded, and the ADB method for selecting a third-party dream is known. Art Mode precedence and Auto Launch behavior remain in current work item 5.

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

Status: in progress. The user chose the library's `originals` folder and accepts hidden/recently deleted photos without a separate warning. Async photo loading and a read-only explicit Guest adapter are implemented. After the Mac mini's `SigningRequired` preference was turned off, TV preview could read and advance through SMB photos. The TCL also started the app DreamService on idle, advanced between SMB photos, and displayed location/date labels (see [the server change](PHOTO_SOURCES.md#mac-mini-signing-change-2026-10-12) and [device tests](DEVICE_TESTING.md#device-findings)). Random full-tree sampling and place-name reverse geocoding are verified in preview. On 2026-10-13, the HEIC `STATUS_ACCESS_DENIED` was traced to owner-only (0600) originals in the live library. CCC copies those modes faithfully to a clone volume that enforces ownership, where Guest is evaluated as "others". By user decision ([product](PRODUCT.md#open-product-decisions)), all 18,601 supported photo originals were made readable by others, and the clone scan found 0 unreadable after CCC. 12 MP HEIC display was verified in TV preview and DreamService. 24 MP HEIC decoding is under investigation; see the ordered work list above. A random 10-photo library sample played 9 of 10. TCL Auto Launch and Art Mode precedence remain unverified.

- Use the full `originals` directory by default per the user's choice; it includes hidden and recently deleted photos and unedited originals. A Mac-side export folder can be selected later.
- Done: resolved the SMB signing/Guest incompatibility server-side with the Mac mini's `SigningRequired=false` preference, applied via Apple's stop, sync, and start sequence. Client-side SMB2-only and `withSigningRequired(false)` options did not help. If a macOS update restores required signing, recheck with `tools/check_smb_signing.py`; the fallback is a dedicated read-only account. Do not rely on obsolete SMB1.
- Configure the TV with the Mac mini SMB host, `Photos` share, and an explicit SMB authentication mode. The tested share allows read-only Guest access; support Guest without saving a username or password. If registered-user access is offered, store credentials only on the TV. Anonymous SMB is a separate mode and was rejected by the server during testing.
- Build a saved file index refreshed in the background; the dream currently rescans the share on start.
- Done: scan the full configured tree and uniformly sample up to the selected 100, 250, 500, or 1,000 supported images, then shuffle the selected pool. The scan remains uncached, so it runs again at slideshow start.
- Done: skip images that fail to load during playback. TV preview showed unreadable SMB files being skipped while the slideshow continued. Done: apply EXIF orientation to `BitmapFactory` results; `ImageDecoder` already applies it. Two portrait 12 MP HEICs from the recreated first-25 sample selection were verified upright on the TV. Done: soften photo label text and shadow opacity, with readability confirmed in TV preview. Remaining playback work: sample to at least display resolution (4032×3024 originals currently decode to 1008×756 on a 1080p screen), preload the next image, and show a clear state when the share or the external drive holding the cloned library is unavailable.
- Done: configurable labels for place names (with coordinate fallback) plus capture date, capture date only, or no label. Missing metadata components are omitted. Place-name reverse geocoding is best effort and was confirmed on real SMB photos.
- Done: SMB JPEG display and multi-photo advancement in TCL DreamService.
- Partly done: 12 MP (4032×3024) HEICs display in TV preview and DreamService, with labels and slide advancement, decoded by Android's `BitmapFactory`. **24 MP (5712×4284) HEICs fail on this TV**: `BitmapFactory` returns null after reading bounds, and the ImageDecoder fallback throws `IOException`. One was confirmed in a 10-photo random sample, and the earlier 25-file trial very likely lost all five of its 24 MP files the same way. The fallback has not decoded any HEIC yet. Diagnosis: all Android decode paths fail for 24 MP files, because the TV's vendor-modified native HEIF frame extraction rejects the 5×9 grid of 640×896 tiles without creating a decoder, even though the HEVC decoders accept that tile format (see [device findings](DEVICE_TESTING.md#device-findings)). A 24 MP sample converts to full-resolution JPEG on the development Mac with `sips` and libheif; libheif can also export all 45 tiles. Options (user decision): decode HEIF grids in the app by extracting the compressed tiles and decoding them with `MediaCodec`; test one tile first. Or convert/export on the Mac side. Or skip 24 MP HEICs for now.
- Done (user decision): make supported photo originals readable by Guest with `tools/smb_access_probe/make_photos_readable.sh` on the live library. 18,601 files were changed, and the clone had 0 unreadable after CCC. The script is re-run manually for new photos.
- Done: a random 10-photo library sample (4 HEIC, 5 JPEG, 1 PNG) was all readable over Guest. 9 of 10 played on the TV; the failure was a 24 MP HEIC (see above).
- Done: establish a daily manual source routine. The user runs the permission script once a day before the daily CCC clone. A launchd agent was blocked by macOS privacy controls; a CCC "before copying files" script mode exists but is not installed.
- For album selection, use exported album folders or a separate album index on the SMB share. Do not treat package directories as albums.

## 5. Google Photos proof of concept

- Use built-in Google TV Ambient mode for Google-only album playback where it meets the need.
- If integration into the custom app is still wanted, review Picker API policy for a persistent screensaver use case and prototype explicit selection with session expiry handling.
- Google Ambient API is designed for TV slideshows but requires partner-program acceptance; do not assume personal sideloading removes that requirement.

## 6. Polish

- Add transitions and test randomized selection and metadata labels in DreamService over long sessions.

## Backlog

- [Browser-only Apple Photos check](DEVICE_TESTING.md#optional-browser-only-apple-photos-check) on the TV. Lower priority now that SMB is the chosen source.
