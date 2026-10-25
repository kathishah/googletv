#!/bin/zsh
# Make supported photo and video originals in the LIVE Photos library readable by others (adds go+r),
# so the Carbon Copy Cloner clone shared as SMB Guest becomes readable by the TV.
#
# Interactive use on the Mac mini, as the library owner (no sudo):
#   zsh make_photos_readable.sh scan              # counts of photos/videos not readable by others (no changes)
#   zsh make_photos_readable.sh apply             # add go+r to those files; record changed paths for revert
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
# Scope: regular files below the library's `originals` only, with the photo and video extensions the
# TV app displays or plays (as a normal user, only files owned by that user). Never touches the Photos
# database, other package folders, the clone, or directories. Only permission bits change. Videos
# include the short motion clips Photos stores next to Live Photos. Logs are counts only; the
# manifests hold private paths and stay on the Mac mini.
# INCLUDE_VIDEOS=0 restricts a run to photos (the behavior before 2026-10-17). `revert` always undoes
# every recorded path, photo or video.
# LIVE_VOLUME, LIVE, STATE_DIR, LOG, NO_LAUNCHCTL, INCLUDE_VIDEOS, and FAKE_ROOT can be overridden from the
# environment (for testing).

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

# LIVE_VOLUME is the mount point of the drive that holds the LIVE Photos library, for example
# /Volumes/MyPhotosDrive. Set it in the environment, or edit the placeholder below in your own copy
# (the root copy installed for CCC cannot see your shell environment). LIVE can be set directly instead.
: ${LIVE_VOLUME:="/Volumes/YOUR_LIVE_DRIVE"}
: ${LIVE:="$(find "$LIVE_VOLUME" -maxdepth 5 -type d -name 'Photos Library.photoslibrary' -prune -print 2>/dev/null | head -1)"}

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
  probe "$LIVE_VOLUME" volume
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

PHOTO_EXTS=(jpg jpeg png webp heic heif)
VIDEO_EXTS=(mov mp4 m4v 3gp 3g2 avi mpg mpeg mts m2ts)

# NUL-separated list of supported photos (and, unless INCLUDE_VIDEOS=0, videos) that others cannot
# read. As a normal user, only that user's files. As root (CCC), no owner filter: the external volume ignores
# ownership, so every file appears to belong to whoever looks at it.
unreadable_media() {
  local owner_filter=() exts=("${PHOTO_EXTS[@]}") ext
  local name_args=()
  is_root || owner_filter=(-user "$(id -un)")
  [ "${INCLUDE_VIDEOS:-1}" = 0 ] || exts+=("${VIDEO_EXTS[@]}")
  for ext in "${exts[@]}"; do name_args+=(-o -iname "*.$ext"); done
  name_args=("${(@)name_args[2,-1]}")  # drop the leading -o
  find "$LIVE/originals" -type f "${owner_filter[@]}" ! -perm -o+r \
    \( "${name_args[@]}" \) -print0 2>/dev/null
}
count0() { tr -cd '\0' | wc -c | xargs; }

do_apply() {
  need_live
  local manifest="$STATE_DIR/changed-$(date +%Y%m%d-%H%M%S).lst" found left failed=0
  unreadable_media > "$manifest.tmp"
  found=$(count0 < "$manifest.tmp")
  if [ "$found" -gt 0 ]; then
    xargs -0 chmod go+r < "$manifest.tmp" 2>/dev/null || failed=1
    mv "$manifest.tmp" "$manifest"
  else
    rm -f "$manifest.tmp"
  fi
  left=$(unreadable_media | count0)
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
    echo "photos and videos not readable by others, by extension:"
    unreadable_media | tr '\0' '\n' | sed -nE 's/.*\.([^.]+)$/\1/p' | tr '[:upper:]' '[:lower:]' | sort | uniq -c
    echo "total: $(unreadable_media | count0)"
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
    awk 'NR>1 && /^#/ {print; next} NR>1 {exit}' "$SCRIPT_PATH"; exit 2
    ;;
esac
