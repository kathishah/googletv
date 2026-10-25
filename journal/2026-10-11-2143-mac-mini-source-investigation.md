# Mac mini source investigation

Moved verbatim from `docs/DEVICE_TESTING.md` on 2026-10-19, with links updated for the new location.

On 2026-10-11, read-only checks of `/Volumes/Photos/Photos Library.photoslibrary` found an `originals` directory with JPEG and HEIC samples that could be opened and read. The package also contains databases and resource directories; its internal folders are not Photos albums. A later Photos settings screenshot from the Mac mini showed the active System Photo Library under `LIVE_VOLUME`, That was recorded as the same location reached through the SMB `Photos` share. **Correction (2026-10-13):** the shared package is a cloned copy of that library on a second external drive, not the live library. The user expects the clone to contain exactly the same files; this has not been verified, and identical file contents do not guarantee identical ownership or modes. The chosen integration is read-only SMB access to available image files below `originals`; no package database access or writes. Photos is set to **Download Originals to this Mac** (confirmed 2026-10-13). CCC and the manual permission script each run once a day, with the permission script before the clone refresh.

Read-only SMB check from the development Mac (the host address was passed at runtime and is intentionally omitted here):

```sh
python3 tools/check_smb_source.py "$MAC_MINI_IP"
```

Result: SMB port 445 reachable; noninteractive share listing accepted using the current Mac session; `Photos Library.photoslibrary/originals/` present with 16 immediate subdirectories; three sample JPEG files readable. The share was mounted by Finder for this check. This verifies access from the development Mac, **not from the TCL TV**. No private image bytes or filenames were saved to the repository.
