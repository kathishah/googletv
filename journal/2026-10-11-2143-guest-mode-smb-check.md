# Guest-mode SMB check

Moved verbatim from `docs/DEVICE_TESTING.md` on 2026-10-19, with links updated for the new location.

On 2026-10-11, the development Mac mounted the share explicitly as Guest at `$HOME/PhotosGuest` using macOS `mount_smbfs`. The mount source showed `//guest:@<host>/Photos`. Running the checker against that mount:

```sh
python3 tools/check_smb_source.py "$MAC_MINI_IP" --mount-path "$HOME/PhotosGuest"
```

Result: current-session share listing rejected and identity unavailable (exit 77); Guest-only share listing accepted and included `Photos`; anonymous-only listing rejected; SMB port 445 reachable; the Photos library originals path was present; the recursive extension-based inventory completed and three sample images were readable. This verifies read-only library access using SMB Guest from the development Mac. It does not verify the Android app or the TCL TV. No private IP address, photo bytes, or filenames are stored here.
