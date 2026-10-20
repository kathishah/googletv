# Media-type selection: photos, videos, or both

Status: **implemented and exercised on the TCL TV (2026-10-19).** All seven implementation milestones are complete; see the [plan](#implementation-plan) and [device findings](DEVICE_TESTING.md#device-findings). The DreamService video fallback, video playback, poster covers, failure recovery and soak results are recorded below.

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
- **Type Videos lists only albums that have videos** (373 of 759 on this library); the subtitle says "Showing only albums with videos". Albums hidden by the filter keep any selection they had, so it is still there for the other Types: a note says how many selected albums are hidden, **Select all** selects only the albums shown, and **Clear all** clears everything. The bottom total counts the shown selected albums. If no album has videos the picker says so.
- With Type Photos + Videos an album with nothing eligible stays selectable, is dimmed, and reads "No pictures or videos"; with Type Photos the card is unchanged ("0 pictures"). Selected albums with nothing eligible add a warning under the controls, consistent with the existing rule that an empty album is explained, not hidden, outside the Videos filter.
- The bottom rail total reads "N albums · X items" (or "X pictures" with Type *Photos*, unchanged). Selection saves immediately and survives Type changes.
- Covers: unchanged for photo albums. With Type *Videos*, or for an album that has only videos while videos are in scope, the cover is a poster frame. `VideoPoster` opens the clip over its own connection, requests the end and first media blocks through the progressive reader, and waits for its minimum head/tail readiness gate before taking the frame near one second with `MediaMetadataRetriever`. The reader continues fetching until closed; closing it stops the rest of the download. The poster is scaled to 480 by 300, and up to four clips are tried, lightest first among the album's first eight. Posters are cached in the persistent thumbnail cache under their own key; photo cover cache keys are unchanged.

### Screensaver

- The DreamService does not play or prepare video files and never starts a `MediaPlayer`. The shared catalog JSON parser still reads video metadata.
- **Type = Photos:** unchanged. The screensaver honors Media (*All* or *Choose Albums*), the saved albums, Pool, Interval, and Label.
- **Type = Photos + Videos or Videos:** the screensaver ignores Media, the saved albums, Type, and Pool, and shows a random pool of **500 photos** drawn from the whole library (the same sampling as *All*). Interval and Label still apply. The saved albums and Type stay saved for the in-app slideshow.
- **Catalog:** the screensaver uses the catalog last refreshed from the app. If that catalog is missing or its format is no longer supported, the screensaver asks you to refresh the album catalog in the app instead of building it during idle startup. It does not check the Mac for newer Photos changes at dream start; refresh in the app to pick those up.
- The settings screen says so under the Type row: "App slideshow only. Screensaver shows 500 random photos."
- Implementation: `PhotoSourceFactory.create` takes a `forDream` flag; when it is true and Type is not Photos it returns the *All* source with a pool of 500. The in-app slideshow passes false. This rule is the only change to the screensaver path.
- Assumption to confirm: "random 500 photos" means the whole library, so a saved album selection is also ignored while Type includes videos.

### Playback

- **Dwell:** photos use the Interval setting. A video plays to its end, capped at **60 s**, whatever the Interval is (76% of clips are 60 s or shorter). A longer clip plays its first 60 s and moves on.
- **Look:** letterboxed (never cropped, like photos), black bars, muted, no controls. A clip is enlarged to fit but by at most 2× its own pixel size, so small old clips are not stretched into blur (a 640-wide clip plays 1280 px wide; a 160×90 clip plays as a small centered window). The view is resized to the clip's displayed size on every size change, so rotated portrait clips are upright and correctly proportioned.
- **Labels:** photos keep location and date from EXIF. For videos the date and, when present, location come from the container metadata (`MediaMetadataRetriever`, read from the same progressive source once the clip is ready). If a clip has neither, no label. **Measured:** of 9 clips, 7 had a date only, 1 had place and date, and 1 had none, so dates are reliable and locations are rare. The label is built by `VideoLabel`, with the same format as photos ("place · Jun 12, 2022", coordinates when no place name is found).
- **Transitions:** none. The previous slide stays up until the next one is ready (first video frame rendered, or photo decoded), then it switches.
- **Order:** one shuffled sequence. With Media *All* and videos included, the pool is drawn so videos appear in proportion to their share of the library (about 9%); Type *Videos* is all videos. With Media *Choose Albums*, all eligible items from the selected albums are shuffled together, as today.

### States and errors

- **Preparing:** while a video's safe-start wait runs (usually under 5 s), the previous slide stays on screen. If the wait would exceed 60 s the clip is skipped for this pass and the log records a count, never a name.
- **Low buffer (implemented):** `VideoSurfaceStage` checks the buffer every 250 ms. It pauses the player when less than 1 s of video is buffered ahead, resumes at 4 s ahead (or at 2 s when the rest of the download is expected to finish in time), never pauses once the whole file is local, and abandons a paused clip after 10 s with no new data. The clip also ends at 60 s.
- **Clip fails** (cannot open, no decoder, read error, stall longer than 10 s): advance at once to the next slide. An unplayable or missing clip is dropped from the pool; a clip with a transient reader or buffer failure goes to the back to be tried again. Three consecutive counted failures (video failures and retryable photo/share failures) use the existing stored-artwork fallback and its caption; ordinary per-file photo skips are not counted. A video counts as successful only when it reaches its end or the 60 s cap. The first frame alone does not reset the failure streak. A successfully shown photo also resets it. After a clip finishes, the next photo is preloaded as soon as the whole file is local.
- **Share unavailable** or access denied: existing behavior (retry with backoff, then stored artwork).
- **Unsupported container or codec:** counted as a failure; the format family is logged without file names.

## Data

### Catalog v2

`PhotosAlbumCatalog.VERSION` becomes 2, so existing catalogs are rebuilt on the next refresh (an old catalog never survives the unchanged shortcut). Changes:

**Implemented (milestone 1, 2026-10-17).** The layout is leaner than first drafted:

- Each album keeps `paths` (its photos, exactly as in v1) and adds `photoCount`, `videoCount`, and `videos`, a list of `{p: relative path, d: duration seconds, s: size bytes}`.
- Top level adds `videos`: every active, non-hidden video in the library in the same shape, so Media *All* can include videos without the album tables.
- Two queries. The library query reads `ZASSET` rows with `ZKIND=1`, not trashed and not hidden, joined to `ZADDITIONALASSETATTRIBUTES.ZORIGINALFILESIZE` (about 3,400 lookups). The album query is the v1 query unchanged. An album's videos are resolved against the library list when the catalog is built, so the album query never needs duration or size. Joining the attributes table on every membership row (38,000) was tried first and made the rebuild 79 s instead of 51 s.
- A video needs `ZKIND=1`, an extension in the allowlist (mov, mp4, m4v, 3gp, 3g2, avi, mpg, mpeg, mts, m2ts), a safe path relative to `originals`, and a positive duration and size. Live Photo motion clips are not separate asset rows, so they do not appear.
- **Version 3 (2026-10-17):** the library video query now leaves out shared-album copies (`ZSAVEDASSETTYPE` 4). On the TV, 18% of version 2's 3,258 videos were missing from the share, all under one long numeric folder (`<id>/<album GUID>`), because those files are iCloud Shared Album copies that are not in `originals`. Version 3 lists 2,667 videos, all in the 16 normal `originals` folders, and a 200-path sample showed none missing; album video memberships are unchanged (3,862). `PhotosAlbumCatalog.VERSION` was bumped, so the rebuild happens on the next picker open or refresh (45.8 s).
- Videos that are dropped: on this library, 65 are stored outside `originals` (their directory is an absolute path, the same case as the 1,116 out-of-folder photo references) and 35 have no recorded file size.
- The file is 2.25 MB (v1 was 1.66 MB), a catalog read takes about 0.9 s in the picker and 2.3 s during a refresh, and `sourceModifiedAt`, the unchanged shortcut, and incremental sync are unchanged. A v1 file is rejected and rebuilt on the next picker open or refresh.

### Counts and the bitrate rule

Bitrate for scheduling is `s * 8 / d`. Items without usable `d` and `s` are not played.

## Reading videos: progressive download

**Implemented (milestone 3, 2026-10-17).** `ProgressiveReader` (plain Kotlin, JVM-tested) does the work; `SmbRemoteVideo` is its per-clip SMB connection and `ProgressiveMediaDataSource` hands it to `MediaPlayer`.

1. **Open** the file read-only over its own Guest connection, one per clip. Aborting a clip closes that connection from another thread; reader threads are never interrupted, because interrupting an SMB read breaks its connection.
2. **Fetch order:** the last 4 MiB block first (iPhone `.mov` files keep their index, `moov`, at the end), then the first block, then forward. A block the player asks for out of order jumps the queue.
3. **One sequential reader thread** writes 4 MiB blocks into a sparse temp file under `cacheDir/video-cache` and tracks which blocks are present. `readAt` serves present blocks from the local file and waits up to 10 s for missing ones, then fails the clip.
4. **Safe-start gate** (`awaitReadyToPlay`): playback may start once `(size − buffered) / (R × 0.85) ≤ duration` and at least min(8 MiB, 2 s of video) is buffered. It returns `Ready`, `TooSlow` (the start wait would exceed 60 s, decided as soon as the rate is known, and never reported as Ready after a wait longer than 60 s), `Failed`, or `Cancelled`.
5. **Planning rate `R`:** the smoothed *effective* rate of this clip (each block's read **and** write), assumed 56 Mbps until measured, capped by `LinkRateMemory`, which remembers the overall pace of the last three clips (a clip must have moved 16 MiB to count) and reports the slowest. A fast start is therefore not taken at face value after a slow clip.
6. **Cleanup:** `close()` stops the reader, aborts the connection, and deletes the temp file; `purgeStale` removes leftovers from a crash. `start()` creates the folder first, then refuses when free space after the file would be under 6 GiB (`InsufficientStorageException`).
7. **Bandwidth:** the next clip is not fetched while the current one is downloading.

**Known limit.** The gate cannot know the future link. On the first heavy run (no memory yet) the early blocks arrived at 9 MB/s, the gate opened, and then the link fell to about 5 MB/s: the 89 Mbps clip froze for a total of 17.5 s. With the memory, later runs of the same clip waited 33–45 s and then played with no stall. A player-level safeguard (pause when the buffer ahead runs low, resume when it recovers, abandon after the 10 s read timeout) belongs to milestone 4, which owns the `MediaPlayer`.

Why this and not the alternatives: on-demand reads (the test reader) ran the 89 Mbps clip at about half speed; a separate download-then-play mode would need a second mechanism for the 1.5% of heavy clips; parallel read-ahead was slower than one reader.

## Permissions and operations

- Videos must be readable by Guest: done by `make_photos_readable.sh` ([decision](PRODUCT.md)); keep the daily routine (script on the live library, then the CCC clone). New videos are owner-only until the script runs.
- **Risk:** Guest was denied across the whole share twice on 2026-10-17 after CCC activity and recovered after listing the library as another user on the Mac mini ([device testing](DEVICE_TESTING.md)). Cause unknown. During a denial the screensaver shows stored artwork, as it already does; videos never run there. A CCC after-copy hook that touches the library as another user is a candidate fix if it recurs.
- Videos are now world-readable on the LAN, including recently deleted ones. Accepted, recorded in `PRODUCT.md`.

## Implementation plan

Each milestone ends with its exit check on the TCL before the next starts. Commands and results go to [device testing](DEVICE_TESTING.md).

| # | Milestone | Main work | Exit check |
| --- | --- | --- | --- |
| 1 | Catalog v2 and counts (**done 2026-10-17**) | Query and JSON v2 (`videos`, counts, bump `VERSION`); keep the unchanged shortcut safe against v1 files; JVM tests for read/write, v1 rejection, path safety | Refresh builds v2 on the TV; photo counts match v1; video counts match the survey within the snapshot lag; cached read stays under 3 s. **Met:** see [device testing](DEVICE_TESTING.md#catalog-v2-milestone-1-2026-10-17) |
| 2 | Settings and picker (**done 2026-10-17**) | `media_type` preference, Type row (and the Photos row renamed Media), summary text, card counts and badges per mode, empty-state rules, and the screensaver fallback (pulled forward from milestone 5 because it is a small factory rule); photos-only path unchanged | All settings and picker states in the mock work with the D-pad; the Photos type is today's behavior. **Met:** see [device testing](DEVICE_TESTING.md#settings-and-picker-milestone-2-2026-10-17). The slide model gaining a kind moves to milestone 4, where video slides first exist |
| 3 | Progressive data source (**done 2026-10-17**) | `ProgressiveReader` with `SmbRemoteVideo` and `ProgressiveMediaDataSource`, gate math, rate memory, cache cleanup; JVM tests with an in-memory share (tail-first, ordering, gate maths, abandonment, cleanup) | On the TV: typical clip starts in under 3 s with no stall; 4K HLG and the 89 Mbps clip start within 5 s of the model and finish with at most one pause; temp files gone afterwards. **Met, with one caveat:** see [device testing](DEVICE_TESTING.md#progressive-reader-milestone-3-2026-10-17) |
| 4 | Playback in the preview (**done 2026-10-17**) | Video surface in `SlideshowView` sized to the clip, `MediaPlayer` lifecycle, the low-buffer pause and resume safeguard, errors and skip rules, labels from container metadata | Portrait, landscape, rotated and HDR clips upright and correctly proportioned (user check); 20 consecutive clips with stable memory; failure and skip paths exercised. **Met:** see [device testing](DEVICE_TESTING.md#video-playback-in-the-in-app-slideshow-milestone-4-2026-10-17) |
| 5 | Screensaver fallback (**done 2026-10-18**) | `forDream` flag in `PhotoSourceFactory`; the dream path returns the 500-photo *All* source when Type is not Photos; the settings note under Type | With Type = Photos + Videos and Type = Videos, idle activation shows 500 random photos and ignores saved albums while honoring Interval and Label; with Type = Photos behavior is unchanged (albums respected); the dream never touches video code or the video list. **Met:** the real idle-timeout activation was observed; see [device testing](DEVICE_TESTING.md#screensaver-idle-activation-milestone-5-2026-10-18) |
| 6 | Videos type and covers (**done 2026-10-17**) | Type *Videos*, Media *All* with proportional videos (built in milestone 4), poster-frame covers | Each Type in the in-app slideshow; covers cached and persistent. **Met:** see [device testing](DEVICE_TESTING.md#poster-covers-milestone-6-2026-10-17) |
| 7 | Hardening (**done 2026-10-18**) | Recorded logs per clip (phase timings, no names), a 60-minute soak, album-start and low-storage fixes, a cap on how far small clips are enlarged, docs | Docs and roadmap updated; open questions closed or recorded. **Met:** see [device testing](DEVICE_TESTING.md#hardening-milestone-7-2026-10-18) |

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

- Closed: dates come through `MediaMetadataRetriever` for most clips and locations rarely (7 of 9 had a date only); see Labels.
- Closed: HEVC Main 10 clips played in the 60-minute soak with no reported color or brightness problem. Dolby Vision specifically was not singled out.
- Real throughput variation over a long session (6–10 MB/s observed) and the right safe-start margin; tune with logs in milestone 3.
- Closed for a 60-minute soak (101 clips, memory 80–101 MB, 0 pauses); a soak of several hours is still not done.
- Closed: album-mode previews no longer open every selected photo; a sample of 8 is checked (milestone 7).
- Closed: the TV's idle timers did not interrupt the in-app slideshow in 15-, 30-, and 60-minute runs.
- Open: a few black screens with a date caption were seen during the soak. Not tied to Motion JPEG or MPEG-4 (both render normally); the cause is unknown, so check the log for a `Video finished` line with an unusually short or long time if it recurs.
