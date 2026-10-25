# Poster covers, milestone 6 (2026-10-17)

Moved verbatim from `docs/DEVICE_TESTING.md` on 2026-10-19, with links updated for the new location.

Built and installed as before (108 JVM tests pass, 9 of them new for `AlbumCoverRules` and the reader's `awaitHeadAndTail`; lint 0 errors). The picker was driven with `adb shell input keyevent`, and results read from `adb logcat -s AlbumPicker VideoPoster`. The user checked the posters by eye.

- **Type Photos unchanged:** opening the picker loaded five photo covers from the existing cache in 4–11 ms, and the cache did not grow (38 files), so the photo cache key is intact.
- **Type Videos:** four posters were made in 0.8–1.7 s each (frames 169×300 for a portrait clip and 480×270 or 480×272 for landscape), the thumbnail cache grew from 38 to 42 files, and no temp video file was left behind. The user confirmed the posters were upright and correctly cropped.
- **Persistent reuse:** after restarting the app, the same four posters loaded from disk in 7–15 ms. A reopen within the same session is served from the in-memory cache and logs nothing.
- **Type Photos + Videos:** moving through about 65 cards, photo albums kept photo covers (38 from cache, 23 newly created), and the first two video-only albums (positions 50 and 60 of ~760 in the newest-first order; 57 albums have no photos) made posters of 169×300 and 480×270. The second fetched only 13.7 MB of a 17.8 MB file, so the partial fetch works. The cache reached 67 files.
- **Not covered:** a poster for a very heavy clip (the lightest clips are tried first, so all were light) and posters while the link is slow.
