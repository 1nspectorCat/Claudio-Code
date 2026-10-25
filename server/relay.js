// Voice-bridge relay (server edition): meeting point between Claude Code sessions
// (WS subscribers from the PC), the readback.py Stop hook (HTTP push of ready OGG
// parts) and the phone app (WS subscriber + HTTP fetch of audio + HTTP post of
// user replies). Single TLS port, token-gated.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const WebSocket = require('ws');

const DIR = __dirname;
const CFG = JSON.parse(fs.readFileSync(path.join(DIR, 'config.json'), 'utf8'));
const AUDIO_DIR = path.join(DIR, 'audio');
const UTT_DIR = path.join(DIR, 'utt');   // raw dictation segments awaiting whisper
const LOG = path.join(DIR, 'relay.log');
// Optional parts, off on a fresh install:
//  - HUB: sandboxed-helper endpoints, enabled by configured hub tokens.
//  - CODEX: durable managed-channel queues (/channel/*), on when HUB is on, or
//    config.json has "codexChannels": true, or the state file already exists.
const hubTokens = {};
for (const name of ['server', 'laptop']) {
  if (!Object.prototype.hasOwnProperty.call(CFG.hubTokens || {}, name)) continue;
  const token = CFG.hubTokens[name];
  // A typo here must not silently move a hub to the legacy token or switch it off.
  if (typeof token !== 'string' || !token)
    throw new Error(`config: hubTokens.${name} must be a non-empty string (remove the key to disable that hub)`);
  hubTokens[name] = token;
}
if (!Object.prototype.hasOwnProperty.call(CFG.hubTokens || {}, 'server') && CFG.hubToken)
  hubTokens.server = CFG.hubToken;
if (hubTokens.server && hubTokens.server === hubTokens.laptop)
  throw new Error('config: hubTokens.server and hubTokens.laptop must differ');
if (Object.values(hubTokens).includes(CFG.token))
  throw new Error('config: a hub token must differ from the app token');
const HUB = Object.keys(hubTokens).length > 0;
function ownerOfSid(sid) {
  if (typeof sid !== 'string') return null;
  // Without a laptop hub every hub-* channel stays the server's, as before two hubs.
  // "hub-l-" with the dash: server channels named by hand (hub-lab, hub-landing) stay the server's.
  if (hubTokens.laptop && (/^hub-l-[A-Za-z0-9_-]{1,62}$/.test(sid) || sid === 'hub-control-laptop')) return 'laptop';
  return /^hub-[A-Za-z0-9_-]{1,64}$/.test(sid) ? 'server' : null;
}
function tokenHub(token) {
  return Object.keys(hubTokens).find(name => hubTokens[name] === token) || null;
}
function validHub(name) { return name === 'server' || name === 'laptop'; }
if (!fs.existsSync(AUDIO_DIR)) fs.mkdirSync(AUDIO_DIR, { recursive: true });
if (!fs.existsSync(UTT_DIR)) fs.mkdirSync(UTT_DIR, { recursive: true });

function log(m) {
  const line = `[${new Date().toISOString()}] ${m}\n`;
  try { fs.appendFileSync(LOG, line); } catch (_) {}
  process.stdout.write(line);
}

