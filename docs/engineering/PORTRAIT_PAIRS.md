# Portrait pairs (design)

Product behavior (which photos pair, how the sequence is built, the layout and the background styles, and error states) is in [product](../PRODUCT.md#portrait-pairs). This document is the design: the evidence, the catalog list, rendering and performance, and rendering notes.

## Evidence

From the clone's `Photos.sqlite`, last checkpoint, read-only, counts only. The photos counted are active, non-trashed, not shared-album copies, with screenshots, documents, and receipts left out as the slideshow does ([exclusion entry](../../journal/2026-10-18-1219-excluding-screenshots-documents-receipts.md)): **23,284 photos**, by displayed aspect ratio (width ÷ height after EXIF orientation):

| Displayed aspect | Photos | Share |
| --- | --- | --- |
| under 0.56 (very tall) | 52 | 0.2% |
| 0.56 to 0.7 (about 2:3) | 370 | 1.6% |
| 0.7 to 0.78 (about 3:4) | 7,324 | 31.5% |
| 0.78 to 0.9 (about 4:5) | 17 | 0.1% |
| 0.9 to 1.1 (square-ish) | 166 | 0.7% |
| 1.1 to 1.45 (about 4:3) | 13,814 | 59.3% |
| 1.45 to 1.9 (3:2, 16:9) | 1,162 | 5.0% |
| 1.9 and wider (panorama) | 379 | 1.6% |

- **A third of the library is portrait** (7,763 of 23,284, 33%), and 94% of those are 3:4, so pairs will be mostly 3:4 + 3:4 at 84% fill.
- **Where the shape comes from.** `ZADDITIONALASSETATTRIBUTES.ZORIGINALWIDTH`, `ZORIGINALHEIGHT`, and `ZORIGINALORIENTATION` hold the original file's raw pixel size and EXIF orientation (values 5 to 8 swap width and height). The raw sizes matched the files' own pixel sizes in 24 of 24 sampled files. Do **not** use `ZASSET.ZWIDTH` and `ZHEIGHT`: they are already turned by orientation, but they describe the edited version Photos shows, and the app shows originals (unedited, as [photo sources](../PHOTO_SOURCES.md#what-originals-contains) records).
- **Checked on the TV in milestone 3:** photos with orientation 6 and 8 came out upright and correctly paired ([journal](../../journal/2026-10-18-1427-portrait-pairs-milestone-3-renderer.md)).

## Data

### Catalog

`PhotosAlbumCatalog.VERSION` is now 8 (the full version history is in [album catalog](ALBUM_CATALOG.md#tv-cached-regular-album-index-contract)). Version 5 added one top-level list, `portrait`: the safe `originals`-relative path of every active photo whose displayed aspect is in the pairable band. Version 6 changes only the exclusion query so classified photos remain in `excluded` when Photos marks them trashed; an original may still remain in the tree walked by All mode. The portrait list still contains active photos only. Like `excluded`, `portrait` is looked up case-insensitively with either slash (the same helper, generalized and renamed from `PhotoExclusion` to `PathSet`). A catalog with another version is rejected and rebuilt on the next picker open or refresh, as for the version 3 to 4 change.

- **Query.** One more pass over `ZASSET` joined to `ZADDITIONALASSETATTRIBUTES`, in the same read as the other queries: `ZKIND=0`, `ZTRASHEDSTATE=0`, not a shared-album copy (`ZSAVEDASSETTYPE<>4`), positive original width and height, displayed aspect `(orientation in 5..8 ? height ÷ width : width ÷ height)` between 0.56 and 0.9 (a `CASE` in the SQL, repeated in `PortraitRules` so the rule is unit-tested). Excluded photos (screenshots, documents, receipts) are left out of the list by the mapper.
- **Size (measured on the TV).** 7,712 paths: the catalog went from 2,207,985 to 2,570,455 bytes (+0.36 MB), and a cached read takes 2.2 to 2.4 s (v2 measured 2.3 to 2.8 s during a refresh, so no visible change). The new query takes 10.4 s on the TV inside a 63.8 s refresh (the other 40 s is SQLite's first read building the WAL index, as before).
- **Unknown means single.** A photo newer than the catalog, or one whose original size is missing, is not in the list and is shown alone. The safe failure is today's look.
- **All mode** gets orientation from the same list: the walk samples photos as now and the planner looks each one up, so no extra file is opened and the walk is unchanged. Album mode looks up the album's paths the same way.
- **Pairing is a view concern.** `SmbGuestPhotoSource` returns slides and the pairable flag per slide from the catalog; the pair planning runs in the player (or a small helper it calls) so the same code serves any source. The bundled-sample source has no catalog and returns only singles.

## Rendering and performance

- `SlideshowView` gets a second photo layer beside the first and a pure function (`PairFit`, JVM-tested like `VideoFitRules`) that returns the two rectangles and the three slice widths from the two bitmap sizes and the screen size (the 16 px minimum slice replaces the gutter).
- **Decode size.** Photos are decoded to fit the whole screen as now (a 12 MP landscape file is decoded at 1008 × 756, rotated for portrait); a pair member is shown at the same height it would have alone, so **no change to decode sizes** is needed and a pair costs one more bitmap of about 3 MB.
- **Decode time is the risk.** The worker decodes one photo at a time, so a pair takes two decodes. 12 MP photos (most portraits) decode fast; a 24 MP HEIC takes 5.5 to 7 s each through the native tile decoder ([device testing](../DEVICE_TESTING.md#device-findings)), so a pair of two of them can take up to about 14 s against a 10 s Interval. **Measured (milestone 4):** 14.7 to 21.9 s for pairs of two 24 MP HEICs (each decode 7 to 13 s including the SMB transfer, slower than the 5.5 to 7 s of the earlier single-photo tests), at most 2.2 times a 10 s Interval, in a worst-case pool made only of such photos; in the real albums the longest pair took 17.9 s. The preload starts for the next slide's two photos as soon as the current slide appears, and the existing "stay until ready" rule keeps the screen from going blank. Measure real pairs from the library on the TV; if the stretch is a problem, the options are decoding the second photo at a smaller target or a minimum pair Interval.
- Memory: one decode at a time; the current and preloaded pair can hold four photo bitmaps, plus up to two 8 MB background bitmaps. Android 8+ stores bitmap pixels in native memory, so managed Java heap alone does not describe bitmap or process memory. PSS includes more than the app's managed heap and must not be compared directly with the 192 MB managed-heap limit. **Measured:** a 31-minute soak stayed between 91 and 141 MB process PSS with no upward trend; a later picker sweep raised PSS from about 88 MB to 203 MB, and the user accepted the focused-card-only cover behavior without requiring more measurement. See [device testing](../../journal/2026-10-19-2007-resource-cleanup-follow-up.md).

## Layout and rendering notes

- **Rendering.** One full-screen background behind the two photo layers, built when the pair is prepared, off the main thread (as built: `PairScene.build` draws it with Canvas into an opaque 1920 x 1080 `ARGB_8888` bitmap, about 8 MB, shown by a full-screen `ImageView`; it took 13 to 149 ms on the TV, the walnut frame being the slowest because of its blurred shadows; a preloaded pair holds one more). `PairFit` returns the two photo rectangles and the background is given them, because the frame styles draw their lines and shadow from those edges. The wood grain and linen are drawn from a few repeating lines (no image assets), and the whole drawable is cached for the life of the slide. Memory: one screen-sized drawable.
- **Fallback.** A style that cannot be built falls back to plain black behind the pair. A constant (`BACKGROUND_STYLES`) can switch the feature to black or restrict the set.
- **24 MP HEIC portraits (found in milestone 3).** The native tile decoder returned a screen-sized canvas with the picture centered on black, so a portrait came back as a 1920 x 1080 bitmap and `PairFit` shrank the pair. The decoder now reports the picture's rectangle and `NativeHeifDecoder` crops to it. The same pictures also had part of the image missing (a black strip on one edge) because of a libheif tile-offset quirk; see [device testing](../../journal/2026-10-18-1427-portrait-pairs-milestone-3-renderer.md).
