// Voice-bridge relay (Oracle edition): meeting point between Claude Code sessions
// (WS subscribers from the PC), the readback.py Stop hook (HTTP push of ready OGG
// parts) and the phone app (WS subscriber + HTTP fetch of audio + HTTP post of
// user replies). Single TLS port, token-gated, isolated from all money code.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const WebSocket = require('ws');

const DIR = __dirname;
const CFG = JSON.parse(fs.readFileSync(path.join(DIR, 'config.json'), 'utf8'));
const AUDIO_DIR = path.join(DIR, 'audio');
const UTT_DIR = path.join(DIR, 'utt');   // raw dictation segments awaiting whisper
const LOG = path.join(DIR, 'relay.log');
if (!fs.existsSync(AUDIO_DIR)) fs.mkdirSync(AUDIO_DIR, { recursive: true });
if (!fs.existsSync(UTT_DIR)) fs.mkdirSync(UTT_DIR, { recursive: true });

function log(m) {
  const line = `[${new Date().toISOString()}] ${m}\n`;
  try { fs.appendFileSync(LOG, line); } catch (_) {}
  process.stdout.write(line);
}

const sessions = new Map(); // sid -> Set<ws>
const phones = new Set();
const recent = [];
// v0.37: when did each session last drain its inbox — a session whose poller died
// silently swallows messages; the phone deserves to know its target is deaf.
// v0.39: lastConsume is in-memory — right after a relay restart EVERY session looks
// deaf for one poll cycle. Grace period: benefit of the doubt for the first 150s.
const BOOT_TS = Date.now();
const lastConsume = new Map(); // sid -> ts
const readerFresh = (sid) =>
  Date.now() - BOOT_TS < 150000 || Date.now() - (lastConsume.get(sid) || 0) < 130000;
// Inbound texts for sessions that poll over HTTPS instead of holding a WS
// (Claude Code's Monitor ws client rejects self-signed TLS; curl --cacert works).
const inbox = new Map(); // sid -> Array<{text, ts}>
// Per-session voice history so the phone can browse and replay past messages.
const history = new Map(); // sid -> Array<voice-event> (newest last, capped)
const HIST_CAP = 80;

function recordHistory(evt) {
  if (!history.has(evt.session)) history.set(evt.session, []);
  const h = history.get(evt.session);
  h.push(evt);
  while (h.length > HIST_CAP) h.shift();
}

function authOk(url) {
  return url.searchParams.get('token') === CFG.token;
}

function broadcastPhones(evt) {
  const msg = JSON.stringify(evt);
  recent.push(evt);
  while (recent.length > 30) recent.shift();
  let n = 0;
  for (const ws of phones) if (ws.readyState === 1) { ws.send(msg); n++; }
  return n;
}

function pruneAudio() {
  try {
    // Never delete a file still referenced by browsable history; the count is a
    // floor for unreferenced leftovers only.
    const referenced = new Set();
    for (const h of history.values())
      for (const e of h) if (e.audio) referenced.add(path.basename(e.audio));
    const files = fs.readdirSync(AUDIO_DIR)
      .map(f => ({ f, t: fs.statSync(path.join(AUDIO_DIR, f)).mtimeMs }))
      .sort((a, b) => b.t - a.t);
    for (const { f } of files.slice(CFG.keepAudio || 100))
      if (!referenced.has(f)) fs.unlinkSync(path.join(AUDIO_DIR, f));
  } catch (_) {}
}

function readBody(req, cb, limit = 15 * 1024 * 1024) {
  const chunks = [];
  let size = 0;
  req.on('data', c => { size += c.length; if (size > limit) { req.destroy(); } else chunks.push(c); });
  req.on('end', () => cb(Buffer.concat(chunks)));
}

