# Photo screensaver product brief

## Goal

Show personal photos on a TCL Google TV as a slideshow. The user can choose which source to show and how long each photo remains visible. The first real source is the Mac mini's Photos library files, read from its existing SMB `Photos` share. The server has been verified to allow read-only SMB Guest access and to reject anonymous SMB access; Google Photos remains a possible later source.

## Core experience

1. Open the TV app with the remote.
2. Choose a photo source and the photos or albums to display.
3. Choose the slide interval. Initial proposed choices: 10 seconds, 30 seconds, 1 minute, 5 minutes, and 15 minutes.
4. Preview the slideshow immediately.
5. If the TCL firmware permits it, select the app as the system screensaver so it starts on idle.

## First build acceptance criteria

- App appears in the TV launcher and every control is usable with a D-pad and Select/Back.
- Local sample images display without stretching; landscape and portrait photos are handled gracefully.
- Interval survives app restart and the photo changes at the selected interval.
- Slideshow can run both from a launcher preview and from the `DreamService`.
- The dream stops timers and image work when dismissed.
- Actual TCL TV behavior is recorded, including whether third-party dreams appear in system settings.

## Cloud source expectations

- Google Photos: standard Google sign-in plus the Picker API can access photos and videos the user explicitly selects. Google's purpose-built Ambient API supports TV slideshows and user-configured sources, but requires acceptance into the Photos partner program. Do not promise automatic sync of arbitrary existing albums through normal sign-in or the Picker API.
- Mac mini Photos library: the TV reads image files from the library package's `originals` directory over SMB, read-only. The SMB client must support an explicit Guest access mode for the tested share; Guest is distinct from anonymous access. This supports an all-available-photos slideshow without a Mac companion. The package's internal directories are not Photos albums; album selection requires a separate album-to-file export or index.
- A source must clearly show when selection expires, is unavailable, or requires renewed permission. Avoid a silent empty slideshow.

## Open product decisions

- Exact TCL model and Google TV/Android TV version.
- Decide how to provide Photos album membership over SMB. A Mac-side export into named folders is the first candidate; the TV must not infer albums from the library package's internal directories or database.
- Determine whether all intended originals are present locally when iCloud Photos optimization is enabled.
- Whether display images should be cached on the TV for offline playback, and for how long.
- Behavior when the TV is offline and whether photos should shuffle or play in order.
