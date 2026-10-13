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
| Model | TCL 65" A300 (NXTFRAME art TV, 2024), per the on-TV user guide and the box. Consistent with the Android TV 12 / October 2024 software. A Best Buy listing for the similar [65A3G Designer Series (2026)](https://support.tcl.com/en_US/a3g-series) was considered and ruled out. ADB reports generic `Smart TV` for `ro.product.model` and `G08_4K_US_NF` for `ro.product.device`. |

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

ADB reports SDK `31`, generic model `Smart TV`, and device product `G08_4K_US_NF`. The original `screensaver_components` value is `com.google.android.apps.tv.dreamx/.service.Backdrop`; `screen_off_timeout` is `600000` ms (10 minutes). These are recorded below and must be restored after any screensaver tests.

### 4. Learn how screensavers are selected on this TV

Status: **partly done.** ADB-selected dream activation is recorded below. The screensaver options in Settings and the TCL Art Mode interaction still need testing. Requires step 3.

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

Status: **partly done.** The app's dream activated during one idle test without changing the Auto Launch setting. The setting itself and its effect have not been checked; requires step 4 and an installed screensaver app.

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

Status: **partly done.** The first Kotlin app slice is built and installed. D-pad settings, preview, interval persistence, photo changes, and idle activation passed on the TV. TCL Auto Launch permission and Art Mode interaction remain to be checked. From `apps/screensaver/`:

```sh
./gradlew assembleDebug lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the app from the TV launcher. Verify D-pad focus, interval persistence, slideshow layout, and Back behavior. Then repeat steps 4 and 5 with our package and `DreamService` class, and record whether the dream starts on idle.

## Mac mini source investigation

On 2026-10-11, read-only checks of `/Volumes/Photos/Photos Library.photoslibrary` found an `originals` directory with JPEG and HEIC samples that could be opened and read. The package also contains databases and resource directories; its internal folders are not Photos albums. A later Photos settings screenshot from the Mac mini showed the active System Photo Library under `Samsung_T5`, and the user confirmed this is the same location reached through the SMB `Photos` share. The chosen integration is read-only SMB access to available image files below `originals`; no package database access or writes. Whether all iCloud originals are available locally remains unverified: check Photos → Settings → iCloud on the Mac mini for "Download Originals to this Mac" versus "Optimize Mac Storage".

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

## Optional browser-only Apple Photos check

Lower priority now that SMB is the chosen source. If testing the web alternative, search Apps on the TV for a browser, install one available for this model, and visit `https://www.icloud.com/photos/`. Test sign-in, navigating to an album, starting a full-screen slideshow, and controlling it with the remote. Record whether the browser remains open and whether idle/sleep interrupts playback. Apple's supported-browser list does not include TV browsers, so success is device-specific. Do not enter Apple credentials into a browser you do not trust.

## Device findings

| Date | TV model / OS | Test | Result |
| --- | --- | --- | --- |
| 2026-10-11 | TCL 65" A300 / Android TV 12, build STT2.230203.001 | OS facts from Settings → System → About; model from on-TV user guide and box | Recorded in [Known TV facts](#known-tv-facts) |
| 2026-10-11 | Development Mac | Xcode license check | Accepted; Android toolchain had not yet been installed |
| 2026-10-12 | TCL 65" A300 / Android TV 12 | Developer options menu | Wireless debugging is not listed; USB debugging was enabled afterward. |
| 2026-10-12 | TCL 65" A300 / Android TV 12 | Legacy ADB network connection and authorization | Port 5555 initially refused while USB debugging was off. After enabling it, ADB connected; accepting **Always allow from this computer** changed device state from `unauthorized` to `device`. No physical USB connection was needed. |
| 2026-10-12 | TCL 65" A300 / Android TV 12 | Device properties and original screensaver settings (step 3) | SDK 31; `ro.product.model=Smart TV`; `ro.product.device=G08_4K_US_NF`; `screensaver_components=com.google.android.apps.tv.dreamx/.service.Backdrop`; `screen_off_timeout=600000` ms (10 minutes). Read-only; settings not changed. |
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
| Pending | Pending | Screensaver options in Settings (step 4) | Not tested |
| Pending | Pending | What runs on idle: Art Mode vs. selected dream, with Art Mode on and off (step 4) | Not tested |
| Pending | Pending | Aerial Views over SMB Guest, HEIC, idle start (step 6) | Not tested |
