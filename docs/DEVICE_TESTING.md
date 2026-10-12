# TCL TV setup and test log

## Development Mac

Current check on 2026-10-11: no working Java runtime, Android SDK, Android Studio, Gradle, or `adb` found. `DEVELOPER_DIR=/Library/Developer/CommandLineTools brew install openjdk@17 android-commandlinetools android-platform-tools` failed with an unaccepted Xcode license, so no Android build or TV test has run. Install Android Studio and its SDK/platform tools before generating or building the app. Android Studio can use its bundled JDK; command-line Gradle needs a compatible JDK. Do not commit `local.properties`.

## Mac mini source investigation

On 2026-10-11, read-only checks of `/Volumes/Photos/Photos Library.photoslibrary` found an `originals` directory with JPEG and HEIC samples that could be opened and read. The package also contains databases and resource directories; its internal folders are not Photos albums. A later Photos settings screenshot from the Mac mini showed the active System Photo Library under `Samsung_T5`, and the user confirmed this is the same location reached through the SMB `Photos` share. The chosen integration is read-only SMB access to available image files below `originals`; no package database access or writes. Whether all iCloud originals are available locally remains unverified.

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

## Connect to the TV

The TV is already in developer mode. Connect the Mac and TV to the same network. In the TV's developer options, enable the available debugging mode. For Android 13+ TV wireless debugging, use the pairing code shown on the TV:

```sh
adb pair TV_IP:PAIRING_PORT
adb connect TV_IP:DEBUG_PORT
adb devices
```

Use the actual pairing and connection ports shown by the TV; they can differ. Older TV versions may expose a legacy network debugging port instead. Follow the [official adb wireless debugging guide](https://developer.android.com/tools/adb) for the installed TV version. Never put the IP address or pairing code in this repository.

## Optional browser-only Apple Photos check

If testing the web alternative, search Apps on the TV for a browser, install one available for this model, and visit `https://www.icloud.com/photos/`. Test sign-in, navigating to an album, starting a full-screen slideshow, and controlling it with the remote. Record whether the browser remains open and whether idle/sleep interrupts playback. Apple's supported-browser list does not include TV browsers, so success is device-specific. Do not enter Apple credentials into a browser you do not trust.

## Once the Android project exists

From `apps/screensaver/`:

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the app from the TV launcher. Verify D-pad focus, interval persistence, slideshow layout, and Back behavior. Then inspect Settings → System → Ambient mode / Screensaver for an option to select the app. If the TV does not expose it, record the exact model, OS version, and menu behavior here before choosing a fallback.

The [Aerial Views maintainer](https://github.com/theothernt/AerialViews) documents an additional TCL Google TV requirement: allow Auto Launch permission for a third-party screensaver to start in the background. The same device setting may matter for our app. Do not assume an installed `DreamService` is enough to start on idle.

## Device findings

| Date | TV model / OS | Test | Result |
| --- | --- | --- | --- |
| Pending | Pending | Third-party dream shown in settings and starts on idle | Not tested |
