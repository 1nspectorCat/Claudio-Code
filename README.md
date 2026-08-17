# Claudio Code — voice walkie‑talkie for Claude Code sessions

Talk to your Claude Code sessions **by voice, hands‑free, from your phone** — their
answers are read out loud into your headset, you reply by speaking, and several
sessions can run at once as separate channels.

Built for one real situation: you are walking, riding, driving or cooking, both hands
are busy, and your agents on the desktop keep finishing tasks and asking questions.

> Russian speech out of the box (server‑side whisper.cpp or Android recognition).
> The UI is Russian; the code and setup are English.

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
- **Nothing is lost when you are offline.** Answers wait on your relay and arrive later.

## What you need

- An **Android phone** (Android 8+).
- A **Linux server** you control (the cheapest VPS is fine) — it relays audio and text
  between the desktop and the phone. There is no shared cloud: everything is yours.
- **Claude Code** on your desktop (Windows/macOS/Linux) with Python 3 and ffmpeg.

## Setup in three steps

**1. Server (about 5 minutes)**

```bash
# on your server, in one directory
git clone <this repo> && cd <repo>/server
bash install-relay.sh
```

The script generates a token and a TLS certificate, installs the single npm dependency,
starts the relay, verifies it answers, and prints a **setup link + QR code**.

**2. Phone**

Install the APK from [Releases](../../releases), open it and scan the QR code from
step 1 — address, token and certificate fingerprint fill themselves in. Nothing to type.

**3. Desktop**

Copy `desktop/readback.py` to `~/.claude/voice/` and register it as a Stop hook in
`~/.claude/settings.json`:

```json
{ "hooks": { "Stop": [ { "hooks": [ { "type": "command",
  "command": "python ~/.claude/voice/readback.py", "async": true } ] } ] } }
```

Create `~/.claude/voice/bridge/config.json` with the relay URL and token printed by the
installer, and an empty file `~/.claude/voice/ON` to switch the voice output on.

Each session also needs a tiny poller that pulls your replies from the relay — see
[docs/SETUP.md](docs/SETUP.md).

## Two recognition modes

| | Android recognition | Server whisper.cpp |
|---|---|---|
| Setup | works out of the box | build whisper.cpp on the server |
| Quality in noise | mediocre | noticeably better |
| Headset button during dictation | works | does not (the headset eats the press) |

Switch in Settings → «для продвинутых» → «распознавание whisper».

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

## Privacy

- Audio never leaves your machines: speech recognition runs either on the phone or on
  **your** server.
- The relay is yours, the token is yours, TLS is pinned to your own certificate.
- The phone keeps the conversation locally so it survives a relay restart.
- See [docs/PRIVACY.md](docs/PRIVACY.md).

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
