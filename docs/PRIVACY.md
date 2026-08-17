# Privacy Policy — Claudio Code

*Last updated: 2026-08-17*

Claudio Code is a self-hosted voice walkie-talkie for AI coding sessions. The Android
app connects **only** to a relay server that you install and operate yourself. This
document covers the whole system: the app, the relay, and the desktop hook — because the
desktop hook does use two outside services, and pretending otherwise would be a lie.

## What the app records

The app records microphone audio **only while dictation is active** (a foreground-service
notification with a microphone badge is visible the whole time it can listen). Recorded
audio and recognized text are sent over TLS, pinned to your own certificate, to the relay
address you configured — and to no other address.

## What the developer collects

**Nothing.** No analytics, no advertising SDKs, no crash reporting, no developer-operated
backend. The developer never receives your audio, text, token, or any telemetry.

## Outside services this system uses

Three, and each can be avoided:

1. **Speech recognition (phone, default mode).** Android's `SpeechRecognizer` is provided
   by whatever recognition service is installed on your device — on most phones, Google.
   Your speech is processed by that service. **Avoid it** by switching on server-side
   recognition (Settings → «для продвинутых» → «распознавание whisper»): then audio goes
   to your own server and whisper.cpp transcribes it there.
2. **Speech synthesis (desktop hook).** `desktop/readback.py` synthesizes the voice with
   Microsoft's `edge-tts` cloud service, so the **text of each session answer** is sent to
   Microsoft. **Avoid it** by replacing the `synth()` function with a local TTS engine —
   everything else in the pipeline stays the same.
3. **Your Claude Code sessions** talk to Anthropic, as they always do. This project does
   not change that and does not add to it.

Nothing else leaves your machines.

## Where your data lives

- **On your relay server:** queued messages (in memory, dropped on restart), synthesized
  answer audio in `audio/` (capped by `keepAudio`, default 100 files per session), raw
  dictation segments in `utt/` while whisper is transcribing them (deleted after use, and
  swept hourly), and `relay.log`. The log records message **lengths**, not text, unless
  you set `"logText": true` in `config.json`; it rotates at 5 MB and keeps one old file.
  Delete any of it whenever you like — `rm -rf audio utt relay.log*` costs you nothing but
  the replay history.
- **On the phone:** the conversation feed and your settings (relay address, token,
  certificate fingerprint) in app-private storage, excluded from cloud backup
  (`allowBackup=false`).
- **On your computer:** `~/.claude/voice/readback.log` (a diagnostic log, no message text)
  and small bookkeeping files under `~/.claude/voice/bridge/`.

## Data sharing

None. Nobody but you has access to your relay, and the token that guards it is generated
on your server and never leaves it — except into the QR code you scan with your own phone.

## Permissions

- **Microphone** — dictation to your AI sessions; active only during use.
- **Bluetooth** — headset microphone and buttons.
- **Notifications / Foreground service** — the always-visible status of the radio,
  required by Android for background microphone use.

## Contact

Questions: open an issue in the project repository.
