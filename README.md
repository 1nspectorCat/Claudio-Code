# Claudio Code

A hands-free walkie-talkie for [Claude Code](https://claude.com/claude-code): your Claude Code
sessions speak their replies into your earphones, and you answer by voice from an Android
phone — while walking, riding or cooking. Several sessions become channels you can switch
between.

```
Claude Code (your computer)                 your server (any VPS)        phone
Stop hook voices the reply   ── HTTPS ──▶  relay.js (Node, port 8443) ◀── Claudio Code app
and posts the audio to the relay           session queues, /utt, WSS      plays replies, sends voice
```

Port 8443 is only an example: the relay listens on whatever port you put in its `config.json`.

There is no shared server: everyone runs their own small relay. Your audio and text go only
through machines you control.

## What it does

- Plays each session's reply in your earphones as soon as it is ready.
- You answer by voice; the message lands in the session that spoke last (or the one you picked).
- Say **"send"** to send, **"cancel"** to drop the draft — or use the big button on the screen
  or your headset button. A voice command works only as a phrase of its own ("send", "send it",
  "send please", "send now"; "cancel"), so "I'll send it tomorrow" never sends by accident.
- Several sessions = several channels: choose which ones you hear, and which one you answer.
- System phrases are short and never talk over a reply; the reply pauses and resumes.

## Setup (about 15 minutes)

1. **Server** — any Linux box with a public IP and Node.js 18+. Copy `server/relay.js` and
   `server/install-relay.sh`, run `bash install-relay.sh`. It creates a token and a
   certificate, starts the relay and prints a setup link (`claudio://setup?...`) with a QR code.
2. **Computer with Claude Code** — copy `desktop/readback.py` to `~/.claude/voice/`, fill in
   its `config.json` (relay address, token) and register it as a Stop hook in
   `~/.claude/settings.json`. Every reply is voiced (edge-tts + ffmpeg) and sent to the relay.
   In a session, start the inbox poller (the `voice-bridge` skill from this repo).
3. **Phone** — install the app (APK from [Releases](https://github.com/1nspectorCat/Claudio-Code/releases/latest)), scan the QR code from your server. Address, token and
   certificate fingerprint are set automatically. Tap the circle and say "hello".

Details: [SETUP.md](docs/SETUP.md). How to use the app: [GUIDE.md](docs/GUIDE.md).
Privacy: [PRIVACY.md](docs/PRIVACY.md).

## Speech recognition

By default the app uses Android's own speech recognizer (English, en-US). Optionally you can
run whisper.cpp on your server for better recognition in noise (`transcribe.js`, "advanced" →
Whisper in the app). Whisper detects the language automatically; to pin it, set
`WHISPER_LANG` (see [SETUP.md](docs/SETUP.md#whisper-optional-advanced)).

## In development

A multi-provider hub: one server that runs coding sessions on any subscription you already have
(Claude, Gemini, OpenAI and others), with channels created from the phone and a lead model handing
routine work to cheaper ones. It works in the author's own setup today; the app screens for it are
hidden in this build until the hub server is cleaned up and published.

## Requirements

- Android phone, Bluetooth earphones or a headset (the app will not play replies through the
  phone speaker unless you allow it).
- Claude Code on a computer, a small server you control.

## License

MIT, see [LICENSE](LICENSE).
