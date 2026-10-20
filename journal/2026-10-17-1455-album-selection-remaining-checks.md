# Album selection: remaining Phase 1 checks (as of 2026-10-19)

Moved verbatim from the "Remaining Phase 1 checks" section of `docs/ALBUM_SELECTION.md` on 2026-10-19, with links updated for the new location. These are the checks as last listed; their status was not re-verified when this entry was written.

1. On the TCL, exercise first-use catalog creation, refresh replacement, failed-refresh preservation, and cache-loss recovery. DB/WAL consistency during a live refresh has been verified (user-confirmed 2026-10-19); end-to-end times are recorded below.
2. Partly done (2026-10-17): after an actual CCC refresh the incremental sync ran (60,699,960 new WAL bytes) and, per the user, the saved album selection still worked, so `ZUUID` continuity held for that refresh, and UUID continuity has since been verified (user-confirmed 2026-10-19).
3. Done in part (2026-10-17, user-confirmed on the TCL): the DreamService plays photos from the selected albums. Still to finish on-device: All photos behavior, uncapped selected-album playback, cross-album path deduplication, selection persistence, error handling, and the same selection in Preview and DreamService. Review the settings and picker with [the mock screens](../docs/mockups/album-selection.html) and [carousel mock](../docs/mockups/album-picker-carousel.html).
