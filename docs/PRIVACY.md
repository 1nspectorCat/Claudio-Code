# Privacy Policy — Claudio Code

*Last updated: 2026-08-14*

Claudio Code is a self-hosted voice walkie-talkie for AI coding sessions. It
connects **only** to a relay server that you install and operate yourself.

## What the app records

The app records microphone audio **only while dictation is active** (a
foreground-service notification with a microphone badge is always visible
while it can listen). Recorded audio is sent over TLS **exclusively to the
relay server address you configured** — a server you own. Nothing is sent
anywhere else.

## What the developer collects

**Nothing.** The app contains no analytics, no advertising SDKs, no crash
reporting, and no developer-operated backend. The developer of this app never
receives your audio, your text, your token, or any telemetry.

## Where your data lives

- Audio and transcribed text: on **your** relay server, under your control.
- Settings (relay address, access token, certificate fingerprint): stored
  locally on the device in app-private storage, excluded from cloud backup
  (`allowBackup=false`).

## Data sharing

None. There are no third parties in this system unless you configure your own
relay to add them.

## Permissions

- **Microphone** — dictation to your AI sessions; active only during use.
- **Bluetooth** — headset microphone and buttons.
- **Notifications / Foreground service** — the always-visible status of the
  radio, required by Android for background microphone use.

## Contact

Questions: open an issue in the project repository.
