# Media-type selection: photos, videos, or both

Status: **draft spec, 2026-10-17. Not implemented.** Roadmap item 9. Mock: [media-type.html](mockups/media-type.html).

## Goal

Let the user choose what the **in-app slideshow** shows: **photos only** (today's behavior, still the default), **photos and videos**, or **videos only**. The choice applies to both Media choices (*All* and *Choose Albums*). Videos play muted, letterboxed, in the same shuffled sequence as photos.

**The screensaver (DreamService) never plays videos** (decided by the user, 2026-10-17). When Type is anything other than Photos, the screensaver falls back to 500 random photos from the library; see [Screensaver](#screensaver).

Not in v1: audio, seeking or any playback controls, Live Photo motion, Shared Albums, multi-image layouts ([roadmap item 10](ROADMAP.md)), HDR-specific handling, transitions between slides.

## Evidence this spec rests on

All measured on this library and the TCL (see the linked sections).

- **Library** ([survey](PHOTO_SOURCES.md#video-survey-2026-10-17)): 3,354 active videos next to 34,526 photos; 73% `.mov`, 26% `.mp4`; median 32 s, 90th percentile 110 s; 63% are 1080p-class; 1,894 are in 378 regular albums. The survey used a database snapshot from the last checkpoint, so recent videos are missing.
- **Decode** ([TV survey](DEVICE_TESTING.md#video-decode-survey-2026-10-17)): all 21 clips, one per distinct format found, decode on hardware (H.264, HEVC, Dolby Vision HLG up to 4K, Motion JPEG, MPEG-4, 120/240 fps, rotation).
- **Playback** ([SMB test](DEVICE_TESTING.md#video-playback-over-smb-2026-10-17)): typical clips start in 1.5–2.6 s and play with no stalls; rotation is applied by the player; the view must be resized to the clip's aspect ratio. From local storage even the 71 and 89 Mbps clips play in real time, so the only limit is SMB throughput.
- **Link:** a single sequential reader sustains 6–10 MB/s (about 56–74 Mbps). Parallel reads were slower, not faster.
- **Bitrate model:** by file size over duration, 97% of clips are under 20 Mbps. With a reader that stays ahead of playback and a safe-start wait, 99.4% of clips need no wait at all on the conservative 56 Mbps link, 15 need 5 s or less, 3 need 5–20 s, and 3 need 20–60 s. No clip needs more than 60 s. This is a model from average bitrate, not yet a measurement of the final reader.
- **Permissions:** `make_photos_readable.sh` now covers videos, and the heavy clips were readable on the clone afterwards. Before that, 20 of 28 sampled videos were owner-only.

## User experience

### Settings

The settings table changes in two rows:

| Row | Choices | Change |
| --- | --- | --- |
| Media | **All**, Choose Albums | The existing **Photos** row, renamed. Choices and behavior are unchanged; the stored preference stays `photo_selection_mode`. |
| Type | **Photos** (default), Photos + Videos, Videos | New row, directly below **Media** and above **Pool**. |

- **Type** is persisted as `media_type` (`PHOTOS`, `PHOTOS_AND_VIDEOS`, `VIDEOS`). A missing or unknown value is `PHOTOS`. It is independent of the saved album IDs and of the **Media** choice (*All* versus *Choose Albums*), like the existing selection mode.
- Choosing a mode that includes videos needs the catalog (it holds the video list). If no valid catalog exists, the same in-screen loading and refresh flow as album selection opens, with the same actionable error and retry if it fails; the saved mode is not changed until the catalog is ready.
- **Pool** keeps its meaning (the number of items drawn when Media is *All*) and now counts photos and videos together. It stays disabled when Media is *Choose Albums*. It applies to the in-app slideshow only; the screensaver fallback below always uses 500.
- **Label** applies to videos as far as metadata allows (below).
- **Interval** applies to photos only. A video stays on screen for its own length (below). The settings screen says so under the Interval row ("Videos play to the end, up to 60 s").

### Album picker

- Each card shows what the current **Type** would play: Type *Photos* shows "N photos" as today; *Photos + videos* shows "N photos · M videos"; *Videos* shows "M videos". A video badge appears on cards when videos are in scope.
- An album with nothing eligible in the current mode stays selectable and is marked "No videos" (or "No photos"), consistent with the existing rule that an empty album is explained, not hidden.
- The bottom rail total reads "N albums · X items" (or "X pictures" with Type *Photos*, unchanged). Selection saves immediately and survives Type changes.
- Covers: unchanged for photo albums. With Type *Videos*, or for an album that has only videos, the cover is a poster frame taken from the first readable video (`MediaMetadataRetriever.getFrameAtTime` through the same SMB data source) and cached in the existing persistent thumbnail cache.

### Screensaver

- The DreamService does not play videos, does not read the video list, and never starts a `MediaPlayer`.
- **Type = Photos:** unchanged. The screensaver honors Media (*All* or *Choose Albums*), the saved albums, Pool, Interval, and Label.
- **Type = Photos + Videos or Videos:** the screensaver ignores Media, the saved albums, Type, and Pool, and shows a random pool of **500 photos** drawn from the whole library (the same sampling as *All*). Interval and Label still apply. The saved albums and Type stay saved for the in-app slideshow.
- The settings screen says so under the Type row: "App slideshow only. Screensaver shows 500 random photos."
- Implementation: `PhotoSourceFactory.create` takes a `forDream` flag; when it is true and Type is not Photos it returns the *All* source with a pool of 500. The in-app slideshow passes false. This rule is the only change to the screensaver path.
- Assumption to confirm: "random 500 photos" means the whole library, so a saved album selection is also ignored while Type includes videos.

### Playback

- **Dwell:** photos use the Interval setting. A video plays to its end, capped at **60 s**, whatever the Interval is (76% of clips are 60 s or shorter). A longer clip plays its first 60 s and moves on.
- **Look:** letterboxed (never cropped, like photos), black bars, muted, no controls. The view is resized to the clip's displayed size on every size change, so rotated portrait clips are upright and correctly proportioned.
- **Labels:** photos keep location and date from EXIF. For videos the date and, when present, location come from the container metadata (`MediaMetadataRetriever`). If a clip has neither, no label. To be verified on the TV (open question below).
- **Transitions:** none. The previous slide stays up until the next one is ready (first video frame rendered, or photo decoded), then it switches.
- **Order:** one shuffled sequence. With Media *All* and videos included, the pool is drawn so videos appear in proportion to their share of the library (about 9%); Type *Videos* is all videos. With Media *Choose Albums*, all eligible items from the selected albums are shuffled together, as today.

### States and errors

- **Preparing:** while a video's safe-start wait runs (usually under 5 s), the previous slide stays on screen. If the wait would exceed 60 s the clip is skipped for this pass and the log records a count, never a name.
- **Clip fails** (cannot open, no decoder, read error, stall longer than 10 s): advance at once to the next slide. Three consecutive failures of any kind use the existing stored-artwork fallback and its caption.
- **Share unavailable** or access denied: existing behavior (retry with backoff, then stored artwork).
- **Unsupported container or codec:** counted as a failure; the format family is logged without file names.

## Data

### Catalog v2

`PhotosAlbumCatalog.VERSION` becomes 2, so existing catalogs are rebuilt on the next refresh (an old catalog never survives the unchanged shortcut). Changes:

- Each album: `photoCount`, `videoCount`, and `items`, objects `{p: relative path, k: 0 photo | 1 video, d: duration seconds (video), s: size bytes (video)}` replacing the plain `paths` list.
- Top-level `videos`: every active, non-hidden video in the library as the same item objects, so Media *All* can include videos without the album tables.
- The query reads `ZKIND` 0 and 1, joins `ZADDITIONALASSETATTRIBUTES.ZORIGINALFILESIZE` and `ZASSET.ZDURATION`, and keeps video extensions from an allowlist: mov, mp4, m4v, 3gp, 3g2, avi, mpg, mpeg, mts, m2ts. Live Photo motion clips are not separate asset rows, so they do not appear as videos. Trashed and hidden assets are excluded. Rows missing a duration or size are dropped.
- Size estimate: about +0.25 MB for 3,300 videos on top of 1.66 MB. Read time stays dominated by JSON parsing (about 0.8–2 s measured today).
- `sourceModifiedAt`, the unchanged shortcut, and incremental sync are unaffected.

### Counts and the bitrate rule

Bitrate for scheduling is `s * 8 / d`. Items without usable `d` and `s` are not played.

## Reading videos: progressive download

One component, `ProgressiveSmbDataSource` (a `MediaDataSource`), used for every clip.

1. **Open** the file read-only over the existing Guest connection (a connection per clip, never interrupted; see the test notes).
2. **Fetch the tail first** (last 4 MiB) and the head (first 4 MiB). iPhone `.mov` files keep their index (`moov`) at the end, and the player reads it before the media data.
3. **Read forward** in 4 MiB blocks on **one** sequential reader thread into a sparse temp file under the app cache, tracking which blocks are present. `readAt` serves present blocks from the local file and waits for missing ones (up to 10 s).
4. **Safe-start gate:** playback starts when the bytes buffered satisfy `(size − buffered) / (R × 0.85) ≤ D`, so the download is expected to finish before playback does, and at least min(8 MiB, 2 s of video) is buffered. `R` is the measured sequential rate (moving average over recent blocks), assumed 56 Mbps until measured. If reaching that point would take more than 60 s, skip.
5. **Underrun:** if the player starves mid-clip it pauses by itself; if no data arrives for 10 s the clip is abandoned.
6. **Cleanup:** the temp file is deleted when the clip ends, fails, or the player stops, and any leftovers are purged at start. The cache refuses to start when free space is under 6 GiB; the largest clip in the library is about 4 GB.
7. **Bandwidth:** the next clip is not fetched while the current one is downloading, because parallel reads halved throughput in the test. Photo preloading continues as today.

Why this and not the alternatives: on-demand reads (the test reader) ran the 89 Mbps clip at about half speed; a separate download-then-play mode would need a second mechanism for the 1.5% of heavy clips; parallel read-ahead was slower than one reader.

## Permissions and operations

- Videos must be readable by Guest: done by `make_photos_readable.sh` ([decision](PRODUCT.md)); keep the daily routine (script on the live library, then the CCC clone). New videos are owner-only until the script runs.
- **Risk:** Guest was denied across the whole share twice on 2026-10-17 after CCC activity and recovered after listing the library as another user on the Mac mini ([device testing](DEVICE_TESTING.md)). Cause unknown. During a denial the screensaver shows stored artwork, as it already does; videos never run there. A CCC after-copy hook that touches the library as another user is a candidate fix if it recurs.
- Videos are now world-readable on the LAN, including recently deleted ones. Accepted, recorded in `PRODUCT.md`.

## Implementation plan

Each milestone ends with its exit check on the TCL before the next starts. Commands and results go to [device testing](DEVICE_TESTING.md).

| # | Milestone | Main work | Exit check |
| --- | --- | --- | --- |
| 1 | Catalog v2 and counts | Query and JSON v2 (`items`, `videos`, counts, bump `VERSION`); keep the unchanged shortcut safe against v1 files; JVM tests for read/write, v1 rejection, path safety | Refresh builds v2 on the TV; photo counts match v1; video counts match the survey within the snapshot lag; cached read stays under 3 s |
| 2 | Settings and picker | `media_type` preference, Type row (and the Photos row renamed Media), summary text, card counts and badges per mode, empty-state rules; slide model gains a kind and the video fields; photos-only path unchanged | All settings and picker states in the mock work with the D-pad; Photos mode is pixel-for-pixel today's behavior |
| 3 | Progressive data source | `ProgressiveSmbDataSource`, gate math, cache cleanup; JVM tests with an in-memory share (tail-first, ordering, gate maths, abandonment, cleanup) | On the TV: typical clip starts in under 3 s with no stall; 4K HLG and the 89 Mbps clip start within 5 s of the model and finish with at most one pause; temp files gone afterwards |
| 4 | Playback in the preview | Video surface in `SlideshowView` sized to the clip, `MediaPlayer` lifecycle, errors and skip rules, labels from container metadata | Portrait, landscape, rotated and HDR clips upright and correctly proportioned (user check); 20 consecutive clips with stable memory; failure and skip paths exercised |
| 5 | Screensaver fallback | `forDream` flag in `PhotoSourceFactory`; the dream path returns the 500-photo *All* source when Type is not Photos; the settings note under Type | With Type = Photos + Videos and Type = Videos, idle activation shows 500 random photos and ignores saved albums while honoring Interval and Label; with Type = Photos behavior is unchanged (albums respected); the dream never touches video code or the video list |
| 6 | Videos type and covers | Type *Videos*, Media *All* with proportional videos, poster-frame covers | Each Type in the in-app slideshow; covers cached and persistent |
| 7 | Hardening | Recorded logs per clip (phase timings, no names), docs, optional extras below | Docs and roadmap updated; open questions closed or recorded |

Optional later: fetching the next clip's head during playback of a light clip, a rolling cache window for the few clips over the free-space limit, an audio toggle, HDR tuning.

## Decided

- **The screensaver never plays videos** (user, 2026-10-17). With Type other than Photos it shows 500 random photos from the library.

## Decisions to confirm

Recommended defaults are used above.

1. **Cap of 60 s per clip** (alternative: the Interval setting, or a user choice).
2. **Videos always muted** in v1.
3. **Proportional mix** of videos when Media is *All* (about 9%), not a user-set share.
4. **Skip only clips that would need a start wait over 60 s** (3 clips on the conservative link), not a fixed bitrate cut.
5. **Free-space guard of 6 GiB** and a per-clip temp file (up to about 4 GB) in the app cache.

## Open questions

- Do iPhone video containers carry usable location and date for the label through `MediaMetadataRetriever` on this TV? Check in milestone 4.
- Does Dolby Vision or HLG content look acceptable on this 100-nit panel in the in-app slideshow? Unmeasured.
- Real throughput variation over a long session (6–10 MB/s observed) and the right safe-start margin; tune with logs in milestone 3.
- Memory and heap behavior across many consecutive clips; soak in milestone 4.
- Whether the TV's idle timers (screen-off timeout, Art Mode) interrupt the in-app slideshow during long clips; the slideshow keeps the screen on while it runs, and the longest capped clip is 60 s.
