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
    echo "config.json нечитаем (битый JSON) — почини или удали его и перезапусти"; exit 1
  fi
  # порт живого конфига — истина: релей слушает его, а не env PORT
  CFG_PORT=$(node -e "console.log(JSON.parse(require('fs').readFileSync('config.json','utf8')).port||8443)")
  if [ -n "${PORT:-}" ] && [ "$PORT" != "$CFG_PORT" ]; then
    echo "порт берётся из config.json: $CFG_PORT (env PORT=$PORT проигнорирован — поменяй его в config.json)"
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
    # порт занят посторонним node (запускали без pm2) — второй экземпляр ушёл бы в crash-loop
    echo "релей уже запущен ВНЕ pm2 — убей его (pkill -f 'node.*relay.js') и перезапусти скрипт"
    exit 1
  fi
  pm2 restart voice-relay 2>/dev/null || pm2 start relay.js --name voice-relay
  pm2 save >/dev/null 2>&1 || true
elif health; then
  echo "релей уже работает на порту $PORT — второй не запускаю"
else
  echo "(hint: install pm2 to keep the relay alive across reboots: npm i -g pm2)"
  nohup node relay.js >relay.out 2>&1 &
fi

# Честная проверка: ссылку печатаем только если релей реально отвечает
sleep 2
if ! health; then
  echo
  echo "ОШИБКА: релей не отвечает на https://127.0.0.1:$PORT/status"
  echo "смотри логи: pm2 logs voice-relay  (или файл relay.out)"
  exit 1
fi

# Public IP: env IP wins; curl needs -f (ifconfig.me can answer 200 with junk)
# and -4 (a bare IPv6 makes an invalid URL); hostname -I is the offline fallback.
IP="${IP:-$(curl -sf -4 -m 5 ifconfig.me || true)}"
[ -n "$IP" ] || IP=$(hostname -I 2>/dev/null | awk '{print $1}' || true)
if ! echo "$IP" | grep -Eq '^[0-9]{1,3}(\.[0-9]{1,3}){3}$'; then
  echo
  echo "не смог определить внешний IPv4 (получил: '$IP')"
  echo "перезапусти с явным адресом:  IP=x.x.x.x bash install-relay.sh"
  exit 1
fi
LINK="claudio://setup?url=https://$IP:$PORT&token=$TOKEN&pin=$PIN"

echo
echo "── настройка телефона ──────────────────────────────────"
echo "ссылка-настройка (открой на телефоне или отсканируй QR):"
echo
echo "  $LINK"
echo
if command -v qrencode >/dev/null; then
  qrencode -t ansiutf8 "$LINK"
else
  echo "(поставь qrencode, чтобы видеть QR прямо здесь: apt install qrencode)"
fi
echo
echo "ручной вариант — вставить в приложении одной строкой:"
echo "  https://$IP:$PORT|$TOKEN|$PIN"
