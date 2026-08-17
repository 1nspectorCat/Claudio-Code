#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Voice readback — global Claude Code Stop hook for Claudio Code.

Takes the last assistant message, strips markdown down to speech-friendly text,
synthesizes speech (edge-tts), converts it to OGG/Opus (ffmpeg) and pushes it to
YOUR relay, which hands it to the phone. Long answers are split into parts that
the radio plays in order.

Requirements: python 3, `pip install edge-tts`, ffmpeg and curl on PATH.

Config: ~/.claude/voice/bridge/config.json
  { "url": "https://<your-server>:8443",
    "token": "<the token printed by install-relay.sh>",
    "cacert": "cert.pem",          # copy it from the server, same folder
    "voice": "ru-RU-DmitryNeural" } # any edge-tts voice

Toggle: presence of ~/.claude/voice/ON   (delete the file = voice off)
Log:    ~/.claude/voice/readback.log     (every refusal is written here)
Defensive: any failure exits 0 so the session is never disrupted.

Hardening: single-instance LOCK + content DEDUP + per-run TEMP files + per-part
TIMEOUT + skip-empty — these fix duplicate and truncated voices that appear when
several Stop events overlap while edge-tts is slow.
"""
import os, sys, json, re, asyncio, subprocess, tempfile, time, hashlib

DIR = os.path.join(os.environ.get("USERPROFILE") or os.path.expanduser("~"), ".claude", "voice")
TOGGLE = os.path.join(DIR, "ON")
LOG = os.path.join(DIR, "readback.log")
LOCK = os.path.join(DIR, "readback.lock")
LASTSENT = os.path.join(DIR, "last_sent.txt")
DEFAULT_VOICE = "ru-RU-DmitryNeural"
# edge-tts times out often enough that one attempt loses parts of an answer, and a
# listener who gets part 2 without part 1 has no way to notice. Smaller chunks
# synthesize faster (fewer timeouts), the timeout is generous, retries are many.
MAX_CHARS = 1100          # per voice part
HARD_CAP = 12000          # absolute ceiling per answer
SYNTH_TIMEOUT = 60        # seconds per part — a hung edge-tts can't stall the whole readback
SYNTH_RETRIES = 4         # attempts per part, with a growing pause
LOCK_TTL = 240            # seconds — a lock fresher than this means another run is active

def log(m):
    try:
        from datetime import datetime
        with open(LOG, "a", encoding="utf-8") as f:
            f.write("[%s] %s\n" % (datetime.now().isoformat(timespec="seconds"), m))
    except Exception:
        pass

# ── single-instance lock: prevent overlapping runs racing on temp files ──
# Queue instead of skipping: with parallel sessions an instant skip silently dropped
# WHOLE answers ("skip: another readback active" in the log meant the user never heard
# that message at all). Wait up to LOCK_WAIT, then skip honestly and log it.
LOCK_WAIT = 150
def acquire_lock():
    try:
        waited = 0
        while os.path.exists(LOCK) and (time.time() - os.path.getmtime(LOCK)) < LOCK_TTL:
            if waited >= LOCK_WAIT:
                return False                  # waited long enough — honest skip, logged
            time.sleep(3); waited += 3
    except Exception:
        pass
    try:
        with open(LOCK, "w") as f:
            f.write(str(os.getpid()))
    except Exception:
        pass
    return True

def release_lock():
    try:
        os.remove(LOCK)
    except Exception:
        pass

# ── dedup: never voice the exact same message twice in a row (kills double-fire) ──
def is_duplicate(speech):
    h = hashlib.md5(speech.encode("utf-8", "ignore")).hexdigest()
    try:
        if os.path.exists(LASTSENT) and open(LASTSENT, encoding="utf-8").read().strip() == h:
            return True
    except Exception:
        pass
    try:
        open(LASTSENT, "w", encoding="utf-8").write(h)
    except Exception:
        pass
    return False

def to_speech(md):
    t = md
    t = re.sub(r"```[\s\S]*?```", " (code block — read it on screen) ", t)
    t = re.sub(r"^\s*\|.*\|\s*$", "", t, flags=re.M)
    t = re.sub(r"^\s*[-:|\s]+\s*$", "", t, flags=re.M)
    t = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", t)
    t = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", t)
    t = re.sub(r"`([^`]+)`", r"\1", t)
    t = re.sub(r"^#{1,6}\s*", "", t, flags=re.M)
    t = re.sub(r"\*\*([^*]+)\*\*", r"\1", t)
    t = re.sub(r"\*([^*]+)\*", r"\1", t)
    t = re.sub(r"^\s*[-*]\s+", "", t, flags=re.M)
    t = re.sub(r"^\s*\d+\.\s+", "", t, flags=re.M)
    t = re.sub(r"[#*_>`~]", "", t)
    t = re.sub(r"\n{3,}", "\n\n", t)
    t = re.sub(r"[ \t]{2,}", " ", t)
    return t.strip()

def chunk(s, size):
    if len(s) <= size:
        return [s]
    out, buf = [], ""
    for para in s.split("\n\n"):
        if len(buf) + len(para) + 2 > size and buf:
            out.append(buf.strip()); buf = ""
        if len(para) > size:
            for sent in re.split(r"(?<=[.!?])\s+", para):
                if len(buf) + len(sent) + 1 > size and buf:
                    out.append(buf.strip()); buf = ""
                buf += ((" " if buf else "") + sent)
        else:
            buf += (("\n\n" if buf else "") + para)
    if buf.strip():
        out.append(buf.strip())
    return out

async def synth(text, mp3_path, voice):
    import edge_tts
    await asyncio.wait_for(edge_tts.Communicate(text, voice=voice).save(mp3_path), timeout=SYNTH_TIMEOUT)


# ── the finished OGG goes to your relay, which hands it to the phone ──────────
BRIDGE_CFG = os.path.join(DIR, "bridge", "config.json")
def bridge_push(ogg, sid, proj, part_text, part_i, parts_n, msgid="", ctx=""):
    try:
        bc = json.load(open(BRIDGE_CFG, encoding="utf-8"))
        import base64, urllib.parse
        t = base64.urlsafe_b64encode(part_text.encode("utf-8")).decode("ascii")
        pj = base64.urlsafe_b64encode((proj or "").encode("utf-8")).decode("ascii")
        cx = base64.urlsafe_b64encode((ctx or "").encode("utf-8")).decode("ascii")
        base = bc.get("url") or ("http://127.0.0.1:%s" % bc.get("port", 8767))
        url = ("%s/hook?token=%s&session=%s&part=%d&parts=%d&projb=%s&text=%s&msgid=%s&ctx=%s"
               % (base, bc["token"], urllib.parse.quote(sid or ""),
                  part_i, parts_n, pj, t, urllib.parse.quote(str(msgid)), cx))
        cmd = ["curl", "-s", "-m", "5"]
        if bc.get("cacert"):
            cmd += ["--cacert", os.path.join(DIR, "bridge", bc["cacert"])]
        cmd += ["-X", "POST", "--data-binary", "@%s" % ogg, url]
        r = subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=8)
        if r.returncode != 0:
            # Silence here used to be the worst failure mode: the answer simply never
            # arrived and nothing said why. curl 60 = TLS check failed, which on a
            # self-signed relay means the cacert is missing or points at the wrong file.
            hint = ""
            if r.returncode == 60:
                hint = (" — TLS check failed: copy cert.pem from the server into "
                        "~/.claude/voice/bridge/ and set \"cacert\": \"cert.pem\"")
            elif r.returncode == 7 or r.returncode == 28:
                hint = " — relay unreachable: wrong url, or port closed in the server firewall"
            log("bridge push failed rc=%d%s | %s" % (r.returncode, hint,
                                                     (r.stderr or b"").decode("utf-8", "ignore").strip()[:160]))
            return False
        return True
    except Exception as e:
        log("bridge push fail: %s" % e)
    return False

def main():
    if not os.path.exists(TOGGLE):
        return
    if not os.path.exists(BRIDGE_CFG):
        log("no relay config: create %s (see README)" % BRIDGE_CFG); return
    try:
        cfg = json.load(open(BRIDGE_CFG, encoding="utf-8"))
    except Exception:
        log("bad relay config: %s is not valid JSON" % BRIDGE_CFG); return
    if not cfg.get("url") or not cfg.get("token"):
        log("relay config needs both \"url\" and \"token\""); return
    if not cfg.get("cacert"):
        log("warning: no \"cacert\" in relay config — a self-signed relay will reject the push")
    voice = cfg.get("voice") or DEFAULT_VOICE

    raw = sys.stdin.read()
    try:
        payload = json.loads(raw)
    except Exception:
        return
    tpath = payload.get("transcript_path")
    if not tpath or not os.path.exists(tpath):
        log("no transcript"); return
    sid = payload.get("session_id") or ""
    proj = os.path.basename(payload.get("cwd") or "") or ""

    # Channel name: if you gave the session a custom title in Claude Code, the radio should
    # call the channel exactly that — otherwise the names on the phone do not match what you
    # see on screen. Falls back to the working directory name. Never let this break readback.
    try:
        # customTitle is NOT necessarily on the first line of the transcript, so scan the
        # head of the file; a first-line-only check silently fell back to the folder name.
        _title = ""
        with open(tpath, encoding="utf-8") as _fh:
            for _i, _ln in enumerate(_fh):
                if _i > 80:
                    break
                try:
                    _o = json.loads(_ln)
                except Exception:
                    continue
                _t = (_o.get("customTitle") or "").strip()
                if _t:
                    _title = _t
                    break
        # After /clear, Claude Code starts a NEW transcript file that has no title yet — the
        # channel would suddenly rename itself to the folder name mid-conversation. Remember
        # the last known title per working directory.
        import re as _re2
        _slug = _re2.sub(r"[^A-Za-z0-9_-]", "-", payload.get("cwd") or "noproj")
        _nf = os.path.join(DIR, "bridge", "title_%s.txt" % _slug)
        if _title:
            proj = _title[:40]
            try:
                with open(_nf, "w", encoding="utf-8") as _w:
                    _w.write(proj)
            except Exception:
                pass
        else:
            try:
                with open(_nf, encoding="utf-8") as _r:
                    _prev = _r.read().strip()
                if _prev:
                    proj = _prev[:40]
            except Exception:
                pass
    except Exception:
        pass

    # A session id CHANGES when the context is compacted: the answer goes out under the new
    # id while the session's poller is still listening on the old one, and the user's replies
    # land nowhere. Keep a per-project history of ids — the poller re-reads the tail of this
    # file every few seconds and polls all recent ids, so the receiver follows the change.
    try:
        if sid:
            import re as _re
            slug = _re.sub(r"[^A-Za-z0-9_-]", "-", payload.get("cwd") or "noproj")
            sf = os.path.join(DIR, "bridge", "sids_%s.txt" % slug)
            tail = []
            try:
                tail = [l.strip() for l in open(sf, encoding="ascii").read().splitlines() if l.strip()]
            except Exception:
                pass
            if not tail or tail[-1] != sid:
                tail.append(sid)
                open(sf, "w", encoding="ascii").write("\n".join(tail[-5:]) + "\n")
    except Exception:
        pass

    text = None
    with open(tpath, encoding="utf-8") as f:
        lines = [ln for ln in f.read().split("\n") if ln.strip()]

    # Parse once so we can grab both the assistant reply AND the human message
    # it answered (cheap context line for the walkie-talkie — no LLM needed).
    objs = []
    for ln in lines:
        try:
            objs.append(json.loads(ln))
        except Exception:
            continue

    ai_idx = None
    for i in range(len(objs) - 1, -1, -1):
        obj = objs[i]
        m = obj.get("message", obj)
        if (m.get("role") or obj.get("type")) != "assistant":
            continue
        c = m.get("content")
        if isinstance(c, str) and c.strip():
            text = c; ai_idx = i; break
        if isinstance(c, list):
            parts = [b.get("text") for b in c if isinstance(b, dict) and b.get("type") == "text" and b.get("text")]
            if parts:
                text = "\n".join(parts); ai_idx = i; break

    # Context = the most recent HUMAN message before this reply. Skip tool
    # results and system-injected turns; keep only real user prose.
    ctx = ""
    if ai_idx is not None:
        for i in range(ai_idx - 1, -1, -1):
            obj = objs[i]
            m = obj.get("message", obj)
            if (m.get("role") or obj.get("type")) != "user":
                continue
            c = m.get("content")
            cand = None
            if isinstance(c, str):
                cand = c
            elif isinstance(c, list):
                # a genuine user turn has text blocks; tool_result turns don't
                if any(isinstance(b, dict) and b.get("type") == "tool_result" for b in c):
                    continue
                tb = [b.get("text") for b in c if isinstance(b, dict) and b.get("type") == "text" and b.get("text")]
                cand = "\n".join(tb) if tb else None
            if cand and cand.strip() and not cand.lstrip().startswith(("[SYSTEM", "<system")):
                cand = cand.lstrip()
                # a phone voice reply IS real user prose — keep the text, drop the tag
                if cand.startswith("[ГОЛОС С ТЕЛЕФОНА]"):
                    cand = cand[len("[ГОЛОС С ТЕЛЕФОНА]"):]
                ctx = " ".join(cand.split())[:160]
                if ctx:
                    break
    if not text or not text.strip():
        return

    speech = to_speech(text)[:HARD_CAP]
    if not speech.strip():
        return
    if is_duplicate(speech):
        log("skip duplicate"); return

    parts = chunk(speech, MAX_CHARS)
    msgid = str(int(time.time() * 1000))   # groups this reply's parts into one message
    sent = 0
    for part_i, part in enumerate(parts, 1):
        if not part.strip():
            continue                                   # never synth an empty chunk (0-byte mp3 → fail)
        mp3 = ogg = None
        try:
            fd, mp3 = tempfile.mkstemp(suffix=".mp3", dir=DIR); os.close(fd)
            fd, ogg = tempfile.mkstemp(suffix=".ogg", dir=DIR); os.close(fd)
            # Retry transient edge-tts/network failures: one blip used to lose a part forever.
            synth_ok = False
            for attempt in range(SYNTH_RETRIES):
                try:
                    asyncio.run(synth(part, mp3, voice))    # per-run temp files: no cross-run clobber
                    if os.path.exists(mp3) and os.path.getsize(mp3) > 0:
                        synth_ok = True; break
                except Exception as se:
                    log("synth attempt %d fail: %r" % (attempt + 1, se))
                time.sleep(1.5 * (attempt + 1))
            if not synth_ok:
                log("synth gave up after %d retries — part %d/%d lost (is edge-tts installed?)"
                    % (SYNTH_RETRIES, part_i, len(parts)))
                continue
            r = subprocess.run(
                ["ffmpeg", "-y", "-i", mp3, "-c:a", "libopus", "-b:a", "32k", ogg],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if r.returncode != 0 or not os.path.exists(ogg) or os.path.getsize(ogg) == 0:
                log("ffmpeg failed (rc=%d) — is ffmpeg on PATH?" % r.returncode)
                continue
            if bridge_push(ogg, sid, proj, part, part_i, len(parts), msgid, ctx if part_i == 1 else ""):
                sent += 1
        except Exception as e:
            log("part fail: %s" % e)
        finally:
            for p in (mp3, ogg):
                try:
                    if p and os.path.exists(p):
                        os.remove(p)
                except Exception:
                    pass
    log("sent %d/%d voice part(s), %d chars" % (sent, len(parts), len(speech)))

if __name__ == "__main__":
    locked = acquire_lock()
    if not locked:
        try: log("skip: another readback active")
        except Exception: pass
        sys.exit(0)
    try:
        main()
    except Exception as e:
        log("fatal: %s" % e)
    finally:
        release_lock()
    sys.exit(0)
