# Settings focus and demarcation

Follow-up review tightened the mock parity. Type, Layout, and Labels each have their own bordered panel while staying on one row. The Cancel glyph is now a left-turn undo arrow. SMB fields brighten with a blue border and fill when focused. Guest and Registered user rows are inset in the sign-in card; the selected option has a stronger blue fill, and the focused row gets a separate focus outline.

Build and lint from `apps/screensaver/`:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:assembleDebug :app:lintDebug
```

Result: **BUILD SUCCESSFUL**. The updated APK installed and opened on the TCL TV. The settings row showed three distinct panels and the undo icon. The SMB screen showed the focused server field with its highlight, and the Guest option with the brighter selected state, inset spacing, and focus outline. The app was left on SMB settings with the server field focused for review. The existing Guest connection test remains verified in the prior entry; no connection test was run in this visual follow-up.
