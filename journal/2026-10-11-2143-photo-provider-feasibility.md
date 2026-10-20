# Photo provider feasibility (checked 2026-10-04)

Moved verbatim from `docs/PHOTO_SOURCES.md` on 2026-10-19, with links updated for the new location. Provider documentation was checked on 2026-10-04; provider behavior and policies change, so recheck before implementing OAuth or caching.

## Google Photos

Google sign-in authenticates the user; the OAuth token's scopes and the Photos API determine which media the app can read. Google removed the broad `photoslibrary.readonly` access model in 2025. The Library API now focuses on media created by the app. The [Google Photos API update](https://developers.google.com/photos/support/updates) and [authorization guide](https://developers.google.com/photos/overview/authorization) explain the distinction.

The [Picker API](https://developers.google.com/photos/picker/guides/get-started-picker) lets the user explicitly choose photos. Users can search for an album title in the picker, but albums are not directly presented as an automatically syncing source in its [picking experience](https://developers.google.com/photos/picker/guides/picking-experience). Picker sessions and selected media access expire; [base URLs normally last up to 60 minutes](https://developers.google.com/photos/library/guides/access-media-items). We need to validate the policy and UX for any longer-lived slideshow before implementing it.

The [Photos Ambient API](https://developers.google.com/photos/ambient/guides/about) is designed for ambient slideshows on TVs and photo frames. It uses [OAuth for TVs and limited-input devices](https://developers.google.com/photos/ambient/guides/configure-your-app), and users configure media sources such as albums in Google Photos. However, [Google requires acceptance into the Photos partner program](https://developers.google.com/photos/partner-program/overview) before an app can access this API. A normal Google sign-in or an OAuth token from another scope does not bypass that requirement.

Google TV's [built-in Ambient mode](https://support.google.com/googletv/answer/10070821?hl=en) already offers Google Photos album selection and slideshow speed on supported devices. This is a useful baseline for testing the TV and might cover the Google-only use case without custom code.

## Apple Photos

Apple's [PhotoKit](https://developer.apple.com/documentation/PhotoKit) exposes a user's Photos library, including iCloud-backed content, to authorized apps on Apple devices. Apple's [CloudKit](https://developer.apple.com/documentation/cloudkit) concerns an app's own containers; it is not a direct API to a user's personal iCloud Photos library. We have not found a documented Apple Photos API that an Android TV app can use to sign in and browse all personal albums directly.

An optional browser experiment is [Photos on iCloud.com](https://support.apple.com/en-gb/guide/icloud/mm73262d11fe/icloud). It supports browsing albums and a [full-screen slideshow](https://support.apple.com/en-gb/guide/icloud/mmc8ef9b8a9a/icloud). In that slideshow, selected videos appear as thumbnails rather than playing as videos. Apple's [recommended iCloud.com browsers](https://support.apple.com/en-gb/118308) are desktop browsers on Mac and PC, so login, layout, and remote navigation on a Google TV browser are unverified. The website does not give our app an API for custom timing or automatic system-screensaver activation.

Chosen path for the first personal build: the TV reads available image files from the existing Mac mini SMB share. A public [iCloud Shared Album website](https://support.apple.com/en-gb/guide/iphone/ipha8f8fc3c5/26/ios/26) can be viewed by anyone with the link, but is not a private-library API and should not be treated as one.

SMB gives file access, not a Photos API. It can display locally available originals, but cannot reliably reveal album membership, edits, or iCloud-only assets. A read-only inspection of the cloned Photos database found album-to-file references for regular albums; the shared-album sample needs more work (see [album database probe](2026-10-16-2354-album-database-probe.md)). Direct Apple ID sign-in on Android TV still does not provide a documented Photos-library API. [PhotoKit access](https://developer.apple.com/documentation/PhotoKit) · [Photo library authorization](https://developer.apple.com/documentation/photokit/fetching-assets)
