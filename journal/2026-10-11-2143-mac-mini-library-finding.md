# Mac mini library finding (2026-10-11)

Moved verbatim from `docs/PHOTO_SOURCES.md` on 2026-10-19, with links updated for the new location. Inline corrections and updates are kept as originally written.

The existing SMB `Photos` share contains `Photos Library.photoslibrary`. Read-only inspection from this Mac confirmed that the package has `originals` subdirectories with readable JPEG and HEIC image data. The package also contains databases and other private implementation files. These internal directories are **not** the albums shown in Photos. Apple [warns against manually accessing or altering library contents](https://support.apple.com/en-gb/guide/photos/pht12e7a8015/mac). Keep all access read-only and treat the package schema as private and changeable; the app should tolerate layout changes and missing files rather than assuming it is a stable public API.
