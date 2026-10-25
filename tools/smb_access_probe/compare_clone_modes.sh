#!/bin/zsh
# Read-only comparison of the live Photos library and its CCC clone on the Mac mini.
# Prints counts, modes, volume ownership state, and extended-attribute NAMES only; no file names.
# Run on the Mac mini: zsh compare_clone_modes.sh
# Never writes to either library and never opens the Photos database.

CLONE="$(sharing -l | grep -A1 -E '^name:[[:space:]]+Photos$' | sed -nE 's/^path:[[:space:]]+//p')/Photos Library.photoslibrary"
# LIVE_VOLUME: mount point of the drive holding the LIVE library, for example /Volumes/MyPhotosDrive.
: ${LIVE_VOLUME:="/Volumes/YOUR_LIVE_DRIVE"}
LIVE="$(find "$LIVE_VOLUME" -maxdepth 5 -type d -name 'Photos Library.photoslibrary' -prune -print 2>/dev/null | head -1)"
[ -d "$CLONE/originals" ] && [ -d "$LIVE/originals" ] || { echo "library paths not found"; exit 1; }

echo "== volume ownership"
for v in "$LIVE_VOLUME" "$(df "$CLONE" | tail -1 | sed -E 's#.* (/Volumes/.*)$#\1#')"; do
  echo "$v: $(diskutil info "$v" | grep -i owners | xargs) mount=$(mount | grep -F " on $v (" | grep -o noowners || echo no-noowners-flag)"
done
echo "== umask"; umask

cmpmodes() {
  (cd "$CLONE/originals" && find . -type f | grep -iE "\.($1)\$" | head -25 | while IFS= read -r f; do
    L="$LIVE/originals/$f"
    c=$(stat -f '%Lp' "$f"); l=$(stat -f '%Lp' "$L" 2>/dev/null || echo missing)
    [ "$(stat -f '%Su' "$f")" = "$(stat -f '%Su' "$L" 2>/dev/null)" ] && o=same-owner || o=diff-owner
    if [ ! -e "$L" ]; then b=missing; elif cmp -s "$f" "$L"; then b=same-bytes; else b=diff-bytes; fi
    echo "clone=$c live=$l $o $b"
  done | sort | uniq -c)
}
echo "== HEIC/HEIF (25)"; cmpmodes 'heic|heif'
echo "== JPEG (25)"; cmpmodes 'jpg|jpeg'

xattrs_by_mode() {
  (cd "$LIVE/originals" && find . -type f -perm "$1" | grep -iE "\.($2)\$" | head -10 | while IFS= read -r f; do
    echo "acl=$(ls -led "$f" | tail -n +2 | wc -l | xargs) cpl.original=$(xattr "$f" | grep -cx com.apple.cpl.original) quarantine=$(xattr "$f" | grep -cx com.apple.quarantine) cloudAsset=$(xattr "$f" | grep -cx com.apple.assetsd.cloudAsset.UUID)"
  done | sort | uniq -c)
}
echo "== live 0600 HEIC xattr markers (10)"; xattrs_by_mode 600 'heic|heif'
echo "== live 0600 JPEG xattr markers (10)"; xattrs_by_mode 600 'jpg|jpeg'
echo "== live 0644 JPEG xattr markers (10)"; xattrs_by_mode 644 'jpg|jpeg'
