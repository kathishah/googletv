# Photo sources

What the app can read, and why. This file holds the current state; the investigations behind it are dated entries in [`journal/`](../journal/README.md), listed at the end.

## Scope

The app works only with an Apple Photos library: the Mac mini's `Photos` SMB share, read-only. Google Photos and other sources are not supported, and neither are Shared Albums yet (see [Albums](#albums)). The three bundled artworks are a demo source and the fallback shown while SMB is failing (see [`ASSETS.md`](../apps/screensaver/ASSETS.md)).

## Why only Apple Photos

- **Google Photos.** Google removed broad read access to an existing library in 2025. The Picker API is explicit, time-limited selection, and the Photos Ambient API for TV slideshows requires acceptance into Google's partner program. Google TV's built-in Ambient mode already covers Google-only albums.
- **Apple Photos.** PhotoKit runs only on Apple platforms, and CloudKit covers an app's own containers, so there is no documented Apple Photos API for an Android TV app. SMB gives file access instead, and the Photos database on the share gives regular-album membership.

Sources and details are in the [provider feasibility entry](../journal/2026-10-11-2143-photo-provider-feasibility.md). Provider policies change; recheck before implementing OAuth or caching.

## The Mac mini source

- **What the share is.** The SMB `Photos` share contains `Photos Library.photoslibrary`, a clone of the live library (on an external drive) made daily by Carbon Copy Cloner (CCC). Package-internal directories are not albums. Apple [warns against manually accessing or altering library contents](https://support.apple.com/en-gb/guide/photos/pht12e7a8015/mac), so access is read-only and the package schema is treated as private and changeable. Photos is set to Download Originals to this Mac. See [library finding](../journal/2026-10-11-2143-mac-mini-library-finding.md).
- **Authentication.** The share allows read-only SMB Guest access and rejects anonymous access. The app defaults to SMBJ's explicit Guest mode, never anonymous access or an arbitrary username with a blank password. A Registered user option is available for other SMB configurations; its password is encrypted with an Android Keystore key. The settings screen can test authentication and read-only folder enumeration. See [Guest test](../journal/2026-10-11-2143-smb-guest-authentication-test.md) and [SMB settings update](../journal/2026-10-25-2135-settings-smb-authentication-and-tv-install.md).
- **SMB signing.** SMBJ refuses a Guest session when the server requires signing, and no client option avoids that. The Mac mini's server preference `SigningRequired` is therefore set to false, and sessions are unsigned (accepted: read-only, LAN-only). A macOS update may restore the default; check with `python3 tools/check_smb_signing.py "$MAC_MINI_IP"`. The Registered user option can be used with a dedicated read-only account when Guest access is unsuitable. See [client failure](../journal/2026-10-12-1729-smbj-guest-signing-failure.md) and [the server change and its revert](../journal/2026-10-12-1729-mac-mini-signing-change.md).
- **File permissions.** Photos writes some originals as owner-only (0600). CCC copies those modes to the clone volume, where Guest is evaluated as "others" and denied. So `tools/smb_access_probe/make_photos_readable.sh` adds read for others to supported photo and video originals on the live library, run manually once a day before the daily clone. The decisions and their accepted consequences are in [product](PRODUCT.md#product-decisions). A launchd agent for the script was blocked by macOS privacy controls (TCC); a CCC pre-copy script mode exists but is not installed. See [Guest permissions](../journal/2026-10-13-1223-guest-read-permissions-per-file.md), [clone ownership and the library-wide change](../journal/2026-10-13-1223-clone-ownership-and-permission-script.md), and [video permissions](../journal/2026-10-17-1830-video-permissions.md).

### What originals contains

`originals` holds every original the library has downloaded, not just what the Photos app shows in its main view. Expected behavior based on how Photos stores files, not yet verified against this library:

- **Hidden photos** are flagged in the database only; their files stay in `originals`.
- **Recently Deleted** items keep their files until they are permanently deleted (after about 30 days or when emptied).
- **Edits are not applied.** Crops, rotations, and adjustments made in Photos live elsewhere in the package; `originals` holds unedited files.
- Screenshots, document scans, receipts, and Live Photo `.mov` companion files are mixed in with ordinary photos (confirmed for screenshots, documents and receipts: see the exclusion rule below).

An "all of originals" slideshow on a shared living-room TV can therefore show hidden and recently deleted photos. The user chose `originals` as the default for this personal app and does not want a separate warning or opt-in. A separate export folder could provide hidden/deleted filtering, applied edits, or files that exist only in iCloud, but exported photo copies are not part of the current album-selection plan. The ordinary folders beside the package could be a separate SMB source later; their folder names do not represent Photos albums.

### Albums

Regular albums are read from the Photos SQLite database: `ZGENERICALBUM` entity 34 with `ZKIND=2`, joined through `Z_34ASSETS` to `ZASSET`, with `ZUUID` as the opaque album ID. The TV stages the clone's `Photos.sqlite` and WAL from the read-only share, queries the local copy, and keeps a metadata-only JSON catalog in app-private storage. The flow, refresh behavior and timings are in [album selection](engineering/ALBUM_CATALOG.md). TV code never writes to the library, validates paths, fails closed on schema changes, and never presents package-internal directories as albums. Evidence: [database probe](../journal/2026-10-16-2354-album-database-probe.md) and [cache validation](../journal/2026-10-17-1455-regular-album-cache-validation.md).

**Shared Albums are unresolved.** They use a different relationship (`ZSHARE`, via `ZASSET.ZCOLLECTIONSHARE`), and probes could not reliably map them to files on the share. See the [shared album probes](../journal/2026-10-16-2354-shared-album-probes.md).

### Excluding screenshots, documents, and receipts

The slideshow never shows them. The rule and thresholds are a product decision ([product](PRODUCT.md#product-decisions)), based on Photos' own classification in the staged database. Signals, calibration and limits: [exclusion entry](../journal/2026-10-18-1219-excluding-screenshots-documents-receipts.md).

## Open gaps

- Shared Albums cannot be mapped to files yet.
- Photos' private schema can change.

## Discovery log

Dated findings behind this file, oldest first. Other TV-side findings are in [device testing](DEVICE_TESTING.md#device-findings).

- 2026-10-11: [provider feasibility](../journal/2026-10-11-2143-photo-provider-feasibility.md), [Mac mini library finding](../journal/2026-10-11-2143-mac-mini-library-finding.md), [SMB Guest test](../journal/2026-10-11-2143-smb-guest-authentication-test.md)
- 2026-10-12: [SMBJ Guest signing failure](../journal/2026-10-12-1729-smbj-guest-signing-failure.md), [signing change](../journal/2026-10-12-1729-mac-mini-signing-change.md), [EXIF prefix read](../journal/2026-10-12-2250-exif-prefix-read.md)
- 2026-10-13: [Guest permissions per file](../journal/2026-10-13-1223-guest-read-permissions-per-file.md), [clone ownership and permission script](../journal/2026-10-13-1223-clone-ownership-and-permission-script.md)
- 2026-10-16: [album database probe](../journal/2026-10-16-2354-album-database-probe.md), [shared album probes](../journal/2026-10-16-2354-shared-album-probes.md)
- 2026-10-17: [regular-album cache validation](../journal/2026-10-17-1455-regular-album-cache-validation.md), [video survey](../journal/2026-10-17-1813-video-survey.md), [video permissions](../journal/2026-10-17-1830-video-permissions.md)
- 2026-10-18: [excluding screenshots, documents and receipts](../journal/2026-10-18-1219-excluding-screenshots-documents-receipts.md)
- 2026-10-25: [SMB settings and authentication](../journal/2026-10-25-2135-settings-smb-authentication-and-tv-install.md)
