# Screensaver review: implementation plan

Plan for fixing the findings in [app-review.md](app-review.md), the combined Claude and Codex review of `apps/screensaver` at `cd6566e`. Finding IDs (B, R, C) refer to that report. Drafted 2026-10-19; implementation began the same day. B02 and the available Step 0 baselines are complete; see [device findings](docs/DEVICE_TESTING.md#device-findings). The picker cover-memory and optional video checks remain pending.

## Principles

- **Order:** the screensaver comes first, then the in-app slideshow, then the album picker, then tooling. Within that, cheap and isolated fixes go before larger reworks.
- **Small milestones:** each step is a buildable milestone made of small commits, one per finding, directly on `main`. Each ends with a TV exit check, and its commands and results are recorded in `docs/DEVICE_TESTING.md` (AGENTS.md).
- **Bugs found from the code are not reproduced on the TV first:** their logic is deterministic, so the TV check comes after the fix. TV checks run first only where the measured size of a problem decides whether or how to fix it.
- **Docs travel with the fix:** each fix updates its own doc lines as it lands. The broader spec and doc reconciliation happens once, after steps 1–3, so the same lines are not edited twice.
- **Build and test command** for every step:
  ```sh
  cd apps/screensaver
  JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
  ```
  Use `:app:lintDebug`: a plain `lintDebug` also checks the `heif-probe` module and fails. Baseline: 199 JVM tests pass.
- **Rules that apply throughout:** SMB strictly read-only; no photo filenames or host addresses in logs or docs; no new dependencies without checking official documentation first.

## Step 0: TV checks before fixing (read-only)

Baseline run 2026-10-19; results are recorded in [device findings](docs/DEVICE_TESTING.md#device-findings). The commands use adb and the debug build. No device settings were changed, app preferences were restored after the preview cycles, and the Mac mini share was not touched. Record counts and measurements, not filenames or addresses. Keep the numbers from checks 2 and 3 as a baseline to rerun after the fixes. The 100+ album cover-memory check remains pending because opening the picker would access the configured SMB share; the optional video session was also skipped.

| # | Check | How | What it decides |
| --- | --- | --- | --- |
| 0.1 | **R06** leftover image temp files | `adb shell run-as com.googletv.screensaver ls -la cache`: count `heif-decode-*` and `smb-image-*` files, with sizes and ages | If whole photos are lingering, the R06 purge moves into step 1; otherwise it stays in step 5 |
| 0.2 | **B13 / R03** thread and SMB connection leaks | Enter and leave the preview about 10 times, recording the app's thread count (`ps -T`) and open port-445 connections (from `/proc/net/tcp`, addresses not recorded) after each round. Then open the picker, move across a few cards so covers load, back out at once, and check port 445 again a minute later | Whether R03 really leaves SMB sessions open: an early fix or cleanup |
| 0.3 | **R02** picker cover memory | `dumpsys meminfo com.googletv.screensaver` before and after moving across 100 or more albums: Java heap, native heap, PSS | Whether the carousel needs card recycling or virtualization |
| 0.4 | **B18** launcher banner | Look at the home screen apps row: banner or icon? | If the TCL launcher shows the icon, B18 is cosmetic and waits |
| 0.5 | Optional, only if videos are used in the app: **R05 / R07 / B12** | A short in-app video session with logcat: time between `Video finished` and the next slide, and any player errors before the first frame | How urgent the video cleanup and watchdog work is |

### Checks on the Mac, not the TV

Each needs the user's separate approval. Both are read-only, counts-only queries of the clone's Photos database.

- **B07:** how many trashed screenshots, documents and receipts still have a file in `originals`. This shows how often the bug appears.
- **R15:** whether trashed photos keep their regular-album memberships. This sets the album rule before any code changes.

### Not checked on the TV before fixing

- **B01, B11, B13 (the missing call), B14:** the logic is clear from the code; check after the fix.
- **B02:** reproducing it means saving a blank host and waiting for the screensaver to crash. Check after the fix that Save rejects it.
- **B03, B04, B05, R01, R14:** test with local fixtures. B05's cost is already measured (713.9 s for a full WAL transfer).
- **B06:** reproducing it would mean changing permissions on the share. Fix it from the code.
- **R04, R13:** the wider HEIF checks were waived by decision.
- **B16, R09:** they need the app's process to be killed or the TV's font-scale setting changed. Defer to step 6 with a restore plan.

## Decisions needed from the user

| Decision | Blocks | Question |
| --- | --- | --- |
| Video success boundary | Resolved 2026-10-19 | User decided a clip resets the streak only after it finishes or reaches the 60 s cap. |
| Catalog rebuild in the screensaver | Resolved 2026-10-19 | User chose app refresh. DreamService uses the last app-refreshed catalog and asks for an app refresh if it is missing or incompatible; it does not check the Mac for newer Photos changes at dream start. |
| Trashed photos in albums | R15 (deferred) | Should recently deleted photos appear in album slideshows? Needs the Mac database check first |
| Saved Date-only labels | Docs and UI | What happens to saved `DATE_ONLY` values (Save currently changes them silently to Location + date): keep, migrate explicitly, or bring the option back |

## Step 1: quick wins

About a day; small, isolated, low-risk.

| Item | Change | Test / doc |
| --- | --- | --- |
| **B02** | Check the whole settings draft before Save writes anything (in SMB mode: host, share and path, using the same rules as the `SmbGuestPhotoSource` init block). Show the error and write nothing. `SlideshowDreamService` catches a source that cannot be built and shows `showErrorState` instead of crashing | Unit test for the check if it is extracted as a pure function |
| **B07** | Drop `a.ZTRASHEDSTATE=0` from the exclusion query only. Bump `PhotosAlbumCatalog.VERSION` to 6 with a comment | Update the schema and exclusion text in ALBUM_SELECTION, PHOTO_SOURCES and MULTI_IMAGE |
| **B11** | With Type Photos, build the union of the selected albums and fail only if it is empty | Keep the picker warning unchanged |
| **B14** | `ProgressiveReader.readAt` returns 0 when `size == 0`, before the end-of-file check (`MediaDataSource` contract) | Fix the assertion in `ProgressiveReaderTest` |
| **B13** | `MainActivity.leavePreview` calls `dispose()` | — |
| MEDIA_TYPE:39 | Show "Videos play to the end, up to 60 s" under Interval when Type includes videos | — |
| Optional **B18** | A 320×180 xhdpi banner with the app name; keep the artwork for the slideshow | Only if check 0.4 shows the launcher uses the banner |
| Conditional **R06** | Purge leftover image temp files when the source starts | Only if check 0.1 found leftovers |

**Ordering trap:** B07 bumps the catalog version. Until B09 is fixed, the next screensaver start in album mode rebuilds the catalog inside the screensaver. Right after installing, refresh the catalog from the picker (as earlier version bumps did), and record that step in DEVICE_TESTING.

**Completed first:** B02 on 2026-10-19. Shared SMB validation now runs before Save writes preferences; DreamService displays source-construction errors. The pure validation tests, app build and lint passed, and the TV rejected an empty-host Save without changing the preference file. See [device findings](docs/DEVICE_TESTING.md#device-findings).

**Completed next:** B07 on 2026-10-19. The exclusion query now retains classifications for trashed assets, catalog v6 rejects v5, and the schema/exclusion docs are current. The 203 JVM tests, debug build and lint passed; the TV rebuilt its catalog from the staged database/WAL in 62.1 s, and the v6 marker was verified. Settings were restored to Sample pics. No specific Recently Deleted image was used for a display-level check; the plan's separate count-only Mac Photos query was not run. See [device findings](docs/DEVICE_TESTING.md#device-findings).

**Completed:** B11 on 2026-10-19. Photos mode now unions paths across the selected albums and only errors when no readable supported photos remain in the combined selection. Picker warnings are unchanged. The 203 JVM tests, debug build and lint passed; the change is commit `ca4f95f`.

**Completed:** B14 on 2026-10-19. `ProgressiveReader.readAt` now returns 0 for a zero-byte request before checking EOF, and the JVM assertions cover both an in-range position and EOF. The 203 JVM tests, debug build and lint passed; the change is commit `a16f733`.

**Completed:** B13 on 2026-10-19. Leaving Preview now disposes the `SlideshowPlayer`, including its worker executors and source. The 203 JVM tests, debug build and lint passed; the change is commit `b3fc9f6`.

**Completed:** MEDIA_TYPE:39 on 2026-10-19. A note under Interval now explains the 60-second video cap for both video-inclusive Types and is hidden for Photos. The 203 JVM tests, debug build and lint passed; the TCL settings screen showed it in the requested location with all rows and actions fitting. Cancel restored the saved Photos Type.

**Step 1 TV exit check completed:** B02 rejected a blank host (recorded above); the TV refreshed and verified catalog v6 for B07; a video-only album selected alongside the prior albums played under Type Photos for B11; ten Preview/Back cycles stayed at a steady thread count after initial teardown for B13; idle activation started the DreamService at the temporary 60-second timeout. The saved preferences and 600000 ms timeout were restored. No Recently Deleted sample was identified because the separately approved Mac count query was not run; its conditional display check remains untested. The 320×180 launcher banner is not applicable because the app icon looked good in check 0.4, and R06 is not applicable because check 0.1 found no leftover image temp files.

**Completed:** C03 test seam on 2026-10-19. `SlideshowPlayer` now accepts an injected monotonic clock, callback dispatcher, and worker/fallback executors; production still uses the main Looper and single-thread workers. A JVM test drives an album-index failure through a deterministic callback queue. Full suite: 204 tests passed; debug build and lint passed. Commit `54f577d`.

**Completed:** B01 on 2026-10-19. After a video failure, the player now advances from the failed slot and preserves a prepared successor only when it matches that slot. A sole remaining photo is shown, and a lone stalled video retries after backoff. Tests cover permanent and transient failures at first, middle and last positions, a prepared photo successor, and one-item pools. Full suite: 208 tests passed; debug build and lint passed. Commit `87ddcb4`.

**Completed:** B06 on 2026-10-19. All mode now checks a small photo sample in Photos and Photos + Videos types. If per-file refusals empty a previously working pool, the player re-lists with backoff and switches to stored artworks after repeated share failures; single-file failures remain skippable while other slides remain. Full suite: 210 tests passed; debug build and lint passed. Commit `a1c741c`.

**Completed:** R12 on 2026-10-19. Per-file SMB refusals keep the reusable session; transport/session and configuration failures discard it, and transient failure on a reused session retries on a fresh connection. The cancellation epoch remains in place. Failure-policy unit tests pass. Full suite: 211 tests passed; debug build and lint passed. Commit `758afde`.

**TV exit check:**
- Save rejects a blank SMB host.
- Picker refresh builds catalog v6, and the timing is recorded.
- A recently deleted screenshot (if check 0.1 or the B07 Mac count found one) no longer appears in All mode.
- Selected albums including a video-only album play under Type Photos.
- Thread count is steady across preview cycles (compare with check 0.2).
- Idle activation still works.

## Step 2: slideshow player correctness

- **Test seam first (part of C03):** inject the clock, the scheduler (or handler) and the executor into `SlideshowPlayer`, so its failure transitions run on the JVM. Do no more than these fixes need.
- **B01:** after removing a failed current video, pick the next slide from the failed slot, keep or drop `prepared` to match, and handle a pool reduced to one slide (show it if it has not been shown; retry a lone video). Test permanent and transient failures at the first, middle and last slots, including pools reduced to one.
- **Completed B06 (2026-10-19):** All mode now checks a small photo sample in Photos and Photos + Videos types. If per-file refusals empty a working pool, the player re-lists with backoff and switches to stored artworks after repeated share failures; individual failures are still skipped while slides remain. Full suite: 210 tests passed; debug build and lint passed. Commit `a1c741c`.
- **Completed R12 (2026-10-19):** per-file SMB refusals keep the reusable connection; transport/session failures discard it, stale reused sessions reconnect once, and cancellation still uses the epoch rule. Failure-policy tests pass. Full suite: 211 tests passed; debug build and lint passed. Commit `758afde`.
- **Completed B12 (2026-10-19):** video player failures now carry the reader's underlying cause. Transient SMB/reader failures retry; unplayable or unsupported clips are dropped. The failure streak resets only on video completion/60 s cap or a successfully shown photo, never on the first frame; repeated post-start stalls are counted. User decision recorded in `docs/MEDIA_TYPE.md`. Full suite: 216 tests passed; debug build and lint passed. Commit `2eaf384`.
- **Completed R05 (2026-10-19):** `PreparedVideo` and its reader now close off the playback thread; reader cleanup deletes the local cache in `finally` even when join is interrupted. Close is idempotent and records clip rate once. Full suite: 219 tests passed; debug build and lint passed. Commit `5d4b753`.

**Step 2 TV exit check completed (2026-10-19):** the mixed SMB preview showed photos, a portrait pair, and a video that completed and advanced to the next photo; no failure could be induced in that real clip run. The app dream activated after about a minute at a temporary timeout. Preferences matched byte-for-byte after restoration and the 600000 ms timeout was restored. Thread count was stable at 32 after exit, six above the earlier 26-thread preview-cycle baseline; PSS was about 97 MB 30 seconds after exit. See the measured run in `docs/DEVICE_TESTING.md`. The three-video-failure transition was verified in JVM tests and the stored-artwork fallback had already been seen on the TV during the unreadable-share check.

## Step 3: catalog refresh integrity (DatabaseStage, local fixtures)

1. **Completed B05 (2026-10-19):** a failed append or post-copy check now truncates to the saved committed prefix only when the database state, WAL header/generation and local/remote prefix still match. The injected mid-copy stream failure rolled back, and the next sync resumed at the committed offset. Full suite: 220 tests passed; debug build and lint passed. Commit `dbd270e`.
2. **Completed B04 + B03 (2026-10-19):** WAL copies now reject early EOF unless the scanner has reached the stale-generation tail; the source WAL's size and modification time must match before and after copying; a WAL shorter than its header is treated as unreadable, and only explicit SMB not-found statuses mean it is absent. Other listing errors abort the refresh while preserving the previously committed catalog. Fixtures cover early EOF rollback/retry, metadata changes, unclear listings, short WALs, and the stale-generation exception. Full suite: 225 tests passed; debug build and lint passed.
   - Reject a WAL stream that ends early, except for the stale-generation tail the design expects.
   - Compare the WAL's size and modification time before and after the copy.
   - Tell a truly absent WAL apart from an unclear listing, and keep the previous catalog when the snapshot is uncertain.
   - Never publish the older database-only view by accident.
3. **Completed R14 (2026-10-19):** the staged SQLite database plus committed WAL prefix now have a persisted SHA-256 identity, and the cached catalog stores the identity it was built from. A same-length WAL replacement with a changed modification time forces a full WAL copy and produces a new identity; repeated uncommitted tails do not change the committed identity, so the catalog can be reused without another SQLite query while its source timestamp is refreshed. The TV's first required checksum-version refresh copied 7.72 GB in 1,047 s; a subsequent unchanged refresh reused the saved identity and catalog in 4.5 s without copying database bytes. Full suite: 232 tests passed; debug build and lint passed.
4. **Completed R01 (2026-10-19):** `WalFrameScanner` now validates the WAL header checksum and every complete frame's cumulative checksum in SQLite's specified byte order. A checksum-invalid log aborts the refresh; incremental resume seeds the running checksum from the saved committed frame. Added a SQLite-generated WAL fixture and tests for corrupt header/frame checksums, a truncated final frame, both checksum word orders, and database-stage retry after corruption. Sync state and catalog versions were advanced so a pre-checksum snapshot is refreshed once. Full suite: 232 tests passed; debug build and lint passed. TV refresh completed successfully; see [device findings](docs/DEVICE_TESTING.md#device-findings).

**TV exit check:** an unchanged refresh (expect about 3 s), an incremental refresh after the next CCC run, and the time of each. Ideally also a refresh interrupted mid-transfer (turn Wi-Fi off during an incremental copy) followed by a resumed refresh that does not fetch the whole WAL again.

## Step 4: catalog and picker flows

- **B09 completed (2026-10-19):** choosing a video-inclusive Type with SMB goes through catalog progress/retry before it is committed; transient SMB errors remain retryable, and the TV confirmed that Media All stayed All. Per the user's decision, DreamService requires a usable app-refreshed catalog and shows an app-refresh instruction when it is missing or incompatible. It does not check for newer Mac changes at idle start. Commits `68b5d82` and `7f9d058`; see [device findings](docs/DEVICE_TESTING.md#device-findings).
- **B10 implementation completed (2026-10-19):** a failed refresh restores the old carousel and shows an inline error with Retry; commit `078400f`. The TV failure injection is untested: ADB uses Wi-Fi, and the TV shell rejected the temporary port-445 firewall rule. The normal picker refresh and successful retry path remain available.
- **B08 completed (2026-10-19):** catalog, staged snapshot state, cover cache, and saved album selection are bound to the SHA-256 identity of the normalized SMB location; catalog reads reject a mismatched ID. The old unbound stage was adopted once for the configured single library. Commit `eb0d10f`; full suite: 240 tests, debug build, and lint passed. On TV, catalog creation reused the staged database/WAL and fetched 0 bytes in 64.8 s; 10 albums / 879 photos remained selected. See [device findings](docs/DEVICE_TESTING.md#device-findings).

**TV exit check:** the video catalog progress gate and Media All preservation were observed; the screensaver showed the app-refresh instruction for an outdated catalog. B08's new catalog was built from the adopted stage with 0 bytes fetched and the 10 / 879 selection preserved. The B10 failed-refresh carousel path remains untested on TV because SMB blocking would require an input route that keeps ADB available; see [device findings](docs/DEVICE_TESTING.md#device-findings).

## Step 5: resource and cancellation cleanup

- **R03:** check each job's owner token before every new network or decode phase. Keep the picker's refresh source so leaving the picker can cancel the refresh. Stop cover jobs that are still queued after the picker closes from reopening a closed source. Stop a pair's second decode from starting after `stop()`.
- **R06:** move image temp files into their own folder, purge leftovers, and limit total size and age (unless done in step 1).
- **B15:** make the native decoder free its libheif context, handle, options and tiles on every exit path, and close the reader's file handle if `setLength` throws.
- **R02:** recycle cards, or drop the cover images of cards far off screen, with bitmap ownership shared with a bounded cache. Only if check 0.3 shows meaningful growth.
- **R08:** narrow the catch-all error handling in the code touched by this step.

**TV exit check:** rerun checks 0.2 and 0.3 and compare with the baseline.

**Step 5 implementation progress (2026-10-19):** R03's pair phase and picker refresh/cover ownership guards are implemented; R06 image temps now use a dedicated bounded directory and legacy leftovers are removed; B15 has RAII ownership in the HEIF probe and closes the reader file on `setLength` failure; R08's touched Kotlin recovery paths preserve interruption and no longer catch fatal `Error`s. The app JVM suite, debug build, lint, and HEIF probe native build pass. On TV, six old HEIF temp files were observed and subsequently cleaned, and ten Preview/Back cycles left zero port-445 sockets. R02's initial 88 MB to 203 MB PSS increase justified dropping off-focus cover bitmaps. The follow-up sweep was too slow over Wi-Fi ADB and PSS stayed elevated in the interrupted sample. **The user accepts this memory behavior and the focused-card-only covers; no further R02 work or repeat measurement is required. Step 5 is complete.** See [device findings](docs/DEVICE_TESTING.md#resource-cleanup-follow-up-2026-10-19).

## Step 6: TV polish, accessibility, docs

- **B18:** the banner (unless done in step 1).
- **B17:** selected state, state descriptions and field labels for TalkBack.
- **B16:** keep the settings draft, the current screen and the focused control across activity recreation.
- **R09:** settings rows that grow with their content, and a scrolling settings screen at larger text sizes.
- **R10:** restore focus after the preview, Sort and Refresh.
- **Docs reconciliation:** work through the spec and doc table in app-review.md (stale statuses, schema version, Auto Launch guidance in ARCHITECTURE, PRODUCT and AGENTS.md, the DEVICE_TESTING step 4 and step 7 summaries, the README link and lint command, the bitmap memory wording). Can start any time after step 3; the broken README link can be fixed at once.

**TV exit check:** TalkBack reads the selected setting choices. A larger font scale is tried and then restored. D-pad routes and Back work with the on-screen keyboard open.

**Step 6 progress (2026-10-19):** B17 is implemented: settings choices expose row/option names and selected state, and SMB labels target their fields. The TV's accessibility node dump confirmed the selected interval state; audible TalkBack navigation remains for the final exit check because TalkBack is currently disabled. B18 remains skipped per the earlier TV check, which found the launcher icon adequate. B16 draft, screen and control restoration are implemented; on-TV font-scale recreation preserved an unsaved Layout choice and Cancel discarded it. R09 now has content-sized rows/buttons in a vertical scroller; at font scale 1.2 D-pad Down reached Preview and scrolled actions fully onscreen, and the TV font scale was restored. Preview/Back and Sort focus restoration were confirmed. The user decided to remove legacy DATE_ONLY support; the enum is removed and old stored values use the normal default fallback. The user restored the album selection after Select all was accidentally activated; the TV now shows 1 album / 63 photos. The current build, lint and full JVM suite pass. Keyboard Back/D-pad and Refresh focus remain pending. Documentation reconciliation, including Auto Launch, media-type and portrait-pair status updates, is complete.

## Deferred until evidence appears

| Item | Reason |
| --- | --- |
| R04 HEIF offset alignment | The wider HEIF check was waived by decision; revisit if a shifted or cropped HEIC is seen |
| R13 native downscale aliasing | The user did not notice it; revisit if it becomes visible |
| R11 Photos schema fingerprint | Do it when a macOS Photos update arrives; schema failures already keep the old catalog |
| R15 album trash semantics | Starts with the Mac database check and the user's decision, not code |
| R07 video watchdog | Only if check 0.5 or the unexplained black screens point at player hangs |
| C01, C02, remaining C03 | Tooling and structure cleanup, when that code is next touched |
| PairPlanner style repeats | Trivial; a playlist with a single pair, or a pair that was split, can repeat a style |
