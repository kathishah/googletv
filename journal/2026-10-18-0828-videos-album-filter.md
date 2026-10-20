# Videos album filter (2026-10-18)

Moved verbatim from `docs/DEVICE_TESTING.md` on 2026-10-19, with links updated for the new location.

At the user's request, Type Videos lists only albums that have videos. Built and installed as before (110 JVM tests pass, 2 new for the filter rules; lint 0 errors). Checked with the picker log line `Showing X of Y albums` and `uiautomator dump` text patterns (counts and fixed phrases only, no album names).

- **Videos:** 373 of 759 albums shown, subtitle "Showing only albums with videos", and a note that 6 selected albums without videos are hidden. **Select all** selected the 373 shown albums and left the 6 hidden ones as they were (379 saved IDs); the total read "373 albums · 1,863 videos", matching the 1,863 distinct videos in albums. **Clear all** cleared everything.
- **Photos and Photos + Videos:** 759 of 759 albums shown, no filter note, as before.
- **Settings drift found and fixed:** at the start of this check the saved Type was Videos although it had been restored to Photos at the end of milestone 4. The app saves settings only on Save or Preview, so the cause is unknown; the saved copy was restored to Media Choose Albums, Type Photos, with the same 11 selected albums, and compared byte for byte with the intended file.
- **Not covered:** a very large catalog and the filter with a sort change (the sort path reuses the same visible list).
