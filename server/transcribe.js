// Whisper transcriber for the voice bridge. Watches UTT_DIR for dictation
// segments uploaded by the phone (via relay /utt), transcribes them with
// whisper.cpp (base model, language from WHISPER_LANG, default auto), accumulates per-utterance text, detects the
// spoken send-word (and cancel-word) at the tail, and on finalize posts the
// text back through relay /uttdone (which routes it into the session and
// notifies the phone).
//
// v0.36 hardening (code audit + adversarial review):
//  - REGEX FIX: JS \w is ASCII-only — the Kotlin-copied send-word regex never
//    matched Cyrillic, so the server-side send-word was dead. Explicit class.
//  - finalize waits for missing seq numbers (up to 15s) — a tiny final used to
//    overtake a big retrying segment and ship a truncated message. The same
//    completeness check now guards mid-stream send-word finalization.
//  - finished utt ids are remembered (ring of 200); FRESH stragglers (speech
//    recorded while the server was finalizing) are re-homed into a follow-up
//    utterance and delivered within ~30s, not deleted and not 10-min ghosts.
//    Stragglers of a CANCELLED utt are deleted (cancel means discard).
//  - voice cancel: "отмена" at the tail cancels the utterance server-side —
//    but only for app v36+ (meta carries v; older apps can't hear
//    utt_cancelled and would keep pouring speech into a dead utt).
//  - /uttdone failures don't wipe the text — retried on the next tick;
//    relayPost has a 10s timeout so a hung relay can't freeze the transcriber.
//  - abandoned utts are delivered with an explicit marker;
//  - a bad file can no longer abort the whole tick (per-file try/catch).
//
// Runs as pm2 process `voice-whisper`, niced so other services on the box never starve.
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const https = require('https');

const DIR = path.join(process.env.HOME, 'voice_bridge');
const UTT_DIR = path.join(DIR, 'utt');
const CFG = JSON.parse(fs.readFileSync(path.join(DIR, 'config.json'), 'utf8'));
const WHISPER = path.join(process.env.HOME, 'whisper.cpp', 'build', 'bin', 'whisper-cli');
const MODEL = path.join(process.env.HOME, 'whisper.cpp', 'models', 'ggml-base.bin');
const PORT = CFG.port || 8443;
// Язык распознавания для whisper-cli -l: "auto" (по умолчанию), "en", "ru"… Владельцу — WHISPER_LANG=ru.
const WHISPER_LANG = process.env.WHISPER_LANG || 'auto';
// English commands only off a pure-Russian setup: there the old Russian rules stay exactly as they were.
const EN_CMDS = WHISPER_LANG !== 'ru';

const log = (m) => process.stdout.write(`[${new Date().toISOString()}] ${m}\n`);

// utt -> { session, appV, orphan, text, doneSeqs:Set, finalSeen, finalSeq,
//          finalDeadline, finalReady, finalWhy, finishedAt, gapLogged }
const state = new Map();

// Finished/cancelled utt ids (ring of 200) with enough context to re-home
// stragglers: utt -> { session, at, cancelled, appV, seqs }
const done = new Map();
const doneOrder = [];
function markDone(utt, st, cancelled) {
  if (done.has(utt)) return;
  // seqs (v1.42): уже прочитанные сегменты — их повтор (ретрай телефона) не станет новым сообщением
  done.set(utt, { session: st ? st.session : '', at: Date.now(), cancelled: !!cancelled, appV: st ? st.appV : 0,
    seqs: st ? new Set(st.doneSeqs) : new Set() });
  doneOrder.push(utt);
  while (doneOrder.length > 200) done.delete(doneOrder.shift());
}

