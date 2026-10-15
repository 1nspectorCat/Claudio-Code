# Claudio Code — how to use it

<!-- DRAFT for the public repo (store build, English). Button and setting names must match
     the English strings in MainActivity once the store build is checked on a phone. -->

Claudio Code is a walkie-talkie between your phone and Claude Code sessions on your computer.
You hear the replies in your earphones and answer by voice; the screen is only for when you
want to step in by hand.

## Main screen

**Chat feed.** At the top is the conversation of the current channel: channel replies on the
left, your messages on the right marked "sent". Bubbles are folded to three lines.
- tap a bubble — expand or fold it;
- the ▶ icon on a reply — listen to it;
- long press a reply — play from that point on;
- the arrows in the header (⤢) — expand or fold the whole conversation at once.

The conversation is also kept on the phone, so it survives a relay restart (the relay keeps
history only in memory).

**The circle is both the talk button and the status.** Its colour and label tell you what is
going on:
- "online" — tap to open the microphone;
- "listening" — speak; a tap sends what you said;
- "playing" — a reply is playing; tap to pause, tap again to resume from the same spot;
- "reply · name" — a gap between two channels; a tap answers the one that just finished;
- "sent", "mic asleep", "offline", "waiting for headphones" — what they say;
- LONG press on the circle — turn the whole radio on or off.

Below the circle: a hint (LONG press it to switch the send mode — on silence or only by the
word), a "✕ cancel" button while you dictate and "🎙 interrupt and speak" while a reply plays.

**Dock at the bottom:** pause/resume (when idle — replay the last reply), channels, log.

## Channels

The "channels" button opens the list. Each channel has:
- **"active"** — whom you hear. Tick as many as you like: you hear exactly the ticked ones,
  everything else stays silent, including new sessions. Nothing ticked — you hear everyone.
- **"reply"** — talk to this channel specifically. Works for one message, then the usual rule
  applies again.
- **long press on a row** — remove the channel from the list (it comes back when it really
  replies). A removed channel stops being the recipient and is not shown in the header after
  a restart.
- Status in words: "working · N min" (you asked, no reply yet), "replied · N min ago",
  "not listening" (the session's receiver died — messages wait in the queue), "off".
- The top line says which channels you are working with right now.

Three buttons above the list:
- **"✎ edit"** — tick mode: you see every channel the relay knows, including removed ones.
  Tick the ones to keep — you can bring back or remove several at once.
- **"↻ refresh"** — re-read the list from the relay.
- **"✕ close"** — close the panel.

**A session after context compaction.** When Claude Code compacts the context it gives the
session a new id, and for the radio that is a new channel. If the old channel with the same
name was active and has been silent for five minutes, the radio moves your choice to the
continuation and says "channel such-and-such continues".

**Recipient rule:** your answer always goes to whoever spoke last. Nothing needs pinning.
Exceptions: a fresh manual choice with the "reply" button, single-channel mode, and whatever
you have already started dictating.

## Voice

- **"send"** — send what you dictated (it must be the last word; "send please" and
  "send now" work too).
- **"cancel"** — said on its own: erase the draft.

<!-- TODO before publishing: the switchboard commands ("which sessions", "switch to <name>",
     "switch to the second", "unpin", "over and out" = erase draft + put the mic to sleep) are
     recognised on the relay (server side) and are Russian-only today. Either add English
     phrases to the relay or drop this list from the public guide. -->

If you have nothing to say, just stay quiet: after 70 seconds without a word the microphone
goes to sleep by itself.

## System phrases and replies

A system phrase ("sent to such-and-such", "channel is not listening") never plays over a
reply. If it arrives while a reply is playing, the reply freezes, the phrase plays alone, and
the reply continues from the same spot. The send confirmation names the channel the message
went to.

## Gap between channels

When two channels reply back to back, after the first one the radio says "reply to X, next,
Y" and gives you 12 seconds. Start talking — the queue waits until you finish. Send — the next
reply plays right away. Stay silent — it moves on by itself.

## Settings

A few plain-language switches: headphones only, name the channel before a reply, voice cues,
send on silence. Below them, "advanced" (relay address, whisper, hands-free mode, recording
source, catch-up) and "debug" (log).

First start: three steps and a setup link — the install script on your server prints a QR
code, the phone scans it, and the config sets itself up.

## If something is wrong

- **The radio is silent after a tap.** After 2.5 seconds it says the reason aloud ("put on
  your headphones", "can't play") and writes it to the log.
- **"Channel is not listening".** The session's receiver on your computer died; messages are
  not lost, they wait in the queue and arrive when it comes back.
- **Offline.** Replies pile up on the relay and arrive when you reconnect — the radio says
  "replies waiting".
- **Log** (bottom right button) — everything is there: which phrases played, where a message
  went, why sound did not start.
