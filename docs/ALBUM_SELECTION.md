# SMB regular-album selection

## Phase 1 scope

Let the user choose one or more regular, user-created albums from the Mac Photos library for the TV slideshow. The TV reads existing supported image files over the SMB share; it does not export or duplicate photos. Shared albums are explicitly deferred to a later phase and do not appear in this picker. Their path investigation does not block implementation or release of regular-album selection.

For this phase, “album” means a regular user-created Photos album whose assets can be mapped to existing readable files under the library's `originals` directory. Smart albums, folders, Shared Albums, and videos are out of scope unless validation shows they can be supported cleanly and the product scope is updated.

## User experience

1. Keep **All photos** as the default, preserving today's behavior. In SMB source settings, show the current choice and a **Choose albums** action.
2. **Choose albums** opens a full-screen, D-pad-friendly picker showing regular albums in a single grid. Do not include a search bar. Use each album's cover image across the card, with a compact `Album title (N)` overlay and a selected state. Use a dark gradient behind the overlay text. If no readable cover exists, use a neutral cover without hiding the album.
3. Provide **Sort** (newest year-month first by default, or A–Z), **Select all**, and **Clear all**. Albums with the same year-month sort by title; albums without a usable year-month follow the dated albums, sorted alphabetically. The Type filter is deferred until shared albums are introduced, when it can filter the unified grid without adding separate tabs.
4. Stage selection changes while the picker is open. **Apply** saves them; **Cancel** or Back discards them. When multiple selected albums contain the same file, include it once. Use all usable photos from selected albums and shuffle the full set without a fixed pool-size limit. The existing random-pool setting applies only to **All photos** mode.
5. In SMB settings, summarize the saved choice (for example, “3 albums · 842 photos”) and allow reopening the picker. Hide the random-pool-size setting while specific albums are selected. Preview and DreamService use the same persisted selection.
6. If a selected album contains no usable photos, keep it selected and explain that no readable supported photos are currently available. Do not silently switch to **All photos**. If the index is missing or invalid, show an actionable inline error in settings/picker, preserve the saved selection, and keep **All photos** available. There is no separate “Index unavailable” screen.

## Regular-album index contract

- A Mac-side process creates a metadata-only `PhotosAlbumIndex.json` sidecar at the root of the existing SMB share, outside `Photos Library.photoslibrary`. It reads the cloned Photos database read-only and writes only the index. It runs after the CCC clone refresh; it never copies or exports photos. The TV reads the sidecar over the existing read-only SMB connection and never opens the Photos database.
- Each index entry contains an opaque album ID, display title, optional year-month sort key, usable-photo count, and safe relative paths for supported image files. The exact stable-ID derivation and versioned JSON schema are implementation decisions that must be resolved before coding; IDs must survive routine CCC refreshes and title edits where possible.
- The generator initially includes regular user-created albums only. It maps membership to existing files under `Photos Library.photoslibrary/originals`. Video assets are excluded until the separate media-type feature is implemented. Current source behavior shows original, unedited files; album selection does not apply Photos edits.
- The TV validates that each path remains under the configured library-package root and specifically under `originals`, exists, is readable, and has a supported image type. It does not recursively scan internal directories or present them as albums. Duplicate paths are collapsed. Missing, unsupported, duplicate, and out-of-root references are reflected in usable counts and diagnostics without logging photo filenames.
- If an index refresh removes a selected album ID, keep the saved ID and tell the user that selection is unavailable. Do not broaden the slideshow silently. The user can remove the unavailable selection or explicitly return to **All photos**.
- Album cover tiles use a bounded, display-sized decode of a representative existing album image. They are previews only; no exported image copies are created. Photo filenames and host addresses stay out of logs.

## Regular-album validation and implementation exit criteria

Two sampled regular-album references resolved to readable files under `originals`, which establishes initial feasibility but not coverage. Before implementing the picker:

1. Validate regular-album mapping across the clone, not only the two samples. Record totals for linked, missing, unsupported, duplicate, and out-of-root references without recording photo filenames.
2. Confirm album IDs remain stable across a CCC refresh and title changes; specify the versioned index schema and invalid-index behavior.
3. Generate a sample index outside the library package and read it over the TV's existing read-only SMB connection.
4. Review the D-pad picker in [the mock screens](mockups/album-selection.html), then implement the settings entry, picker, saved selection, and preview/DreamService integration.
5. Verify All photos remains unchanged, specific-album mode has no pool-size cap, duplicate paths display once, and index/source errors never silently broaden the selection.

## Later phase: shared albums

Shared Albums created by the user or shared with the user remain a desired follow-up. They are not shown or selectable in Phase 1. The current investigation finds inconsistent path materialization and does not yet establish a reliable mapping from shared album items to readable files. Continue the read-only investigation separately. Add shared albums to the same picker and restore the Type filter only after representative items from both sharing roles can be mapped reproducibly to readable files on the clone and over SMB. See [shared-album probe findings](PHOTO_SOURCES.md#album-database-probe-2026-10-16).
