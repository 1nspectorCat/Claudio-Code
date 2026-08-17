#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Voice readback (audio) — global Claude Code Stop hook.
Takes the last assistant message, strips markdown to speech-friendly text,
synthesizes Russian speech (edge-tts), converts to OGG/Opus (ffmpeg) and sends
it as a Telegram VOICE message. Long answers are split into several voice
messages that Telegram autoplays in order.

Gating: runs only if toggle file ON exists AND config.json present.
Config: ~/.claude/voice\\config.json = {"botToken","chatId","voice"?}
Toggle: presence of ~/.claude/voice\\ON   ("voice off" = delete it)
Defensive: any failure exits 0 so the session is never disrupted.

Hardening (15.06): single-instance LOCK + content DEDUP + per-run TEMP files +
per-part TIMEOUT + skip-empty. Fixes duplicate / truncated voices that appeared
when many Stop events overlapped while edge-tts was slow and clobbered the shared
out.mp3/out.ogg scratch files.
"""
import os, sys, json, re, asyncio, subprocess, tempfile, time, hashlib

DIR = os.path.join(os.environ.get("USERPROFILE") or os.path.expanduser("~"), ".claude", "voice")
TOGGLE = os.path.join(DIR, "ON")
CONFIG = os.path.join(DIR, "config.json")
LOG = os.path.join(DIR, "readback.log")
LOCK = os.path.join(DIR, "readback.lock")
LASTSENT = os.path.join(DIR, "last_sent.txt")
DEFAULT_VOICE = "ru-RU-DmitryNeural"
# (12.07) edge-tts стал регулярно таймаутить (TimeoutError бросками весь день, части терялись:
# «sent 1/2» — юзер получал хвост без начала). Куски меньше (синтез быстрее → меньше таймаутов),
# таймаут щедрее, попыток больше; при полном провале синтеза часть уходит ТЕКСТОМ (см. send_text_fallback).
MAX_CHARS = 1100          # per voice message (было 1600)
HARD_CAP = 12000          # absolute ceiling per answer
SYNTH_TIMEOUT = 60        # seconds per part (было 30) — a hung edge-tts can't stall the whole readback
SYNTH_RETRIES = 4         # попыток синтеза на часть (было 3), пауза нарастает
LOCK_TTL = 240            # seconds — a lock fresher than this means another run is active (учтён рост таймаута)

def log(m):
    try:
        from datetime import datetime
        with open(LOG, "a", encoding="utf-8") as f:
            f.write("[%s] %s\n" % (datetime.now().isoformat(timespec="seconds"), m))
    except Exception:
        pass

# ── single-instance lock: prevent overlapping runs racing on temp files ──
# (12.07 v2) НЕ скипаем при занятом локе, а ЖДЁМ очередь: мгновенный скип молча терял
# ЦЕЛЫЕ ответы при параллельных сессиях (лог 11:27/11:34/12:42 «skip: another readback
# active» = юзер не получил те сообщения вообще). Ждём до LOCK_WAIT, потом всё равно скип.
LOCK_WAIT = 150
def acquire_lock():
    try:
        waited = 0
        while os.path.exists(LOCK) and (time.time() - os.path.getmtime(LOCK)) < LOCK_TTL:
            if waited >= LOCK_WAIT:
                return False                  # очередь не дождалась — честный скип (залогируется)
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
    t = re.sub(r"```[\s\S]*?```", " (фрагмент кода — смотри в тексте) ", t)
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

# (12.07) СТРАХОВКА СОДЕРЖАНИЯ: голос не синтезировался/не сконвертировался → шлём часть ТЕКСТОМ
# в тот же чат. Хуже голоса, но юзер на ходу больше не теряет куски ответа молча.
def send_text_fallback(token, chat, part):
    try:
        r = subprocess.run(
            ["curl", "-s", "-F", "chat_id=%s" % chat,
             "-F", "text=🔇 (голос не синтезировался — текстом)\n\n%s" % part[:3900],
             "https://api.telegram.org/bot%s/sendMessage" % token],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return r.returncode == 0
    except Exception as e:
        log("text fallback fail: %s" % e)
        return False

# ── (16.07) voice-bridge: копия готового OGG уходит в локальный релей для телефона-рации.
# Полностью независим от телеграм-пути: нет config/релей лежит — молча пропускаем.
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
        subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=8)
    except Exception as e:
        log("bridge push fail: %s" % e)

def main():
    if not os.path.exists(TOGGLE):
        return
    if not os.path.exists(CONFIG):
        log("no config"); return
    try:
        cfg = json.load(open(CONFIG, encoding="utf-8"))
    except Exception:
        log("bad config"); return
    token, chat = cfg.get("botToken"), cfg.get("chatId")
    voice = cfg.get("voice") or DEFAULT_VOICE
    if not token or not chat:
        log("config missing token/chat"); return

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

    # (15.08, слово юзера: «названия сессий в рации не совпадают с тем, что я вижу в Claude
    # Code, и путаются»). Заголовок, который юзер задал сессии сам, лежит первой строкой
    # транскрипта (customTitle). Есть он — рация называет канал ИМЕННО ТАК; нет — как было,
    # именем рабочей папки. Ошибка чтения не должна ронять озвучку.
    try:
        # (17.08 v2, боевое: сессия называется «мета судья», а рация говорила «брейншторм»)
        # customTitle лежит НЕ ОБЯЗАТЕЛЬНО в первой строке транскрипта — сканируем начало
        # файла, иначе откатывались на имя рабочей папки.
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
        # (17.08, боевое: после /clear Claude Code заводит НОВЫЙ файл разговора, заголовка в
        # нём ещё нет — и канал внезапно переименовывался в имя рабочей папки («мета судья»
        # превращалась в «финсоветник»). Помним последнее известное имя для этой папки.
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

    # v0.40c (боевое «сессия не слушает НА СВОЮ ЖЕ сессию»): id сессии МЕНЯЕТСЯ при сжатии
    # контекста — озвучка уходит под новым id, а Monitor-поллер сессии слушает старый.
    # Ведём историю id беседы per-проект: поллер (скилл voice-bridge) каждые 3с перечитывает
    # хвост этого файла и опрашивает все свежие id — приёмник сам следует за сменой.
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
    url = "https://api.telegram.org/bot%s/sendVoice" % token
    msgid = str(int(time.time() * 1000))   # groups this reply's parts into one message
    sent = 0
    for part_i, part in enumerate(parts, 1):
        if not part.strip():
            continue                                   # never synth an empty chunk (0-byte mp3 → fail)
        mp3 = ogg = None
        try:
            fd, mp3 = tempfile.mkstemp(suffix=".mp3", dir=DIR); os.close(fd)
            fd, ogg = tempfile.mkstemp(suffix=".ogg", dir=DIR); os.close(fd)
            # retry transient edge-tts/network failures (18.06): раньше один блик/таймаут терял часть
            # навсегда. (12.07) попыток больше, пауза нарастает; полный провал → ТЕКСТ-фолбэк, не потеря.
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
                log("synth gave up after retries → text fallback")
                if send_text_fallback(token, chat, part):
                    sent += 1
                continue
            r = subprocess.run(
                ["ffmpeg", "-y", "-i", mp3, "-c:a", "libopus", "-b:a", "32k", ogg],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if r.returncode != 0 or not os.path.exists(ogg) or os.path.getsize(ogg) == 0:
                log("ffmpeg fail → text fallback")
                if send_text_fallback(token, chat, part):
                    sent += 1
                continue
            bridge_push(ogg, sid, proj, part, part_i, len(parts), msgid, ctx if part_i == 1 else "")   # рация: копия в релей
            # (18.07, слово юзера — приватность) файл NO_TG выключает телеграм-дубль: звук ТОЛЬКО через
            # рацию, озвучки не попадают на серверы Телеграма. Вернуть дубль = удалить файл NO_TG.
            if os.path.exists(os.path.join(DIR, "NO_TG")):
                sent += 1
                continue
            # (12.07 v2) curl возвращает 0 даже когда Telegram отвечает отказом (429 флуд-лимит
            # на пачках из 4-6 частей) — раньше это логировалось как «sent». Читаем ответ API,
            # на 429 ждём retry_after и повторяем; полный провал → текст-фолбэк, не потеря.
            ok_send = False
            for _ in range(2):
                r2 = subprocess.run(
                    ["curl", "-s", "-F", "chat_id=%s" % chat, "-F", "voice=@%s" % ogg, url],
                    capture_output=True, text=True)
                try:
                    resp = json.loads(r2.stdout or "{}")
                except Exception:
                    resp = {}
                if resp.get("ok"):
                    ok_send = True; break
                wait_s = 3
                try:
                    wait_s = int(resp.get("parameters", {}).get("retry_after", 2)) + 1
                except Exception:
                    pass
                log("sendVoice not ok (err=%s) → retry in %ds" % (resp.get("error_code"), wait_s))
                time.sleep(wait_s)
            if ok_send:
                sent += 1
            elif send_text_fallback(token, chat, part):
                sent += 1
            time.sleep(1.1)                       # pacing: многочастный ответ не долбит флуд-лимит
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
