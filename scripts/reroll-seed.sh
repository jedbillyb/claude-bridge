#!/usr/bin/env bash
#
# reroll-seed.sh — reroll the Minecraft world/seed on the OCI Pelican/Wings server.
#
# Uses the SAFE, reversible approach: it switches the server's `level-name` to a
# fresh world folder (and sets `level-seed`), so Paper generates a brand-new world
# on the next start. Your existing world is NOT touched or deleted — it stays on
# disk, so you can switch back any time.
#
# Usage (run from your desktop; relies on your `ssh oci` host alias):
#   ./reroll-seed.sh                 # new world, random seed
#   ./reroll-seed.sh 8675309         # new world with a specific seed (number or text)
#   ./reroll-seed.sh --list          # show current level-name + existing world folders
#   ./reroll-seed.sh --use world     # switch back to an existing world folder
#
# AFTER running: click **Restart** in the Pelican panel. (Stop/start must go
# through the panel — a direct docker/systemctl restart desyncs Wings.)
#
# You can run this while the server is up; it only edits server.properties and
# leaves the live world alone. The change takes effect on the next restart.
set -euo pipefail

SSH="${SSH:-ssh oci}"

MODE="new"
ARG=""

case "${1:-}" in
  --list) MODE="list" ;;
  --use)  MODE="use"; ARG="${2:-}";
          [ -n "$ARG" ] || { echo "error: --use needs a world folder name" >&2; exit 1; } ;;
  --help|-h) sed -n '2,30p' "$0"; exit 0 ;;
  "")     MODE="new"; ARG="" ;;
  -*)     echo "error: unknown option '$1'" >&2; exit 1 ;;
  *)      MODE="new"; ARG="$1" ;;
esac

# Validate user input (their own server, but avoid surprises in sed/shell).
if [ "$MODE" = "new" ] && [ -n "$ARG" ] && ! [[ "$ARG" =~ ^-?[A-Za-z0-9_]+$ ]]; then
  echo "error: seed must be letters/digits/underscore (optionally leading -)" >&2; exit 1
fi
if [ "$MODE" = "use" ] && ! [[ "$ARG" =~ ^[A-Za-z0-9_]+$ ]]; then
  echo "error: world name must be letters/digits/underscore" >&2; exit 1
fi

# Run the work remotely as root. Args are passed safely via printf %q.
# shellcheck disable=SC2029
$SSH "sudo bash -s -- $(printf '%q ' "$MODE" "$ARG")" <<'REMOTE'
set -euo pipefail
MODE="$1"; ARG="${2:-}"

# Real server volumes are UUID-named; skip hidden dirs like .sftp.
mapfile -t VOLS < <(find /var/lib/pelican/volumes -maxdepth 1 -mindepth 1 -type d ! -name '.*' 2>/dev/null)
if [ "${#VOLS[@]}" -ne 1 ]; then
  echo "error: expected exactly one server volume, found ${#VOLS[@]}." >&2
  printf '  %s\n' "${VOLS[@]}" >&2
  exit 1
fi
VOL="${VOLS[0]}"
PROP="$VOL/server.properties"
[ -f "$PROP" ] || { echo "error: $PROP not found" >&2; exit 1; }

get_prop() { grep -E "^$1=" "$PROP" | head -1 | cut -d= -f2-; }
set_prop() {
  local key="$1" val="$2"
  if grep -qE "^${key}=" "$PROP"; then
    sed -i "s|^${key}=.*|${key}=${val}|" "$PROP"
  else
    printf '%s=%s\n' "$key" "$val" >> "$PROP"
  fi
}

cur="$(get_prop level-name)"

if [ "$MODE" = "list" ]; then
  echo "current level-name: ${cur:-<unset>}"
  echo "world folders on disk:"
  for d in "$VOL"/world*/; do [ -d "$d" ] && echo "  $(basename "$d")"; done
  exit 0
fi

if [ "$MODE" = "use" ]; then
  [ -d "$VOL/$ARG" ] || { echo "error: world folder '$ARG' does not exist in $VOL" >&2; exit 1; }
  set_prop level-name "$ARG"
  echo "level-name -> $ARG"
  echo "Now click Restart in the Pelican panel to load it."
  exit 0
fi

# MODE=new: find the next free worldN name (keeps the old world intact).
max=1
for d in "$VOL"/world*/; do
  [ -d "$d" ] || continue
  n="$(basename "$d")"; n="${n#world}"
  if [[ "$n" =~ ^[0-9]+$ ]] && [ "$n" -gt "$max" ]; then max="$n"; fi
done
new="world$((max+1))"

set_prop level-name "$new"
set_prop level-seed "$ARG"

echo "Old world kept as: ${cur:-world}"
echo "New world         : $new"
echo "Seed              : ${ARG:-<random>}"
echo
echo "Now click Restart in the Pelican panel. Paper will generate the new world."
echo "To undo: ./reroll-seed.sh --use ${cur:-world}  (then restart again)"
REMOTE
