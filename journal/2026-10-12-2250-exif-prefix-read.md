# EXIF read needs a bounded prefix stream (2026-10-12)

Moved verbatim from `docs/PHOTO_SOURCES.md` on 2026-10-19, with links updated for the new location. Inline corrections and updates are kept as originally written.

During the TCL TV check, Android's platform EXIF reader returned no GPS or date from two real SMB JPEGs when given SMBJ's network stream directly, although the Mac read their EXIF records. Reading at most the first 512 KiB into a memory stream before EXIF parsing restored both fields on the TV for the tested JPEG. The bytes are discarded after each label is built; no photo file is saved on the TV by this path. This is a bounded best-effort read, so metadata beyond the prefix may still be unavailable. See [device findings](../docs/DEVICE_TESTING.md#device-findings).
