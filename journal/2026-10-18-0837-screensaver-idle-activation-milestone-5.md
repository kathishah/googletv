# Screensaver idle activation, milestone 5 (2026-10-18)

Moved verbatim from `docs/DEVICE_TESTING.md` on 2026-10-19, with links updated for the new location.

With Type Photos + Videos saved (Media Choose Albums, 11 albums selected), the TV was left idle on its home screen and the dream state was polled every 15 seconds with `adb shell dumpsys dreams` (`mCurrentDreamName`) and `dumpsys power` (`mWakefulness`), which send no input. Logs came from `adb logcat -s PhotoSource VideoStage VideoPoster`.

- **Activation:** the idle clock started at 08:30:02. To avoid a 10-minute wait the system `screen_off_timeout` was lowered from 600000 to 60000 ms with `adb shell settings put system screen_off_timeout 60000`, and the original was written down first and restored afterwards (`600000`, verified). The dream started within seconds of that change, because the TV had already been idle for more than a minute; the first poll showing `Dreaming` with `SlideshowDreamService` was 08:32:05. The 10-minute activation at the default timeout was observed earlier ([2026-10-16](../docs/DEVICE_TESTING.md#device-findings)), so this run tests what the dream shows, not the 10-minute figure.
- **What it showed:** the log read "Screensaver shows 500 random photos because Type includes videos" and "Photo listing found 500 pooled photos in 5983 ms", so the saved albums and the 1,000 pool were ignored. For 14 consecutive polls (about 3.5 minutes) the TV was `Dreaming`. Three screenshots taken 12 s apart were three different frames (compared by hash; the images were deleted without being viewed), so it advanced through photos at the 10 s interval.
- **No video:** zero `Video`, `VideoStage`, or `VideoPoster` log lines during the dream, and `cache/video-cache` held no temp files.
- **Restored:** idle timeout back to 600000 ms, dream ended, and the saved settings (Media Choose Albums, Type Photos, the same 11 albums) compared identical to the intended copy.
- **Not covered:** a longer dream session of hours, and the dream while the Mac mini is denying Guest (it shows the stored artwork, as before, covered in earlier milestones).
