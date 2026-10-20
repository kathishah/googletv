# Media type: open questions as of 2026-10-19

Moved verbatim from the "Open questions" section of `docs/MEDIA_TYPE.md` on 2026-10-19, with links updated for the new location. Closed items are kept here as they were recorded.

- Closed: dates come through `MediaMetadataRetriever` for most clips and locations rarely (7 of 9 had a date only); see Labels.
- Closed: HEVC Main 10 clips played in the 60-minute soak with no reported color or brightness problem. Dolby Vision specifically was not singled out.
- Real throughput variation over a long session (6–10 MB/s observed) and the right safe-start margin; tune with logs in milestone 3.
- Closed for a 60-minute soak (101 clips, memory 80–101 MB, 0 pauses); a soak of several hours is still not done.
- Closed: album-mode previews no longer open every selected photo; a sample of 8 is checked (milestone 7).
- Closed: the TV's idle timers did not interrupt the in-app slideshow in 15-, 30-, and 60-minute runs.
- Open: a few black screens with a date caption were seen during the soak. Not tied to Motion JPEG or MPEG-4 (both render normally); the cause is unknown, so check the log for a `Video finished` line with an unusually short or long time if it recurs.
