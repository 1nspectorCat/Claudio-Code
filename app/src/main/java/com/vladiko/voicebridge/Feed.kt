package com.vladiko.voicebridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// v0.69: local memory for the chat feed. Stores ONLY the user's outgoing replies
// (echoes of confirmed sends: utt_sent / a successful /say) — incoming messages
// live in the relay's /history and are never duplicated here. The screen listens;
// the service merely drops a one-line "it went out" fact, LogBus-style.
object Feed {
    data class Out(val text: String, val ts: Long, val unread: Boolean)

    private val map = LinkedHashMap<String, ArrayList<Out>>()   // sid -> echoes, oldest first
    @Volatile var listener: (() -> Unit)? = null
    private var loaded = false

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val raw = ctx.getSharedPreferences("feed", Context.MODE_PRIVATE)
                .getString("out", "{}") ?: "{}"
            val o = JSONObject(raw)
            for (sid in o.keys()) {
                val arr = o.getJSONArray(sid)
                val l = ArrayList<Out>()
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    l.add(Out(e.optString("t"), e.optLong("ts"), e.optBoolean("u")))
                }
                map[sid] = l
            }
        } catch (_: Exception) {}
    }

    private fun save(ctx: Context) {
        try {
            val o = JSONObject()
            for ((sid, l) in map) {
                val arr = JSONArray()
                for (e in l) arr.put(JSONObject().put("t", e.text).put("ts", e.ts).put("u", e.unread))
                o.put(sid, arr)
            }
            ctx.getSharedPreferences("feed", Context.MODE_PRIVATE).edit()
                .putString("out", o.toString()).apply()
        } catch (_: Exception) {}
    }

    // Called by the service at the moment a send is CONFIRMED (utt_sent event or
    // a successful /say response). Never called on failures — the feed shows facts.
    @Synchronized
    fun userSent(ctx: Context, sid: String, text: String, unread: Boolean, ts: Long = 0L) {
        if (sid.isEmpty() || text.isEmpty()) return
        load(ctx)
        // re-insert to tail = LRU: eviction drops the least recently USED sid, not the
        // longest-known one (the owner's main session is usually the oldest entry)
        val l = map.remove(sid) ?: ArrayList()
        map[sid] = l
        l.add(Out(text, if (ts > 0) ts else System.currentTimeMillis(), unread))
        while (l.size > 30) l.removeAt(0)
        while (map.size > 20) map.remove(map.keys.first())
        save(ctx)
        listener?.invoke()
    }

    @Synchronized
    fun outFor(ctx: Context, sid: String): List<Out> {
        load(ctx)
        return map[sid]?.toList() ?: emptyList()
    }

    // v0.79: когда юзер последний раз писал В ЭТОТ канал. Вместе с временем последнего
    // ответа канала это и есть косвенное состояние: спросили и ждём — «работает»;
    // ответ свежее вопроса — «ответила». Точнее рация знать не может: думает ли Claude
    // Code прямо сейчас, релею никто не сообщает.
    @Synchronized
    fun lastAskTs(ctx: Context, sid: String): Long {
        load(ctx)
        return map[sid]?.lastOrNull()?.ts ?: 0L
    }

    // ── v0.73: локальная память ВХОДЯЩИХ ─────────────────────────────────
    // Relay держит историю в оперативной памяти (HIST_CAP=80 событий на канал,
    // сутки, и полный сброс при каждом рестарте) — переписка на экране исчезала
    // не по вине телефона. Кэшируем то, что уже видели: лента переживает рестарт
    // релея и копится глубже его капа. Аудио может протухнуть (pruneAudio на
    // сервере) — текст остаётся, потому и храним отдельно от звука.
    data class In(
        val msgid: String, val ts: Long, val proj: String, val text: String,
        val audios: List<Pair<Int, String>>, val bytes: Long
    )

    private val inMap = LinkedHashMap<String, LinkedHashMap<String, In>>()   // sid -> msgid -> In
    private var inLoaded = false

    @Synchronized
    private fun loadIn(ctx: Context) {
        if (inLoaded) return
        inLoaded = true
        try {
            val raw = ctx.getSharedPreferences("feed", Context.MODE_PRIVATE)
                .getString("in", "{}") ?: "{}"
            val o = JSONObject(raw)
            for (sid in o.keys()) {
                val arr = o.getJSONArray(sid)
                val m = LinkedHashMap<String, In>()
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    val auds = ArrayList<Pair<Int, String>>()
                    val aa = e.optJSONArray("a")
                    if (aa != null) for (j in 0 until aa.length()) {
                        val s = aa.optString(j)
                        val p = s.indexOf('|')
                        if (p > 0) auds.add(Pair(s.substring(0, p).toIntOrNull() ?: 1, s.substring(p + 1)))
                    }
                    val item = In(e.optString("m"), e.optLong("ts"), e.optString("p"),
                        e.optString("t"), auds, e.optLong("b"))
                    if (item.msgid.isNotEmpty()) m[item.msgid] = item
                }
                inMap[sid] = m
            }
        } catch (_: Exception) {}
    }

    private fun saveIn(ctx: Context) {
        try {
            val o = JSONObject()
            for ((sid, m) in inMap) {
                val arr = JSONArray()
                for (e in m.values) {
                    val aa = JSONArray()
                    for (a in e.audios) aa.put("${a.first}|${a.second}")
                    arr.put(JSONObject().put("m", e.msgid).put("ts", e.ts).put("p", e.proj)
                        .put("t", e.text).put("a", aa).put("b", e.bytes))
                }
                o.put(sid, arr)
            }
            ctx.getSharedPreferences("feed", Context.MODE_PRIVATE).edit()
                .putString("in", o.toString()).apply()
        } catch (_: Exception) {}
    }

    @Synchronized
    fun rememberIn(ctx: Context, sid: String, items: List<In>) {
        if (sid.isEmpty() || items.isEmpty()) return
        loadIn(ctx)
        val m = inMap.remove(sid) ?: LinkedHashMap()
        inMap[sid] = m
        var changed = false
        for (it in items) {
            if (it.msgid.isEmpty()) continue
            val old = m[it.msgid]
            // свежая версия сообщения полнее (доехали остальные части) — заменяем
            if (old == null || old.audios.size < it.audios.size || old.text.length < it.text.length) {
                m[it.msgid] = it
                changed = true
            }
        }
        while (m.size > 60) m.remove(m.keys.first()).also { changed = true }
        while (inMap.size > 8) inMap.remove(inMap.keys.first()).also { changed = true }
        if (changed) saveIn(ctx)
    }

    @Synchronized
    fun inFor(ctx: Context, sid: String): List<In> {
        loadIn(ctx)
        return inMap[sid]?.values?.sortedBy { it.ts } ?: emptyList()
    }
}
