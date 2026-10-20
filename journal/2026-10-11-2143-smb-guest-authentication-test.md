# SMB authentication test (2026-10-11)

Moved verbatim from `docs/PHOTO_SOURCES.md` on 2026-10-19, with links updated for the new location. Inline corrections and updates are kept as originally written.

The Mac mini's `Photos` share is configured for SMB Guest access and read-only access. From the development Mac, an explicit Guest mount (`//guest:@<host>/Photos`) could read the library's `originals` directory and three sample images; the recursive inventory completed. The share listing probe also accepted Guest access. Current-session authentication and anonymous-only access were rejected. This establishes that the server accepts SMB Guest access, not anonymous access; an Android SMB client must offer an explicit Guest mode (or equivalent guest credentials). Do not treat an arbitrary username with a blank password as Guest. This was tested from the development Mac, not the TV, and does not establish that the app's SMB client works on the TV. SMB1 is obsolete and insecure, and macOS file sharing is built around SMB2/3, so the Android client library must support SMB2 or later.

Because the share is readable by Guest, any device on a network that can reach the Mac mini's SMB service may be able to read its contents. Keep the share read-only and limit network reachability to the intended local network.
