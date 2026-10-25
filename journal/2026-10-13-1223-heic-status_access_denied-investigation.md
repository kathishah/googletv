# HEIC `STATUS_ACCESS_DENIED` investigation (2026-10-13)

Moved verbatim from `docs/DEVICE_TESTING.md` on 2026-10-19, with links updated for the new location.

Question: why can the TV list the tree and show JPEGs, but get `STATUS_ACCESS_DENIED` opening HEIC/HEIF? Both the development Mac and the TV were checked against the **same** sample files. The probe scripts are in `tools/smb_access_probe/`. `$MOUNT` and `$SAMPLE_TSV` were scratch paths outside the repository. The TSV held the sampled relative paths and was deleted afterward.

1. Mount the share explicitly as Guest, **read-only**, on the development Mac:

   ```sh
   mount_smbfs -o rdonly,nobrowse "//guest:@$MAC_MINI_IP/Photos" "$MOUNT"
   ```

2. Sample and test read-only opens (`os.open(O_RDONLY)` plus a 16 byte–4 KiB read) without walking the whole tree. The script shuffles the 16 `originals` subfolders and takes up to 25 HEIC/HEIF (at most 2 per folder) and up to 15 JPEG controls (1 per folder):

   ```sh
   python3 -I tools/smb_access_probe/sample_mount.py "$MOUNT" "$SAMPLE_TSV"
   ```

   An earlier pass took 1 file per folder (16 HEIC, 5 JPEG). All 16 HEIC failed with `EACCES`; 3 JPEG failed and 2 read. The final sample:

   | Sampled | Mode reported over the Guest mount | Mac read-only open |
   | --- | --- | --- |
   | 25 HEIC (0 HEIF found) | 0600 (all 25) | 25 `EACCES` |
   | 11 JPEG | 0600 | 11 `EACCES` |
   | 4 JPEG | 0644 | 4 readable |

   For all 40 files, `stat` succeeded with a nonzero size, and `ls -led` showed no ACL entries. `xattr` listing, which needs an open, failed on all 36 files with mode 0600 and succeeded on the 4 with mode 0644.

3. Repeat the same requests from the TV with the app's SMBJ 0.15.0 configuration. That configuration is explicit Guest, `withSigningRequired(false)`, multi-protocol negotiate, and 15 s timeouts. Use share access `ALL`, `FILE_OPEN`, and `FILE_NON_DIRECTORY_FILE`. The diagnostic was copied temporarily into the app's `playback` package and built with the normal command. It was then run from the APK with `app_process`, as the shell user, without installing anything over the app. The host and the sample paths were piped over stdin, so neither was stored on the TV:

   ```sh
   cd apps/screensaver && JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew assembleDebug --quiet
   adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/smbdiag.apk
   { echo "$MAC_MINI_IP"; cat "$SAMPLE_TSV"; } | adb shell 'CLASSPATH=/data/local/tmp/smbdiag.apk app_process /system/bin com.googletv.screensaver.playback.SmbAccessDiagnosticKt'
   adb shell rm /data/local/tmp/smbdiag.apk
   ```

   Session: `guest=true anonymous=false`. Results per group:

   | Request from TV | 25 HEIC (Mac: denied) | 11 JPEG (Mac: denied) | 4 JPEG (Mac: readable) |
   | --- | --- | --- | --- |
   | Directory listing entry (`QUERY_DIRECTORY`) | 25 found, size > 0 | 11 found, size > 0 | 4 found, size > 0 |
   | Open `FILE_READ_ATTRIBUTES` + query `FileStandardInformation` | 25 OK, size > 0 | 11 OK | 4 OK |
   | Open `READ_CONTROL` + query owner/group/DACL | 25 `STATUS_NOT_SUPPORTED` | 11 `STATUS_NOT_SUPPORTED` | 4 `STATUS_NOT_SUPPORTED` |
   | Open `FILE_READ_DATA` only + read 16 bytes | 25 `STATUS_ACCESS_DENIED` | 11 `STATUS_ACCESS_DENIED` | 4 OK, 16 bytes |
   | Open `GENERIC_READ` (production) + read 16 bytes | 25 `STATUS_ACCESS_DENIED` | 11 `STATUS_ACCESS_DENIED` | 4 OK, 16 bytes |
   | Open `MAXIMUM_ALLOWED` + read 16 bytes | 25 `STATUS_ACCESS_DENIED` | 11 `STATUS_ACCESS_DENIED` | 4 `STATUS_ACCESS_DENIED` |

