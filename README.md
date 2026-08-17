# Claudio Code — voice walkie‑talkie for Claude Code sessions

Talk to your Claude Code sessions **by voice, hands‑free, from your phone** — their
answers are read out loud into your headset, you reply by speaking, and several
sessions run at once as separate channels.

Built for one real situation: you are walking, riding, driving or cooking, both hands
are busy, and your agents on the desktop keep finishing tasks and asking questions.

> **Android 8+ only** — there is no iOS version and none is planned.
> **Russian speech only** — recognizer, voice, spoken commands and UI are Russian
> (see [Using another language](#using-another-language)).
> **Your own Linux server required** — there is no hosted service, by design.

---

## What it actually does

- **Answers are spoken to you.** A Stop hook on the desktop turns each session reply
  into speech and pushes it to your phone.
- **You reply by voice.** Say your message, finish with the stop‑word — the text lands
  in that session as if you typed it.
- **Several sessions = several channels.** Pick which ones you hear, answer whoever
  spoke last, or address a specific channel.
- **A gap between channels.** When two sessions answer back to back, the radio pauses
  and offers you 12 seconds to answer the first one before the second starts.
- **Chat feed on screen.** Their answers on the left, yours on the right, replayable.
- **Reconnect catch‑up.** When the phone comes back online it picks up what arrived in
  the last 30 minutes (up to the 10 most recent parts). That backlog lives in the
  relay's memory, so restarting the relay clears it; whatever already reached the phone
  is stored on the phone and survives.

## What you need

- An **Android phone** (Android 8+). Bluetooth headset with a button is a plus, not a must.
- A **Linux server** you control (the cheapest VPS is fine) with **Node.js 18+**,
  `openssl` and `curl`. Optional: `qrencode` (prints the setup QR in the terminal),
  `pm2` (keeps the relay running across reboots).
- **Claude Code** on your desktop (Windows/macOS/Linux) with **Python 3**,
  **`pip install edge-tts`**, **ffmpeg** and **curl** available on PATH.

## Setup

### 1. Server (about 5 minutes)

```bash
git clone https://github.com/1nspectorCat/Claudio-Code.git
cd Claudio-Code/server
bash install-relay.sh
```

The script generates a token and a self‑signed TLS certificate, installs the single npm
dependency, starts the relay, verifies it answers, checks whether the port is reachable
from outside, and prints a **setup link + QR code**.

**Open the port.** The relay listens on TCP 8443. On most providers that port is closed
by default — in the VM firewall (`sudo ufw allow 8443/tcp`) *and* in the provider's own
cloud firewall / security list (Oracle, AWS, GCP, Azure each have one). The installer
warns you if it cannot reach itself from outside.

### 2. Phone (1 minute)

Install the APK from [Releases](../../releases). Then point your phone's **camera app**
at the QR code from step 1 — the app has no built‑in scanner; the camera recognizes the
`claudio://setup?…` link and opens Claudio Code with address, token and certificate
fingerprint filled in. The app asks you to confirm the server before saving it.

No camera? In the app: Settings → «для продвинутых» → paste one line: `address|token|pin`.

### 3. Desktop — answers → phone

Copy `desktop/readback.py` to `~/.claude/voice/` and create
`~/.claude/voice/bridge/config.json` (template: `desktop/bridge-config.example.json`):

```json
{ "url": "https://YOUR.SERVER.IP:8443",
  "token": "the token install-relay.sh printed",
  "cacert": "cert.pem",
  "voice": "ru-RU-DmitryNeural" }
```

**Copy `cert.pem` from the server** into `~/.claude/voice/bridge/` — the relay's
certificate is self‑signed, and without this file every push fails.

Register the hook in `~/.claude/settings.json`. **Merge this into your existing file —
do not replace it:**

```json
{ "hooks": { "Stop": [ { "hooks": [ { "type": "command",
  "command": "python3 $HOME/.claude/voice/readback.py", "async": true } ] } ] } }
```

On Windows use the expanded path instead — `%USERPROFILE%` is not expanded inside the
hook string either, so write it out: `"command": "python C:\\Users\\<you>\\.claude\\voice\\readback.py"`
(use `py` instead of `python` if that is how Python is installed).

Finally create an empty file `~/.claude/voice/ON` — its presence is the on/off switch
for voice output. Deleting it silences everything.

**Check it works:** answer something in a session, then look at
`~/.claude/voice/readback.log`. A line `sent 1/1 voice part(s)` means the answer reached
your relay. Every refusal is written there too, with the reason.

### 4. Desktop — your voice → sessions

The relay holds one queue per session, and something must drain it. Copy
`desktop/skill/voice-bridge/` into `~/.claude/skills/` — then a session arms its own
receiver when you say "arm the voice bridge" (or `/voice-bridge`). The skill explains
what the loop does and why every guard in it exists.

Only one poller per session: `GET /consume` hands the queue over exactly once, so two
pollers split your messages and half of them vanish.

To check the path by hand: `bash desktop/poller.sh` prints whatever the relay is holding.

## Two recognition modes

| | Android recognition | Server whisper.cpp |
|---|---|---|
| Setup | no extra server software | build whisper.cpp on your server |
| Quality in noise | mediocre | noticeably better |
| Headset button during dictation | works | does not (the headset eats the press) |

Both modes need your relay — it is the only path between the phone and your sessions.
Switch in Settings → «для продвинутых» → «распознавание whisper». Server‑side
recognition needs `whisper.cpp` and `server/transcribe.js`; see [docs/SETUP.md](docs/SETUP.md).

## Voice commands

| Say | Effect |
|---|---|
| `отправляй` | send what you dictated |
| `отбой` | wipe the draft, send nothing, put the mic to sleep |
| `отмена` | wipe the draft |
| `переключи на <name>` / `переключи на вторую` | change the target channel |
| `какие сессии` | read the channel list out loud |
| `открепи` | back to "answer whoever spoke last" |

Full user guide (in Russian): [docs/GUIDE.md](docs/GUIDE.md).

## Build it yourself

Requires JDK 17 and the Android SDK with platform 35.

```bash
./gradlew assembleStoreDebug     # app/build/outputs/apk/store/debug/
```

Two flavors: `store` ships with empty settings (what the Releases APK is built from),
`personal` bakes a relay address into the build from `local.properties` — handy if you
reinstall often and do not want to rescan the QR.

## Privacy

The app talks only to the relay you configured: recorded audio and text go there over
TLS pinned to your own certificate, and nowhere else. The relay is yours, the token is
yours, and the phone keeps the conversation locally so it survives a relay restart.

Three things do leave your machines, and you should know about them:

- **Speech recognition.** With the default Android recognizer, audio goes to whatever
  recognition service your phone uses — on most devices that is Google. Switching to
  server‑side whisper.cpp keeps audio on your own server.
- **Speech synthesis.** The desktop hook uses Microsoft's `edge-tts` cloud service, so
  the *text* of each answer is sent there to be turned into speech.
- **Nothing else.** No analytics, no crash reporting, no developer‑operated backend.

Details, including what is stored on the server and how to clear it:
[docs/PRIVACY.md](docs/PRIVACY.md).

## Using another language

Russian is hardcoded in four places — change all four:

- `EXTRA_LANGUAGE` in `app/src/main/java/com/vladiko/voicebridge/BridgeService.kt`
- `tts?.setLanguage(...)` in the same file
- `-l ru` in `server/transcribe.js`
- `DEFAULT_VOICE` in `desktop/readback.py`

The spoken commands (`отправляй`, `отбой`, …) are patterns in `BridgeService.kt` and
`server/transcribe.js`; the on‑screen UI is Russian throughout.

## Honest limitations

- You need your own server — there is no hosted option, by design.
- The stop‑word is unreliable in strong wind; use the on‑screen button or the headset
  button instead.
- The headset button does not reach the app while the whisper recorder holds the
  headset in call mode (measured, not guessed).
- Tested by one person, daily, on one phone. Expect rough edges elsewhere.

## Why it exists

Claude Code can be driven from a phone (Anthropic's Remote Control), and Claude has a
voice mode — but neither reads *your running sessions'* answers into your ear while you
walk, nor lets you keep several of them going by voice. This does exactly that, and it
was built and debugged in the field, on a scooter, over several weeks.

## License

MIT — see [LICENSE](LICENSE).
