# Photo source feasibility

Checked against official provider documentation on 2026-10-04. Recheck before implementing OAuth or caching because provider behavior and policies change.

## Google Photos

Google sign-in authenticates the user; the OAuth token's scopes and the Photos API determine which media the app can read. Google removed the broad `photoslibrary.readonly` access model in 2025. The Library API now focuses on media created by the app. The [Google Photos API update](https://developers.google.com/photos/support/updates) and [authorization guide](https://developers.google.com/photos/overview/authorization) explain the distinction.

The [Picker API](https://developers.google.com/photos/picker/guides/get-started-picker) lets the user explicitly choose photos. Users can search for an album title in the picker, but albums are not directly presented as an automatically syncing source in its [picking experience](https://developers.google.com/photos/picker/guides/picking-experience). Picker sessions and selected media access expire; [base URLs normally last up to 60 minutes](https://developers.google.com/photos/library/guides/access-media-items). We need to validate the policy and UX for any longer-lived slideshow before implementing it.

The [Photos Ambient API](https://developers.google.com/photos/ambient/guides/about) is designed for ambient slideshows on TVs and photo frames. It uses [OAuth for TVs and limited-input devices](https://developers.google.com/photos/ambient/guides/configure-your-app), and users configure media sources such as albums in Google Photos. However, [Google requires acceptance into the Photos partner program](https://developers.google.com/photos/partner-program/overview) before an app can access this API. A normal Google sign-in or an OAuth token from another scope does not bypass that requirement.

Google TV's [built-in Ambient mode](https://support.google.com/googletv/answer/10070821?hl=en) already offers Google Photos album selection and slideshow speed on supported devices. This is a useful baseline for testing the TV and might cover the Google-only use case without custom code.

## Apple Photos

Apple's [PhotoKit](https://developer.apple.com/documentation/PhotoKit) exposes a user's Photos library, including iCloud-backed content, to authorized apps on Apple devices. Apple's [CloudKit](https://developer.apple.com/documentation/cloudkit) concerns an app's own containers; it is not a direct API to a user's personal iCloud Photos library. We have not found a documented Apple Photos API that an Android TV app can use to sign in and browse all personal albums directly.

An optional browser experiment is [Photos on iCloud.com](https://support.apple.com/en-gb/guide/icloud/mm73262d11fe/icloud). It supports browsing albums and a [full-screen slideshow](https://support.apple.com/en-gb/guide/icloud/mmc8ef9b8a9a/icloud). In that slideshow, selected videos appear as thumbnails rather than playing as videos. Apple's [recommended iCloud.com browsers](https://support.apple.com/en-gb/118308) are desktop browsers on Mac and PC, so login, layout, and remote navigation on a Google TV browser are unverified. The website does not give our app an API for custom timing or automatic system-screensaver activation.

Chosen path for the first personal build: the TV reads available image files from the existing Mac mini SMB share. A public [iCloud Shared Album website](https://support.apple.com/en-gb/guide/iphone/ipha8f8fc3c5/26/ios/26) can be viewed by anyone with the link, but is not a private-library API and should not be treated as one.

SMB gives file access, not a Photos API. It can display locally available originals, but cannot reliably reveal album membership, edits, or iCloud-only assets. If automatic album selection becomes essential, a Mac-side PhotoKit export or index is a possible later addition; the TV can continue to read resulting images through SMB. Direct Apple ID sign-in on Android TV still does not provide a documented Photos-library API. [PhotoKit access](https://developer.apple.com/documentation/PhotoKit) · [Photo library authorization](https://developer.apple.com/documentation/photokit/fetching-assets)

### Mac mini library finding (2026-10-11)

The existing SMB `Photos` share contains `Photos Library.photoslibrary`. Read-only inspection from this Mac confirmed that the package has `originals` subdirectories with readable JPEG and HEIC image data. The package also contains databases and other private implementation files. These internal directories are **not** the albums shown in Photos. Apple [warns against manually accessing or altering library contents](https://support.apple.com/en-gb/guide/photos/pht12e7a8015/mac). The user explicitly chose read-only SMB access to original image files for this personal app. The app must never write to the package or parse its database; it should tolerate layout changes and missing files rather than assuming the package is a stable public API.

The Mac mini's Photos settings screenshot shows the open library under `Samsung_T5/…/Pictures/Photos Library.photoslibrary`; the user confirmed this and the package reached through the SMB `Photos` share are the same location. The disabled “Use as System Photo Library” button indicates the open library is already the System Photo Library, per [Apple's guidance](https://support.apple.com/en-ie/104946). If iCloud Photos uses Optimize Mac Storage, some full-resolution originals may exist only in iCloud and therefore be absent from the SMB files. [Optimize storage](https://support.apple.com/en-lamr/guide/photos/phta9b4673b4/mac). Video files and HEIC decoding need separate device checks before claiming full library coverage.

The ordinary folders beside the package can be a separate SMB source later. Their folder names do not represent Photos albums.

Still to check on the Mac mini: Photos → Settings → iCloud, "Download Originals to this Mac" versus "Optimize Mac Storage". Record the result here.

### What originals contains

`originals` holds every original the library has downloaded, not just what the Photos app shows in its main view. Expected behavior based on how Photos stores files, not yet verified against this library:

- **Hidden photos** are flagged in the database only; their files stay in `originals`.
- **Recently Deleted** items keep their files until they are permanently deleted (after about 30 days or when emptied).
- **Edits are not applied.** Crops, rotations, and adjustments made in Photos live elsewhere in the package; `originals` holds unedited files.
- Screenshots, document scans, receipts, and Live Photo `.mov` companion files are mixed in with ordinary photos.

An "all of originals" slideshow on a shared living-room TV will therefore show hidden and deleted photos. Before enabling it, decide whether to accept that or use a Mac-side export into a separate folder as the default source. An export folder fixes four things at once: album selection, hidden and deleted photos, applied edits, and photos that exist only in iCloud. The TV reads it over the same SMB share.

### SMB authentication test (2026-10-11)

The Mac mini's `Photos` share is configured for SMB Guest access and read-only access. From the development Mac, an explicit Guest mount (`//guest:@<host>/Photos`) could read the library's `originals` directory and three sample images; the recursive inventory completed. The share listing probe also accepted Guest access. Current-session authentication and anonymous-only access were rejected. This establishes that the server accepts SMB Guest access, not anonymous access; an Android SMB client must offer an explicit Guest mode (or equivalent guest credentials). Do not treat an arbitrary username with a blank password as Guest. This was tested from the development Mac, not the TV, and does not establish that the app's SMB client works on the TV. SMB1 is obsolete and insecure, and macOS file sharing is built around SMB2/3, so the Android client library must support SMB2 or later.

Because the share is readable by Guest, any device on a network that can reach the Mac mini's SMB service may be able to read its contents. Keep the share read-only and limit network reachability to the intended local network.

## Product implication

“All photos” and “live album sync” are goals, not promises for both providers. The app must show capabilities per source honestly. For this private setup, build the local slideshow first, then add read-only SMB access to available originals or an exported folder (see [what originals contains](#what-originals-contains)). To select actual Photos albums, export them into named folders on the share or generate a separate album index on the Mac mini; do not infer albums from package directories. Google Picker remains an optional limited-selection path.
