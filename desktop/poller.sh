#!/usr/bin/env bash
# Claudio Code — inbound poller / diagnostic.
#
# The relay keeps one queue per session id. GET /consume empties that queue and hands
# the text over exactly ONCE, so only one poller per session may run — otherwise two of
# them split your messages and half of what you say disappears.
#
# In normal use the session itself runs this loop through the skill in
# desktop/skill/voice-bridge/ (a background monitor whose output lands in the session).
# This script is the same loop for a terminal: use it to check that your speech reaches
# the relay at all, before blaming Claude Code.
#
#   bash poller.sh                # follow every session id this machine has answered from
#   bash poller.sh <session-id>   # follow one specific session
#
set -euo pipefail

VOICE_DIR="${VOICE_DIR:-$HOME/.claude/voice}"
CFG="$VOICE_DIR/bridge/config.json"
[ -f "$CFG" ] || { echo "no relay config at $CFG (see README)"; exit 1; }

URL=$(node -e "console.log(JSON.parse(require('fs').readFileSync('$CFG','utf8')).url||'')" 2>/dev/null \
      || python3 -c "import json;print(json.load(open('$CFG'))['url'])")
TOKEN=$(node -e "console.log(JSON.parse(require('fs').readFileSync('$CFG','utf8')).token||'')" 2>/dev/null \
      || python3 -c "import json;print(json.load(open('$CFG'))['token'])")
CACERT="$VOICE_DIR/bridge/cert.pem"
CA=()
[ -f "$CACERT" ] && CA=(--cacert "$CACERT")

sids() {
  if [ $# -gt 0 ]; then echo "$1"; return; fi
  # readback.py records the ids it has answered under, newest last, one file per project.
  # Ids change when a session is compacted, so follow the recent ones, not just the first.
  cat "$VOICE_DIR"/bridge/sids_*.txt 2>/dev/null | tail -20 | sort -u
}

echo "polling ${URL} every 3s — Ctrl-C to stop"
while :; do
  for sid in $(sids "$@"); do
    out=$(curl -s -m 8 "${CA[@]}" "$URL/consume?token=$TOKEN&session=$sid" || true)
    [ -n "$out" ] && printf '%s | %s\n' "${sid:0:8}" "$out"
  done
  sleep 3
done