// Same fuzzy send-word rule as the app. NOTE: \w is ASCII-only in JS (the
// Kotlin original relied on ICU \w) — the class must be explicit.
const SEND_RE = /^([оа]т?|т)п+рав[а-яёa-z0-9]*$/;
// v1.42 (боевое 15.10: два финала посреди живой речи за две минуты, телефон выбросил хвосты).
// Корень шаблона шире команды: «отправил», «отправлю», «отправлено», «отправка», «оправдан»
// — это рассказ, не приказ, и посреди диктовки о делах они звучат постоянно. Сужение, не
// расширение (закон: ложный финал дороже пропуска): повелительные и неопределённые формы
// и уличные искажения «отправляй» по-прежнему ловятся. «Отправляю/отправляем» оставлены:
// whisper слышит так и саму команду, данных, что они режут речь, нет.
const NOT_SEND_RE = /^([оа]т?|т)п+рав(ил|лял|лен|лён|лю$|ит$|итс|ят|ител|лени|им$|ишь|ляешь|к|д)/;
const isSendWord = (w) => SEND_RE.test(w) && !NOT_SEND_RE.test(w);
// v0.50 (боевое: юзер трижды сказал «отправляй», не сработало ни разу): whisper на улице
// отрывает приставку в отдельное слово — в логах «от правилей», «от правляй». Ловим и такой
// вид, но УЗКО: только корень «правля*» (правляй/правляйте). «правил», «правда», «правильно»
// сюда НЕ попадают — именно на широком шаблоне в v0.12 отправляло посреди фразы.
const SEND_SPLIT_RE = /^п+равля[а-яё]*$/;
const SEND_PREFIX = new Set(['от', 'о', 'ат', 'а', 'т']);
const CANCEL_RE = /^отмен[а-яёa-z0-9]*$/;   // «отмена/отменить/отмени» — voice cancel (v0.36)
const FILLERS = new Set(['пожалуйста', 'сейчас', 'давай', 'сообщение', 'это', 'уже', 'быстро']);
const norm = (w) => w.toLowerCase().replace(/[^а-яёa-z0-9]/g, '');

// English send/cancel are deliberately strict (a send mid-speech is worse than a miss):
// the LAST SENTENCE of the text must be exactly one of these phrases, nothing else.
// "I'll send", "don't send", "please send" never fire.
const EN_SEND = new Set(['send', 'send it', 'send please', 'send now']);
const EN_CANCEL = new Set(['cancel']);
const enNorm = (s) => s.toLowerCase().replace(/[^a-z0-9 ]/g, ' ').replace(/\s+/g, ' ').trim();
function lastSentence(text) {
  const m = text.match(/([^.!?]*)[.!?\s]*$/);
  return { phrase: enNorm(m[1]), at: m.index, raw: m[1].trim() };
}

function stripSendWord(text) {
  if (EN_CMDS) {
    const ls = lastSentence(text);
    if (EN_SEND.has(ls.phrase)) return { text: text.slice(0, ls.at).trim(), hit: true, word: ls.raw };
  }
  const words = text.trim().split(/\s+/);
  if (!words.length) return { text, hit: false };
  let cut = -1;
  const li = words.length - 1;
  if (isSendWord(norm(words[li]))) cut = li;
  else if (words.length >= 2 && FILLERS.has(norm(words[li])) && isSendWord(norm(words[li - 1]))) cut = li - 1;
  // v0.51 ОТКАТ: разорванное «от|правляй» временно ОТКЛЮЧЕНО. В поле сразу же случилось
  // ложное срабатывание (финал по стоп-слову посреди живой речи) — а это боль №1 проекта,
  // она дороже пропуска. Возвращаемся к проверенному правилу; ниже пишем в лог, ЧТО именно
  // было принято за команду, чтобы решать по факту, а не по догадке.
  if (cut < 0) return { text, hit: false };
  return { text: words.slice(0, cut).join(' ').trim(), hit: true, word: words.slice(cut).join(' ') };
}

// v1.42 (боевое 15.10, лог whisper: utt mv9scob0 seg 4 — 20.0с звука, текст кончается
// «…Отправляе», финал по стоп-слову). Телефон режет сегмент по длине (20с) посреди слова;
// обрубок «отправляешь/отправляем» подходит под SEND_RE, а продолжение слова и речь за ним
// уехали следующим сегментом в уже закрытое сообщение. Телефон (1.42+) метит такой сегмент
// (cut: len — по 20с, probe — 4с-проба после него). Стоп-слово в хвосте помеченного сегмента
// не финализирует сразу: решает следующий сегмент. Слова в нём = речь шла дальше, не команда
// (текст склеен как есть). Пусто (whisper слов не нашёл) или ничего не пришло за срок = команда.
// Срок после probe длиннее: следующий кусок телефон пришлёт только на паузе или через 20с.
// Старый телефон метки не шлёт — ведёт себя как раньше.
const SEND_WAIT_LEN = 12000;   // проба 4с + отгрузка по мобильной сети + очередь whisper
const SEND_WAIT_PROBE = 25000;
// pend — отложенное стоп-слово {seq, word, deadline} или null; text — весь текст utt с этим
// сегментом; segText — текст этого сегмента; cut — метка сегмента ('', 'len', 'probe').
// Возврат: { pend, word, note } — word не пуст = финализировать по стоп-слову сейчас.
function sendDecision(pend, text, segText, cut, seq, now) {
  let note = '';
  // ревью: решает только ПОСЛЕДУЮЩИЙ сегмент; запоздавший предыдущий ничего не меняет
  if (pend && seq < pend.seq) return { pend, word: '', note };
  // ревью: сегмент без слов не может сделать командой то, что ею уже не было
  // (иначе отвергнутый обрубок + «сейчас» воскресал бы на следующем пустом куске)
  if (!pend && !segText) return { pend: null, word: '', note };
  let rejected = false;
  if (pend) {
    if (!segText) return { pend: null, word: pend.word, note: `за отложенным "${pend.word}" слов нет — это команда` };
    note = `после "${pend.word}" речь продолжилась — не команда, склеено`;
    rejected = true;
    // ревью: прежний обрубок отвергнут — команду ищем только в новом сегменте, иначе
    // продолжение «пожалуйста»/«сейчас» (FILLERS) снова сделало бы обрубок стоп-словом
    text = segText;
  }
  const { hit, word } = stripSendWord(text);
  if (!hit) return { pend: null, word: '', note, rejected };
  if (cut) {
    const wait = cut === 'probe' ? SEND_WAIT_PROBE : SEND_WAIT_LEN;
    return { pend: { seq, word, deadline: now + wait }, rejected,
      word: '', note: (note ? note + '; ' : '') + `стоп-слово "${word}" в хвосте обрезанного (${cut}) сегмента — жду следующий до ${wait / 1000}с` };
  }
  return { pend: null, word, note, rejected };
}

