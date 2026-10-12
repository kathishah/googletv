# Photo screensaver

First planned Google TV app. The Android project will live here.

## Initial build

- TV launcher screen with remote navigation and a manual slideshow preview.
- Android `DreamService` using the same slideshow engine.
- Local sample photos, a user-selected interval, and a clear empty/error state.
- On-device verification on the TCL TV.

The first real source will read available image files under `Photos Library.photoslibrary/originals/` through the existing SMB share, strictly read-only. The tested share allows explicit SMB Guest access and rejects anonymous access, so the SMB client needs a Guest authentication mode. Album selection will need an export or index because the package folders are not Photos albums. See [the product spec](../../docs/PRODUCT.md) and [photo source constraints](../../docs/PHOTO_SOURCES.md).
