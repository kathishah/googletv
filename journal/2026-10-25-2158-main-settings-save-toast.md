# Main settings save toast

Changed successful main settings saves to use a brief “Settings saved.” toast, matching SMB Save. The persistent status line stays on “Changes take effect when saved.” Preview still enters the slideshow without showing a save toast, and save validation errors still appear inline.

Build and lint from `apps/screensaver/`:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:assembleDebug :app:lintDebug
```

Result: **BUILD SUCCESSFUL**. The APK was installed and opened on the TCL TV. The main Save action was selected and activated; the “Settings saved.” toast appeared, and the screen remained on settings. `git diff --check` passed.