// v0.54 (слово юзера 30.07: «рубка между сессиями нужна, но по НАЗВАНИЯМ работать не будет —
// я говорю недостаточно чётко и не помню все имена; давай по номерам»): голосовые команды
// самой рации. Команда НЕ уходит в сессию — она адресована телефону.
// Защита от боли №1 проекта (ложное срабатывание стоп-слова): командой считается только
// КОРОТКАЯ фраза целиком (≤5 слов) с явным глаголом/словом «сессия» И номером. Хвост длинного
// сообщения командой не станет никогда, потому что длинное сообщение просто не проходит по длине.
const ORD_PREFIX = [['перв', 1], ['втор', 2], ['трет', 3], ['четв', 4], ['пят', 5], ['шест', 6]];
const NUM_WORD = { один: 1, одну: 1, два: 2, две: 2, три: 3, четыре: 4, пять: 5, шесть: 6 };
// Расстояние Левенштейна — whisper коверкает и служебные слова: «сессию» → «сетсию».
function lev(a, b) {
  if (!a.length) return b.length;
  if (!b.length) return a.length;
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    for (let j = 1; j <= b.length; j++)
      cur[j] = Math.min(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    prev = cur;
  }
  return prev[b.length];
}
const isSessionWord = (x) =>
  x.startsWith('сесси') || lev(x, 'сессию') <= 2 || lev(x, 'сессия') <= 2 || lev(x, 'сессии') <= 2;

