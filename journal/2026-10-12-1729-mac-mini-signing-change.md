# Mac mini signing change (2026-10-12)

Moved verbatim from `docs/PHOTO_SOURCES.md` on 2026-10-19, with links updated for the new location. Inline corrections and updates are kept as originally written.

The cause was server-side. In SMBJ 0.15.0, a session requires signing when either the client config or the server's NEGOTIATE `SecurityMode` requires it. A Guest session with signing required throws `SMB2GuestSigningRequiredException`. `withSigningRequired(false)` only clears the client half, so no client option could avoid it. An unauthenticated NEGOTIATE probe (`tools/check_smb_signing.py`) showed the Mac mini reporting signing as required at SMB 3.0.2. Apple's own client still mounted the share as Guest, so macOS tolerates unsigned Guest sessions despite advertising the requirement.

Apple does document the server preference, in an archived article that applies to macOS 10.13.3 and earlier: [Turn off packet signing for SMB 2 and SMB 3 connections](https://support.apple.com/en-us/101442). It still works on the current macOS when applied with Apple's sequence. Writing the key and running `launchctl kickstart -k system/com.apple.smbd` was not enough. On the Mac mini (user-operated, with the original plist backed up first):

```sh
sudo launchctl disable system/com.apple.smbd
sudo launchctl bootout system/com.apple.smbd
sudo defaults write /Library/Preferences/SystemConfiguration/com.apple.smb.server SigningRequired -bool false
sudo /usr/libexec/smb-sync-preferences
sudo launchctl enable system/com.apple.smbd
sudo launchctl bootstrap system /System/Library/LaunchDaemons/com.apple.smbd.plist
```

The probe then reported `SIGNING_REQUIRED=False`, and the unchanged TV build displayed photos from the share through SMBJ's explicit Guest mode. The user accepted the trade-off: the change applies to every SMB client of the Mac mini, and sessions are unsigned, on the basis that the share is read-only and reachable only on the home LAN. A macOS update may restore the default. Rerun the probe if the TV source stops connecting. To revert, run `sudo defaults delete /Library/Preferences/SystemConfiguration/com.apple.smb.server SigningRequired` within the same stop, sync, and start sequence. The dedicated read-only account remains the fallback if the preference stops working.
