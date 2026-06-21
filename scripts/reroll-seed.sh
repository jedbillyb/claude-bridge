#!/usr/bin/env bash
#
# reroll-seed.sh — wipe the Minecraft world and reroll the seed on the OCI
# Pelican/Wings server. DESTRUCTIVE: it deletes the current world folders
# (world / world_nether / world_the_end) so Paper regenerates a fresh world
# with a new seed on the next start.
#
# Usage (run from your desktop; relies on your `ssh oci` host alias):
#   ./reroll-seed.sh                 # wipe world, random seed   (asks to confirm)
#   ./reroll-seed.sh 8675309         # wipe world, specific seed (number or text)
#   ./reroll-seed.sh --backup        # tar the world into backups/ before wiping
#   ./reroll-seed.sh -y 8675309      # skip the typed confirmation
#   ./reroll-seed.sh --list          # show current level-name + world folders/sizes
#
# IMPORTANT:
#   1. STOP the server in the Pelican panel BEFORE running. The script refuses to
#      wipe while the server process is alive (deleting a live world corrupts it).
#   2. After it finishes, START the server in the panel — Paper builds the new world.
#   3. This is irreversible unless you used --backup. There is no undo.
set -euo pipefail

SSH="${SSH:-ssh oci}"

MODE="new"; SEED=""; BACKUP=0; YES=0
while [ $# -gt 0 ]; do
  case "$1" in
    --list)      MODE="list" ;;
    --backup)    BACKUP=1 ;;
    -y|--yes)    YES=1 ;;
    --help|-h)   sed -n '2,30p' "$0"; exit 0 ;;
    -*)          echo "error: unknown option '$1'" >&2; exit 1 ;;
    *)           SEED="$1" ;;
  esac
  shift
done

if [ -n "$SEED" ] && ! [[ "$SEED" =~ ^-?[A-Za-z0-9_]+$ ]]; then
  echo "error: seed must be letters/digits/underscore (optionally leading -)" >&2; exit 1
fi

# Typed confirmation happens locally (the remote script's stdin is the heredoc,
# so it can't read your keyboard).
if [ "$MODE" = "new" ] && [ "$YES" -eq 0 ]; then
  echo "This will PERMANENTLY DELETE the current world on the server."
  [ "$BACKUP" -eq 1 ] && echo "(a backup will be made first)" || echo "(no backup — pass --backup to keep one)"
  printf "Type DELETE to proceed: "
  read -r ans
  [ "$ans" = "DELETE" ] || { echo "aborted."; exit 1; }
fi

# shellcheck disable=SC2029
$SSH "sudo bash -s -- $(printf '%q ' "$MODE" "$SEED" "$BACKUP")" <<'REMOTE'
set -euo pipefail
MODE="$1"; SEED="${2:-}"; BACKUP="${3:-0}"

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

cur="$(get_prop level-name)"; cur="${cur:-world}"
WORLDS=("$cur" "${cur}_nether" "${cur}_the_end")

if [ "$MODE" = "list" ]; then
  echo "current level-name: $cur"
  echo "world folders on disk:"
  for d in "$VOL"/world*/; do
    [ -d "$d" ] && printf '  %-20s %s\n' "$(basename "$d")" "$(du -sh "$d" 2>/dev/null | cut -f1)"
  done
  exit 0
fi

# Safety: never wipe a live world. The server's java process is visible on the
# host even though it runs in the container. The '[s]' trick stops grep (or the
# detector) from matching its own command line.
if ps -eo args 2>/dev/null | grep -q '[s]erver\.jar'; then
  echo "error: the server appears to be RUNNING. Stop it in the Pelican panel first." >&2
  exit 1
fi

if [ "$BACKUP" -eq 1 ]; then
  ts="$(date +%Y%m%d_%H%M%S)"
  mkdir -p "$VOL/backups"
  present=(); for w in "${WORLDS[@]}"; do [ -d "$VOL/$w" ] && present+=("$w"); done
  if [ "${#present[@]}" -gt 0 ]; then
    out="$VOL/backups/${cur}_${ts}.tar.gz"
    tar -czf "$out" -C "$VOL" "${present[@]}"
    chown -R --reference="$VOL/server.properties" "$VOL/backups"
    echo "backup written: $out ($(du -sh "$out" 2>/dev/null | cut -f1))"
  fi
fi

deleted=()
for w in "${WORLDS[@]}"; do
  if [ -d "$VOL/$w" ]; then rm -rf "${VOL:?}/$w"; deleted+=("$w"); fi
done

set_prop level-name "$cur"
set_prop level-seed "$SEED"

echo "deleted: ${deleted[*]:-<none found>}"
echo "level-name: $cur"
echo "level-seed: ${SEED:-<random>}"
echo
echo "Now START the server in the Pelican panel — Paper will generate the new world."
REMOTE
