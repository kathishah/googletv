# Bundled artworks

The app bundles three artworks as `drawable-nodpi` JPEGs (recorded 2026-10-16). They are the "Sample pics" source and the fallback shown while SMB is failing.

**These images are not covered by the repository's MIT licence** (see [`LICENSE`](../../LICENSE)). Each is a reproduction of a very old two-dimensional painting. The table below records where each file came from and the status its Wikimedia Commons page showed when it was last checked (2026-10-24). Check the linked page before reusing an image elsewhere; the licence status of a photographic reproduction can differ by country.

| Resource | Work | Commons file (original size) | Commons status on 2026-10-24 |
| --- | --- | --- | --- |
| `art_creation_of_adam.jpg` (3840×1709) | The Creation of Adam, Michelangelo, Sistine Chapel ceiling | [`Adam's Creation Sistine Chapel ceiling by Michelangelo JBU33cut.jpg`](https://commons.wikimedia.org/wiki/File:%27Adam%27s_Creation_Sistine_Chapel_ceiling%27_by_Michelangelo_JBU33cut.jpg) (4492×2000; the cleanest panel-only crop found, 2.25:1, so it letterboxes on a 16:9 TV). Photograph of the ceiling taken in 2011 by the Commons contributor credited on that page. | Public domain (artwork's copyright expired; Commons tag `PD-art-old-100-expired`) |
| `art_last_supper.jpg` (3840×2088) | The Last Supper, Leonardo da Vinci | [`Leonardo da Vinci - The Last Supper high res.jpg`](https://commons.wikimedia.org/wiki/File:Leonardo_da_Vinci_-_The_Last_Supper_high_res.jpg) (5381×2926, 1.84:1, nearly 16:9). A 9600×4800 composite exists but is 2:1 and 43 MB, and a 4972×2609 version is CC BY-SA 4.0, so neither was used. | Public domain (`PD-art-old-100-expired`) |
| `art_lamentation_of_christ.jpg` (2500×2076) | Lamentation of Christ (The Dead Christ and Three Mourners), Mantegna, Pinacoteca di Brera | [`The dead Christ and three mourners, by Andrea Mantegna.jpg`](https://commons.wikimedia.org/wiki/File:The_dead_Christ_and_three_mourners,_by_Andrea_Mantegna.jpg) (2500×2076, the best public-domain full view found; larger Mantegna files on Commons are CC BY-SA detail shots). Pillarboxed on a 16:9 TV. Source credited on Commons: Pinacoteca di Brera. | Public domain (`PD-art-old-100-expired`) |

"Lamentation of Christ" was taken to mean Mantegna's painting. Giotto's Scrovegni Lamentation is a different work with the same name; swap the file if that was intended.

## Metadata

The embedded metadata was removed from `art_creation_of_adam.jpg` on 2026-10-24 (a lossless rewrite; the pixels are identical). The original crop carried the photographer's personal contact details, a rights notice and a camera serial number, which have no place in this repository. The credit above replaces the rights notice. The other two files carry only a colour-space tag. When adding or replacing an image, strip its metadata first (`jpegtran -copy none in.jpg > out.jpg`) and record the source and licence here.

## Replacing or removing an artwork

The three images are listed in `PhotoSource.kt` (the `art_*` slides and the resource map) and the manifest and dream preview use `art_last_supper`. To drop one, remove its slide and map entry, delete the file and its row here, and run the unit tests. Any replacement should have a licence you can state in this file.

## History note

Between 2026-10-12 and 2026-10-16 two other sample images (`sample_mountain_lake.jpg`, `sample_redwood_forest.jpg`) were bundled instead of these artworks. They are no longer in the tree; they remain in the git history. Their origin was not recorded at the time (they carry no camera, author or licence metadata), so nothing about their licence is claimed here.
