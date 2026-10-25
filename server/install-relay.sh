#!/usr/bin/env bash
# Claudio Code relay installer. Run on your OWN server next to relay.js
# (both files come from this repository). It generates a token and a
# self-signed TLS cert, writes config.json, installs the single npm
# dependency (ws), starts the relay, VERIFIES it answers, and prints the
# one-tap setup link (claudio://setup?...) plus a QR code — scan it with
# the phone camera and the app configures itself.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

command -v node >/dev/null || { echo "need node 18+ (apt install nodejs)"; exit 1; }
command -v openssl >/dev/null || { echo "need openssl"; exit 1; }
command -v curl >/dev/null || { echo "need curl (apt install curl)"; exit 1; }
[ -f relay.js ] || { echo "relay.js not found next to this script"; exit 1; }

# relay.js needs the ws module — the only npm dependency
if ! node -e "require('ws')" 2>/dev/null; then
  command -v npm >/dev/null || { echo "need npm to install the ws module (apt install npm)"; exit 1; }
  echo "installing ws module..."
  npm install --no-audit --no-fund ws
fi

# TLS pair: regenerate if EITHER half is missing (an unpaired cert breaks startup)
if [ ! -f cert.pem ] || [ ! -f key.pem ]; then
  openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem \
    -days 3650 -subj "/CN=claudio-relay" 2>/dev/null
fi
PIN=$(openssl x509 -in cert.pem -outform DER | openssl dgst -sha256 | awk '{print $NF}')

# Reuse token and port from an existing config; a broken config.json is reported,
# not silently overwritten with a fresh token (the phone still holds the old one).
TOKEN=""
if [ -f config.json ]; then
  if ! TOKEN=$(node -e "console.log(JSON.parse(require('fs').readFileSync('config.json','utf8')).token||'')" 2>/dev/null); then
    echo "config.json is unreadable (broken JSON) — fix or delete it and rerun"; exit 1
  fi
  # the live config port wins: the relay listens on it, not on env PORT
  CFG_PORT=$(node -e "console.log(JSON.parse(require('fs').readFileSync('config.json','utf8')).port||8443)")
  if [ -n "${PORT:-}" ] && [ "$PORT" != "$CFG_PORT" ]; then
    echo "port is taken from config.json: $CFG_PORT (env PORT=$PORT ignored — change it in config.json)"
  fi
  PORT="$CFG_PORT"
fi
PORT="${PORT:-8443}"
if [ -z "$TOKEN" ]; then
  TOKEN=$(head -c 24 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 32)
  cat > config.json <<EOF
{ "token": "$TOKEN", "port": $PORT, "tlsKey": "key.pem", "tlsCert": "cert.pem", "keepAudio": 100 }
EOF
fi

health() {
  curl -sk -m 5 "https://127.0.0.1:$PORT/status?token=$TOKEN" 2>/dev/null | grep -q '"ok":true'
}

if command -v pm2 >/dev/null; then
  if ! pm2 describe voice-relay >/dev/null 2>&1 && health; then
    # the port is held by a stray node (started without pm2) — a second instance would crash-loop
    echo "the relay is already running OUTSIDE pm2 — kill it (pkill -f 'node.*relay.js') and rerun the script"
    exit 1
  fi
  pm2 restart voice-relay 2>/dev/null || pm2 start relay.js --name voice-relay
  pm2 save >/dev/null 2>&1 || true
elif health; then
  echo "the relay is already running on port $PORT — not starting a second one"
else
  echo "(hint: install pm2 to keep the relay alive across reboots: npm i -g pm2)"
  nohup node relay.js >relay.out 2>&1 &
fi

# Honest check: print the link only if the relay really answers
sleep 2
if ! health; then
  echo
  echo "ERROR: the relay does not answer at https://127.0.0.1:$PORT/status"
  echo "see the logs: pm2 logs voice-relay  (or the relay.out file)"
  exit 1
fi

# Public IP: env IP wins; curl needs -f (ifconfig.me can answer 200 with junk)
# and -4 (a bare IPv6 makes an invalid URL); hostname -I is the offline fallback.
IP="${IP:-$(curl -sf -4 -m 5 ifconfig.me || true)}"
[ -n "$IP" ] || IP=$(hostname -I 2>/dev/null | awk '{print $1}' || true)
if ! echo "$IP" | grep -Eq '^[0-9]{1,3}(\.[0-9]{1,3}){3}$'; then
  echo
  echo "could not detect the external IPv4 (got: '$IP')"
  echo "rerun with an explicit address:  IP=x.x.x.x bash install-relay.sh"
  exit 1
fi
LINK="claudio://setup?url=https://$IP:$PORT&token=$TOKEN&pin=$PIN"

echo
echo "── phone setup ─────────────────────────────────────────"
echo "setup link (open it on the phone or scan the QR):"
echo
echo "  $LINK"
echo
if command -v qrencode >/dev/null; then
  qrencode -t ansiutf8 "$LINK"
else
  echo "(install qrencode to see the QR right here: apt install qrencode)"
fi
echo
echo "manual option — paste into the app as one line:"
echo "  https://$IP:$PORT|$TOKEN|$PIN"
