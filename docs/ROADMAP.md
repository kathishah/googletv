# Roadmap

The screensaver app has everything planned for it. What remains is optional. The milestone-by-milestone history, with measurements, is archived in the [roadmap history entry](../journal/2026-10-19-2246-roadmap-history.md).

## Done

- **TV path and setup.** Toolchain, network `adb`, selecting the screensaver with `adb`, and idle activation at the default timeout ([device testing](DEVICE_TESTING.md#setup-sequence)).
- **Local slideshow and DreamService.** Settings screen, preview, interval, shared renderer ([product](PRODUCT.md#first-build-acceptance-criteria), [architecture](ARCHITECTURE.md)).
- **Read-only SMB Photos source.** Guest access, random pool in All mode, place/date labels, 24 MP HEIC decoding, retry with backoff and the stored-artwork fallback ([photo sources](PHOTO_SOURCES.md)).
- **Regular album selection.** TV-cached catalog built from the Photos database with incremental WAL sync ([album selection](PRODUCT.md#album-picker)).
- **Media type.** Photos, Photos + Videos or Videos, with progressive video playback in the app ([media type](PRODUCT.md#videos)).
- **Exclusions.** Screenshots, documents and receipts are never shown ([photo sources](PHOTO_SOURCES.md#excluding-screenshots-documents-and-receipts)).
- **Portrait pairs.** A Layout setting with patterned backgrounds ([multi-image layouts](PRODUCT.md#portrait-pairs)).
- **October 2026 code review.** All six steps of the fix plan are done ([review](../journal/2026-10-19-2051-app-review.md), [plan](../journal/2026-10-19-2051-app-review-implementation-plan.md)).

## Remaining (all optional)

- **Shared Albums.** Blocked until their files can be mapped reliably ([photo sources](PHOTO_SOURCES.md#albums)).
- **Saved All-mode file index.** The dream still lists the whole tree at each start (3–4 s).
- **Faster refresh.** Reuse the SQLite `-shm` when the WAL is unchanged, or a Mac-side compact index run from a CCC hook (launchd is blocked by macOS privacy controls).
- **Transitions** between slides.
- **Google Photos.** Not planned; see [why only Apple Photos](PHOTO_SOURCES.md#why-only-apple-photos).
- **Unexplained black video screens.** A few were seen in the 60-minute soak; check the log for a `Video finished` line with an unusual time if they recur. A multi-hour soak has not been done.
- **After a TV firmware update.** Recheck idle activation, Art Mode behavior and the `adb` setup.
- **Browser-only Apple Photos check** on the TV (low priority now that SMB is the source).

### Deferred code-review items

These wait for evidence; none is needed now.

| Item | Revisit when |
| --- | --- |
| R04 HEIF offset alignment | A shifted or cropped HEIC is seen (the wider check was waived) |
| R13 native downscale aliasing | It becomes visible |
| R11 Photos schema fingerprint | A macOS Photos update arrives (schema failures already keep the old catalog) |
| R15 album trash semantics | After checking the Mac database and a user decision |
| R07 video watchdog | The black screens above point at player hangs |
| C01, C02, remaining C03 | That tooling and structure code is next touched |
| PairPlanner style repeats | Trivial: a playlist with a single pair, or a pair that was split, can repeat a style |