4. Clean up: the diagnostic source was removed from the app (a copy is kept in `tools/smb_access_probe/`), `diskutil unmount "$MOUNT"` was run, and the scratch host and path files were deleted. The normal tree was rebuilt with `./gradlew assembleDebug lintDebug --rerun-tasks`, which succeeded with 0 errors and 6 warnings. The sixth warning, compared with the previous record, is `UseRequiresApi` on the existing `@TargetApi` in `PlaceNameResolver.kt`; it is unrelated to this check. The installed app, its settings, the Backdrop screensaver, and the 600000 ms timeout were not changed.

Findings:

- **File metadata is readable from the TV.** Listing entries and attribute-only opens worked for all 25 sampled HEIC files. **EXIF metadata is not readable**, because it needs file data, and every data open was denied.
- **The TV's request is not the cause.** The Mac's own kernel SMB client was denied on exactly the same 36 files. The TV was denied even when it requested only `FILE_READ_DATA`, which is narrower than production's `GENERIC_READ`. The same `FILE_READ_DATA` and `GENERIC_READ` requests read the 4 files that the Mac could also read. The Mac client's exact access mask was not captured, because `tcpdump` needs root. That gap does not change the conclusion, because both clients were denied.
- **The cause is not specific to the file extension or the decoder.** Over the Guest mount, readability tracked the reported file mode exactly in 40 of 40 cases: 0600 was denied and 0644 was readable. The denied files included 11 JPEGs. The earlier HEIC-only trial failed because every sampled HEIC was a 0600 file. Under its normal settings, the app silently skips the many unreadable JPEGs too.
- `MAXIMUM_ALLOWED` was denied even on readable files; do not use it as a probe or in production.

Unknown at the time of this initial permission sample; later follow-ups below resolved or updated several of these:

- Whether **any** HEIC/HEIF on the share is readable by Guest. None of the 25 sampled was, and no HEIF files were found. The decoder fallback therefore remains **unverified**, in both preview and DreamService.
- The server-side owner and ACL of the denied files. The Guest security-descriptor query returned `STATUS_NOT_SUPPORTED`, and the Mac mini's local view was not inspected. The reported 0600 and 0644 modes are what the server returned to a Guest client with `noowners`; they were not read on the Mac mini's disk.
- Why some originals are 0600 and others 0644. Import source, Photos version, and sync history are all possible explanations; none has been tested.
- What fraction of the whole library Guest can read. 4 of 40 sampled files were readable, but this was a sample, not a library-wide estimate.

The shared package is a clone of the live `LIVE_VOLUME` library on a second external drive, so the cloning process is also a candidate cause.

The proposed next diagnostic was a local, read-only comparison of modes on the live library and clone. That comparison was completed and its result follows.

**Result (2026-10-13, run by the user over ssh on the Mac mini; output reviewed here):** the read-only comparison is now saved as `tools/smb_access_probe/compare_clone_modes.sh`. Copy it to the Mac mini and run it with `zsh compare_clone_modes.sh`. Pasting the long one-liners into the ssh session corrupted lines containing `*`; the script avoids that. The clone resolved to the shared `Photos` volume, and the live library to the user's `Pictures` folder on `LIVE_VOLUME`. `diskutil info` reported `Owners: Disabled` (mounted `noowners`) for `LIVE_VOLUME`, and `Owners: Enabled` (no `noowners`) for the clone volume. `umask` was `0022`. Twenty-five HEIC files were `clone=600 live=600 same-owner same-bytes`. Of 25 JPEGs, 17 were `600/600` and 8 were `644/644`, all with the same owner and identical bytes. Ten 0600 HEICs had 0 ACL entries and identical xattr names on both drives, including `com.apple.assetsd.*`, `com.apple.cpl.original`, `com.apple.assetsd.cloudAsset.UUID`, and `com.apple.quarantine`. Conclusion: CCC is not the cause. Owner-only modes exist in the live library, where ownership is ignored. The clone volume enforces ownership, so Guest is denied. Still unknown: why Photos writes some originals as 0600. The script's last section compares iCloud and quarantine markers on 0600 and 0644 files to test that. Also unknown: whether Guest could read the 0600 files if the clone volume ignored ownership.

