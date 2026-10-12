# Roadmap

## 0. Optional browser feasibility check on the TCL TV

- Review [existing app options](APPS_RESEARCH.md) before committing to a custom build.
- Search the TV's Apps catalog for a browser and open `https://www.icloud.com/photos/`.
- Test Apple Account sign-in, album navigation with the remote, full-screen slideshow, and whether the chosen browser stays active long enough for the intended use.
- Record whether the web slideshow's timing and video-thumbnail behavior are acceptable. This is a manual slideshow, not a system idle screensaver.

## 1. Buildable TV skeleton

- Generate the Android Gradle project in `apps/screensaver/` using a current compatible Android Gradle Plugin, SDK, and JDK.
- Add a TV launcher activity, `LEANBACK_LAUNCHER` entry, `DreamService` declaration, and placeholder graphics.
- Connect to the TCL TV and verify a debug build installs and opens.

## 2. Local slideshow

- Add sample images, image-fit behavior, and a shared slideshow controller.
- Add remote-friendly interval settings and persist them.
- Test launcher preview, dream lifecycle, and idle screensaver selection on the TV.

## 3. Read-only SMB Photos source

- Configure the TV with the Mac mini SMB host, `Photos` share, and an explicit SMB authentication mode. The tested share allows read-only Guest access; support Guest without saving a username or password. If registered-user access is offered, store credentials only on the TV. Anonymous SMB is a separate mode and was rejected by the server during testing.
- Enumerate supported image files below `Photos Library.photoslibrary/originals/` without writing to the library or reading its database.
- Downsample images, skip unsupported files, handle disconnected shares, and test JPEG/HEIC behavior on the TCL TV.
- For album selection, use exported album folders or a separate album index on the SMB share. Do not treat package directories as albums.

## 4. Google Photos proof of concept

- Use built-in Google TV Ambient mode for Google-only album playback where it meets the need.
- If integration into the custom app is still wanted, review Picker API policy for a persistent screensaver use case and prototype explicit selection with session expiry handling.
- Google Ambient API is designed for TV slideshows but requires partner-program acceptance; do not assume personal sideloading removes that requirement.

## 5. Polish

- Add shuffle/order controls, transitions, image metadata options, and test on the actual TV over long sessions.
