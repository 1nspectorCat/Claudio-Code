# Claudio Code — setting up your own relay (SETUP)

<!-- DRAFT for the public repo (store build, English). -->

There is no shared server: every user runs THEIR OWN bridge. Three parts:

```
Claude Code (your computer)                 your server (any VPS)        phone
Stop hook voices the reply   ── HTTPS ──▶  relay.js (Node, port 8443) ◀── Claudio Code (APK)
and posts the OGG to the relay             session queues, /utt, WSS      plays replies, sends voice
```

## 1. Server (5 minutes)

Any Linux server with a public IP will do (the cheapest VPS is fine).

1. Install Node.js 18+.
2. Copy `server/relay.js` and `server/install-relay.sh` from this repository into one folder
   and run:
   ```bash
   bash install-relay.sh
   ```
   The script generates a token and a certificate, starts the relay and prints a
   **setup link** (`claudio://setup?...`) and its QR code.
3. Manual way (if the script does not fit). The relay reads its settings ONLY from
   `config.json` next to it:
   ```bash
   npm install ws
   openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 3650 -subj "/CN=relay"
   TOKEN=$(head -c 24 /dev/urandom | base64 | tr -d '+/=')
   printf '{ "token": "%s", "port": 8443, "tlsKey": "key.pem", "tlsCert": "cert.pem", "keepAudio": 100 }\n' "$TOKEN" > config.json
   openssl x509 -in cert.pem -outform DER | sha256sum | awk '{print $1}'   # PIN — hex only, without " -"
   ```
   and start the relay (pm2 is best): `node relay.js`.
   Port 8443 is only an example — any free port works; open the same port in your firewall
   and use it in the app's address.

## 2. Computer with Claude Code (10 minutes)

1. Copy `desktop/readback.py` to `~/.claude/voice/` and fill in `config.json` (relay address,
   token).
2. Register it as a Stop hook in `~/.claude/settings.json` — every session reply will be voiced
   (edge-tts + ffmpeg) and sent to the relay.
3. In a session, start the inbox poller (the voice-bridge skill from the repository).

<!-- TODO before publishing: readback.py must use an English edge-tts voice for public users. -->

## 3. Phone (1 minute)

1. Install the app.
2. Scan the QR code from your server with the camera (or open the `claudio://setup?...` link) —
   address, token and fingerprint are set automatically.
   Fallback: settings → "advanced" → paste one line `address|token|pin` (or JSON).
3. Tap the circle and say "hello". Done.

## Whisper (optional, advanced)

By default recognition uses Android's own recognizer. If you want server-side recognition
(better in noise), install whisper.cpp and `transcribe.js` from the repository on your server,
then turn on the Whisper switch in the app's "advanced" section.

Recognition language is set by the `WHISPER_LANG` environment variable of `transcribe.js`
(passed to whisper.cpp as `-l`):

- `auto` (default) — whisper detects the language of each recording;
- `en`, `de`, … — pin one language: more reliable on short phrases and in noise;
- `ru` — Russian; English voice commands ("send", "cancel") are then switched off on the server.

With pm2: `WHISPER_LANG=en pm2 start transcribe.js --name voice-whisper --update-env`
(or `env: { WHISPER_LANG: "en" }` in an ecosystem file). Server-side voice commands follow the
same strict rule as the app: "send" / "send it" / "send please" / "send now" or "cancel" only
as the last sentence of their own.
