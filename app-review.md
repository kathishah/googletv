# Android TV screensaver — combined code review

Collated October 19, 2026, against revision `cd6566e5928c86635e513edcf2e919fa6e97939f` (58 reachable commits).

This report consolidates the [Codex review](/Users/dev/dev/googletv/docs/app-review-codex.md), the [Claude review](/Users/dev/dev/googletv/app-review-claude.md), Codex's verification of Claude's claims, and Claude's verification of Codex supplied by the user. Duplicate findings are combined; corrections from both verifications are incorporated. It is a reviewed synthesis, not a claim that both reviewers independently reproduced every scenario.

Scope: `apps/screensaver`, native HEIF code in `tools/heif_tile_prototype`, related tooling, JVM tests, Kotlin/Android TV development practices, and repository/specification conformance. AGENTS.md, ARCHITECTURE, PRODUCT, ROADMAP, ALBUM_SELECTION, MEDIA_TYPE, and MULTI_IMAGE were checked. DEVICE_TESTING and PHOTO_SOURCES remain the source of truth for measured behavior and firmware quirks; ROADMAP remains the current work order.

Only this new report was written during collation. The original reports, application code, other documentation, dependencies, and configuration were not changed; nothing was committed. The TV and Mac mini share were not contacted. Existing test output and public API documentation were inspected; no build, test, lint, adb, SSH, mount, or share-probe command was run during collation.

**Evidence and severity:** a verified bug is established from source or an API contract, not newly reproduced on the TV. A risk has a concrete failure mechanism but unmeasured runtime impact or a conditional trigger. Cleanup covers documentation, tooling, and development improvements. P1 concerns playback availability or catalog correctness; P2 is substantive correctness, resource, or usability work; P3 is cleanup. Conditional exposure is described in each finding. No source-library corruption or production SMB mutation was found; local catalog correctness is not established merely by successful SQLite queries.

## Main assessment

The highest-priority defects are failed-video progression, invalid settings reaching the DreamService, and WAL refresh handling. The next group covers error recovery, catalog/picker behavior, deterministic cleanup, accessible settings, and lifecycle state. Cover retention and cancellation can increase process memory or leave SMB sessions alive; their actual impact needs measurement.

The design has useful foundations: source/rendering separation, explicit Guest authentication, read-only production handles, display-bounded decoding, shared Preview/Dream rendering, atomic catalog replacement, and pure Kotlin planning helpers. Fixes do not require a rewrite into Compose, coroutines, Media3, or a larger dependency-injection framework.

## Verified bugs

### B01 — P1: failed-video removal can skip the next item or stall playback

Locations: [SlideshowPlayer.kt:477](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/SlideshowPlayer.kt:477), [SlideshowPlayer.kt:205](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/SlideshowPlayer.kt:205).

Removing the current video leaves currentIndex pointing at its replacement; showNextSlide then increments again. A sequence of failed video, photo A, photo B advances to B. If A was already prepared, it remains stranded in `prepared`; that non-null value prevents further preloading until A comes around again, retaining its bitmaps/background and making intervening advances wait for fresh decoding. If removal leaves one good photo, the size-less-than-two guard prevents displaying it. A sole video that starts and subsequently stalls also cannot retry through that guard.

Suggested fix: select the successor from the failed slot after removal, keep or invalidate prepared content according to that successor, and distinguish a sole item that still needs presentation from one already displayed. An index decrement alone does not fix the one-item cases. Add player-level tests for permanent and transient failures at the first, middle, and final slots, including pools reduced to one.

Spec disposition: change code to satisfy MEDIA_TYPE’s advance/retry behavior.

### B02 — P1: invalid saved SMB settings can crash screensaver activation

Locations: [MainActivity.kt:353](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/MainActivity.kt:353), [SlideshowDreamService.kt:23](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/dream/SlideshowDreamService.kt:23), [SmbGuestPhotoSource.kt:94](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/SmbGuestPhotoSource.kt:94).

Save persists an empty host or invalid path without validating the complete configuration. Preview catches source-construction validation failures, but the DreamService constructs its source without that protection. Saving SMB with a blank host therefore leaves an exception waiting for the next idle activation.

Suggested fix: validate the complete settings draft before writing any preferences; commit the validated settings together. The dream should also handle invalid stored configuration with a stable error state rather than crashing.

Spec disposition: change code.

### B03 — P1: an ambiguous missing WAL can replace a good catalog with an older snapshot

Locations: [SmbGuestPhotoSource.kt:309](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/SmbGuestPhotoSource.kt:309), [DatabaseStage.kt:86](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/DatabaseStage.kt:86).

The remote stat adapter returns null for a missing directory-listing entry and for per-file listing errors. DatabaseStage interprets null as an absent WAL, removes the staged WAL, and queries the database alone. If CCC briefly removes/replaces the WAL while leaving an older checkpointed database visible, refresh can publish older memberships/exclusions and discard the reusable multi-gigabyte staged WAL.

The earlier claim that a denied WAL read directly triggers this path was too broad: stat lists the same folder as the database, and a denied WAL header read fails later. The realistic unverified trigger is a transiently absent listing entry during replacement. A legitimately absent WAL can also represent a valid checkpoint; absence must be assessed with snapshot consistency rather than always rejected.

Suggested fix: distinguish definitive nonexistence from denied/ambiguous access, verify coherent database/WAL absence across the snapshot, and preserve the previous catalog on an uncertain snapshot. Do not silently publish the older DB-only view.

Spec disposition: change code; retain ALBUM_SELECTION's successful-snapshot requirement.

### B04 — P1: early EOF during WAL transfer can be accepted as success

Location: [DatabaseStage.kt:188](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/DatabaseStage.kt:188).

The database copy verifies its expected byte count. The WAL copy merely breaks on EOF. If the remote WAL shrinks mid-copy while the observed header/database metadata remain unchanged, a short stream ending after an earlier commit can be accepted. The scanner retains that prefix and state/catalog can be published using the newer remote modification time. This is a narrow race, not the normal behavior of an arbitrary network exception; a later refresh can recover it.

Suggested fix: reject an unexplained short stream. Allow intentional early termination only when the scanner explicitly recognizes the stale-generation tail permitted by the sync design. Never publish a partial transfer as a fully current catalog.

Spec disposition: change code.

### B05 — P1: a failed incremental append discards the reusable WAL prefix on the next sync

Locations: [DatabaseStage.kt:93](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/DatabaseStage.kt:93), [DatabaseStage.kt:183](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/DatabaseStage.kt:183).

Incremental copying modifies the staged WAL in place. If a stream throws after appending bytes, or post-copy verification fails before state is saved, the WAL is longer than the previous recorded committed length. The next reuse check requires exact equality and chooses a full transfer instead. The documented clone had a 6.4 GB WAL; its historical full TV transfer took 713.9 seconds. That is a measured example of the cost, not a guaranteed duration.

