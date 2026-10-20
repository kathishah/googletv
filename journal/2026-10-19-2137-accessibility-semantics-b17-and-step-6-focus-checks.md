# Accessibility semantics, B17 and Step 6 focus checks (2026-10-19)

Moved verbatim from `docs/DEVICE_TESTING.md` on 2026-10-19, with links updated for the new location.

- Settings choice buttons now expose the row and option in their accessible name, expose selected/not-selected node state, and provide a state description. SMB field labels are linked to their edit fields with `labelFor`.
- **TV node and TalkBack check:** after installing the build, UIAutomator exposed `Interval, 10 secs` as selected and `Interval, 30 secs` as not selected. During the TalkBack check, accessibility events included `Not selected. Type, Photos + Videos. Button` and `selected. Selected. Type, Photos. Button`. Although Android logged that TTS was not ready during some speech attempts, the user confirmed they heard TalkBack audio; audible selected-state announcements are verified. TalkBack was restored to its original disabled state (`enabled_accessibility_services` absent and `accessibility_enabled=0`).
- **Keyboard:** with an SMB field focused, D-pad Right moved virtual-keyboard focus from `q` to `w`, Down moved it to `s`, and Back dismissed the keyboard while leaving the field focused. No field text was changed or saved.
- **Refresh focus (R10):** in the picker, one Down from the initial state focused Refresh; Right/Left moved between Sort and Refresh; Down/Up moved between Refresh and an album card. Activating Refresh completed successfully and returned focus to the same album card. The user had changed the album selection before these checks; no album selection controls were activated during them.
- **TV/build state:** app settings remained foregrounded; the installed build label was `1299efa5` (not dirty). Keyboard testing did not save SMB settings. The TV selection was left as the user had set it.
- **Build:** 240 JVM tests passed; debug APK and lint passed.
