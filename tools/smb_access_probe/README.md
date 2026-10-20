# SMB access probes

Diagnostics and the permission script from the HEIC `STATUS_ACCESS_DENIED` investigation. For commands and results, see [device testing](../../docs/DEVICE_TESTING.md#heic-status_access_denied-investigation-2026-10-13). For the decision, see [photo sources](../../docs/PHOTO_SOURCES.md#guest-read-permissions-per-file-2026-10-13).

Every tool here prints counts only. Never commit sample lists, manifests, host addresses, or photo filenames.

| File | Runs on | Purpose |
| --- | --- | --- |
| `sample_mount.py` | Development Mac, against a read-only Guest mount | Samples up to 25 HEIC/HEIF files and 15 JPEG controls, and tests read-only opens. Writes the private path list to a scratch file outside the repository. |
| `SmbAccessDiagnostic.kt` | TV, via `app_process` | Opens the sampled paths with the app's SMBJ Guest configuration and several read-only access masks. Copy it into the app's `playback` package temporarily, build, run, then remove it. |
| `HeicDecodeDiagnostic.kt` | TV, via `app_process` | Reads sampled HEICs into memory over SMB Guest and tries `BitmapFactory`, `ImageDecoder`, `BitmapRegionDecoder`, and `MediaMetadataRetriever`. Also lists the TV's HEVC and HEIF decoders. Install it the same way as `SmbAccessDiagnostic.kt`. |
| `HeicCapsProbe.kt` | TV, via `app_process` | Reports which decoders accept the 512×512 and 640×896 HEVC tile formats. Reads no photo data. |
| `VideoDecodeProbe.kt` | TV, via `app_process` | Prints decoder capabilities for the video formats in the library, then demuxes and decodes the first frames of each clip in a TV directory. Prints per-clip results by index only. Install it the same way as `SmbAccessDiagnostic.kt`. |
| `VideoPlaybackProbe.kt` | TV, as a temporary activity | Plays clips from the SMB share through `MediaPlayer` and logs first-frame, stall, and throughput numbers under the `VideoProbe` tag, measures raw parallel read rate, or (mode `P`, with the clip duration as a fourth column) plays a clip through the production `ProgressiveReader` and logs the gate decision, lateness, and leftover temp files. Needs a manifest entry while installed; remove both afterwards. Reads `files/vtest.txt` (`label<TAB>path<TAB>prefetch`, negative prefetch means raw threads). |
| `compare_clone_modes.sh` | Mac mini | Read-only comparison of modes, owners, bytes, and xattr names between the live library and its CCC clone. |
| `heic_perm_trial.sh` | Mac mini (user) | Fixed 25-HEIC permission trial with `select`, `check`, `apply`, `revert`, and `names`. |
| `smb_debug_logging.sh` | Mac mini (`sudo` for `on`, `status`, `off`) | Temporary debug-level logging for smbd and opendirectoryd, for the morning Guest `STATUS_ACCESS_DENIED` outages. `collect START END` saves a time window to `~/smb-debug/` on the Mac mini and prints counts and message types only. Turn it `off` after the test. |
| `make_photos_readable.sh` | Mac mini (user, or CCC as root) | Adds group/other read to supported photo and video originals in the live library (`INCLUDE_VIDEOS=0` for photos only). Subcommands: `scan`, `apply`, `revert`, `diagnose`, and `status`. Also has a CCC "before copying files" mode that always exits 0. |

The Mac mini scripts change permission bits only, and only for supported photo and video files below `originals`. They never write to the clone, and never open the Photos database.
