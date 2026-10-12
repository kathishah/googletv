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
| Model | TCL 65" A300 (NXTFRAME art TV, 2024), per the on-TV user guide and the box. Consistent with the Android TV 12 / October 2024 software. A Best Buy listing for the similar [65A3G Designer Series (2026)](https://support.tcl.com/en_US/a3g-series) was considered and ruled out. Optional cross-check in step 3: `adb shell getprop ro.product.model`. |

What this implies:

- Use `minSdk 31` so the app can install on this TV. Check current official documentation before choosing `targetSdk` and `compileSdk`.
- Android TV 12 does not support pairing-code wireless debugging. The [official adb guide](https://developer.android.com/tools/adb) requires Android 13 or higher for that on TV. Use the network debugging path in step 2.
- Community reports (see [Aerial Views setup](https://github.com/theothernt/AerialViews)) say Google TV devices running Android TV 12 or later have no settings menu for choosing a third-party screensaver; one is selected with `adb` instead (step 4).
- The TCL Auto Launch `appops` name for Android TV 13 or lower is `APP_AUTO_START` (step 5).
- The A300 is TCL's NXTFRAME "art TV". Per [TCL support](https://support.tcl.com/en_US/t-exhibition-turning-your-tcl-tv-into-a-work-of-art) and launch coverage, it has its own idle **Art Mode** (the T-Exhibition app) with an art library, AI art, and a personal photo gallery loaded from USB or the TCL Home phone app. This is separate from Google TV's Ambient mode. It is unknown whether Art Mode replaces, precedes, or blocks the Android screensaver set by `screensaver_components`; step 4 must find out.

## Setup sequence

### 1. Install the Android toolchain on the development Mac

Status: **not done.** Check on 2026-10-11 found no working Java runtime, Android SDK, Android Studio, Gradle, or `adb`. The earlier Homebrew failure (unaccepted Xcode license) is resolved: `xcodebuild -license check` now succeeds.

Recommended: install Android Studio, which bundles a compatible JDK, the SDK manager, and platform tools.

```sh
brew install --cask android-studio
```

Open Android Studio once and let the setup wizard install the Android SDK and platform tools. Then add `adb` to the shell path:

```sh
echo 'export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"' >> ~/.zshrc
```

Command-line alternative (no IDE): `brew install openjdk@17 android-commandlinetools android-platform-tools`. Command-line Gradle needs a JDK compatible with the chosen Android Gradle Plugin; check its release notes. Never commit `local.properties`.

Verify:

```sh
adb version
```

### 2. Connect adb to the TV

Status: **not done.** Requires step 1.

On the TV:

1. Settings → System → Developer options → turn on **USB debugging**. On Android TV this typically also enables network debugging on TCP port 5555, so no cable is needed. This is community-documented TV behavior; the official guide does not cover Android TV 12.
2. Find the TV's IP address under Settings → Network & Internet → the connected network. A DHCP reservation in the router keeps it stable.

On the Mac, with the TV and Mac on the same network:

```sh
adb connect TV_IP:5555
```

The TV shows **Allow USB debugging?** with the Mac's key fingerprint. Select "Always allow from this computer", then OK.

```sh
adb devices
```

Expected: `TV_IP:5555  device`. If it shows `unauthorized`, accept the prompt on the TV and run `adb devices` again. If the connection is refused, look in Developer options for a separate **Network debugging** or **Wireless debugging** option, enable it, and retry. Record what worked in [Device findings](#device-findings).

### 3. Record device facts

Status: **partly done** (see [Known TV facts](#known-tv-facts)). Requires step 2.

```sh
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.model
adb shell settings get secure screensaver_components
adb shell settings get system screen_off_timeout
```

Expected SDK: `31`. Record the model name and the **original** `screensaver_components` and `screen_off_timeout` values in [Device findings](#device-findings) before changing anything; they are needed to restore the built-in Ambient mode.

### 4. Learn how screensavers are selected on this TV

Status: **not done.** Requires step 3.

Check Settings → System → Ambient mode / Screensaver and record which options are shown. Also find the Art Mode / T-Exhibition settings (look for an idle or Art Mode toggle and its timeout) and record them. Let the TV go idle with no changes and note what appears first: Art Mode, Google's Ambient mode, or nothing.

Then select a third-party dream with `adb`:

```sh
adb shell settings put secure screensaver_components PACKAGE/DREAM_SERVICE_CLASS
adb shell settings get secure screensaver_components
```

Restore the original value recorded in step 3 with the same `settings put` command. The screen-off timeout is in milliseconds; community reports say its minimum is 360000 (6 minutes) on Android TV 12 and later:

```sh
adb shell settings put system screen_off_timeout 600000
```

After selecting a dream, let the TV go idle again with Art Mode enabled, then with Art Mode turned off, and record which one runs in each case. If Art Mode always takes over, the third-party screensaver may only work with Art Mode turned off.

### 5. Allow TCL Auto Launch

Status: **not done.** Requires step 4 and an installed screensaver app.

According to the [Aerial Views maintainer](https://github.com/theothernt/AerialViews), some TCL Google TVs do not start a third-party screensaver unless the app has Auto Launch permission. On the TV, open Safety Guard → Permission Shield → Auto Launch Permission, set Auto manager to Closed, and set the app to Opened. If Safety Guard is missing, on Android TV 13 or lower:

```sh
adb shell appops set PACKAGE APP_AUTO_START allow
```

Do not assume an installed `DreamService` is enough to start on idle.

### 6. Optional: prove the whole path with Aerial Views

Status: **not done.** Requires steps 2–5. Takes about 30 minutes and needs no project code.

[Aerial Views](https://github.com/theothernt/AerialViews) is an open-source TV screensaver that can show photos from SMB. Install it from Google Play on the TV, add the Mac mini's `Photos` share as an SMB source pointing at `Photos Library.photoslibrary/originals`, then set it as the screensaver using step 4. Its component name from the maintainer's guide is:

```sh
adb shell settings put secure screensaver_components com.neilturner.aerialviews/.ui.screensaver.DreamActivity
```

Then apply step 5 with package `com.neilturner.aerialviews`. Record whether it can use SMB Guest access, whether HEIC photos display, whether the screensaver starts on idle, and whether it survives a TV restart. Restore the original screensaver afterward. Success proves idle activation, Auto Launch, SMB from the TV, and HEIC decoding on this TV before our own code depends on them. Use a test folder first if you do not want the full library shown during the experiment (see [hidden and deleted photos](PHOTO_SOURCES.md#what-originals-contains)).

### 7. Build and install our app

Status: **blocked** until the Android project exists (roadmap milestone 2). From `apps/screensaver/`:

```sh
./gradlew assembleDebug
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
| 2026-10-11 | Development Mac | Xcode license check | Accepted; Android toolchain still not installed |
| Pending | Pending | adb connection method (step 2) | Not tested |
| Pending | Pending | Original `screensaver_components` and `screen_off_timeout` (step 3) | Not recorded |
| Pending | Pending | Screensaver options in Settings; adb selection (step 4) | Not tested |
| Pending | Pending | What runs on idle: Art Mode vs. selected dream, with Art Mode on and off (step 4) | Not tested |
| Pending | Pending | Aerial Views over SMB Guest, HEIC, idle start (step 6) | Not tested |
| Pending | Pending | Our dream selected and starts on idle (step 7) | Not tested |