function handler(req, res) {
  const url = new URL(req.url, 'http://x');
  const send = (code, obj) => {
    res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(obj));
  };
  try {
    if (!authOk(url)) return send(401, { error: 'bad token' });

    if (req.method === 'POST' && url.pathname === '/hook') {
      const sid = url.searchParams.get('session') || 'unknown';
      const part = parseInt(url.searchParams.get('part') || '1', 10);
      const parts = parseInt(url.searchParams.get('parts') || '1', 10);
      const b64 = (s) => Buffer.from((s || '').replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8');
      let proj = url.searchParams.get('proj') || '';
      try { const pb = url.searchParams.get('projb'); if (pb) proj = b64(pb); } catch (_) {}
      let text = '';
      try { text = b64(url.searchParams.get('text')); } catch (_) {}
      let ctx = '';
      try { ctx = b64(url.searchParams.get('ctx')); } catch (_) {}
      const msgid = url.searchParams.get('msgid') || String(Date.now());
      readBody(req, (buf) => {
        const id = `${Date.now()}-${crypto.randomBytes(4).toString('hex')}`;
        const fname = `${id}.ogg`;
        try { fs.writeFileSync(path.join(AUDIO_DIR, fname), buf); } catch (e) { return send(500, { error: String(e) }); }
        pruneAudio();
        const evt = {
          type: 'voice', session: sid, proj, part, parts, text, ctx, msgid,
          audio: `/audio/${fname}`, bytes: buf.length, ts: Date.now(),
        };
        recordHistory(evt);
        const delivered = broadcastPhones(evt);
        log(`hook: ${sid.slice(0, 8)} part ${part}/${parts} ${buf.length}b -> ${delivered} phone(s)`);
        send(200, { ok: true, delivered, audio: `/audio/${fname}` });
      });
      return;
    }

    if (req.method === 'POST' && url.pathname === '/say') {
      readBody(req, (buf) => {
        let body = {};
        try { body = JSON.parse(buf.toString('utf8')); } catch (_) { return send(400, { error: 'bad json' }); }
        const sid = body.session_id;
        const text = (body.text || '').trim();
        if (!sid || !text) return send(400, { error: 'need session_id and text' });
        if (!inbox.has(sid)) {
          if (inbox.size >= 100) return send(429, { error: 'too many queues' });
          inbox.set(sid, []);
        }
        const q = inbox.get(sid);
        q.push({ text: text.slice(0, 4000), ts: Date.now() });
        while (q.length > 50) q.shift();
        const subs = sessions.get(sid);
        let n = 0;
        if (subs) for (const ws of subs) if (ws.readyState === 1) { ws.send(`[ГОЛОС С ТЕЛЕФОНА] ${text}`); n++; }
        log(`say -> ${sid.slice(0, 8)}: ws ${n}, queued ${q.length}, ${text.slice(0, 60)}`);
        send(200, { ok: true, ws: n, queued: q.length, unread: !readerFresh(sid) });
      });
      return;
    }

    // Phone uploads one dictation segment (WAV). The whisper transcriber picks it
    // up from UTT_DIR; on final it calls /uttdone which routes text into the session.
    if (req.method === 'POST' && url.pathname === '/utt') {
      const sid = url.searchParams.get('session') || '';
      const utt = (url.searchParams.get('utt') || '').replace(/[^a-z0-9]/gi, '');
      const seq = parseInt(url.searchParams.get('seq') || '0', 10);
      const fin = url.searchParams.get('final') === '1';
      const cancel = url.searchParams.get('cancel') === '1';
      if (!utt) return send(400, { error: 'need utt' });
      readBody(req, (buf) => {
        try {
          if (cancel) {
            fs.writeFileSync(path.join(UTT_DIR, `${utt}.cancel`), '');
            log(`utt ${utt}: cancelled by phone`);
            return send(200, { ok: true });
          }
          fs.writeFileSync(path.join(UTT_DIR, `${utt}.meta.json`),
            JSON.stringify({ session: sid, ts: Date.now(), v: parseInt(url.searchParams.get('v') || '0', 10), nosil: url.searchParams.get('nosil') === '1' }));
          const name = `${utt}_${String(seq).padStart(3, '0')}${fin ? '_F' : ''}.wav`;
          if (buf.length) fs.writeFileSync(path.join(UTT_DIR, name), buf);
          else if (fin) fs.writeFileSync(path.join(UTT_DIR, name), Buffer.alloc(0));
          log(`utt ${utt} seg ${seq}${fin ? ' FINAL' : ''}: ${buf.length}b`);
          send(200, { ok: true });
        } catch (e) { send(500, { error: String(e) }); }
      });
      return;
    }

    // Transcriber reports a finished utterance: route text like /say + tell the phone.
    if (req.method === 'POST' && url.pathname === '/uttdone') {
      readBody(req, (buf) => {
        let body = {};
        try { body = JSON.parse(buf.toString('utf8')); } catch (_) { return send(400, { error: 'bad json' }); }
        const sid = body.session_id || '';
        const text = (body.text || '').trim();
        const empty = !!body.empty;
        const cancelled = !!body.cancelled;   // v0.36: voice-cancel detected by the transcriber
        // v0.54: команда рации («переключись на вторую», «какие сессии») — распознана
        // транскрайбером, в сессию не идёт, телефон получает её отдельным событием.
        const cmd = body.cmd || null;
        let queued = false;
        if (!cancelled && !empty && sid && text) {
          if (!inbox.has(sid)) {
            if (inbox.size < 100) inbox.set(sid, []);
          }
          const q = inbox.get(sid);
          if (q) { q.push({ text: text.slice(0, 4000), ts: Date.now() }); while (q.length > 50) q.shift(); queued = true; }
          const subs = sessions.get(sid);
          let n = 0;
          if (subs) for (const ws of subs) if (ws.readyState === 1) { ws.send(`[ГОЛОС С ТЕЛЕФОНА] ${text}`); n++; }
          log(`uttdone -> ${sid.slice(0, 8)}: ws ${n}, queued ${queued}, ${text.slice(0, 60)}`);
        }
        // v0.36: honest event types. utt_lost = text existed but was NOT routed anywhere
        // (no session id, or the inbox map is full) — the phone used to hear a confident
        // "ушло" while the text silently died here.
        const type = cmd ? 'utt_cmd'
          : cancelled ? 'utt_cancelled'
          : empty ? 'utt_empty'
          : (queued ? 'utt_sent' : 'utt_lost');
        if (type === 'utt_lost') log(`uttdone LOST (no session): ${text.slice(0, 60)}`);
        if (cmd) log(`uttdone CMD -> phone: ${JSON.stringify(cmd)}`);
        broadcastPhones({
          type, utt: body.utt || '', cmd,
          session: sid, text: text.slice(0, 1000), ts: Date.now(),
          // v0.37: «ушло» в очередь, которую никто не читает — телефон предупредит юзера
          unread: type === 'utt_sent' && !readerFresh(sid),
        });
        send(200, { ok: true });
      });
      return;
    }

    // v0.60: транскрайбер предупреждает телефон, что «смысловая тишина» набирается —
    // юзеру нужен предупредительный бип ДО отправки, а не факт после неё.
    if (req.method === 'POST' && url.pathname === '/uttwarn') {
      readBody(req, (buf) => {
        let body = {};
        try { body = JSON.parse(buf.toString('utf8')); } catch (_) {}
        broadcastPhones({ type: 'utt_warn', utt: body.utt || '', session: body.session_id || '', ts: Date.now() });
        send(200, { ok: true });
      });
      return;
    }

    // Sessions the relay has seen recently: {sid, proj, lastTs, count}.
    if (req.method === 'GET' && url.pathname === '/sessions') {
      const out = [];
      for (const [sid, h] of history) {
        if (!h.length) continue;
        const last = h[h.length - 1];
        out.push({ sid, proj: last.proj, lastTs: last.ts, count: h.length, alive: readerFresh(sid) });
      }
      out.sort((a, b) => b.lastTs - a.lastTs);
      return send(200, { ok: true, sessions: out });
    }

    // Recent messages of one session (newest last), for browsing/replay.
    if (req.method === 'GET' && url.pathname === '/history') {
      const sid = url.searchParams.get('session');
      if (!sid) return send(400, { error: 'need session' });
      const limit = Math.min(parseInt(url.searchParams.get('limit') || '40', 10), HIST_CAP);
      const h = history.get(sid) || [];
      return send(200, { ok: true, events: h.slice(-limit) });
    }

    if (req.method === 'GET' && url.pathname.startsWith('/audio/')) {
      const f = path.join(AUDIO_DIR, path.basename(url.pathname));
      if (!fs.existsSync(f)) return send(404, { error: 'gone' });
      res.writeHead(200, { 'Content-Type': 'audio/ogg' });
      fs.createReadStream(f).pipe(res);
      return;
    }

    // Session-side inbound: drain queued phone texts as plain lines (one event per line).
    // v0.36: drain optimistically, restore on abort. Honest coverage note: this catches
    // "client already gone before the write" (writableFinished=false); a small body that
    // reached the kernel buffer but died on the wire still counts as delivered — full
    // at-least-once would need an ack cursor. Chosen tradeoff: rare dupes < silent loss.
    if (req.method === 'GET' && url.pathname === '/consume') {
      const sid = url.searchParams.get('session');
      if (!sid) return send(400, { error: 'need session' });
      lastConsume.set(sid, Date.now());
      const q = inbox.get(sid) || [];
      inbox.delete(sid);
      if (q.length) {
        res.on('close', () => {
          if (!res.writableFinished) {
            const cur = inbox.get(sid) || [];
            inbox.set(sid, q.concat(cur).slice(-50));
            log(`consume ${sid.slice(0, 8)}: aborted, restored ${q.length}`);
          }
        });
      }
      res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end(q.map(m => `[ГОЛОС С ТЕЛЕФОНА] ${m.text.replace(/\s*\n\s*/g, ' / ')}`).join('\n'));
      if (q.length) log(`consume ${sid.slice(0, 8)}: drained ${q.length}`);
      return;
    }

    // v0.40: how long ago did this session last drain its inbox — the poller_guard
    // Stop-hook uses it to detect a dead receiver and force the session to re-arm.
    if (req.method === 'GET' && url.pathname === '/readerage') {
      const sid = url.searchParams.get('session');
      if (!sid) return send(400, { error: 'need session' });
      const t = lastConsume.get(sid) || 0;
      return send(200, {
        ok: true,
        age: t ? Math.round((Date.now() - t) / 1000) : null,
        uptime: Math.round((Date.now() - BOOT_TS) / 1000),
      });
    }

    if (req.method === 'GET' && url.pathname === '/status') {
      return send(200, {
        ok: true,
        sessions: [...sessions.entries()].map(([sid, set]) => ({ sid, subs: set.size })),
        phones: phones.size,
        recent: recent.slice(-5).map(e => ({ session: e.session, proj: e.proj, part: e.part, parts: e.parts, ts: e.ts })),
      });
    }

    send(404, { error: 'unknown route' });
  } catch (e) {
    log(`http error: ${e}`);
    try { send(500, { error: 'internal' }); } catch (_) {}
  }
}

