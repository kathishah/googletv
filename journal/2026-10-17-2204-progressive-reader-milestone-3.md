# Progressive reader, milestone 3 (2026-10-17)

Moved verbatim from `docs/DEVICE_TESTING.md` on 2026-10-19, with links updated for the new location.

Built and installed as before (78 JVM tests pass, 21 of them for `ProgressiveReader` and `LinkRateMemory`; lint 0 errors). The JVM tests use an in-memory share on a virtual clock (each fake read advances time by bytes over the link rate, so nothing sleeps) and cover fetch order, byte-exact `readAt`, out-of-order priority, the gate opening and staying closed (the fake link hands out one block at a time), skipping an impossible clip, abort and cleanup, timeouts, failures, free-space refusal, tiny files, and the rate memory. A deliberate mutation of the gate formula made two of those tests fail, which is how an initially weak test was found and replaced.

On the TV, `tools/smb_access_probe/VideoPlaybackProbe.kt` (temporary activity, removed afterwards, mode `P`) played three real clips through the production reader with the player muted; logs under the `VideoProbe` tag. Clip durations came from `ffprobe`.

- **Bug found on the device only:** the first run refused every clip with `InsufficientStorageException`, because the free-space check ran before the cache folder existed and a missing folder reports 0 bytes free. Fixed (create the folder first) with a regression test that uses the real default check.
- **Typical clip** (1080p H.264, 34.5 MB, 29.6 s): start wait 0.55–0.65 s, first frame 0.2 s after start, played 29.8 s (0.24 s late), no buffering events, bytes read equal to the file size, no refetch.
- **4K HLG** (140 MB, 15.7 s, 71 Mbps): on a fast link (about 10 MB/s) the wait was 1.4 s and the whole download finished before the clip ended; on a slow link (4–5 MB/s) the wait was 18.4–19.9 s, matching the model to within about a second, and playback was 0.3–0.9 s late with no stalls.
- **89 Mbps clip** (287 MB, 25.7 s): first run (no rate memory yet): the early blocks ran at 9 MB/s, the gate opened after 20 s, then the link fell to about 5 MB/s and the clip took 43.2 s to play (17.5 s late). Runs after the rate memory and the effective-rate change: wait 33.1 s and 44.7 s at a planned 4.7 MB/s, then 26.0 s of smooth playback (0.33 s late), no buffering events.
- **Link variability:** the reader measured 4–6 MB/s for several minutes and 10–12 MB/s a few minutes later, against 9 MB/s in the earlier tests. Writing to flash is negligible (0.9 s of a 45 s download).
- **Cleanup:** every run ended with 0 files in `cache/video-cache`; the test files and the activity were removed from the TV afterwards.
- **Not covered:** a mid-clip link drop is not handled below the 10 s read timeout (milestone 4's player-level pause and resume), concurrent clips, and a crash leaving a temp file (only the JVM test of `purgeStale`).
