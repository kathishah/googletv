# Media type: catalog v2 and v3 build notes (2026-10-17)

Moved verbatim from the "Catalog v2" section of `docs/MEDIA_TYPE.md` on 2026-10-19, with links updated for the new location.

`PhotosAlbumCatalog.VERSION` becomes 2, so existing catalogs are rebuilt on the next refresh (an old catalog never survives the unchanged shortcut). Changes:

**Implemented (milestone 1, 2026-10-17).** The layout is leaner than first drafted:

- Each album keeps `paths` (its photos, exactly as in v1) and adds `photoCount`, `videoCount`, and `videos`, a list of `{p: relative path, d: duration seconds, s: size bytes}`.
- Top level adds `videos`: every active, non-hidden video in the library in the same shape, so Media *All* can include videos without the album tables.
- Two queries. The library query reads `ZASSET` rows with `ZKIND=1`, not trashed and not hidden, joined to `ZADDITIONALASSETATTRIBUTES.ZORIGINALFILESIZE` (about 3,400 lookups). The album query is the v1 query unchanged. An album's videos are resolved against the library list when the catalog is built, so the album query never needs duration or size. Joining the attributes table on every membership row (38,000) was tried first and made the rebuild 79 s instead of 51 s.
- A video needs `ZKIND=1`, an extension in the allowlist (mov, mp4, m4v, 3gp, 3g2, avi, mpg, mpeg, mts, m2ts), a safe path relative to `originals`, and a positive duration and size. Live Photo motion clips are not separate asset rows, so they do not appear.
- **Version 3 (2026-10-17):** the library video query now leaves out shared-album copies (`ZSAVEDASSETTYPE` 4). On the TV, 18% of version 2's 3,258 videos were missing from the share, all under one long numeric folder (`<id>/<album GUID>`), because those files are iCloud Shared Album copies that are not in `originals`. Version 3 lists 2,667 videos, all in the 16 normal `originals` folders, and a 200-path sample showed none missing; album video memberships are unchanged (3,862). `PhotosAlbumCatalog.VERSION` was bumped, so the rebuild happens on the next picker open or refresh (45.8 s).
- Videos that are dropped: on this library, 65 are stored outside `originals` (their directory is an absolute path, the same case as the 1,116 out-of-folder photo references) and 35 have no recorded file size.
- The file is 2.25 MB (v1 was 1.66 MB), a catalog read takes about 0.9 s in the picker and 2.3 s during a refresh, and `sourceModifiedAt`, the unchanged shortcut, and incremental sync are unchanged. A v1 file is rejected and rebuilt on the next picker open or refresh.