function parseCmd(text) {
  // v0.60 (боевое, ГЛАВНАЯ причина «переключение вообще не работает»): юзер, не услышав
  // отклика, повторяет команду два-три раза — и фраза перестаёт быть короткой, а значит
  // и командой. Смотрим ТОЛЬКО ПЕРВОЕ предложение: повторы и продолжение мысли ему не мешают.
  const first = (text.split(/[.!?]/)[0] || '');
  const w = first.toLowerCase().replace(/[^а-яёa-z0-9 ]/g, ' ').split(/\s+/).filter(Boolean);
  if (!w.length || w.length > 6) return null;
  const hasSession = w.some((x) => x.startsWith('сесси'));
  // ПОВЕЛИТЕЛЬНОЕ наклонение, а не любое «переключ*»: «я переключился на вторую вкладку» —
  // это сообщение, а не команда, и съесть его было бы хуже, чем не понять команду.
  // v0.57 (боевое, ПЕРВАЯ ЖЕ команда с самоката): юзер сказал «переключи на сессию 2»,
  // whisper выдал «Вереключи на CSU2» — глухая «п» на ветру слышится как «в»/«б».
  // Расширяем ТОЛЬКО первую букву, форма остаётся повелительной: ложных срабатываний это
  // не добавляет («вереключился» так же не подходит), а живую команду больше не теряет.
  const hasSwitch = w.some((x) => /^[впб]ереключ(и|ись|ите|итесь|ить|аемся|айся)$/.test(x));
  // v0.55 (ревью, КРИТИЧНО): «сессия + номер» БОЛЬШЕ НЕ КОМАНДА. Домен юзера — ровно сессии
  // и номера, и на живой речи это ело сообщения: «в сессии два бага», «сессия три раза упала»,
  // «в этой сессии два вопроса» — всё это распознавалось как переключение, текст исчезал
  // навсегда, а адресат молча уезжал. Команда обязана иметь ГЛАГОЛ в повелительном.
  const asks = w.some((x) => /^(каки|какие|список|перечисл)/.test(x));
  if (hasSession && asks && w.length <= 4) return { cmd: 'list' };
  // Команда обязана НАЧИНАТЬСЯ с глагола и называть цель — номер или слово «сессия».
  // Без этого «переключи ветку на main и собери» (живая рабочая фраза) стало бы командой.
  const startsCmd = /^[впб]ереключ(и|ись|ите|итесь|ить|аемся|айся)$/.test(w[0]) ||
    (w.length > 1 && /^(давай|рация)$/.test(w[0]) && /^[впб]ереключ/.test(w[1]));
  // v0.56: снять закрепление голосом — вернуться к «отвечаю в последнюю игравшую».
  // Тоже только повелительное наклонение и короткая фраза.
  if (w.some((x) => /^открепи(сь|те|ть)?$/.test(x)) && w.length <= 3) return { cmd: 'unpin' };
  // v0.93 (слово юзера с самоката): «отбой» — мне нечего сказать. Черновик стереть,
  // микрофон усыпить, в сессию НЕ отправлять. Короткая фраза, чтобы не съесть живую речь.
  // v0.97 (боевое, лог whisper: «отбой» слышится как «А, да, бой!» и «Адбой» — глухая «т»
  // на ветру звучит как «д», а приставка отрывается). Ловим слитные варианты в любом месте
  // КОРОТКОЙ фразы, а голое «бой» — только если всё остальное это вводные слова.
  const FILLER = /^(а|э|эм|ну|да|вот|так|и|ой|ты|то|эй)$/;
  // v1.01: команда сна = КОРОТКАЯ фраза, где кроме самого слова только вводные.
  // Слово ловим гибко (whisper коверкает его каждый раз: отбой/адбой/дебой/отой/а ты бой),
  // но живая речь не пройдёт: рядом с 'бой' в ней всегда есть значимые слова
  // ('отбой воздушной тревоги', 'морской бой', 'бой курантов').
  const SLEEP_WORD = /^[а-яё]{1,3}(бой|бои)$|^(от|ад|ат|аб|об)ой$|^бо[йи]$/;
  if (w.length <= 4 && w.some((x) => SLEEP_WORD.test(x)) &&
      w.every((x) => FILLER.test(x) || SLEEP_WORD.test(x))) return { cmd: 'sleep' };
  if (w.length <= 4 && w.some((x) => /^бой$/.test(x)) &&
      w.every((x) => FILLER.test(x) || /^бой$/.test(x))) return { cmd: 'sleep' };
  if (w.some((x) => /^отвечай(те)?$/.test(x)) && w.some((x) => x.startsWith('последн')) && w.length <= 4)
    return { cmd: 'unpin' };
  if (!hasSwitch) return null;
  for (const x of w) {
    if (/^[1-9]$/.test(x)) return { cmd: 'switch', n: parseInt(x, 10) };
    if (NUM_WORD[x]) return { cmd: 'switch', n: NUM_WORD[x] };
    for (const [p, n] of ORD_PREFIX) if (x.startsWith(p)) return { cmd: 'switch', n };
  }
  // Номер мог слипнуться со словом («сессию 2» → «CSU2»). Ищем цифру ВНУТРИ токена —
  // это безопасно: сюда мы попадаем только с повелительным глаголом в фразе.
  for (const x of w) {
    const d = x.match(/[1-9]/);
    if (d) return { cmd: 'switch', n: parseInt(d[0], 10) };
  }
  // v0.59 (боевое: в поле юзер переключается ПО ИМЕНИ — «переключи на сессию брейншторм», —
  // хотя сам просил номера). Имя сервер не знает: список сессий живёт на телефоне. Отдаём
  // телефону остаток фразы, он сопоставит его со своими именами нечётко (whisper коверкает:
  // «бренштурм», «бренч-торм»). Служебные слова выкидываем.
  // Имя цели принимаем ТОЛЬКО когда в фразе есть слово «сессия» (пусть и покорёженное) и
  // команда открывает сообщение — иначе рабочие фразы вида «переключи ветку на main»
  // уезжали бы в рубку вместо сессии.
  if (!startsCmd || !w.some(isSessionWord)) return null;
  const STOP = new Set(['на', 'в', 'во', 'мне', 'давай', 'пожалуйста', 'рация']);
  const name = w.filter((x) => !/^[впб]ереключ/.test(x) && !isSessionWord(x) && !STOP.has(x)).join(' ').trim();
  if (name) return { cmd: 'switch', n: 0, name };
  return null;
}

