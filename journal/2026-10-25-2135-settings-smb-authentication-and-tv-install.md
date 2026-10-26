# Settings layout and SMB authentication

The settings screen now puts Type, Layout, and the Location + date switch on one horizontal row. Type, Layout, Preview, Save, and Cancel use icon-only buttons with accessible names. The TV screen capture showed the full settings table and action row without vertical scrolling.

The SMB screen defaults to explicit Guest mode, offers Registered user credentials, and includes a read-only connection check that authenticates, opens the share, verifies the configured library folder, and lists that folder. Registered passwords are stored encrypted with a device-local Android Keystore key. The TV screen showed both authentication modes; selecting Registered user exposed username and password fields. The Guest connection test completed successfully and reported that the configured folder is readable. Registered-account authentication still needs a manual test with credentials for an account on the share.

Build and verification from `apps/screensaver/`:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Result: **BUILD SUCCESSFUL**. Unit tests, debug APK assembly, and app lint passed. Gradle emitted the existing 32-bit-only native library warning.

Device setup and launch commands:

```sh
adb kill-server
adb start-server
adb connect TV_ADDRESS:5555
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.googletv.screensaver
adb shell am start -n com.googletv.screensaver/.MainActivity
```

The TV reconnected after the ADB server restart. The updated app installed and opened successfully. On the TV, the main settings row and actions fit on screen; SMB settings showed the default Guest option, the connection test, and Back. Registered user exposed both credential inputs. The app was left on SMB settings in Guest mode after the connection test reported success. No screensaver system setting was changed during this check.

For this 1920×1080 TV capture, the ADB UI checks were:

```sh
adb shell input tap 1580 220  # Open SMB settings
adb shell input tap 870 660   # Select Registered user; username and password appear
adb shell input tap 410 660   # Return to Guest mode
adb shell input tap 1500 790  # Test connection; result: connected, folder readable
```
