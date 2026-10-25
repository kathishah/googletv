# Playback correctness, B06/B12/R05 exit check (2026-10-19)

Moved verbatim from `docs/DEVICE_TESTING.md` on 2026-10-19, with links updated for the new location.

Built and installed the debug APK from the working tree after B12 and R05. Before testing, the TV's slideshow preferences, screen-off timeout, and selected DreamService were saved. Through the app settings UI, a temporary preview used Photo source SMB share, Media All, Type Photos + Videos, pool 100, and 10-second interval.

- **Mixed preview:** the TV read the saved catalog (~760 albums, 2,667 videos) in 930 ms and listed a 100-photo pool in 6.0 s. Photos and a portrait pair rendered. A video became ready in 685 ms, started, and finished in 8.9 s after fetching its full 7.96 MB, with no pauses. The following photo appeared; no skipped slide, player failure, `FATAL EXCEPTION`, or `AndroidRuntime` line was observed. One stale shared SMB connection failed and the source reconnected before the video completed.
- **Failure fallback:** no video failed during this run, so the three-failure video fallback was not induced on the TV. JVM tests cover repeated post-start stalls and the third-failure transition; the earlier unreadable-share check above verified the stored-artwork fallback on the TV.
- **Resources:** thread count was 33 during preview and 32 after exit, remaining 32 after another 30 seconds. The earlier preview-cycle baseline was 26 after teardown, so the count is six higher after a video has played; no `video-reader` or `video-reader-close` thread remained in the sampled thread names. PSS varied from about 104 to 140 MB during preview and was about 97 MB 30 seconds after exit. These are single-run samples, not a soak result.
- **Idle activation:** with a temporary 60,000 ms timeout, `dumpsys dreams` reported the app DreamService active and power state `Dreaming` by the first poll after about a minute. Three bundled photos advanced at the saved 10-second interval.
- **Restored:** saved slideshow preferences matched the pre-test file byte-for-byte, including all 11 album IDs; the timeout is 600,000 ms, the app DreamService remains selected, and the TV was awakened back to Home.