function tailCancel(text) {
  if (EN_CMDS && EN_CANCEL.has(lastSentence(text).phrase)) return true;
  const words = text.trim().split(/\s+/).filter(Boolean);
  if (!words.length) return false;
  return CANCEL_RE.test(norm(words[words.length - 1]));
}

// v0.37 (боевое: юзер слушал музыку — сессии прилетало «[музыка] [музыка] [музыка]»):
// whisper на не-речи галлюцинирует служебные пометки и типовые фразы. Вырезаем их;
// сегмент, состоявший только из мусора, превращается в пустой и не шлётся.
const NOISE_PHRASES = [
  /продолжение следует/gi,
  /субтитры (сделал|делал|создавал)[^.!?]*/gi,
  /редактор субтитров[^.!?]*/gi,
  /корректор [а-яё.\s]+/gi,
];
function stripNoise(text) {
  let t = text
    .replace(/\[[^\]]*\]/g, ' ')                       // [музыка], [шум], [аплодисменты]...
    .replace(/\((музыка|шум|смех|аплодисменты)[^)]*\)/gi, ' ')
    .replace(/♪+/g, ' ');
  for (const re of NOISE_PHRASES) t = t.replace(re, ' ');
  t = t.replace(/\s+/g, ' ').trim();
  if (/^[.,!?…\-\s]*$/.test(t)) return '';   // осталась одна пунктуация — это не текст
  return t;
}

function relayPost(pathname, obj) {
  return new Promise((resolve) => {
    const body = JSON.stringify(obj);
    const req = https.request({
      hostname: 'localhost', port: PORT, method: 'POST',
      path: `${pathname}?token=${CFG.token}`, rejectUnauthorized: false,
      headers: { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) },
    }, (res) => { res.resume(); res.on('end', () => resolve(res.statusCode)); });
    // a hung (not dead) relay must not freeze the busy-gated tick forever
    req.setTimeout(10000, () => req.destroy(new Error('timeout')));
    req.on('error', (e) => { log(`relay post err: ${e.message}`); resolve(0); });
    req.write(body); req.end();
  });
}

function readMeta(utt) {
  try { return JSON.parse(fs.readFileSync(path.join(UTT_DIR, `${utt}.meta.json`), 'utf8')); } catch (_) { return {}; }
}

function newState(utt) {
  const meta = readMeta(utt);
  const st = {
    session: meta.session || '', appV: meta.v || 0, orphan: !!meta.orphan, noSil: !!meta.nosil,
    text: '', doneSeqs: new Set(), finalSeen: false, emptyMs: 0, warned: false,
    finalSeq: -1, finalDeadline: 0, finalReady: false, finalWhy: '',
    finishedAt: 0, gapLogged: false,
  };
  state.set(utt, st);
  return st;
}

function transcribe(wav) {
  try {
    const out = execFileSync('nice', ['-n', '10', WHISPER, '-m', MODEL, '-f', wav, '-l', WHISPER_LANG, '-nt', '-t', '3'],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], timeout: 120000 });
    return out.replace(/\s+/g, ' ').trim();
  } catch (e) {
    log(`whisper err on ${path.basename(wav)}: ${e.message}`);
    return '';
  }
}

async function finalize(utt, st, why) {
  // Session id can arrive with a later segment than the one that created the
  // state — re-read the freshest meta before routing.
  const meta = readMeta(utt);
  if (meta.session) st.session = meta.session;
  let text = why === 'cancelled' ? '' : st.text.trim();
  if (text) {
    const r = stripSendWord(text);
    // v1.42: отвергнутый обрубок («Отправляе» + «сейчас») — часть речи, не срезать
    if (r.hit && r.text.length >= (st.keepLen || 0)) {
      log(`utt ${utt}: срезано стоп-слово "${r.word}"`);
      text = r.text;
    }
  }
  if (why === 'abandoned' && text) text = '(обрывок диктовки, доехал с опозданием) ' + text;
  // v0.54: команда рации перехватывается ЗДЕСЬ и в сессию не уходит. Гейт по версии клиента
  // обязателен: приложение до 54 события utt_cmd не понимает и молча потеряло бы сообщение.
  let cmd = null;
  if (text && why !== 'cancelled' && st.appV >= 54) {
    cmd = parseCmd(text);
    if (cmd) {
      log(`utt ${utt}: КОМАНДА РАЦИИ ${JSON.stringify(cmd)} из "${text}" — в сессию не отправляю`);
      cmd.src = text;   // телефон покажет в журнале, что именно было съедено командой
      text = '';
    }
  }
  log(`utt ${utt} FINAL (${why}): "${text.slice(0, 80)}"`);
  // v1.42: причина финала едет в телефон — в журнале видно, ПОЧЕМУ сервер закрыл сообщение
  const code = await relayPost('/uttdone', {
    utt, session_id: st.session, text, empty: !text && !cmd, cancelled: why === 'cancelled', cmd,
    why: why === 'final' && st.sendWord ? 'send-word' : why, word: st.sendWord || '',
    orphan: !!st.orphan,   // v1.42: хвост после чужого финала — телефон не скажет второе «ушло»
  });
  if (code !== 200) {
    // Relay down/restarting: keep the text, retry on the next tick — a restart
    // in this window used to silently delete the whole message.
    st.finalReady = true;
    st.finalWhy = why;
    log(`utt ${utt}: uttdone failed (${code}) — retry next tick`);
    return;
  }
  st.finishedAt = Date.now();
  st.finalReady = false;
  markDone(utt, st, why === 'cancelled');
  cleanup(utt, why === 'cancelled' ? null : st.doneSeqs);
}