**Permission trial on 25 HEICs (2026-10-13).** The user made this storage-layer change themselves, on the live library only, using `tools/smb_access_probe/heic_perm_trial.sh` on the Mac mini. That script selects the same 25 HEICs as the comparison, records their modes in `~/photo-perm-trial/` on the Mac mini, and changes only those files with `chmod go+r`. Steps run by the user:

```sh
zsh ~/heic_perm_trial.sh select   # 25 recorded, all 600
zsh ~/heic_perm_trial.sh check    # 25 orig=600 live=600 clone=600
zsh ~/heic_perm_trial.sh apply    # changed=25 skipped=0 (Photos was still running with no window; mode-only change)
zsh ~/heic_perm_trial.sh check    # 25 orig=600 live=644 clone=600
# Photos: a Filename Smart Album showed 2 of the photos normally; mode still 644 after the Photos session
# Carbon Copy Cloner task run as usual
zsh ~/heic_perm_trial.sh check    # 25 orig=600 live=644 clone=644
```

CCC propagated the permission-only change. Verification from the development Mac and the TV:

- **Mac Guest mount, read-only:** the same 25 files were selected through the mount in the same traversal order. All 25 were mode 0644 and opened read-only, and each file header began with `ftypheic`.
- **TV SMBJ Guest diagnostic:** the same `app_process` procedure as above was used. All 25 succeeded with `FILE_READ_DATA` and with production `GENERIC_READ`. `READ_CONTROL` returned `STATUS_NOT_SUPPORTED` and `MAXIMUM_ALLOWED` returned `STATUS_ACCESS_DENIED`, both unchanged from before.
- **TV preview:** this used a temporary build, installed over the app with `adb install -r` so settings were kept. The build restricted `loadSlides()` to a path list written to the app's cache directory. The list was piped in through `run-as`, so paths were never stored in source. Temporary `HeicTrial` logs recorded the decoder path, the bitmap size, and whether a label was present. With SMB Guest selected, a 10 s interval, and **Location + date**, preview displayed HEICs with place-name and date captions and advanced. One captured log window showed 30 loads, all through `BitmapFactory`: 29 had location and date labels and 1 had a single-part label. The first session also had one load that failed. `BitmapFactory` returned null after reading the bounds, then the ImageDecoder fallback threw `IOException`, and the player skipped that file for the rest of the session. The user visually confirmed that the HEICs rendered well on the TV.
- **TV DreamService:** `adb shell settings put secure screensaver_components com.googletv.screensaver/.dream.SlideshowDreamService` and `adb shell settings put system screen_off_timeout 60000` were set, followed by `KEYCODE_HOME`. After 60 s, `DreamActivity` was resumed. Two `adb exec-out screencap -p` captures 11 s apart differed, and both showed labeled HEICs. The captured logs showed 4 loads, all `BitmapFactory` with location and date labels. Part of the dream log was lost to the TV's 1 MiB logcat buffer and to a capture-file mistake, so a full 25-file count was not taken; the user agreed that this was unnecessary. The failure did not recur in the captured lines.
- **Observations:** decoded bitmaps were 1008×756. Power-of-two sampling of 4032×3024 originals undershoots the 1080p display. One portrait HEIC displayed rotated 90°, because EXIF orientation is not applied yet; this was already a roadmap item. On this TV, the ImageDecoder fallback has not yet decoded a HEIC successfully. Its one attempt failed, and the cause, whether transient or file-specific, is unknown.
- **Restore:** `adb shell input keyevent KEYCODE_WAKEUP`, Backdrop restored, `screen_off_timeout 600000` restored, trial list deleted, temporary code reverted with `git checkout`, and `./gradlew assembleDebug lintDebug --rerun-tasks` run (0 errors, 6 warnings). The normal APK was reinstalled with `adb install -r app/build/outputs/apk/debug/app-debug.apk`, and the share was unmounted. Screenshots and logs were deleted from scratch.

The 25 files remain 0644 on both drives until the user runs `zsh ~/heic_perm_trial.sh revert` followed by a CCC run. This confirms the owner, mode, and ACL that Guest is evaluated against. It requires no share or permission change. Any fix requires a product decision by the user. Options include different permissions, an authenticated read-only account that can read these files, or a Mac-side export folder with readable copies. Do not change share permissions as part of a diagnostic.
