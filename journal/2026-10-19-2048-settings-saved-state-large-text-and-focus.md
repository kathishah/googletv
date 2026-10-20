# Settings saved state, large text and focus (2026-10-19)

Moved verbatim from `docs/DEVICE_TESTING.md` on 2026-10-19, with links updated for the new location.

- **B16:** with an unsaved Layout change, changing the TV font scale to 1.2 triggered settings recreation. The draft Layout choice remained selected and the saved preference stayed `PORTRAIT_PAIRS`; Cancel restored the saved choice. Returning the font scale to its default also retained the saved 10 albums / 879 photos selection.
- **R09:** the updated settings screen uses content-sized rows and buttons inside a vertical scroller. At font scale 1.2, all choice labels remained visible; repeated D-pad Down reached Preview, and scrolling placed Preview, Save and Cancel fully within the screen. Preview/Back returned focus to Preview. Font scale is restored to the default (1.0).
- **R10:** Preview focus restoration is confirmed. Sorting from an album card to A–Z returned focus to that same album by identity. The earlier attempt to reach Refresh routed to Select all, which was activated; the user later restored their preferred selection. Subsequent checks confirmed Refresh focus restoration as recorded in the Step 6 entry above. The user has since changed the selection again; checks must leave it untouched.
- **Current TV state:** the temporary font-scale override has been removed (default 1.0). The user's current selection is intentionally managed by the user; continue device checks without changing it.
- **Build:** `:app:assembleDebug :app:lintDebug` and `:app:testDebugUnitTest` passed after these changes (240 JVM tests).
