#!/bin/zsh
# Make supported photo originals in the LIVE Photos library readable by others (adds go+r), so the
# Carbon Copy Cloner clone shared as SMB Guest becomes readable by the TV.
#
# Interactive use on the Mac mini, as the library owner (no sudo):
#   zsh make_photos_readable.sh scan              # counts of photos not readable by others (no changes)
#   zsh make_photos_readable.sh apply             # add go+r to those photos; record changed paths for revert
#   zsh make_photos_readable.sh revert            # remove go+r from every path recorded by apply
#   zsh make_photos_readable.sh diagnose          # check volume/library/originals access from this context
#   zsh make_photos_readable.sh status            # recent log lines (user and CCC runs)
#   zsh make_photos_readable.sh uninstall-agent   # remove the earlier launchd agent (blocked by macOS privacy)
#
# CCC "before copying files" script: install a root-owned copy in CCC's Scripts folder and select it
# in the task's Advanced settings. Run as root with CCC's arguments (or none), it applies the same
# change, logs counts to /Library/Logs/make_photos_readable.log, and always exits 0 so a problem
# never aborts the backup. Use `sudo zsh <that copy> revert` to undo CCC-applied changes.
#
# Scope: regular files below the library's `originals` only, with the extensions the TV app displays
# (as a normal user, only files owned by that user). Never touches videos, the Photos database, other
# package folders, the clone, or directories. Only permission bits change. Logs are counts only; the
# manifests hold private paths and stay on the Mac mini.
# LIVE, STATE_DIR, LOG, NO_LAUNCHCTL, and FAKE_ROOT can be overridden from the environment (for testing).

set -u
setopt NO_MULTIOS  # so `cmd 2>&1 >/dev/null` captures only stderr
umask 077
SCRIPT_PATH="${0:A}"
LABEL="local.photo-perms"
# FAKE_ROOT=1 exercises the root/CCC code paths in tests; it grants no privileges.
is_root() { [ "$EUID" -eq 0 ] || [ "${FAKE_ROOT:-}" = 1 ]; }
if is_root; then
  : ${STATE_DIR:="/var/root/photo-perms"}
  : ${LOG:="/Library/Logs/make_photos_readable.log"}
else
  : ${STATE_DIR:="$HOME/photo-perms"}
  : ${LOG:="$STATE_DIR/photo-perms.log"}
fi
USER_LOG="$HOME/photo-perms/photo-perms.log"
CCC_LOG="/Library/Logs/make_photos_readable.log"

: ${LIVE:="$(find /Volumes/Samsung_T5 -maxdepth 5 -type d -name 'Photos Library.photoslibrary' -prune -print 2>/dev/null | head -1)"}

log() {
  mkdir -p "$STATE_DIR"
  print -r -- "$(date '+%Y-%m-%d %H:%M:%S') [$(id -un)] $*" >> "$LOG"
  is_root && chmod 644 "$LOG"
}
die() { echo "error: $*" >&2; log "error: $*"; exit 1; }

# One line per location: exists / is a directory / ls error (message only, no path).
probe() {
  local e=no d=no l
  [ -e "$1" ] && e=yes; [ -d "$1" ] && d=yes
  l=$(ls "$1" 2>&1 >/dev/null | head -1 | sed -E 's/^.*: //')
  echo "$2 exists=$e dir=$d ls=${l:-ok}"
}
diagnose() {
  echo "context: user=$(id -un) LIVE=${${LIVE:+set}:-unset}"
  probe /Volumes/Samsung_T5 volume
  [ -n "$LIVE" ] && { probe "$LIVE" library; probe "$LIVE/originals" originals; }
}
need_live() {
  mkdir -p "$STATE_DIR"
  if [ -z "$LIVE" ] || [ ! -d "$LIVE/originals" ] || [[ "$LIVE" != *.photoslibrary ]]; then
    diagnose | while IFS= read -r line; do log "diagnose: $line"; echo "diagnose: $line" >&2; done
    die "live library originals not found (see diagnose lines)"
  fi
  # macOS privacy (TCC) blocks show up as EPERM rather than missing files.
  local err
  err=$(ls "$LIVE/originals" 2>&1 >/dev/null | head -1 | sed -E 's/^.*: //')
  [ -z "$err" ] || die "cannot list originals: $err"
}

# NUL-separated list of supported photos that others cannot read. As a normal user, only that
# user's files. As root (CCC), no owner filter: Samsung_T5 ignores ownership, so every file appears
# to belong to whoever looks at it.
unreadable_photos() {
  local owner_filter=()
  is_root || owner_filter=(-user "$(id -un)")
  find "$LIVE/originals" -type f "${owner_filter[@]}" ! -perm -o+r \
    \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' -o -iname '*.webp' -o -iname '*.heic' -o -iname '*.heif' \) \
    -print0 2>/dev/null
}
count0() { tr -cd '\0' | wc -c | xargs; }

do_apply() {
  need_live
  local manifest="$STATE_DIR/changed-$(date +%Y%m%d-%H%M%S).lst" found left failed=0
  unreadable_photos > "$manifest.tmp"
  found=$(count0 < "$manifest.tmp")
  if [ "$found" -gt 0 ]; then
    xargs -0 chmod go+r < "$manifest.tmp" 2>/dev/null || failed=1
    mv "$manifest.tmp" "$manifest"
  else
    rm -f "$manifest.tmp"
  fi
  left=$(unreadable_photos | count0)
  log "apply found=$found still_unreadable=$left chmod_error=$failed"
  echo "found=$found still_unreadable=$left chmod_error=$failed"
}

mode="${1:-}"
# CCC passes source/destination paths (or nothing) to a "before" script, which runs as root.
if is_root && { [ -z "$mode" ] || [[ "$mode" == /* ]]; }; then mode=ccc-before; fi

case "$mode" in
  ccc-before)
    ( do_apply ) 2>&1 | sed 's/^/ccc-before: /'
    exit 0  # never abort the backup
    ;;
  scan)
    need_live
    echo "photos not readable by others, by extension:"
    unreadable_photos | tr '\0' '\n' | sed -nE 's/.*\.([^.]+)$/\1/p' | tr '[:upper:]' '[:lower:]' | sort | uniq -c
    echo "total: $(unreadable_photos | count0)"
    ;;
  apply)
    do_apply
    ;;
  revert)
    need_live
    total=0
    for m in "$STATE_DIR"/changed-*.lst(N); do
      n=$(count0 < "$m")
      xargs -0 chmod go-r < "$m" 2>/dev/null
      mv "$m" "$m.reverted"
      total=$((total + n))
    done
    log "revert paths=$total"
    echo "reverted=$total (recorded paths; files that no longer exist are ignored)"
    ;;
  diagnose)
    diagnose | while IFS= read -r line; do log "diagnose: $line"; echo "$line"; done
    ;;
  status)
    for f in "$USER_LOG" "$CCC_LOG"; do
      [ -f "$f" ] && { echo "== ${f/#$HOME/~}"; tail -6 "$f"; }
    done
    ;;
  uninstall-agent)
    [ -z "${NO_LAUNCHCTL:-}" ] && launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null
    rm -f "$HOME/Library/LaunchAgents/$LABEL.plist"; echo "removed $LABEL"
    ;;
  *)
    sed -n '2,22p' "$SCRIPT_PATH"; exit 2
    ;;
esac
