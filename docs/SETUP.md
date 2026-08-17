# Claudio Code — full setup

There is no shared cloud: every user runs their own relay. Three parts:

```
Claude Code (your computer)              your server (any VPS)          phone
Stop hook speaks the answer  ── HTTPS ──▶  relay.js (Node, :8443)  ◀── Claudio Code (APK)
and pushes OGG to the relay                per-session queues, WSS     plays answers, sends speech
```

The relay is the only meeting point. Both directions go through it, in both recognition
modes — the phone never talks to your computer directly.

## 1. Server

Any Linux box with a public IP. Requirements: Node.js 18+, `openssl`, `curl`.

### Scripted

```bash
git clone https://github.com/1nspectorCat/Claudio-Code.git
cd Claudio-Code/server
bash install-relay.sh
```

It writes `config.json` (fresh token, `chmod 600`), generates a self-signed TLS pair,
installs `ws`, starts the relay (under pm2 if pm2 is present), verifies it answers, tests
whether the port is reachable from outside, and prints the `claudio://setup?…` link plus
a QR code.

Re-running it is safe: an existing token and port are reused, so the phone keeps working.

### Manual

```bash
cd server && npm install --omit=dev
openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 3650 -subj "/CN=claudio-relay"
TOKEN=$(head -c 24 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 32)
printf '{ "token": "%s", "port": 8443, "tlsKey": "key.pem", "tlsCert": "cert.pem", "keepAudio": 100 }\n' "$TOKEN" > config.json
chmod 600 config.json key.pem
openssl x509 -in cert.pem -outform DER | openssl dgst -sha256 | awk '{print $NF}'   # the PIN: hex only
node relay.js
```

The relay reads settings **only** from `config.json` next to itself. Useful extra keys:
`"logText": true` turns the message text back on in `relay.log` (default logs lengths
only), `"keepAudio"` caps how many voice files are kept per session.

### Open the port

`relay.js` listens on `0.0.0.0:8443`. On most providers that is closed by default, and a
closed port looks exactly like a broken app: the installer succeeds, the phone says "no
connection". Open it in **both** places:

```bash
sudo ufw allow 8443/tcp
```

and in the provider's cloud firewall (Oracle "security list", AWS "security group", GCP
"firewall rule", Azure "network security group").

If you want the relay to survive reboots:

```bash
npm i -g pm2
pm2 start relay.js --name voice-relay
pm2 startup          # run the command it prints, as root
pm2 save
```

## 2. Computer with Claude Code

### Answers → phone

1. Copy `desktop/readback.py` to `~/.claude/voice/`.
2. Create `~/.claude/voice/bridge/config.json` from
   `desktop/bridge-config.example.json`: `url`, `token`, `cacert`, optional `voice`
   (any edge-tts voice name).
3. Copy `cert.pem` from the server into `~/.claude/voice/bridge/`. Without it curl
   refuses the self-signed certificate and nothing reaches the relay.
4. Install the Python dependency: `pip install edge-tts`. Make sure `ffmpeg` and `curl`
   are on PATH.
5. Register the Stop hook in `~/.claude/settings.json` (merge, do not overwrite):

```json
{ "hooks": { "Stop": [ { "hooks": [ { "type": "command",
  "command": "python3 $HOME/.claude/voice/readback.py", "async": true } ] } ] } }
```

Windows: write the full path, `"command": "python C:\\Users\\<you>\\.claude\\voice\\readback.py"`.

6. Create the empty toggle file `~/.claude/voice/ON`. Deleting it turns voice off.

Diagnostics live in `~/.claude/voice/readback.log`: `sent 1/1 voice part(s)` means the
answer went out; `bridge push failed rc=60` means the certificate is missing or wrong;
`no relay config` means step 2 was skipped.

### Your voice → sessions

The relay queues your speech per session id, and `GET /consume` empties that queue **once**.
Something inside the session must poll it. Copy `desktop/skill/voice-bridge/` into
`~/.claude/skills/` and ask the session to arm the bridge (`/voice-bridge`). The skill
starts a background loop that fetches messages and hands them to the session.

Two things worth knowing:

- **One poller per session.** Two loops split the queue and half your messages disappear
  into the one that is not being read.
- **Session ids change** when Claude Code compacts the context. `readback.py` keeps a
  per-project history in `~/.claude/voice/bridge/sids_<project>.txt`; the poller follows
  the recent ids from there, so a compaction does not silence the radio.

To see the raw stream in a terminal: `bash desktop/poller.sh`.

## 3. Phone

1. Install the APK from Releases.
2. Point the **phone camera** at the QR code from step 1 (the app has no scanner — the
   camera opens the `claudio://setup?…` link). Confirm the server when asked.
   Manual path: Settings → «для продвинутых» → paste `address|token|pin`.
3. Press the circle and say something.

## 4. Whisper on the server (optional, better in noise)

By default recognition happens on the phone. Server-side whisper.cpp is more accurate in
wind and traffic, at the cost of a couple of seconds and some CPU.

```bash
git clone https://github.com/ggerganov/whisper.cpp && cd whisper.cpp
cmake -B build && cmake --build build -j --config Release
bash ./models/download-ggml-model.sh base
```

Then run the transcriber next to the relay (it reads the same `config.json` and watches
the same `utt/` directory):

```bash
cd Claudio-Code/server
WHISPER_BIN=$HOME/whisper.cpp/build/bin/whisper-cli \
WHISPER_MODEL=$HOME/whisper.cpp/models/ggml-base.bin \
pm2 start transcribe.js --name voice-whisper
pm2 logs voice-whisper       # expect: voice-whisper up: watching …/utt, model ggml-base.bin
```

If the binary or the model is missing, the process says so and exits instead of silently
transcribing nothing. Override `VB_DIR` if your relay directory is elsewhere.

Finally turn on the toggle in the app: Settings → «для продвинутых» → «распознавание whisper».

Sizing, measured on a 4-core ARM VPS: a 19-second segment takes ~5.6 s with the `base`
model — comfortably faster than speech. The `small` model is roughly 3× slower and was
not worth it.

## Troubleshooting

| Symptom | Where to look |
|---|---|
| No answers on the phone | `~/.claude/voice/readback.log` on the computer |
| `bridge push failed rc=60` | `cert.pem` missing in `~/.claude/voice/bridge/` |
| Phone says "no connection" | port 8443 closed in the cloud firewall |
| Speech recognized but the session never sees it | no poller, or two pollers; check `GET /readerage?session=<sid>` |
| Relay state | `curl -s --cacert cert.pem "https://IP:8443/status?token=TOKEN"` |
