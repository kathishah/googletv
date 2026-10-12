# Proposed architecture

## Repository shape

`apps/screensaver/` will be one standalone Android application. Future TV apps can be siblings under `apps/`. Avoid a shared Android module until more than one app actually needs the same code.

## Screensaver components

| Component | Responsibility |
| --- | --- |
| TV activity | Remote-friendly setup, interval selection, source status, and slideshow preview |
| `DreamService` | System screensaver entry point and lifecycle handling |
| Slideshow controller | Select the next image, schedule transitions, pause/resume |
| Photo source interface | Supply image references independently of the renderer |
| Settings store | Persist interval and source choices |
| Source adapters | Local test images first; read-only SMB library files next |

Both the TV activity and `DreamService` should use the same slideshow controller and renderer. The dream must release work in `onDreamingStopped` and `onDetachedFromWindow`. Keep source authorization separate from the dream UI; a screensaver should never launch a sign-in flow while idle.

## Data and privacy

Start with bundled local test images. The SMB adapter will enumerate supported image files below `Photos Library.photoslibrary/originals/`, read them without writing to the share, and downsample for the TV display. It must ignore databases, caches, hidden files, and other package content. Keep the SMB host, share, and authentication mode in TV settings. For credential-free access to the tested share, use explicit Guest mode with no saved username or password; do not treat anonymous access or an arbitrary blank-password username as equivalent. If registered-user access is offered, never put credentials in Git or logs and protect stored credentials with Android Keystore. A future album adapter can read user-exported album folders over the same SMB share. The package's internal directory names must never be presented as Photos albums. Any image cache on the TV needs an explicit size and retention policy.

## Platform dependency

Android provides `DreamService`, but Google TV's Ambient mode UI may not expose third-party dreams on every TCL firmware. The first on-device milestone must verify selection and idle activation. Keep a launcher-based slideshow path so the project is usable if system integration is restricted.
