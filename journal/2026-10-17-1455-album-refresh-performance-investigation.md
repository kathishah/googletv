# Album refresh performance investigation and WAL decision (2026-10-17)

Moved verbatim from the "Refresh performance investigation" section of `docs/ALBUM_SELECTION.md` on 2026-10-19, with links updated. The incremental sync design that resulted stays in the album selection doc.

Refresh performance is the next open investigation, separate from the implemented carousel and local cache. Known observations and baseline measurements:

1. The mounted clone measured `Photos.sqlite` at 1,280,086,016 bytes and `Photos.sqlite-wal` at 6,370,653,032 bytes (7,650,739,048 bytes total; 7.12 GiB). The user observed the TV copy at about 7% after roughly one minute, then later reported completing a full refresh. This was not a controlled throughput measurement.
2. On the development Mac, a 128 MiB sequential WAL read took 12.705 s (10.56 MB/s); a straight-line extrapolation is about 12.1 minutes for the database/WAL pair, not a TV measurement. Two concurrent 64 MiB reads (one from each file) took 17.3 s overall (about 7.8 MB/s combined), slower than that sequential sample.
3. The TCL reported a 780 Mbps Wi-Fi link rate, which is a negotiated link rate rather than measured SMB throughput. App storage showed 44 GiB available, so the measured file pair fits, but storage capacity does not explain transfer time.
4. On a picker re-entry, “Reading the saved catalog from this TV…” remained on screen for several seconds before the carousel appeared. This was a rough observation, not a timed benchmark; no SMB copy was requested on that visit. It suggests separately measuring local JSON read/parse and album-card construction too.

### WAL question and decision (2026-10-17)

The TV only needs album names, counts, and file locations, so why copy the WAL, which is 83% of the 7.12 GiB? The WAL holds committed changes (new photos, new albums, membership edits) not yet checkpointed into `Photos.sqlite`. **Finding: the WAL is required.** On the mounted clone, `Photos.sqlite` was last modified 2026-10-06 and `Photos.sqlite-wal` on 2026-10-17. The main file changes only when Photos checkpoints, so a DB-only copy would show the library about 11 days old (assuming CCC preserved modification times; only `stat` was run, no query diff). The WAL is large because Photos rewrites the same pages repeatedly, not because it holds much album data, so the fix is to stop re-transferring it.

Decision, in order: (1) now, incremental WAL sync on the TV (implemented, below); (2) later, optionally one Mac-side script run by CCC that makes photos readable before the copy and writes a compact album index after it. Option 2 reverses the rule that there is no sidecar on the share and no Mac-side generator, so record a `docs/PRODUCT.md` decision if adopted. launchd cannot be used: TCC blocks background access to the external volumes ([photo sources](../docs/PHOTO_SOURCES.md)), so CCC before/after script hooks are the only route, and their disk access, a root-run `python3` with an absolute path, CCC SafetyNet/delete settings leaving the index alone, and Guest readability are all unverified. A partial-read custom SQLite file layer was rejected as the most complex option.
