# Settings and SMB mock parity

After comparing the installed TV screen with the approved mock, the settings Type/Layout controls were changed to use matching photo, video, combined-media, single-image, and portrait-pair line icons. Labels now uses the calendar-plus-pin artwork and an on/off switch track; Preview, Save, and Cancel use drawn icons with accessible names. The SMB form now follows the mock's two-column composition with compact boxed fields, a Guest/Registered radio card, and a separate connection test card. The connection test result is shown when it completes.

Build and verification from `apps/screensaver/`:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Result: **BUILD SUCCESSFUL**. Unit tests, debug APK assembly, and app lint passed. Gradle emitted the existing 32-bit-only native library warning.

The updated APK was installed on the TCL TV and opened on SMB settings. D-pad navigation reached the connection test, and explicit Guest access returned “Connected · folder is readable.” The app remains on SMB settings with Guest selected and the successful result visible. Registered account authentication still needs a manual test with valid credentials.

Device update and launch commands, after the existing ADB connection was available:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.googletv.screensaver/.MainActivity
```
