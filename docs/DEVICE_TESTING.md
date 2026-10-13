# TCL TV setup and test log

Follow the setup steps in order. Each step lists its current status. Record results in [Device findings](#device-findings) and never store the TV's IP address, the Mac mini's IP address, pairing codes, serial numbers, photo bytes, or photo filenames in this repository.

## Known TV facts

Read from Settings → System → About on the TV, 2026-10-11:

| Field | Value |
| --- | --- |
| Android TV OS | 12 (API level 31) |
| Security patch level | 2024-10-05 |
| Kernel | 5.4.233+, built 2024-11-28 |
| OS build | STT2.230203.001 |
| Product/firmware info | V8-T65T02-LF1V187.001303. A TCL platform/firmware code, not a model name; community sources read it as platform branch, board code, and software version. Useful for tracking firmware updates. |
| Model | TCL 65" A300 (NXTFRAME art TV, 2024), per the on-TV user guide and the box. Consistent with the Android TV 12 / October 2024 software. A Best Buy listing for the similar [65A3G Designer Series (2026)](https://support.tcl.com/en_US/a3g-series) was considered and ruled out. ADB reports generic `Smart TV` for `ro.product.model`. |

What this implies:

- Use `minSdk 31` so the app can install on this TV. Check current official documentation before choosing `targetSdk` and `compileSdk`.
- The [official adb guide](https://developer.android.com/tools/adb) describes pairing-code wireless debugging. This TV's Developer options screen did not list Wireless debugging. USB debugging enabled legacy network ADB on port 5555; that port was not reachable before USB debugging was enabled.
- Community reports (see [Aerial Views setup](https://github.com/theothernt/AerialViews)) say Google TV devices running Android TV 12 or later have no settings menu for choosing a third-party screensaver; one is selected with `adb` instead (step 4).
- The TCL Auto Launch `appops` name for Android TV 13 or lower is `APP_AUTO_START` (step 5).
- The A300 is TCL's NXTFRAME "art TV". Per [TCL support](https://support.tcl.com/en_US/t-exhibition-turning-your-tcl-tv-into-a-work-of-art) and launch coverage, it has its own idle **Art Mode** (the T-Exhibition app) with an art library, AI art, and a personal photo gallery loaded from USB or the TCL Home phone app. This is separate from Google TV's Ambient mode. It is unknown whether Art Mode replaces, precedes, or blocks the Android screensaver set by `screensaver_components`; step 4 must find out.

## Setup sequence

### 1. Install the Android toolchain on the development Mac

Status: **done.** On 2026-10-11, Android Studio 2026.2.1.8, Android SDK command-line tools, and platform-tools were installed with Homebrew. The first-run setup completed. `adb version` reports 37.0.1; Android Studio's bundled JDK is available. The installed SDK includes platform 37.0, Build Tools 36.0.0, and an Android Emulator system image. These are available for later use; choose the app's compile/target SDK from current official guidance before project generation. The earlier Homebrew failure (unaccepted Xcode license) is resolved: `xcodebuild -license check` succeeds.

Open Android Studio and complete its first-run setup to install the Android SDK. Review and accept the Android SDK license terms in the setup flow before SDK packages are installed. Android Studio's bundled JDK is available; Homebrew-installed `adb` and SDK command-line tools are also on `PATH`. Command-line Gradle needs a JDK compatible with the chosen Android Gradle Plugin; check its release notes. Never commit `local.properties`.

Verify:

```sh
adb version
```

### 2. Connect adb to the TV

Status: **done.** Requires step 1.

On the TV, open Settings → System → Developer options. If **Wireless debugging** is available, enable it and choose **Pair device with pairing code**. Keep the TV and Mac on the same network. The TV displays a pairing address, pairing port, and temporary code. On the Mac, run:

```sh
adb pair TV_IP:PAIRING_PORT
```

Enter the code when prompted. Then use the separate IP address and connection port shown in the Wireless debugging screen:

```sh
adb connect TV_IP:DEBUG_PORT
adb devices
```

Accept any debugging authorization prompt on the TV. If Wireless debugging is not available on this firmware, enable **USB debugging** and try the legacy network ADB endpoint on port 5555; on this TV it became reachable after USB debugging was enabled. A physical USB cable was not needed. The TV showed the RSA authorization prompt; selecting **Always allow from this computer** authorized the development Mac. Record the available menu and connection result in [Device findings](#device-findings).

### 3. Record device facts

Status: **done** (see [Known TV facts](#known-tv-facts) and [Device findings](#device-findings)). Requires step 2.

```sh
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.model
adb shell settings get secure screensaver_components
adb shell settings get system screen_off_timeout
```

ADB reports SDK `31` and generic model `Smart TV`. The original `screensaver_components` value is `com.google.android.apps.tv.dreamx/.service.Backdrop`; `screen_off_timeout` is `600000` ms (10 minutes). These are recorded below and must be restored after any screensaver tests.

### 4. Learn how screensavers are selected on this TV

Status: **partly done.** Settings and idle behavior were inspected on 2026-10-12. The Ambient mode page offered Google's Backdrop settings and a Start now action. T-Exhibition Art Mode settings showed Wallpaper Time at 2 minutes and Keep Screen On enabled; neither was changed. With the original Backdrop selected, Google Ambient displayed on idle. The app DreamService also ran when explicitly selected with ADB. Art Mode was not toggled, so precedence between Art Mode and the app dream remains unverified. Requires step 3.

Check Settings → System → Ambient mode / Screensaver and record which options are shown. Also find the Art Mode / T-Exhibition settings (look for an idle or Art Mode toggle and its timeout) and record them. Let the TV go idle with no changes and note what appears first: Art Mode, Google's Ambient mode, or nothing.

Then select a third-party dream with `adb`:

```sh
adb shell settings put secure screensaver_components PACKAGE/DREAM_SERVICE_CLASS
adb shell settings get secure screensaver_components
```

Restore the original value recorded in step 3 with the same `settings put` command. The screen-off timeout is in milliseconds. A temporary 60000 ms timeout successfully triggered our dream once on this TV; this does not establish the TV's minimum supported timeout. The original value recorded in step 3 was 600000 ms (10 minutes):

```sh
adb shell settings put system screen_off_timeout 600000
```

After selecting a dream, let the TV go idle again with Art Mode enabled, then with Art Mode turned off, and record which one runs in each case. If Art Mode always takes over, the third-party screensaver may only work with Art Mode turned off.

### 5. Allow TCL Auto Launch

Status: **partly done.** The app dream activated during idle tests without changing Auto Launch settings. Safety Guard / Permission Shield was not present in the TV's available apps, and `adb shell appops get` returned no operations; `adb shell appops set com.googletv.screensaver APP_AUTO_START allow` returned `Unknown operation string`. The firmware's Auto Launch state and whether changing it is required remain unverified; requires step 4 and an installed screensaver app.

According to the [Aerial Views maintainer](https://github.com/theothernt/AerialViews), some TCL Google TVs do not start a third-party screensaver unless the app has Auto Launch permission. On the TV, open Safety Guard → Permission Shield → Auto Launch Permission, set Auto manager to Closed, and set the app to Opened. If Safety Guard is missing, on Android TV 13 or lower:

```sh
adb shell appops set PACKAGE APP_AUTO_START allow
```

The successful test does not establish whether Auto Launch permission was enabled already or whether it is required under other conditions. Record its current state, then test the dream with the setting enabled and disabled if practical.

### 6. Optional: prove the whole path with Aerial Views

Status: **not done.** Requires steps 2–5. Takes about 30 minutes and needs no project code.

[Aerial Views](https://github.com/theothernt/AerialViews) is an open-source TV screensaver that can show photos from SMB. Install it from Google Play on the TV, add the Mac mini's `Photos` share as an SMB source pointing at `Photos Library.photoslibrary/originals`, then set it as the screensaver using step 4. Its component name from the maintainer's guide is:

```sh
adb shell settings put secure screensaver_components com.neilturner.aerialviews/.ui.screensaver.DreamActivity
```

Then apply step 5 with package `com.neilturner.aerialviews`. Record whether it can use SMB Guest access, whether HEIC photos display, whether the screensaver starts on idle, and whether it survives a TV restart. Restore the original screensaver afterward. Success proves idle activation, Auto Launch, SMB from the TV, and HEIC decoding on this TV before our own code depends on them. Use a test folder first if you do not want the full library shown during the experiment (see [hidden and deleted photos](PHOTO_SOURCES.md#what-originals-contains)).

### 7. Build and install our app

Status: **partly done.** D-pad settings, preview, interval persistence, SMB Guest photo changes and labels, and idle DreamService activation passed on the TV. The permission-denied HEIC/JPEG sample was resolved at the storage layer; after the permission change, 12 MP HEICs displayed in preview and DreamService. A random 10-photo sample played 9 of 10; the failure was a 24 MP HEIC. Subsequent TV diagnostics found that all tested platform decode paths fail for 24 MP files during native HEIF frame extraction (see [the decode diagnosis](#device-findings)). EXIF orientation is now applied to `BitmapFactory` results and has been visually verified with two portrait 12 MP HEIC samples on the TV. TCL Auto Launch and Art Mode interaction remain to be checked. From `apps/screensaver/`:

```sh
./gradlew assembleDebug lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the app from the TV launcher. Verify D-pad focus, interval persistence, slideshow layout, and Back behavior. Then repeat steps 4 and 5 with our package and `DreamService` class, and record whether the dream starts on idle.

## Mac mini source investigation

On 2026-10-11, read-only checks of `/Volumes/Photos/Photos Library.photoslibrary` found an `originals` directory with JPEG and HEIC samples that could be opened and read. The package also contains databases and resource directories; its internal folders are not Photos albums. A later Photos settings screenshot from the Mac mini showed the active System Photo Library under `Samsung_T5`, That was recorded as the same location reached through the SMB `Photos` share. **Correction (2026-10-13):** the shared package is a cloned copy of that library on a second external drive, not the live library. The user expects the clone to contain exactly the same files; this has not been verified, and identical file contents do not guarantee identical ownership or modes. The chosen integration is read-only SMB access to available image files below `originals`; no package database access or writes. Photos is set to **Download Originals to this Mac** (confirmed 2026-10-13). CCC and the manual permission script each run once a day, with the permission script before the clone refresh.

Read-only SMB check from the development Mac (the host address was passed at runtime and is intentionally omitted here):

```sh
python3 tools/check_smb_source.py "$MAC_MINI_IP"
```

Result: SMB port 445 reachable; noninteractive share listing accepted using the current Mac session; `Photos Library.photoslibrary/originals/` present with 16 immediate subdirectories; three sample JPEG files readable. The share was mounted by Finder for this check. This verifies access from the development Mac, **not from the TCL TV**. No private image bytes or filenames were saved to the repository.

### Guest-mode SMB check

On 2026-10-11, the development Mac mounted the share explicitly as Guest at `$HOME/PhotosGuest` using macOS `mount_smbfs`. The mount source showed `//guest:@<host>/Photos`. Running the checker against that mount:

```sh
python3 tools/check_smb_source.py "$MAC_MINI_IP" --mount-path "$HOME/PhotosGuest"
```

Result: current-session share listing rejected and identity unavailable (exit 77); Guest-only share listing accepted and included `Photos`; anonymous-only listing rejected; SMB port 445 reachable; the Photos library originals path was present; the recursive extension-based inventory completed and three sample images were readable. This verifies read-only library access using SMB Guest from the development Mac. It does not verify the Android app or the TCL TV. No private IP address, photo bytes, or filenames are stored here.

### HEIC `STATUS_ACCESS_DENIED` investigation (2026-10-13)

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

The shared package is a clone of the live `Samsung_T5` library on a second external drive, so the cloning process is also a candidate cause.

The proposed next diagnostic was a local, read-only comparison of modes on the live library and clone. That comparison was completed and its result follows.

**Result (2026-10-13, run by the user over ssh on the Mac mini; output reviewed here):** the read-only comparison is now saved as `tools/smb_access_probe/compare_clone_modes.sh`. Copy it to the Mac mini and run it with `zsh compare_clone_modes.sh`. Pasting the long one-liners into the ssh session corrupted lines containing `*`; the script avoids that. The clone resolved to the shared `Photos` volume, and the live library to the user's `Pictures` folder on `Samsung_T5`. `diskutil info` reported `Owners: Disabled` (mounted `noowners`) for `Samsung_T5`, and `Owners: Enabled` (no `noowners`) for the clone volume. `umask` was `0022`. Twenty-five HEIC files were `clone=600 live=600 same-owner same-bytes`. Of 25 JPEGs, 17 were `600/600` and 8 were `644/644`, all with the same owner and identical bytes. Ten 0600 HEICs had 0 ACL entries and identical xattr names on both drives, including `com.apple.assetsd.*`, `com.apple.cpl.original`, `com.apple.assetsd.cloudAsset.UUID`, and `com.apple.quarantine`. Conclusion: CCC is not the cause. Owner-only modes exist in the live library, where ownership is ignored. The clone volume enforces ownership, so Guest is denied. Still unknown: why Photos writes some originals as 0600. The script's last section compares iCloud and quarantine markers on 0600 and 0644 files to test that. Also unknown: whether Guest could read the 0600 files if the clone volume ignored ownership.

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

## Optional browser-only Apple Photos check

Lower priority now that SMB is the chosen source. If testing the web alternative, search Apps on the TV for a browser, install one available for this model, and visit `https://www.icloud.com/photos/`. Test sign-in, navigating to an album, starting a full-screen slideshow, and controlling it with the remote. Record whether the browser remains open and whether idle/sleep interrupts playback. Apple's supported-browser list does not include TV browsers, so success is device-specific. Do not enter Apple credentials into a browser you do not trust.

## Device findings

| Date | TV model / OS | Test | Result |
| --- | --- | --- | --- |
| 2026-10-11 | TCL 65" A300 / Android TV 12, build STT2.230203.001 | OS facts from Settings → System → About; model from on-TV user guide and box | Recorded in [Known TV facts](#known-tv-facts) |
| 2026-10-11 | Development Mac | Xcode license check | Accepted; Android toolchain had not yet been installed |
| 2026-10-12 | TCL 65" A300 / Android TV 12 | Developer options menu | Wireless debugging is not listed; USB debugging was enabled afterward. |
| 2026-10-12 | TCL 65" A300 / Android TV 12 | Legacy ADB network connection and authorization | Port 5555 initially refused while USB debugging was off. After enabling it, ADB connected; accepting **Always allow from this computer** changed device state from `unauthorized` to `device`. No physical USB connection was needed. |
| 2026-10-12 | TCL 65" A300 / Android TV 12 | Device properties and original screensaver settings (step 3) | SDK 31; `ro.product.model=Smart TV`; `screensaver_components=com.google.android.apps.tv.dreamx/.service.Backdrop`; `screen_off_timeout=600000` ms (10 minutes). Read-only; settings not changed. |
| 2026-10-12 | TCL 65" A300 / Android TV 12 | Installed DreamService inventory (read-only, step 4) | Before the app install, `adb shell cmd package query-services --brief -a android.service.dreams.DreamService` found built-in Colors (`com.android.dreams.basic/.Colors`) and Backdrop (`com.google.android.apps.tv.dreamx/.service.Backdrop`). |
| 2026-10-12 | Development Mac | First app build | `cd apps/screensaver && ./gradlew assembleDebug lintDebug` succeeded using Android Studio's JDK 25.0.3, AGP 9.2.0, Gradle 9.4.1, compile SDK 37, target SDK 34, and min SDK 31. After addressing unused resources, one unused dream attribute, and localized strings, lint reports 0 errors and 3 warnings: `OldTargetApi`, `AndroidGradlePluginVersion`, and `DataExtractionRules`. The target SDK warning is retained pending a platform policy decision; backup is intentionally disabled. |
| 2026-10-12 | TCL 65" A300 / Android TV 12 | First app install and launcher/service discovery | `adb install -r app/build/outputs/apk/debug/app-debug.apk` succeeded. Package query found `com.googletv.screensaver/.dream.SlideshowDreamService`. The launcher screen opened. |
| 2026-10-12 | TCL 65" A300 / Android TV 12 | Manual slideshow preview and settings | D-pad changed the interval to 10 seconds; preview showed the landscape sample and advanced to the portrait sample. Back returned to settings. Interval was restored to 30 seconds and remained 30 seconds after force-stop/relaunch. Existing system Backdrop component and 600000 ms screen-off timeout were not changed. |
| 2026-10-12 | TCL 65" A300 / Android TV 12 | App DreamService idle activation | Temporarily set `screensaver_components` to `com.googletv.screensaver/.dream.SlideshowDreamService` and `screen_off_timeout` to 60000 ms. The TV started the app's `DreamActivity` after roughly one minute idle; the landscape and portrait samples advanced at the configured 30-second interval. In a follow-up after requesting a bright dream, the user confirmed normal brightness and that both landscape and portrait photos fit the screen. Waking returned to Google TV. Restored Backdrop (`com.google.android.apps.tv.dreamx/.service.Backdrop`) and `screen_off_timeout=600000` ms; current settings were verified. The Auto Launch setting and Art Mode state were not inspected or changed. |
| 2026-10-12 | TCL 65" A300 / Android TV 12 | Async-source sample regression and SMB Guest attempt | Installed the uncommitted SMB-source build. The bundled landscape sample displayed through the new worker-thread loading path. The SMB Guest preview failed during session setup with `SMB2GuestSigningRequiredException`, before share enumeration; no source images were loaded. This is a client/server signing compatibility failure, not evidence that TV Guest listing works. |
| 2026-10-12 | TCL 65" A300 / Android TV 12 | SMB2-only client negotiation trial | Rebuilt and installed the app with SMBJ limited to SMB 2.0.2/2.1. Guest preview still failed with `SMB2GuestSigningRequiredException` before share enumeration. Removed the ineffective client restriction afterward; restored Sample photos as the selected source. |
| 2026-10-12 | Mac mini SMB server (macOS) | Server signing requirement | `python3 tools/check_smb_signing.py "$MAC_MINI_IP"` reported `SIGNING_REQUIRED=True` (dialect SMB 3.0.2). The user backed up `/Library/Preferences/SystemConfiguration/com.apple.smb.server.plist` and set `SigningRequired -bool false`. `launchctl kickstart -k system/com.apple.smbd` alone did not change the result. Following Apple's sequence (stop smbd with `launchctl disable`/`bootout`, run `/usr/libexec/smb-sync-preferences`, then `launchctl enable`/`bootstrap`), the probe reported `SIGNING_REQUIRED=False`. `smb-sync-preferences` printed an unload error and "No matching processes" because smbd was already stopped; this was harmless. See [the server change](PHOTO_SOURCES.md#mac-mini-signing-change-2026-10-12). |
| 2026-10-12 | TCL 65" A300 / Android TV 12 | SMB Guest manual preview after server change | With the uncommitted SMB-source build already installed and no app changes, the user selected **SMB Guest share** (`Photos` share, default `originals` folder) and opened **Preview slideshow**. Photos from the share displayed. First successful TV-side Guest session setup, listing, and image reads. HEIC decoding was not checked. Dream (idle) mode was tested separately below. |
| 2026-10-12 | TCL 65" A300 / Android TV 12 | SMB Guest DreamService idle test | With SMB Guest selected, temporarily set the app DreamService as the screensaver and reduced the idle timeout to 60000 ms. The TV entered the app dream and displayed Photo 1 without an error. Restored the original Backdrop screensaver and 600000 ms timeout afterward. Progression to a second SMB photo was not confirmed. |
| 2026-10-12 | TCL 65" A300 / Android TV 12 | Random photo pool, unreadable-file skip, and metadata labels | Installed the updated app. The 100, 250, 500, and 1,000 pool sizes and three label modes fit the remote-friendly settings screen. SMB preview scanned the configured tree, displayed randomized photos, advanced to another photo, and showed an EXIF capture-date label on a photo with date metadata. Unreadable SMB files were skipped while playback continued. Location coordinates were not observed in this preview. |
| 2026-10-12 | Development Mac | Place-name label build and lint | `cd apps/screensaver && JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew assembleDebug lintDebug` succeeded. Lint reported 0 errors and 5 warnings (the previous target SDK, Gradle, and backup warnings plus two existing platform `ExifInterface` warnings). The Geocoder branch compiles for min SDK 31. |
| 2026-10-12 | TCL 65" A300 / Android TV 12 (API 31) | Place-name Geocoder check with a synthetic GPS sample | Added GPS metadata to a temporary copy of the bundled mountain sample, built a separate `com.googletv.screensaver.geotest` APK with `./gradlew assembleDebug --quiet`, and installed it with `adb install -r /tmp/googletv-geotest.apk`. In **Location + date** preview, the TV's UI hierarchy showed both caption text and image description as **Mountain View, California**. This confirms the API 31 lookup path and rendered label on this TV for a known coordinate. The test app was uninstalled, temporary source changes were restored, and the normal `./gradlew assembleDebug lintDebug` build passed again. No private photos were used. |
| 2026-10-12 | TCL 65" A300 / Android TV 12 (API 31) | Real SMB photo place-name check | Mounted the configured `Photos` share explicitly as SMB Guest and read-only on the development Mac. A metadata-only scan found GPS-tagged JPEGs under `originals`; no library database was opened. A separate temporary test APK used the production SMB image-loading and label path, with its slide list limited to one GPS-tagged file for a deterministic check. Installed it with `adb install -r /tmp/googletv-smbfinaltest.apk`, opened **Location + date** preview, and inspected the TV UI hierarchy. The real SMB photo displayed with a place-name caption and EXIF capture date. The test output recorded only whether each component appeared, not the photo name, place, coordinates, or share address. The test app was uninstalled and the normal debug APK installed with `adb install -r apps/screensaver/app/build/outputs/apk/debug/app-debug.apk`; Backdrop remains selected as the system screensaver. The Guest mount, temporary host files, and UI dumps were removed. |
| 2026-10-12 | TCL 65" A300 / Android TV 12 (API 31) | User visual confirmation of place labels | The user confirmed that the TV showed location names for the shortlisted images. Coordinate fallback and labels across the full library remain unverified. |
| 2026-10-12 | TCL 65" A300 / Android TV 12 (API 31) | SMB EXIF stream diagnosis | On two real SMB JPEGs, Android's platform EXIF reader returned no GPS or date from SMBJ's direct input stream. A temporary diagnostic build showed the same images' metadata parsed when read from a byte array. A 256 KiB prefix also worked; the app now reads at most 512 KiB into memory for metadata. `cd apps/screensaver && JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew assembleDebug lintDebug` passed after the production fix. |
| 2026-10-12 | Development Mac and TCL 65" A300 / Android TV 12 (API 31) | End-to-end SMB Guest DreamService check and HEIC investigation | Exact build: from the repository root, `cd apps/screensaver && JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew assembleDebug lintDebug` succeeded (0 errors, 5 warnings). Exact install: `adb install -r apps/screensaver/app/build/outputs/apk/debug/app-debug.apk` succeeded. For the idle test: `adb shell settings put secure screensaver_components com.googletv.screensaver/.dream.SlideshowDreamService`; `adb shell settings put system screen_off_timeout 60000`; `adb shell input keyevent KEYCODE_HOME`; wait about 60 seconds; `adb shell dumpsys activity activities` showed the app's `DreamActivity`; `adb exec-out screencap -p` showed an SMB photo with a location/date caption. A second capture 11 seconds later differed, confirming advancement. `adb shell input keyevent KEYCODE_WAKEUP` returned to the Google TV launcher. Original settings were restored with `adb shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop` and `adb shell settings put system screen_off_timeout 600000`; `adb shell settings get secure screensaver_components` and `adb shell settings get system screen_off_timeout` verified both values. The regular app preview on this build also rendered a labeled SMB photo and advanced to a different photo. For decoder isolation, the extension filter was temporarily set to `setOf("heic", "heif")`, then built with `./gradlew assembleDebug` and installed with `adb install -r /tmp/googletv-heic-diagnostic-debug.apk`. That HEIC-only trial failed before decoding: SMB returned `STATUS_ACCESS_DENIED` opening the candidate entries as Guest. The filter was restored and the normal all-format APK was rebuilt and reinstalled. The app's API 28+ ImageDecoder fallback was added, but HEIC decoding on this share is **not verified** until Guest can read one. No share permissions were changed; SMB remained read-only. Temporary screenshots/test APKs were kept outside the repository and removed after testing. |
| 2026-10-13 | Development Mac and TCL 65" A300 / Android TV 12 (API 31) | HEIC `STATUS_ACCESS_DENIED` comparison, Mac Guest mount vs. TV SMBJ Guest, same 25 HEIC and 15 JPEG files | Mac read-only Guest mount: all 40 `stat` calls succeeded; opens failed with `EACCES` on 25/25 HEIC and 11/15 JPEG. All 36 failures were reported as mode 0600, and all 4 readable JPEGs as 0644. TV (`guest=true`): listing and `FILE_READ_ATTRIBUTES` opens succeeded on all 40. `READ_CONTROL` security query returned `STATUS_NOT_SUPPORTED`. `FILE_READ_DATA` and `GENERIC_READ` opens returned `STATUS_ACCESS_DENIED` on the same 36 files and read the same 4. Conclusion: per-file Guest read permission on the server, not file extension, decoder, or the TV's access mask. No readable HEIC was found, so the decoder fallback is still unverified. Commands and unknowns are in [the HEIC investigation](#heic-status_access_denied-investigation-2026-10-13). No TV settings or share permissions were changed. |
| 2026-10-13 | Development Mac and TCL 65" A300 / Android TV 12 (API 31) | 24 MP HEIC decode diagnosis | Three 5712×4284 HEICs and one 4032×3024 control were read into memory over Guest by `tools/smb_access_probe/HeicDecodeDiagnostic.kt`, run via `app_process`, with logcat captured. **All three 24 MP files failed every platform path**: `BitmapFactory` at sample sizes 4, 8, and 16 returned null; `ImageDecoder` threw `IOException: getPixels failed with error invalid input`; `BitmapRegionDecoder` returned null; and `MediaMetadataRetriever.getPrimaryImage` returned null. The 12 MP control succeeded on every path. Logcat: MediaTek's `MtkMP4Extractor` parsed each 24 MP file successfully. The primary image is a 5×9 grid of **640×896** tiles, with 90° rotation and max input 860160 bytes. Then `StagefrightMetadataRetriever: all codecs failed to extract frame` appeared **without any decoder being created**, followed by `HeifDecoderImpl: decode: metadata is a nullptr`. No reason was logged. 12 MP files are a 6×8 grid of 512×512 tiles, decoded tile by tile by `c2.android.hevc.decoder`. Container parsing on the Mac (read-only) showed that the primary tiles of both sizes are HEVC Main Still Picture, 8-bit 4:2:0. 24 MP tiles declare level 3.1 and 12 MP tiles level 3.0. `tools/smb_access_probe/HeicCapsProbe.kt` showed that `c2.android.hevc.decoder` and `c2.mtk.hevc.decoder` both accept 640×896 at Main level 3.1 (`isFormatSupported` true). So the rejection happens inside the TV's native, vendor-modified HEIF frame extraction, not in codec capability, and the app cannot configure around it. Sampled share of 24 MP: 6 of 29 HEICs seen so far, a sample rather than a library-wide count. These are likely the newest iPhone photos. The installed app was not changed. The diagnostic APK, mount, and scratch data were removed. |
| 2026-10-13 | Development Mac and TCL 65" A300 / Android TV 12 (API 31) | Random library sample, 10 photos | Picked a random subfolder, then a random supported file, ten times (8 of 16 subfolders listed; no full walk). Through a read-only Guest mount, all 10 were 0644 and readable: 4 HEIC, 5 JPEG, 1 PNG. On the TV, a temporary build restricted preview to those paths in list order, with the same `trial_paths.txt` and `HeicTrial` logging as the 25-file trial, and the user watched. 9 of 10 displayed. All 9 decoded with `BitmapFactory`: 3 HEIC at 1008×756 and 5 JPEG and 1 PNG at various sizes. The two screenshot-like images had no label, as expected. **1 HEIC failed**: `BitmapFactory` returned null after reading the bounds, then the ImageDecoder fallback threw `IOException`, and the file was skipped. Read-only `sips` and `ftyp` checks on the Mac showed that the failing file is **5712×4284** (24 MP), 8-bit, Display P3, with `tmap` (gain map) brands. The three that displayed were 4032×3024. One of them had the same `tmap` brands, so the gain map alone is not the cause. The earlier 25-HEIC trial contained 5 files of 5712×4284 and 20 of 4032×3024, and every logged HEIC success was 1008×756, the 4032×3024 decode size; a 5712×4284 decode would be 1428×1071. That trial's single logged failure probably undercounts: all 5 large files likely failed and were dropped, with most skip lines lost to the 1 MiB log buffer. The subsequent decoder diagnosis confirmed that the tested 24 MP HEICs fail across all tested platform decode paths; 12 MP HEICs work. Restored afterwards: temporary code reverted, `./gradlew assembleDebug lintDebug --rerun-tasks` (0 errors, 6 warnings), normal APK reinstalled, trial list deleted, Backdrop and 600000 ms timeout verified, mount removed. |
| 2026-10-13 | Mac mini (user over ssh) | Library-wide photo permission change | `make_photos_readable.sh scan` found 18,601 supported photos not readable by others: 8,974 HEIC, 9,188 JPEG, 438 PNG, 1 WebP. `apply` reported `found=18601 still_unreadable=0 chmod_error=0`. After a CCC run, `scan` on the clone reported a total of 0. A launchd agent for daily runs failed with `Operation not permitted` listing `Samsung_T5` (TCC) and was removed. The user now runs the permission script once a day before the daily clone. A random 10-photo sample was tested on the TV: 9 of 10 displayed; the failing 24 MP HEIC is covered in the decode diagnosis above. A full sample after the daily workflow is not recorded. See [photo sources](PHOTO_SOURCES.md#guest-read-permissions-per-file-2026-10-13). |
| 2026-10-13 | Mac mini, development Mac, and TCL 65" A300 / Android TV 12 (API 31) | HEIC permission trial (25 files) | The user made 25 sampled HEICs 0644 on the live library, and CCC propagated the change to the clone. Photos still opened sampled photos, and the mode survived a Photos session. Mac Guest read 25/25. The TV SMBJ Guest opened 25/25 with `GENERIC_READ`. Preview and DreamService displayed HEICs with location/date labels and advanced; the decode logs showed `BitmapFactory` on every logged success. One preview load failed in both decoders (`IOException`), cause unknown. One portrait HEIC displayed rotated (EXIF orientation not applied). Details: [the HEIC investigation](#heic-status_access_denied-investigation-2026-10-13). TV settings restored. |
| 2026-10-13 | Mac mini (user over ssh, read-only) | Live library vs. CCC clone: modes, ownership, bytes | 25/25 HEIC were 0600 on both drives. Of 25 JPEGs, 17 were 0600 and 8 were 0644 on both. All 50 had the same owner and identical bytes. `Samsung_T5`: Owners Disabled (`noowners`). Clone volume: Owners Enabled. The owner-only modes come from the live library, not from CCC; the clone volume's ownership enforcement is why Guest is denied. See [the HEIC investigation](#heic-status_access_denied-investigation-2026-10-13). |
| 2026-10-13 | Development Mac and TCL 65" A300 / Android TV 12 (API 31) | EXIF orientation handling on portrait HEICs | Mounted the `Photos` SMB share read-only and recreated the first-25 HEIC/HEIF selection used in the earlier trial. Build and install of the updated app succeeded. Selected two distinct 12 MP samples whose EXIF orientation was 6; the TV log showed 756×1008 bitmaps for both, and the preview screenshots showed both upright. Temporary test-path/debug hooks, path lists, and screenshots were removed; the normal APK was reinstalled. Backdrop and the 600000 ms screen timeout were verified restored, and the SMB mount was removed. |
| 2026-10-13 | Development Mac and TCL 65" A300 / Android TV 12 (API 31) | Softer photo labels | `./gradlew assembleDebug` succeeded, and the updated app was installed for a preview. A photo label remained readable and looked softer with 85% white text opacity and 44% black shadow opacity. Loading/error status messages retain full opacity. The preview was stopped and the TV returned to Home; Backdrop and the 600000 ms screen timeout were verified unchanged. |
| 2026-10-12 | TCL 65" A300 / Android TV 12 | Art Mode and Auto Launch settings review | T-Exhibition showed Wallpaper Time 2 minutes and Keep Screen On enabled. The TV's available apps did not include Safety Guard / Permission Shield; the Auto Launch permission state could not be found. The appop fallback was unsupported on this firmware (`APP_AUTO_START` unknown). DreamService nevertheless started during the explicit idle tests; this does not establish the current permission state or Art Mode precedence. No TCL settings were changed. |
| Pending | TCL 65" A300 / Android TV 12 | Place-name fallback and other label modes | Verify coordinates when the Geocoder cannot resolve a real SMB photo, and check date-only and no-label modes on the TV. |
| 2026-10-12 | TCL 65" A300 / Android TV 12 | Ambient mode screensaver options (step 4) | Settings showed Google Backdrop / Ambient mode controls and a Start now action. No third-party selection menu was shown; use ADB. |
| Pending | TCL 65" A300 / Android TV 12 | Art Mode precedence with Art Mode on and off (step 4) | Original Backdrop appeared on idle; app DreamService ran when selected through ADB. Art Mode was left unchanged, so its precedence is unknown. |
| Pending | Pending | Aerial Views over SMB Guest, HEIC, idle start (step 6) | Not tested |
