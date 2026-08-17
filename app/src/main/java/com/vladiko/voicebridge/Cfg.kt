package com.vladiko.voicebridge

import android.content.Context
import java.util.ArrayDeque

// App configuration persisted in SharedPreferences. Defaults point at the
// owner's relay; all values editable on the settings screen (nothing hardcoded
// for other users — they enter their own relay URL/token/pin).
object Cfg {
    // v0.66: defaults live in the build flavor. "personal" bakes in the owner's relay,
    // "store" ships empty — no personal endpoints inside the public binary.
    val DEF_URL = BuildConfig.DEF_URL
    val DEF_PIN = BuildConfig.DEF_PIN

    var url = DEF_URL
    var token = ""
    var pin = DEF_PIN
    var handsFree = false
    var autoSend = true     // v0.5: hands-free auto-send after silence (warn beep 4s, send 2s later)
    var btMic = true        // use headset (SCO/BLE) microphone for dictation
    var onlyHeadset = false // hold playback until headphones are connected
    // v1.04 (слово юзера: «надел наушники — работаю в них, снял дома — работаю с телефона,
    // и не хочу лазить в настройки»): режим сам следует за гарнитурой. Наушники есть —
    // звук идёт в них; наушников нет — играем вслух, ничего не копим.
    var autoRoute = true
    var announceProj = true // say the project name before a new session speaks
    var catchup = true      // on connect, replay messages missed in the last 30 min
    var speed = 1.0f        // playback speed (1.0..2.0)
    var replyTarget = ""    // "" = reply to the last played session
    // v0.62: solo mode — hear ONLY this session, everything else is silent. Unlike the
    // per-session mute list this is a RULE, not a snapshot: sessions the phone has never
    // seen (an agent spawning a dozen more) are silent from their very first word.
    var soloSid = ""        // "" = solo off; ОДИН выбранный канал (частный случай picked)
    // v0.82 (слово юзера: «хочу работать с двумя-тремя каналами из пяти, и видеть, с какими»).
    // Набор рабочих каналов — ПРАВИЛО, как и solo: слышны ровно эти sid, всё остальное молчит,
    // включая сессии, которых телефон ещё не видел. Пусто = слышу всех (прежнее поведение).
    var pickedSids = ""     // CSV
    var voiceCues = true    // v0.26: spoken cues ("слушаю"/"ушло") — beeps get lost over SCO
    // v0.35: dictation goes to server whisper (fallback: Android recognizer).
    // v0.67: default is flavor-dependent — personal true (owner runs whisper.cpp),
    // store false (a newcomer has no whisper backend; Android recognition works out of the box).
    var whisper = BuildConfig.DEF_WHISPER
    // v0.63: recording source for the whisper recorder. VOICE_COMMUNICATION (true) is what
    // v0.35 introduced — and it is the prime suspect for the headset button dying during
    // dictation, since it puts the headset into the conversational profile where the HFP
    // stack eats the key press. VOICE_RECOGNITION (false) records from the same headset mic
    // without claiming a phone call. A toggle, because only the field can decide this.
    var micVoiceComm = true
    // v0.64: treat the self-induced microphone route change as a headset button press.
    // Measured 14.08: during dictation the press never reaches the app as any event, but the
    // HFP stack tears SCO down — and THAT we do see. The trace is the only signal the button
    // will ever give us while SCO is up. Off by default: a false positive sends a half-spoken
    // message, and in this project that has always been the more expensive failure.
    var routeAsButton = false

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        url = p.getString("url", DEF_URL) ?: DEF_URL
        token = p.getString("token", "") ?: ""
        pin = p.getString("pin", DEF_PIN) ?: DEF_PIN
        handsFree = p.getBoolean("handsFree", false)
        autoSend = p.getBoolean("autoSend", true)
        btMic = p.getBoolean("btMic", true)
        onlyHeadset = p.getBoolean("onlyHeadset", false)
        autoRoute = p.getBoolean("autoRoute", true)
        announceProj = p.getBoolean("announceProj", true)
        catchup = p.getBoolean("catchup", true)
        speed = p.getFloat("speed", 1.0f)
        replyTarget = p.getString("replyTarget", "") ?: ""
        soloSid = p.getString("soloSid", "") ?: ""
        pickedSids = p.getString("pickedSids", "") ?: ""
        // миграция: включённый solo — это набор из одного канала
        if (pickedSids.isEmpty() && soloSid.isNotEmpty()) pickedSids = soloSid
        voiceCues = p.getBoolean("voiceCues", true)
        whisper = p.getBoolean("whisper", BuildConfig.DEF_WHISPER)
        micVoiceComm = p.getBoolean("micVoiceComm", true)
        routeAsButton = p.getBoolean("routeAsButton", false)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putString("url", url.trim().trimEnd('/'))
            .putString("token", token.trim())
            .putString("pin", pin.trim())
            .putBoolean("handsFree", handsFree)
            .putBoolean("autoSend", autoSend)
            .putBoolean("btMic", btMic)
            .putBoolean("onlyHeadset", onlyHeadset)
            .putBoolean("autoRoute", autoRoute)
            .putBoolean("announceProj", announceProj)
            .putBoolean("catchup", catchup)
            .putFloat("speed", speed)
            .putString("replyTarget", replyTarget)
            .putString("soloSid", soloSid)
            .putString("pickedSids", pickedSids)
            .putBoolean("voiceCues", voiceCues)
            .putBoolean("whisper", whisper)
            .putBoolean("micVoiceComm", micVoiceComm)
            .putBoolean("routeAsButton", routeAsButton)
            .apply()
    }
}

