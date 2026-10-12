# Photo screensaver product brief

## Goal

Show personal photos on a TCL Google TV as a slideshow. The user can choose which source to show and how long each photo remains visible. The first real source is the Mac mini's Photos library files, read from its existing SMB `Photos` share. The server has been verified to allow read-only SMB Guest access and to reject anonymous SMB access; Google Photos remains a possible later source.

## Core experience

1. Open the TV app with the remote.
2. Choose a photo source. The first SMB version shows all photos under one configured folder; choosing albums comes later and needs an exported folder or index (see [photo sources](PHOTO_SOURCES.md#product-implication)).
3. Choose the slide interval. Initial proposed choices: 10 seconds, 30 seconds, 1 minute, 5 minutes, and 15 minutes.
4. Preview the slideshow immediately.
5. Make the app the system screensaver so it starts on idle. On this TV that is a one-time `adb` setup plus the TCL Auto Launch permission, not a settings menu choice (see [device testing](DEVICE_TESTING.md#setup-sequence)).

## First build acceptance criteria

- App appears in the TV launcher and every control is usable with a D-pad and Select/Back.
- Local sample images display without stretching; landscape and portrait photos are handled gracefully.
- Interval survives app restart and the photo changes at the selected interval.
- Slideshow can run both from a launcher preview and from the `DreamService`.
- The dream stops timers and image work when dismissed.
- Actual TCL TV behavior is recorded: whether third-party dreams appear in system settings, whether `adb` selection works, and whether the dream starts on idle after Auto Launch is allowed.

## Cloud source expectations

- Google Photos: standard Google sign-in plus the Picker API can access photos and videos the user explicitly selects. Google's purpose-built Ambient API supports TV slideshows and user-configured sources, but requires acceptance into the Photos partner program. Do not promise automatic sync of arbitrary existing albums through normal sign-in or the Picker API.
- Mac mini Photos library: the TV reads image files from the library package's `originals` directory over SMB, read-only. The SMB client must support an explicit Guest access mode for the tested share; Guest is distinct from anonymous access. This supports an all-available-photos slideshow without a Mac companion. `originals` also contains hidden and recently deleted photos, and its files do not include edits made in Photos (see [what originals contains](PHOTO_SOURCES.md#what-originals-contains)). The package's internal directories are not Photos albums; album selection requires a separate album-to-file export or index.
- A source must clearly show when selection expires, is unavailable, or requires renewed permission. Avoid a silent empty slideshow.

## Open product decisions

- The TV is a TCL 65" A300 (NXTFRAME) on Android TV 12 (see [known TV facts](DEVICE_TESTING.md#known-tv-facts)). Open: how the app should coexist with TCL's built-in idle Art Mode, which has its own photo gallery.
- Default SMB source: all of `originals` (includes hidden and recently deleted photos, unedited) or a Mac-side export folder. Decide before milestone 4.
- Decide how to provide Photos album membership over SMB. A Mac-side export into named folders is the first candidate; the TV must not infer albums from the library package's internal directories or database.
- Determine whether all intended originals are present locally: check Photos → Settings → iCloud on the Mac mini for "Download Originals to this Mac" versus "Optimize Mac Storage".
- Whether display images should be cached on the TV for offline playback, and for how long.
- Behavior when the TV is offline and whether photos should shuffle or play in order.
