# Multi-image layout: portrait pairs

Status: **in progress, started 2026-10-18.** Milestone 1 (catalog v5) is done; see the [plan](#implementation-plan). Roadmap item 10, first part. Mock: [portrait-pairs.html](mockups/portrait-pairs.html) (today's single portrait, the plain pair, and four three-slice styles: pinstripes, diamond lattice, walnut frame, brass fillet mat).

## Goal

Add a **Layout** setting. **Single** is today's behavior and stays the default. **Portrait pairs** shows two portrait photos side by side on one slide, so a portrait photo no longer sits in the middle of the screen with wide black bars. Landscape photos stay full screen, one at a time, exactly as now.

**Only portrait photos are paired** (the user's requirement): two photos share a slide only if both are portrait to begin with, so a pair never adds black space that a single photo would not have. The four-photo tile layout is **not** part of this spec; it is deferred.

Not in v1: four-up tiles, pairing landscape or square photos, cropping to fill, pairing a photo with a video, transitions, a user-set gutter.

## Why: the black edges

On the 1920 × 1080 panel (photos are never cropped, as today):

| Slide | Photo size shown | Screen filled | Black on each side |
| --- | --- | --- | --- |
| Single 3:4 portrait (today) | 810 × 1080 | **42%** | 555 px |
| Single 2:3 portrait | 720 × 1080 | 38% | 600 px |
| Single 4:3 landscape | 1440 × 1080 | 75% | 240 px |
| **Pair** of 3:4 portraits | 810 × 1080 each | **84%** | 142 px |
| Pair of 3:4 and 2:3 | 810 and 720 × 1080 | 80% | 187 px |
| Pair of 2:3 portraits | 720 × 1080 each | 75% | 232 px |
| Pair of 9:16 portraits | 608 × 1080 each | 63% | 344 px |

With the proposed three-slice background the free space becomes a pattern at the left, middle, and right, so the screen is used 100% (photos 84% and pattern 16% for two 3:4 photos), see [Three-slice background](#three-slice-background-proposed-see-the-mock).

A portrait photo is already limited by the screen's height, so it is shown at full height alone. Placing two next to each other costs it nothing: each photo in a pair is exactly as large as it would be alone, and the free space on the sides is used. Two 3:4 photos double the filled area (42% to 84%), which is the same as a landscape photo or better.

## Evidence

From the clone's `Photos.sqlite`, last checkpoint, read-only, counts only. The photos counted are active, non-trashed, not shared-album copies, with screenshots, documents, and receipts left out as the slideshow does ([photo sources](PHOTO_SOURCES.md#excluding-screenshots-documents-and-receipts-2026-10-18)): **23,284 photos**, by displayed aspect ratio (width ÷ height after EXIF orientation):

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
- **Where the shape comes from.** `ZADDITIONALASSETATTRIBUTES.ZORIGINALWIDTH`, `ZORIGINALHEIGHT`, and `ZORIGINALORIENTATION` hold the original file's raw pixel size and EXIF orientation (values 5 to 8 swap width and height). The raw sizes matched the files' own pixel sizes in 24 of 24 sampled files. Do **not** use `ZASSET.ZWIDTH` and `ZHEIGHT`: they are already turned by orientation, but they describe the edited version Photos shows, and the app shows originals (unedited, as [photo sources](PHOTO_SOURCES.md#what-originals-contains) records).
- **Photos still need verification on the TV:** orientation 6 and 8 photos must come out upright and correctly paired (checked by eye in milestone 3).

## User experience

### Settings

A new row, **Layout**, with **Single** (default) and **Portrait pairs**. It goes after **Interval** and before **Label**; the screen has room for one more row only if row spacing is tightened slightly, to be checked on the TV. Under the row: "Two portrait photos side by side. Landscape photos stay full screen."

- Persisted as `layout_mode` (`SINGLE`, `PORTRAIT_PAIRS`); a missing or unknown value is `SINGLE`.
- It is independent of Media, Type, Pool, Interval, and Label, and is saved with the rest on **Save**.
- **It applies to both the in-app slideshow and the screensaver.** The screensaver's photos are photos only, so the layout always applies there; with Type other than Photos it applies to the 500 random photos the screensaver shows ([media type](MEDIA_TYPE.md#screensaver)).
- **Videos are never paired.** With Type including videos, a video is always its own full-screen slide, and only photos are candidates for pairing.
- **Pool counts photos, not slides.** A pool of 100 photos is about 90 slides in Portrait pairs (see the numbers below), because each pair is one slide.
- Interval: **a pair stays up for one Interval**, so each photo is on screen as long as it would be alone. The pool is therefore used up slightly faster.

### Which photos pair

A photo is **pairable** when its displayed aspect ratio is between **0.56 and 0.9** (9:16 up to a little under square). The upper limit is chosen so that almost every pair of pairable photos shares the screen at full height: with the least free space the layout keeps (16 px per slice, see below), two photos whose aspects add up to 1.73 or less fit at 1080 px high (two at 0.86 do); two at the 0.9 limit are drawn 4% smaller (1040 px high). Only 17 photos in the library sit between 0.78 and 0.9, so this is rare. Photos in the square-ish band (0.9 to 1.1, 0.7% of the library) are not paired in v1, and neither are very tall photos below 0.56 (0.2%). Everything else, and any photo the catalog does not know about, is a single.

### Building the sequence (and how random it is)

Pairing is random in three ways: **which** portraits are paired, **who** their partner is, and **which look** a pair gets. Not every portrait is paired.

The pool is shuffled as it is today. The slide list is then built in one pass over the shuffled photos (`PairPlanner`, plain Kotlin, JVM-tested, with the random source passed in so tests can fix it):

1. A non-pairable photo (landscape, square, very tall, unknown, or any video) becomes a **single** slide in place.
2. A pairable photo flips a weighted coin: it becomes a **pair candidate** with probability `PAIR_PROBABILITY` (0.6) and otherwise stays a **single** slide, shown alone on black as today.
3. The first candidate becomes a single slide for now and waits. The **next candidate** in the shuffle is pulled forward into that slide, which becomes a **pair**. Then the waiting slot is free again.
4. A candidate that never finds a partner (an odd count at the end) stays a single.

So landscape photos and unpaired portraits keep their random order, and a pair appears where its first photo landed. Which photos are in the pool (albums, exclusion, pool size) is unchanged; the planner only decides how they share slides.

**What that looks like.** In a pool of 100 photos about 33 are portrait. About 20 of them become candidates and form about 10 pairs; the other 13 portraits show alone, and 67 landscapes show alone: roughly 90 slides, of which about 11% are pairs. No spacing rule is needed: with pairs this infrequent two in a row is rare and harmless, and a rule would make the order less random.

**The look of each pair is chosen when the pair is formed** and then stays fixed for that slide, so it never changes mid-slide:
- **Style:** one of the four styles, chosen at random, but **never the same style as the previous pair**, so consecutive pairs always differ.
- **Variant:** within the style, a random variant: walnut, oak, or cherry for the frame; brass on charcoal, silver on navy, or brass on navy for the mat; 8, 10, or 14 px pitch for the pinstripes; a small, medium, or large lattice. The photos' own colors vary the stripes and lattice further.
- **Order:** which of the two photos is on the left is the order in which they were shuffled.

Photos that are pair candidates but end up alone (the odd one out) get no pattern, like any other single.

### Layout of a pair

- **Equal height, centered as a group.** Both photos are drawn at the same height `H = min(screen height, (screen width − 3 × 16) ÷ (aspect₁ + aspect₂))`, side by side, and the group is centered on the screen. The 16 px is the least a slice may be (the "gutter" of decision 1: with the three-slice background it is a minimum, not a black strip), so photos never touch each other or the edge. `H` is the full 1080 px whenever the two aspects add up to 1.73 or less, which covers every shape in the table above; two photos at the 0.9 limit shrink to 1040 px. (`PairFit`, built in milestone 2: the first draft said `H` is always 1080, which is false at the upper limit.)
- **Never cropped.** The layout is computed from the **decoded bitmaps' real sizes**, not the catalog's flag, so a photo whose database size is wrong still fits (the pair just shrinks or leaves more black); the catalog only decides who may be partners.
- No transition, as today: the previous slide stays up until the whole next slide (both photos) is ready, then it switches.

#### Three-slice background (proposed; see the mock)

Instead of black, the free space is divided into **three equal slices: left, middle, and right**, and a quiet pattern behind the photos shows through all three: `slice = (screen width - width of photo 1 - width of photo 2) / 3`, and the photos sit at `x = slice` and `x = 2 x slice + width of photo 1`. For two 3:4 photos the free space is 300 px, so each slice is 100 px (the layout is 100 | 810 | 100 | 810 | 100); for 3:4 + 2:3 it is 130 px per slice; for 9:16 + 9:16, 235 px. The slices replace the 16 px gutter and the outer black bars, so **100% of the screen is used and no black is left**. The photos are unchanged: same height, never cropped. Any pairable combination works the same way, with no special case for wide leftovers.

- **Four styles, one picked per pair** at random when the pair is formed (never the same as the previous pair's, with random variants inside the style; see Building the sequence). The user chose the first two after seeing the mock and asked for two frame-like styles to go with them:
  1. **Pinstripes:** thin vertical lines, 10 px apart, a lighter tone on a dark tone, running behind both photos.
  2. **Diamond lattice:** a fine diagonal grid in two quiet tones, running behind both photos.
  3. **Walnut frame:** vertical wood grain in the slices, a dark rebate line and a pale bevel line along each photo's sides, and a soft shadow, so the two pictures look set into one wooden diptych. Fixed walnut tones; an oak variant is possible.
  4. **Brass fillet mat:** a dark linen mat with two thin brass lines around each photo, the way a gallery mounts a print. Fixed tones.
  Dropped after the mock: color blocks and soft edge.
- **A frame can only show at the sides and in the middle.** The photos already run the full height of the screen, so there is no top or bottom rail. The frame effects (lines, rebate, shadow) are drawn around each photo's rectangle and are simply clipped at the top and bottom edges of the screen.
- **Colors.** Pinstripes and lattice take a base tone from the photos (each decoded bitmap scaled down to a few pixels and averaged, mixed and darkened; cheap, no new library) and a lighter tone for the lines. The two frame styles use fixed material tones. Contrast stays low: the background must never compete with the photos or their labels.
- **Rendering.** One full-screen background behind the two photo layers, built when the pair is prepared, off the main thread (as built: `PairScene.build` draws it with Canvas into an opaque 1920 x 1080 `ARGB_8888` bitmap, about 8 MB, shown by a full-screen `ImageView`; it took 13 to 149 ms on the TV, the walnut frame being the slowest because of its blurred shadows; a preloaded pair holds one more). `PairFit` returns the two photo rectangles and the background is given them, because the frame styles draw their lines and shadow from those edges. The wood grain and linen are drawn from a few repeating lines (no image assets), and the whole drawable is cached for the life of the slide. Memory: one screen-sized drawable.
- **Fallback.** A style that cannot be built falls back to plain black behind the pair. A constant (`BACKGROUND_STYLES`) can switch the feature to black or restrict the set.
- Labels stay inside their own photos, so the background carries no text. As built each photo has its own caption at its bottom-left corner (same size and softness as a single photo's, at most two lines), even when both labels are identical (decision 5, default).
- **24 MP HEIC portraits (found in milestone 3).** The native tile decoder returned a screen-sized canvas with the picture centered on black, so a portrait came back as a 1920 x 1080 bitmap and `PairFit` shrank the pair. The decoder now reports the picture's rectangle and `NativeHeifDecoder` crops to it. The same pictures also had part of the image missing (a black strip on one edge) because of a libheif tile-offset quirk; see [device testing](DEVICE_TESTING.md#portrait-pairs-milestone-3-renderer-2026-10-18).

### States and errors

- **One of the two photos fails** (bad file, decoder error): the pair is not skipped. The photo that loaded is shown as a single full-screen slide, the failed photo is dropped from the pool (or sent to the back for a network error), and it counts in the existing failure streak. Three consecutive failures use the stored artworks, as now.
- **A pair is not ready** (the second photo is still decoding): the previous slide stays up. This is the existing rule and means a slow pair dwells longer, never shows a blank.
- Pool of one photo, or a pool with no pairable photos: behaves exactly like Single.
- The stored fallback artworks are always single slides.

## Data

### Catalog version 5

`PhotosAlbumCatalog.VERSION` becomes 5. It adds one top-level list, `portrait`: the safe `originals`-relative path of every active photo whose displayed aspect is in the pairable band. Like `excluded`, it is looked up case-insensitively with either slash (the same helper, generalized and renamed from `PhotoExclusion` to `PathSet`). A catalog with another version is rejected and rebuilt on the next picker open or refresh, as for the version 3 to 4 change.

- **Query.** One more pass over `ZASSET` joined to `ZADDITIONALASSETATTRIBUTES`, in the same read as the other queries: `ZKIND=0`, `ZTRASHEDSTATE=0`, not a shared-album copy (`ZSAVEDASSETTYPE<>4`), positive original width and height, displayed aspect `(orientation in 5..8 ? height ÷ width : width ÷ height)` between 0.56 and 0.9 (a `CASE` in the SQL, repeated in `PortraitRules` so the rule is unit-tested). Excluded photos (screenshots, documents, receipts) are left out of the list by the mapper.
- **Size (measured on the TV).** 7,712 paths: the catalog went from 2,207,985 to 2,570,455 bytes (+0.36 MB), and a cached read takes 2.2 to 2.4 s (v2 measured 2.3 to 2.8 s during a refresh, so no visible change). The new query takes 10.4 s on the TV inside a 63.8 s refresh (the other 40 s is SQLite's first read building the WAL index, as before).
- **Unknown means single.** A photo newer than the catalog, or one whose original size is missing, is not in the list and is shown alone. The safe failure is today's look.
- **All mode** gets orientation from the same list: the walk samples photos as now and the planner looks each one up, so no extra file is opened and the walk is unchanged. Album mode looks up the album's paths the same way.
- **Pairing is a view concern.** `SmbGuestPhotoSource` returns slides and the pairable flag per slide from the catalog; the pair planning runs in the player (or a small helper it calls) so the same code serves any source. The bundled-sample source has no catalog and returns only singles.

## Rendering and performance

- `SlideshowView` gets a second photo layer beside the first and a pure function (`PairFit`, JVM-tested like `VideoFitRules`) that returns the two rectangles and the three slice widths from the two bitmap sizes and the screen size (the 16 px minimum slice replaces the gutter).
- **Decode size.** Photos are decoded to fit the whole screen as now (a 12 MP landscape file is decoded at 1008 × 756, rotated for portrait); a pair member is shown at the same height it would have alone, so **no change to decode sizes** is needed and a pair costs one more bitmap of about 3 MB.
- **Decode time is the risk.** The worker decodes one photo at a time, so a pair takes two decodes. 12 MP photos (most portraits) decode fast; a 24 MP HEIC takes 5.5 to 7 s each through the native tile decoder ([device testing](DEVICE_TESTING.md#device-findings)), so a pair of two of them can take up to about 14 s against a 10 s Interval. The preload starts for the next slide's two photos as soon as the current slide appears, and the existing "stay until ready" rule keeps the screen from going blank. Measure real pairs from the library on the TV; if the stretch is a problem, the options are decoding the second photo at a smaller target or a minimum pair Interval.
- Memory: no new native allocation (one decode at a time); the Java heap holds up to four photo bitmaps (current pair and the preloaded pair) instead of two. Check PSS in the soak.

## Implementation plan

Each milestone ends with its exit check on the TCL before the next starts. Commands and results go to [device testing](DEVICE_TESTING.md).

| # | Milestone | Main work | Exit check |
| --- | --- | --- | --- |
| 1 | Catalog v5 with `portrait` (**done 2026-10-18**) | Query and JSON, generalize `PhotoExclusion` to a path set (`PathSet`), bump `VERSION`; JVM tests for orientation swap, aspect band, round trip, v4 rejection | **Met:** see [device testing](DEVICE_TESTING.md#portrait-pairs-milestone-1-catalog-v5-2026-10-18). Refresh on the TV builds v5; the list has about 7,700 paths (matches the Mac count within the snapshot lag); a 24-photo sample of listed paths checked against the files' real shape; catalog read time recorded |
| 2 | `PairPlanner`, `PairFit`, and the look chooser (**done 2026-10-18**) | Pure Kotlin: the planning pass with the 0.6 pair probability and a passed-in random source, the rectangles, and the style and variant chooser (never the same style twice in a row); JVM tests (odd counts, no pairables, videos, a fixed seed gives a fixed plan, probability 0 gives no pairs, probability 1 pairs all, rectangles for every combination in the table, near-limit aspects, no repeated style) | Tests pass; the table's fill figures reproduce; over 10,000 simulated photos about 60% of portraits end up paired. **Met:** see [device testing](DEVICE_TESTING.md#portrait-pairs-milestone-2-planner-and-fit-2026-10-18) (no TV step: pure Kotlin) |
| 3 | Renderer (**done 2026-10-18**) | Second photo layer, per-photo labels, `PairFit` in `SlideshowView`, and the three-slice background (the four styles, the color sampling, the black fallback); a test pair from a sample catalog in the in-app preview | Screenshots of a 3:4 pair in each of the four styles, a 3:4 + 2:3 pair, and a rotated-orientation pair: upright, equal height, three equal slices, labels inside their photos, no black; the user picks the styles to keep. **Met** (the user's pick is still to come, at the end): see [device testing](DEVICE_TESTING.md#portrait-pairs-milestone-3-renderer-2026-10-18) |
| 4 | Player integration | Slides carry a partner, preload both, failure rules (one fails, not ready, three failures), stored artworks stay single | Real portrait photos from the library in the in-app slideshow: pair timing with 12 MP and 24 MP photos, no blank frames, failure paths exercised, a 30-minute soak with stable memory |
| 5 | Settings and screensaver | `layout_mode`, the Layout row and note, `PhotoSourceFactory` and dream wiring, docs | Setting persists; idle activation shows pairs with Interval honored; Single unchanged; the screen has no clipped rows |

## Decided

User decisions, 2026-10-18:

- **Only portrait photos are paired**; two photos share a slide only if both are portrait, so a pair never adds black space. Landscape photos stay full screen. Four-photo tiles are deferred.
- **The free space is split into three equal slices** (left, middle, right) holding a pattern or frame, instead of black.
- **Styles:** pinstripes and diamond lattice are kept; the walnut frame and brass fillet mat were added as frame-like styles and the frame ideas were approved.
- **Randomness:** not every portrait is paired, and the look of each pair is random (style, variant), never the same style twice in a row.
- **Pairing frequency is a constant, `PAIR_PROBABILITY` 0.6**, not a setting in v1.
- **A lone portrait stays on black**, as today; only pairs get a pattern or frame.

## Decisions to confirm

**Resolved 2026-10-18:** the user asked for the recommended defaults to be used for every item below, and the build follows them. Item 8: the pattern is always on with Portrait pairs (no third Layout choice). Item 9: pinstripes and lattice take their tones from the photos, the two frame styles use fixed tones with random variants (walnut, oak, cherry; brass on charcoal, silver on navy, brass on navy), and no further frame idea was added.

Recommended defaults are used above.

1. **16 px gutter** between the two photos (alternatives: touching, or a wider gap).
2. **One Interval per pair**, not a longer dwell for two photos.
3. **Square-ish photos (0.9 to 1.1) are not paired** in v1.
4. **Minimum aspect 0.56**: a photo taller than 9:16 is shown alone.
5. **Per-photo labels**, or one shared label when both are identical.
6. **Applies to the screensaver** as well as the in-app slideshow.
7. **Photos newer than the catalog are shown alone** until the next refresh.
8. **Three-slice pattern background instead of black** (the mock). If yes: **always on** with Portrait pairs, or a third Layout choice ("Portrait pairs, black") for the plain version? Recommended: always on.
9. **Styles.** Pinstripes and diamond lattice are kept (user choice). The walnut frame and brass fillet mat are new and need a look; fixed tones or tinted by the photos? Any other frame idea (for example a pale gallery mat, a thin black frame with a white mat)?

## Open questions

- Real decode time for pairs of large portrait HEICs (see Rendering).
- Whether the Layout row fits the settings screen without tightening spacing.
- Whether pair frequency should be a setting (for example Rarely, Sometimes, Often) rather than a constant.
- Whether a pair should avoid two photos from the same moment (for example two near-identical shots); not needed for v1, and the catalog has no burst information.
- Four-up tiles are deferred; this spec's `PairFit` and the per-photo label placement should generalize to a grid when that work starts.