// Registry of sessions seen on the wire: who spoke, when, and whether the user
// muted them. Backing store is a small JSON blob in SharedPreferences.
object SessionBook {
    data class Entry(val sid: String, var proj: String, var lastTs: Long, var muted: Boolean)

    private val map = LinkedHashMap<String, Entry>()

    fun load(ctx: Context) {
        map.clear()
        try {
            val raw = ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE)
                .getString("sessions", "[]") ?: "[]"
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val e = Entry(o.getString("sid"), o.optString("proj"), o.optLong("lastTs"), o.optBoolean("muted"))
                map[e.sid] = e
            }
        } catch (_: Exception) {}
    }

    fun save(ctx: Context) {
        try {
            val arr = org.json.JSONArray()
            for (e in map.values) {
                arr.put(
                    org.json.JSONObject()
                        .put("sid", e.sid).put("proj", e.proj)
                        .put("lastTs", e.lastTs).put("muted", e.muted)
                )
            }
            ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit()
                .putString("sessions", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    @Synchronized
    fun seen(ctx: Context, sid: String, proj: String, ts: Long): Entry {
        // удалённый канал не воскрешаем старой историей — только новым ответом
        if (!map.containsKey(sid) && ts <= forgotTs(ctx, sid)) return Entry(sid, proj, ts, false)
        val e = map.getOrPut(sid) { Entry(sid, proj, ts, false) }
        if (proj.isNotEmpty()) e.proj = proj
        if (ts > e.lastTs) e.lastTs = ts
        while (map.size > 20) map.remove(map.keys.first())
        save(ctx)
        return e
    }

    fun isMuted(sid: String): Boolean = map[sid]?.muted == true

    // v0.82: канал можно убрать из списка совсем (тестовые/отработавшие «мозолят глаза»).
    // Момент удаления помним: релей отдаёт свою историю каждые 15с, и без этого строка
    // возвращалась бы сама. Канал вернётся, когда РЕАЛЬНО ответит что-то новое.
    fun forget(ctx: Context, sid: String) {
        map.remove(sid)
        ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit()
            .putLong("forgot_$sid", System.currentTimeMillis()).apply()
        save(ctx)
    }

    // v1.06 (слово юзера: «удалил лишние, а как теперь ВЕРНУТЬ канал в список?»):
    // снимаем все метки удаления — при ближайшем обновлении с сервера каналы вернутся.
    // v1.09: вернуть ОДИН канал (галочка в режиме правки списка)
    fun unforget(ctx: Context, sid: String) {
        try {
            ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit()
                .remove("forgot_$sid").apply()
        } catch (_: Exception) {}
    }

    fun isForgotten(ctx: Context, sid: String): Boolean = forgotTs(ctx, sid) > 0

    fun unforgetAll(ctx: Context) {
        try {
            val p = ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE)
            val ed = p.edit()
            for (k in p.all.keys) if (k.startsWith("forgot_")) ed.remove(k)
            ed.apply()
        } catch (_: Exception) {}
    }

    fun forgotTs(ctx: Context, sid: String): Long =
        ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE).getLong("forgot_$sid", 0L)
    fun all(): List<Entry> = map.values.sortedByDescending { it.lastTs }
    fun setMuted(ctx: Context, sid: String, m: Boolean) {
        map[sid]?.muted = m; save(ctx)
    }
}

// Tiny in-process log bus: service writes, activity renders.
object LogBus {
    private val lines = ArrayDeque<String>()
    @Volatile var listener: (() -> Unit)? = null

    @Synchronized
    fun add(s: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        lines.addLast("[$t] $s")
        while (lines.size > 200) lines.removeFirst()
        listener?.invoke()
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")
}