Suggested fix: recover the prior recorded prefix after verifying that database identity, WAL generation, and prefix integrity still match. Truncate only an eligible extra tail; do not blindly trust any longer local file. Add a throwing-stream test followed by a successful resumed sync.

Back during a picker refresh is not an established cancellation trigger: the refresh source is local to its runnable and picker exit currently does not abort it (R03). Network interruption or other exceptions after append are sufficient triggers.

Spec disposition: change code to preserve the intended incremental reuse guarantee.

### B06 — P1: All mode does not recover when listing works but every photo is refused

Locations: [SmbGuestPhotoSource.kt:105](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/SmbGuestPhotoSource.kt:105), [SlideshowPlayer.kt:519](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/SlideshowPlayer.kt:519).

The eight-photo startup readability check runs only for selected albums. All mode can list successfully, then drop access-denied photos individually until the pool is empty and playback stops. PRODUCT promises continuing retry/artwork behavior for a temporarily refusing share.

Suggested fix: apply the startup readability sample to All mode, and provide recovery when a previously usable pool becomes entirely refused. Preserve ordinary per-file skipping when other photos remain usable.

Spec disposition: change code.

### B07 — P2: Recently Deleted classified images can bypass exclusion

Location: [PhotosAlbumCatalog.kt:174](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/PhotosAlbumCatalog.kt:174).

The exclusion query requires ZTRASHEDSTATE=0. All mode walks originals that can retain Recently Deleted files. If a classified receipt is deleted in Photos, remains in originals, and the catalog is refreshed, its path can disappear from the exclusion list and become eligible again. PRODUCT’s screenshot/document/receipt decision does not exempt deleted items.

Suggested fix: retain classification exclusions for any original the filesystem walk can encounter, regardless of trash state. Bump the catalog version when changing the query so existing exclusions rebuild. Add a SQL integration fixture with a classified trashed asset and a retained original.

Spec disposition: change code to implement the broad product decision, or explicitly record a narrower decision if that was intended. Presence of a particular deleted original was not checked during this review.

### B08 — P2: catalog and cover caches are not associated with their SMB source

Locations: [SmbGuestPhotoSource.kt:224](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/SmbGuestPhotoSource.kt:224), [AlbumCoverRules.kt:23](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/AlbumCoverRules.kt:23).

Changing host, share, or library path still reuses the same catalog file. A cache hit occurs before the originals-root validation. Cover keys also omit source identity. Switching libraries can show old metadata/covers and resolve old relative paths against the new library.

Suggested fix: bind catalog, stage state, cover cache, and selection context to a private source identifier. Reject mismatched catalogs before returning them. Do not log the underlying identifying configuration.

Spec disposition: change code.

### B09 — P2: video Type selection bypasses the catalog-readiness flow

Locations: [MainActivity.kt:238](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/MainActivity.kt:238), [SmbGuestPhotoSource.kt:294](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/SmbGuestPhotoSource.kt:294).

Type changes immediately, and Save can persist it without a valid catalog. Preview then starts a potentially long database build behind a generic slideshow loading screen. Transport failures during that build become AlbumIndexException, which the player treats as terminal. Selected-album Dream startup can also build a missing/obsolete catalog, hold the process-wide staging lock, and end on an error rather than retry/artworks. Historical warm refreshes were tens of seconds (v5: 63.8 seconds); a full staged transfer was much longer. These are recorded measurements, not a new startup benchmark.

Suggested fix: use the specified catalog progress/retry flow before committing a Type that includes videos. Keep that flow independent of album-mode activation so Media All remains All. Preserve transport-error classification when background catalog work fails. Decide explicitly whether Dream may rebuild or should require refresh in the app; if it rebuilds, provide appropriate retry/cancellation. The phrase “next picker open rebuilds it” alone does not expressly prohibit other callers.

Spec disposition: change code to match MEDIA_TYPE.

### B10 — P2: failed refresh preserves the cache file but loses the usable picker

Location: [MainActivity.kt:470](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/MainActivity.kt:470).

Refresh replaces the carousel with loading UI, then every failure leads to a full-screen error even when a valid old catalog remains. The file is preserved correctly; the specified cached-carousel-plus-inline-error behavior is absent.

Suggested fix: retain or reload the prior index and display its carousel with the refresh error and retry action.

Spec disposition: change code to match ALBUM_SELECTION.

### B11 — P2: one empty selected album blocks nonempty selected albums

Location: [SmbGuestPhotoSource.kt:362](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/SmbGuestPhotoSource.kt:362).

With Type Photos, a selected video-only album causes the entire slideshow to fail even if another selected album has hundreds of usable photos. The picker allows the empty album and already explains its count.

Suggested fix: build the usable union and fail only if the union is empty. Keep the empty album selected and explained in the picker.

Spec disposition: change code to honor the usable selected union; the spec should explicitly state whether an empty member may block other albums if a stricter policy is intended.

### B12 — P2: pre-first-frame video errors lose retry classification; failure streaks reset on start

Locations: [VideoSurfaceStage.kt:79](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/VideoSurfaceStage.kt:79), [SlideshowPlayer.kt:447](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/SlideshowPlayer.kt:447).

A player error before rendering is classified UNPLAYABLE solely because `started` is false. A network read failure or timeout can therefore permanently drop a playable clip. Also, every first rendered frame resets the streak: clips that each start then stall never accumulate three consecutive failures. Both behaviors are visible in code; whether a partial playback counts as a successful recovery needs a clarified product rule.

Suggested fix: expose the actual reader/error classification and distinguish transient transport failure from corrupt/unsupported media. Do not classify every error during an incomplete download as retryable: unsupported files can fail before download finishes too. Define a video success boundary, then implement and test failure streaks consistently, including repeated post-start stalls.

Spec disposition: change code for cause-aware retries; clarify success/failure accounting in MEDIA_TYPE and reconcile its broad wording with deliberately uncounted per-file photo failures.

### B13 — P2: leaving Preview omits executor disposal

Location: [MainActivity.kt:949](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/MainActivity.kt:949).

