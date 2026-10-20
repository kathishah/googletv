# Excluding screenshots, documents, and receipts (2026-10-18)

Moved verbatim from `docs/PHOTO_SOURCES.md` on 2026-10-19, with links updated for the new location. Inline corrections and updates are kept as originally written.

The user does not want screenshots, documents, or receipts in the slideshow ([product](../docs/PRODUCT.md)). Random "All" mode only walks the `originals` tree, so the exclusion list has to come from the Photos database, which the TV already stages for album selection. Photos classifies photos itself in the background and its Utilities albums (Documents, Receipts) are built from that; the app reads the same classification instead of guessing from pixels.

**Where the signals are** (clone's `Photos.sqlite`, last checkpoint, read-only; counts only, no filenames):

- `ZASSET.ZISDETECTEDSCREENSHOT = 1`: 832 of 34,526 active photos (2.4%).
- `ZSCENECLASSIFICATION` rows with `ZCLASSIFICATIONTYPE = 0` hold one scene id and a confidence per asset (`ZASSETATTRIBUTES` joins `ZADDITIONALASSETATTRIBUTES.Z_PK`, whose `ZASSET` is the asset key). Apple's scene taxonomy ships on macOS (`PhotosFormats.framework/Resources/PFSceneTaxonomyData_99.bz2`, plain text once decompressed) and names the ids: 492 document, 1086 receipt (a child of document), 1665 screenshot, 1664 handwriting, 1494 whiteboard. Versions 84 and 99 agree on these ids. The library's scene analysis versions are 100 and 101, newer than either file, so the ids were also checked by looking at photos (below).
- Not usable: rows of `ZCLASSIFICATIONTYPE = 3` (content classification, whose taxonomy names document, driving licence, passport, visa) hang off video attributes: 2,224 of the 2,437 assets they cover are videos, and the counts (1,797 "driving licences") are not plausible for photos. They are ignored.
- Coverage gap: 9,945 of the library's 37,968 asset-attribute rows have scene analysis version 0, meaning they were never analyzed. A photo with no classification cannot be excluded.

**Calibration** (26,214 active, non-hidden, non-shared photos). Counts by rule: document confidence ≥ 0.2 2,778; ≥ 0.4 2,375; ≥ 0.6 2,009; receipt ≥ 0.19 192; screenshot flag 832. Random samples of 24 per band, viewed as contact sheets (thumbnails were deleted afterwards):

| Band | What the sample showed |
| --- | --- |
| document ≥ 0.6 | 24 of 24 documents, receipts, notes, or whiteboards |
| document 0.4 to 0.6 | about 21 of 24 documents or forms; 3 photos of people or a display |
| document 0.2 to 0.4 | about 12 of 24 real documents (licence, passport, forms); the rest family photos |
| document 0.1 to 0.2 | mostly ordinary photos; about 4 of 24 ID cards, a few maps and signs |
| receipt ≥ 0.19 | 24 of 24 receipts |
| screenshot flag | 12 of 12 screens (a few are photos of a screen) |
| no tag (control) | 24 of 24 ordinary photos |

**Rule used:** exclude a photo when the screenshot flag is set, or document confidence is ≥ 0.4, or receipt confidence is ≥ 0.19. That is 2,458 photos, 9.4% of the 26,214. The constants are in `PhotosAlbumCatalog.kt` and need a catalog refresh to change. Known limits: ID cards and some real documents below 0.4 still show, a few ordinary photos at 0.4 to 0.6 are hidden, and unanalyzed or newly imported photos are not filtered until Photos has analyzed them and the TV catalog is refreshed.

**How the TV uses it.** Catalog version 4 added `excluded`, the relative paths of those photos, and drops them from every album's `paths` (so album counts and covers never include them). Version 6 keeps those classification exclusions for trashed assets too: Photos may leave a deleted asset's original in the tree that *All* mode walks, so trash state must not remove its exclusion. This does not change which assets are included in albums or the portrait list. *All* mode skips excluded paths while walking the tree, before sampling; album mode filters again at load time as a guard. If no usable catalog exists the slideshow shows everything and logs a warning. The query remains one uncorrelated subquery, a single pass over the classification rows. Run on the Mac over SMB it took 134 s; the TV's timing is recorded in [device testing](2026-10-18-1219-excluding-screenshots-documents-and-receipts-tv-run.md).