// v1.42 (боевое 15.10, ревью): финал посреди речи (стоп-слово, тишина, «отбой») стирал и
// сегменты, которые уже лежали на диске, но ещё не прочитаны, — это речь ПОСЛЕ финала.
// С keep (набор прочитанных seq) непрочитанные сегменты (и финальный со звуком) остаются: done-ring
// следующим проходом переселит их в `${utt}r` (rehomeStraggler), а не удалит.
const cutMark = (utt, seq) => path.join(UTT_DIR, `${utt}_${String(seq).padStart(3, '0')}.cut`);

function cleanup(utt, keep) {
  try {
    for (const f of fs.readdirSync(UTT_DIR)) {
      if (!f.startsWith(utt)) continue;
      // v1.42: метка .cut непрочитанного сегмента остаётся вместе с ним (переедет в rehome)
      const m = keep && f.match(/^([a-z0-9]+)_(\d{3})(_F)?\.(wav|cut)$/);
      if (m && m[1] === utt && !keep.has(parseInt(m[2], 10))) continue;
      fs.unlinkSync(path.join(UTT_DIR, f));
    }
  } catch (_) {}
  state.delete(utt);
}

// Speech recorded while the server was finalizing the previous utterance used
// to be deleted (or delivered as a 10-min ghost). Re-home it into a follow-up
// utterance instead; the short orphan flush delivers it within ~30s.
function rehomeStraggler(utt, f, seq, info) {
  const rid = `${utt}r`;
  if (done.has(rid)) {
    try { fs.unlinkSync(path.join(UTT_DIR, f)); } catch (_) {}
    try { fs.unlinkSync(cutMark(utt, seq)); } catch (_) {}
    return;
  }
  try {
    fs.writeFileSync(path.join(UTT_DIR, `${rid}.meta.json`),
      JSON.stringify({ session: info.session, ts: Date.now(), v: info.appV, orphan: true }));
    // v1.42 (ревью): метка «обрезан посреди речи» едет вместе с сегментом — хвост не теряет защиту
    try { fs.renameSync(cutMark(utt, seq), cutMark(rid, seq)); } catch (_) {}
    fs.renameSync(path.join(UTT_DIR, f), path.join(UTT_DIR, `${rid}_${String(seq).padStart(3, '0')}.wav`));
    log(`utt ${utt} seg ${seq}: arrived after finalize — re-homed to ${rid}`);
  } catch (e) {
    log(`rehome err ${f}: ${e.message}`);
    try { fs.unlinkSync(path.join(UTT_DIR, f)); } catch (_) {}
    try { fs.unlinkSync(cutMark(utt, seq)); } catch (_) {}
    try { fs.unlinkSync(cutMark(`${utt}r`, seq)); } catch (_) {}
  }
}

