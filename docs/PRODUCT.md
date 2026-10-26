# Photo screensaver product spec

What the app does and the decisions behind it. How it is built is in [architecture](ARCHITECTURE.md) and the per-feature design docs in [`engineering/`](engineering/): [album catalog and sync](engineering/ALBUM_CATALOG.md), [video playback](engineering/VIDEO_PLAYBACK.md), and [portrait pairs](engineering/PORTRAIT_PAIRS.md). Interface mockups are in [`mockups/`](mockups/).

Contents: [Goal and scope](#goal-and-scope) · [Core experience](#core-experience) · [Settings](#settings) · [Album picker](#album-picker) · [Playback](#playback) · [Screensaver](#screensaver) · [When things go wrong](#when-things-go-wrong) · [Acceptance criteria](#first-build-acceptance-criteria) · [Source expectations](#source-expectations) · [Product decisions](#product-decisions) · [Open questions](#open-questions) · [Out of scope](#out-of-scope)

## Goal and scope

Show personal photos on a TCL Google TV as a slideshow. The user can choose which source to show and how long each photo remains visible. The first real source is the Mac mini's Photos library files, read from its existing SMB `Photos` share. The server has been verified to allow read-only SMB Guest access and to reject anonymous SMB access. The app works only with an Apple Photos library; other sources, including Google Photos, are not supported (see [photo sources](PHOTO_SOURCES.md#scope)).

### Album scope

Let the user choose one or more regular, user-created albums from the Mac Photos library for the TV slideshow. The TV reads existing supported image files over the SMB share; it does not export or duplicate photos. Shared albums are explicitly deferred to a later phase and do not appear in this picker. Their path investigation does not block implementation or release of regular-album selection.

For this phase, “album” means a regular user-created Photos album whose assets can be mapped to existing readable files under the library's `originals` directory. Smart albums, folders, Shared Albums, and videos are out of scope unless validation shows they can be supported cleanly and the product scope is updated.

## Core experience

1. Open the TV app with the remote.
2. Choose a photo source and what to show, the slide interval, the random pool size, and the label (see [Settings](#settings) and the [Album picker](#album-picker)).
3. Preview the slideshow immediately.
4. Make the app the system screensaver so it starts on idle. On this TV, selection uses one-time `adb` setup; no separate TCL Auto Launch control was found or needed for observed idle activation (see [device testing](DEVICE_TESTING.md#setup-sequence)). Recheck after firmware updates.

## Settings

The launcher settings are a compact table: setting name at left and D-pad choices at right. Type, Layout, and the Location + date label toggle share one row. Type and Layout use icon-only buttons with accessible names; the label control is a switch with a calendar and location-pin icon. Preview, Save, and Cancel are icon-only actions with accessible names.

| Row | Choices | Notes |
| --- | --- | --- |
| Photo source | Sample pics, SMB share, SMB settings | SMB host, share name, and library path are edited on the separate SMB settings screen |
| Media | **All** (default), Choose Albums | *All* shuffles a random sample of the library; *Choose Albums* opens the [album picker](#album-picker) |
| Type | **Photos** (default), Photos + Videos, Videos | Icon buttons; shares a row with Layout and Label. See [Type](#type) |
| Pool | 100, 250, 500, 1,000 | Applies to *All* only; counts photos and videos together; disabled in *Choose Albums*; the screensaver fallback always uses 500 |
| Interval | 10 secs, 30 secs, 1 min, 5 min, 15 min | Photos only; a video plays to its end (up to 60 s) and a pair stays up for one Interval |
| Layout | **Single** (default), Portrait pairs | Icon buttons; shares a row with Type and Label. See [Layout](#layout) |
| Label | Location + date on/off | Calendar and pin icon with a switch; place names are best effort and GPS coordinates appear when Android cannot resolve one. Legacy Date-only values are unsupported and fall back to Location + date |

### Saving and the SMB screen

Main-screen **Save** commits the settings draft and **Cancel** restores the last saved values. **SMB settings** opens a separate screen with the saved server address, share name, and library path prefilled and editable. Sign-in defaults to explicit **Guest**; **Registered user** reveals username and password fields. The password is encrypted with a device-local Android Keystore key. **Test connection** checks read-only access and enumerates the configured library folder. **Back** returns to the settings table with edits staged; the main-screen Save or Cancel commits or discards them.

### Type

The **Type** row sits directly below **Media** and above **Pool**.

- **Type** is persisted as `media_type` (`PHOTOS`, `PHOTOS_AND_VIDEOS`, `VIDEOS`). A missing or unknown value is `PHOTOS`. It is independent of the saved album IDs and of the **Media** choice (*All* versus *Choose Albums*), like the existing selection mode.
- Choosing a mode that includes videos needs the catalog (it holds the video list). If no valid catalog exists, the same in-screen loading and refresh flow as album selection opens, with the same actionable error and retry if it fails; the saved mode is not changed until the catalog is ready.
- **Pool** keeps its meaning (the number of items drawn when Media is *All*) and now counts photos and videos together. It stays disabled when Media is *Choose Albums*. It applies to the in-app slideshow only; the screensaver fallback below always uses 500.
- **Label** applies to videos as far as metadata allows (below).
- **Interval** applies to photos only. A video stays on screen for its own length (below). The settings screen says so under the Interval row ("Videos play to the end, up to 60 s").

### Layout

The **Layout** row goes after **Interval** and before **Label**: **Single** (default) and **Portrait pairs**. The note "Two portrait photos side by side. Landscape photos stay full screen." sits beside the buttons.

- Persisted as `layout_mode` (`SINGLE`, `PORTRAIT_PAIRS`); a missing or unknown value is `SINGLE`.
- It is independent of Media, Type, Pool, Interval, and Label, and is saved with the rest on **Save**.
- **It applies to both the in-app slideshow and the screensaver.** The screensaver's photos are photos only, so the layout always applies there; with Type other than Photos it applies to the 500 random photos the screensaver shows ([Screensaver](#screensaver)).
- **Videos are never paired.** With Type including videos, a video is always its own full-screen slide, and only photos are candidates for pairing.
- **Pool counts photos, not slides.** A pool of 100 photos is about 90 slides in Portrait pairs (see the numbers below), because each pair is one slide.
- Interval: **a pair stays up for one Interval**, so each photo is on screen as long as it would be alone. The pool is therefore used up slightly faster.

## Album picker

Mockups: [settings and picker](mockups/album-selection.html), [carousel](mockups/album-picker-carousel.html), and the [layout options that were considered](mockups/album-picker-layout-options.html).

1. **Choose Albums** opens the picker and activates album mode. Album selection changes save immediately; the settings table summarizes the saved choice. Switching back to All preserves album IDs for later use.
2. **Choose albums** opens a full-screen, D-pad-friendly horizontal carousel of regular albums. Do not include a search bar or show Shared Albums, disabled shared cards, or a Type filter in the picker. Use full-card cover images with a compact title and picture count overlaid on a dark gradient. If no readable cover exists, use a neutral cover without hiding the album. The first five visible covers are prefetched; cover thumbnails use an app-private persistent cache so later visits can show them without another SMB read.
3. Provide top-rail controls for **Refresh**, **Sort** (newest year-month first by default, or A–Z), **Select all**, and **Clear all**. Albums with the same year-month sort by title; albums without a usable year-month follow dated albums, sorted alphabetically. A focused card has a clear outline independent of its selected checkmark. The bottom rail shows selected album and distinct picture totals, a saved-automatically hint, and **Done**; remote Back also returns to settings. Every album toggle and bulk selection saves immediately. Preserve selected IDs if the catalog is missing or invalid, and show an actionable warning for IDs no longer present. When multiple selected albums contain the same file, include it once. Use all usable photos from selected albums and shuffle the full set without a fixed pool-size limit. The random-pool setting applies only in **All** mode.
4. In **Choose Albums** mode, settings summarizes the saved choice (for example, “3 albums · 842 photos”) and allows reopening the picker. Preview and DreamService use the same persisted mode and selection. Persist the mode separately from album IDs so an empty or invalid album selection can never be interpreted as **All photos**.
5. Selecting **Choose Albums** opens the picker screen immediately. Show an in-screen spinner/status area while loading the local catalog or doing the first database read; do not navigate to a separate build/cache screen. Enumerate the current step (for example, reading the saved catalog, connecting to the share, copying the database, reading albums, validating photo paths, or saving the catalog). With a valid local catalog, render the album carousel as soon as it is read; do not wait for a network request. On first use, or when the user selects **Refresh**, read the cloned Photos SQLite database over SMB, build a metadata-only JSON catalog in persistent app-private storage, then render the carousel. Reopening uses the cached catalog without rereading SQLite. The carousel's top rail provides a D-pad accessible **Refresh** action.
6. Refresh runs in the same picker screen with step/progress feedback. Keep the old carousel usable if practical, and keep the old catalog until a replacement has been fully built and validated. If refresh fails while a valid cache exists, retain the old catalog and show an inline error. If no valid catalog exists, show an actionable error and retry/back choices. Preserve saved album IDs and never silently switch to **All photos**. If a refreshed catalog no longer contains a selected album, preserve that ID and explain that the selection is unavailable.
7. If a selected album contains no usable photos, keep it selected and explain that no readable supported photos are currently available. Keep **All photos** available as an explicit mode.

### The picker and Type

- Each card shows what the current **Type** would play: Type *Photos* shows "N photos" as today; *Photos + videos* shows "N photos · M videos"; *Videos* shows "M videos". A video badge appears on cards when videos are in scope.
- **Type Videos lists only albums that have videos** (373 of ~760 on this library); the subtitle says "Showing only albums with videos". Albums hidden by the filter keep any selection they had, so it is still there for the other Types: a note says how many selected albums are hidden, **Select all** selects only the albums shown, and **Clear all** clears everything. The bottom total counts the shown selected albums. If no album has videos the picker says so.
- With Type Photos + Videos an album with nothing eligible stays selectable, is dimmed, and reads "No pictures or videos"; with Type Photos the card is unchanged ("0 pictures"). Selected albums with nothing eligible add a warning under the controls, consistent with the existing rule that an empty album is explained, not hidden, outside the Videos filter.
- The bottom rail total reads "N albums · X items" (or "X pictures" with Type *Photos*, unchanged). Selection saves immediately and survives Type changes.
- Covers: unchanged for photo albums. With Type *Videos*, or for an album that has only videos while videos are in scope, the cover is a poster frame.

## Playback

### Photos

A photo stays up for the Interval. There are no transitions: the previous slide stays up until the next one is ready, then it switches. A label shows the place name and capture date from the photo's EXIF data, or nothing in No label mode. When specific albums are selected, the slideshow uses every usable photo in those albums.

### Videos

Status: **implemented and exercised on the TCL TV (2026-10-19).** All seven implementation milestones are complete; see the [milestone plan](../journal/2026-10-17-2012-media-type-implementation-plan.md) and [device findings](DEVICE_TESTING.md#device-findings). The DreamService video fallback, video playback, poster covers, failure recovery and soak results are recorded in the journal. Mockup: [media type](mockups/media-type.html). Design: [video playback](engineering/VIDEO_PLAYBACK.md).

Let the user choose what the **in-app slideshow** shows: **photos only** (today's behavior, still the default), **photos and videos**, or **videos only**. The choice applies to both Media choices (*All* and *Choose Albums*). Videos play muted, letterboxed, in the same shuffled sequence as photos.

**The screensaver (DreamService) never plays videos** (decided by the user, 2026-10-17). When Type is anything other than Photos, the screensaver falls back to 500 random photos from the library; see [Screensaver](#screensaver).

#### Playback rules

- **Dwell:** photos use the Interval setting. A video plays to its end, capped at **60 s**, whatever the Interval is (76% of clips are 60 s or shorter). A longer clip plays its first 60 s and moves on.
- **Look:** letterboxed (never cropped, like photos), black bars, muted, no controls. A clip is enlarged to fit but by at most 2× its own pixel size, so small old clips are not stretched into blur (a 640-wide clip plays 1280 px wide; a 160×90 clip plays as a small centered window). The view is resized to the clip's displayed size on every size change, so rotated portrait clips are upright and correctly proportioned.
- **Labels:** photos keep location and date from EXIF. For videos the date and, when present, location come from the container metadata (`MediaMetadataRetriever`, read from the same progressive source once the clip is ready). If a clip has neither, no label. **Measured:** of 9 clips, 7 had a date only, 1 had place and date, and 1 had none, so dates are reliable and locations are rare. The label is built by `VideoLabel`, with the same format as photos ("place · Jun 12, 2022", coordinates when no place name is found).
- **Transitions:** none. The previous slide stays up until the next one is ready (first video frame rendered, or photo decoded), then it switches.
- **Order:** one shuffled sequence. With Media *All* and videos included, the pool is drawn so videos appear in proportion to their share of the library (about 9%); Type *Videos* is all videos. With Media *Choose Albums*, all eligible items from the selected albums are shuffled together, as today.

#### States and errors

- **Preparing:** while a video's safe-start wait runs (usually under 5 s), the previous slide stays on screen. If the wait would exceed 60 s the clip is skipped for this pass and the log records a count, never a name.
- **Clip fails** (cannot open, no decoder, read error, stall longer than 10 s): advance at once to the next slide. An unplayable or missing clip is dropped from the pool; a clip with a transient reader or buffer failure goes to the back to be tried again. Three consecutive counted failures (video failures and retryable photo/share failures) use the existing stored-artwork fallback and its caption; ordinary per-file photo skips are not counted. A video counts as successful only when it reaches its end or the 60 s cap. The first frame alone does not reset the failure streak. A successfully shown photo also resets it. After a clip finishes, the next photo is preloaded as soon as the whole file is local.
- **Share unavailable** or access denied: existing behavior (retry with backoff, then stored artwork).
- **Unsupported container or codec:** counted as a failure; the format family is logged without file names.

### Portrait pairs

Status: **implemented and accepted on the TCL TV (2026-10-18).** All five milestones (catalog, planner and fit, renderer, player integration, settings and screensaver) are complete; see the [milestone plan](../journal/2026-10-18-1355-portrait-pairs-implementation-plan.md) and [device findings](../journal/2026-10-18-1549-portrait-pairs-milestone-5-settings-and-screensaver.md). Mock: [portrait-pairs.html](mockups/portrait-pairs.html) (single portrait, plain pair, and four three-slice styles: pinstripes, diamond lattice, walnut frame, brass fillet mat). Design: [portrait pairs](engineering/PORTRAIT_PAIRS.md).

Add a **Layout** setting. **Single** is today's behavior and stays the default. **Portrait pairs** shows two portrait photos side by side on one slide, so a portrait photo no longer sits in the middle of the screen with wide black bars. Landscape photos stay full screen, one at a time, exactly as now.

**Only portrait photos are paired** (the user's requirement): two photos share a slide only if both are portrait to begin with, so a pair never adds black space that a single photo would not have. The four-photo tile layout is **cancelled** (user decision, 2026-10-18).

#### Why: the black edges

On the 1920 × 1080 panel (photos are never cropped, as today):

| Slide | Photo size shown | Screen filled | Black on each side |
| --- | --- | --- | --- |
| Single 3:4 portrait (today) | 810 × 1080 | **42%** | 555 px |
| Single 2:3 portrait | 720 × 1080 | 38% | 600 px |
| Single 4:3 landscape | 1440 × 1080 | 75% | 240 px |
| **Pair** of 3:4 portraits | 810 × 1080 each | **84%** | 142 px |
| Pair of 3:4 and 2:3 | 810 and 720 × 1080 | 80% | 187 px |
| Pair of 2:3 portraits | 720 × 1080 each | 75% | 232 px |
| Pair of 9:16 portraits | 608 × 1080 each | 63% | 344 px |

With the three-slice background the free space becomes a pattern at the left, middle, and right, so the screen is used 100% (photos 84% and pattern 16% for two 3:4 photos), see [Three-slice background](#three-slice-background-see-the-mock).

A portrait photo is already limited by the screen's height, so it is shown at full height alone. Placing two next to each other costs it nothing: each photo in a pair is exactly as large as it would be alone, and the free space on the sides is used. Two 3:4 photos double the filled area (42% to 84%), which is the same as a landscape photo or better.

#### Which photos pair

A photo is **pairable** when its displayed aspect ratio is between **0.56 and 0.9** (9:16 up to a little under square). The upper limit is chosen so that almost every pair of pairable photos shares the screen at full height: with the least free space the layout keeps (16 px per slice, see below), two photos whose aspects add up to 1.73 or less fit at 1080 px high (two at 0.86 do); two at the 0.9 limit are drawn 4% smaller (1040 px high). Only 17 photos in the library sit between 0.78 and 0.9, so this is rare. Photos in the square-ish band (0.9 to 1.1, 0.7% of the library) are not paired in v1, and neither are very tall photos below 0.56 (0.2%). Everything else, and any photo the catalog does not know about, is a single.

#### Building the sequence (and how random it is)

Pairing is random in three ways: **which** portraits are paired, **who** their partner is, and **which look** a pair gets. Not every portrait is paired.

The pool is shuffled as it is today. The slide list is then built in one pass over the shuffled photos (`PairPlanner`, plain Kotlin, JVM-tested, with the random source passed in so tests can fix it):

1. A non-pairable photo (landscape, square, very tall, unknown, or any video) becomes a **single** slide in place.
2. A pairable photo flips a weighted coin: it becomes a **pair candidate** with probability `PAIR_PROBABILITY` (0.6) and otherwise stays a **single** slide, shown alone on black as today.
3. The first candidate becomes a single slide for now and waits. The **next candidate** in the shuffle is pulled forward into that slide, which becomes a **pair**. Then the waiting slot is free again.
4. A candidate that never finds a partner (an odd count at the end) stays a single.

So landscape photos and unpaired portraits keep their random order, and a pair appears where its first photo landed. Which photos are in the pool (albums, exclusion, pool size) is unchanged; the planner only decides how they share slides.

**What that looks like.** In a pool of 100 photos about 33 are portrait. About 20 of them become candidates and form about 10 pairs; the other 13 portraits show alone, and 67 landscapes show alone: roughly 90 slides, of which about 11% are pairs. No spacing rule is needed: with pairs this infrequent two in a row is rare and harmless, and a rule would make the order less random.

**The look of each pair is chosen when the pair is formed** and then stays fixed for that slide, so it never changes mid-slide:
- **Style:** one of the four styles, chosen at random, but **never the same style as the previous pair**, so consecutive pairs always differ.
- **Variant:** within the style, a random variant: walnut, oak, or cherry for the frame; brass on charcoal, silver on navy, or brass on navy for the mat; 8, 10, or 14 px pitch for the pinstripes; a small, medium, or large lattice. The photos' own colors vary the stripes and lattice further.
- **Order:** which of the two photos is on the left is the order in which they were shuffled.

Photos that are pair candidates but end up alone (the odd one out) get no pattern, like any other single.

#### Layout of a pair

- **Equal height, centered as a group.** Both photos are drawn at the same height `H = min(screen height, (screen width − 3 × 16) ÷ (aspect₁ + aspect₂))`, side by side, and the group is centered on the screen. The 16 px is the least a slice may be (the "gutter" of decision 1: with the three-slice background it is a minimum, not a black strip), so photos never touch each other or the edge. `H` is the full 1080 px whenever the two aspects add up to 1.73 or less, which covers every shape in the table above; two photos at the 0.9 limit shrink to 1040 px. (`PairFit`, built in milestone 2: the first draft said `H` is always 1080, which is false at the upper limit.)
- **Never cropped.** The layout is computed from the **decoded bitmaps' real sizes**, not the catalog's flag, so a photo whose database size is wrong still fits (the pair just shrinks or leaves more black); the catalog only decides who may be partners.
- No transition, as today: the previous slide stays up until the whole next slide (both photos) is ready, then it switches.

##### Three-slice background (see the mock)

Instead of black, the free space is divided into **three equal slices: left, middle, and right**, and a quiet pattern behind the photos shows through all three: `slice = (screen width - width of photo 1 - width of photo 2) / 3`, and the photos sit at `x = slice` and `x = 2 x slice + width of photo 1`. For two 3:4 photos the free space is 300 px, so each slice is 100 px (the layout is 100 | 810 | 100 | 810 | 100); for 3:4 + 2:3 it is 130 px per slice; for 9:16 + 9:16, 235 px. The slices replace the 16 px gutter and the outer black bars, so **100% of the screen is used and no black is left**. The photos are unchanged: same height, never cropped. Any pairable combination works the same way, with no special case for wide leftovers.
- **Four styles, one picked per pair** at random when the pair is formed (never the same as the previous pair's, with random variants inside the style; see Building the sequence). The user chose the first two after seeing the mock and asked for two frame-like styles to go with them:
  1. **Pinstripes:** thin vertical lines, 10 px apart, a lighter tone on a dark tone, running behind both photos.
  2. **Diamond lattice:** a fine diagonal grid in two quiet tones, running behind both photos.
  3. **Walnut frame:** vertical wood grain in the slices, a dark rebate line and a pale bevel line along each photo's sides, and a soft shadow, so the two pictures look set into one wooden diptych. Fixed walnut tones; an oak variant is possible.
  4. **Brass fillet mat:** a dark linen mat with two thin brass lines around each photo, the way a gallery mounts a print. Fixed tones.
  Dropped after the mock: color blocks and soft edge.
- **A frame can only show at the sides and in the middle.** The photos already run the full height of the screen, so there is no top or bottom rail. The frame effects (lines, rebate, shadow) are drawn around each photo's rectangle and are simply clipped at the top and bottom edges of the screen.
- **Colors.** Pinstripes and lattice take a base tone from the photos (each decoded bitmap scaled down to a few pixels and averaged, mixed and darkened; cheap, no new library) and a lighter tone for the lines. The two frame styles use fixed material tones. Contrast stays low: the background must never compete with the photos or their labels.
- Labels stay inside their own photos, so the background carries no text. As built each photo has its own caption at its bottom-left corner (same size and softness as a single photo's, at most two lines), even when both labels are identical (decision 5, default).

#### States and errors

- **One of the two photos fails** (bad file, decoder error): the pair is not skipped. The photo that loaded is shown as a single full-screen slide, the failed photo is dropped from the pool (or sent to the back for a network error), and it counts in the existing failure streak. Three consecutive failures use the stored artworks, as now. As built, only network-type failures count toward the three (exactly as for a single photo; an unreadable file never did), and a pair that lost a photo shows the other one alone without bringing up the artworks by itself.
- **A pair is not ready** (the second photo is still decoding): the previous slide stays up. This is the existing rule and means a slow pair dwells longer, never shows a blank.
- Pool of one photo, or a pool with no pairable photos: behaves exactly like Single.
- The stored fallback artworks are always single slides.

### Exclusions

The slideshow never shows screenshots, documents, or receipts, as Photos classified them. This applies to the app slideshow and the screensaver, in *All* and *Choose Albums*. The rule and its limits are a [product decision](#product-decisions); the evidence is in [photo sources](PHOTO_SOURCES.md#excluding-screenshots-documents-and-receipts).

## Screensaver

- The DreamService does not play or prepare video files and never starts a `MediaPlayer`.
- **Type = Photos:** unchanged. The screensaver honors Media (*All* or *Choose Albums*), the saved albums, Pool, Interval, and Label.
- **Type = Photos + Videos or Videos:** the screensaver ignores Media, the saved albums, Type, and Pool, and shows a random pool of **500 photos** drawn from the whole library (the same sampling as *All*). Interval and Label still apply. The saved albums and Type stay saved for the in-app slideshow.
- **Catalog:** the screensaver uses the catalog last refreshed from the app. If that catalog is missing or its format is no longer supported, the screensaver asks you to refresh the album catalog in the app instead of building it during idle startup. It does not check the Mac for newer Photos changes at dream start; refresh in the app to pick those up.
- The settings screen says so under the Type row: "App slideshow only. Screensaver shows 500 random photos."
- **Layout:** the Layout setting applies to the screensaver too; with Type other than Photos it applies to the 500 random photos it shows.
- **Idle activation:** The app is the system screensaver and starts on idle. On this TV, selection uses one-time `adb` setup; no separate TCL Auto Launch control was found or needed for observed idle activation (see [device testing](DEVICE_TESTING.md#setup-sequence)). Recheck after firmware updates.

## When things go wrong

When the SMB source is unreachable, the slideshow keeps retrying with backoff for as long as it runs. After three consecutive failures it shows three stored public-domain artworks with the caption "SMB failure. Loading…" instead of the date/place label, and returns to the SMB photos as soon as a read succeeds. A share that answers but refuses or lacks every one of the 8 photos sampled at start (seen while the clone was refreshing) is treated the same way: retry, then stored artworks. A configuration error (Guest rejected, signing required, wrong share) shows an error message instead and does not retry. Photos are removed from the session pool only for per-photo errors, never for network errors.

## First build acceptance criteria

- App appears in the TV launcher and every control is usable with a D-pad and Select/Back.
- Local sample images display without stretching; landscape and portrait photos are handled gracefully.
- Interval survives app restart and the photo changes at the selected interval.
- Slideshow can run both from a launcher preview and from the `DreamService`.
- The dream stops timers and image work when dismissed.
- Actual TCL TV behavior is recorded: third-party dream selection uses `adb`, and the app started on idle at the default timeout without a separate Auto Launch setting. Recheck after firmware updates.

## Source expectations

- Mac mini Photos library: the TV reads files from the library package over SMB, read-only, with an explicit Guest access mode (Guest is distinct from anonymous access). The Mac mini's SMB server is configured not to require signing so that Guest sessions work; the user accepted unsigned sessions because the share is read-only and LAN-only. In **All** mode the app samples up to 1,000 supported items from the full tree per run and shuffles the selected pool. `originals` also contains hidden and recently deleted photos, and its files do not include edits made in Photos (see [what originals contains](PHOTO_SOURCES.md#what-originals-contains)). Album selection covers regular user-created albums through a metadata-only catalog that points to existing files. Shared Albums are not supported yet. Package-internal directories are not albums.
- Other sources, including Google Photos, are not supported; see [why only Apple Photos](PHOTO_SOURCES.md#why-only-apple-photos).
- A source must clearly show when it is unavailable or needs attention. Avoid a silent empty slideshow.

## Product decisions

- Default SMB source is `Photos Library.photoslibrary/originals`. The user accepts that it can include hidden and recently deleted photos, and does not want a separate warning or opt-in. Originals are unedited. The current album-selection plan uses an index to point at existing originals rather than exported photo copies.
- **Decision (2026-10-13): make photo originals readable by Guest at the storage layer.** Many originals in the live library are owner-only (0600), and Carbon Copy Cloner copies those modes to the shared clone, where SMB Guest is denied. Using `tools/smb_access_probe/make_photos_readable.sh`, the user added group/other read to supported photo files (jpg, jpeg, png, webp, heic, heif) below the live library's `originals`; nothing else in the package is touched. The next CCC run propagated the change. The user runs the permission script manually once a day before the daily clone, so new photos become readable on the next refresh; automation beyond this routine is deferred. Accepted consequence: any account on the Mac mini, and any device on the LAN that can reach the Guest share, can read every photo, including hidden and recently deleted ones. Alternatives that were considered and not chosen: a Samba `force user` share, a Mac-side photo service running as the owner, or an export folder.
- **Decision (2026-10-17): extend the readable-by-Guest change to videos.** At the user's request, `tools/smb_access_probe/make_photos_readable.sh` now also adds group/other read to video originals below the live library's `originals` (mov, mp4, m4v, 3gp, 3g2, avi, mpg, mpeg, mts, m2ts), including the short motion clips Photos keeps beside Live Photos. Reason: the media-type feature needs the TV to play videos, and in a 28-video sample only 8 were readable by Guest (every clip above 18.4 Mbps was owner-only; see [photo sources](../journal/2026-10-17-1830-video-permissions.md)). The routine is unchanged: run the script on the live library, then the daily CCC clone. Accepted consequence, as for photos: any account on the Mac mini and any device on the LAN that can reach the Guest share can read every video, including recently deleted ones. `INCLUDE_VIDEOS=0` restores photos-only runs, and `revert` undoes every recorded path.
- **Decision (2026-10-17): the screensaver does not play videos.** Videos are for the in-app slideshow only. When the in-app Type setting includes videos, the DreamService shows 500 random photos from the library instead (details in [Screensaver](#screensaver)).
- **Decision (2026-10-18): the slideshow never shows screenshots, documents, or receipts.** Applies to the in-app slideshow and the screensaver, in *All* and *Choose Albums*, as Photos classified them: the screenshot flag, document confidence of 0.4 or more, or receipt confidence of 0.19 or more (9.4% of the library on the 2026-10-06 snapshot). Photos not yet analyzed by Photos, and photos newer than the TV catalog, are not filtered until the catalog is refreshed; with no usable catalog nothing is filtered. ID cards and real documents scored below 0.4 can still appear, and a few ordinary photos at 0.4 to 0.6 are hidden. Evidence and thresholds: [photo sources](../journal/2026-10-18-1219-excluding-screenshots-documents-receipts.md).
- **Decision (2026-10-18): portrait pairs.** A **Layout** setting, **Single** (default) or **Portrait pairs**, applies to the in-app slideshow and the screensaver. In Portrait pairs two portrait photos can share one slide, side by side at full height and never cropped, over a pinstripe, diamond lattice, walnut frame, or brass fillet mat background (never the same style twice in a row); landscape photos and videos stay as they are and a lone portrait stays on black. About 60% of portraits are paired (a constant, not a setting). A pair stays up for one Interval. Needs catalog version 5. Details in [portrait pairs](#portrait-pairs).
  - **The free space is split into three equal slices** (left, middle, right) holding a pattern or frame, instead of black.
  - **Styles:** pinstripes and diamond lattice are kept; the walnut frame and brass fillet mat were added as frame-like styles and the frame ideas were approved.
  - **Randomness:** not every portrait is paired, and the look of each pair is random (style, variant), never the same style twice in a row.
  - **Pairing frequency is a constant, `PAIR_PROBABILITY` 0.6**, not a setting in v1.
  - **A lone portrait stays on black**, as today; only pairs get a pattern or frame.
  - **The numbered "decisions to confirm" (16 px minimum slice, one Interval per pair, square-ish photos not paired, minimum aspect 0.56, per-photo labels, applies to the screensaver, newer photos shown alone, pattern always on, styles and tones) were resolved on 2026-10-18 by adopting the recommended defaults.** The "decision N" references in the spec above point to the list in the the [journal](../journal/2026-10-18-1355-portrait-pairs-decisions-to-confirm.md).
- Album selection covers regular user-created albums. Use a read-only metadata index; do not present package-internal directories as albums. Shared Albums created by the user or shared with the user are a later feature, contingent on verifying their storage mapping.
- Catalog cache decision: use the Photos database album UUID (`ZUUID`) as the opaque ID and build a versioned JSON catalog in persistent app-private TV storage from a locally staged read-only SQLite snapshot. The user can explicitly refresh it; cache loss triggers a rebuild. Full clone mapping validation is recorded in [photo-source findings](../journal/2026-10-17-1455-regular-album-cache-validation.md). This private Photos schema can change. CCC UUID continuity across a refresh has been verified (user-confirmed 2026-10-19).
- The daily CCC clone is complete relative to the live library; full content equality has been verified (user-confirmed 2026-10-19). Photos is set to "Download Originals to this Mac".

## Open questions

- The TV is a TCL 65" A300 (NXTFRAME) on Android TV 12 (see [known TV facts](DEVICE_TESTING.md#known-tv-facts)). Current firmware testing found that TCL Art Mode did not interrupt a manually started artwork or the app DreamService. Recheck after firmware updates.
- Whether display images should be cached on the TV for offline playback, and for how long.
- Real video throughput variation over a long session (6–10 MB/s observed) and the right safe-start margin; tune with logs if stalls recur. A multi-hour soak has not been done (a 60-minute soak was clean).
- A few black screens with a date caption were seen during the soak. Not tied to Motion JPEG or MPEG-4 (both render normally); the cause is unknown, so check the log for a `Video finished` line with an unusually short or long time if it recurs.
- Assumption to confirm: "random 500 photos" means the whole library, so a saved album selection is also ignored while Type includes videos.

Earlier open questions that were closed are in the [journal](../journal/2026-10-17-2012-media-type-open-questions.md).

### Awaiting explicit confirmation

The recommended defaults below are implemented. The user has not yet explicitly confirmed them.

1. **Cap of 60 s per clip** (alternative: the Interval setting, or a user choice).
2. **Videos always muted** in v1.
3. **Proportional mix** of videos when Media is *All* (about 9%), not a user-set share.
4. **Skip only clips that would need a start wait over 60 s** (3 clips on the conservative link), not a fixed bitrate cut.
5. **Free-space guard of 6 GiB** and a per-clip temp file (up to about 4 GB) in the app cache.

## Out of scope

- Not in v1: audio, seeking or any playback controls, Live Photo motion, Shared Albums, multi-image layouts other than [portrait pairs](#portrait-pairs), HDR-specific handling, transitions between slides.
- Not built: four-up tiles (cancelled), pairing landscape or square photos, cropping to fill, pairing a photo with a video, transitions, a user-set gutter.
- Four-up tiles, pairing landscapes or square photos, user-controlled frequency, and avoiding same-moment pairs remain out of scope. The catalog has no burst information.
- A wider check of the HEIF offset fix, a network-failure check of pairs, and a smaller second decode for 24 MP pairs were waived by the user after review; revisit only if an issue appears.
