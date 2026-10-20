# Album picker cover and scroll regression (2026-10-19)

Moved verbatim from `docs/DEVICE_TESTING.md` on 2026-10-19, with links updated for the new location.

- **Cause:** every album focus change walked all picker cards, repeatedly queried child positions, and searched the full album list inside that loop, making D-pad navigation quadratic in catalog size. The same path cleared every non-focused cover, so previews disappeared as focus moved.
- **Fix:** removed the full-carousel focus scan. Loaded previews stay attached while in the recent-cover LRU; when an entry is evicted, its `ImageView` is cleared before its bitmap is recycled.
- **TV check:** installed the debug APK with `adb install -r`, reopened the cached picker, and moved focus across ten albums without activating cards. Covers stayed visible across the carousel and the saved count remained 1 album / 63 photos. Cached cover reads in this pass took 12–16 ms each. This is a focused regression check, not a broad scroll-performance benchmark.
- **Build:** `:app:assembleDebug :app:lintDebug` passed. The TV build label showed `fe419b95-dirty` because this fix is uncommitted.
- **Return-scroll follow-up:** LRU eviction also removes the album's in-memory request marker, so focusing an evicted card can decode its persistent thumbnail again. After installing this change, a 12-card rightward then leftward navigation showed covers again on return. During this scripted navigation, the saved summary unexpectedly changed from 1 album / 63 photos to 6 / 282; I stopped navigation, cleared the selection, and reselected the previously confirmed 63-photo card. Final summary was restored to 1 album / 63 photos. Do not treat the selection-count change as an intended picker action.
