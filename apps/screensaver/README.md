# Photo screensaver

A Kotlin / Android Views app for Google TV: a remote-friendly settings screen, a manual preview, and an Android `DreamService` that the system starts on idle. It runs on a TCL 65" A300 (Android TV 12, `minSdk 31`).

**It only works with an Apple Photos library**, read from the Mac mini's `Photos` SMB share, strictly read-only with explicit SMB Guest access. Three bundled public-domain artworks are a demo source and the fallback while the share is unreachable ([ASSETS.md](ASSETS.md)).

## What it does

- **All or Choose Albums.** *All* shuffles a random sample of 100, 250, 500 or 1,000 supported items from the library. *Choose Albums* plays every usable photo in the regular Photos albums you pick. The album list comes from a catalog the TV builds from a staged copy of the Photos database and keeps in app-private storage; **Refresh** rebuilds it ([album selection](../../docs/PRODUCT.md#album-picker)).
- **Type.** Photos, Photos + Videos, or Videos. Videos play muted in the in-app slideshow only; the screensaver shows 500 random photos instead ([media type](../../docs/PRODUCT.md#videos)).
- **Hides screenshots, documents and receipts**, using Photos' own classification.
- **Layout.** Single photos, or portrait photos paired side by side over patterned backgrounds ([multi-image layouts](../../docs/PRODUCT.md#portrait-pairs)).
- **Labels.** Place name plus date from the photo's EXIF data, or no label. Place names are best effort; GPS coordinates show when none resolves.
- **Large HEIC photos.** 24 MP+ HEIC images are decoded with a bundled libheif/libde265 tile decoder, because the TV's platform decoders reject them.

## Settings

The settings screen is a compact table (Photo source, Media, Type, Pool, Interval, Layout, Label). Changes stay staged until **Save**. The choices and what each applies to are in the [settings reference](../../docs/PRODUCT.md#settings).

## When things go wrong

- **Share unreachable.** The slideshow retries with backoff. After three consecutive failures it shows the stored artworks with the caption "SMB failure. Loading…" and returns to photos as soon as a read succeeds.
- **Configuration errors** (Guest rejected, signing required, wrong share) show a message and do not retry.
- **Screensaver and the catalog.** The screensaver uses the catalog last refreshed in the app. If it is missing or in an unsupported format, the screensaver asks you to refresh in the app; it does not build one during idle startup.

## Build, install, and learn more

- Build, install and set as the screensaver: [root README](../../README.md#build-and-install).
- How the code is organized: [architecture](../../docs/ARCHITECTURE.md#code-map).
- TV setup and the release checklist: [device testing](../../docs/DEVICE_TESTING.md).
- Why only Apple Photos, and how the Mac mini is configured: [photo sources](../../docs/PHOTO_SOURCES.md).
- The history of findings and measurements: [journal](../../journal/README.md).
