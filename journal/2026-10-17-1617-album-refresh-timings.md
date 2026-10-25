# Measured album refresh timings on the TCL (2026-10-17)

Moved verbatim from `docs/ALBUM_SELECTION.md` on 2026-10-19, with links updated.

Per-phase timings are logged under the `PhotoSource` tag as one line per refresh (no filenames or host addresses). Three refreshes against the same clone state:

| Run | Total | Transfer | SQLite | Other |
| --- | --- | --- | --- | --- |
| First (full) | 900.7 s | database 138.4 s (1.28 GB, 9.2 MB/s) + WAL 713.9 s (6.37 GB, 8.9 MB/s) | prepare ~38 s (unlabelled at the time), first row 1.2 s, rows 4.4 s | catalog write 3.4 s |
| Second (database reused, WAL resumed, 0 bytes fetched) | 48.6 s | none | about 37 s unlabelled, rows 4.4 s | catalog write 3.1 s |
| Full path, 0 bytes fetched (after timers added) | 45.9 s | none | **prepare 37.4 s**, first row 0.5 s, rows 4.5 s | SMB session 1.0 s, catalog read 2.0 s, write 0.4 s |
| After a CCC refresh, WAL grew by 60,699,960 bytes (database unchanged) | 50.6 s | WAL resumed at byte 6,370,653,032: 6.1 s at 9.9 MB/s, SMB session 7.1 s | prepare 37.9 s, first row 0.6 s, rows 4.5 s | catalog write 0.4 s; same ~760 albums and 33,305 paths |
| Unchanged shortcut (two taps) | 3.0 s each | none | none | SMB session 1.0 s, catalog read 2.0 s |
| Catalog v2 rebuild, album query joined to the attributes table (first attempt) | 78.8 s | none | prepare 40.2 s, first row 5.0 s, **rows 26.6 s**, videos 1.5 s | catalog write 1.3 s |
| Catalog v2 rebuild, album videos resolved from the library list | 51.1 s | none | prepare 39.7 s, first row 0.5 s, rows 4.6 s, videos 1.5 s | catalog write 0.6 s, SMB session 1.2 s, catalog read 2.8 s |
| Catalog v2, unchanged shortcut | 3.4 s | none | none | SMB session 1.1 s, catalog read 2.3 s |

Conclusions: the first sync is 98% SMB transfer at about 9 MB/s, and the real incremental refresh (60.7 MB of new WAL) took 50.6 s against 900.7 s for the full copy. SQLite's first read (`rawQuery` prepare) spends about 37 s building the wal-index for a 6.4 GB WAL; it recurs whenever the WAL changed, because the `-shm` must be rebuilt. Resumed syncs with new WAL bytes therefore cost roughly 45 s plus the delta transfer, as the 16:37 run confirmed. Unchanged libraries take about 3 s. Remaining ideas, none needed yet: reuse the `-shm` when the WAL is unchanged and the catalog is rebuilt anyway, skip `fd.sync()` on catalog writes, and the Mac-side index.

Timings for database and WAL copy, SQLite phases, catalog write, and cached catalog read are now logged (above); cover loading logs "loaded from cache/share" with milliseconds under the `AlbumPicker` tag. Still to compare: Keep a stable clone snapshot during comparisons. Check SMB read buffering/request size, protocol overhead, per-file metadata operations, TV storage throughput, and catalog JSON size. Compare parallel reads against the existing sequential sample; do not assume parallel copy helps, and preserve the matching DB/WAL snapshot if CCC is refreshing. Keep progress reporting tied to actual bytes and report phase changes separately. Record measured bytes and elapsed times without logging photo filenames or host addresses.
