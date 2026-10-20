# Existing app survey

Moved verbatim from `docs/APPS_RESEARCH.md` on 2026-10-19, with links updated for the new location. Dated at the commit that first recorded it.

Checked 2026-10-04 from public Google Play listings, developer documentation, and Google/Apple help pages. Ratings and availability can change and Google Play may filter apps differently on the TCL TV. None of these apps has been installed or tested on this TV yet.

## Closest options

| App | Public review signal | Fit for this project | Main gap |
| --- | --- | --- | --- |
| [Google TV Ambient Mode](https://support.google.com/googletv/answer/10070821?hl=en) | Built into Google TV | Google Photos albums, slideshow speed, automatic idle activation | No Apple Photos source |
| [TCL Art Mode / T-Exhibition](https://support.tcl.com/en_US/t-exhibition-turning-your-tcl-tv-into-a-work-of-art) | Built into the A300 (NXTFRAME); added 2026-10-11 | Idle art display with a personal photo gallery mode | Photos come from USB or the TCL Home phone app, not SMB; interval and refresh behavior not yet checked |
| [Aerial Views](https://play.google.com/store/apps/details?id=com.neilturner.aerialviews) | 4.6 stars, about 880 Google Play reviews | Strong TV screensaver for photos/videos from USB, SMB, WebDAV, or Immich | No direct Google Photos or iCloud Photos account connection |
| [Photo Gallery for Cloud Photos](https://play.google.com/store/apps/details?id=io.klebo.iphoto) | About 4.1 stars, 270 Google Play reviews | Browse private iCloud Photos and albums on Android TV | Listing does not establish automatic idle screensaver or interval control; requests Apple ID sign-in through a third-party app |
| [PixFolio](https://play.google.com/store/apps/details?id=com.snapwood.photos2) | 3.8 stars, about 5,100 Google Play reviews | Google Photos selection, local import, slideshow, TV screensaver | No Apple source; [developer says](https://www.snapwoodapps.com/google2025/) Google Photos changes prevent automatic library updates |
| [gFolio](https://play.google.com/store/apps/details?id=com.snapwood.gfolio) | 4.4 stars, about 4,000 Google Play reviews | TV screensaver with interval and automatically refreshing Google Drive folders | Reads Google Drive, not Google Photos or iCloud Photos; requires a separate sync/export path |

## Near match with too little review history

[PhotoWall](https://play.google.com/store/apps/details?id=net.nextlayertechnology.photowall) claims a TV screensaver mixing Google Photos picks, public iCloud Shared Albums, and phone uploads, with offline copies. Its Google Play listing currently shows only 100+ downloads and no rating summary. Apple [public shared albums](https://support.apple.com/en-gb/guide/iphone/ipha8f8fc3c5/26/ios/26) are viewable by anyone with the link; this is not access to private iCloud albums. The [developer shows a photo-duration setting](https://nextlayertechnology.net/photowall), but the range is not documented. Verify interval control, album refresh, video playback, and TCL idle activation before relying on it.

## Not recommended as primary choices

- [Slideshow for Google Photos](https://play.google.com/store/apps/details?id=jp.dip.mukacho.picasadaydream) advertises albums, all photos, interval, video, and screensaver, but is rated about 2.9 stars. A 2026 review reports repeated loss of its Google Photos connection.
- [PhotoCloud Frame Slideshow](https://play.google.com/store/apps/details?id=sk.baka.photoframe) lists many sources including iCloud Public Share and Google Photos/Picasa, but is rated about 3.6 stars and was last updated in 2024. Verify any claimed Google Photos connection against the 2025 API change before using it.
- [PicDock](https://play.google.com/store/apps/details?id=kr.devdad.picdock) advertises many sources, but the [developer says](https://devdad.kr/blog/android-tv-photo-screensaver-nas.html) its current screensaver does not display iCloud Shared Albums or a mix of sources, and it may not appear in every TV Play catalog.
- [Easy Photo Player](https://play.google.com/store/apps/details?id=com.easyphotoplayer.tv) supports a manual Google Photos slideshow with 3–30 second speed, but has little review history, a 10-view/day free tier, and its [FAQ](https://easyphotoplayer.com/) says photos must be selected again every seven days. Its listing does not establish idle screensaver support.
- The older Photo Gallery and Screensaver by Furnaghan is no longer available at its direct Google Play listing. Older recommendations predate Google's 2025 Photos API restrictions.

## Recommended order to try on the TCL TV

1. Test built-in Ambient mode with a Google Photos album and speed setting.
2. For private Apple albums, test `icloud.com/photos` in a TV browser or try Photo Gallery for Cloud Photos after reviewing its sign-in and privacy details.
3. If one automatic screensaver must mix sources, test PhotoWall with a *test* public iCloud Shared Album first; there is not yet enough review history to call it established.
4. If reliability matters more than direct cloud connection, export/sync photos to USB or a local share and test Aerial Views. Its [maintainer's setup guide](https://github.com/theothernt/AerialViews) notes that some TCL Google TVs need Auto Launch permission for a third-party screensaver.

Update 2026-10-11: with SMB from the Mac mini chosen as the source, Aerial Views is the closest existing match. It is open source, reads photos from SMB, and its guide documents selecting it with `adb` on Google TV. It is now an optional validation step before writing code ([setup step 6](../docs/DEVICE_TESTING.md#6-optional-prove-the-whole-path-with-aerial-views), [roadmap milestone 1](2026-10-19-2246-roadmap-history.md#1-optional-validate-the-tv-path-with-an-existing-app)). Not yet checked: whether it supports SMB Guest access and HEIC.

No well-reviewed app found in this survey clearly supports private Apple Photos **and** automatically syncing Google Photos albums **and** a user-defined interval **and** confirmed idle screensaver operation on this TCL model. The TV is now known (TCL 65" A300, Android TV 12; see [known TV facts](../docs/DEVICE_TESTING.md#known-tv-facts)), but screensaver compatibility is not settled until tested on it.
