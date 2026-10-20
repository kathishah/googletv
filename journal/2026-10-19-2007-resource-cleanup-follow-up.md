# Resource cleanup follow-up (2026-10-19)

Moved verbatim from `docs/DEVICE_TESTING.md` on 2026-10-19, with links updated for the new location.

- **R06:** the installed build found six old `heif-decode-*` temp files in the app cache root. The next image decode removed those legacy leftovers. The new decoder path uses `cache/image-decode-temp`, purges stale files on entry, and enforces age and total-size bounds; the post-decode legacy-root count was zero.
- **R03:** ten Preview/Back cycles completed with zero established SMB port-445 sockets afterward. The app remained on settings with the saved 10 albums / 879 photos selection. Thread count was 32 after the cycles versus the earlier 26-thread preview-cycle baseline; this run did not isolate the cause. The pair second-decode stop check was code-reviewed and build-tested, but not induced on the TV.
- **R02:** the baseline picker sweep raised PSS from about 88 MB to 203 MB (native heap from about 12 MB to 88 MB). A focused-cover-only cleanup was added, but the follow-up ADB sweep was slow and did not yield a stable after-idle measurement; PSS remained elevated during the interrupted run. **Decision (2026-10-19):** the user accepts the observed memory increase and the focused-card-only cover behavior; no further R02 memory work or repeat measurement is required. The saved selection remained 10 / 879.
- **Builds:** the app JVM tests, debug APK, and lint passed after the resource/cancellation changes. The HEIF probe's Android native build also passed with RAII ownership for libheif contexts, handles, options, and tiles.
