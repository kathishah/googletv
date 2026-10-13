#!/bin/zsh
# Permission trial for a small, fixed sample of HEIC originals. Run on the Mac mini by the user.
#
#   zsh heic_perm_trial.sh select   # record the 25 sampled HEICs and their current modes (no changes)
#   zsh heic_perm_trial.sh check    # counts of live/clone modes for the recorded files (no changes)
#   zsh heic_perm_trial.sh apply    # LIVE library only: add group/other read (0600 -> 0644) to recorded files
#   zsh heic_perm_trial.sh revert   # LIVE library only: restore each recorded file's original mode
#   zsh heic_perm_trial.sh names    # print Photos' original filenames for the recorded files (local use only)
#
# Only permission bits change. File bytes, xattrs, timestamps, and the Photos database are never touched.
# The clone is never written; rerun Carbon Copy Cloner to propagate (or revert) the change.
# Output is counts only, except `names`. The manifest holds private relative paths: it stays in
# $STATE_DIR on the Mac mini; do not copy it into the repository.
# LIVE and CLONE can be overridden from the environment.

set -u
STATE_DIR="$HOME/photo-perm-trial"
MANIFEST="$STATE_DIR/manifest.tsv"   # relative path <TAB> original octal mode
COUNT=25

: ${LIVE:="$(find /Volumes/Samsung_T5 -maxdepth 5 -type d -name 'Photos Library.photoslibrary' -prune -print 2>/dev/null | head -1)"}
: ${CLONE:="$(sharing -l | grep -A1 -E '^name:[[:space:]]+Photos$' | sed -nE 's/^path:[[:space:]]+//p')/Photos Library.photoslibrary"}

die() { echo "error: $*" >&2; exit 1; }
[ -d "$LIVE/originals" ] || die "live library originals not found"
[ -d "$CLONE/originals" ] || die "clone library originals not found"
[ "$LIVE" != "$CLONE" ] || die "live and clone resolve to the same path"

safe_rel() { [[ "$1" == ./* && "$1" != *"/../"* && "$1" != *$'\n'* ]]; }

manifest_lines() {
  [ -f "$MANIFEST" ] || die "no manifest; run select first"
  while IFS=$'\t' read -r rel mode; do
    safe_rel "$rel" || die "unexpected path in manifest"
    print -r -- "$rel"$'\t'"$mode"
  done < "$MANIFEST"
}

case "${1:-}" in
  select)
    [ -e "$MANIFEST" ] && die "manifest already exists; keep it for revert (delete $STATE_DIR only after reverting)"
    mkdir -p "$STATE_DIR" && chmod 700 "$STATE_DIR"
    # Same selection as compare_clone_modes.sh: first 25 HEIC/HEIF in the clone's traversal order.
    (cd "$CLONE/originals" && find . -type f | grep -iE '\.(heic|heif)$' | head -$COUNT) |
    while IFS= read -r rel; do
      L="$LIVE/originals/$rel"
      [ -f "$L" ] || { echo "skip (missing on live)"; continue; }
      print -r -- "$rel"$'\t'"$(stat -f '%Lp' "$L")"
    done > "$MANIFEST"
    chmod 600 "$MANIFEST"
    echo "recorded $(wc -l < "$MANIFEST" | xargs) files; original live modes:"
    cut -f2 "$MANIFEST" | sort | uniq -c
    ;;
  check)
    manifest_lines | while IFS=$'\t' read -r rel orig; do
      l=$(stat -f '%Lp' "$LIVE/originals/$rel" 2>/dev/null || echo missing)
      c=$(stat -f '%Lp' "$CLONE/originals/$rel" 2>/dev/null || echo missing)
      echo "orig=$orig live=$l clone=$c"
    done | sort | uniq -c
    ;;
  apply)
    pgrep -xq Photos && echo "note: Photos is running; quitting it first is recommended"
    changed=0; skipped=0
    manifest_lines | { while IFS=$'\t' read -r rel orig; do
      L="$LIVE/originals/$rel"
      cur=$(stat -f '%Lp' "$L" 2>/dev/null || echo missing)
      if [ -f "$L" ] && [ ! -L "$L" ] && [ "$cur" = "$orig" ] && [ "$cur" = "600" ]; then
        chmod go+r "$L" && changed=$((changed + 1))
      else
        skipped=$((skipped + 1))
      fi
    done; echo "changed=$changed skipped=$skipped"; }
    ;;
  revert)
    restored=0; skipped=0
    manifest_lines | { while IFS=$'\t' read -r rel orig; do
      L="$LIVE/originals/$rel"
      if [ -f "$L" ] && [ ! -L "$L" ] && [[ "$orig" == <-> ]]; then
        chmod "$orig" "$L" && restored=$((restored + 1))
      else
        skipped=$((skipped + 1))
      fi
    done; echo "restored=$restored skipped=$skipped"; }
    ;;
  names)
    echo "Private: original filenames for local lookup in Photos. Do not share or paste this output."
    manifest_lines | while IFS=$'\t' read -r rel orig; do
      xattr -p com.apple.assetsd.originalFilename "$LIVE/originals/$rel" 2>/dev/null | strings | head -1
    done
    ;;
  *)
    sed -n '2,9p' "$0"; exit 2
    ;;
esac