let server;
if (CFG.tlsKey && CFG.tlsCert) {
  server = require('https').createServer({
    key: fs.readFileSync(path.join(DIR, CFG.tlsKey)),
    cert: fs.readFileSync(path.join(DIR, CFG.tlsCert)),
  }, handler);
} else {
  server = require('http').createServer(handler);
}

// Token gate BEFORE the websocket handshake: bad token gets HTTP 401, not a
// successful 101-then-close (which would reset the client's reconnect backoff).
const wss = new WebSocket.Server({
  server,
  verifyClient: (info, cb) => {
    try {
      const url = new URL(info.req.url, 'http://x');
      if (!authOk(url)) return cb(false, 401, 'bad token');
      cb(true);
    } catch (_) { cb(false, 400, 'bad request'); }
  },
});
wss.on('connection', (ws, req) => {
  const url = new URL(req.url, 'http://x');
  if (!authOk(url)) { ws.close(4001, 'bad token'); return; }
  const role = url.searchParams.get('role');
  if (role === 'session') {
    const sid = url.searchParams.get('session');
    if (!sid) { ws.close(4002, 'need session'); return; }
    if (!sessions.has(sid)) sessions.set(sid, new Set());
    sessions.get(sid).add(ws);
    log(`session connected: ${sid.slice(0, 8)} (subs ${sessions.get(sid).size})`);
    ws.on('close', () => {
      const set = sessions.get(sid);
      if (set) { set.delete(ws); if (!set.size) sessions.delete(sid); }
      log(`session disconnected: ${sid.slice(0, 8)}`);
    });
  } else if (role === 'phone') {
    phones.add(ws);
    log(`phone connected (${phones.size})`);
    // v0.36: server clock in hello — the phone computes the 30-min catchup window
    // against server timestamps, so skewed phone clocks no longer break catchup
    try { ws.send(JSON.stringify({ type: 'hello', now: Date.now(), recent: recent.slice(-10) })); } catch (_) {}
    ws.on('close', () => { phones.delete(ws); log('phone disconnected'); });
  } else {
    ws.close(4003, 'bad role');
  }
  ws.on('error', () => {});
});

// Hourly hygiene: drop empty or stale (>24h) inbox queues.
// v0.36: also sweep UTT_DIR — if voice-whisper dies, raw voice recordings used to
// pile up unbounded (disk + privacy). Anything older than 1h is garbage.
setInterval(() => {
  try {
    const cutoff = Date.now() - 24 * 3600 * 1000;
    for (const [k, q] of inbox) {
      if (!q.length || q[q.length - 1].ts < cutoff) inbox.delete(k);
    }
    for (const [k, h] of history) {
      if (!h.length || h[h.length - 1].ts < cutoff) history.delete(k);
    }
  } catch (_) {}
  try {
    const uttCutoff = Date.now() - 3600 * 1000;
    for (const f of fs.readdirSync(UTT_DIR)) {
      const p = path.join(UTT_DIR, f);
      try { if (fs.statSync(p).mtimeMs < uttCutoff) fs.unlinkSync(p); } catch (_) {}
    }
  } catch (_) {}
}, 3600 * 1000);

server.listen(CFG.port, '0.0.0.0', () => log(`voice-bridge relay up on :${CFG.port} (tls=${!!CFG.tlsKey})`));
