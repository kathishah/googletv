# Video playback (design)

Product behavior (the Type setting, the picker, playback rules, the screensaver fallback, and errors) is in [product](../PRODUCT.md#videos). This document is the design: the evidence, the catalog data, progressive download, permissions, and player notes.

## Evidence this design rests on

All measured on this library and the TCL (see the linked sections).

- **Library** ([survey](../../journal/2026-10-17-1813-video-survey.md)): 3,354 active videos next to 34,526 photos; 73% `.mov`, 26% `.mp4`; median 32 s, 90th percentile 110 s; 63% are 1080p-class; 1,894 are in 378 regular albums. The survey used a database snapshot from the last checkpoint, so recent videos are missing.
- **Decode** ([TV survey](../../journal/2026-10-17-1813-video-decode-survey.md)): all 21 clips, one per distinct format found, decode on hardware (H.264, HEVC, Dolby Vision HLG up to 4K, Motion JPEG, MPEG-4, 120/240 fps, rotation).
- **Playback** ([SMB test](../../journal/2026-10-17-1830-video-playback-over-smb.md)): typical clips start in 1.5–2.6 s and play with no stalls; rotation is applied by the player; the view must be resized to the clip's aspect ratio. From local storage even the 71 and 89 Mbps clips play in real time, so the only limit is SMB throughput.
- **Link:** a single sequential reader sustains 6–10 MB/s (about 56–74 Mbps). Parallel reads were slower, not faster.
- **Bitrate model:** by file size over duration, 97% of clips are under 20 Mbps. With a reader that stays ahead of playback and a safe-start wait, 99.4% of clips need no wait at all on the conservative 56 Mbps link, 15 need 5 s or less, 3 need 5–20 s, and 3 need 20–60 s. No clip needs more than 60 s. This is a model from average bitrate, not yet a measurement of the final reader.
- **Permissions:** `make_photos_readable.sh` now covers videos, and the heavy clips were readable on the clone afterwards. Before that, 20 of 28 sampled videos were owner-only.

## Data

### Catalog

Videos are part of the shared catalog: each album has `photoCount`, `videoCount` and `videos` (`p` relative path, `d` duration in seconds, `s` size in bytes), and the top level has a `videos` list of every active, non-hidden video, so Media *All* can include videos without the album tables. A video needs an allowlisted extension (mov, mp4, m4v, 3gp, 3g2, avi, mpg, mpeg, mts, m2ts), a safe path under `originals`, and a positive duration and size. Shared Album copies (`ZSAVEDASSETTYPE` 4) are left out because their files are not in `originals`. The full schema and version history are in [album selection](ALBUM_CATALOG.md#tv-cached-regular-album-index-contract); how videos were added to it, with measurements, is in the [catalog v2 and v3 build notes](../../journal/2026-10-17-2012-media-type-catalog-v2.md).

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

- Videos must be readable by Guest: done by `make_photos_readable.sh` ([decision](../PRODUCT.md)); keep the daily routine (script on the live library, then the CCC clone). New videos are owner-only until the script runs.
- **Risk:** Guest was denied across the whole share twice on 2026-10-17 after CCC activity and recovered after listing the library as another user on the Mac mini ([journal](../../journal/2026-10-17-1455-device-findings-log.md)). Cause unknown. During a denial the screensaver shows stored artwork, as it already does; videos never run there. A CCC after-copy hook that touches the library as another user is a candidate fix if it recurs.
- Videos are now world-readable on the LAN, including recently deleted ones. Accepted, recorded in `PRODUCT.md`.

## Player and reader notes

- **Low buffer (implemented):** `VideoSurfaceStage` checks the buffer every 250 ms. It pauses the player when less than 1 s of video is buffered ahead, resumes at 4 s ahead (or at 2 s when the rest of the download is expected to finish in time), never pauses once the whole file is local, and abandons a paused clip after 10 s with no new data. The clip also ends at 60 s.
- Implementation: `PhotoSourceFactory.create` takes a `forDream` flag; when it is true and Type is not Photos it returns the *All* source with a pool of 500. The in-app slideshow passes false. This rule is the only change to the screensaver path.
- The shared catalog JSON parser still reads video metadata.
- **Poster covers.** `VideoPoster` opens the clip over its own connection, requests the end and first media blocks through the progressive reader, and waits for its minimum head/tail readiness gate before taking the frame near one second with `MediaMetadataRetriever`. The reader continues fetching until closed; closing it stops the rest of the download. The poster is scaled to 480 by 300, and up to four clips are tried, lightest first among the album's first eight. Posters are cached in the persistent thumbnail cache under their own key; photo cover cache keys are unchanged.
