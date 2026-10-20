#!/bin/zsh
# Temporary debug logging for the Mac mini's SMB server and directory service, to find out why SMB
# Guest is refused (STATUS_ACCESS_DENIED on every open) some mornings until the condition clears.
#
# Run on the Mac mini:
#   sudo zsh smb_debug_logging.sh on                 # debug-level, persisted logs for smbd and opendirectoryd
#   sudo zsh smb_debug_logging.sh status             # show the current log configuration
#   zsh smb_debug_logging.sh collect 07:30 08:15     # save today's window and print a counts-only summary
#   sudo zsh smb_debug_logging.sh off                # back to the default log configuration
#
# `collect` takes HH:MM times for today, or full "YYYY-MM-DD HH:MM" values. The full log goes to
# ~/smb-debug/ and stays on the Mac mini (it can hold client addresses and paths); the summary printed
# to the terminal has message types and counts only. Debug logging enlarges the system log, so turn it
# off once the test is done. Nothing here touches the share, the library, or any sharing setting.

set -u
umask 077
SUBSYSTEMS=(com.apple.smb com.apple.opendirectoryd)
: ${OUT_DIR:="$HOME/smb-debug"}  # overridable for testing

need_root() { [ "$EUID" -eq 0 ] || { echo "error: run with sudo for '$1'" >&2; exit 1; }; }

show_status() {
  for s in $SUBSYSTEMS; do
    print -r -- "== $s"
    /usr/bin/log config --status --subsystem "$s"
  done
}

# HH:MM means today; anything longer is passed to `log show` as given.
as_time() { [[ "$1" == ??:?? ]] && print -r -- "$(date +%Y-%m-%d) $1:00" || print -r -- "$1"; }

collect() {
  local start end file
  start="$(as_time "$1")"; end="$(as_time "$2")"
  mkdir -p "$OUT_DIR"
  file="$OUT_DIR/smb-debug-$(date +%Y%m%d-%H%M%S).log"
  /usr/bin/log show --debug --info --style compact --start "$start" --end "$end" \
    --predicate 'process == "smbd" OR process == "opendirectoryd" OR subsystem == "com.apple.smb"' > "$file"
  print -r -- "saved $(wc -l < "$file" | tr -d ' ') lines for $start to $end in $file"
  print -r -- "== Guest logins per minute (each failing TV attempt makes about 8)"
  grep "bind_tree" "$file" | awk '{print substr($2,1,5)}' | uniq -c
  print -r -- "== refusal-like lines per minute"
  grep -iE "denied|eacces|eperm|not permitted|access_denied|authoriz|0xc0000022" "$file" \
    | awk '{print substr($2,1,5)}' | uniq -c
  print -r -- "== top message types (numbers and hex replaced)"
  sed -E 's/^[^ ]+ [^ ]+ +[A-Za-z]+ +[^ ]+ +//; s/0x[0-9a-fA-F]+/H/g; s/[0-9]+/N/g' "$file" \
    | cut -c1-160 | sort | uniq -c | sort -rn | head -40
}

case "${1:-}" in
  on)
    need_root on
    for s in $SUBSYSTEMS; do /usr/bin/log config --subsystem "$s" --mode "level:debug,persist:debug"; done
    print -r -- "debug logging on at $(date '+%Y-%m-%d %H:%M'); run 'off' after the test"
    show_status
    ;;
  off)
    need_root off
    for s in $SUBSYSTEMS; do /usr/bin/log config --subsystem "$s" --reset; done
    print -r -- "debug logging reset at $(date '+%Y-%m-%d %H:%M')"
    show_status
    ;;
  status)
    need_root status
    show_status
    ;;
  collect)
    [ $# -eq 3 ] || { echo "usage: zsh $0 collect START END   (HH:MM today, or 'YYYY-MM-DD HH:MM')" >&2; exit 2; }
    collect "$2" "$3"
    ;;
  *)
    sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