let busy = false;
async function tick() {
  if (busy) return;
  busy = true;
  try {
    // retry finalizations that failed because the relay was unreachable
    for (const [utt, st] of state) {
      if (st.finalReady) await finalize(utt, st, st.finalWhy || 'final');
    }
    const files = fs.readdirSync(UTT_DIR).sort();
    // cancels first
    for (const f of files) {
      if (f.endsWith('.cancel')) {
        const utt = f.slice(0, -7);
        log(`utt ${utt}: cancel`);
        markDone(utt, state.get(utt), true);
        cleanup(utt);
      }
    }
    for (const f of files) {
      try {
        const m = f.match(/^([a-z0-9]+)_(\d{3})(_F)?\.wav$/);
        if (!m) continue;
        const [, utt, seqS, fin] = m;
        const full = path.join(UTT_DIR, f);
        if (!fs.existsSync(full)) continue;   // removed by a cleanup earlier in this tick
        const seq = parseInt(seqS, 10);
        const doneInfo = done.get(utt);
        if (doneInfo) {
          // straggler of a finished utterance: fresh speech after a send-word
          // finalize is the start of the NEXT message — keep it. Cancelled or
          // stale (>60s) leftovers are garbage.
          const fresh = Date.now() - doneInfo.at < 60000;
          let size = 0;
          try { size = fs.statSync(full).size; } catch (_) {}
          // v1.42 (ревью): финальный сегмент со звуком — тоже речь (телефон отправил хвост в
          // закрытый сервером utt, не успев узнать о финале); повтор прочитанного seq — не речь
          const seen = doneInfo.seqs && doneInfo.seqs.has(seq);
          if (fresh && !doneInfo.cancelled && size > 100 && !seen) rehomeStraggler(utt, f, seq, doneInfo);
          else { try { fs.unlinkSync(full); } catch (_) {} try { fs.unlinkSync(cutMark(utt, seq)); } catch (_) {} }
          continue;
        }
        const st = state.get(utt) || newState(utt);
        if (st.doneSeqs.has(seq)) { try { fs.unlinkSync(full); } catch (_) {} continue; }
        // send-word finalize is pending: higher seqs belong to the NEXT
        // utterance — leave them on disk, they'll be re-homed after finalize
        if (st.finalSeen && seq > st.finalSeq) continue;
        st.doneSeqs.add(seq);
        // v1.42: метка «обрезан посреди речи» — релей пишет её ДО wav; читаем только сегмент,
        // который сейчас разбираем (непрочитанный уедет с меткой в rehome)
        let cut = '';
        try { cut = fs.readFileSync(cutMark(utt, seq), 'utf8').trim(); fs.unlinkSync(cutMark(utt, seq)); } catch (_) {}
        if (fin) cut = '';
        const size = fs.statSync(full).size;
        let text = '';
        if (size > 100) {
          const t0 = Date.now();
          text = stripNoise(transcribe(full));
          if (text) { st.text = (st.text + ' ' + text).trim(); st.emptyMs = 0; st.warned = false; }
          else st.emptyMs += (size / 32000) * 1000;   // сек звука без слов -> мс
          log(`utt ${utt} seg ${seq}: ${(size / 32000).toFixed(1)}s audio -> ${Date.now() - t0}ms, "${text.slice(0, 60)}"`);
        }
        try { fs.unlinkSync(full); } catch (_) {}
        if (fin) {
          // Don't finalize yet: a big segment may still be in flight while this
          // tiny final overtook it. Completeness is checked below.
          st.finalSeen = true;
          st.finalSeq = seq;
          st.finalDeadline = Date.now() + 15000;
          // v1.42: финал разрешает отложенное стоп-слово: слова в нём — речь шла дальше
          if (st.sendPend) {
            if (text) {
              log(`utt ${utt}: после "${st.sendPend.word}" речь продолжилась — не команда`);
              st.keepLen = st.text.length - text.length - 1;
            } else st.sendWord = st.sendPend.word;
            st.sendPend = null;
          }
          continue;
        }
        // voice cancel arrived mid-stream (v36+ apps only: older apps can't
        // hear utt_cancelled and would silently lose everything after it)
        if (st.appV >= 36 && tailCancel(st.text)) { await finalize(utt, st, 'cancelled'); continue; }
        // v0.98 (боевое: юзер сказал «отбой» десять раз — ничего. В логе слово РАСПОЗНАНО,
        // но команды разбирались ТОЛЬКО на финале, а финала нет: юзер после «отбоя» молчит).
        // Короткая команда сна обязана срабатывать в потоке, как стоп-слово.
        {
          const midCmd = parseCmd(st.text);
          if (midCmd && midCmd.cmd === 'sleep') {
            log(`utt ${utt}: КОМАНДА «отбой» в потоке из "${st.text.slice(0, 40)}" — закрываю`);
            await finalize(utt, st, 'cmd');
            continue;
          }
        }
        // send-word arrived mid-stream: finalize as soon as every seq is here
        // v1.42: на сегменте, обрезанном посреди речи, — только после следующего (sendDecision)
        const sd = sendDecision(st.sendPend, st.text, text, cut, seq, Date.now());
        st.sendPend = sd.pend;
        // текст до отвергнутого обрубка включительно finalize не срезает как стоп-слово
        if (sd.rejected) st.keepLen = st.text.length - text.length - 1;
        if (sd.note) log(`utt ${utt}: ${sd.note}`);
        if (sd.word) {
          const word = sd.word;
          log(`utt ${utt}: стоп-слово распознано как "${word}" — финализирую`);
          st.sendWord = word;
          let gap = false;
          for (let i = 0; i < seq; i++) if (!st.doneSeqs.has(i)) { gap = true; break; }
          if (!gap) await finalize(utt, st, 'send-word');
          else { st.finalSeen = true; st.finalSeq = seq; st.finalDeadline = Date.now() + 15000; }
          continue;
        }
        // v0.50 (боевое + ИЗМЕРЕНО на реальной записи юзера с автострады): отсчёт тишины
        // В ТЕЛЕФОНЕ на улице не доходит до конца НИКОГДА — уличный шум пробивает любой
        // уровневый порог. Замер по 31 куску его звука: подъём порога с +6 до +14 dB удлиняет
        // самую длинную паузу лишь с 4.0с до 4.8с при нужных 10с, а чувствительность к речи
        // падает с 61% до 44% кадров. Значит порог тут не поможет в принципе.
        // Поэтому «человек замолчал» решает СМЫСЛОВОЙ детектор: whisper не нашёл слов.
        // Он не зависит ни от ветра, ни от громкости, ни от АРУ микрофона.
        // v0.60 (боевое: «оно отправляет само, хотя я говорю, и я не слышу никакого пика»).
        // Разбор: телефон грузит сегменты, только когда СЛЫШИТ энергию речи, а на самокате
        // это ветер. Whisper на ветре не находит слов — и «смысловая тишина» копилась ровно
        // тогда, когда человек говорил в шум. Порог поднят с 10с до 20с, и в 12с телефон
        // получает предупреждение, чтобы бип успел прозвучать и юзер мог продолжить.
        if (!st.noSil && st.text && st.emptyMs >= 12000 && !st.warned) {
          st.warned = true;
          log(`utt ${utt}: 12с без слов — предупреждаю телефон`);
          relayPost('/uttwarn', { utt, session_id: st.session });
        }
        if (!st.noSil && st.text && st.emptyMs >= 20000) { await finalize(utt, st, 'silence'); continue; }
      } catch (e) {
        log(`file err ${f}: ${e.message}`);
      }
    }
    // v1.42: отложенное стоп-слово, за которым за срок ничего не пришло, — команда
    for (const [utt, st] of state) {
      if (st.finishedAt || st.finalSeen || !st.sendPend || Date.now() <= st.sendPend.deadline) continue;
      log(`utt ${utt}: за "${st.sendPend.word}" сегментов нет — это команда, финализирую`);
      st.sendWord = st.sendPend.word;
      st.finalSeen = true; st.finalSeq = st.sendPend.seq; st.finalDeadline = Date.now() + 15000;
      st.sendPend = null;
    }
    // finals: ship once every seq up to finalSeq has been seen (or the wait expires)
    for (const [utt, st] of state) {
      if (st.finishedAt || st.finalReady || !st.finalSeen) continue;
      let missing = -1;
      for (let i = 0; i < st.finalSeq; i++) if (!st.doneSeqs.has(i)) { missing = i; break; }
      if (missing < 0) {
        await finalize(utt, st, st.appV >= 36 && tailCancel(st.text) ? 'cancelled' : 'final');
      } else if (Date.now() > st.finalDeadline) {
        log(`utt ${utt}: seq ${missing} never arrived — finalizing with a gap`);
        await finalize(utt, st, 'final');
      } else if (!st.gapLogged) {
        st.gapLogged = true;
        log(`utt ${utt}: waiting for seq ${missing} before finalize`);
      }
    }
    // abandoned utts (app died mid-dictation): flush after 10 min;
    // re-homed orphans (speech after a finalize) go out after 30s
    for (const [utt, st] of state) {
      const limit = st.orphan ? 30000 : 600000;
      if (!st.finishedAt && !st.finalSeen && st.doneSeqs.size && Date.now() - lastTouch(utt) > limit) {
        log(`utt ${utt}: ${st.orphan ? 'orphan' : 'abandoned'}, flushing`);
        await finalize(utt, st, 'abandoned');
      }
    }
  } catch (e) { log(`tick err: ${e.message}`); }
  busy = false;
}

function lastTouch(utt) {
  try { return fs.statSync(path.join(UTT_DIR, `${utt}.meta.json`)).mtimeMs; } catch (_) { return 0; }
}

log(`voice-whisper up: watching ${UTT_DIR}, model ${path.basename(MODEL)}`);
setInterval(tick, 500);
