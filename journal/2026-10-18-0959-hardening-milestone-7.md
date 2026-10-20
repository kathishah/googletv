# Hardening, milestone 7 (2026-10-18)

Moved verbatim from `docs/DEVICE_TESTING.md` on 2026-10-19, with links updated for the new location.

A 60-minute soak of the in-app slideshow (Type Videos, Media All, the real catalog of 2,667 library videos) from 08:43 to 09:44, with the app's own `VideoStage` and `PhotoSource` log lines and a once-a-minute memory sample (`dumpsys meminfo`).

- **Result:** 101 clips finished, 1 was skipped by the start-wait rule, none failed, and every logged clip had 0 pauses. Memory (PSS) stayed between 80 and 101 MB for the hour, with no growth trend, and the video cache held only the clip being played.
- **Idle timers:** the TV's screen-off and Art Mode timers did not interrupt the slideshow in 15-, 30-, and 60-minute runs, so that open question is closed for in-app preview.
- **HDR:** HEVC Main 10 clips played in the soak, and the user reported no color or brightness problem.
- **Album start:** starting an album preview now checks a sample of 8 photos instead of opening every selected one (the opening of every selected photo took about 21 s on the TV before the first slide), and a low-storage condition is treated as "try this clip later" instead of a failure.
- **Black screens:** the user saw a couple of black screens with a date caption during the soak. A controlled run with a 48 s Motion JPEG (160×90), a 31 s MPEG-4 (640×352), a 5 s H.264 (640×480), and a 1080p H.264 control, sampling screen brightness every 3 seconds, showed all four formats rendering normally (no near-black samples), so the codec theory is ruled out. A 65-minute brightness-monitored run was started and stopped at the user's request. The cause is **unexplained**; it is most likely clips that are dark at the start or fade in, which the caption would still label.
- **Blur:** very old clips (2011–2012) look soft because their originals are small: about 10% of the library is under 640 px wide, including 78 clips at 160×90. The app used to stretch every clip to fill the screen. Now `VideoFitRules` scales a clip to fit the view but by at most 2× its own pixel size (6 JVM tests, 116 in all). On the TV: the 640×352 and 640×480 clips play 1280 px wide with black around them, and the 160×90 clip plays as a 320×180 window in the middle. The 2× value is a single constant (`VideoFitRules.MAX_UPSCALE`) if it should be larger.
- **Restored afterwards:** the real catalog (2,175,320 bytes) and the saved settings (Media Choose Albums, Type Photos, the same 11 albums, interval 10 s, pool 1000) compared byte-identical to the saved copies, and the idle timeout was still 600000 ms.
- **Not covered:** a soak of several hours, and the cause of the black screens.
- **Build label restored:** the settings screen's "Build <commit> · <time>" line had been dropped in the album-selection rework (commit 00ef614). It is back on the header line, right-aligned, and was checked on the TV (the screen is no taller; the label shows `-dirty` only for a build with uncommitted changes).