Back calls stop and drops the player reference. Its executors are explicitly shut down only by dispose, which can no longer be called for that player. Repeated previews depend on garbage collection/finalization for cleanup. The Android 12 single-thread executor wrapper shuts down its delegate when finalized, and threads are created lazily. “Two permanent threads per Preview” is therefore unsupported; the fallback executor may never start a thread. See [Android 12 Executors implementation](https://android.googlesource.com/platform/libcore/+/refs/tags/android-12.0.0_r1/ojluni/src/main/java/java/util/concurrent/Executors.java).

Suggested fix: dispose the player when permanently leaving Preview. Reserve stop for a player that will resume.

This is a verified missing cleanup call. Permanent activity retention and a permanent thread leak were not established; those would require runtime evidence. It is a Kotlin/Android resource-ownership issue, not a requirement to adopt coroutines.

### B14 — P2: zero-length video reads incorrectly report EOF

Location: [ProgressiveReader.kt:229](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/ProgressiveReader.kt:229).

A request with size zero returns -1, and the JVM test expects that. The Android contract requires zero. A demuxer issuing a zero-length read can receive a false EOF.

Suggested fix: return zero for zero-sized reads before checking EOF; correct the test. Actual use of zero-sized probes on this TV was not measured. Source: [MediaDataSource.readAt](https://developer.android.com/reference/android/media/MediaDataSource#readAt(long,%20byte[],%20int,%20int)).

### B15 — P2: exception paths leak native resources or a file handle

Locations: [heif_tile_probe.cpp:104](/Users/dev/dev/googletv/tools/heif_tile_prototype/heif_tile_probe.cpp:104), [ProgressiveReader.kt:149](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/ProgressiveReader.kt:149).

A C++ allocation exception after context/handle creation bypasses their manual releases; the JNI catch cannot recover those pointers. If RandomAccessFile.setLength throws, the file has not yet been assigned to the reader, so subsequent close cannot close it.

Suggested fix: give context, image handle, decoding options, and tile objects RAII ownership. Keep the local file under exception-safe ownership until reader initialization succeeds.


### B16 — P2: unsaved settings and navigation are lost on activity recreation

Locations: [MainActivity.kt:73](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/MainActivity.kt:73), [MainActivity.kt:327](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/MainActivity.kt:327), [MainActivity.kt:392](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/MainActivity.kt:392).

Added in the Android/Kotlin best-practices pass. onCreate always restores persisted preferences, ignores savedInstanceState, and creates fields without stable view IDs. There is no explicit state save for the staged draft, active screen, carousel position, or focused control. A locale/font-scale recreation or process recreation after backgrounding discards unsaved edits and returns to the initial screen. Rotation is not a typical trigger on this TV; background process recreation is the more realistic case. The code gap is confirmed; a device incident was not reproduced.

Suggested fix: preserve the small settings draft and navigation/focus identifiers in saved state without committing the draft. Restore from that state before preferences. A ViewModel may help retained work, but it does not replace saved state for process death; a Bundle-based solution is sufficient for a small app. Source: [Android configuration changes](https://developer.android.com/guide/topics/resources/runtime-changes).

### B17 — P2: current setting choices have no accessible selected-state semantics

Locations: [MainActivity.kt:300](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/MainActivity.kt:300), [MainActivity.kt:183](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/MainActivity.kt:183).

Added in the Android TV pass. The settings mark function changes only button tint. It never exposes selected/checked state or a state description. A TalkBack user navigating the Type, Layout, or Interval choices has no programmatic indication of the saved/draft choice. SMB field headings also have no labelFor association with stable field IDs.

Suggested fix: expose selection through appropriate accessibility node state and group labels, announce changes where needed, and associate persistent labels with the text fields. Validate with the TV’s TalkBack. The album cards already include selected/not-selected words in their descriptions, so the finding does not claim that every selection is unlabeled. Source: [TalkBack support for TV](https://developer.android.com/training/tv/accessibility/talkback-support).

### B18 — P2: the launcher banner is an unlabelled slideshow artwork

Location: [AndroidManifest.xml:8](/Users/dev/dev/googletv/apps/screensaver/app/src/main/AndroidManifest.xml:8).

Added in the Android TV pass. The banner references the full 3840-by-2088 Last Supper artwork in drawable-nodpi; visual inspection confirms it has no application-identifying text. Official TV guidance calls for a 320-by-180 xhdpi banner containing text. This makes the app harder to identify in launchers that present banners and is an asset-guidance mismatch, not proof of an installation failure.

Suggested fix: supply a dedicated TV banner with a clear app name and the documented dimensions/density, retaining the artwork separately for the slideshow. Source: [Create a TV app — launcher icon and banner](https://developer.android.com/training/tv/get-started/create).

## Risks requiring further validation

### R01 — P1: WAL checksum rejection is not necessarily a refresh exception

Locations: [WalFrameScanner.kt:96](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/WalFrameScanner.kt:96), [DatabaseStage.kt:201](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/DatabaseStage.kt:201).

The scanner records commit boundaries without validating cumulative checksums. SQLite may read the earlier valid prefix rather than throw. A damaged/torn copy could thus generate a successful stale catalog, while the recorded resume boundary and unchanged shortcut perpetuate it. Matching header/salts and modification times also do not establish a coherent snapshot while CCC replaces files.

Suggested fix: verify checksums and SQLite’s accepted committed boundary before publishing sync state/catalog; strengthen post-copy snapshot verification. Treat this as the documented live-CCC consistency gap, not as a proven corruption incident. Source: [SQLite WAL validity and reader rules](https://sqlite.org/fileformat2.html#wal_file_format).

### R02 — P2: the cover LRU does not bound bitmaps retained by attached cards

Locations: [MainActivity.kt:611](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/MainActivity.kt:611), [MainActivity.kt:850](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/MainActivity.kt:850).

Every card remains attached, and a visited card's ImageView keeps its bitmap after eviction from the 32-entry cache. Scrolling through hundreds of albums can retain hundreds of covers. Covers load on focus/prefetch, not all at initial construction. The prior 480×300-per-cover estimate overstated typical photo cover sizes; for scale, a 252×189 ARGB bitmap is about 186 KiB, so 759 such bitmaps would be about 138 MiB before other allocations. This is an illustration, not measured retention or a prediction that every cover is visited.

On API 31 bitmap pixels occupy native memory; the 192 MB managed heap limit is not a direct bitmap budget. Process/native pressure remains relevant, but no TV out-of-memory event was reproduced. The persistent cache's 96-file/32-MB disk limit does not bound live ImageViews.

Suggested fix: recycle/virtualize cards or detach offscreen drawables with explicit bitmap ownership shared with a bounded cache. Never recycle a bitmap still displayed. Measure managed heap, native allocations, and PSS during large-picker navigation. See [Android bitmap memory guidance](https://developer.android.com/topic/performance/graphics/manage-memory).

### R03 — P2: generation checks stop presentation more thoroughly than source work

Locations: [SlideshowPlayer.kt:357](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/SlideshowPlayer.kt:357), [SmbGuestPhotoSource.kt:548](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/SmbGuestPhotoSource.kt:548), [MainActivity.kt:822](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/MainActivity.kt:822).

A pair job can begin its second decode after stop. Queued cover jobs check their token only after loading/writing, so they can reopen a source closed by picker exit. The refresh source is local to its runnable, preventing picker exit from aborting that transfer. After `close()` advances the source epoch, a later second decode or queued cover job starts with the new epoch and may establish a new connection. If its owner has already discarded the source, no later close is guaranteed. This is a concrete leak-capable interleaving, not a measured persistent session on the server. Concurrent calls can also both see no connection, open independently, and overwrite the retained reference; normal current callers mostly serialize operations, so that separate race remains latent. A single `pending` reference can also be overwritten by a video job queued behind a preload, and an older callback can clear it.

Suggested fix: give each operation an owner/token checked before each new network/decode phase; retain the active source so cancellation can abort it. Make the intended source concurrency contract explicit and test close/open/retry interleavings. Track queued/running tasks without allowing old callbacks to clear newer ownership. Preserve the progressive reader’s per-clip abort mechanism. `pending.cancel(true)` interrupts the photo loader, not the dedicated progressive reader; it is not by itself a literal violation of MEDIA_TYPE’s narrower reader-thread rule, nor is interruption automatically harmless.

### R04 — P2: the native HEIF offset heuristic proves coverage, not alignment

Location: [heif_tile_probe.cpp:38](/Users/dev/dev/googletv/tools/heif_tile_prototype/heif_tile_probe.cpp:38).

An incorrect offset that still covers the image is retained. Under the observed offset-complement quirk, let padding be `count × tile − image`: when padding is greater than half a tile, the wrong reported value can pass the coverage test, shifting content by `2 × padding − tile`. At exactly half, the values coincide. This establishes the conditional arithmetic, not a failing camera file. Documented tested images passed; broader checking was explicitly waived in the work order.

Suggested fix: derive offsets from transformation/crop semantics and add synthetic pixel-reference cases before expanding correctness claims. Simply accepting only zero or computed padding can reject legitimate clean-aperture cropping and is not a general fix. No claim is made that the verified examples are wrong.

### R05 — P2: video cleanup can block the UI or terminate before local cleanup

Locations: [VideoSurfaceStage.kt:149](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/VideoSurfaceStage.kt:149), [ProgressiveReader.kt:357](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/ProgressiveReader.kt:357).

Video finish/cancel calls close on the main thread, including a join of up to three seconds. Slow SMB abort can freeze Back or a transition; a three-second wait does not occur on every close, only when the reader fails to exit promptly. An interrupted join bypasses subsequent channel/file deletion because that cleanup is not protected by finally.

Suggested fix: signal cancellation immediately, perform joining off-main, and guarantee local cleanup even when waiting is interrupted. Make close fully idempotent, including recording a clip’s rate at most once.

### R06 — P2: crashes can leave full private image temp files behind

Locations: [PhotoSource.kt:212](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/PhotoSource.kt:212), [PhotoSource.kt:275](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/PhotoSource.kt:275).

Normal completion removes HEIF/ImageDecoder temp files. Crash leftovers have no equivalent of the video-cache stale purge, and no explicit bounded retention policy. Android may evict cache files, so “remain indefinitely” is too strong; neither a leftover nor a likely heap-related process kill was established on the TV.

Suggested fix: isolate image temporaries in a dedicated directory, purge abandoned files when no decoder owns them, and enforce a size/age policy. No leftover private file was searched for on the TV.

### R07 — P2: preparation and first-frame phases lack a watchdog

Location: [VideoSurfaceStage.kt:65](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/VideoSurfaceStage.kt:65).

The monitor starts after onPrepared. A platform preparation hang has no stage deadline. A prepared player that never reports rendering start also lacks a dedicated first-frame deadline. The reader already has a ten-second missing-data timeout: this risk concerns platform/player hangs that do not progress to a timed-out read, not all network waits.

Suggested fix: add bounded preparation/first-frame states and classify timeouts as retryable where appropriate. Whether this contributes to the recorded black screens is unknown.

### R08 — P2: broad Kotlin exception capture can hide cancellation and fatal errors

Locations: [PhotoSource.kt:107](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/PhotoSource.kt:107), [PhotosAlbumCatalog.kt:40](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/PhotosAlbumCatalog.kt:40), [SlideshowPlayer.kt:236](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/SlideshowPlayer.kt:236).

Added in the Kotlin pass. runCatching catches every Throwable. Here, metadata failures can silently become default orientation, catalog failures can become “no usable catalog,” and worker failures can become ordinary skip/retry events. That includes interruption/cancellation and VM/resource errors that deserve different handling. This broad handling contributes to R03 and can obscure the original cause under memory pressure.

Suggested fix: catch expected I/O/format exceptions, propagate cancellation, and preserve/handle thread interruption deliberately. Keep narrowly justified recovery—such as a decorative background failing—separate from blanket recovery. If coroutines are introduced later, never consume CancellationException. An executor-based implementation can apply the same ownership discipline today. Sources: [Kotlin runCatching contract](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/run-catching.html), [Android coroutine exception practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices).

### R09 — P2: fixed settings geometry has unverified text-scale and safe-area behavior

Locations: [MainActivity.kt:128](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/MainActivity.kt:128), [MainActivity.kt:284](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/MainActivity.kt:284).

Added in the Android TV pass. Settings use fixed 36 dp button heights, 44 dp rows, sp text, and a non-scrolling vertical layout; the root has only 14/18 dp top/bottom padding. Device screenshots establish fit for the tested configuration, not larger font scales or overscan modes. Larger text can clip inside fixed rows or push actions offscreen.

Suggested fix: permit content-driven height and accessible vertical scrolling, retain reachable actions, and check safe areas/focus outlines at supported text scales. Do not apply overscan padding to the artwork itself. Actual clipping on the TCL was not reproduced. Source: [Android TV accessibility and text scaling](https://developer.android.com/training/tv/accessibility).

### R10 — P2: focus restoration and IME/Back interaction need explicit validation

Locations: [MainActivity.kt:109](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/MainActivity.kt:109), [MainActivity.kt:796](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/MainActivity.kt:796), [MainActivity.kt:949](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/MainActivity.kt:949).

Added in the Android TV pass. The app supplies initial/card focus and several return targets, but leavePreview does not explicitly restore the invoking control. The Sort click handler searches for a focused card while focus is on the Sort button, so it cannot remember the last card that way. Raw Back ACTION_UP interception needs checking with an open TV keyboard and accessibility navigation.

Suggested fix: track focused control/album identity independently, restore it after screen swaps, and test all directional paths plus keyboard dismissal followed by Back. Use explicit directional overrides only for demonstrated failures of Android’s default navigation. Do not assume that lack of nextFocus attributes is itself a bug. No focus trap or IME failure is claimed as reproduced. Source: [Android TV navigation](https://developer.android.com/training/tv/get-started/navigation).

### R11 — P2: JSON version rejection does not establish source-schema compatibility

Locations: [PhotosAlbumCatalog.kt:48](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/PhotosAlbumCatalog.kt:48), [PhotosAlbumCatalog.kt:109](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/PhotosAlbumCatalog.kt:109).

Catalog v5 rejection is implemented correctly. However, the Photos source uses private entity/table semantics, including entity 34, without a supported source-model fingerprint. A future schema that remains syntactically queryable but changes entity meaning could produce an empty/incomplete “successful” catalog.

Suggested fix: record and validate the observed source-schema/model identity and add semantic fixtures for supported versions. Preserve the old catalog on unsupported versions. Dynamically discovering the entity number from `Z_PRIMARYKEY` alone would not establish compatibility of all private membership-table semantics. This is an unresolved provider risk, not a claim that the current clone is unsupported.

### R12 — P2: per-file failures unnecessarily discard reusable SMB connections

Location: [SmbGuestPhotoSource.kt:564](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/SmbGuestPhotoSource.kt:564).

`withDiskShare` discards the connection on every throwable, including per-file denial or decode failure. A run of refused files can renegotiate for each file/sample instead of reusing a healthy transport. The branch is confirmed; the reconnect latency during an actual outage is unmeasured and should not be presented as a fixed one second per file.

Suggested fix: retain healthy connections for classified per-file failures, discard transport/session failures, and preserve cancellation/epoch ownership. Test denial followed by a readable file and a stale-connection retry.

### R13 — P2: native downscaling can alias and duplicates output buffers

Locations: [heif_tile_probe.cpp:158](/Users/dev/dev/googletv/tools/heif_tile_prototype/heif_tile_probe.cpp:158), [heif_tile_probe.cpp:229](/Users/dev/dev/googletv/tools/heif_tile_prototype/heif_tile_probe.cpp:229), [NativeHeifDecoder.kt:16](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/NativeHeifDecoder.kt:16).

Scaling maps successive source pixels directly into destination pixels without area filtering. Fine textures can alias during strong downscaling; visual severity was not measured. JNI also holds a full-screen native vector and output bitmap at once, followed by a Kotlin crop when needed.

The earlier sum of vector + full bitmap + crop as one simultaneous peak is incorrect: the vector is destroyed when JNI returns, before crop allocation. At 1920×1080 ARGB, the two output buffers total about 16.6 MB during JNI; a full bitmap plus a 3.5 MB crop totals about 11.8 MB during cropping. Libheif/tile allocations and current/preloaded slides are additional. These are buffer calculations, not total measured decoder/process peaks or Java-heap usage.

Suggested fix: add pixel-reference downscale tests and use a bounded filter suitable for tile boundaries. Consider decoding directly to content-sized bitmap storage to reduce copying, with an explicit JNI contract/ownership redesign. RAII exception safety is covered by B15.

### R14 — P2: uncommitted WAL tails can defeat the unchanged shortcut

Locations: [DatabaseStage.kt:40](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/DatabaseStage.kt:40), [SmbGuestPhotoSource.kt:260](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/SmbGuestPhotoSource.kt:260).

When an uncommitted remote tail is fetched but not retained, every refresh can fetch it again. `unchanged` depends on fetched bytes, and the caller additionally compares source modification time, so the expensive query may repeat even without a new committed transaction.

Suggested fix: base query reuse on a validated committed snapshot identity, while retaining truthful refresh/source timestamps. Comparing committed lengths alone is insufficient: equal lengths can belong to different WAL generations or contents. Add same-length replacement and repeated-uncommitted-tail tests.

### R15 — P2: album-photo queries do not establish trash/hidden eligibility

Location: [PhotosAlbumCatalog.kt:109](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/PhotosAlbumCatalog.kt:109).

The album filter excludes trashed albums but does not filter member photo assets by trash/hidden state. Album videos are resolved against a separately filtered library list. If Photos retains trashed memberships, those photos may remain eligible. The actual membership behavior was not checked; do not label this a demonstrated deleted-photo display incident. PRODUCT explicitly accepts hidden/recently deleted files in All, which does not itself decide the intended album semantics.

Suggested fix: validate retained memberships against a supported offline database fixture or an authorized database inspection, decide album eligibility explicitly, and align photo/video queries with that rule. Do not broaden selection silently.

## Kotlin and Android TV development assessment

### Practices that fit this app

- Pure Kotlin planning, fitting, label, and mapping helpers make many rules independently testable. Local use of use/finally generally makes normal-path stream and temporary-file ownership clear.
- Expensive SMB reads and image decoding are generally off the UI thread. Player generations reject stale results and recycle rejected prepared content. The main gaps are cancellation before side effects, deterministic disposal, and exception-path ownership.
- The activity and DreamService share a renderer/player, and the dream does not launch sign-in UI. Explicit Guest authentication and read-only handles fit the repository constraints.
- The manifest declares LEANBACK_LAUNCHER, leanback support, no touchscreen requirement, and BIND_DREAM_SERVICE protection. The screensaver path supplies photos-only playback; no MediaPlayer is supplied to the dream.
- Default D-pad navigation, clear custom focus outlines, full-card click targets, and explicit picker Back handling are appropriate foundations. The device log remains the evidence for tested remote behavior.
- Normal stale-result rejection/recycling, all eight EXIF transforms, atomic catalog writes with fsync, progressive tail-first fetching, and the free-space guard are supported by inspection. These checks do not resolve the exception/cancellation/snapshot findings above or prove all cleanup paths safe.
- Gradle 9.4.1, compile SDK 37, and the pinned NDK match the AGP 9.2 compatibility table. The Gradle distribution has a SHA-256 checksum. AGP 9 supports built-in Kotlin, so absence of a separate org.jetbrains.kotlin.android plugin is not a defect. Sources: [AGP 9.2 compatibility](https://developer.android.com/build/releases/agp-9-2-0-release-notes), [built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin).

## Cleanup

### C01 — P3: diagnostic output and implicit output destinations need safer defaults

Locations: [check_smb_source.py:65](/Users/dev/dev/googletv/tools/check_smb_source.py:65), [heif_tile_probe.cpp:336](/Users/dev/dev/googletv/tools/heif_tile_prototype/heif_tile_probe.cpp:336), [SmbAccessDiagnostic.kt:55](/Users/dev/dev/googletv/tools/smb_access_probe/SmbAccessDiagnostic.kt:55).

The checker prints host/share and raw server identity; the host HEIF harness prints an input-derived path and writes `<input>.ppm` beside its input. Captured tool output can disclose private identifiers, and choosing a source-library input can attempt an adjacent write. These are local tooling behaviors, not a production SMB mutation finding; a write through a read-only mount would fail. The harness example uses a generic local sample path. Treat the logging issue as repository-guidance nonconformance and the output policy as a risky default, rather than claiming it has modified the share.

The access diagnostic requests `MAXIMUM_ALLOWED` plus specific read rights; the assertion that every Kotlin tool requests only `GENERIC_READ` was incorrect. No write operation was found, but that requested access mask is broader than a strictly read-only mask.

Suggested fix: redact identifiers by default, require a deliberate output destination outside source data, and request only necessary read rights in diagnostics. No tool was run against a device/share for this review.

### C02 — P3: production native code is coupled to a prototype directory

Location: [app/build.gradle.kts:56](/Users/dev/dev/googletv/apps/screensaver/app/build.gradle.kts:56).

The app CMake path points into `tools/heif_tile_prototype`, so editing a diagnostic prototype changes production decoding. This is coupling and ownership ambiguity, not an unpinned dependency finding.

Suggested fix: establish a clearly owned production native module with explicit diagnostic consumers, or clearly document the shared production role before further prototype changes.

### C03 — P3: isolate state and scheduling to make real failure transitions testable

Locations: [SlideshowPlayer.kt:40](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/SlideshowPlayer.kt:40), [SlideshowScheduler.kt:7](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/SlideshowScheduler.kt:7), [MainActivity.kt:508](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/MainActivity.kt:508).

The player constructs its own executors/Handler and directly reads SystemClock; the scheduler accepts an Android Handler rather than a plain test scheduler. This makes the failure transitions in B01/B12 difficult to test on the JVM. MainActivity also owns catalog work, draft state, selection counting, hundreds of card views, and cache ownership. Building the whole carousel and repeated full-selection counting on main can make D-pad response slow as libraries grow; current timing was not measured.

Suggested cleanup: inject a small clock/task/timer interface, model playback transitions explicitly, and extract picker state plus catalog/cache ownership from Activity. Use immutable snapshots for worker input. This should enable deterministic tests and bound UI work; introducing a library or replacing the working View renderer is optional.

Do not make framework migrations solely to satisfy a best-practices label. Current official TV guidance recommends Compose for new TV UI, but the existing Views implementation is a valid engineering choice for this personal app and its DreamService. Similarly, a general media app’s transport controls or background audio policy should not override the decided muted, control-free, foreground slideshow behavior.

## Spec conformance and documentation divergences

The bugs above identify behavior that should change. The following table covers additional divergences and stale claims. Existing historical test rows should remain historical; current summaries/statuses should be reconciled with the latest measured findings.

| Location | Divergence, scenario, and disposition |
| --- | --- |
| [ALBUM_SELECTION.md:11](/Users/dev/dev/googletv/docs/ALBUM_SELECTION.md:11), [PRODUCT.md:10](/Users/dev/dev/googletv/docs/PRODUCT.md:10) | Settings summaries omit Type/Layout; ALBUM_SELECTION still calls Media “Photos.” Update the summaries to the implemented settings; later product decisions already describe the additions. The prior PRODUCT:54 citation was invalid. |
| [MainActivity.kt:333](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/MainActivity.kt:333), [ROADMAP.md:66](/Users/dev/dev/googletv/docs/ROADMAP.md:66) | DATE_ONLY remains supported by preferences/rendering but is absent from choices; restoring the draft maps it to Location + date, which a later Save persists. Decide an explicit legacy migration/compatibility policy and align docs/UI. Do not remove the enum as “dead” without addressing persisted values. ROADMAP also retains closed Date-only/Auto Launch questions. |
| [MULTI_IMAGE.md:180](/Users/dev/dev/googletv/docs/MULTI_IMAGE.md:180), [ARCHITECTURE.md:18](/Users/dev/dev/googletv/docs/ARCHITECTURE.md:18) | Layout fit, EXIF orientation, and tested HEIF support still appear open despite later device results. Update current summaries to the tested scope, retaining the waived broader HEIF check. |
| [ALBUM_SELECTION.md:24](/Users/dev/dev/googletv/docs/ALBUM_SELECTION.md:24) | Current schema is described as v4. Update the doc to v5 with portrait; retain v2–v4 as history. Phase-1 video exclusions at lines 7/27 need explicit cross-references to the now-implemented MEDIA_TYPE extension. |
| [ALBUM_SELECTION.md:28](/Users/dev/dev/googletv/docs/ALBUM_SELECTION.md:28) | Claims every reference is checked for existence/readability during catalog validation. Builder validates path syntax/type; startup samples eight photos. Update doc to the intentionally implemented sampling behavior and distinguish candidate counts from verified-readable counts. |
| [ALBUM_SELECTION.md:18](/Users/dev/dev/googletv/docs/ALBUM_SELECTION.md:18) | Failed refresh should show old catalog plus inline error. Change code as B10; do not weaken the preservation requirement merely to match the UI. |
| [MEDIA_TYPE.md:36](/Users/dev/dev/googletv/docs/MEDIA_TYPE.md:36) | Catalog-ready Type selection is missing. Change code as B09. |
| [MEDIA_TYPE.md:39](/Users/dev/dev/googletv/docs/MEDIA_TYPE.md:39) | Required “Videos play to the end, up to 60 s” Interval note is absent. Add the note in code. |
| [MEDIA_TYPE.md:51](/Users/dev/dev/googletv/docs/MEDIA_TYPE.md:51) | “Does not read the video list” is stronger than implementation: shared JSON parsing reads video lists even for Photos. Update doc to “never opens/prepares/plays video files,” or implement a photo-only catalog read if literal metadata avoidance matters. |
| [MEDIA_TYPE.md:47](/Users/dev/dev/googletv/docs/MEDIA_TYPE.md:47) | Poster loading has a minimum head/tail readiness gate, not a three-head-block transfer ceiling. Reader continues forward until closed. Update doc; the observed partial fetch remains valid. |
| [MEDIA_TYPE.md:70](/Users/dev/dev/googletv/docs/MEDIA_TYPE.md:70) | “Failures of any kind” conflicts with deliberately uncounted per-file photo failures documented in MULTI_IMAGE. Update photo wording; fix video accounting as B12. |
| [PairPlanner.kt:96](/Users/dev/dev/googletv/apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/PairPlanner.kt:96) | “Never the same style twice” fails for a playlist with only one pair looping; removing failed pairs can also create matching neighbors. Change code to enforce the decision across actual presentations, or document an explicitly approved exception. Keep the appearance fixed while a slide is visible. |
| [MULTI_IMAGE.md:60](/Users/dev/dev/googletv/docs/MULTI_IMAGE.md:60) | “Pool counts photos” needs qualification for mixed media: the Type spec counts photos and videos together before pairing. Update doc to “items before pairing; pairing reduces slide count.” |
| [ARCHITECTURE.md:13](/Users/dev/dev/googletv/docs/ARCHITECTURE.md:13) | Package/toolkit/SDK choices remain Open. Update doc to implemented choices, including built-in Kotlin. |
| [ARCHITECTURE.md:27](/Users/dev/dev/googletv/docs/ARCHITECTURE.md:27) | Plain-Kotlin injectable-clock controller and persistent All-mode index describe future architecture. Mark the abstraction/index work as deferred; keep ROADMAP’s current work order authoritative. |
| [ARCHITECTURE.md:44](/Users/dev/dev/googletv/docs/ARCHITECTURE.md:44), [PRODUCT.md:15](/Users/dev/dev/googletv/docs/PRODUCT.md:15), [AGENTS.md:12](/Users/dev/dev/googletv/AGENTS.md:12) | Auto Launch requirement or unknown-activation claims conflict with this firmware’s measured behavior: no control found, none needed. Update current guidance while retaining the firmware-specific qualification. |
| [DEVICE_TESTING.md:74](/Users/dev/dev/googletv/docs/DEVICE_TESTING.md:74), [DEVICE_TESTING.md:128](/Users/dev/dev/googletv/docs/DEVICE_TESTING.md:128) | Step 4 contradicts itself: its status says the full ten-minute wait was observed, but its text says it was not observed end to end. Step 7 still calls Date-only rendering pending despite later results. Reconcile current summaries with the latest measured entries; preserve historical rows. |
| [PHOTO_SOURCES.md:37](/Users/dev/dev/googletv/docs/PHOTO_SOURCES.md:37), [ALBUM_SELECTION.md:26](/Users/dev/dev/googletv/docs/ALBUM_SELECTION.md:26), [ROADMAP.md:80](/Users/dev/dev/googletv/docs/ROADMAP.md:80) | Refresh timings and CCC UUID continuity still appear unmeasured despite later measurements and one continuity check. Update summaries, retaining live-copy consistency and title-edit gaps. |
| [MEDIA_TYPE.md:3](/Users/dev/dev/googletv/docs/MEDIA_TYPE.md:3), [MULTI_IMAGE.md:3](/Users/dev/dev/googletv/docs/MULTI_IMAGE.md:3), [ROADMAP.md:17](/Users/dev/dev/googletv/docs/ROADMAP.md:17) | “Not implemented,” “in progress,” resolved MULTI_IMAGE questions, and four-up “deferred” conflict with completed/accepted work and cancellation. Update those summaries. MEDIA_TYPE’s still-open decision confirmations remain open in ROADMAP; implementation completion alone does not resolve them. |
| [apps/screensaver/README.md:13](/Users/dev/dev/googletv/apps/screensaver/README.md:13) | Broken device-test-log anchor; use device-findings. README also describes obsolete samples/failure handling/verification gaps and an unqualified lint command known to fail the diagnostic module. Update doc. |
| [MULTI_IMAGE.md:136](/Users/dev/dev/googletv/docs/MULTI_IMAGE.md:136) | Bitmap memory is described solely as Java heap. On Android 8 and later, bitmap pixels are stored in native heap. Update wording to discuss total bitmap/native/process memory and avoid equating PSS with the 192 MB managed heap limit. See [Android bitmap memory guidance](https://developer.android.com/topic/performance/graphics/manage-memory). |

The core v5 version rejection, UUID album identity, ordinary traversal rejection, exclusion thresholds, portrait orientation band, pair geometry, 0.6 probability, per-photo labels, video muting/cap, and factory’s 500-photo dream fallback otherwise match the reviewed rules. Dream receives no MediaPlayer/video stage and does not play video files, although shared catalog parsing/building still reads video metadata. Equivalent Type-note wording is not a behavioral divergence. Syntax validation is not proof of filesystem/symlink containment or future source-schema semantics. Source-schema compatibility remains distinct from JSON version validation (R11).

## Tests and verification

The current existing XML reports contain 199 tests across 22 suites, with zero failures/errors/skips, timestamped October 19, 2026, 04:24:11–04:24:17 UTC. Claude reports running the following command successfully; the XML corroborates the totals but does not prove its exact command flags. Codex inspected results and did not rerun tests during review/verification/collation.

```sh
cd /Users/dev/dev/googletv/apps/screensaver
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew --offline :app:testDebugUnitTest
```

The older timestamps in the original Codex report refer to previously inspected output and are not the latest available results. Passing helper tests do not validate the orchestration gaps below.

### Important missing coverage

- No tests drive SlideshowPlayer orchestration, generations, scheduler interaction, callback ordering, pool exhaustion, fallback recovery, or disposal.
- Interrupted incremental append, short EOF, missing/replaced WAL, checksum failure, and same-length/same-generation rewrite scenarios need distinct tests; current bookkeeping fixtures do not establish real SQLite recovery.
- Android SQLite SQL is not executed by mapper tests. Add supported-schema fixtures, classification/trash cases, actual WAL recovery, and refresh preservation.
- No automated native pixel-reference tests cover tile offsets, transforms, crop output, or allocation-failure ownership.
- No tests establish cover retention limits, saved-draft restoration, accessible selection state, D-pad focus/Back behavior, font scaling, or TV IME interaction.
- No tests exercise actual SMB connection reuse/close races or MediaPlayer preparation/failure semantics.
- Add cleanup tests with an abort that stays blocked, an interrupted closer, allocation/storage failure after file open, and stale image/video cache files.

### Weak or misleading tests

| Location | Weakness and stronger check |
| --- | --- |
| [DatabaseStageTest.kt:21](/Users/dev/dev/googletv/apps/screensaver/app/src/test/java/com/googletv/screensaver/playback/DatabaseStageTest.kt:21), [WalTestSupport.kt:19](/Users/dev/dev/googletv/apps/screensaver/app/src/test/java/com/googletv/screensaver/playback/WalTestSupport.kt:19) | Fake database bytes and checksum-free WALs test bookkeeping, not SQLite compatibility/recovery. Add real SQLite-generated fixtures and corruption/truncation cases. |
| [PathSetTest.kt:35](/Users/dev/dev/googletv/apps/screensaver/app/src/test/java/com/googletv/screensaver/playback/PathSetTest.kt:35) | “Outside root” test contains only short strings. The implementation strips by root length without validating the prefix; an unrelated same-length root can match. Current callers construct rooted paths, so this is a helper-contract/test gap rather than a demonstrated path escape. Add equal-length/longer unrelated roots and validate the prefix. |
| [AlbumCatalogMapperTest.kt:89](/Users/dev/dev/googletv/apps/screensaver/app/src/test/java/com/googletv/screensaver/playback/AlbumCatalogMapperTest.kt:89) | Inputs and expected count both come from the production extension set. Removing a required format still passes. Assert the spec’s independent literal allowlist. |
| [SmbErrorsTest.kt:16](/Users/dev/dev/googletv/apps/screensaver/app/src/test/java/com/googletv/screensaver/playback/SmbErrorsTest.kt:16) | Inheritance check does not establish the player retry behavior claimed by its name. Drive a failed listing through a test scheduler and assert retry/backoff/fallback. |
| [ProgressiveReaderTest.kt:119](/Users/dev/dev/googletv/apps/screensaver/app/src/test/java/com/googletv/screensaver/playback/ProgressiveReaderTest.kt:119) | Explicitly enshrines the wrong zero-length read result. Assert the platform contract instead. |
| [ProgressiveReaderTest.kt:258](/Users/dev/dev/googletv/apps/screensaver/app/src/test/java/com/googletv/screensaver/playback/ProgressiveReaderTest.kt:258) | Close uses sleeps and an immediately unblocking fake abort; it does not establish real SMB shutdown behavior. Use latches to ensure a read has started and deliberately gate abort/completion; verify interruption-safe cleanup separately. |
| [PairFailureRulesTest.kt:17](/Users/dev/dev/googletv/apps/screensaver/app/src/test/java/com/googletv/screensaver/playback/PairFailureRulesTest.kt:17) | Useful helper checks do not verify ownership, preload timing, failure streaks, or behavior after the live player changes the pool. Add player integration tests alongside them. |

The claim that `containsSmbPathIsTheSameLookupUnderAnotherName` is tautological is rejected: it has independent positive/negative expectations. The two-choice Layout test also protects a deliberate product contract. Their limited scope does not make them useless. No blanket criticism is made of round-trip, enum/default, or seeded-random tests: these protect real contracts. They simply do not substitute for lifecycle and integration coverage.

### Commands and checks performed

Both reviews used source/doc inspection and reachable Git history. Codex additionally checked local Markdown targets, existing XML, bounded secret patterns, historical image GPS markers, in-memory arithmetic, and official API/platform sources. During collation, source locations and test totals were rechecked; only this Markdown report was created. No fresh runtime or device verification is implied by any source-reading result.

## Repository rules and dependency audit

Production SMB handles use explicit Guest authentication, GENERIC_READ, and FILE_OPEN. No production SMB mutation path was found. App-authored logs generally contain aggregate counts and sanitized exception/status names; C01 covers diagnostic exceptions. No claim is made that all repository tools obey the same access-mask/logging restrictions as production.

The reachable-history scan checked all 58 commits for private IPv4 addresses, common token/private-key patterns, credential literals, personal hostnames, device literals, and camera/UUID-style photo filenames. No matching private values were found. Historical media paths were limited to documented generated samples and public-domain artworks; no private photo/database/key path was identified. This is not forensic certification of unreachable objects or proof that every possible secret format is absent.

Runtime/native dependency versions are pinned. `org.json:json:20250517` is explicitly recorded with its test-only rationale in [DEVICE_TESTING.md:361](/Users/dev/dev/googletv/docs/DEVICE_TESTING.md:361); the contrary claim in Claude’s original report is rejected. SMBJ/toolchain versions and native commit pins are recorded, and the Gradle wrapper includes a distribution checksum. Official Android guidance is recorded for the toolchain, and the additional pass verified AGP compatibility and built-in Kotlin. Git cannot prove that every historical dependency addition was researched beforehand. No dependency, target SDK, or implementation framework was changed or recommended for upgrade merely because a newer version exists.

The accepted originals default, explicit Guest behavior, deferred Shared Albums/Apple integration, and Google Photos limitations are correctly represented at the product level. No credential scraper, Apple ID password UI, or implicit full-library Google Photos access was found.

## Checks requiring the actual TCL, share, or additional fixtures

The existing DEVICE_TESTING and PHOTO_SOURCES measurements remain the evidence for previously verified behavior. This review adds no new claim that third-party dream integration or playback works on the device.

Outstanding checks include the following. Several WAL/native cases can first be tested with local fixtures; real firmware behavior, latency, and process memory require the TCL. The list records limitations and is not permission to contact either device.

- Repeated Preview enter/Back cycles, large-picker browsing, and cleanup under the 192 MB heap; distinguish managed heap, native allocations, and PSS.
- MediaPlayer callback order, preparation/first-frame deadlines, surface replacement continuity, and the unexplained black screens.
- Real SMB close timing, Guest denial recovery in All/album/video modes, and cancellation during connection creation, transfer, native decode, or picker exit.
- Coherent database/WAL snapshots during CCC activity, checksum failures, real refresh interruption, cache-loss recovery, and source changes.
- Additional HEIF pixel/offset/transform examples; previously waived broad validation remains a recorded limitation.
- Whether trashed assets retain regular-album memberships; an offline database fixture or separately authorized read-only database inspection can establish this.
- TalkBack selection/field announcements, every D-pad route, focus after Preview/Sort/Refresh, keyboard dismissal and Back, large text, safe areas, and configuration/process recreation.
- HDR appearance, longer mixed/pair/video sessions, and current firmware’s idle activation/Art Mode interaction.

For later fixes, start with deterministic player/WAL fixtures and isolated buildable milestones, then record the exact build and TCL validation commands/results in DEVICE_TESTING. The review itself changes no work-order decision.

## Reconciled claims and provenance

This is the combined actionable report; the originals remain unchanged as review history. Overlapping video-advance, exclusion, empty-album, All-mode recovery, WAL-checksum, close-latency, temp-file, and offset findings each appear once above. Claude's interrupted-WAL-append, per-file reconnection, native scaling, uncommitted-tail, and possible trashed-membership observations are included alongside the Codex-specific lifecycle, configuration, API-contract, accessibility, and TV findings.

The collation rejects or narrows these earlier assertions: two permanent Preview threads per exit; simultaneous native-vector/full-bitmap/crop memory; bitmap pixels wholly within the 192 MB managed heap; an undocumented org.json dependency; all diagnostic opens using GENERIC_READ; tautological PathSet/Layout contract tests; a denied WAL read directly implying absence; Back aborting refresh; unconditional STALLED classification during download; arbitrary cropping offsets limited to zero/padding; DATE_ONLY being dead; and a repository-wide “all rules pass/no secrets exist” certification.

The missing Type/Interval behavior should change in code; historical status/schema summaries should change in docs; equivalent note wording needs no behavioral fix. Decisions still requiring clarification are identified explicitly above rather than silently changing product policy. No review finding changes the ROADMAP or authorizes device/share operations.