// Optional presence marker: voice input from the phone proves the owner is around.
// Written only when env OWNER_SEEN_FILE is set; never allowed to crash the relay.
const OWNER_ALIVE_FILE = process.env.OWNER_SEEN_FILE || '';
function markOwnerAlive() {
  if (!OWNER_ALIVE_FILE) return;
  try { fs.writeFileSync(OWNER_ALIVE_FILE, JSON.stringify({ ts: Math.floor(Date.now() / 1000), source: 'voice' })); } catch (_) {}
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
const managedChannels = new Map(); // managed radio channels only (durable queues)
const history = new Map(); // sid -> Array<voice-event> (newest last, capped)
const HIST_CAP = 80;
const MSG_MAX = 100000; // per queued message; was a silent 4000-char cut


// Managed channels use persistent relay queues. Config/auth remain separate.
const QUEUE_STATE = path.join(DIR, 'radio-state.json');
const CODEX = HUB || CFG.codexChannels === true || fs.existsSync(QUEUE_STATE);
function persistRadio() {
  try {
    const data = JSON.stringify({ inbox: [...inbox].filter(([sid]) => managedChannels.has(sid)), channels: [...managedChannels] });
    fs.writeFileSync(QUEUE_STATE + '.tmp', data, { mode: 0o600 });
    fs.renameSync(QUEUE_STATE + '.tmp', QUEUE_STATE);
  } catch (_) { return false; }
  return true;
}
try {
  if (CODEX && fs.existsSync(QUEUE_STATE)) {
    const saved = JSON.parse(fs.readFileSync(QUEUE_STATE, 'utf8'));
    for (const [sid, q] of (saved.inbox || [])) {
      if (typeof sid === 'string' && Array.isArray(q)) inbox.set(sid, q.slice(-50));
    }
    for (const [sid, channel] of (saved.channels || [])) managedChannels.set(sid, channel);
  }
} catch (_) { log('radio state unreadable; refusing to discard queued messages'); process.exit(1); }

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

const hubSids = new Map(); // sid -> last use; at most 20 per owning hub within a day
const hubStates = new Map(); // hub name -> {body, ts}
// Hub announcements are sent once, while relay history is volatile. Discover
// channels from the periodically published inventory even after a relay restart.
function hubInventory() {
  const result = new Map();
  for (const [hubName, hubState] of hubStates) {
    let state;
    try { state = JSON.parse(hubState.body); } catch (_) { continue; }
    if (!state || !Array.isArray(state.channels)) continue;
    for (const ch of state.channels.slice(0, 20)) {
      if (!ch || ownerOfSid(ch.id) !== hubName || typeof ch.name !== 'string') continue;
      const name = ch.name.replace(/[\x00-\x1f\x7f]/g, '').trim().slice(0, 120);
      if (!name) continue;
      result.set(ch.id, { proj: `hub: ${name}`, lastTs: Number.isFinite(ch.last_activity) ? ch.last_activity * 1000 : hubState.ts,
        alive: Date.now() - hubState.ts < 150000 && Date.now() - (lastConsume.get(ch.id) || 0) < 130000 });
    }
  }
  return result;
}
// Secrets for the hub: phone -> relay -> hub, once. Kept in memory
// for at most 10 minutes, never logged, handed out to the hub key exactly once.
const hubSecrets = new Map(); // hub:req -> {hub, body, ts}
// Files for hub channels: phone -> relay disk -> hub, once. 20 MB each, 100 MB total, 30 min.
const HUBFILE_DIR = path.join(DIR, 'hubfiles');
if (HUB && !fs.existsSync(HUBFILE_DIR)) fs.mkdirSync(HUBFILE_DIR, { recursive: true, mode: 0o700 });
const hubFiles = new Map(); // id -> {session, name, size, ts}
function pruneHubFiles() {
  const now = Date.now();
  for (const [k, v] of hubFiles) if (now - v.ts > 1800000) {
    try { fs.unlinkSync(path.join(HUBFILE_DIR, k)); } catch (_) {}
    hubFiles.delete(k);
  }
}
function pruneHubSecrets() {
  const now = Date.now();
  for (const [k, v] of hubSecrets) if (now - v.ts > 600000) hubSecrets.delete(k);
}

function handler(req, res) {
  const send = (code, obj) => {
    res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(obj));
  };
  let url;
  try { url = new URL(req.url, 'http://x'); } catch (_) { return send(400, { error: 'bad url' }); }
  try {
    // Narrow second key for the sandboxed hub: it may only drain and speak
    // its own "hub-" channels, so it can never take a live session's queue.
    let isHub = false;
    const hubName = tokenHub(url.searchParams.get('token'));
    if (hubName) {
      // The hub publishes a state summary for the phone's status screen.
      if (req.method === 'GET' && (url.pathname === '/hubfile' || url.pathname === '/hubfile-fetch')) {
        pruneHubFiles();
        const fid = url.searchParams.get('id');
        if (!fid) return send(200, { ok: true, files: [...hubFiles.entries()].filter(([, v]) => ownerOfSid(v.session) === hubName).map(([id, v]) => ({ id, ...v })) });
        const meta = hubFiles.get(fid);
        if (!meta) return send(404, { error: 'no such file' });
        if (ownerOfSid(meta.session) !== hubName) return send(403, { error: 'file belongs to another hub' });
        const fp = path.join(HUBFILE_DIR, fid);
        let buf;
        try { buf = fs.readFileSync(fp); } catch (e) { return send(500, { error: 'read failed' }); }
        try { fs.unlinkSync(fp); } catch (_) {}
        hubFiles.delete(fid);
        res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': buf.length });
        return res.end(buf);
      }
      if (req.method === 'GET' && url.pathname === '/hubsecret') {
        pruneHubSecrets();
        const items = [];
        for (const [key, value] of hubSecrets) if (value.hub === hubName) {
          items.push(value.body);
          hubSecrets.delete(key);
        }
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        return res.end('[' + items.join(',') + ']');
      }
      if (req.method === 'POST' && url.pathname === '/hubstate') {
        return readBody(req, (buf) => {
          let state;
          try { state = JSON.parse(buf.toString('utf8')); } catch (_) { return send(400, { error: 'json body' }); }
          // the phone takes every listed channel as this hub's: drop ids the relay routes to the other hub
          if (state && Array.isArray(state.channels))
            state.channels = state.channels.filter(ch => ch && ownerOfSid(ch.id) === hubName);
          hubStates.set(hubName, { body: JSON.stringify(state), ts: Date.now() });
          send(200, { ok: true });
        }, 64 * 1024);
      }
      const hubSid = url.searchParams.get('session') || '';
      const hubOp = (req.method === 'POST' && url.pathname === '/hook') ||
                    (req.method === 'GET' && url.pathname === '/consume');
      if (!hubOp || ownerOfSid(hubSid) !== hubName)
        return send(403, { error: 'hub token: only /consume and /hook on hub- channels' });
      const nowTs = Date.now();
      for (const [k, t] of hubSids) if (nowTs - t > 86400000) hubSids.delete(k);
      if (!hubSids.has(hubSid) && [...hubSids.keys()].filter(sid => ownerOfSid(sid) === hubName).length >= 20) return send(429, { error: 'hub token: too many channels' });
      hubSids.set(hubSid, nowTs);
      isHub = true;
    } else if (!authOk(url)) return send(401, { error: 'bad token' });


    // Managed-channel adapters use durable IDs and explicit ACK, leaving plain
    // /consume unchanged. These endpoints require the normal relay token.
    if (CODEX && url.pathname.startsWith('/channel/')) {
      if (isHub) return send(403, { error: 'normal relay authentication required' });
      if (req.method === 'GET' && url.pathname === '/channel/inbox') {
        const sid = url.searchParams.get('session') || '';
        if (!managedChannels.has(sid)) return send(404, { error: 'channel not registered' });
        lastConsume.set(sid, Date.now());
        const q = inbox.get(sid) || [];
        for (const m of q) if (!m.id) m.id = crypto.randomUUID();
        if (!persistRadio()) return send(503, { error: 'message IDs not saved' });
        return send(200, { ok: true, messages: q });
      }
      if (req.method !== 'POST') return send(405, { error: 'method' });
      return readBody(req, (buf) => {
        let body;
        try { body = JSON.parse(buf.toString('utf8')); } catch (_) { return send(400, { error: 'json body' }); }
        const sid = body.session;
        if (typeof sid !== 'string' || !/^(codex-[A-Za-z0-9-]{1,80}|[a-f0-9-]{36})$/.test(sid))
          return send(400, { error: 'invalid Codex channel' });
        if (url.pathname === '/channel/register') {
          if (typeof body.proj !== 'string' || !body.proj.startsWith('Codex — ') || body.proj.length > 80)
            return send(400, { error: 'invalid Codex title' });
          if (!managedChannels.has(sid) && managedChannels.size >= 100)
            return send(429, { error: 'channel limit' });
          managedChannels.set(sid, { proj: body.proj, ts: Date.now() });
          if (!persistRadio()) return send(503, { error: 'radio state not saved' });
          return send(200, { ok: true });
        }
        if (!managedChannels.has(sid)) return send(404, { error: 'channel not registered' });
        if (url.pathname === '/channel/ack') {
          if (!Array.isArray(body.ids) || body.ids.length > 100) return send(400, { error: 'invalid ACK' });
          const ids = new Set(body.ids);
          const before = inbox.get(sid) || [];
          const after = before.filter(m => !ids.has(m.id));
          if (after.length) inbox.set(sid, after); else inbox.delete(sid);
          lastConsume.set(sid, Date.now());
          if (!persistRadio()) { inbox.set(sid, before); return send(503, { error: 'ACK not saved' }); }
          return send(200, { ok: true, acknowledged: before.length - after.length });
        }
        if (url.pathname === '/channel/drop') {
          if ((inbox.get(sid) || []).length) return send(409, { error: 'unacknowledged messages' });
          managedChannels.delete(sid);
          if (!persistRadio()) return send(503, { error: 'channel state not saved' });
          history.delete(sid);
          lastConsume.delete(sid);
          for (let i = recent.length - 1; i >= 0; i--) if (recent[i].session === sid) recent.splice(i, 1);
          broadcastPhones({ type: 'session_removed', session: sid, ts: Date.now() });
          return send(200, { ok: true });
        }
        return send(404, { error: 'channel operation' });
      }, 32 * 1024);
    }

    if (req.method === 'POST' && url.pathname === '/hook') {
      const sid = url.searchParams.get('session') || 'unknown';
      const part = parseInt(url.searchParams.get('part') || '1', 10);
      const parts = parseInt(url.searchParams.get('parts') || '1', 10);
      const b64 = (s) => Buffer.from((s || '').replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8');
      let proj = url.searchParams.get('proj') || '';
      try { const pb = url.searchParams.get('projb'); if (pb) proj = b64(pb); } catch (_) {}
      // A hub channel must never carry a live channel's name: the phone hands a picked
      // channel's place to a newcomer with the same name (inheritPick).
      if (isHub) proj = 'hub: ' + proj.slice(0, 40);
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
      }, isHub ? 2 * 1024 * 1024 : undefined);
      return;
    }

    if (req.method === 'POST' && url.pathname === '/say') {
      readBody(req, (buf) => {
        let body = {};
        try { body = JSON.parse(buf.toString('utf8')); } catch (_) { return send(400, { error: 'bad json' }); }
        const sid = body.session_id;
        const text = (body.text || '').trim();
        if (!sid || !text) return send(400, { error: 'need session_id and text' });
        // Never cut silently: an over-long message is refused so the sender knows.
        if (text.length > MSG_MAX) {
          log(`say -> ${sid.slice(0, 8)}: refused, ${text.length} chars > ${MSG_MAX}`);
          return send(413, { error: 'text too long', length: text.length, max: MSG_MAX });
        }
        if (!inbox.has(sid)) {
          if (inbox.size >= 100) return send(429, { error: 'too many queues' });
          inbox.set(sid, []);
        }
        const q = inbox.get(sid);
        if (managedChannels.has(sid) && q.length >= 50) return send(429, { error: 'Codex queue full' });
        q.push({ text, ts: Date.now() });
        while (q.length > 50) q.shift();
        if (managedChannels.has(sid) && !persistRadio()) { q.pop(); return send(503, { error: 'message not saved' }); }
        if (req.headers['x-claudio-diagnostic'] !== '1') markOwnerAlive(); // real phone input only
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
          // Any mic upload from the phone IS a sign of life, even when whisper hears
          // nothing (long outdoor speech): presence must not depend on ASR success,
          // the token-protected upload itself proves the owner.
          if (req.headers['x-claudio-diagnostic'] !== '1' && !/^codextest[0-9a-f]{32}$/.test(utt)) markOwnerAlive();
          if (cancel) {
            fs.writeFileSync(path.join(UTT_DIR, `${utt}.cancel`), '');
            log(`utt ${utt}: cancelled by phone`);
            return send(200, { ok: true });
          }
          fs.writeFileSync(path.join(UTT_DIR, `${utt}.meta.json`),
            JSON.stringify({ session: sid, ts: Date.now(), v: parseInt(url.searchParams.get('v') || '0', 10), nosil: url.searchParams.get('nosil') === '1' }));
          const name = `${utt}_${String(seq).padStart(3, '0')}${fin ? '_F' : ''}.wav`;
          // v1.42: segment cut mid-speech (len = 20s cap, probe = probe after such a cut).
          // Write the marker BEFORE the wav: whisper picks the wav up at once and must already see the marker.
          const cut = url.searchParams.get('cut') || '';
          if (!fin && buf.length && (cut === 'len' || cut === 'probe'))
            fs.writeFileSync(path.join(UTT_DIR, `${utt}_${String(seq).padStart(3, '0')}.cut`), cut);
          if (buf.length) fs.writeFileSync(path.join(UTT_DIR, name), buf);
          else if (fin) fs.writeFileSync(path.join(UTT_DIR, name), Buffer.alloc(0));
          log(`utt ${utt} seg ${seq}${fin ? ' FINAL' : ''}${cut && !fin ? ' cut=' + cut : ''}: ${buf.length}b`);
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
        // v0.54: radio command (e.g. "switch to the second", "which sessions") recognised
        // by the transcriber; it does not go to the session, the phone gets it as a separate event.
        const cmd = body.cmd || null;
        let queued = false;
        if (!cancelled && !empty && sid && text) {
          // Dictation is not refused (the voice would be lost); a cut is marked visibly.
          const kept = text.length > MSG_MAX
            ? `${text.slice(0, MSG_MAX)} [truncated by relay: ${MSG_MAX} of ${text.length} chars]` : text;
          if (kept !== text) log(`uttdone -> ${sid.slice(0, 8)}: cut ${text.length} -> ${MSG_MAX}`);
          if (!inbox.has(sid)) {
            if (inbox.size < 100) inbox.set(sid, []);
          }
          const q = inbox.get(sid);
          if (q) {
            if (managedChannels.has(sid) && q.length >= 50) return send(429, { error: 'Codex queue full' });
            q.push({ text: kept, ts: Date.now() }); while (q.length > 50) q.shift();
            if (managedChannels.has(sid) && !persistRadio()) { q.pop(); return send(503, { error: 'message not saved' }); }
            queued = true;
          }
          if (req.headers['x-claudio-diagnostic'] !== '1' && !/^codextest[0-9a-f]{32}$/.test(body.utt || '')) markOwnerAlive(); // exclude synthetic dictation
          const subs = sessions.get(sid);
          let n = 0;
          if (subs) for (const ws of subs) if (ws.readyState === 1) { ws.send(`[ГОЛОС С ТЕЛЕФОНА] ${kept}`); n++; }
          log(`uttdone -> ${sid.slice(0, 8)}: ws ${n}, queued ${queued}, ${text.slice(0, 60)}`);
        }
        // v0.36: honest event types. utt_lost = text existed but was NOT routed anywhere
        // (no session id, or the inbox map is full) — the phone used to hear a confident
        // "sent" while the text silently died here.
        const type = cmd ? 'utt_cmd'
          : cancelled ? 'utt_cancelled'
          : empty ? 'utt_empty'
          : (queued ? 'utt_sent' : 'utt_lost');
        if (type === 'utt_lost') log(`uttdone LOST (no session): ${text.slice(0, 60)}`);
        if (cmd) log(`uttdone CMD -> phone: ${JSON.stringify(cmd)}`);
        broadcastPhones({
          type, utt: body.utt || '', cmd,
          // v1.42: reason for the server-side final and what was taken for the stop word - for the phone's journal
          why: String(body.why || '').slice(0, 20), word: String(body.word || '').slice(0, 40),
          orphan: !!body.orphan,
          session: sid, text: text.slice(0, 1000), ts: Date.now(),
          // v0.37: "sent" into a queue nobody reads - the phone warns the user
          unread: type === 'utt_sent' && !readerFresh(sid),
        });
        send(200, { ok: true });
      });
      return;
    }

    // v0.60: the transcriber warns the phone that "semantic silence" is accumulating -
    // the user needs a warning beep BEFORE sending, not the fact afterwards.
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
      const hubChannels = hubInventory();
      for (const [sid, h] of history) {
        if (!h.length) continue;
        // A fresh authoritative inventory also removes deleted hub channels.
        const hubState = hubStates.get(ownerOfSid(sid));
        if (sid.startsWith('hub-') && hubState && Date.now() - hubState.ts < 150000 && !hubChannels.has(sid)) continue;
        const last = h[h.length - 1];
        out.push({ sid, proj: managedChannels.get(sid)?.proj || hubChannels.get(sid)?.proj || last.proj, lastTs: last.ts, count: h.length,
          alive: hubChannels.has(sid) ? hubChannels.get(sid).alive : readerFresh(sid) });
      }
      for (const [sid, channel] of managedChannels) {
        if (!out.some(item => item.sid === sid)) out.push({ sid, proj: channel.proj, lastTs: channel.ts, count: 0, alive: readerFresh(sid) });
      }
      for (const [sid, channel] of hubChannels) {
        if (!out.some(item => item.sid === sid)) out.push({ sid, ...channel, count: 0 });
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
      if (managedChannels.has(sid) && !persistRadio()) { inbox.set(sid, q); return send(503, { error: 'queue not saved' }); }
      if (q.length) {
        res.on('close', () => {
          if (!res.writableFinished) {
            const cur = inbox.get(sid) || [];
            inbox.set(sid, q.concat(cur).slice(-50));
            if (managedChannels.has(sid)) persistRadio();
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

    if (HUB && req.method === 'POST' && url.pathname === '/hubfile') {
      const fsid = url.searchParams.get('session') || '';
      if (!ownerOfSid(fsid)) return send(400, { error: 'files only for hub- channels' });
      let fname = '';
      try { fname = Buffer.from((url.searchParams.get('nameb') || '').replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8'); } catch (_) {}
      fname = path.basename(fname || 'file').replace(/[\x00-\x1f]/g, '').slice(0, 120) || 'file';
      pruneHubFiles();
      let total = 0; for (const v of hubFiles.values()) total += v.size;
      return readBody(req, (buf) => {
        if (!buf.length) return send(400, { error: 'empty file' });
        if (total + buf.length > 100 * 1024 * 1024) return send(507, { error: 'relay file space full' });
        const fid = `${Date.now()}-${crypto.randomBytes(4).toString('hex')}`;
        try { fs.writeFileSync(path.join(HUBFILE_DIR, fid), buf, { mode: 0o600 }); } catch (e) { return send(500, { error: 'write failed' }); }
        hubFiles.set(fid, { session: fsid, name: fname, size: buf.length, ts: Date.now() });
        log(`hubfile ${fid} -> ${fsid}: ${buf.length}b`);
        send(200, { ok: true, id: fid });
      }, 20 * 1024 * 1024);
    }

    if (HUB && req.method === 'POST' && url.pathname === '/hubsecret') {
      return readBody(req, (buf) => {
        let body;
        try { body = JSON.parse(buf.toString('utf8')); } catch (_) { return send(400, { error: 'json body' }); }
        if (!body || typeof body !== 'object' || Array.isArray(body)) return send(400, { error: 'json object' });
        const targetHub = body.hub === undefined ? 'server' : body.hub;
        if (!validHub(targetHub)) return send(400, { error: 'bad hub' });
        if (!hubTokens[targetHub]) return send(409, { error: 'hub not configured' });
        const reqId = String(body.req || '');
        if (!/^[A-Za-z0-9_-]{4,64}$/.test(reqId)) return send(400, { error: 'need req' });
        pruneHubSecrets();
        if ([...hubSecrets.values()].filter(v => v.hub === targetHub).length >= 20) return send(429, { error: 'too many pending secrets' });
        hubSecrets.set(`${targetHub}:${reqId}`, { hub: targetHub, body: JSON.stringify(body), ts: Date.now() });
        log(`hubsecret stored req=${reqId.slice(0, 8)} kind=${String(body.provider || body.kind || '').slice(0, 20)}`);
        send(200, { ok: true });
      }, 8 * 1024);
    }

    if (HUB && req.method === 'GET' && url.pathname === '/hubstate') {
      const targetHub = url.searchParams.has('hub') ? url.searchParams.get('hub') : 'server';
      if (!validHub(targetHub)) return send(400, { error: 'bad hub' });
      const hubState = hubStates.get(targetHub);
      if (!hubState) return send(200, { ok: true, hub: targetHub, state: null });
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      return res.end(`{"ok":true,"hub":"${targetHub}","ts":${hubState.ts},"state":${hubState.body}}`);
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
