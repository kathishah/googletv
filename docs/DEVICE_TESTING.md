# TCL TV setup and runbook

Follow the setup steps in order. Each step lists its current status. This file is the runbook: it holds the TV facts, the setup steps and a release checklist. Dated test results are entries in [`journal/`](../journal/README.md), indexed under [Device findings](#device-findings). Never store the TV's IP address, the Mac mini's IP address, pairing codes, serial numbers, firmware or build strings, volume or album names, photo bytes, or photo filenames in this repository. Placeholders used in these docs: `TV_IP`, `MAC_HOST`, `LIVE_VOLUME` (the drive holding the live library), `PHOTOS_SHARE`.

## Known TV facts

Read from Settings → System → About on the TV, 2026-10-11. Only what the app depends on is kept here. Record your own TV's security patch level, kernel, OS build and firmware string in your own notes, not in this repository: they identify a particular device and firmware.

| Field | Value |
| --- | --- |
| Android TV OS | 12 (API level 31) |
| Model | TCL 65" A300 (NXTFRAME art TV, 2024), per the on-TV user guide and the box. ADB reports the generic `Smart TV` for `ro.product.model`. |
| Firmware | Verified on one firmware version; re-check after updates (see the README). |

What this implies:

- Use `minSdk 31` so the app can install on this TV. Check current official documentation before choosing `targetSdk` and `compileSdk`.
- The [official adb guide](https://developer.android.com/tools/adb) describes pairing-code wireless debugging. This TV's Developer options screen did not list Wireless debugging. Enabling USB debugging also enabled legacy network ADB (default port 5555), which was not reachable before. Leave it off when you are not working on the TV: legacy network ADB, once authorized, lets that computer control the TV.
- Community reports (see [Aerial Views setup](https://github.com/theothernt/AerialViews)) say Google TV devices running Android TV 12 or later have no settings menu for choosing a third-party screensaver; one is selected with `adb` instead (step 4).
- Community reports mention an `APP_AUTO_START` app-op on some Android TV versions, but this firmware does not expose it; see step 5.
- The A300 is TCL's NXTFRAME "art TV". Per [TCL support](https://support.tcl.com/en_US/t-exhibition-turning-your-tcl-tv-into-a-work-of-art) and launch coverage, it has its own idle **Art Mode** (the T-Exhibition app) with an art library, AI art, and a personal photo gallery loaded from USB or the TCL Home phone app. This is separate from Google TV's Ambient mode. On this firmware, testing found that Art Mode did not interrupt a manually started artwork or the app DreamService (see step 4 and [Device findings](#device-findings)); recheck after firmware updates.

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

Status: **done, including the default 10-minute wait (observed 2026-10-16).** Settings and idle behavior were inspected on 2026-10-12. The Ambient mode page offered Google's Backdrop settings and a Start now action. T-Exhibition Art Mode settings showed Wallpaper Time at 2 minutes and Keep Screen On enabled; neither was changed. With the original Backdrop selected, Google Ambient displayed on idle. On 2026-10-16, manually started T-Exhibition artwork was not interrupted by Backdrop (see [Device findings](#device-findings)). The app DreamService is selected with the original 600000 ms timeout. Idle activation was observed at 60000 ms and at the default 600000 ms; the latter completed after a full 10-minute wait. A later TV restart also activated the dream (user-reported). Requires step 3.

Check Settings → System → Ambient mode / Screensaver and record which options are shown. Also find the Art Mode / T-Exhibition settings (look for an idle or Art Mode toggle and its timeout) and record them. Let the TV go idle with no changes and note what appears first: Art Mode, Google's Ambient mode, or nothing.

Then select a third-party dream with `adb`:

```sh
adb shell settings put secure screensaver_components PACKAGE/DREAM_SERVICE_CLASS
adb shell settings get secure screensaver_components
```

To go back to Google's screensaver, restore the original value recorded in step 3 with the same `settings put` command. The screen-off timeout is in milliseconds. A temporary 60000 ms timeout successfully triggered our dream once on this TV; this does not establish the TV's minimum supported timeout. The original value recorded in step 3 was 600000 ms (10 minutes):

```sh
adb shell settings put system screen_off_timeout 600000
```

After selecting a dream, let the TV go idle again with Art Mode enabled, then with Art Mode turned off, and record which one runs in each case. If Art Mode always takes over, the third-party screensaver may only work with Art Mode turned off.

### 5. Check TCL Auto Launch

Status: **no Auto Launch control found; none was needed for idle activation on this firmware.** On 2026-10-16, these checks found no TCL Auto Launch mechanism:

- No Safety Guard / Permission Shield package was installed.
- Android App Info showed no Auto Launch control.
- `cmd appops get com.googletv.screensaver` returned `No operations`.
- The op names `APP_AUTO_START`, `AUTO_START`, and `BOOT_COMPLETED` were all `Unknown operation string`.
- `settings list system|secure|global` had no auto-start or allowlist key.
- `service list` showed no TCL guard or auto-start service.

Without changing anything TCL-specific, the app dream started on normal idle in every recorded idle test: at 60000 ms on 2026-10-12, 2026-10-13, and earlier on 2026-10-16, and twice more on 2026-10-16 at 120000 ms. A hidden vendor mechanism cannot be ruled out, but none was observed blocking the dream. Re-check this after a firmware update. Requires step 4 and an installed screensaver app.

The [Aerial Views maintainer](https://github.com/theothernt/AerialViews) reports that some TCL Google TVs need an Auto Launch permission. On this TV, the menu, package, app-op, settings keys and vendor service were not found, and the app DreamService activated without any TCL-specific changes. The following is reference guidance only for a firmware that actually exposes the setting; it is not a setup step for the currently tested firmware.

If a later firmware exposes Safety Guard → Permission Shield → Auto Launch Permission, the Aerial Views guide says to set Auto manager to Closed and the app to Opened. If Safety Guard is missing, on Android TV 13 or lower, first verify that the operation exists before attempting:

```sh
adb shell appops set PACKAGE APP_AUTO_START allow
```

No Auto Launch state was changed. Recheck only after a firmware update or if the dream stops starting on idle.

### 6. Optional: prove the whole path with Aerial Views

Status: **done (user-reported, details not recorded).** Requires steps 2–5. Takes about 30 minutes and needs no project code.

[Aerial Views](https://github.com/theothernt/AerialViews) is an open-source TV screensaver that can show photos from SMB. Install it from Google Play on the TV, add the Mac mini's `Photos` share as an SMB source pointing at `Photos Library.photoslibrary/originals`, then set it as the screensaver using step 4. Its component name from the maintainer's guide is:

```sh
adb shell settings put secure screensaver_components com.neilturner.aerialviews/.ui.screensaver.DreamActivity
```

Then use step 5 only if that firmware exposes an Auto Launch setting. Record whether Aerial Views can use SMB Guest access, display HEIC photos, start on idle, and survive a TV restart. Restore the original screensaver afterward. The user reports this optional check is done; details were not recorded. Use a test folder first if you do not want the full library shown during an experiment (see [hidden and deleted photos](PHOTO_SOURCES.md#what-originals-contains)).

### 7. Build and install our app

Status: **installed; build, unit tests, lint, mock-parity visual checks, and the Guest SMB connection test passed.** Previous device checks covered D-pad settings, preview, interval persistence, SMB Guest photo changes and labels, portrait layout, 24 MP HEIC decoding, video playback, and idle DreamService activation. The 2026-10-25 build adds the compact icon settings row, label switch, registered SMB credentials, and read-only connection test. Main Save now confirms with a toast. Registered-account authentication still needs a manual test with valid account credentials. The app is open on main settings after confirming the Save toast on the TV. Current findings are in the [SMB implementation entry](../journal/2026-10-25-2135-settings-smb-authentication-and-tv-install.md), [mock-parity follow-up](../journal/2026-10-25-2142-settings-mock-parity.md), [focus and demarcation follow-up](../journal/2026-10-25-2154-settings-focus-and-demarcation.md), and [main Save toast check](../journal/2026-10-25-2158-main-settings-save-toast.md). From `apps/screensaver/`:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the app from the TV launcher. Verify D-pad focus, interval persistence, slideshow layout, and Back behavior. Then repeat steps 4 and 5 with our package and `DreamService` class, and record whether the dream starts on idle.

## Release checklist

Run this when installing a new build on the TV. Record the result as a journal entry (build, tests, what was checked on the TV, how settings were restored).

1. Build and test from `apps/screensaver/`:

   ```sh
   JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
   ```

   Use `:app:lintDebug`; a plain `lintDebug` also checks the `heif-probe` module and fails.
2. Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`. The settings screen shows the build label; it should have no `-dirty` suffix for a committed build.
3. Check on the TV: D-pad navigation, the Type/Layout/Labels row and icon actions without scrolling, Save and Cancel, Preview and Back, and idle activation of the DreamService. Open SMB settings, verify Guest is the default, test read-only folder access, and check that Registered user reveals username and password fields. If a registered read-only account is available, test that mode too.
4. Restore anything changed for testing: the selected `screensaver_components` (our DreamService), `screen_off_timeout` (600000), and the saved settings and album selection.

## Optional browser-only Apple Photos check

Lower priority now that SMB is the chosen source. If testing the web alternative, search Apps on the TV for a browser, install one available for this model, and visit `https://www.icloud.com/photos/`. Test sign-in, navigating to an album, starting a full-screen slideshow, and controlling it with the remote. Record whether the browser remains open and whether idle/sleep interrupts playback. Apple's supported-browser list does not include TV browsers, so success is device-specific. Do not enter Apple credentials into a browser you do not trust.

## Device findings

Dated test results, oldest first. Each entry records what was tested, the build, and the result.

- **2026-10-11:** [Mac mini source investigation](../journal/2026-10-11-2143-mac-mini-source-investigation.md); [Guest-mode SMB check](../journal/2026-10-11-2143-guest-mode-smb-check.md); [Device findings log, 2026-10-11](../journal/2026-10-11-2143-device-findings-log.md)
- **2026-10-12:** [Device findings log, 2026-10-12](../journal/2026-10-12-1250-device-findings-log.md)
- **2026-10-13:** [HEIC `STATUS_ACCESS_DENIED` investigation (2026-10-13)](../journal/2026-10-13-1223-heic-status_access_denied-investigation.md); [Device findings log, 2026-10-13](../journal/2026-10-13-1223-device-findings-log.md)
- **2026-10-15:** [HEIF tile decode prototype (2026-10-15)](../journal/2026-10-15-2216-heif-tile-decode-prototype.md); [Device findings log, 2026-10-15](../journal/2026-10-15-2216-device-findings-log.md)
- **2026-10-16:** [Device findings log, 2026-10-16](../journal/2026-10-16-0849-device-findings-log.md)
- **2026-10-17:** [Video decode survey (2026-10-17)](../journal/2026-10-17-1813-video-decode-survey.md); [Video playback over SMB (2026-10-17)](../journal/2026-10-17-1830-video-playback-over-smb.md); [Catalog v2, milestone 1 (2026-10-17)](../journal/2026-10-17-2112-catalog-v2-milestone-1.md); [Settings and picker, milestone 2 (2026-10-17)](../journal/2026-10-17-2123-settings-and-picker-milestone-2.md); [Progressive reader, milestone 3 (2026-10-17)](../journal/2026-10-17-2204-progressive-reader-milestone-3.md); [Video playback in the in-app slideshow, milestone 4 (2026-10-17)](../journal/2026-10-17-2252-video-playback-in-the-in-app-slideshow-milestone-4.md); [Device findings log, 2026-10-17](../journal/2026-10-17-1455-device-findings-log.md)
- **2026-10-18:** [Poster covers, milestone 6 (2026-10-17)](../journal/2026-10-18-0824-poster-covers-milestone-6.md); [Videos album filter (2026-10-18)](../journal/2026-10-18-0828-videos-album-filter.md); [Screensaver idle activation, milestone 5 (2026-10-18)](../journal/2026-10-18-0837-screensaver-idle-activation-milestone-5.md); [Hardening, milestone 7 (2026-10-18)](../journal/2026-10-18-0959-hardening-milestone-7.md); [Excluding screenshots, documents, and receipts (2026-10-18)](../journal/2026-10-18-1219-excluding-screenshots-documents-and-receipts-tv-run.md); [Unreadable share at screensaver start (2026-10-18)](../journal/2026-10-18-1300-unreadable-share-at-screensaver-start.md); [Portrait pairs, milestone 1: catalog v5 (2026-10-18)](../journal/2026-10-18-1405-portrait-pairs-milestone-1-catalog-v5.md); [Portrait pairs, milestone 2: planner and fit (2026-10-18)](../journal/2026-10-18-1408-portrait-pairs-milestone-2-planner-and-fit.md); [Portrait pairs, milestone 3: renderer (2026-10-18)](../journal/2026-10-18-1427-portrait-pairs-milestone-3-renderer.md); [Portrait pairs, milestone 4: player integration (2026-10-18)](../journal/2026-10-18-1538-portrait-pairs-milestone-4-player-integration.md); [Portrait pairs, milestone 5: settings and screensaver (2026-10-18)](../journal/2026-10-18-1549-portrait-pairs-milestone-5-settings-and-screensaver.md)
- **2026-10-19:** [Playback correctness, B06/B12/R05 exit check (2026-10-19)](../journal/2026-10-19-1423-playback-correctness-b06b12r05-exit-check.md); [Resource cleanup follow-up (2026-10-19)](../journal/2026-10-19-2007-resource-cleanup-follow-up.md); [Accessibility semantics, B17 and Step 6 focus checks (2026-10-19)](../journal/2026-10-19-2137-accessibility-semantics-b17-and-step-6-focus-checks.md); [Settings saved state, large text and focus (2026-10-19)](../journal/2026-10-19-2048-settings-saved-state-large-text-and-focus.md); [Album picker cover and scroll regression (2026-10-19)](../journal/2026-10-19-2106-album-picker-cover-and-scroll-regression.md); [Device findings log, 2026-10-19](../journal/2026-10-19-1337-device-findings-log.md)
- **2026-10-25:** [Settings layout, SMB authentication, and TV install](../journal/2026-10-25-2135-settings-smb-authentication-and-tv-install.md); [Settings and SMB mock parity](../journal/2026-10-25-2142-settings-mock-parity.md); [Settings focus and demarcation](../journal/2026-10-25-2154-settings-focus-and-demarcation.md); [Main settings save toast](../journal/2026-10-25-2158-main-settings-save-toast.md)
