package com.vladiko.voicebridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.TimeUnit

// The walkie-talkie core: holds a WebSocket to the relay (role=phone), plays
// incoming voice parts through the headphones (or speaker), then records the
// user's spoken reply and posts it back to the session that spoke last.
//
// Modes:
//  - BUTTON: headset media button (or on-screen button) starts the dictation
//    and sends it (next press, deferred 600ms so a double press can cancel).
//  - HANDSFREE: dictation starts by itself after playback and the microphone
//    stays hot; only the stop word sends, silence never does.
//
// Headset controls (Soundcore physical buttons, AirPods gestures):
//  PLAY_PAUSE / HEADSETHOOK  -> start dictation / send
//  NEXT  (double gesture)    -> cancel draft
//  PREVIOUS (triple gesture) -> replay the last message
class BridgeService : Service() {

    companion object {
        @Volatile var running = false
        @Volatile var instance: BridgeService? = null
        // v0.36: версия из BuildConfig — единый источник с gradle (versionName), конец рассинхрона
        val APP_VERSION: String = BuildConfig.VERSION_NAME
        const val CH = "bridge"
        val SEND_WORDS = if (Lang.en) listOf("send") else listOf("отправляй", "отправляйте")
        val CANCEL_WORDS = if (Lang.en) listOf("cancel") else listOf("отмена", "отменить")
        // store: отправка только когда ВЕСЬ сегмент — одна из этих фраз («I'll send» не шлёт).
        val EN_SEND_PHRASES = setOf("send", "send it", "send please", "send now")

        // v0.46: три НЕЗАВИСИМЫХ ограничителя вместо одного. Даже если откажут два первых,
        // сообщение всё равно уйдёт — это единственная безусловная страховка от обратной беды
        // («черновик висит вечно», болезнь v0.7 и «+4dB/60с», см. БАЛАНС в CLAUDE.md).
        const val HOT_MS = 200L            // сколько подряд громко, чтобы счесть это речью (щелчки мимо)
        const val HOT_WH_MS = 160          // то же для whisper-рекордера (8 кадров по 20мс)
        const val SOFT_CAP_MS = 30000L     // без НОВОГО ТЕКСТА (финал/рост догадки) — условный потолок
        const val HARD_CAP_MS = 90000L     // абсолютный потолок: не двигается ничем
        const val PARTIAL_FRESH_MS = 8000L // догадка старше МОМЕНТА ВЗВОДА отсчёта — протухла (авто-путь)
        const val PARTIAL_BTN_FRESH_MS = 20000L // ручная отправка кнопкой: окно шире авто-пути (как в v0.30)
        const val REARM_MAX = 2            // сколько раз бип может перевзвестись новым текстом
        const val SCO_WAIT_MAX = 10        // v0.47: попыток по 800мс (~8с) ждать микрофон гарнитуры, потом телефон
    }

    // v0.46: оценщик фона по МИНИМУМУ (minimum statistics). Кольцо из 8 подокон по 1.25с = окно 10с;
    // фон = ВТОРОЙ наименьший из заполненных (одиночный провал не должен занижать фон на 10 секунд).
    // Дыра в кадрах (рестарт распознавателя, бэкофф, конец озвучки) — полный сброс: старые подокна
    // протухли и врали бы. Пока не warm — голос не объявляем вообще, иначе первый же кадр, попавший
    // на речь или на собственную реплику «слушаю», задаст фон и детектор ослепнет с первой секунды.
    private class NoiseFloorTracker(
        private val subMs: Long = 1250L,
        private val subs: Int = 8,
        private val gapResetMs: Long = 3000L,
        private val warmupMs: Long = 700L,
    ) {
        private val mins = FloatArray(subs)
        private val filled = BooleanArray(subs)
        private var idx = 0
        private var subStart = 0L
        private var lastFeed = 0L
        private var startedAt = 0L
        var warm = false; private set
        var floor = 0f; private set

        fun reset() {
            for (i in 0 until subs) { mins[i] = 0f; filled[i] = false }
            idx = 0; subStart = 0L; lastFeed = 0L; startedAt = 0L
            warm = false; floor = 0f
        }

        fun feed(level: Float, tMs: Long): Float {
            if (lastFeed == 0L || tMs < lastFeed || tMs - lastFeed > gapResetMs) {
                reset()
                startedAt = tMs
                subStart = tMs
            }
            lastFeed = tMs
            var guard = 0
            while (tMs - subStart >= subMs && guard++ < subs) {
                subStart += subMs
                idx = (idx + 1) % subs
                filled[idx] = false
            }
            if (!filled[idx]) { mins[idx] = level; filled[idx] = true }
            else if (level < mins[idx]) mins[idx] = level
            var lo = Float.MAX_VALUE
            var lo2 = Float.MAX_VALUE
            var n = 0
            for (i in 0 until subs) if (filled[i]) {
                n++
                val v = mins[i]
                if (v < lo) { lo2 = lo; lo = v } else if (v < lo2) lo2 = v
            }
            floor = when {
                // v0.46-fix: одиночное тихое подокно не должно давать фону прыгнуть на уровень
                // речи (n=2→3 на 2.5с речи). Второй минимум ограничиваем сверху «минимум + 3 dB»:
                // защита от одиночного провала сохраняется, ослепление детектора — нет.
                n >= 3 && lo2 != Float.MAX_VALUE -> minOf(lo2, lo + 3f)
                n > 0 -> lo
                else -> level
            }
            warm = tMs - startedAt >= warmupMs
            return floor
        }
    }

    // Instance-scoped liveness: gates every callback that may fire after onDestroy.
    @Volatile private var alive = true

    private val main = Handler(Looper.getMainLooper())
    private lateinit var client: OkHttpClient      // websocket (readTimeout=0, ping)
    private lateinit var http: OkHttpClient        // audio download + /say (bounded timeouts)
    private var ws: WebSocket? = null
    private var wsUp = false
    private var announcedLink: Boolean? = null   // v0.26: чтобы не повторять «на связи» на каждый реконнект
    private var reconnectDelay = 3000L

    private val playQueue = ArrayDeque<JSONObject>()   // fresh events from relay
    // v0.49 (боевое, ДОКАЗАНО логами релея): при подключении релей отдаёт всё накопленное разом
    // (в замере — 5 частей длинного ответа). Раньше это уезжало прямо в playQueue и мгновенно
    // забирало микрофон: гейт полудуплекса не поднимает рекордер, пока очередь непуста, поэтому
    // юзер физически не мог заговорить сразу после включения рации — писалась тишина.
    // Теперь догон ждёт ЗДЕСЬ: очередь воспроизведения пуста, микрофон свободен, а накопленное
    // проигрывается по явной команде («слушать» / кнопка гарнитуры).
    private val heldQueue = ArrayDeque<JSONObject>()
    private val fileQueue = ArrayDeque<File>()         // local replay files
    private var player: MediaPlayer? = null
    @Volatile private var playing = false    // v0.36: читается из recorder-потока — volatile
    @Volatile private var paused = false   // v0.9 (слово юзера): пауза озвучки с продолжением с того же места
    private var pausedAtMs = 0   // v0.15: позиция, с которой продолжим (пауза = заглушение, не стоп)
    // v0.36 (аудит): поколение воспроизведения. Каждый стоп/потеря наушников его инкрементит;
    // отложенные продолжения (докачка части, routeWait-ретрай) захватывают своё поколение и при
    // несовпадении молча умирают. Лечит класс «зомби-озвучка стартует после стопа» — тот же
    // корень, что «полслова и обрыв» v0.10, только входы через асинхронные хвосты.
    private var playGen = 0
    @Volatile private var lastBtnTs = 0L   // v0.16: антидребезг кнопки гарнитуры
    private var btnLongFired = false       // v0.32: долгое нажатие уже отработало
    private var btnGhostPress = false      // v0.36: DOWN отброшен как дребезг — его UP тоже дребезг
    private var btnLongTimer: Runnable? = null
    private var currentFile: File? = null
    private var lastSession: String? = null
    private var currentSid: String? = null   // v0.12: сессия недоигранного сообщения — её части идут первыми
    private var lastProj: String? = null
    private var announcedProj: String? = null
    private var announcedMsgId: String? = null   // v0.76: имя канала — раз на сообщение
    private var batch = mutableListOf<File>()          // parts of the current message run
    private var lastBatch = listOf<File>()             // parts of the previous run (for replay)
    private var partSeq = 0
    // v0.42 (боевое «ответ из другой сессии перебивает мою диктовку»): части одного ответа
    // приходят с разрывом до минуты (TTS синтезирует по мере готовности). Раньше после
    // части 1/2 рация считала сообщение конченым, поднимала микрофон, юзер начинал говорить —
    // и тут доезжала часть 2/2 и перебивала. Теперь смотрим на «part/parts» события и ждём
    // недостающие части (потолок 60с), микрофон не поднимаем.
    private var lastPartNum = 1
    private var lastPartsTotal = 1
    private var partWaitDeadline = 0L
    private var lastEventTs = 0L                       // for hello-recent catch-up dedup

    private var recognizer: SpeechRecognizer? = null
    @Volatile private var listening = false   // v0.36: читается из recorder-потока — volatile
    private var draft = StringBuilder()
    private var pendingPress: Runnable? = null
    private var pendingSend = false
    private var autoSendTimer: Runnable? = null   // v0.5: авто-отправка по тишине (безрукий)
    @Volatile private var lastFinalTs = 0L        // v0.8: последняя реальная диктовка (для сна микрофона)
    @Volatile private var listenStartedAt = 0L    // v0.12: когда микрофон подняли (сон считать от НЕГО)
    @Volatile private var micLiveAt = 0L          // v1.43: когда запись реально пошла (0 — ещё ждём канал)
    private var listenIsResume = false            // v1.43: этот подъём — продолжение диктовки с паузы
    private var resumeRequested = false
    private var draftStartTs = 0L                 // v1.43: подъём, на котором начат текущий черновик
    @Volatile private var lastPlaybackTs = 0L     // v0.8: последняя озвучка
    // v0.35: диктовка через серверный Whisper — свой рекордер вместо SpeechRecognizer
    @Volatile private var recThread: Thread? = null
    @Volatile private var uttId = ""
    @Volatile private var uttSeq = 0
    @Volatile private var uttSpeechMs = 0
    @Volatile private var segLiveMs = 0   // v1.47: речь текущего, ещё не отгруженного сегмента (для skipWait)
    // v1.48 (боевое 16.10: «диктовал 2-3 минуты, влез ответ другой сессии, и отправилось туда»):
    // адресат открытой диктовки фиксируется первым отгруженным куском. Озвучка посреди неё
    // двигает lastSession — без этой фиксации остаток монолога и «отправляй» уезжали к говорившему.
    @Volatile private var dictSid = ""
    @Volatile private var dictSidUtt = ""
    // v0.46 (ревью): абсолютный предел whisper-черновика меряется ЗАПИСАННЫМ звуком и живёт
    // вместе с utt, а не с одним проходом рекордера — тот умирает на КАЖДОЙ прилетевшей озвучке
    // (playNext → stopListening), и локальный «абсолютный» предел не наступал никогда.
    @Volatile private var whArmedMs = 0L
    @Volatile private var wFloor = 0f
    @Volatile private var finalizeReq = false
    @Volatile private var cancelReq = false
    @Volatile private var rotateReq = false
    @Volatile private var lastPartial = ""     // v0.30: последняя догадка распознавателя (страховка)
    @Volatile private var lastPartialTs = 0L
    @Volatile private var lastLoudTs = 0L         // v0.7: момент последней ГРОМКОЙ речи у микрофона (RMS)
    // v0.11 (боевое, улица: «отправляет, хотя я продолжаю говорить»): фиксированный порог 7dB
    // работал в квартире и слеп на улице — ветер поднимает фон, голос перестаёт его превышать.
    // Считаем СКОЛЬЗЯЩИЙ фон и голосом зовём только то, что явно громче фона: порог сам
    // подстраивается под улицу и под комнату.
    @Volatile private var noiseFloor = 0f
    @Volatile private var lastVoiceTs = 0L        // момент последней речи ОТНОСИТЕЛЬНО фона

    // v0.46 (боевое, слово юзера: «в шумном месте я продолжаю говорить, а он — пик, и ушло»).
    // Корень найден чтением кода и подтверждён тремя независимыми разборами: скользящее СРЕДНЕЕ
    // фона (noiseFloor/wFloor) обновлялось на КАЖДОМ замере, включая замеры самой речи. Постоянная
    // времени ~3.3с — значит через несколько секунд непрерывного говорения «фон» подтягивался к
    // уровню голоса, разница падала ниже порога, и детектор переставал видеть говорящего человека.
    // Дома это не всплывало не из-за запаса громкости (зависимость логарифмическая, слепнет и там),
    // а потому что отсчёт заводил каждый распознанный ФИНАЛ. В шуме финалов нет — и оба канала
    // («текст» и «громкость») отказывают одновременно. Отсюда выстрел посреди мысли.
    //
    // Лечение — НЕ трогая пороги (5.5 dB и +6 dB остаются байт-в-байт): фон считаем по МИНИМУМУ
    // за скользящее окно. Минимум обновляется безусловно, без гейта «речь/не речь», поэтому
    // положительной обратной связи нет и залипнуть он не может; речь его не отравляет, потому что
    // между слогами и фразами уровень падает к фону. Трекер минимума по построению даёт значение
    // НЕ ВЫШЕ прежнего среднего — значит новый детектор математически не может стать ГЛУШЕ
    // сегодняшнего. Маятник «чутко/глухо» из раздела БАЛАНС (ломали дважды) здесь не качается.
    private val floorSr = NoiseFloorTracker()
    @Volatile private var lastActivityTs = 0L   // ЛЮБОЙ признак речи: громкость ИЛИ финал ИЛИ рост догадки
    @Volatile private var lastTextTs = 0L       // только текстовые признаки — двигают потолок 30с
    private var firstArmTs = 0L                 // абсолютный потолок: не двигается НИЧЕМ
    private var lastPartialMaxLen = 0           // храповик длины догадки (рост = новая речь)
    private var prevPartial = ""                // антифликер А/Б/А у распознавателя
    @Volatile private var ttsSpeakStartTs = 0L  // страховка от залипшего ttsSpeaking (у него нет таймаута)
    private var hotMs = 0L                      // подтверждение речи длительностью (щелчки не в счёт)
    private var lastRmsTs = 0L
    private var sendRearms = 0                  // перевзводы отсчёта после бипа (кап REARM_MAX)
    private var beepAtTs = 0L
    private var lastSensorLogTs = 0L
    // диагностика датчика: окно счётчиков, сбрасывается печатью строки «датчик:»
    private var rmsFrames = 0
    private var rmsVoiceFrames = 0
    private var gatedFrames = 0                 // v0.46: кадры RMS, отброшенные эхо-гейтом/озвучкой
    @Volatile private var recActive = false     // v0.46: сессия распознавателя реально запущена (не бэкофф/не очередь)
    private var rmsMin = Float.MAX_VALUE
    private var rmsMax = -Float.MAX_VALUE
    private var partialsSeen = 0
    private var partialsNew = 0
    private var partialsGrow = 0
    private var beepBoth: (Int, Int) -> Unit = { _, _ -> }   // v0.5.2: бип и в MUSIC, и в SCO-канал
    private var retryDelay = 300L
    private var clientErrStreak = 0   // v0.5.5: подряд ошибок ERROR_CLIENT (код 5) без живого финала
    private var scoStarted = false
    @Volatile private var scoStartedAt = 0L   // v0.39: маршрут SCO устаканивается ~1с — реплики ждут его
    private var scoWaitCount = 0      // v0.36: сколько циклов ждём SCO — чтобы отказ не был немым
    @Volatile private var micTypeDegraded = false  // v0.36: система не дала FGS-тип microphone из фона
    private val unsent = ArrayDeque<String>()      // v0.36: неотправленные из-за сети сообщения — дошлём при связи
    @Volatile private var pendingUttAck = ""       // v0.36: ждём utt_sent/utt_empty от сервера (сторож)
    private var uttAckTimer: Runnable? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    // v0.37 (боевое «ушло-ушло-ушло по кругу»): эхо СОБСТВЕННЫХ реплик в SCO-микрофон
    // считалось речью, рождало сегменты, сервер их финализировал → новое «ушло» → новое эхо.
    // Пока TTS говорит (и 700мс после) — кадры рекордера не речь и в сегмент не пишутся.
    @Volatile private var ttsSpeaking = false
    @Volatile private var lastTtsEndTs = 0L
    @Volatile private var announceProceed: Runnable? = null
    @Volatile private var announceId: String? = null
    private val pendingSynth = AtomicInteger(0)
    private val announceSeq = AtomicInteger(0)
    @Volatile private var userActionTs = 0L   // v0.37: последнее осознанное действие юзера
    private var lastUnreadCueTs = 0L          // v0.37: не пилить «сессия не слушает» чаще раза в минуту
    private var lastEmptyCueTs = 0L           // v0.37: и «ничего не расслышал» тоже
    @Volatile private var dictHold = false    // v0.37: диктовка остановлена плашкой, черновик ждёт
    @Volatile private var micAsleep = false   // v0.50: микрофон спит ПОСЛЕ ОТПРАВКИ (не ручной mute)

    private lateinit var tones: ToneGenerator
    private lateinit var audioManager: AudioManager
    private var focusReq: AudioFocusRequest? = null
    private var mediaSession: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        main.post {
            if (!alive) return@post
            when (change) {
                // v0.10: CAN_DUCK — это просьба играть тише, НЕ пауза. Раньше мы на неё паузили,
                // и любой короткий звук системы (в т.ч. наш же TTS «ушло») рубил озвучку.
                // v0.36 (аудит+ревью): LOSS и LOSS_TRANSIENT разведены. После ПОСТОЯННОЙ потери
                // (юзер включил голосовое в Telegram / свой плеер) GAIN не приходит НИКОГДА —
                // прежний код оставлял playing=true навсегда: очередь мертва, плашка врёт.
                // Теперь LOSS = штатная пауза с мёртвым плеером: позиция и часть сохранены
                // (в т.ч. поверх ручной паузы), отложенные докачки/ретраи уводятся паузным
                // гейтом playFile в очередь, а не в мусор. Снимет кнопка/наушники/сторож 90с.
                AudioManager.AUDIOFOCUS_LOSS -> {
                    if (!playing && !paused) return@post
                    LogBus.add(tr("звук забрало другое приложение — пауза (кнопка продолжит, сам — через 90с)", "audio taken by another app — paused (button resumes, auto-resume in 90s)"))
                    val pos = if (paused) pausedAtMs else if (cueHold) cueHoldAtMs else (try { player?.currentPosition ?: 0 } catch (_: Exception) { 0 })
                    cueHold = false
                    try { player?.stop() } catch (_: Exception) {}
                    try { player?.release() } catch (_: Exception) {}
                    player = null
                    pausedAtMs = pos
                    playing = false
                    paused = true
                    main.removeCallbacks(unpauseGuard)
                    main.postDelayed(unpauseGuard, 90000)
                    abandonFocus()
                    updateNotification()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    if (playing) LogBus.add(tr("аудиофокус отобран на время (звонок?) — жду", "audio focus taken temporarily (call?) — waiting"))
                    try { player?.let { if (it.isPlaying) it.pause() } } catch (_: Exception) {}
                }
                AudioManager.AUDIOFOCUS_GAIN ->
                    try { player?.let { if (!it.isPlaying && playing) it.start() } } catch (_: Exception) {}
            }
        }
    }

    // v0.61 — ДИАГНОСТИКА к «кнопка на отправку не срабатывает» (это НЕ лечение, а замер).
    // Во время диктовки SCO поднят, и по коду AOSP (HeadsetStateMachine.processKeyPressed)
    // нажатие кнопки гарнитуры в этом состоянии приложению не отдаётся ВООБЩЕ: при поднятом
    // SCO и отсутствии телефонного звонка HFP-стек просто РВЁТ SCO. Отсюда проверяемое
    // следствие: в момент нажатия маршрут микрофона сменится САМ. Появится строка — путь
    // «медиакнопка» мёртв окончательно (лечится только самоуправляемым звонком через Telecom);
    // не появится — гарнитура глотает нажатие у себя, и в телефон не приходит ничего.
    private var commListener: Any? = null
    // v0.64: один «удар» на одну диктовку — маршрут может дёрнуться и по другим причинам,
    // а два подряд означали бы отправку пустого черновика следом за настоящей.
    @Volatile private var routeBtnFired = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            main.post {
                if (!alive || !headsetPresent()) return@post
                // v0.36: наушники вернулись — снимаем «паузу потери наушников» сами
                if (paused && !playing) {
                    LogBus.add(tr("наушники вернулись — продолжаю с места паузы", "headphones back — resuming from the pause point"))
                    resumeIfPaused()
                    return@post
                }
                if (!playing && (playQueue.isNotEmpty() || fileQueue.isNotEmpty())) {
                    LogBus.add(tr("наушники подключены — продолжаю", "headphones connected — resuming"))
                    kickPlayback()
                }
                // v0.36: гарнитура вернулась посреди диктовки — вернуть ей микрофон
                // (setCommunicationDevice перекидывает живой VOICE_COMMUNICATION-поток)
                if (listening && Cfg.btMic && !scoStarted) {
                    LogBus.add(tr("гарнитура вернулась — микрофон снова её", "headset back — microphone is on it again"))
                    startHeadsetMic()
                }
            }
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            main.post { if (alive && !headsetPresent()) onHeadsetLost() }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                main.post { if (alive) onHeadsetLost() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Cfg.load(this)
        // v0.33 (директива юзера: «микрофон телефона не используется вообще»): у него остался
        // включён телефонный микрофон с экспериментов v0.28 — из-за этого «ничего не расслышал»
        // при телефоне в кармане. Принудительно возвращаем гарнитуру и держим так.
        if (!Cfg.btMic) {
            Cfg.btMic = true
            Cfg.save(this)
            LogBus.add(tr("микрофон принудительно переключён на гарнитуру", "microphone forced onto the headset"))
        }
        SessionBook.load(this)
        client = Net.client(Cfg.pin)
        http = client.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
        tones = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
        // v0.5.2 (боевой фидбек «не слышу ни одного писка»): при активном SCO-микрофоне STREAM_MUSIC
        // не маршрутизируется в гарнитуру — бипы звучали в никуда. Дублируем каждый бип в голосовой
        // канал (STREAM_VOICE_CALL), когда SCO включён: одноразовый генератор, релиз после сигнала.
        beepBoth = { tone, ms ->
            try { tones.startTone(tone, ms) } catch (_: Exception) {}
            if (scoStarted) {
                try {
                    val tg = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 90)
                    tg.startTone(tone, ms)
                    main.postDelayed({ try { tg.release() } catch (_: Exception) {} }, ms + 150L)
                } catch (_: Exception) {}
            }
        }
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioDeviceCallback(deviceCallback, main)
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                val l = AudioManager.OnCommunicationDeviceChangedListener { dev ->
                    val t = dev?.type ?: -1
                    if (listening && scoStarted && t != AudioDeviceInfo.TYPE_BLUETOOTH_SCO && t != 26) {
                        LogBus.add(tr("маршрут микрофона сменился САМ (тип $t) — если это было нажатие, гарнитура/HFP рвёт SCO", "microphone route changed ON ITS OWN (type $t) — if this was a press, the headset/HFP is dropping SCO"))
                        // v0.64 — ЗАМЕР 14.08 ДАЛ КАНАЛ ТАМ, ГДЕ ЕГО НЕ ИСКАЛИ. Скрин журнала
                        // 19:28:31: нажатие во время диктовки не приходит НИКАКИМ событием, но
                        // системный слой в этот момент рвёт SCO, и маршрут микрофона уезжает на
                        // телефон (тип 1). Само нажатие мы поймать не можем — а вот его СЛЕД
                        // ловим уже сейчас. Значит след и есть кнопка: другого сигнала от неё не
                        // будет никогда. Заодно это объясняет, почему у юзера иногда «срабатывало»
                        // со второго раза: первое нажатие убивало SCO, а второе прилетало обычной
                        // медиакнопкой — рвать уже было нечего.
                        // Ложное срабатывание здесь дороже пропуска (урок проекта), поэтому:
                        // тумблер по умолчанию ВЫКЛЮЧЕН, и обязательна проверка, что гарнитура
                        // на месте — если она пропала, это отвал наушников, а не нажатие.
                        if (Cfg.routeAsButton && headsetPresent() && !routeBtnFired) {
                            routeBtnFired = true
                            LogBus.add(tr("считаю это нажатием кнопки — отправляю", "treating this as a button press — sending"))
                            sendNow()
                        }
                    }
                }
                audioManager.addOnCommunicationDeviceChangedListener(mainExecutor, l)
                commListener = l
            } catch (_: Exception) {}
        }
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        val prefs = getSharedPreferences("bridge", MODE_PRIVATE)
        lastEventTs = prefs.getLong("lastPlayedTs", 0L)
        lastSession = prefs.getString("lastSession", null)
        // штамп ручного выбора живёт в памяти и умирает с сервисом (а рестарт бывает от
        // сохранения настроек до OEM-киллера) — даём восстановленному закреплению те же 3 минуты
        if (Cfg.replyTarget.isNotEmpty()) manualPinTs = System.currentTimeMillis()
        lastProj = prefs.getString("lastProj", null)
        setupMediaSession()
        setupTts()
        if (Cfg.voiceOff) main.postDelayed({ if (alive && Cfg.voiceOff) startSilentKeepalive() }, 1500)   // v1.28: кнопка гарнитуры в тихом режиме
        // v0.32 (исследование: в SCO-режиме гарнитуры шлют кнопку HFP AT-командой — CKPD/PTT, —
        // и до медиасессии она не доходит В ПРИНЦИПЕ; так её ловят PTT-рации): второй канал.
        try {
            val f = IntentFilter(android.bluetooth.BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT)
            for (id in 0..2047) f.addCategory(
                android.bluetooth.BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY + "." + id
            )
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(vendorBtnReceiver, f, RECEIVER_EXPORTED)
            else registerReceiver(vendorBtnReceiver, f)
        } catch (e: Exception) { LogBus.add(tr("hfp-канал кнопки: ${e.message}", "hfp button channel: ${e.message}")) }
    }

    // Кнопка гарнитуры, пришедшая телефонным (HFP) путём во время диктовки.
    private val vendorBtnReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val cmd = i?.getStringExtra(
                android.bluetooth.BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD
            ) ?: return
            val args = try {
                @Suppress("DEPRECATION")
                (i.getSerializableExtra(android.bluetooth.BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS) as? Array<*>)
                    ?.joinToString(",") ?: ""
            } catch (_: Exception) { "" }
            main.post {
                if (!alive) return@post
                LogBus.add(tr("кнопка (HFP-канал): $cmd $args", "button (HFP channel): $cmd $args"))
                if (cmd.contains("CKPD", true) || cmd.contains("PTT", true)) {
                    val now = System.currentTimeMillis()
                    if (now - lastBtnTs < 150) return@post
                    lastBtnTs = now
                    if (paused && playing) resumeIfPaused() else onButton()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // v0.12: кнопка гарнитуры может прилететь сюда интентом — отдать её медиасессии
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            mediaSession?.let { androidx.media.session.MediaButtonReceiver.handleIntent(it, intent) }
        }
        val firstStart = !running
        startInForeground()
        running = true
        acquireWakeLock()
        if (ws == null || !wsUp) connect()
        // v0.36: строка и сторож — один раз, а не на каждый интент (каждый повторный
        // onStartCommand добавлял ПАРАЛЛЕЛЬНУЮ вечную цепочку micSleepCheck)
        if (firstStart) {
            LogBus.add(tr("сервис запущен (Claudio Code $APP_VERSION), подключаюсь к ${Cfg.url}", "service started (Claudio Code $APP_VERSION), connecting to ${Cfg.url}"))
            LogBus.add(tr("медиасессия активна=${mediaSession?.isActive == true} — кнопки должны идти сюда", "media session active=${mediaSession?.isActive == true} — buttons should arrive here"))
        }
        main.removeCallbacks(micSleepCheck)
        main.postDelayed(micSleepCheck, 30000)   // v0.8: сторож сна микрофона
        main.removeCallbacks(fastGrab)
        main.postDelayed(fastGrab, 4000)          // v0.44: частый перехват кнопок
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        running = false
        instance = null
        main.removeCallbacksAndMessages(null)
        pendingPress = null
        pendingSend = false
        stopListening()
        recognizer?.destroy()
        ws?.cancel()
        ws = null
        try { player?.release() } catch (_: Exception) {}
        player = null
        try { cuePlayer?.release() } catch (_: Exception) {}
        cuePlayer = null
        stopSilentKeepalive()   // v0.51: беззвучный keepalive переживал смерть сервиса и крутился вечно
        abandonFocus()
        mediaSession?.release()
        tts?.shutdown()
        try { audioManager.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                (commListener as? AudioManager.OnCommunicationDeviceChangedListener)?.let {
                    audioManager.removeOnCommunicationDeviceChangedListener(it)
                }
            } catch (_: Exception) {}
        }
        commListener = null
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(vendorBtnReceiver) } catch (_: Exception) {}
        try { tones.release() } catch (_: Exception) {}   // v0.36: тёк нативный AudioTrack на каждый цикл сервиса
        wakeLock?.let { if (it.isHeld) it.release() }
        LogBus.add(tr("сервис остановлен", "service stopped"))
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voicebridge:svc")
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire()
        } catch (e: Exception) {
            LogBus.add("wakelock: ${e.message}")
        }
    }

    // v0.26 (слово юзера: «сделай, чтобы я звуком дополнительно понимал»): вибро + короткая
    // реплика голосом. Вибро выбрано главным каналом сознательно: оно НЕ зависит от аудиомаршрута,
    // а бипы через SCO-канал гарнитуры доказанно теряются (боевой урок v0.5.2/0.5.4).
    private fun buzz(ms: Long) {
        try {
            val vib = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
            if (Build.VERSION.SDK_INT >= 26) vib?.vibrate(android.os.VibrationEffect.createOneShot(ms, 200))
            else @Suppress("DEPRECATION") vib?.vibrate(ms)
        } catch (_: Exception) {}
    }

    // v0.38 (боевое «громкость команд упала ещё сильнее»): живой TTS в STREAM_VOICE_CALL вне
    // звонка на многих прошивках уходит в РАЗГОВОРНЫЙ ДИНАМИК ТЕЛЕФОНА, а не в гарнитуру —
    // v0.37 сделал хуже. Правильный путь: реплики синтезируются В ФАЙЛЫ один раз (prewarm при
    // старте TTS), и играются MediaPlayer'ом с USAGE_VOICE_COMMUNICATION при активном SCO
    // (маршрутизируется в гарнитуру как звонок) или USAGE_MEDIA без SCO. Это тот же механизм,
    // каким играют подсказки VoIP-приложения.
    // v0.51: карта пополняется и с главного потока (ensureCue на смену адресата), и с потока
    // инициализации TTS (prewarmCues) — обычный HashMap здесь стал бы гонкой на ровном месте.
    private val cueFiles = java.util.concurrent.ConcurrentHashMap<String, File>()
    private var cuePlayer: MediaPlayer? = null
    private val CUE_PHRASES = listOf(
        tr("рация на связи", "radio online"), tr("связь потеряна", "connection lost"), tr("слушаю", "listening"), tr("ушло", "sent"), tr("отменено", "cancelled"), tr("стоп", "stop"), tr("пауза", "paused"),
        tr("микрофон выключен", "microphone off"), tr("ничего не расслышал", "didn't catch anything"), tr("гарнитура не отвечает", "headset not responding"), tr("есть ответы", "replies waiting"),
        tr("не знаю, кому отправить", "no one to send to"), tr("распознавание молчит", "recognition is silent"), tr("канал не слушает", "channel is not listening"),
        tr("не ушло, отправлю при связи", "not sent, will send when back online"), tr("адресат сброшен, повтори", "recipient reset, say it again"),
        // v0.94: фразы, появившиеся в v0.73-0.93, тоже должны звучать ГРОМКО с первого раза
        tr("есть ответы — нажми слушать", "replies waiting — press listen"), tr("отбой", "discarded"), tr("отбой, стёрто", "discarded, erased"), tr("надень наушники", "put on your headphones"),
        tr("не могу играть", "can't play"), tr("отправляю начатое, потом переключусь", "sending what you started, then switching"), tr("сначала отправь начатое", "send what you started first"),
        tr("слушаю все каналы", "listening to all channels")
    )

    private fun prewarmCues() {
        val t = tts ?: return
        try {
            t.setSpeechRate(0.95f)
            for (p in CUE_PHRASES) {
                val f = File(cacheDir, "cue_" + Math.abs(p.hashCode()) + ".wav")
                cueFiles[p] = f
                if (!f.exists() || f.length() < 200) {
                    pendingSynth.incrementAndGet()
                    t.synthesizeToFile(p, Bundle(), f, "cuefile_$p")
                }
            }
            // v0.51: имя текущего адресата — сразу после рестарта. Через main: listenCuePhrase
            // читает SessionBook, а он не потокобезопасен и живёт на главном потоке.
            main.post { if (alive) ensureTargetCues() }
        } catch (e: Exception) { LogBus.add(tr("prewarm реплик: ${e.message?.take(40)}", "cue prewarm: ${e.message?.take(40)}")) }
    }

    // v0.46: единый эхо-гейт для ДАТЧИКА РЕЧИ (не путать с гейтом рекордера в whisperLoop).
    // Реплики играет cuePlayer, который `playing` не ставит, — без этого гейта наше же «слушаю»
    // и «ушло» считаются речью юзера и накачивают фон. Кап 8с: у ttsSpeaking нет ни одного
    // таймаута во всём файле, и не пришедший onDone залипшим флагом убил бы датчик навсегда.
    private fun ttsGateActive(): Boolean {
        val now = System.currentTimeMillis()
        return (ttsSpeaking && now - ttsSpeakStartTs < 8000) || now - lastTtsEndTs < 700
    }

    // v0.51 (боевое, найдено в логах релея 26.07: голосовые уезжали то в одну сессию, то в
    // другую, юзер этого не видел и не слышал — на ходу экран не смотрят): имя адресата
    // должно ЗВУЧАТЬ. Реплика с именем проекта готовится заранее — как только адресат сменился,
    // то есть за секунды до подъёма микрофона; к моменту «слушаю» файл уже лежит в кэше.
    // Возвращает false, только если синтез был нужен, но движок его не принял.
    private fun ensureCue(phrase: String): Boolean {
        val t = tts ?: return false
        if (!ttsReady || phrase.isEmpty()) return false
        val f = cueFiles[phrase]
        if (f != null && f.exists() && f.length() > 200) return true
        val nf = File(cacheDir, "cue_" + Math.abs(phrase.hashCode()) + ".wav")
        cueFiles[phrase] = nf
        if (!nf.exists() || nf.length() < 200) {
            // v1.30 (ревью): отказ synthesizeToFile (ERROR или исключение) приходит без onDone/
            // onError — без отката pendingSynth залипал бы > 0, и каждое объявление шло бы с QUEUE_FLUSH
            pendingSynth.incrementAndGet()
            val ok = try {
                t.setSpeechRate(0.95f)
                t.synthesizeToFile(phrase, Bundle(), nf, "cuefile_$phrase") == TextToSpeech.SUCCESS
            } catch (_: Exception) { false }
            if (!ok) { pendingSynth.decrementAndGet(); return false }
        }
        return true
    }

    private var lastAnnouncedTarget: String? = null   // v0.51: имя адресата называем на СМЕНУ
    // v0.76/v0.85: ручной выбор адресата (кнопка «ответить» или голосовая рубка) действует
    // НА ОДНО СООБЩЕНИЕ — до ближайшей отправки, и не дольше трёх минут. Дальше снова
    // работает простое правило: отвечаю тому, кто говорил последним. Слово юзера: «я выбрал
    // каналы в работу, между ними приоритет закреплять не надо».
    private var manualPinTs = 0L
    // v1.44 (ревью v1.43): счётчик ЯВНЫХ смен адресата (голосом, кнопкой, «ответ в крайнюю»).
    // Экран сверяет его и снимает выбор «смотрю X»: лента идёт за новым адресатом. Смены
    // по последнему говорившему его не трогают — ленту не выдёргивают из-под чтения.
    @Volatile var targetEpoch = 0
    // v1.16-fix (боевое, повторялось: «играет ответ Y, я на экране перехожу в X, озвучка кончилась,
    // диктую, „отправляй“ — ушло в Y»). Три минуты считались от ТАПА, а длинный ответ Y
    // (несколько частей, ожидание хвоста до 60с) играет дольше: к подъёму микрофона штамп
    // протухал, и правило «последний говоривший» возвращало Y. Теперь выбор, сделанный ПОСЛЕ
    // того, как заговорил последний говоривший, держится без срока — до отправки в него или до
    // нового сообщения (новое сообщение = новый «последний говоривший», дальше снова три минуты).
    // MAX до первой озвучки: восстановленное после рестарта закрепление выбором не считается.
    private var speakerStartTs = Long.MAX_VALUE
    private var replaySelSeen = 0   // v1.46: номер переслушанной выборки — штамп говорившего ставим раз на выборку
    // v1.46: состояние «последнего говорившего» до переслушивания — откат, если звук выборки весь пропал.
    // v1.47 (ревью): без speakerStartTs/answeredCurrent/lastProj откат оставлял штамп «сейчас» —
    // manualPinHolds() ложно, и ручной выбор юзера терялся на ближайшем подъёме микрофона.
    private class ReplayPrev(val session: String?, val target: String, val pinTs: Long,
                             val speakerTs: Long, val answered: Boolean, val proj: String?)
    private var replayPrev: ReplayPrev? = null

    // v1.46 (слово юзера: «адресат — тот, чью озвучку я слышал ПОСЛЕДНЕЙ»). Трёхминутная льгота
    // снята: старый выбор X побеждал Y, который заговорил ПОСЛЕ выбора. Теперь ручной выбор держится
    // до ближайшей новой озвучки (любой сессии, включая переслушанное) или до отправки в него.
    // Три минуты остались только восстановленному после рестарта закреплению — до первой озвучки.
    private fun manualPinHolds(): Boolean =
        manualPinTs > 0L && (manualPinTs >= speakerStartTs ||
            (speakerStartTs == Long.MAX_VALUE && System.currentTimeMillis() - manualPinTs < 180000))
    // v0.54: фразы, которые надо сыграть, КАК ТОЛЬКО допишется их файл (синтез асинхронный).
    // Живой TTS на музыкальном канале звучит тише файла — на улице юзер его не слышит (урок v0.38).
    private val cuePlayWhenReady: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    // v1.30: объявление имени с QUEUE_FLUSH обрывает синтез ЖДАННОЙ фразы — раньше метка
    // снималась в onStop и фраза пропадала совсем (страховка cue() видела пустую метку и молчала).
    // Теперь один повтор синтеза; второй обрыв — метку снимаем, без петли.
    private val cueResynthOnce: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    // Имена проектов приходят слагами (voicebridge_app) — озвучка читает подчёркивания вслух.
    private fun spoken(n: String) = n.replace('_', ' ').replace('-', ' ')

    // Произнести произвольную фразу громким путём: есть файл — играем, нет — синтезируем
    // и играем по готовности (см. onDone у cuefile_).
    private fun say(phrase: String) {
        if (phrase.isEmpty()) return
        if (!Cfg.voiceCues || !ttsReady || Cfg.voiceOff) { LogBus.add(tr("реплики выключены, не сказал: $phrase", "cues are off, did not say: $phrase")); return }
        fun ready(f: File?) = f != null && f.exists() && f.length() > 200 &&
            System.currentTimeMillis() - f.lastModified() > 1200
        if (ready(cueFiles[phrase])) { playCueFile(cueFiles[phrase]!!); return }
        cuePlayWhenReady.add(phrase)
        ensureCue(phrase)
        // v0.55 (ревью): файл мог остаться в кэше от ПРОШЛОГО запуска — тогда ensureCue ничего
        // не синтезирует, onDone не придёт, и фраза не прозвучит никогда («первый раз молчит»).
        if (ready(cueFiles[phrase]) && cuePlayWhenReady.remove(phrase)) playCueFile(cueFiles[phrase]!!)
        // v1.17-fix: файл существует, но слишком свежий (< 1.2с) — ready() ложный, ensureCue()
        // не пересинтезирует существующий файл, onDone не придёт, метка cuePlayWhenReady висит
        // вечно → фраза молчит, и через kickPlayback блокирует следующие до 3с. Ждём дозревания.
        else if (cuePlayWhenReady.contains(phrase)) {
            val cf = cueFiles[phrase]
            if (cf != null && cf.exists() && cf.length() > 200) {
                val age = System.currentTimeMillis() - cf.lastModified()
                val wait = maxOf(1300 - age, 200)
                fun tryPlay(attempt: Int) {
                    main.postDelayed({
                        if (!cuePlayWhenReady.contains(phrase)) return@postDelayed
                        val f2 = cueFiles[phrase]
                        if (f2 != null && ready(f2)) {
                            cuePendingUntil = System.currentTimeMillis() + 300
                            if (cuePlayWhenReady.remove(phrase)) playCueFile(f2)
                        } else if (attempt < 3) {
                            tryPlay(attempt + 1)
                        } else {
                            cuePlayWhenReady.remove(phrase)
                            LogBus.add(tr("фраза «$phrase» не дозрела после 3 попыток — пропускаю", "phrase “$phrase” not ready after 3 attempts — skipping"))
                        }
                    }, if (attempt == 1) wait else 500)
                }
                tryPlay(1)
            }
        }
    }

    // ── v0.54: голосовая рубка (слово юзера: «по номерам, и чтобы сначала зачитал, что есть») ──
    // Список — только свежие сессии, не больше четырёх: у юзера их бывает десять, и зачитывать
    // все было бы бесполезно («озвучивание на 15 минут списка сессий тоже без толку»).
    private var cmdList: List<Pair<String, String>> = emptyList()
    private var cmdListTs = 0L

    private fun freshSessions(): List<Pair<String, String>> =
        SessionBook.all().sortedByDescending { it.lastTs }.take(4)
            .map { it.sid to it.proj.ifEmpty { it.sid.take(4) } }

    private fun speakSessionList() {
        val l = freshSessions()
        if (l.isEmpty()) { say(tr("сессий пока нет", "no sessions yet")); return }
        cmdList = l
        cmdListTs = System.currentTimeMillis()
        val ord = listOf(tr("первая", "first"), tr("вторая", "second"), tr("третья", "third"), tr("четвёртая", "fourth"))
        LogBus.add(tr("рубка: ", "switchboard: ") + l.mapIndexed { i, p -> "${i + 1}) ${p.second}" }.joinToString(", "))
        say(l.mapIndexed { i, p -> "${ord[i]}, ${spoken(p.second)}" }.joinToString(". "))
    }

    // v0.59: сопоставление СКАЗАННОГО имени со списком сессий. Имена проектов латиницей
    // («brainstorm»), а юзер произносит их по-русски, и whisper коверкает: «бренштурм»,
    // «бренч-торм», «войсбридж». Поэтому: кириллицу переводим в латиницу по звучанию и
    // сравниваем расстоянием Левенштейна с допуском в половину длины — а вслух рация всё
    // равно подтверждает выбор, так что промах слышен сразу и стоит одну команду.
    private fun translit(x: String): String {
        val m = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
            'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m",
            'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
            'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch",
            'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya"
        )
        val sb = StringBuilder()
        for (c in x.lowercase()) {
            val t = m[c]
            when {
                t != null -> sb.append(t)
                c.isLetterOrDigit() -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun lev(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private fun matchSessionByName(spoken0: String): Pair<String, String>? {
        // Пробуем и всю фразу, и КАЖДОЕ слово по отдельности: whisper коверкает служебные
        // слова тоже («сессию» → «сетсию»), и мусорное слово рядом с именем портило бы счёт.
        val parts = (listOf(spoken0) + spoken0.split(' ')).map { translit(it) }.filter { it.length >= 3 }
        if (parts.isEmpty()) return null
        var best: Pair<String, String>? = null
        var bestScore = 1.0
        for (e in SessionBook.all().sortedByDescending { it.lastTs }.take(10)) {
            val cand = translit(e.proj.ifEmpty { e.sid })
            if (cand.isEmpty()) continue
            for (said in parts) {
                // сравниваем и целиком, и по общему префиксу той же длины: «бренштурм» против
                // «brainstorm» даёт заметное расстояние на хвосте, а начало совпадает хорошо
                val head = minOf(said.length, cand.length)
                val d = minOf(
                    lev(said, cand).toDouble() / maxOf(said.length, cand.length),
                    lev(said.take(head), cand.take(head)).toDouble() / head
                )
                if (d < bestScore) { bestScore = d; best = e.sid to e.proj.ifEmpty { e.sid.take(4) } }
            }
        }
        return if (bestScore <= 0.5) best else null
    }

    private fun switchByName(said: String) {
        val hit = matchSessionByName(said)
        if (hit == null) {
            LogBus.add(tr("рубка: не узнал имя «$said» — читаю список", "switchboard: did not recognize name “$said” — reading the list"))
            say(tr("не понял, какую сессию", "didn't catch which session"))
            main.postDelayed({ if (alive) speakSessionList() }, 2500)
            return
        }
        LogBus.add(tr("рубка: «$said» → «${hit.second}»", "switchboard: “$said” → “${hit.second}”"))
        // через единую точку: если сейчас идёт диктовка, начатое уедет СТАРОМУ адресату,
        // а переключение применится после подтверждения (v0.78)
        setReplyTargetManual(hit.first)
        lastAnnouncedTarget = hit.first
    }

    private fun switchToSession(n: Int) {
        // Порядок берём ТОТ ЖЕ, что был озвучен (если он свежий): иначе между «какие сессии»
        // и «переключись на вторую» чужой ответ переставит список, и юзер уедет не туда.
        val l = if (cmdList.isNotEmpty() && System.currentTimeMillis() - cmdListTs < 180000) cmdList
                else freshSessions()
        if (n < 1 || n > l.size) { LogBus.add(tr("рубка: сессии №$n нет", "switchboard: no session #$n")); say(tr("такой сессии нет", "no such session")); return }
        val (sid, name) = l[n - 1]
        LogBus.add(tr("рубка: адресат теперь «$name»", "switchboard: target is now “$name”"))
        setReplyTargetManual(sid)   // v0.78: та же защита начатой диктовки
        lastAnnouncedTarget = sid   // имя прозвучало сейчас — на подъёме микрофона не повторять
    }

    private fun listenCuePhrase(): String {
        val n = replyTargetName()
        return if (n.isEmpty() || n == "—") tr("слушаю", "listening") else tr("слушаю, ", "listening, ") + spoken(n)
    }

    // v0.52 (боевое с самоката 30.07, дословно: «после ушло я не услышал вообще ничего… я так
    // понимаю, крайнее сообщение потерялось»). Оно не терялось — юзер говорил в УСНУВШИЙ после
    // отправки микрофон и не знал об этом. Сон обязан быть слышен, и в той же реплике должно
    // звучать, КУДА ушло: два вопроса, которые он задавал вслух каждый раз. Одной фразой,
    // потому что вторая реплика подряд обрывает первую (cuePlayer один).
    // ревью v1.15: пока играет ответ, playNext уже переставил lastSession на говорящий канал,
    // и «ушло в X» по адресату называло ЕГО, а не тот, куда ушло сообщение. Имя берём по sid
    // самого сообщения; без sid (прогрев) — по адресату, как прежде.
    private fun sentBasePhrase(sid: String = ""): String {
        val n = if (sid.isNotEmpty())
            SessionBook.all().firstOrNull { it.sid == sid }?.proj.orEmpty()
        else replyTargetName()
        return if (n.isEmpty() || n == "—") tr("ушло", "sent") else tr("ушло в ", "sent to ") + spoken(n)
    }

    // Поверх звучащего ответа — только фраза, которая уже лежит файлом: иначе она уйдёт в
    // синтез или живой TTS, а живой TTS удержание не ставит и звучит поверх ответа.
    private fun sentPhraseOverAnswer(sid: String): String {
        val full = sentBasePhrase(sid)
        val f = cueFiles[full]
        return if (f != null && f.exists() && f.length() > 200) full else tr("ушло", "sent")
    }

    // v0.83 (боевое: «ушло в X… слушаю… мета судья… — каша из фраз подряд»). Когда сразу
    // за отправкой играет ответ ДРУГОГО канала, три реплики склеиваются в одну, а имя
    // канала перед озвучкой не повторяется (announcedMsgId ставится заранее).
    private fun sentPhraseWithNext(sid: String = ""): String {
        val base = sentBasePhrase(sid)
        val nxt = playQueue.firstOrNull() ?: return base
        val nsid = nxt.optString("session")
        val sentTo = sid.ifEmpty { Cfg.replyTarget.ifEmpty { lastSession ?: "" } }
        if (nsid.isEmpty() || nsid == sentTo) return base
        val nname = nxt.optString("proj").ifEmpty { nsid.take(8) }
        val mid = nxt.optString("msgid")
        if (mid.isNotEmpty()) { announcedMsgId = mid; announcedProj = nname }
        return base + tr(". дальше ", ". next, ") + spoken(nname)
    }

    private fun sentCuePhrase(sid: String = ""): String =
        if (micAsleep) sentBasePhrase(sid) + tr(". микрофон спит", ". mic asleep") else sentBasePhrase(sid)

    // Обе формы «ушло» готовим заранее: какая понадобится, зависит от того, уснёт ли микрофон,
    // а на живом TTS реплика звучит тихо — ровно то, чего юзер на улице не слышит.
    private fun ensureTargetCues() {
        ensureCue(listenCuePhrase())
        val b = sentBasePhrase()
        ensureCue(b)
        ensureCue(tr("$b. микрофон спит", "$b. mic asleep"))
    }

    private fun cue(text: String) {
        if (!Cfg.voiceCues || !ttsReady || Cfg.voiceOff) return
        val f = cueFiles[text]
        // v0.51: имена реплик теперь заводятся на лету (под имя адресата), а synthesizeToFile
        // асинхронен — недописанный wav играть нельзя, он звучит обрывком или молчит.
        if (f != null && f.exists() && f.length() > 200 &&
            System.currentTimeMillis() - f.lastModified() > 1200
        ) {
            playCueFile(f)
            return
        }
        // файла (ещё) нет — живой TTS как запасной путь; на музыкальном канале, негромко
        // v0.66 (вопрос юзера: «иногда говорит „слушаю, X“, а иногда просто „слушаю“ — так
        // задумано?»): не задумано. Имя пропадает по одной из двух причин, и различить их можно
        // только здесь: либо адресат неизвестен (тогда фраза изначально без имени — видно по
        // строке «отвечаю в …»), либо файл реплики не готов и она уходит ЖИВЫМ TTS, а он тихий
        // (урок v0.38) — на улице такую реплику просто не слышно. Пишем, каким путём пошли.
        // v0.94 (аудит + слово юзера «часть фраз слышно тихо»): файла нет — СИНТЕЗИРУЕМ и
        // играем по готовности тем же громким путём, что и прогретые. Живой TTS остаётся
        // последним резервом (он тихий, урок v0.38, и мимо всех гейтов — аудит v0.94).
        if (cuePlayWhenReady.size < 4) {
            cuePlayWhenReady.add(text)
            ensureCue(text)
            LogBus.add(tr("реплика «${text.take(40)}» синтезируется — сыграю целиком, когда будет готова", "cue “${text.take(40)}” is being synthesized — will play whole when ready"))
            // страховка: синтез мог не запуститься (движок занят) — снимем метку, чтобы
            // гейт воспроизведения не держал очередь вечно (аудит: залипание cuePlayWhenReady)
            main.postDelayed({
                // страховка не должна ГЛОТАТЬ фразу: файл дописан — играем, нет — живым голосом
                if (cuePlayWhenReady.remove(text)) {
                    val f2 = cueFiles[text]
                    // файл ДОПИСАН только если его не трогали ~0.8с: иначе сыграем огрызок
                    // (боевое: «ушло в мета судья. дальше» — и обрыв на полуслове)
                    val done = f2 != null && f2.exists() && f2.length() > 200 &&
                        System.currentTimeMillis() - f2.lastModified() > 800
                    if (done) playCueFile(f2!!)
                    else try {
                        tts?.setSpeechRate(0.95f)
                        val b = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
                        tts?.speak(text, TextToSpeech.QUEUE_ADD, b, "cue")
                    } catch (_: Exception) {}
                }
            }, if (cueFiles[text]?.exists() == true) 2200 else 5000)
            return
        }
        try {
            tts?.setSpeechRate(0.95f)
            val b = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
            tts?.speak(text, TextToSpeech.QUEUE_ADD, b, "cue")
        } catch (_: Exception) {}
    }

    // Отложенная реплика могла протухнуть, пока ждала: «слушаю» в выключенный микрофон —
    // это приглашение говорить в никуда (ревью v0.97). Остальные фразы остаются валидными.
    private fun cueStillValid(f: File): Boolean =
        listening || f != cueFiles[listenCuePhrase()]

    private fun playCueFile(f: File) {
        main.post {
            if (!alive) return@post
            if (Cfg.voiceOff) { cueQueue.clear(); cuePlayWhenReady.clear(); return@post }   // v1.28
            // v1.07 (слово юзера: «пауза — это тишина, ничего не должно звучать, включая
            // системные фразы»): на паузе реплика ждёт в очереди и прозвучит, когда ты
            // сам снимешь паузу. Раньше она играла поверх тишины и ломала ожидание.
            if (paused) {
                queueCue(f)
                return@post
            }
            // v0.39 (боевое «от слова слушаю слышны последние три буквы»): SCO-маршрут
            // устаканивается ~1с после включения — реплика, начатая раньше, теряет начало.
            // Свежевключённый SCO → подождать остаток секунды и сыграть целиком.
            // v0.97 (слово юзера: «системные фразы должны звучать ПОСЛЕ выключения микрофона,
            // ровно и громко»): пока идёт запись — реплика ждёт. Иначе она звучит в открытый
            // микрофонный канал, где её половину съедает эхо-гейт, а громкость скачет.
            if (cueWaitFrom == 0L) cueWaitFrom = System.currentTimeMillis()
            if (recThread != null && System.currentTimeMillis() - cueWaitFrom < 4000) {
                cuePendingUntil = System.currentTimeMillis() + 500
                main.postDelayed({ if (alive && cueStillValid(f)) playCueFile(f) }, 400)
                return@post
            }
            cueWaitFrom = 0L
            // канал ещё гаснет — дать ему договорить teardown, иначе фраза рвётся
            val down = 1300 - (System.currentTimeMillis() - scoStoppedAt)
            if (!scoStarted && scoStoppedAt > 0 && down > 50) {
                cuePendingUntil = System.currentTimeMillis() + down + 100
                main.postDelayed({ if (alive && cueStillValid(f)) playCueFile(f) }, down)
                return@post
            }
            if (scoStarted) {
                val settle = 1100 - (System.currentTimeMillis() - scoStartedAt)
                if (settle > 50) {
                    // v0.94-fix: помечаем ожидание, иначе микрофон не знает, что реплика ещё
                    // впереди, и стартует раньше неё (ревью: правка не работала на гарнитуре)
                    cuePendingUntil = System.currentTimeMillis() + settle + 150
                    main.postDelayed({ if (alive && cueStillValid(f)) playCueFile(f) }, settle)
                    return@post
                }
            }
            // v0.94 (аудит 24 наложений + слово юзера «фразы наступают друг на друга»):
            // реплики теперь ЖДУТ ОЧЕРЕДИ, а не убивают предыдущую. Раньше каждый новый cue
            // делал release() играющей фразы — и до уха доходил огрызок, часто самый
            // бесполезный из трёх. Потолок 6с на фразу: залипшая не имеет права держать всё.
            if (cuePlayer != null && System.currentTimeMillis() - ttsSpeakStartTs < 6000) {
                queueCue(f)
                return@post
            }
            try { cuePlayer?.release() } catch (_: Exception) {}
            cuePlayer = null
            try {
                ttsSpeaking = true   // эхо-гейт рекордера действует и на файловые реплики
                ttsSpeakStartTs = System.currentTimeMillis()   // v0.46: кап на залипший флаг
                val mp = MediaPlayer()
                // v0.45 (боевое, регресс кнопок): реплики ВСЕГДА на музыкальном канале (USAGE_MEDIA).
                // v0.38 перевёл их на USAGE_VOICE_COMMUNICATION ради громкости — но этот канал во
                // время диктовки загонял наушник в чистый HFP и ронял A2DP-поток, который держит
                // медиа-кнопку живой. Именно это убило кнопку во время диктовки (работала вчера).
                // Громкость músic-канала при SCO поднимаем принудительно (см. ниже).
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                mp.setDataSource(f.absolutePath)
                mp.setOnCompletionListener {
                    main.post {
                        ttsSpeaking = false
                        lastTtsEndTs = System.currentTimeMillis()
                        try { mp.release() } catch (_: Exception) {}
                        if (cuePlayer === mp) cuePlayer = null
                        unduckPlayer()
                        cueQueue.poll()?.let { nx -> playCueFile(nx) }
                    }
                }
                mp.setOnErrorListener { _, _, _ ->
                    main.post {
                        ttsSpeaking = false
                        lastTtsEndTs = System.currentTimeMillis()
                        try { mp.release() } catch (_: Exception) {}
                        if (cuePlayer === mp) cuePlayer = null
                        unduckPlayer()
                        // v1.13 (аудит): годность файла проверялась одним размером (>200 байт),
                        // и обрезанный wav — синтез убит на середине — проходил проверку вечно:
                        // фраза немела НАВСЕГДА, переживая перезапуски (кэш лежит в cacheDir).
                        // Кэш самолечится: битый файл выбрасываем, следующий вызов пересинтезирует.
                        dropBadCue(f)
                        cueQueue.poll()?.let { nx -> playCueFile(nx) }
                    }
                    true
                }
                mp.prepare()
                // v0.94: ВСЕГДА полная громкость. Половинная на A2DP («чтобы не пугать»)
                // на улице означала, что половину системных фраз юзер просто не слышит.
                mp.setVolume(1f, 1f)
                mp.start()
                cuePlayer = mp
                // v0.58 (слово юзера: «идёт озвучка сообщения, и поверх неё реплика — они
                // накладываются»). v0.53 закрыл одну сторону стыка: озвучка ждёт реплику.
                // Обратную сторону — реплика поверх ИДУЩЕЙ озвучки — закрываем приглушением,
                // как делает автомобильная навигация: ответ уходит на четверть громкости и
                // возвращается сам. Глушить нельзя (порвём поток A2DP и потеряем кнопку), а
                // молчать реплике — значит потерять «ушло» и имя адресата.
                if (playing && !paused) holdAnswerForCue()
            } catch (e: Exception) {
                ttsSpeaking = false
                lastTtsEndTs = System.currentTimeMillis()
                unduckPlayer()
                dropBadCue(f)                                    // v1.13: см. onErrorListener
                cueQueue.poll()?.let { nx -> playCueFile(nx) }   // очередь не должна вставать
            }
        }
    }

    // v1.13 (аудит): при переполнении очередь выбрасывала НОВУЮ фразу, а старые протухшие
    // («слушаю», «есть ответы») держались до конца — то есть терялось как раз то, что важнее
    // всего, обычно подтверждение отправки. Теперь вытесняем самую старую и говорим об этом
    // в журнал. Подтверждение «ушло» не дедуплицируем: две отправки подряд — два подтверждения.
    // Выбросить негодный файл реплики из кэша: и с диска, и из карты имён — иначе
    // ensureCue/prewarmCues увидят «файл есть, больше 200 байт» и не пересинтезируют.
    private fun dropBadCue(f: File) {
        try {
            val key = cueFiles.entries.firstOrNull { it.value.absolutePath == f.absolutePath }?.key
            if (key != null) cueFiles.remove(key)
            if (f.exists()) f.delete()
            LogBus.add(tr("битый файл реплики выброшен — пересинтезирую при следующей", "broken cue file discarded — will resynthesize next time"))
        } catch (_: Exception) {}
    }

    private fun queueCue(f: File) {
        val isSent = cueFiles.entries.any { it.key.startsWith(tr("ушло", "sent")) && it.value.absolutePath == f.absolutePath }
        if (!isSent && cueQueue.any { it.absolutePath == f.absolutePath }) return
        if (cueQueue.size >= 4) {
            cueQueue.poll()
            LogBus.add(tr("очередь реплик полна — выбросил самую старую", "cue queue full — dropped the oldest"))
        }
        cueQueue.addLast(f)
    }

    // v1.13 (аудит реплик, blocker): очередь реплик сливалась ТОЛЬКО из onCompletion уже
    // играющей реплики. На паузе playCueFile ничего не запускает — значит фразы копились
    // в очереди и не выходили НИКОГДА: снятие паузы её не трогало. Так молча пропадали
    // «ушло в X», «канал не слушает», «отбой, стёрто». Хуже: непустая очередь навсегда
    // запрещала сторожу поднять микрофон гарнитуры (гейт на 30-секундном тике — без потолка).
    private fun flushCueQueue() {
        if (cuePlayer == null && !paused) cueQueue.poll()?.let { playCueFile(it) }
    }

    // Вернуть громкость ответа после реплики. В паузе НЕ трогаем: там ноль — это и есть пауза
    // (пауза сделана заглушением намеренно, остановленный плеер рвёт поток и убивает кнопку).
    private fun unduckPlayer() {
        if (!cueHold) {
            if (!paused) try { player?.setVolume(1f, 1f) } catch (_: Exception) {}
            return
        }
        if (cueQueue.isNotEmpty()) return   // следующая реплика продолжит держать ответ
        releaseCueHold()
    }

    private fun holdAnswerForCue() {
        if (cueHold) {
            // следующая реплика цепочки: позицию не трогаем, но сторож считает от неё
            cueHoldSince = System.currentTimeMillis()
            armCueHoldGuard()
            return
        }
        // ревью v1.15 (блокер): между частями ответа playing=true, а в player лежит уже
        // доигравший кусок. Удержание на нём переигрывало старый ответ или съедало начало
        // нового. Держим только реально звучащий ответ; новую часть переякорит playFile.
        val p = player ?: return
        if (currentFile == null || !(try { p.isPlaying } catch (_: Exception) { false })) return
        try {
            cueHoldAtMs = p.currentPosition
            player?.setVolume(0f, 0f)
            cueHold = true
            cueHoldSince = System.currentTimeMillis()
            armCueHoldGuard()
        } catch (_: Exception) {}
    }

    // Страховка: залипшая реплика не имеет права держать ответ в тишине вечно.
    private fun armCueHoldGuard() {
        main.postDelayed({
            if (cueHold && System.currentTimeMillis() - cueHoldSince >= 12000) {
                LogBus.add(tr("реплика держала ответ дольше 12с — отпускаю", "cue held the reply longer than 12s — releasing"))
                releaseCueHold()
            }
        }, 12000)
    }

    private fun releaseCueHold() {
        if (!cueHold) return
        cueHold = false
        if (paused) return                  // пауза уже забрала позицию себе (pausePlayback)
        try {
            player?.let {
                it.seekTo(cueHoldAtMs)
                fadeIn(it)   // без щелчка чужого слога на асинхронном seekTo
            }
        } catch (_: Exception) {}
    }

    // v0.31 (требование юзера: «кнопки должны работать В ПАРЕ с микрофоном гарнитуры, без
    // вариантов»): в SCO-режиме Android отдаёт кнопку телефонной части, потому что медиа мы
    // в этот момент не играем. Обходим ровно эту причину: во время диктовки держим БЕЗЗВУЧНЫЙ
    // зацикленный поток на музыкальном канале. Для системы мы остаёмся активным плеером,
    // значит кнопка остаётся нашей; для уха ничего не меняется (громкость 0).
    private var silentKeeper: MediaPlayer? = null

    private fun silenceFile(): File {
        val f = File(cacheDir, "silence.wav")
        if (f.exists() && f.length() > 100) return f
        val sr = 8000; val secs = 1
        val data = ByteArray(sr * 2 * secs)          // 16-bit mono zeros
        val total = 36 + data.size
        val out = f.outputStream()
        fun le(v: Int, n: Int) { for (i in 0 until n) out.write((v shr (8 * i)) and 0xFF) }
        out.write("RIFF".toByteArray()); le(total, 4); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le(16, 4); le(1, 2); le(1, 2)
        le(sr, 4); le(sr * 2, 4); le(2, 2); le(16, 2)
        out.write("data".toByteArray()); le(data.size, 4); out.write(data)
        out.close()
        return f
    }

    private fun startSilentKeepalive() {
        if (silentKeeper != null) return
        try {
            silentKeeper = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(silenceFile().absolutePath)
                isLooping = true
                setVolume(0f, 0f)
                prepare()
                start()
            }
            grabMediaButtons()
            LogBus.add(tr("держу кнопку за собой (беззвучный поток)", "holding the button for myself (silent stream)"))
        } catch (e: Exception) {
            LogBus.add(tr("беззвучный поток не поднялся: ${e.message}", "silent stream failed to start: ${e.message}"))
            silentKeeper = null
        }
    }

    private fun stopSilentKeepalive() {
        try { silentKeeper?.stop() } catch (_: Exception) {}
        try { silentKeeper?.release() } catch (_: Exception) {}
        silentKeeper = null
    }

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ── foreground plumbing ────────────────────────────────────────────────
    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH, "Claudio Code", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = buildNotification(if (Cfg.handsFree) tr("безрукий режим", "hands-free mode") else tr("кнопочный режим", "button mode"))
        if (Build.VERSION.SDK_INT >= 29) {
            // MICROPHONE type only when the permission is actually granted —
            // otherwise Android 14+ throws SecurityException at startForeground.
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                (if (hasMicPermission()) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            try {
                startForeground(1, n, type)
                micTypeDegraded = false
            } catch (e: SecurityException) {
                // v0.36 (аудит): рестарт из фона (STICKY ночью, кнопка гарнитуры) — Android 14
                // не даёт mic-тип из фона, и рация «слышала тишину» до перезапуска руками.
                // Теперь флаг + повторный апгрейд при выходе приложения на экран (ensureMicFgs).
                micTypeDegraded = true
                LogBus.add(tr("система не дала микрофонный тип из фона — открой приложение, чтобы вернуть слух", "system denied the microphone type from background — open the app to restore hearing"))
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            }
        } else {
            startForeground(1, n)
        }
    }

    // v0.36 (аудит): переключение тумблера Whisper мид-диктовки бросало незавершённое:
    // выключение — utt висел на сервере и приезжал призраком через 10 мин; включение —
    // недописанный draft оставался лежать и держал придержку озвучки. Закрываем хвосты.
    fun onWhisperToggled(nowOn: Boolean) {
        if (nowOn) {
            val t = draft.toString().trim()
            if (t.isNotEmpty()) { draft.setLength(0); LogBus.add(tr("черновик распознавателя дошлю перед переключением", "will send the recognizer draft before switching")); send(t) }
        } else {
            // uttSpeechMs ловит уже отгруженные сегменты, lastVoiceTs — речь в текущем буфере
            if (recThread != null && (uttSpeechMs > 0 || System.currentTimeMillis() - lastVoiceTs < 5000)) {
                LogBus.add(tr("закрываю whisper-черновик перед переключением", "closing the whisper draft before switching"))
                finalizeReq = true
            }
        }
    }

    // v0.36: зовётся из MainActivity.onResume — приложение на экране, можно вернуть mic-тип FGS
    fun ensureMicFgs() {
        if (micTypeDegraded && hasMicPermission()) {
            startInForeground()
            if (!micTypeDegraded) LogBus.add(tr("микрофонный тип FGS восстановлен", "FGS microphone type restored"))
        }
    }

    private fun buildNotification(state: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = androidx.core.app.NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_spark)   // v0.36: своя искра вместо системного ассета
            .setContentTitle("Claudio Code")
            .setContentText(state)
            .setContentIntent(pi)
            .setOngoing(true)
        // v0.44 (боевое «кнопки не доходят НИ В КАКОМ состоянии»): на Android 8+ приложение
        // получает аппаратные медиа-кнопки НАДЁЖНО только когда его уведомление привязано к
        // медиасессии MediaStyle-стилем. Без этого система роутит кнопку последней «настоящей»
        // медиасессии (плеер/ютуб), и наш isActive проигрывает. Привязываем токен сессии.
        mediaSession?.sessionToken?.let { tok ->
            try {
                b.setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(tok))
            } catch (_: Exception) {}
        }
        return b.build()
    }

    private fun updateNotification() {
        if (!alive) return
        val state = when {
            playing -> tr("играю ответ", "playing reply")
            listening -> tr("слушаю", "listening")
            else -> if (Cfg.handsFree) tr("безрукий режим", "hands-free mode") else tr("кнопочный режим", "button mode")
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(1, buildNotification(state))
    }

    // ── headset helpers ───────────────────────────────────────────────────
    private fun headsetPresent(): Boolean {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            when (it.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                26 /* TYPE_BLE_HEADSET */ -> true
                else -> false
            }
        }
    }

    // Headset dropped: never keep talking into the loudspeaker, never keep the
    // stale SCO flag that blocks re-routing when the headset comes back.
    private fun onHeadsetLost() {
        // v0.36 (аудит): потеря гарнитуры ПОСРЕДИ ДИКТОВКИ раньше не замечалась вовсе —
        // запись молча шла с микрофона телефона (в кармане = глухота), а протухший
        // scoStarted не давал вернуть SCO при возврате гарнитуры.
        if (listening && scoStarted) {
            scoStarted = false
            LogBus.add(tr("гарнитура пропала во время диктовки — пишу с телефона, вернётся — переключусь", "headset disappeared during dictation — recording from the phone, will switch back when it returns"))
            buzz(500)
        }
        if (!playing) return
        // v0.36 (аудит, приватность): раньше при onlyHeadset=false (дефолт) отвал наушников
        // просто перекидывал озвучку в ГРОМКИЙ ДИНАМИК на улице. Теперь ответ никогда не
        // доигрывает вслух сам: onlyHeadset=true — часть в очередь до наушников (как раньше),
        // иначе — «пауза потери наушников»: продолжит кнопка или вернувшиеся наушники.
        val pos = if (paused) pausedAtMs else if (cueHold) cueHoldAtMs else (try { player?.currentPosition ?: 0 } catch (_: Exception) { 0 })
        cueHold = false
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        // v0.36 (ревью): playGen здесь НЕ трогаем — часть, докачивающаяся в этот момент,
        // должна уехать в очередь (паузный/наушниковый гейты playFile), а не в мусор
        if (Cfg.onlyHeadset && !Cfg.autoRoute) {
            currentFile?.let { fileQueue.addFirst(it) }
            currentFile = null
            playing = false
            paused = false   // v0.14: иначе пауза переживает потерю наушников и намертво держит очередь
            flushCueQueue()  // v1.13: реплики, отложенные паузой, обязаны выйти
            main.removeCallbacks(unpauseGuard)
            abandonFocus()
            LogBus.add(tr("наушники отключились — жду наушники", "headphones disconnected — waiting for headphones"))
        } else {
            pausedAtMs = pos
            playing = false
            paused = true    // снимается ЛЮБОЙ кнопкой (контракт v0.14) или возвратом наушников
            main.removeCallbacks(unpauseGuard)   // в динамик сам не продолжаю — без 90с-сторожа
            abandonFocus()
            LogBus.add(tr("наушники отключились — пауза (кнопка или наушники продолжат)", "headphones disconnected — paused (button or headphones will resume)"))
        }
        updateNotification()
    }

    // Best-effort: route dictation input to the headset microphone.
    private fun startHeadsetMic() {
        if (!Cfg.btMic) return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val dev = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == 26
                }
                if (dev != null) {
                    audioManager.setCommunicationDevice(dev)
                    scoStarted = true
                    scoStartedAt = System.currentTimeMillis()
                    routeBtnFired = false   // v0.64: канал поднят заново — «удар» снова доступен
                }
            } else {
                @Suppress("DEPRECATION")
                if (audioManager.isBluetoothScoAvailableOffCall) {
                    @Suppress("DEPRECATION")
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                    scoStarted = true
                    scoStartedAt = System.currentTimeMillis()
                    routeBtnFired = false   // v0.64: канал поднят заново — «удар» снова доступен
                }
            }
        } catch (e: Exception) {
            LogBus.add(tr("bt-микрофон: ${e.message}", "bt microphone: ${e.message}"))
        }
    }

    private fun stopHeadsetMic() {
        if (!scoStarted) return
        scoStarted = false
        // v0.97: teardown SCO асинхронный (до ~1.5с). Реплика, начатая в этот момент,
        // звучит в разваливающийся канал — тихо, рвано и с перепадом громкости на
        // середине фразы (боевое: «ушло в X» сначала тихо, потом громко).
        scoStoppedAt = System.currentTimeMillis()
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
        } catch (_: Exception) {}
    }

    // Honest diagnostics: is dictation actually recording through the headset?
    private fun checkMicRoute() {
        if (!Cfg.btMic || Build.VERSION.SDK_INT < 24) return
        try {
            val cfgs = audioManager.activeRecordingConfigurations
            val dev = cfgs.firstOrNull()?.audioDevice
            if (dev != null && headsetPresent() &&
                dev.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO && dev.type != 26
            ) {
                LogBus.add(tr("внимание: пишу с микрофона телефона, не гарнитуры", "warning: recording from the phone microphone, not the headset"))
            }
        } catch (_: Exception) {}
    }

    // v0.7 (боевое «кнопка наушников перестала работать»): кнопки получает ПОСЛЕДНЯЯ активная медиасессия
    // системы — чужой плеер/ютуб перехватывает приоритет. Заново заявляем его при каждом старте
    // озвучки и слушания: isActive + свежий PlaybackState(PLAYING).
    fun grabMediaButtons() {
        try {
            mediaSession?.let {
                it.isActive = true
                it.setPlaybackState(
                    PlaybackStateCompat.Builder()
                        .setActions(
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_PLAY or
                                PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        )
                        .setState(PlaybackStateCompat.STATE_PLAYING, System.currentTimeMillis(), 1f)
                        .build()
                )
            }
        } catch (_: Exception) {}
    }

    // v0.44: частый перехват приоритета кнопок, пока рация активна — если кнопки ворует
    // другое приложение между 30-секундными захватами, это его отобьёт. Останавливается,
    // когда сервис умирает (alive=false).
    private val fastGrab = object : Runnable {
        override fun run() {
            if (!alive) return
            grabMediaButtons()
            main.postDelayed(this, 4000)
        }
    }

    // ── media buttons (Soundcore buttons / AirPods gestures) ─────────────
    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "ClaudioCode").apply {
            // v0.12: явный получатель кнопок — иначе система роутит их последнему медиаплееру
            try {
                // v0.41: цель PendingIntent = androidx-ресивер из манифеста (конфигурация v0.35,
                // где кнопки доказанно работали)
                val mbIntent = Intent(Intent.ACTION_MEDIA_BUTTON).setClass(
                    this@BridgeService, androidx.media.session.MediaButtonReceiver::class.java
                )
                setMediaButtonReceiver(
                    PendingIntent.getBroadcast(
                        this@BridgeService, 0, mbIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            } catch (e: Exception) { LogBus.add(tr("медиакнопки: ${e.message}", "media buttons: ${e.message}")) }
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    )
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f)
                    .build()
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { main.post { if (alive) { LogBus.add(tr("кнопка: play", "button: play")); onButton() } } }
                override fun onPause() { main.post { if (alive) { LogBus.add(tr("кнопка: pause", "button: pause")); onButton() } } }
                override fun onSkipToNext() { main.post { if (alive && !resumeIfPaused()) { if (playing) stopPlayback(thenListen = true) else onCancel() } } }
                override fun onSkipToPrevious() { main.post { if (alive && !resumeIfPaused()) replayLast() } }
                override fun onMediaButtonEvent(mbIntent: Intent): Boolean {
                    // v0.43 диагностика: логируем ЛЮБОЙ дошедший медиаинтент — если этой строки
                    // нет при нажатии, значит система не доставляет нажатие сессии ВООБЩЕ
                    LogBus.add(tr("медиасобытие дошло до рации", "media event reached the radio"))
                    val ev: KeyEvent = if (Build.VERSION.SDK_INT >= 33)
                        mbIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                            ?: return false
                    else
                        @Suppress("DEPRECATION")
                        mbIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) ?: return false
                    val singlePress = ev.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                        ev.keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
                        ev.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                        ev.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
    // v0.32: одиночная кнопка срабатывает по ОТПУСКАНИЮ — иначе долгое нажатие
                    // не отличить от короткого. Держат дольше 650мс — это долгое: полный стоп.
                    // v0.36 (аудит): перенос на UP обошёл антидребезг v0.16 — гарнитура шлёт одно
                    // нажатие ДВУМЯ парами событий (127, следом 79), и второй UP давал ложное
                    // «двойное» (молча стирало черновик / само снимало паузу). Если DOWN отброшен
                    // как дребезг — его UP тоже дребезг.
                    if (ev.action == KeyEvent.ACTION_UP && singlePress) {
                        if (btnGhostPress) {
                            btnGhostPress = false
                            btnLongTimer?.let { main.removeCallbacks(it) }   // v0.36 (ревью): страховка от ложного долгого
                            LogBus.add(tr("  (дребезг — хвост того же жеста)", "  (bounce — tail of the same gesture)"))
                            return true
                        }
                        btnLongTimer?.let { main.removeCallbacks(it) }
                        if (!btnLongFired) main.post {
                            if (!alive) return@post
                            if (paused && playing) resumeIfPaused() else onButton()
                        }
                        return true
                    }
                    if (ev.action == KeyEvent.ACTION_DOWN) {
                        // v0.12 диагностика «кнопки не работают»: видно, ДОХОДИТ ли нажатие и каким кодом
                        LogBus.add(tr("кнопка: код ${ev.keyCode}${if (ev.repeatCount > 0) " (повтор)" else ""}", "button: code ${ev.keyCode}${if (ev.repeatCount > 0) " (repeat)" else ""}"))
                        // v0.16 («иногда отправляет, иногда нет — разницы не вижу»): гарнитуры
                        // шлют ОДНО нажатие несколькими событиями (напр. 127 и следом 79). Второе
                        // прилетало в окно двойного нажатия и трактовалось как отмена — черновик
                        // молча исчезал. Дребезг короче 150мс — это то же самое нажатие.
                        val nowBtn = System.currentTimeMillis()
                        if (nowBtn - lastBtnTs < 150) {
                            btnGhostPress = true   // v0.36: и его UP отбросить тоже
                            LogBus.add(tr("  (дребезг — тот же жест, пропускаю)", "  (bounce — same gesture, skipping)"))
                            return true
                        }
                        lastBtnTs = nowBtn
                        btnGhostPress = false
                        when (ev.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            KeyEvent.KEYCODE_HEADSETHOOK,
                            KeyEvent.KEYCODE_MEDIA_PLAY,
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                if (ev.repeatCount == 0) {
                                    btnLongFired = false
                                    btnLongTimer?.let { main.removeCallbacks(it) }
                                    val t = Runnable {
                                        if (alive && !btnLongFired) { btnLongFired = true; onLongPress() }
                                    }
                                    btnLongTimer = t
                                    main.postDelayed(t, 650)
                                } else if (!btnLongFired) {
                                    btnLongFired = true
                                    btnLongTimer?.let { main.removeCallbacks(it) }
                                    main.post { if (alive) onLongPress() }
                                }
                                return true
                            }
                            // v0.13: часть гарнитур шлёт «стоп» отдельным кодом — не терять его
                            KeyEvent.KEYCODE_MEDIA_STOP -> { main.post { if (alive && !resumeIfPaused()) { if (playing) stopPlayback(thenListen = true) else onCancel() } }; return true }
                            // v0.15 (слово юзера: «левая кнопка отправляет, правая не реагирует»):
                            // правая шлёт другой код и попадала СЮДА — то есть молча СТИРАЛА
                            // надиктованное. Стирание по одиночному нажатию боковой кнопки — потеря
                            // работы, поэтому при непустом черновике эта кнопка ОТПРАВЛЯЕТ, как и левая.
                            // Отмена осталась: голосом «отмена», экранной кнопкой, и этой же кнопкой,
                            // когда черновик пуст (терять нечего).
                            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                main.post {
                                    if (!alive) return@post
                                    when {
                                        playing -> stopPlayback(thenListen = true)
                                        // v1.47: в whisper черновика на телефоне нет — сказанное (600мс в открытом utt) тоже отправляем, не стираем
                                        listening && (draft.isNotEmpty() || saidSomething()) -> { LogBus.add(tr("кнопка (правая) — отправляю", "button (right) — sending")); beginSend() }
                                        skipWait() -> {}   // v1.46: зазор/хвост — «дальше»
                                        else -> onCancel()
                                    }
                                }
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { main.post { if (alive) replayLast() }; return true }
                        }
                    }
                    return super.onMediaButtonEvent(mbIntent)
                }
            })
            isActive = true
        }
    }

    // Single press (deferred 600ms so a double press can cancel first):
    // start dictation / send it. Double press: cancel draft.
    private fun touchUser() {
        userActionTs = System.currentTimeMillis()
        noAutoWakeUntil = 0L   // юзер сам что-то нажал — запрет на пробуждение снят
    }

    fun onButton() {
        // v0.9 (слово юзера «приостановить и продолжить с того же места»): кнопка во время
        // озвучки = ПАУЗА, ещё нажатие = продолжить с той же секунды. Полный стоп со сбросом
        // очереди — двойное нажатие (MEDIA_NEXT). Пауза заодно приватна: микрофон в ней выключен.
        // v0.14: снятие паузы проверяем ПЕРВЫМ и без оглядки на playing — см. resumeIfPaused.
        touchUser()
        // v0.51: главный открытый вопрос по v0.50 — доходит ли кнопка гарнитуры ПОСЛЕ засыпания
        // микрофона. Строка отвечает на него одним взглядом в журнал, без гаданий.
        // (ревью: micAsleep живёт и во время озвучки — без гейта строка врала бы на каждой паузе)
        if (micAsleep && !playing && !paused) LogBus.add(tr("кнопка дошла во сне — бужу микрофон", "button arrived while asleep — waking the microphone"))
        if (Cfg.voiceOff) { toggleVoiceOff(); return }   // v1.28: тап/кнопка при выключенном голосе — вернуть голос
        if (dictHold && !listening && !playing) { toggleMicMute(); return }  // v0.43: кнопка будит из mute
        if (resumeIfPaused()) return
        if (playing) {
            pausePlayback()
            return
        }
        pendingPress?.let {
            main.removeCallbacks(it)
            pendingPress = null
            onCancel()
            return
        }
        val r = Runnable {
            pendingPress = null
            if (!alive) return@Runnable
            if (listening) beginSend() else startListening()
        }
        pendingPress = r
        main.postDelayed(r, 600)
    }

    // v0.14 (боевое, юзер прав — чужих плееров не было): после паузы гарнитура перестаёт слать
    // ОДИНОЧНОЕ нажатие (в журнале: код 127 → пауза → тишина), хотя тройное доходит. Причина не в
    // приоритете сессии, а в самой гарнитуре: поток A2DP оборвался, и она считает, что играть
    // нечего. Не гадаем дальше — выходим из паузы по ЛЮБОЙ долетевшей кнопке.
    // v0.14: снимаем паузу ДАЖЕ если playing уже false. Иначе флаг paused остаётся навсегда,
    // а `playNext()` начинается с `if (paused) return` — очередь озвучек мертва до перезапуска
    // сервиса. Именно так «продолжение не срабатывает вообще никак», в том числе с экрана.
    private fun resumeIfPaused(): Boolean {
        if (!paused) return false
        resumePlayback()
        return true
    }

    // Страховка от тупика: из паузы, которую нечем снять, очередь не выберется никогда.
    // v1.07: пауза больше не снимается сама через полторы минуты — это ломало контракт
    // «пауза = тишина, пока я сам не продолжу». Осталась страховка от полного тупика:
    // полчаса без единого действия юзера — снимаем, иначе очередь не выберется никогда.
    private val unpauseGuard = object : Runnable {
        override fun run() {
            if (!alive || !paused) return
            if (System.currentTimeMillis() - userActionTs < 1800000) {
                main.postDelayed(this, 60000)
                return
            }
            // v1.08 (слово юзера: «лучше пусть выключится, чем включится посреди дел»):
            // не продолжаем сами — гасим микрофон и оставляем паузу. Продолжишь ты.
            LogBus.add(tr("пауза висит полчаса — усыпляю микрофон, озвучка ждёт твоей команды", "pause has hung for half an hour — putting the microphone to sleep, playback waits for your command"))
            if (listening) stopListening()
            micAsleep = true
            noAutoWakeUntil = System.currentTimeMillis() + 600000
        }
    }

    // v0.15 (скрин юзера доказал): после `player.pause()` нажатия НЕ ДОХОДЯТ ВООБЩЕ — в журнале
    // «пауза», и дальше тишина, сколько ни жми. Причина ниже нашего кода: остановленный плеер
    // рвёт поток A2DP, гарнитура считает, что играть нечего, и кнопку нам больше не отдаёт.
    // Значит паузу нельзя делать остановкой. Делаем её ЗАГЛУШЕНИЕМ: поток продолжает идти
    // (гарнитура считает, что музыка играет и продолжает слать кнопку), громкость в ноль,
    // позиция запоминается. На продолжении — вернуть позицию и громкость.
    // v0.32 (слово юзера: нужен жест и на долгое нажатие): полный стоп всего — озвучка,
    // очередь, черновик, микрофон. Один жест «заглушить рацию немедленно».
    fun onLongPress() {
        LogBus.add(tr("долгое нажатие — полный стоп", "long press — full stop"))
        if (playing) stopPlayback(false)
        if (listening) { draft.setLength(0); stopListening() }
        cue(tr("стоп", "stop"))
        buzz(400)
    }

    fun pausePlayback() {
        paused = true
        pausedAtMs = if (cueHold) cueHoldAtMs
            else try { player?.currentPosition ?: 0 } catch (_: Exception) { 0 }
        cueHold = false
        try { player?.setVolume(0f, 0f) } catch (_: Exception) {}
        main.postDelayed(unpauseGuard, 90000)
        // v0.13 (боевое: «в паузу входит, а обратно — вообще никак»): объявлять системе STATE_PAUSED
        // НЕЛЬЗЯ. Приоритет медиакнопок Android отдаёт последней ИГРАЮЩЕЙ сессии — объявив паузу,
        // мы сами отдавали кнопку чужому плееру, и выйти из паузы становилось нечем. Держим
        // STATE_PLAYING и сразу перезабираем приоритет: для системы мы всё ещё активный плеер.
        grabMediaButtons()
        beepBoth(ToneGenerator.TONE_PROP_BEEP, 90)
        LogBus.add(tr("пауза — кнопка продолжит с этого места", "pause — button will resume from this point"))
        updateNotification()
    }

    fun resumePlayback() {
        paused = false
        main.removeCallbacks(unpauseGuard)
        // v1.15 (ревью): реплики, отложенные паузой, выйдут сейчас — ответ встаёт на удержание
        // С ТОЧКИ ПАУЗЫ до их конца. Иначе он заиграл бы вместе с фразой, а удержание взяло бы
        // позицию, до которой плеер успел уйти в тишине.
        if (currentFile != null && (cuePlayer != null || cueQueue.isNotEmpty()) && !cueHold) {
            cueHoldAtMs = pausedAtMs
            cueHold = true
            cueHoldSince = System.currentTimeMillis()
            armCueHoldGuard()
        }
        flushCueQueue()      // v1.13: на паузе реплики копились и НИКОГДА не выпускались
        grabMediaButtons()   // v0.12: вернуть состояние PLAYING и приоритет кнопки
        // v0.14: плеера может уже не быть (озвучка успела кончиться, наушники моргнули).
        // v0.29: но файл-то известен — переигрываем ЕГО с точки паузы, а не проматываем очередь.
        var ok = try {
            player?.let {
                it.seekTo(pausedAtMs)          // v0.15: вернуться туда, где остановились
                if (!cueHold) it.setVolume(1f, 1f)   // v1.15: под фразой звук поднимет releaseCueHold
                if (!it.isPlaying) it.start()
                true
            } ?: false
        } catch (_: Exception) { false }
        val f = currentFile
        if (!ok && f != null && f.exists()) {
            LogBus.add(tr("плеер умер в паузе — переигрываю с ${pausedAtMs / 1000}с", "player died in pause — replaying from ${pausedAtMs / 1000}s"))
            playing = true
            playFile(f, seekTo = pausedAtMs)
            ok = true
        }
        LogBus.add(if (ok) tr("продолжаю с ${pausedAtMs / 1000}с", "resuming from ${pausedAtMs / 1000}s") else tr("продолжать нечего — беру следующее", "nothing to resume — taking the next"))
        if (!ok) { playing = false; kickPlayback() }
        updateNotification()
    }

    // Stop the current playback and drop the queue; the message stays
    // available through "replay last".
    // v0.10 (слово юзера «хочу прервать озвучку и сразу начать диктовать»): thenListen сразу
    // поднимает микрофон — без второго нажатия. Пауза на устаканивание маршрута A2DP→SCO.
    fun stopPlayback(thenListen: Boolean = false) {
        cueHold = false
        touchUser()
        playGen++   // v0.36: убить отложенные продолжения (докачка, routeWait) — иначе зомби-озвучка
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        currentFile = null
        paused = false
        flushCueQueue()      // v1.13: см. resumePlayback — иначе отложенное молчит вечно
        currentSid = null
        playQueue.clear()
        fileQueue.clear()
        boundarySid = null          // очередь выброшена — зазору нечего сторожить
        boundaryHoldUntil = 0L
        if (batch.isNotEmpty()) { lastBatch = batch.toList(); batch = mutableListOf() }
        playing = false
        lastPartsTotal = lastPartNum   // v0.42: стоп = сообщение больше не ждём
        partWaitDeadline = 0L
        abandonFocus()
        beepBoth(ToneGenerator.TONE_PROP_BEEP, 120)
        LogBus.add(
            if (thenListen) tr("озвучка прервана — диктуй (повтор ответа: тройное нажатие)", "playback interrupted — dictate (replay the reply: triple press)")
            else tr("озвучка остановлена (повтор — тройное нажатие / кнопка)", "playback stopped (replay — triple press / button)")
        )
        updateNotification()
        if (thenListen) main.postDelayed({ if (alive && !playing && !listening) startListening() }, 700)
    }

    fun onCancel() {
        touchUser()
        if (Cfg.whisper && recThread != null) cancelReq = true
        draft.setLength(0)
        draftStartTs = 0L      // v1.43 (ревью): стёртый черновик не даёт начало следующему
        listenIsResume = false // v1.43 (ревью): продолжение отменено — ответ больше не держим
        pendingSend = false
        cancelAutoSend()
        resetSendWindow()   // v0.46
        if (!Cfg.handsFree) stopListening()
        beepBoth(ToneGenerator.TONE_PROP_NACK, 250)
        buzz(220)                     // v0.26: длинный толчок = стёрли
        // v1.41: части, дорезанные потолком, уже доехали — стёрт только хвост
        val partsGone = takePartDebt(listenStartedAt).isNotEmpty()
        if (!partsGone) noteEndedOwing(listenStartedAt)
        cue(if (partsGone) tr("начало ушло, хвост отменён", "start sent, tail cancelled") else tr("отменено", "cancelled"))
        LogBus.add(tr("черновик очищен", "draft cleared"))
        kickPlayback()   // v0.5.3: черновика нет — выпустить отложенный ответ
    }

    fun replayLast() {
        if (playing) return
        if (lastBatch.isEmpty()) { LogBus.add(tr("повторять нечего", "nothing to repeat")); return }
        if (Cfg.voiceOff) toggleVoiceOff(announce = false)   // v1.28: явный «повтор» = хочу слышать
        LogBus.add(tr("повторяю последнее (${lastBatch.size} ч.)", "repeating the last (${lastBatch.size} parts)"))
        for (f in lastBatch) if (f.exists()) fileQueue.addLast(f)
        kickPlayback()
    }

    // v0.17: одна строка состояния для крупной плашки на экране
    // v0.20 (слово юзера: «при ведении нескольких сессий мне нужно понимать, ЧТО ко мне приходит»):
    // в состоянии всегда видно, ЧЬЯ озвучка играет и чьи ответы стоят в очереди.
    fun statusText(): String {
        val who = lastProj?.takeIf { it.isNotEmpty() }
        val waiting = playQueue.mapNotNull { it.optString("proj").takeIf { p -> p.isNotEmpty() } }.distinct()
        return when {
            paused -> tr("ПАУЗА", "PAUSED") + (who?.let { " · $it" } ?: "")
            playing -> tr("ИГРАЮ", "PLAYING") + (who?.let { " · $it" } ?: tr(" ОТВЕТ", " REPLY"))
            listening && (draft.isNotEmpty() || uttSpeechMs > 600) -> tr("СЛУШАЮ · есть черновик", "LISTENING · draft in progress")
            listening -> tr("СЛУШАЮ", "LISTENING")
            dictHold -> tr("ПАУЗА ДИКТОВКИ · черновик цел", "DICTATION PAUSED · draft kept")
            // v0.36 (аудит): плашка врала «НА СВЯЗИ» при мёртвом релее — теперь честно
            !wsUp -> tr("НЕТ СВЯЗИ — переподключаюсь", "OFFLINE — reconnecting")
            // v0.51 (хвост №3): с включённым тумблером «только в наушниках» и без гарнитуры
            // ответы копятся молча, и рация выглядит сломанной. Плашка обязана назвать причину.
            (playQueue.isNotEmpty() || fileQueue.isNotEmpty()) && holdForHeadset() ->
                tr("ЖДУ НАУШНИКИ (${playQueue.size + fileQueue.size}) · выключи «только в наушниках»", "WAITING FOR HEADPHONES (${playQueue.size + fileQueue.size}) · turn off “headphones only”")
            playQueue.isNotEmpty() || fileQueue.isNotEmpty() -> {
                val n = playQueue.size + fileQueue.size
                tr("ЖДЁТ ОТВЕТ ($n)", "AWAITING REPLY ($n)") + (if (waiting.isNotEmpty()) " · " + waiting.joinToString(", ") else "")
            }
            // v0.49: накопленное не играет само и не держит микрофон — ждёт команды «слушать»
            heldQueue.isNotEmpty() -> tr("НАКОПИЛОСЬ (${heldQueue.size}) · жми «слушать» · микрофон свободен", "WAITING (${heldQueue.size}) · press “listen” · mic is free")
            // v0.50: состояние после отправки должно быть ВИДНО, а не угадываться
            micAsleep -> tr("ОТПРАВЛЕНО · микрофон спит · жми, чтобы сказать ещё", "SENT · mic asleep · press to say more")
            else -> tr("НА СВЯЗИ", "ONLINE")
        }
    }

    fun isListening(): Boolean = listening
    fun isPaused(): Boolean = paused

    // v0.37 (слово юзера: «по большой плашке — стоп и продолжение ВСЕГО, с того же места»):
    // пауза диктовки. stopListening сохраняет и draft, и uttId — продолжение дольёт
    // речь в ТО ЖЕ сообщение, и уйдёт всё вместе.
    fun holdDictation() {
        touchUser()
        if (!listening) return
        dictHold = true
        stopListening()
        buzz(90)
        cue(tr("пауза", "paused"))
        LogBus.add(tr("диктовка на паузе — черновик цел, тап по плашке продолжит", "dictation paused — draft intact, tap the banner to resume"))
    }

    fun hasPendingDictation(): Boolean = dictHold
    fun resumeDictation() {
        touchUser()
        dictHold = false
        LogBus.add(tr("продолжаю диктовку в то же сообщение", "continuing dictation into the same message"))
        resumeRequested = true
        startListening()
        resumeRequested = false
    }

    // v0.43 (слово юзера: «хочу заглушить микрофон — слушать ответы, но окружение не писать,
    // потом вернуть»): режим «только слушаю ответы». Микрофон молчит (окружение не уходит),
    // сервис и озвучки живут. dictHold=true заодно давит АВТО-подъём микрофона в безруком
    // режиме — иначе после каждого ответа микрофон вставал бы сам.
    fun toggleMicMute(): Boolean {
        touchUser()
        if (isMicMuted()) {                 // разглушить — поднять микрофон
            dictHold = false
            LogBus.add(tr("поднимаю микрофон", "raising the microphone"))
            startListening()
            return false
        }
        dictHold = true                     // заглушить — окружение больше не пишется
        if (listening) stopListening()
        buzz(120)
        cue(tr("микрофон выключен", "microphone off"))
        LogBus.add(tr("микрофон заглушён — слушаю только ответы; тап вернёт микрофон", "microphone muted — listening to replies only; tap to bring the microphone back"))
        updateNotification()
        return true
    }
    fun isMicMuted(): Boolean = dictHold && !listening

    // v1.28 (слово юзера 10.10): голос выкл — переписка без звука. Сервис остаётся на связи:
    // лента, список каналов и набор текста работают; ничего не играет, микрофон не встаёт.
    fun toggleVoiceOff(announce: Boolean = true): Boolean {
        touchUser()
        if (Cfg.voiceOff) {
            Cfg.voiceOff = false
            Cfg.save(this)
            LogBus.add(tr("голос включён", "voice on"))
            if (announce) cue(tr("голос включён", "voice on"))   // one phrase per event: callers that play right after pass false
            updateNotification()
            return false
        }
        buzz(120)   // no spoken cue: voice is going silent
        // an open dictation is discarded, not sent: "voice off" reads as "stop recording me",
        // and a false send costs more than a lost phrase (law of dictation)
        if (Cfg.whisper && recThread != null) cancelReq = true
        else if (uttId.isNotEmpty()) {   // mic already down but the utterance is still open on the server
            postCancel(uttId); uttId = ""; uttSpeechMs = 0; whArmedMs = 0L
        }
        Cfg.voiceOff = true
        Cfg.save(this)
        cueQueue.clear(); cuePlayWhenReady.clear()
        if (listening) stopListening(keepButton = true)
        if (playing || paused || playQueue.isNotEmpty() || fileQueue.isNotEmpty()) stopPlayback()
        startSilentKeepalive()   // the headset button needs a playing (silent) stream to reach us
        LogBus.add(tr("голос выключен: ответы только в ленте, микрофон не поднимается", "voice off: replies in the feed only, microphone stays down"))
        updateNotification()
        return true
    }

    // v0.19 (слово юзера «мне в принципе нет кнопки» + «нужно понимать, в какую сессию отвечаю»):
    // прямая отправка с экрана, без 600мс-ожидания двойного нажатия, и имя адресата для плашки.
    // v0.22 (боевое «сообщения не отправляются»): кнопка обязана слать НЕМЕДЛЕННО. Раньше она
    // шла через beginSend, который ждал финала распознавателя до 2с — а в шуме финал не приходит
    // вовсе, и нажатие выглядело как «ничего не произошло». Теперь: есть черновик — уходит сразу.
    fun sendNow() {
        touchUser()
        if (!listening) { LogBus.add(tr("нечего отправлять — диктовка не идёт", "nothing to send — no dictation in progress")); return }
        if (Cfg.whisper) { beginSend(); return }
        var msg = draft.toString().trim()
        // v0.30/v0.46-fix: финала нет, но догадка свежая — шлём её. У ручной отправки СВОЁ окно
        // (20с, PARTIAL_BTN_FRESH_MS): юзер жмёт осознанно после паузы на раздумье, 8с теряли текст.
        if (msg.isEmpty() && lastPartial.isNotEmpty() &&
            System.currentTimeMillis() - lastPartialTs < PARTIAL_BTN_FRESH_MS) {
            val c = cleanPartialForSend(lastPartial)   // v0.46-fix: догадка идёт мимо onFinalText — та же нормализация
            if (c == null) {                            // догадка = «отмена»
                cancelAutoSend(); resetSendWindow()
                draft.setLength(0); lastPartial = ""
                beepBoth(ToneGenerator.TONE_PROP_NACK, 250)
                LogBus.add(tr("отмена по голосу (догадка) — черновик пуст", "voice cancel (guess) — draft is empty"))
                return
            }
            msg = c
            if (msg.isNotEmpty()) LogBus.add(tr("финала не было — отправляю распознанное на лету", "no final result — sending what was recognized on the fly"))
        }
        if (msg.isEmpty()) { beginSend(); return }   // совсем пусто — дать распознавателю дошептать
        cancelAutoSend()
        resetSendWindow()   // v0.46: мысль ушла — абсолютный потолок считается с новой
        draft.setLength(0)
        lastPartial = ""
        LogBus.add(tr("кнопка — отправляю сразу", "button — sending immediately"))
        send(msg)
        try { recognizer?.cancel() } catch (_: Exception) {}
        sleepAfterSend()   // v0.50: одно сообщение на ответ — микрофон спит до кнопки/ответа
    }

    // v0.51 (ревью): адресата выбрали руками в списке сессий — приготовить его имя голосом,
    // иначе первое «слушаю, X» после ручного выбора уйдёт живым TTS, а это тихий путь.
    // Выбор канала руками с экрана — тоже ручное закрепление: без штампа правило
    // «отвечаю последнему говорившему» откручивало бы его на первом же подъёме микрофона
    fun onReplyTargetPicked() { manualPinTs = System.currentTimeMillis(); ensureTargetCues() }

    // ── v0.78: ЕДИНАЯ точка ручной смены адресата ────────────────────────
    // Боевое (скрин юзера): он диктовал в один канал, посреди речи переключился в
    // интерфейсе на другой — и ВСЁ сказанное уехало в новый. Причина в контуре whisper:
    // сегменты читают Cfg.replyTarget в МОМЕНТ ОТГРУЗКИ, а транскрайбер маршрутизирует
    // склеенный текст по мете последнего сегмента. Значит менять адресата посреди utt
    // нельзя вообще: сперва закрываем начатое в СТАРОГО, смену применяем по подтверждению.
    // v0.80: «речь в полёте» — ЕДИНЫЙ признак. Голый uttId им не является: в whisper-режиме
    // он создаётся при каждом подъёме микрофона и живёт, даже когда юзер молчит, — и раньше
    // блокировал правило «отвечаю тому, кто говорил последним» ПОЧТИ ВСЕГДА (боевая жалоба:
    // «ответила мета судья, а мне говорят слушаю voice bridge»).
    // v0.96 (скрин юзера: он ПРОСТО переключает канал, а рация говорит «отправляю начатое»,
    // шлёт мусор и отвечает «ничего не расслышал»). Виноват порог: uttSpeechMs > 0 — это
    // 100-200мс ветра, а не речь. Речью считаем то же, что и статус экрана: от 600мс,
    // либо живой голос ПРЯМО СЕЙЧАС (< 2.5с назад, а не 5).
    private fun speechInFlight(): Boolean =
        draft.isNotEmpty() ||
            (Cfg.whisper && uttId.isNotEmpty() &&
                (uttSpeechMs + segLiveMs > 600 || (recThread != null && System.currentTimeMillis() - lastVoiceTs < 2500)))

    @Volatile private var pendingTargetSid: String? = null
    private var pendingTargetListen = false
    private var pendingCloseUtt = ""      // какой именно utt дожидаемся
    private var pendingGen = 0            // чтобы старая страховка не стреляла по новому запросу

    fun setReplyTargetManual(sid: String, thenListen: Boolean = false) {
        touchUser()
        if (sid.isEmpty()) return
        if (sid == Cfg.replyTarget) {
            // повтор того же выбора: молчать нельзя — юзер повторяет команду, решив, что не дошло
            onReplyTargetPicked()
            say(tr("отвечаю в ", "replying to ") + spoken(replyTargetName()))
            if (thenListen) raiseMicFor(sid)
            return
        }
        // «речь в полёте» не требует поднятого микрофона: utt переживает опускание (ответ
        // прилетел — playNext сделал stopListening, а незакрытый utt живёт дальше). Именно
        // в этом окне юзер и переключал канал на скрине — и весь текст уезжал новому.
        if (speechInFlight()) {
            pendingTargetSid = sid
            pendingTargetListen = thenListen
            pendingCloseUtt = uttId
            val to = SessionBook.all().firstOrNull { it.sid == sid }?.proj?.ifEmpty { sid.take(8) } ?: sid.take(8)
            LogBus.add(tr("сначала дошлю начатое в «${replyTargetName()}», потом переключусь на «$to»", "will first send what was started to “${replyTargetName()}”, then switch to “$to”"))
            cue(tr("отправляю начатое, потом переключусь", "sending what you started, then switching"))
            buzz(180)
            if (Cfg.whisper) {
                // рекордер между циклами finalizeReq не увидит (startWhisperCycle его затрёт) —
                // закрываем utt пустым финальным сегментом сами
                if (recThread != null) finalizeReq = true
                else { uploadSeg(ByteArray(0), true); uttId = ""; uttSpeechMs = 0 }
            } else {
                pendingSend = true
                finishPendingSend()   // проверенный путь: гасит таймер, чистит догадку и окно
            }
            val g = ++pendingGen
            main.postDelayed({ if (pendingGen == g) applyPendingTarget("") }, 25000)
            return
        }
        applyTarget(sid)
        if (thenListen) raiseMicFor(sid)
    }

    private fun applyTarget(sid: String) {
        Cfg.replyTarget = sid
        targetEpoch++
        manualPinTs = System.currentTimeMillis()
        Cfg.save(this)
        soloFollow(sid)
        lastAnnouncedTarget = null
        ensureTargetCues()
        val n = replyTargetName()
        LogBus.add(tr("адресат: «$n»", "target: “$n”"))
        say(tr("отвечаю в ", "replying to ") + spoken(n))
        // имя адресата уже прозвучало — реплика подъёма микрофона повторила бы его же
        suppressListenCueUntil = System.currentTimeMillis() + 8000
    }

    // utt = чей финал пришёл. Применяем только своё ожидание: подтверждение ПРОШЛОГО
    // сообщения (потолок 45с дорезал монолог) иначе переключило бы адресата до того,
    // как уедет финальный сегмент текущего — и весь utt ушёл бы новому каналу.
    private fun applyPendingTarget(utt: String = "") {
        val s = pendingTargetSid ?: return
        if (pendingCloseUtt.isNotEmpty() && utt.isNotEmpty() && utt != pendingCloseUtt) return
        pendingTargetSid = null
        pendingCloseUtt = ""
        pendingGen++
        val listen = pendingTargetListen
        pendingTargetListen = false
        applyTarget(s)
        if (listen) raiseMicFor(s)
    }

    private fun raiseMicFor(sid: String) {
        if (Cfg.voiceOff) toggleVoiceOff(announce = false)   // v1.28
        dictHold = false
        if (boundarySid != null) boundaryHoldUntil = System.currentTimeMillis() + 12000
        if (playing || paused) stopPlayback(thenListen = true)
        else if (!listening) startListening()
        else { cue(listenCuePhrase()); buzz(90) }
    }

    // v0.73 (слово юзера: «где кнопка, чтобы я просто начал отвечать в конкретную сессию»):
    // один жест = адресат + микрофон. Идущая озвучка обрывается (её очередь чистится, как
    // у тангенты), накопленное в heldQueue остаётся.
    fun replyToSession(sid: String) = setReplyTargetManual(sid, thenListen = true)

    // v0.62 (слово юзера: «веду четырнадцать сессий, тринадцатью управляет агент, общаюсь я
    // только с ним — не хочу слышать остальных»). Старый режим «только текущая» глушил список
    // ИЗВЕСТНЫХ сессий разом, а это СНИМОК: сессии, которых телефон ещё не видел (агент поднял
    // тринадцать новых), в снимок не попадали и заговаривали свободно. Плюс SessionBook держит
    // только 20 записей — при таком обороте метка «замьючен» вытеснялась и сессия оживала сама.
    // Поэтому solo — ПРАВИЛО, а не снимок: звучит ровно один sid, всё остальное молчит всегда.
    private fun audible(sid: String): Boolean =
        // v0.82: набор рабочих каналов — правило. Пусто — слышу всех, кроме выключенных.
        // v1.28: голос выключен — не звучит никто, ответы остаются в ленте
        if (Cfg.voiceOff) false else if (Cfg.pickedSids.isNotEmpty()) picked().contains(sid) else !SessionBook.isMuted(sid)

    // ── v0.82: НАБОР рабочих каналов (solo = набор из одного) ────────────
    fun picked(): Set<String> =
        Cfg.pickedSids.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun nameOf(sid: String): String =
        SessionBook.all().firstOrNull { it.sid == sid }?.proj?.ifEmpty { sid.take(8) } ?: sid.take(8)

    fun pickedNames(): String = picked().joinToString(", ") { nameOf(it) }

    private fun savePicked(set: Set<String>) {
        Cfg.pickedSids = set.joinToString(",")
        // v1.11: имя канала запоминается рядом с sid. По книге сессий его сверять нельзя —
        // SessionBook держит 20 записей и вытесняет самые давние, то есть ровно тот канал,
        // с которым работают весь день.
        val prev = pickedNameMap()
        Cfg.pickedNames = set.joinToString(",") { sid ->
            val nm = (SessionBook.all().firstOrNull { it.sid == sid }?.proj?.takeIf { it.isNotEmpty() }
                ?: prev[sid].orEmpty()).replace(",", " ").replace("=", " ")
            "$sid=$nm"
        }
        Cfg.soloSid = if (set.size == 1) set.first() else ""   // совместимость со старой веткой
        Cfg.save(this)
    }

    private fun pickedNameMap(): Map<String, String> =
        Cfg.pickedNames.split(",").mapNotNull {
            val p = it.split("=", limit = 2)
            if (p.size == 2 && p[0].isNotBlank() && p[1].isNotBlank()) p[0].trim() to p[1].trim() else null
        }.toMap()

    // Добавить/убрать канал из рабочего набора. Возвращает: канал теперь в наборе?
    fun togglePicked(sid: String): Boolean {
        if (sid.isEmpty()) return false
        val set = picked().toMutableSet()
        val nowIn: Boolean
        if (set.contains(sid)) { set.remove(sid); nowIn = false } else { set.add(sid); nowIn = true }
        savePicked(set)
        if (nowIn) {
            // v0.84 (боевое: «выбрал два канала в работу, договорила мета судья, а рация
            // говорит слушаю voice bridge»). «В работу» — это про СЛЫШИМОСТЬ, а не про то,
            // кому отвечать: раньше добавление делало канал адресатом И штамповало ручной
            // выбор, а тот на три минуты глушил правило «отвечаю тому, кто говорил последним».
            // Теперь адресата трогаем, только если его нет или он вне набора, и БЕЗ штампа.
            val cur = Cfg.replyTarget
            if (cur.isEmpty() || !picked().contains(cur)) {
                Cfg.replyTarget = sid
                manualPinTs = 0L   // v1.16-fix: штамп был выбором ДРУГОГО канала — не наследуем
                Cfg.save(this)
                lastAnnouncedTarget = null
                ensureTargetCues()
            }
        } else if (Cfg.replyTarget == sid) {
            // адресат не должен убегать ЗА пределы набора — иначе говоришь туда, чего не слышишь
            Cfg.replyTarget = picked().firstOrNull() ?: ""
            manualPinTs = 0L
            Cfg.save(this)
            ensureTargetCues()
        }
        val n = picked().size
        LogBus.add(if (n == 0) tr("работаю со всеми каналами", "working with all channels") else tr("работаю с $n: ${pickedNames()}", "working with $n: ${pickedNames()}"))
        cue(if (n == 0) tr("слушаю все каналы", "listening to all channels") else if (nowIn) tr("добавил ", "added ") + spoken(nameOf(sid)) else tr("убрал ", "removed ") + spoken(nameOf(sid)))
        if (n > 0) { dropForeign(playQueue); dropForeign(heldQueue) }
        updateNotification()
        return nowIn
    }

    // v1.11 (боевое, 17.08: «почему автоозвучка не происходит?»). Claude Code при сжатии
    // контекста заводит сессии НОВЫЙ id — для рации это ранее невиданный канал, и в рабочий
    // набор он не входит. Сессия, с которой человек работает весь день, замолкает молча и
    // необъяснимо: в журнале «молчит: работаю с …», а его на ходу никто не читает.
    //
    // Наследуем выбор ПО ИМЕНИ, но с четырьмя замками — без них правка опаснее болезни
    // (имя проекта НЕ уникально: readback.py берёт его из имени рабочей папки, и все сессии
    // одной папки называются одинаково — у юзера бывало по четыре «playbook»):
    //   1) только канал, которого телефон НИКОГДА не видел (сжатие всегда даёт новый id);
    //   2) только если одноимённый выбранный канал молчит уже 5 минут — живая соседка по
    //      папке говорит чаще и выбор не украдёт;
    //   3) ЗАМЕНА, а не добавление: набор не растёт, дублей с одним именем не заводится;
    //   4) канал, убранный руками (долгий тап), не возвращается.
    // Адресат переезжает вместе с выбором — иначе диктовка ушла бы в закрытый id (v0.41).
    private fun inheritPick(sid: String, proj: String, firstTime: Boolean) {
        if (!firstTime || sid.isEmpty() || proj.isEmpty()) return
        if (Cfg.pickedSids.isEmpty()) return          // слышно всех — наследовать нечего
        if (SessionBook.isForgotten(this, sid)) return
        val set = picked()
        if (set.contains(sid)) return
        val names = pickedNameMap()
        val now = System.currentTimeMillis()
        val old = set.firstOrNull { o ->
            names[o] == proj &&
                now - (SessionBook.all().firstOrNull { it.sid == o }?.lastTs ?: 0L) > 300_000
        } ?: return
        savePicked(set - old + sid)
        if (Cfg.replyTarget == old) {
            Cfg.replyTarget = sid
            Cfg.save(this)
            lastAnnouncedTarget = null
            ensureTargetCues()
        }
        LogBus.add(tr("«${proj}» продолжился под новым номером — перенёс выбор со старого", "“${proj}” continued under a new number — carried the selection over from the old one"))
        cue(tr("канал ", "channel ") + spoken(proj) + tr(" продолжается", " continues"))
        updateNotification()
    }

    private fun soloName(): String {
        val p = picked()
        if (p.isEmpty()) return "—"
        return if (p.size == 1) nameOf(p.first()) else tr("${p.size} канала", "${p.size} channels")
    }

    fun soloActive(): Boolean = Cfg.pickedSids.isNotEmpty()
    fun soloLabel(): String = soloName()

    // Включение solo = ещё и ЗАКРЕПЛЕНИЕ адресата: если слышно только одну сессию, отвечать
    // тоже надо в неё, иначе «последняя игравшая» осталась бы прежней и голос уехал бы не туда.
    fun setSolo(sid: String) {
        if (sid.isEmpty()) return
        savePicked(setOf(sid))     // «только он» = набор ровно из одного
        Cfg.replyTarget = sid
        manualPinTs = System.currentTimeMillis()
        Cfg.save(this)
        // Всё, что уже успело прилететь от других сессий, слушать он не хочет — иначе после
        // включения режима в ухо доиграет ровно тот хор, из-за которого режим и включали.
        val dropped = dropForeign(playQueue) + dropForeign(heldQueue)
        if (playing && lastSession != null && lastSession != sid) stopPlayback(false)
        ensureTargetCues()
        LogBus.add(tr("слушаю только «${soloName()}»", "listening only to “${soloName()}”") + (if (dropped > 0) tr(", выбросил чужих частей: $dropped", ", dropped parts from others: $dropped") else ""))
        cue(tr("слушаю только ", "listening only to ") + soloName())
        updateNotification()
    }

    fun clearSolo() {
        savePicked(emptySet())
        // Кнопка обещает «вернуть всех», и реплика говорит «слушаю все сессии» — значит поштучные
        // мьюты надо снять тоже, иначе обещание окажется ложью. Заодно это ЕДИНСТВЕННЫЙ путь снять
        // их скопом: массовый мьют старой кнопки удалён вместе со снимочной механикой, а вручную
        // разглушать тринадцать строк в списке на ходу — не вариант.
        var un = 0
        for (s in SessionBook.all()) if (s.muted) { SessionBook.setMuted(this, s.sid, false); un++ }
        LogBus.add(tr("слушаю снова все сессии", "listening to all sessions again") + (if (un > 0) tr(", снял мьют с $un", ", unmuted $un") else ""))
        cue(tr("слушаю все сессии", "listening to all sessions"))
        updateNotification()
    }

    // v0.62: голосовая рубка меняет адресата — в режиме solo вместе с ним обязана переехать и
    // слышимая сессия, иначе выйдет «отвечаю туда, чего не слышу», а на ходу это не заметить.
    private fun soloFollow(sid: String) {
        // в наборе из НЕСКОЛЬКИХ каналов переезжать некуда — адресат просто внутри набора
        if (Cfg.pickedSids.isEmpty() || sid.isEmpty() || picked().contains(sid)) return
        if (picked().size > 1) return
        savePicked(setOf(sid))
        val dropped = dropForeign(playQueue) + dropForeign(heldQueue)
        if (playing && lastSession != null && lastSession != sid) stopPlayback(false)
        LogBus.add(tr("рубка: слушаю теперь только «${soloName()}»", "switchboard: now listening only to “${soloName()}”") +
            (if (dropped > 0) tr(", выброшено чужих частей: $dropped", ", parts from others dropped: $dropped") else ""))
    }

    private fun dropForeign(q: ArrayDeque<JSONObject>): Int {
        val set = picked()
        val keep = q.filter { set.contains(it.optString("session")) }
        val n = q.size - keep.size
        q.clear(); q.addAll(keep)
        return n
    }

    // v0.69: лента экрана показывает разговор адресата — ей нужен sid, не имя (у юзера
    // дубли имён проектов). Чтение без побочек, логика движка не меняется.
    fun replyTargetSid(): String {
        val u = uttId
        // сервер уже закрыл utt (rotateReq ждёт рекордера): следующее сообщение — новое, фиксация не его
        if (u.isNotEmpty() && u == dictSidUtt && dictSid.isNotEmpty() && !rotateReq) return dictSid
        return Cfg.replyTarget.ifEmpty { lastSession ?: "" }
    }

    fun replyTargetName(): String {
        val sid = replyTargetSid()
        if (sid.isEmpty()) return "—"
        return SessionBook.all().firstOrNull { it.sid == sid }?.proj?.ifEmpty { sid.take(8) }
            ?: lastProj ?: sid.take(8)
    }

    // ── playback position API (for the on-screen scrubber) ─────────────────
    fun isPlaying(): Boolean = playing
    // v0.22 (слово юзера: «ползунок продолжает двигаться, нажата пауза или нет»): в новой
    // «беззвучной паузе» плеер намеренно крутится дальше, чтобы не рвать поток на гарнитуру.
    // Наружу отдаём замороженную позицию — ту, с которой продолжим.
    fun positionMs(): Int = try {
        if (paused) pausedAtMs
        else if (cueHold) cueHoldAtMs          // v1.16: под репликой позиция заморожена — как пауза
        else if (playing) player?.currentPosition ?: 0 else 0
    } catch (_: Exception) { 0 }
    fun durationMs(): Int = try { val d = player?.duration ?: 0; if (d > 0) d else 0 } catch (_: Exception) { 0 }
    // v0.36 (аудит): в паузе перемотка обязана двигать ЗАМОРОЖЕННУЮ позицию (pausedAtMs) —
    // раньше resume делал seekTo(pausedAtMs) и отбрасывал всё, что юзер намотал в паузе.
    // v1.16: аналогично для cueHold — реплика держит позицию, и releaseCueHold делает seekTo(cueHoldAtMs);
    // если юзер в этот момент перемотал, НОВАЯ позиция должна уехать в cueHoldAtMs, иначе релиз затрёт перемотку.
    fun seekToMs(ms: Int) {
        val target = ms.coerceAtLeast(0)
        if (paused) pausedAtMs = target
        if (cueHold) cueHoldAtMs = target
        try { player?.seekTo(target) } catch (_: Exception) {}
    }
    fun nudgeMs(delta: Int) {
        try {
            val p = player ?: return
            val d = p.duration
            val base = if (paused) pausedAtMs
                        else if (cueHold) cueHoldAtMs   // v1.16: под репликой позиция заморожена
                        else p.currentPosition
            val target = (base + delta).coerceIn(0, if (d > 0) d else Int.MAX_VALUE)
            if (paused) pausedAtMs = target
            if (cueHold) cueHoldAtMs = target
            p.seekTo(target)
        } catch (_: Exception) {}
    }

    // Play an explicit list of audio URLs (a chosen message and everything after
    // it), replacing whatever is queued. Downloaded lazily like the live path.
    // v1.43 (решение юзера 16.10, журнал 08:11–08:14: после «озвучить» старое сообщение соседнего канала
    // адресат уезжал в тот канал). Переслушать — это чтение, не разговор: keepTarget=true не
    // трогает ни lastSession, ни «кто говорил последним», ни адресата. Голосовое «крайнее»
    // (keepTarget=false) ведёт себя по-старому — там юзер сам позвал последнего говорившего.
    // text — текст выбранного из ленты: если звук на релее уже удалён (404 у всех частей),
    // его прочтём вместо звука.
    @Volatile private var chosenSel = 0
    private var chosenTotal = 0
    private var chosenOk = 0
    private var chosenFail = 0
    private var chosenText = ""

    fun playFrom(sid: String, proj: String, items: List<Pair<String, Long>>,
                 text: String = "", keepTarget: Boolean = true) {
        if (items.isEmpty()) return
        stopPlayback()
        val sel = ++chosenSel
        chosenTotal = items.size; chosenOk = 0; chosenFail = 0; chosenText = text
        for ((ap, ts) in items) {
            playQueue.addLast(JSONObject().put("type", "voice").put("audio", ap)
                .put("session", sid).put("proj", proj).put("ts", ts)
                .put("sel", sel).put("keepTarget", keepTarget))
        }
        LogBus.add(tr("играю выбранное из «$proj»: ${items.size} ч.", "playing the selection from “$proj”: ${items.size} parts"))
        kickPlayback()
    }

    // v1.43 (журнал 16.10: «ошибка загрузки звука: HTTP 404» дважды — и сразу «слушаю, <канал>»).
    // Релей хранит только свежие файлы, лента помнит старые сообщения дольше. Все части выбранного
    // не скачались — одна фраза (или текст сообщения, если он есть), и микрофон НЕ поднимаем:
    // юзер просил послушать, а не говорить. Возвращает true, если провал выбранного обработан.
    private fun chosenPartDone(o: JSONObject, ok: Boolean): Boolean {
        val sel = o.optInt("sel", 0)
        if (sel == 0 || sel != chosenSel) return false
        if (ok) chosenOk++ else chosenFail++
        if (ok || chosenOk > 0 || chosenOk + chosenFail < chosenTotal) return false
        chosenSel++   // разобрались — последующие отказы этого выбора не повторят фразу
        noAutoWakeUntil = System.currentTimeMillis() + 15000
        val t = chosenText.trim()
        // v1.46 (ревью): ни звука, ни текста — юзер этот канал не услышал, адресат возвращается к прежнему
        val prev = replayPrev
        if (t.isEmpty() && prev != null && sel == replaySelSeen && lastSession == o.optString("session") && !speechInFlight()) {
            lastSession = prev.session
            lastProj = prev.proj
            speakerStartTs = prev.speakerTs
            answeredCurrent = prev.answered
            if (Cfg.replyTarget.isEmpty() && prev.target.isNotEmpty()) {
                Cfg.replyTarget = prev.target
                manualPinTs = prev.pinTs
                Cfg.save(this)
            }
            lastAnnouncedTarget = null
            getSharedPreferences("bridge", MODE_PRIVATE).edit().putString("lastSession", lastSession).apply()
        }
        replayPrev = null
        LogBus.add(tr("звук выбранного на релее уже удалён", "the selection's audio is gone from the relay") +
            (if (t.isNotEmpty()) tr(" — читаю текст", " — reading the text") else ""))
        val gone = tr("звук этого сообщения уже удалён", "this message's audio is gone")
        say(if (t.isNotEmpty()) gone + tr(", читаю текст. ", ", reading the text. ") + t.take(400) else gone)
        return true
    }

    // Button send: force-finalize the in-flight segment instead of dropping it,
    // then ship the draft. Safety flush after 2s if the recognizer stays quiet.
    private val flushSend = Runnable { finishPendingSend() }

    // v0.48 (боевое, слово юзера «включаю рацию, жму отправить — ничего не расслышал», ДОКАЗАНО
    // логами релея): при подключении релей отдаёт накопленные ответы пачкой (в замере — три части,
    // ~600КБ, минуты речи). Пока они играют ИЛИ ЖДУТ В ОЧЕРЕДИ, гейт полудуплекса не даёт подняться
    // рекордеру (startWhisperCycle/startRecognizerCycle), а микрофон при этом формально listening=true
    // — поэтому тангента идёт в отправку и упирается в пустоту. Реплика «ничего не расслышал» ВРАЛА:
    // рация не слушала, она в этот момент говорила сама. Нажатие отправки здесь = «прерви ответ и дай
    // сказать» — ровно то, что делает тангента во время самой озвучки (talkNow).
    // v0.80: в зазоре очередь непуста ПО ЗАМЫСЛУ (следующий канал ждёт), но рация НЕ говорит.
    // Прежняя «занятость» приводила отправку в stopPlayback, а он чистит очередь — ответ
    // второго канала уничтожался, не сыграв ни разу (ревью).
    // v1.04: держим озвучку до наушников ТОЛЬКО если это выбрано вручную. В авто-режиме
    // рация идёт за маршрутом: наушники есть — в них, нет — вслух.
    private fun holdForHeadset(): Boolean = !Cfg.autoRoute && Cfg.onlyHeadset && !headsetPresent()

    // v1.08 (боевое: «нажал отправить — сразу заиграл ответ, а „ушло“ прозвучало ПОСЛЕ него»):
    // подтверждение приходит с сервера через 2-3с, и очередь успевала стартовать раньше.
    // Держим очередь до подтверждения, но не дольше 4с — иначе сеть повесит воспроизведение.
    @Volatile private var awaitSendCueUntil = 0L
    private fun awaitingSendCue(): Boolean = System.currentTimeMillis() < awaitSendCueUntil

    private fun playbackBusy(): Boolean =
        playing || paused || batch.isNotEmpty() || fileQueue.isNotEmpty() ||
            (playQueue.isNotEmpty() && !gapOpen())

    private fun beginSend() {
        awaitSendCueUntil = System.currentTimeMillis() + 4000   // v1.08: «ушло» звучит первым
        if (Cfg.whisper) {
            if (recThread != null) { LogBus.add(tr("кнопка — отправляю", "button — sending")); finalizeReq = true; return }
            if (playbackBusy()) {
                LogBus.add(tr("жму отправить, а рация ещё говорит — прерываю ответ и поднимаю микрофон", "pressing send while the radio is still speaking — interrupting the reply and raising the microphone"))
                stopPlayback(thenListen = true)
                return
            }
            LogBus.add(tr("нечего отправлять — микрофон не пишет", "nothing to send — microphone is not recording"))
            cue(tr("ничего не расслышал", "didn't catch anything"))
            return
        }
        // тот же случай на пути Android-распознавателя: черновика нет, потому что микрофон
        // не работал — до v0.48 через 2с приходило «ничего не расслышал» из finishPendingSend
        if (draft.isEmpty() && lastPartial.isEmpty() && playbackBusy()) {
            LogBus.add(tr("жму отправить, а рация ещё говорит — прерываю ответ и поднимаю микрофон", "pressing send while the radio is still speaking — interrupting the reply and raising the microphone"))
            stopPlayback(thenListen = true)
            return
        }
        if (pendingSend) return
        pendingSend = true
        try { recognizer?.stopListening() } catch (_: Exception) {}
        main.postDelayed(flushSend, 2000L)
    }

    private fun finishPendingSend() {
        if (!pendingSend) return
        pendingSend = false
        main.removeCallbacks(flushSend)
        // v0.46-fix: onResults успевает вызвать onFinalText → scheduleAutoSend ПЕРЕД нами, и без
        // cancelAutoSend взведённый warn переживает обнуление окна: firstArmTs=0/lastTextTs=0
        // навсегда — беспричинный бип и досрочная отправка обрывка через 10с после ручной отправки.
        cancelAutoSend()
        resetSendWindow()   // v0.46
        val text = draft.toString().trim()
        draft.setLength(0)
        if (text.isNotEmpty()) lastPartial = ""   // v0.46-fix: как в sendNow — иначе fire дошлёт эту же догадку дублем
        // v0.50: микрофон больше НЕ остаётся горячим после отправки (см. sleepAfterSend).
        // Пустой черновик — не отправка, микрофон оставляем поднятым: человек только собрался.
        if (text.isNotEmpty()) sleepAfterSend() else if (!Cfg.handsFree) stopListening()
        else if (listening) startRecognizerCycle()
        if (text.isNotEmpty()) send(text) else {
            LogBus.add(tr("пустой черновик — не отправляю", "empty draft — not sending"))
            cue(tr("ничего не расслышал", "didn't catch anything"))   // v0.30: иначе нажатие выглядит как поломка
            buzz(300)
        }
    }

    // v0.36 (аудит): смерть voice-whisper на сервере была НЕВИДИМА — «ушло на распознавание»
    // и тишина. Теперь после финала ждём utt_sent/utt_empty максимум 25с, иначе сигналим.
    private fun clearUttAck(utt: String) {
        if (utt.isNotEmpty() && utt == pendingUttAck) {
            pendingUttAck = ""
            uttAckTimer?.let { main.removeCallbacks(it) }
            uttAckTimer = null
        }
    }

    // v1.16-fix: когда телефон закрыл utt — подтверждение сообщения, закрытого ДО ручного
    // выбора, этот выбор не снимает (ревью: старый ACK в X стирал новый выбор X)
    private val uttClosedAt = HashMap<String, Long>()

    // v1.41 (боевое, v1.40: «диктую длинный ответ во фриланс — посреди речи „ушло, дальше
    // voice bridge“, и заиграл чужой ответ»). Потолок (90с записи / 45с удержания) закрывал utt
    // финалом, сервер честно отвечал utt_sent, а тот говорил «ушло … дальше X» и пинал очередь.
    // Пока звучала фраза, рекордер глух (эхо-гейт), датчик речи протухал, uttSpeechMs обнулён
    // newUtt — придержка отпускала, и playNext гасил микрофон посреди мысли. Теперь дорезанное
    // потолком — ЧАСТЬ: уходит тому же адресату молча, очередь не трогаем, микрофон живёт.
    // «Ушло» прозвучит один раз — на настоящем конце диктовки (слово, кнопка, тишина).
    // Монолог = диктовка, опознаётся по listenStartedAt её подъёма микрофона. Каждый финал рекордер
    // метит своей диктовкой (uttDict), части — ещё и capPartUtts. ACK части ВСЕГДА молчит: живая
    // диктовка — чтобы не глушить рекордер; запоздалый — чтобы не было второго «ушло» после финала
    // или фразы посреди уже следующей диктовки (ревью v1.41).
    // Учёт по монологам, не одним слотом: ACK разных диктовок приходят вперемешку (ревью v1.41).
    private val uttDict = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val capPartUtts = java.util.concurrent.ConcurrentHashMap<String, Long>()   // часть → монолог
    @Volatile private var capPartAt = 0L          // когда потолок дорезал часть (рекордер)
    private val partDebt = HashMap<Long, String>()      // монолог → адресат: части доехали молча, «ушло» за нами
    private val confirmedDicts = HashSet<Long>()        // монологи, чьё подтверждение уже прозвучало
    private val endedOwingDicts = HashSet<Long>()       // финал пришёл раньше ACK частей — подтвердит их ACK
    private fun partsInFlight(dict: Long) = capPartUtts.values.contains(dict)
    private fun markConfirmed(dict: Long) {
        if (confirmedDicts.size > 20) confirmedDicts.clear()
        confirmedDicts.add(dict); partDebt.remove(dict); endedOwingDicts.remove(dict)
    }
    // диктовка события: метка рекордера; utt, закрытый сервером без финала телефона, — по времени
    // старта (uttId = base36 времени): начат после подъёма микрофона — значит, этой диктовки
    private fun dictOf(utt: String): Long = uttDict.remove(utt)
        ?: if ((utt.toLongOrNull(36) ?: 0L) >= listenStartedAt) listenStartedAt else -1L
    // долг «ушло» гасит только финал ТОГО ЖЕ монолога; чужой (запоздалый) его не трогает
    private fun takePartDebt(dict: Long): String {
        val sid = partDebt[dict] ?: return ""
        markConfirmed(dict)
        return sid
    }
    // монолог кончился пустым/отменённым хвостом, а его части ещё не подтверждены сервером
    private fun noteEndedOwing(dict: Long) {
        if (!partsInFlight(dict)) return
        if (endedOwingDicts.size > 20) endedOwingDicts.clear()
        endedOwingDicts.add(dict)
        LogBus.add(tr("части монолога ещё в пути — подтвержу, когда доедут", "monologue parts still in transit — will confirm when they arrive"))
    }

    private fun armUttAckTimer(utt: String) {
        if (uttClosedAt.size > 20) uttClosedAt.clear()
        uttClosedAt[utt] = System.currentTimeMillis()
        pendingUttAck = utt
        uttAckTimer?.let { main.removeCallbacks(it) }
        val t = Runnable {
            if (alive && pendingUttAck == utt) {
                pendingUttAck = ""
                LogBus.add(tr("сервер не подтвердил распознавание за 25с — сообщение могло застрять", "server did not confirm recognition within 25s — the message may be stuck"))
                cue(tr("распознавание молчит", "recognition is silent"))
                buzz(400)
            }
        }
        uttAckTimer = t
        main.postDelayed(t, 25000)
    }

    // ── relay connection ──────────────────────────────────────────────────
    private fun connect() {
        ws?.cancel()
        // v0.66 (ревью, КРИТИЧНО для store): в публичной сборке дефолты пустые, и
        // Request.Builder().url("") кидает IllegalArgumentException — первый же тап
        // ненастроенного новичка ронял бы сервис. Сохранение настроек перезапускает
        // сервис (v0.36), так что после настройки connect() честно повторится.
        if (Cfg.url.isBlank() || Cfg.token.isBlank()) {
            LogBus.add(tr("релей не настроен — открой настройки и вставь конфиг", "relay not configured — open settings and paste the config"))
            return
        }
        val wsUrl = Cfg.url.replaceFirst("https", "wss") +
            "/sub?role=phone&token=${Cfg.token}"
        val req = Request.Builder().url(wsUrl).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (!alive || webSocket !== ws) { if (webSocket !== ws) webSocket.cancel(); return@post }
                    val wasDown = !wsUp
                    wsUp = true
                    reconnectDelay = 3000L
                    LogBus.add(tr("связь с релеем установлена", "relay connection established"))
                    // v0.26: голосом — только на СМЕНУ состояния, иначе реконнекты засыплют репликами
                    if (wasDown && announcedLink != true) { announcedLink = true; cue(tr("рация на связи", "radio online")); buzz(60) }
                    // v0.36: досылаем то, что не ушло из-за сети (send() больше не теряет текст)
                    while (unsent.isNotEmpty()) {
                        val t = unsent.pollFirst() ?: break
                        LogBus.add(tr("досылаю сохранённое: ${t.take(50)}", "resending saved: ${t.take(50)}"))
                        send(t, attempt = 1, began = 0L)   // v1.43: старый текст — не ответ на текущее
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post {
                    if (!alive || webSocket !== ws) return@post
                    handleEvent(text)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post {
                    if (!alive || webSocket !== ws) return@post
                    wsUp = false
                    LogBus.add(tr("связь потеряна: ${t.message} — переподключусь", "connection lost: ${t.message} — will reconnect"))
                    if (announcedLink != false) { announcedLink = false; cue(tr("связь потеряна", "connection lost")); buzz(500) }
                    scheduleReconnect()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post {
                    if (!alive || webSocket !== ws) return@post
                    wsUp = false
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (!alive || !running) return
        main.postDelayed({ if (alive && running && !wsUp) connect() }, reconnectDelay)
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(30000L)
    }

    private fun handleEvent(text: String) {
        val o = try { JSONObject(text) } catch (e: Exception) { return }
        when (o.optString("type")) {
            "hello" -> {
                LogBus.add(tr("релей на связи", "relay is online"))
                // catch up on missed messages: never older than 30 min, dedup by ts
                if (!Cfg.catchup) return
                val recent = o.optJSONArray("recent") ?: return
                // v0.36: окно 30 мин считаем от СЕРВЕРНЫХ часов (ts событий — серверные);
                // телефон, спешащий на полчаса, молча отключал догон
                val serverNow = o.optLong("now", System.currentTimeMillis())
                val floor = maxOf(lastEventTs, serverNow - 30 * 60 * 1000L)
                var added = 0
                for (i in 0 until recent.length()) {
                    val ev = recent.optJSONObject(i) ?: continue
                    if (ev.optString("type") != "voice") continue
                    val ts = ev.optLong("ts")
                    if (ts <= floor) continue
                    if (!audible(ev.optString("session"))) continue
                    // v0.73: догон не смеет вернуть то, что юзер уже выбросил руками
                    if (skippedMsgIds.contains(ev.optString("session") + "|" + ev.optString("msgid"))) continue
                    // v0.36 (аудит): часть, уже ждущая в очереди (придержка/пауза), при реконнекте
                    // добавлялась ВТОРОЙ раз — теперь дедуп по пути аудиофайла
                    val ap = ev.optString("audio")
                    if (ap.isNotEmpty() && playQueue.any { it.optString("audio") == ap }) continue
                    if (ap.isNotEmpty() && heldQueue.any { it.optString("audio") == ap }) continue
                    heldQueue.addLast(ev)   // v0.49: ждёт команды, микрофон не блокирует
                    added++
                }
                if (added > 0) {
                    LogBus.add(tr("накопилось $added част. — микрофон свободен, говори; «слушать» проиграет их", "$added parts piled up — microphone is free, speak; “listen” will play them"))
                    // v0.68: раньше о догнанных ответах говорил только вибро-толчок — на ходу
                    // это «рация буркнула и молчит». Теперь называем вслух, что делать.
                    say(tr("есть ответы — нажми слушать", "replies waiting — press listen"))
                    buzz(140)               // толчок: есть что послушать
                    // v0.94: cue("есть ответы") УДАЛЁН — он обрывал фразу выше на полуслове,
                    // а говорил ровно то же, только без подсказки, что делать (аудит)
                    updateNotification()
                } else if (recent.length() > 0) {
                    LogBus.add(tr("догонять нечего", "nothing to catch up on"))
                }
            }
            // v0.35: сервер распознал и отправил надиктованное — подтверждаем юзеру
            "utt_sent" -> {
                clearUttAck(o.optString("utt"))
                // v1.41: часть монолога (дорезал потолок), а диктовка ещё открыта — молча.
                // Микрофон уже спит — диктовка кончилась, тогда это обычное «ушло».
                val part = capPartUtts.remove(o.optString("utt")) != null
                // v1.42: речь, которую сервер переселил в «<utt>r» после своего финала, — «ушло»
                // по монологу уже прозвучало. Такой id ещё и разбирается как base36 (×36), так
                // что ни штамп, ни диктовку из него брать нельзя
                val tail = o.optBoolean("orphan")
                val dict = dictOf(o.optString("utt"))
                val partLive = part && listening && dict == listenStartedAt
                LogBus.add((if (partLive) tr("часть монолога доехала (потолок), диктовка продолжается: ", "part of the monologue arrived (cap), dictation continues: ")
                    else if (part) tr("запоздалая часть монолога доехала: ", "late part of the monologue arrived: ") else tr("отправлено (whisper): ", "sent (whisper): ")) +
                    o.optString("text").take(60))
                // v0.81: ответ дан — зазор больше не приглашает.
                // v1.42 (журнал 16.10 07:52): подтверждение приходит через секунды после отправки,
                // а очередь за это время уже запустила СЛЕДУЮЩИЙ ответ — и чужое «ушло» засчитывалось
                // ему как ответ, зазор на его границе пропадал, следующий канал играл встык.
                // Ответом считаем только диктовку, начатую после того, как зазвучало текущее сообщение.
                val began = if (tail) 0L else if (dict > 0) dict else (o.optString("utt").toLongOrNull(36) ?: 0L)
                if (began >= speakerStartTs) answeredCurrent = true
                // v0.85: ручной выбор был НА ОДНО сообщение — оно ушло. v1.16-fix: только если
                // ушло В ВЫБРАННЫЙ канал и закрыто ПОСЛЕ выбора: запоздалое подтверждение
                // прошлого сообщения не должно стирать выбор, сделанный уже после него.
                // utt закрыл сервер — момента закрытия нет; тогда берём момент НАЧАЛА диктовки
                // (uttId = время старта в base36): начатое после выбора — это и есть то одно сообщение
                val closedAt = uttClosedAt.remove(o.optString("utt"))
                    ?: o.optString("utt").toLongOrNull(36) ?: 0L
                // v1.41: часть — сообщение ещё не кончилось, выбор живёт до его конца
                // v1.42 (ревью): хвост — продолжение уже ушедшего; выбор, сделанный после, не трогаем
                if (!part && !tail && o.optString("session") == Cfg.replyTarget && closedAt >= manualPinTs) manualPinTs = 0L
                lastSendOkTs = System.currentTimeMillis()   // v0.90
                awaitSendCueUntil = 0L                          // v1.08: подтверждение получено
                applyPendingTarget(o.optString("utt"))   // v0.78: начатое доехало старому — переключаемся
                // v0.69: эхо для ленты-чата — сам движок не меняется (см. Feed.kt)
                Feed.userSent(this, o.optString("session"), o.optString("text"), o.optBoolean("unread"), o.optLong("ts"))
                // v0.37: сервер знает, читает ли кто-то очередь адресата — «ушло» в мёртвую
                // сессию больше не притворяется успехом
                if (o.optBoolean("unread")) {
                    val deadSid = o.optString("session")
                    val who = SessionBook.all().firstOrNull { it.sid == deadSid }?.proj
                        ?: deadSid.take(8)
                    LogBus.add(tr("ВНИМАНИЕ: «$who» не забирает сообщения — та сессия закрыта или её приёмник умер. Текст ждёт в очереди", "WARNING: “$who” is not picking up messages — that session is closed or its receiver died. The text waits in the queue"))
                    // v0.41 (боевое «в какую бы сессию я не отвечал — не слушает»): виноват был
                    // ПРИКЛЕЕННЫЙ replyTarget на мёртвую сессию — все диктовки летели в неё,
                    // что бы юзер ни слушал. Мёртвый адресат отклеивается сам: следующие
                    // ответы пойдут в последнюю ИГРАВШУЮ сессию (за ней юзер и следит ушами).
                    // v0.96 (скрин юзера: «я захожу в канал, жму ОТВЕТИТЬ, а он говорит
                    // „адресат сброшен“ — я ничего не сбрасывал»). Канал мог не читать очередь
                    // по внешней причине (у сессии на компьютере умер приёмник), и отменять
                    // ЯВНЫЙ выбор юзера из-за этого нельзя: он выбрал руками минуту назад.
                    // Предупреждаем — но адресата оставляем.
                    val manualFresh = System.currentTimeMillis() - manualPinTs < 180000
                    if (deadSid.isNotEmpty() && deadSid == Cfg.replyTarget && !manualFresh) {
                        Cfg.replyTarget = ""
                        Cfg.save(this)
                        LogBus.add(tr("мёртвый адресат откреплён — отвечаю теперь в последнюю игравшую", "dead target unpinned — replying now to the last one that played"))
                        cue(tr("адресат сброшен, повтори", "recipient reset, say it again"))
                    } else if (System.currentTimeMillis() - lastUnreadCueTs > 10000) {
                        lastUnreadCueTs = System.currentTimeMillis()
                        cue(tr("канал не слушает", "channel is not listening"))
                        // v0.82: не добавлять «слушаю, X» поверх предупреждения (каша из фраз).
                        // Окно, а не флаг: подъём микрофона может случиться сильно позже, и тогда
                        // молчание было бы уже вредным («микрофон встал, а я не знаю»).
                        suppressListenCueUntil = System.currentTimeMillis() + 8000
                    }
                    buzz(400)
                    lastAnnouncedTarget = null
                }
                // v0.51 (дыра v0.50, найдена чтением transcribe.js + логов whisper): финал мог
                // сделать САМ СЕРВЕР — по смысловой тишине (10с звука без слов), по стоп-слову
                // или abandoned-флашем. В этом случае телефон только ротировал utt и продолжал
                // писать улицу: «одно сообщение на ответ» не работало ровно в том режиме, ради
                // которого затевалось (whisper на улице — замер 26.07 доказал, что телефонный
                // отсчёт тишины там не доходит до конца НИКОГДА, значит финал почти всегда серверный).
                // v0.51 (ревью): при unread НЕ спим — текст не дошёл, рация вслух просит
                // повторить, и заснувший микрофон означал бы повтор в выключенный микрофон.
                // v1.41 (ревью): ACK части обогнал newUtt рекордера — не спать: рекордер сам
                // откроет новый utt следующей строкой, монолог продолжается
                // v1.42 (боевое 15.10: «хвост после отправки отброшен (3с речи)» дважды за две
                // минуты): совпадение с uttId значит, что финал сделал СЕРВЕР, не телефон — тот
                // ротирует utt сам в момент отправки. Причину пишем в журнал (стоп-слово каким
                // словом). Причина боевого случая — обрубок «Отправляе» на обрезке сегмента по
                // длине; её лечит сервер (метка cut), хвост по-прежнему отбрасывается.
                if (!part && o.optString("utt") == uttId) {
                    val why = o.optString("why")
                    if (why.isNotEmpty()) LogBus.add(tr("сообщение закрыл сервер: ", "message closed by the server: ") + when (why) {
                        "send-word" -> tr("стоп-слово «", "stop word «") + o.optString("word") + "»"
                        "silence" -> tr("смысловая тишина", "semantic silence")
                        else -> why
                    })
                    if (listening && !o.optBoolean("unread")) sleepAfterSend() else rotateReq = true
                }
                // v0.52: реплика ПОСЛЕ решения о сне — одной фразой и куда ушло, и уснул ли микрофон.
                // v0.53: если ответ уже ждёт в очереди, длинная форма не нужна и вредна — микрофон
                // всё равно поднимется сразу после озвучки, а лишние полторы секунды реплики
                // отодвигают сам ответ.
                // v0.73 (слово юзера: «служебная фраза поверх идущего ответа»): гейт смотрел
                // только на ОЧЕРЕДЬ и молчал о том, что озвучка УЖЕ ИГРАЕТ — тогда «ушло,
                // микрофон спит» ложилось поверх ответа. Играет — подтверждаем вибро.
                if (tail) {
                    // v1.42: вторая фраза «ушло» на тот же монолог только путает; новая диктовка
                    // могла уже начаться — вибро, не речь поверх
                    if (!o.optBoolean("unread")) { LogBus.add(tr("хвост оборванного сообщения доехал", "tail of the cut-off message arrived")); buzz(180) }
                    kickPlayback()
                    return
                }
                if (part) {
                    // v1.41: ни фразы (она глушит рекордер и отпускает придержку), ни пинка очереди
                    // посреди речи. «Канал не слушает» выше остаётся: о мёртвом адресате молчать нельзя.
                    // Долг «ушло» — если финал этого монолога ещё не подтверждён.
                    if (!o.optBoolean("unread") && dict !in confirmedDicts) {
                        if (dict in endedOwingDicts) {
                            // хвост монолога уже закрыт пустым/отменой, а часть доехала только сейчас —
                            // это и есть подтверждение. Идёт новая диктовка — вибро, не фраза поверх речи.
                            markConfirmed(dict)
                            LogBus.add(tr("запоздалая часть закрытого монолога доехала — подтверждаю", "late part of a closed monologue arrived — confirming"))
                            if (!listening) cue(sentCuePhrase(o.optString("session")))
                            buzz(180)
                        } else partDebt[dict] = o.optString("session")
                    }
                    if (!partLive) kickPlayback()   // диктовка кончилась — держать очередь незачем
                    return
                }
                // v1.41 (ревью): хвост ушёл в канал, который не слушает, — «канал не слушает» выше
                // сказано про весь монолог; доехавшее начало отмечаем в журнале, долг гасим
                if (o.optBoolean("unread") && takePartDebt(dict).isNotEmpty())
                    LogBus.add(tr("начало монолога доехало, хвост ждёт в очереди канала", "start of the monologue arrived, tail waits in the channel queue"))
                markConfirmed(dict)   // это «ушло» покрывает и молча доехавшие части своего монолога
                // v1.41 (ревью): запоздалое «ушло» прошлого монолога, а юзер уже диктует новый —
                // фраза заглушила бы рекордер посреди речи; подтверждаем вибро
                val otherDictLive = listening && dict != listenStartedAt
                if (!o.optBoolean("unread") && otherDictLive) {
                    LogBus.add(tr("прошлое сообщение доехало — вибро, идёт новая диктовка", "previous message arrived — vibration, new dictation in progress"))
                    buzz(180)
                } else if (!o.optBoolean("unread")) {
                    val queuedNow = playQueue.isNotEmpty() || fileQueue.isNotEmpty()
                    // «звук реально слышен» = playing && !paused (пауза — это заглушение, v0.15)
                    val busy = playing && !paused
                    // v1.13 (боевое, слово юзера: «две сессии ответили, я ответил в первую —
                    // и не услышал, что ушло: сразу пошла озвучка второй»). Гейт v0.73 молчал
                    // при играющей озвучке, потому что тогда реплика ложилась ПОВЕРХ ответа.
                    // С v0.58 реплика приглушает ответ до четверти и возвращает громкость —
                    // накладки больше нет, а молчание стоит дорого: подтверждение отправки это
                    // ЕДИНСТВЕННЫЙ признак, что сообщение не потерялось.
                    // v1.15: поверх ответа фраза звучит одна — ответ держится (holdAnswerForCue)
                    // и продолжается с той же секунды; «ушло в X», если фраза уже готова файлом.
                    val sentSid = o.optString("session")
                    cue(when {
                        busy -> sentPhraseOverAnswer(sentSid)   // v1.15: ответ держится, фраза в тишине
                        queuedNow -> sentPhraseWithNext(sentSid)
                        else -> sentCuePhrase(sentSid)
                    })
                    buzz(180)
                }
                kickPlayback()
            }
            "utt_empty" -> {
                clearUttAck(o.optString("utt"))
                // v1.42 (review): a re-homed orphan id "<utt>r" parses as base36 into the CURRENT
                // dictation in dictOf — its empty/cancel must not settle the live monologue's debt
                // or speak over the user: vibrate only
                if (o.optBoolean("orphan")) { LogBus.add(tr("хвост оборванного сообщения: %s", "tail of the cut-off message: %s").format(o.optString("type"))); buzz(180); kickPlayback(); return }
                LogBus.add(tr("whisper ничего не расслышал", "whisper heard nothing"))
                applyPendingTarget(o.optString("utt"))   // v0.78: закрывать было нечего — переключаемся
                // v0.37: на фоновом шуме это событие может идти подряд — реплику не чаще раза в минуту
                // v1.00 (боевое, повтор: «нажал отправить, положил телефон — и через 10 секунд
                // „ничего не расслышал“»). Окно 12с оказалось мало: сервер отвечает по мере
                // очереди whisper. Плюс главный признак — СПЯЩИЙ микрофон: если юзер не
                // диктовал, эта пустота может быть только хвостом шума, и говорить о ней нечего.
                // v1.13 (аудит): признак «это хвост шума, молчим» ловил и живую диктовку —
                // нажал кнопку, наговорил, микрофон уснул, whisper на ветру не нашёл слов, и
                // сообщение исчезало БЕЗ ЕДИНОГО СЛОВА. Хвост отличается тем, что после
                // удачной отправки микрофон больше не поднимали.
                val justSent = (micAsleep || System.currentTimeMillis() - lastSendOkTs < 30000) &&
                    listenStartedAt <= lastSendOkTs
                // v1.41: после молча доехавших частей пустым вышел только хвост («отправляй» без
                // новых слов) — подтверждение всего монолога ещё не звучало, говорим его сейчас
                // Пустая сама часть (потолок дорезал ветер), а диктовка открыта — молчим: фраза
                // посреди речи глушит рекордер, ради чего всё и затевалось.
                val emptyPart = capPartUtts.remove(o.optString("utt")) != null
                val dict = dictOf(o.optString("utt"))
                if (emptyPart) {
                    LogBus.add(tr("часть монолога пуста (потолок дорезал шум) — молчу", "monologue part is empty (cap cut noise) — staying silent"))
                    if (dict in endedOwingDicts && !partsInFlight(dict)) {
                        // монолог закрыт, и ни одна его часть так и не доехала — честно сказать
                        endedOwingDicts.remove(dict)
                        if (!listening) cue(tr("ничего не расслышал", "didn't catch anything"))
                    }
                    if (!listening) kickPlayback()
                    return
                }
                val owedSid = takePartDebt(dict)
                if (owedSid.isNotEmpty()) {
                    LogBus.add(tr("хвост пуст, но части монолога доехали — подтверждаю", "tail is empty, but the monologue parts arrived — confirming"))
                    // сервер сам закрыл текущий utt (смысловая тишина) — это конец монолога, как
                    // в utt_sent: спим; закрытый id не переиспользуем (v0.51)
                    if (o.optString("utt") == uttId) {
                        if (listening) sleepAfterSend() else rotateReq = true
                    }
                    // идёт уже другая диктовка — вибро, не фраза поверх речи
                    if (!(listening && dict != listenStartedAt)) cue(sentCuePhrase(owedSid))
                    buzz(180)
                    kickPlayback()
                    return
                }
                if (partsInFlight(dict)) {
                    // пустой финал обогнал ACK частей: монолог кончен, подтвердит доехавшая часть
                    noteEndedOwing(dict)
                    if (o.optString("utt") == uttId) {
                        if (listening) sleepAfterSend() else rotateReq = true
                    }
                    kickPlayback()
                    return
                }
                if (System.currentTimeMillis() - lastEmptyCueTs > 60000 && (!playing || paused) && !justSent) {
                    lastEmptyCueTs = System.currentTimeMillis()
                    cue(tr("ничего не расслышал", "didn't catch anything"))   // v0.88/0.90: не поверх ответа и не сразу после «ушло»
                } else if (justSent) LogBus.add(tr("пустой хвост после отправки — молчу", "empty tail after sending — staying quiet"))
                buzz(300)
            }
            // v0.36: голосовая «отмена» теперь работает и в whisper-режиме — её ловит сервер
            "utt_cancelled" -> {
                clearUttAck(o.optString("utt"))
                // v1.42 (review): a re-homed orphan id "<utt>r" parses as base36 into the CURRENT
                // dictation in dictOf — its empty/cancel must not settle the live monologue's debt
                // or speak over the user: vibrate only
                if (o.optBoolean("orphan")) { LogBus.add(tr("хвост оборванного сообщения: %s", "tail of the cut-off message: %s").format(o.optString("type"))); buzz(180); kickPlayback(); return }
                val cancelPart = capPartUtts.remove(o.optString("utt")) != null
                val dict = dictOf(o.optString("utt"))
                LogBus.add(tr("отмена по голосу (whisper) — черновик стёрт", "voice cancel (whisper) — draft erased"))
                // v1.41 (ревью): «отмена» попала в кусок, дорезанный потолком, а диктовка идёт.
                // Монолог не кончен: долг «ушло» за прежние части остаётся до финала, очередь
                // держим. Но стёртый кусок — до 90с речи, молчать о потере нельзя: одна фраза,
                // придержка ответа продлена на неё (capPartAt), адресат не трогаем.
                if (cancelPart && listening && dict == listenStartedAt) {
                    LogBus.add(tr("отменён кусок монолога (потолок), диктовка продолжается", "monologue chunk (cap) cancelled, dictation continues"))
                    capPartAt = System.currentTimeMillis()
                    cue(tr("кусок отменён, слушаю", "chunk cancelled, listening"))
                    buzz(220)
                    return
                }
                applyPendingTarget(o.optString("utt"))   // v0.78: иначе смена висела бы до страховки
                // v1.41: дорезанные потолком части уже доехали — «отменено» про них было бы враньём
                val partsGone = takePartDebt(dict).isNotEmpty()
                if (!partsGone) noteEndedOwing(dict)
                if (partsGone) LogBus.add(tr("отменён только хвост — начало монолога уже доехало", "only the tail cancelled — the start of the monologue already arrived"))
                // v1.41 (ревью): запоздалая отмена прошлого монолога посреди новой диктовки — вибро
                if (listening && dict != listenStartedAt) LogBus.add(tr("прошлое сообщение отменено — вибро, идёт новая диктовка", "previous message cancelled — vibration, new dictation in progress"))
                else cue(if (partsGone) tr("начало ушло, хвост отменён", "start sent, tail cancelled") else tr("отменено", "cancelled"))
                buzz(220)
                if (o.optString("utt") == uttId) rotateReq = true
                kickPlayback()
            }
            // v0.36: сервер не знал, в какую сессию класть текст — честно сказать, а не молчать
            // v0.54: команда рации, перехваченная сервером («переключись на вторую», «какие сессии»).
            // Текст команды в сессию НЕ уходил — отвечаем на неё сами.
            "utt_cmd" -> {
                clearUttAck(o.optString("utt"))
                capPartUtts.remove(o.optString("utt")); uttDict.remove(o.optString("utt"))   // v1.41: не копим метки
                // команда закрыла utt на сервере — следующая мысль обязана уехать в НОВЫЙ,
                // иначе сервер выбросит её как хвост завершённого (done-ring transcribe.js)
                if (o.optString("utt") == uttId) rotateReq = true
                val c = o.optJSONObject("cmd")
                val src = c?.optString("src") ?: ""
                // v0.55 (ревью): в журнале видно, ЧТО именно было съедено командой — если
                // сработало ложно, текст не придётся вспоминать по памяти
                if (src.isNotEmpty()) LogBus.add(tr("команда рации из: «$src»", "radio command from: “$src”"))
                buzz(140)   // толчок: команда принята, даже если реплики выключены
                when (c?.optString("cmd")) {
                    "list" -> speakSessionList()
                    "switch" -> {
                        val n = c.optInt("n")
                        // v0.59: номера как раньше, но если номера нет — переключаемся по имени
                        if (n > 0) switchToSession(n) else switchByName(c.optString("name"))
                    }
                    // v0.56: снять закрепление голосом — вернуться к «последней игравшей»
                    // v0.93 (слово юзера: «сказал отбой — черновик стереть, микрофон
                    // выключить, ничего не отправлять»). Работает и посреди диктовки.
                    "sleep" -> {
                        val hadDraft = draft.isNotEmpty() || uttSpeechMs > 0
                        draft.setLength(0)
                        pendingSend = false
                        cancelAutoSend()
                        if (Cfg.whisper && recThread != null) cancelReq = true
                        LogBus.add(if (hadDraft) tr("отбой: черновик стёрт, микрофон спит", "cancel: draft erased, microphone asleep") else tr("отбой: микрофон спит", "cancel: microphone asleep"))
                        cue(if (hadDraft) tr("отбой, стёрто", "discarded, erased") else tr("отбой", "discarded"))
                        buzz(220)
                        noAutoWakeUntil = System.currentTimeMillis() + 600000   // 10 минут тишины
                        main.postDelayed({ if (alive) sleepAfterSend() }, 300)
                    }
                    "unpin" -> {
                        // v0.62: «открепи» снимает И режим solo. Иначе из него не выйти голосом
                        // вовсе (экранная кнопка на самокате недоступна), а режим этот глушит
                        // тринадцать сессий разом — запертым в нём остаться нельзя.
                        val wasSolo = Cfg.pickedSids.isNotEmpty()
                        Cfg.replyTarget = ""
                        Cfg.pickedSids = ""; Cfg.soloSid = ""
                        Cfg.save(this)
                        lastAnnouncedTarget = null
                        LogBus.add(
                            if (wasSolo) tr("рубка: закрепление и режим «только одна» сняты — слушаю всех", "switchboard: pin and “only one” mode cleared — listening to everyone")
                            else tr("рубка: закрепление снято — отвечаю в последнюю игравшую", "switchboard: pin cleared — replying to the last one that played")
                        )
                        say(if (wasSolo) tr("слушаю все сессии", "listening to all sessions") else tr("отвечаю в последнюю игравшую", "replying to the last one that played"))
                    }
                    else -> LogBus.add(tr("команда рации не разобрана", "radio command not parsed"))
                }
                kickPlayback()
            }
            // v0.60: сервер набрал 12с звука без распознанных слов и собирается отправить
            // по «смысловой тишине». Предупредительный бип — тот же контракт, что у телефонного
            // отсчёта: юзер слышит сигнал ДО отправки и может продолжить мысль.
            "utt_warn" -> {
                beepBoth(ToneGenerator.TONE_PROP_BEEP, 90)
                buzz(140)
                LogBus.add(tr("сервер не слышит слов 12с — если не закончил, говори дальше", "server hears no words for 12s — if you are not done, keep talking"))
            }
            "utt_lost" -> {
                clearUttAck(o.optString("utt"))
                capPartUtts.remove(o.optString("utt")); uttDict.remove(o.optString("utt"))   // v1.41: не копим метки
                LogBus.add(tr("сообщение НЕ доставлено: релей не знает сессию-адресата", "message NOT delivered: relay does not know the target session"))
                applyPendingTarget(o.optString("utt"))
                beepBoth(ToneGenerator.TONE_PROP_NACK, 300)
                buzz(400)
            }
            "voice" -> {
                val sid = o.optString("session")
                // «видели ли мы этот канал раньше» надо снять ДО seen — она сама заводит запись
                val firstTime = SessionBook.all().none { it.sid == sid }
                SessionBook.seen(this, sid, o.optString("proj"), o.optLong("ts"))
                inheritPick(sid, o.optString("proj"), firstTime)
                // v0.73: юзер выбросил это сообщение в разборе накопленного — его хвост
                // (части приходят с разрывом до минуты) играть нельзя, он его отменил
                if (skippedMsgIds.contains(sid + "|" + o.optString("msgid"))) {
                    LogBus.add(tr("хвост пропущенного сообщения «${o.optString("proj")}» — не играю", "tail of a missed message “${o.optString("proj")}” — not playing"))
                    return
                }
                if (Cfg.voiceOff) {
                    // v1.28: ответ уже в ленте; отметить как увиденный, чтобы догон не поднял его
                    lastEventTs = maxOf(lastEventTs, o.optLong("ts"))
                    LogBus.add(tr("«${o.optString("proj")}» ответил — голос выключен, только лента", "“${o.optString("proj")}” replied — voice off, feed only"))
                    return
                }
                if (!audible(sid)) {
                    LogBus.add(
                        if (Cfg.pickedSids.isNotEmpty()) tr("«${o.optString("proj")}» молчит: работаю с «${pickedNames()}»", "“${o.optString("proj")}” is silent: working with “${pickedNames()}”")
                        else tr("«${o.optString("proj")}» замьючен — пропускаю", "“${o.optString("proj")}” is muted — skipping")
                    )
                    return
                }
                playQueue.addLast(o)
                LogBus.add(tr("голос из «${o.optString("proj")}» (${o.optInt("part")}/${o.optInt("parts")})", "voice from “${o.optString("proj")}” (${o.optInt("part")}/${o.optInt("parts")})"))
                kickPlayback()
            }
        }
    }

    // ── playback ──────────────────────────────────────────────────────────
    private var holdSince = 0L   // v0.5.3: этикет рации — не перебивать активную диктовку
    @Volatile private var lastRealSpeechTs = 0L   // v0.98: когда последний раз были СЛОВА, не шум
    private var holdTicking = false   // v0.10: придержку тикает РОВНО один цикл, не по циклу на часть

    // v0.10 (боевое «ответ играет полсекунды, обрывается, и сразу предлагают диктовать новое»):
    // playNext() НЕ реентерабелен — он безусловно забирает элемент из очереди и пересоздаёт плеер.
    // А входов в него много и они копятся: каждая прилетевшая ЧАСТЬ ответа заводила свой
    // retry-цикл придержки (1500мс), плюс отдельный вход из send(). После отправки все они
    // просыпались по очереди и каждый сносил player'а предыдущего — очередь пролетала за секунды,
    // из каждой части звучало по полслова, а в конце очередь пустела и включалось «слушаю».
    // Теперь любой ВНЕШНИЙ вход идёт сюда: играет — не трогаем, цепочку продолжает onCompletion.
    // v0.57: зазор на границе ответов разных сессий — см. playNext
    private var boundarySid: String? = null
    private var boundaryHoldUntil = 0L
    private val BOUNDARY_ANSWER_MS = 12000L   // окно на ответ в зазоре между каналами
    private val cueQueue = ArrayDeque<File>()   // v0.94: реплики договаривают по очереди
    // v1.15 (слово юзера: «системная фраза и голос ответа звучат одновременно»; и раньше:
    // «приглушения быть не должно — как будто отдали управление: сначала системное, потом
    // сообщение»). Ответ под репликой больше не приглушается до четверти, а ДЕРЖИТСЯ:
    // звук в ноль, позиция запомнена, после реплики — возврат ровно на эту секунду.
    // Плеер при этом продолжает крутиться в тишине — останавливать его нельзя (урок v0.15:
    // остановленный плеер рвёт A2DP, и кнопка гарнитуры умирает).
    @Volatile private var cueHold = false
    @Volatile private var cueHoldAtMs = 0
    @Volatile private var cueHoldSince = 0L
    @Volatile private var cuePendingUntil = 0L // реплика ждёт устаканивания SCO — она ещё впереди
    @Volatile private var scoStoppedAt = 0L    // v0.97: когда канал гарнитуры начали гасить
    @Volatile private var cueWaitFrom = 0L     // с какого момента реплика ждёт конца записи
    private var boundarySuppress = false   // v0.75: явное «слушать» — этот заход без зазора
    // v0.81 (боевое: «говорит „ответь такому-то“, хотя я ему уже ответил»): зазор нужен
    // ТОЛЬКО пока ответа от юзера не было. Раньше эту роль играл micAsleep, но он гаснет
    // при первом же пробуждении микрофона — и приглашение звучало снова, впустую.
    @Volatile private var answeredCurrent = false
    // v1.02 (боевое: «сказал отбой, он уснул — и через полминуты сам поднял микрофон»):
    // после команды сна рация не будит себя САМА (ни после озвучки, ни в зазоре). Будит
    // только явное действие юзера: кнопка гарнитуры, тангента, круг.
    @Volatile private var noAutoWakeUntil = 0L
    @Volatile private var suppressListenCueUntil = 0L   // v0.82: не «слушаю» сразу после «не слушает»
    // v0.90 (боевое: «ушло, мета судья» — и сразу «ничего не расслышал»): после отправки
    // хвост шума (ветер, дорога) уезжает отдельным utt, и whisper честно возвращает пустоту.
    // Юзер уже получил «ушло» — вторая фраза только пугает: она про мусор, а не про его речь.
    @Volatile private var lastSendOkTs = 0L

    private var cueWaitTicking = false   // v0.53: ровно один цикл ожидания реплики
    private var cueWaitSince = 0L

    private fun kickPlayback() {
        if (playing || Cfg.voiceOff) return   // v1.28: вторая линия — без голоса не играет ничего
        // v0.53 (слово юзера, найдено им в поле: «включается два голоса разом — что ушло и что
        // микрофон спит, и поверх сразу озвучка ждавшего ответа»). Реплики играет cuePlayer, ответы —
        // player; это независимые источники, и на стыках они накладывались. Внешний вход в
        // воспроизведение теперь ждёт, пока реплика договорит (+700мс хвоста в ttsGateActive).
        // Потолок 3с: залипшая реплика не имеет права держать очередь ответов.
        // v0.55 (ревью): фраза может быть ещё В СИНТЕЗЕ — cuePlayer тогда пуст, гейт пропускал
        // озвучку, и список сессий заигрывал ПОВЕРХ ответа. Ждём и её тоже.
        if ((ttsGateActive() && cuePlayer != null) || cuePlayWhenReady.isNotEmpty() ||
            System.currentTimeMillis() < cuePendingUntil || awaitingSendCue()) {
            val now = System.currentTimeMillis()
            if (cueWaitSince == 0L) cueWaitSince = now
            if (now - cueWaitSince < 3000) {
                if (!cueWaitTicking) {
                    cueWaitTicking = true
                    main.postDelayed({
                        cueWaitTicking = false
                        if (alive) kickPlayback()
                    }, 250)
                }
                return
            }
        }
        cueWaitSince = 0L
        playNext()
    }

    // v1.47 (ревью): ожидание хвоста перепроверяет очередь раз в секунду. Каждый чужой входящий
    // зовёт kickPlayback, и каждый заводил свою цепочку — они копились. Теперь цепочка одна.
    private var tailTicking = false
    private fun tailTick() {
        if (tailTicking) return
        tailTicking = true
        main.postDelayed({ tailTicking = false; if (alive && !playing) kickPlayback() }, 1000)
    }

    private fun playNext() {
        if (paused) return   // v0.9: в паузе ничего не запускаем поверх — продолжение только кнопкой
        // v0.5.3 (слово юзера: «ответ на первое включается, пока я диктую второе»): если идёт диктовка
        // с непустым черновиком — ответ ЖДЁТ в очереди и заиграет после отправки/отмены (send/cancel
        // дёргают playNext). Страховка от вечного молчания: держим не дольше 90с.
        // v0.7.2: держим ответ и когда речь ТОЛЬКО началась (черновик ещё пуст, но RMS слышит голос) —
        // раньше ответ, прилетевший в первые секунды диктовки, играл ПОВЕРХ юзера.
        // v0.7.3: между частями ОДНОГО сообщения (batch непуст) придержка не применяется — ответ
        // прерывал сам себя: его звук долетал в микрофон, RMS считал это «голосом юзера».
        // v0.19 (боевое: «доходят озвучки из других сессий и перебивают, не успеваю договорить»):
        // придержка смотрела на lastLoudTs — старый ЖЁСТКИЙ порог 7dB, который на улице слеп
        // (ветер поднимает фон, голос его не превышает). Тот же недосмотр, что чинили в v0.11
        // для таймера тишины, только здесь я его не поправил. Теперь придержка слушает и
        // адаптивный lastVoiceTs, и окно расширено до 4с — человек делает паузы внутри мысли.
        // v0.36: в whisper-режиме draft всегда пуст — «черновиком» считается незавершённый utt
        // с речью (рекордер теперь ЖИВ во время придержки и кормит lastVoiceTs, см. whisperLoop)
        // v0.46: придержка смотрела на тот же ослепший датчик — пока он слеп, прилетевший ответ
        // играл ПОВЕРХ говорящего человека, а playNext делал stopListening, и надиктованное
        // оставалось в черновике без отсчёта. Теперь — единый признак живости.
        // lastLoudTs (жёсткий порог 7) оставлен как НЕЗАВИСИМОЕ второе мнение: если завести оба
        // сигнала от одного детектора, двойная страховка схлопнется в одну.
        // v0.99 (боевое: «жму кнопку на наушниках, чтобы сказать второе сообщение, и меня
        // перебивает готовый ответ»). Между подъёмом микрофона и первым словом есть окно, где
        // «говорит ли он» ещё ложно — и ответ прорывался ровно в него. Явный подъём (кнопка,
        // тангента, круг) САМ ПО СЕБЕ означает «сейчас скажу»: даём 10с на начало речи.
        // v1.43 (журнал 16.10 08:12: «продолжаю диктовку» → микрофон ждал гарнитуру и ушёл на
        // телефон → через 13с ответ заиграл поверх диктовки). 10с считались от подъёма, а ожидание
        // канала гарнитуры (до ~8с) съедало их целиком: человек ещё не мог говорить, а окно уже
        // кончилось. Теперь 10с — от РЕАЛЬНОГО старта записи; пока запись не встала — держим
        // (не дольше 20с от подъёма). Продолженная диктовка («продолжаю диктовку») — открытый
        // черновик сама по себе: держим до отправки/отмены, под общим потолком ниже.
        val nowH = System.currentTimeMillis()
        val raisedByHand = listening && userActionTs > listenStartedAt - 2000
        val justRaisedByHand = raisedByHand &&
            (if (micLiveAt == 0L) nowH - listenStartedAt < 20000 else nowH - micLiveAt < 10000)
        val speakingNow = draft.isNotEmpty() || justRaisedByHand ||
            (listening && listenIsResume) ||
            // v1.43: recThread == null — это ожидание канала/перезапуск рекордера, а не конец
            // черновика: сказанное в открытый utt по-прежнему ждёт отправки
            // v1.48: + речь неотгруженного сегмента — после реза потолком uttSpeechMs обнулён,
            // и до следующей отгрузки (~20с) придержка держалась только 4-секундными датчиками
            (Cfg.whisper && uttId.isNotEmpty() && uttSpeechMs + segLiveMs >= 600) ||
            // v1.41: потолок только что дорезал часть — newUtt обнулил uttSpeechMs, но человек
            // посреди мысли; окно короткое, дальше живость держат датчики ниже
            (Cfg.whisper && recThread != null && System.currentTimeMillis() - capPartAt < 6000) ||
            System.currentTimeMillis() - lastActivityTs < 4000 ||
            System.currentTimeMillis() - lastLoudTs < 4000
        if (!playing && listening && batch.isEmpty() && speakingNow) {
            val now = System.currentTimeMillis()
            if (holdSince == 0L) {
                holdSince = now
                LogBus.add(tr("ответ подожду — ты диктуешь", "will wait with the reply — you are dictating"))
                buzz(140)   // v0.26: толчок = «ответ пришёл и ждёт, пока ты договоришь»
            }
            // v0.46: признак живости стал чутче — придержка теперь РЕАЛЬНО доживает до потолка
            // (в v0.45 её отпускал ослепший датчик через 4с). Резать его в этот момент нельзя:
            // 45с < HARD_CAP_MS (90с) означало бы, что самая длинная разрешённая ЭТИМ ЖЕ пакетом
            // диктовка перебивается гарантированно, а речь в окне озвучки не пишется вовсе
            // (playNext делает stopListening, микрофон возвращается только после очереди).
            // v0.98 (боевое: «машина перебила меня посреди длинной мысли»): потолок 90с был
            // АБСОЛЮТНЫМ, и длинный монолог гарантированно перебивался. Теперь он продлевается,
            // пока идёт РЕАЛЬНАЯ речь (распознанные слова, а не шум): 90с всегда, дальше —
            // пока последняя речь моложе 25с, но не дольше 5 минут (иначе ветер держал бы вечно).
            val realFresh = now - lastRealSpeechTs < 25000
            if (now - holdSince < HARD_CAP_MS || (realFresh && now - holdSince < 300000)) {
                if (!holdTicking) {
                    holdTicking = true
                    main.postDelayed({ if (alive) { holdTicking = false; kickPlayback() } }, 1500)
                }
                return
            }
        }
        holdSince = 0L
        // replay files first, then fresh events
        val localFile = fileQueue.pollFirst()
        if (localFile != null) {
            playing = true
            // v0.36 (аудит): в безруком режиме тут был голый recognizer.cancel() — listening
            // оставался true, и после доигрыша startListening выходил по гейту: «СЛУШАЮ» при
            // мёртвом распознавателе до 2 минут. Честный stopListening — рестарт после очереди
            // (ветка пустой очереди) отработает штатно.
            stopListening()
            playFile(localFile)
            return
        }

        // v0.12 (слово юзера: «когда озвучивает ответы от двух разных сессий…»): очередь общая, и
        // части двух сессий, отвечающих одновременно, играли ВПЕРЕМЕШКУ в порядке прихода — каша
        // из двух ответов. Хуже: `lastSession` переставлялся на каждой части, поэтому твой ответ
        // уходил в ту сессию, чей кусок случайно сыграл последним. Теперь пока сообщение сессии
        // не доиграно, её части имеют приоритет; вторая сессия ждёт своей очереди целиком.
        val o = (if (currentSid != null) playQueue.firstOrNull { it.optString("session") == currentSid } else null)
            ?: playQueue.peekFirst()
        // v1.46 (слово юзера: «два ответа разных сессий играют встык»). Ожидание хвоста (v0.42)
        // жило только на ПУСТОЙ очереди: стоило другой сессии прислать ответ, пока часть 2 из 3
        // ещё синтезируется, — и чужой ответ влезал в середину, а зазор (он требует доигранного
        // сообщения) не срабатывал вовсе. Теперь хвост ждём и при чужом в очереди, тем же потолком
        // 60с; переслушанное с экрана (явное «слушать») не ждёт.
        if (o != null && currentSid != null && lastPartNum < lastPartsTotal &&
            o.optString("session") != currentSid && !o.optBoolean("keepTarget", false)
        ) {
            val now = System.currentTimeMillis()
            if (partWaitDeadline == 0L) {
                partWaitDeadline = now + 60000
                LogBus.add(tr("сообщение не докачано ($lastPartNum/$lastPartsTotal) — «${o.optString("proj")}» подождёт хвост", "message incomplete ($lastPartNum/$lastPartsTotal) — “${o.optString("proj")}” waits for the tail"))
            }
            if (now < partWaitDeadline) {
                playing = false
                abandonFocus()
                updateNotification()
                tailTick()
                return
            }
            LogBus.add(tr("часть ${lastPartNum + 1}/$lastPartsTotal так и не пришла — считаю сообщение конченым", "part ${lastPartNum + 1}/$lastPartsTotal never arrived — treating the message as finished"))
            partWaitDeadline = 0L
            lastPartsTotal = lastPartNum
        }
        if (o == null) {
            // v1.47 (ревью): «дальше» без следующего — пропуск зазора не потреблён, позже он подавил бы чужой
            boundarySuppress = false
            // v0.42: сообщение сыграно не целиком (часть N из M) — ждём хвост, микрофон
            // НЕ поднимаем (batch не сбрасываем — он держит гейт диктовки)
            if (currentSid != null && lastPartNum < lastPartsTotal) {
                val now = System.currentTimeMillis()
                if (partWaitDeadline == 0L) {
                    partWaitDeadline = now + 60000
                    LogBus.add(tr("сообщение не докачано ($lastPartNum/$lastPartsTotal) — жду следующую часть", "message incomplete ($lastPartNum/$lastPartsTotal) — waiting for the next part"))
                }
                if (now < partWaitDeadline) {
                    playing = false
                    abandonFocus()
                    updateNotification()
                    tailTick()
                    return
                }
                LogBus.add(tr("часть ${lastPartNum + 1}/$lastPartsTotal так и не пришла — считаю сообщение конченым", "part ${lastPartNum + 1}/$lastPartsTotal never arrived — treating the message as finished"))
                partWaitDeadline = 0L
                lastPartsTotal = lastPartNum
            }
            currentSid = null
            if (playing || batch.isNotEmpty()) {
                if (batch.isNotEmpty()) { lastBatch = batch.toList(); batch = mutableListOf() }
                playing = false
                abandonFocus()
                beepBoth(ToneGenerator.TONE_PROP_BEEP, 150)
                if (Cfg.handsFree && !dictHold && System.currentTimeMillis() > noAutoWakeUntil)
                    main.postDelayed({ if (alive && !dictHold && System.currentTimeMillis() > noAutoWakeUntil) startListening() }, 800)
            }
            playing = false
            updateNotification()
            return
        }

        if (holdForHeadset()) {
            boundarySuppress = false   // v1.47: ждём наушники неопределённо долго — пропуск зазора к тому времени не про это
            playing = false
            abandonFocus()
            LogBus.add(tr("жду наушники (${playQueue.size} в очереди)", "waiting for headphones (${playQueue.size} in queue)"))
            return
        }

        // v0.57 (боевое, слово юзера с самоката: «прослушал ответ сессии X, а ответить не успел —
        // сразу пошёл ответ сессии Y, адресат уехал за ним, и X осталась без ответа молча»).
        // Раньше очередь играла ответы РАЗНЫХ сессий встык, и зазора, в который можно вклиниться,
        // не было вовсе. Теперь на границе между сессиями рация делает паузу и поднимает микрофон:
        // хочешь ответить прошлой — говори сейчас, придержка озвучки (speakingNow выше) сама
        // додержит следующий ответ, пока ты не договоришь.
        // v0.65: зазор ждал ответа до 90с. v0.68 (боевое, слово юзера: «говорит, кто будет
        // отвечать, — и НИКТО не отвечает, ни одну не озвучил»): 90 секунд тишины в поле
        // неотличимы от смерти рации. И хуже: зазор срабатывал при СПЯЩЕМ микрофоне — то есть
        // когда юзер уже ОТВЕТИЛ и очередь держать не за чем; два ответа из разных сессий на
        // спящем микрофоне давали объявление «дальше такая-то» и полторы минуты немоты.
        // Теперь: micAsleep => ответ уже отправлен => зазора НЕТ, играем встык. А живой зазор —
        // 12с НА НАЧАЛО РЕЧИ: заговорил — придержка speakingNow (выше) сама додержит очередь,
        // пока не договоришь; отправка (sleepAfterSend) отпускает её немедленно; молчишь 12с —
        // очередь едет дальше, как в старых версиях.
        // v0.75 (боевое, слово юзера: «ответы разных сессий валятся кучей встык, потом не
        // вспомнить, кто что говорил и кому отвечать»): зазор больше НЕ требует безрукого
        // режима — с телефоном в руках он нужен ровно так же. Разница только в микрофоне:
        // в безруком он встаёт сам, в кнопочном ждём тангенту/круг («ответить в X»).
        // v0.80 (боевое, третья жалоба подряд: «два ответа разных сессий сыграли встык,
        // ответить первой не дали»). КОРЕНЬ найден чтением: зазор требовал currentSid==null
        // и пустого batch, а они обнуляются ТОЛЬКО когда очередь опустела целиком. Ровно в
        // накопленной очереди (реконнект, две сессии) она не пустеет — значит зазор там не
        // мог сработать НИ РАЗУ, он жил только на редком случае «ответы пришли с паузой».
        // Теперь признак честный: у текущего канала в очереди не осталось частей И его
        // сообщение доиграно. Условие !micAsleep снято: юзер ответил ОДНОМУ каналу, это не
        // причина лишать его ответа второму.
        val nextSid = o.optString("session")
        val moreOfCurrent = currentSid != null && playQueue.any { it.optString("session") == currentSid }
        val msgDone = lastPartsTotal <= lastPartNum
        val keepTgt = o.optBoolean("keepTarget", false)
        if (boundarySuppress) {
            boundarySuppress = false
        } else if (!keepTgt && !dictHold && !moreOfCurrent && msgDone && !answeredCurrent &&
            // v0.90.1 (боевое: «ответил, отправил — а он говорит „ответь мета судья“,
            // потом „отправлено“, потом „слушаю…“ — всё в кашу»): сервер подтверждает
            // отправку 2-3 секунды, и в это окно зазор считал, что ответа ещё не было.
            uttAckTimer == null && !finalizeReq &&
            lastSession != null && nextSid.isNotEmpty() && nextSid != lastSession
        ) {
            val now = System.currentTimeMillis()
            if (boundarySid != nextSid) {
                boundarySid = nextSid
                boundaryHoldUntil = now + BOUNDARY_ANSWER_MS
                // предыдущее сообщение закрыто: иначе его части держали бы приоритет и
                // состояние тянулось бы в следующий канал
                if (batch.isNotEmpty()) { lastBatch = batch.toList(); batch = mutableListOf() }
                currentSid = null
                // v0.76 (боевое, самокат: «звучит ответ одной сессии, а мне говорят „слушаю
                // voicebridge“ — какого фига»): зазор поднимал микрофон, но адресатом оставался
                // ранее ЗАКРЕПЛЁННЫЙ (его ставит replyToLatest сам, по свежести на релее).
                // Смысл зазора — ответить ТОМУ, КТО ТОЛЬКО ЧТО ГОВОРИЛ; им и делаем адресата.
                // Открепляем в ДИНАМИКУ (пустой replyTarget = «последняя игравшая»), а не
                // прибиваем к sid: прибитый адресат — болезнь v0.41, он же через одно
                // сообщение снова разошёлся бы с тем, кого слушают. И только когда диктовка
                // НЕ в полёте: сегменты читают Cfg.replyTarget в момент отгрузки, смена
                // посреди utt увезла бы уже сказанное в чужую сессию (ревью v0.76).
                val spoke = lastSession
                if (spoke != null && audible(spoke) && Cfg.replyTarget.isNotEmpty() &&
                    Cfg.replyTarget != spoke && !speechInFlight() && !manualPinHolds()
                ) {
                    Cfg.replyTarget = ""
                    Cfg.save(this)
                    lastAnnouncedTarget = null
                    ensureTargetCues()
                    LogBus.add(tr("зазор: открепляю адресата — отвечаю тому, кто договорил («${lastSpokenName()}»)", "gap: unpinning target — replying to whoever finished speaking (“${lastSpokenName()}”)"))
                }
                val who = o.optString("proj").ifEmpty { nextSid.take(4) }
                // кто ДОГОВОРИЛ, а не закреплённый адресат. v1.16-fix: кроме ручного выбора —
                // ответ уйдёт в выбранный, и звать «ответь договорившему» было бы враньём
                val prev = if (Cfg.replyTarget.isNotEmpty() && Cfg.replyTarget != spoke && manualPinHolds())
                    replyTargetName() else lastSpokenName()
                LogBus.add(tr("граница каналов: ${BOUNDARY_ANSWER_MS / 1000}с на ответ «$prev» — дальше «$who» (заговоришь — подожду)", "channel boundary: ${BOUNDARY_ANSWER_MS / 1000}s to answer “$prev” — then “$who” (speak and I will wait)"))
                // v0.81 (боевое: «говорит „ответил войсбридж“, хотя никто не отвечал»):
                // «ответил X» читалось как «X прислал ответ». Фраза теперь — приглашение.
                say(tr("ответь ", "reply to ") + spoken(prev) + tr(", дальше ", ", next, ") + spoken(who))
                // v0.88 (боевое: «ответь мета судья… слушаю мета судья» — две фразы об одном
                // и том же подряд): объявление зазора УЖЕ назвало адресата, реплика подъёма
                // микрофона тут лишняя. Окно с запасом на устаканивание SCO.
                suppressListenCueUntil = System.currentTimeMillis() + 6000
                // микрофон поднимаем ОТЛОЖЕННО: здесь playing ещё true (заход из onCompletion),
                // и прямой вызов молча гасился гейтом startListening — в безруком режиме
                // микрофон в зазоре не вставал вовсе (ревью v0.75)
                // 1800мс, а не 900: реплика «слушаю, <имя>» синтезируется прямо сейчас, и
                // раньше файл ещё не готов — фраза уходила тихим живым TTS без первого слога
                if (Cfg.handsFree && System.currentTimeMillis() > noAutoWakeUntil) main.postDelayed({
                    // v1.43 (ревью): за 1800мс мог прийти запрет пробуждения (провал «озвучить»)
                    if (alive && !dictHold && !listening && !playing &&
                        System.currentTimeMillis() > noAutoWakeUntil) startListening()
                }, 1800)
                buzz(140)
            }
            if (now < boundaryHoldUntil) {
                playing = false
                updateNotification()
                main.postDelayed({ if (alive && !playing) kickPlayback() }, 1000)
                return
            }
        }
        // v1.46: встык без зазора — в журнал, почему (для следующего полевого разбора)
        if (!keepTgt && lastSession != null && nextSid.isNotEmpty() && nextSid != lastSession && boundarySid != nextSid)
            LogBus.add(tr("встык без зазора → «${o.optString("proj")}»: dictHold=$dictHold answered=$answeredCurrent ack=${uttAckTimer != null} fin=$finalizeReq parts=$lastPartNum/$lastPartsTotal", "back-to-back, no gap → '${o.optString("proj")}': dictHold=$dictHold answered=$answeredCurrent ack=${uttAckTimer != null} fin=$finalizeReq parts=$lastPartNum/$lastPartsTotal"))
        boundarySid = null
        playQueue.remove(o)
        playing = true
        stopListening() // half-duplex: never transcribe our own playback
        if (!keepTgt) {
            answeredCurrent = false   // играет новое сообщение — ответа на него ещё не было
            // новое сообщение (не очередная часть того же) — заговорил новый «последний говоривший»
            if (o.optString("session") != lastSession || o.optInt("part", 1) <= 1)
                speakerStartTs = System.currentTimeMillis()
            lastSession = o.optString("session")
        } else {
            // v1.46 (слово юзера: «включил сообщение на экране X — значит отвечаю X, и после ухода
            // с экрана тоже»). v1.43 держала переслушанное в стороне от адресата — это и увозило
            // ответ в прежнюю сессию. Теперь переслушанное = последний слышанный: lastSession и
            // штамп говорившего переезжают сюда, и отсчёт «ответил ли юзер» начинается заново (раз
            // на выборку): иначе ответ, отданный прошлому каналу, снимал бы зазор после переслушанного.
            // Ручной выбор другого канала снимаем, кроме solo. Открытая диктовка — ничего не трогаем:
            // сегменты читают адресата (в том числе динамический lastSession) в момент отгрузки (ревью).
            val rs = o.optString("session")
            val sel = o.optInt("sel", 0)
            if (rs.isNotEmpty() && speechInFlight()) {
                LogBus.add(tr("переслушиваю «${o.optString("proj")}» — диктовка открыта, адресат прежний", "replaying “${o.optString("proj")}” — dictation open, target unchanged"))
            } else if (rs.isNotEmpty()) {
                if (rs != lastSession || sel != replaySelSeen) {
                    // запоминаем прежнее: если звук выборки весь пропал (404), откатим (chosenPartDone)
                    replayPrev = ReplayPrev(lastSession, Cfg.replyTarget, manualPinTs,
                        speakerStartTs, answeredCurrent, lastProj)
                    speakerStartTs = System.currentTimeMillis()
                    answeredCurrent = false
                }
                replaySelSeen = sel
                lastSession = rs
                lastProj = o.optString("proj").ifEmpty { lastProj }
                if (Cfg.replyTarget.isNotEmpty() && Cfg.replyTarget != rs && !soloActive()) {
                    Cfg.replyTarget = ""
                    manualPinTs = 0L
                    Cfg.save(this)
                    lastAnnouncedTarget = null
                    LogBus.add(tr("переслушиваю «${lastSpokenName()}» — отвечать буду туда", "replaying “${lastSpokenName()}” — replies go there"))
                }
            }
        }
        currentSid = o.optString("session")
        lastPartNum = o.optInt("part", 1)          // v0.42: знаем, докачано ли сообщение
        lastPartsTotal = o.optInt("parts", 1)
        partWaitDeadline = 0L
        val proj = o.optString("proj")
        if (!keepTgt) lastProj = proj
        // v0.51: адресат ответа только что переехал на эту сессию — готовим её имя голосом,
        // пока играет сам ответ (к «слушаю» файл успеет дописаться)
        ensureTargetCues()
        val ts = o.optLong("ts")
        if (ts > 0) {
            lastEventTs = maxOf(lastEventTs, ts)
            val prefs = getSharedPreferences("bridge", MODE_PRIVATE)
            val heardKey = "heard_" + currentSid.orEmpty()
            prefs.edit()
                .putLong("lastPlayedTs", lastEventTs)
                .putString("lastSession", lastSession)
                .putString("lastProj", lastProj)
                .putLong(heardKey, maxOf(prefs.getLong(heardKey, 0L), ts))
                .apply()
        }

        // v0.76 (боевое, самокат: «какая-то сессия ответила, а какая — понимаю только по
        // контексту»). announcedProj не сбрасывался НИКОГДА: имя канала звучало один раз за
        // жизнь сервиса, и дальше все ответы шли безымянными. Теперь имя звучит на каждое
        // НОВОЕ СООБЩЕНИЕ — ключ msgid, а не имя проекта: части одного ответа приходят с
        // одним msgid, и объявление по-прежнему одно на сообщение, не на каждую часть.
        val mid = o.optString("msgid")
        val announce = Cfg.announceProj && ttsReady && proj.isNotEmpty() &&
            (mid.isEmpty() && proj != announcedProj || mid.isNotEmpty() && mid != announcedMsgId)
        if (announce) { announcedProj = proj; announcedMsgId = mid }

        val audioUrl = Cfg.url + o.optString("audio") + "?token=" + Cfg.token
        val gen = playGen   // v0.36: докачка старше стопа не имеет права трогать плеер
        Thread {
            var f: File? = null
            var attempt = 0
            while (f == null) {
                try {
                    val req = Request.Builder().url(audioUrl).build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        partSeq += 1
                        val out = File(cacheDir, "part_$partSeq.ogg")
                        resp.body!!.byteStream().use { ins ->
                            out.outputStream().use { ins.copyTo(it) }
                        }
                        f = out
                    }
                } catch (e: Exception) {
                    if (++attempt >= 2) {
                        main.post {
                            if (!alive) return@post
                            LogBus.add(tr("ошибка загрузки звука: ${e.message}", "audio load error: ${e.message}"))
                            if (gen == playGen) {
                                // v1.43: весь выбранный звук удалён — фраза вместо тишины, и пустая
                                // очередь не поднимет микрофон (noAutoWakeUntil) и не пискнет
                                if (chosenPartDone(o, false) && playQueue.isEmpty()) {
                                    playing = false
                                    abandonFocusAfterCues(0)
                                }
                                playNext()
                            }
                        }
                        return@Thread
                    }
                }
            }
            val file = f!!
            main.post {
                if (!alive) return@post
                if (gen != playGen) return@post   // за время докачки нажали «стоп» — часть не играем
                chosenPartDone(o, true)
                batch.add(file)
                pruneCache()
                if (announce) speakThenPlay(proj, file, gen) else playFile(file, gen = gen)
            }
        }.start()
    }

    private fun pruneCache() {
        try {
            val keep = 40
            val files = cacheDir.listFiles { _, n -> n.startsWith("part_") }
                ?.sortedBy { it.lastModified() } ?: return
            if (files.size > keep) files.take(files.size - keep).forEach {
                if (!lastBatch.contains(it) && !batch.contains(it)) it.delete()
            }
        } catch (_: Exception) {}
    }

    private fun speakThenPlay(proj: String, f: File, gen: Int = playGen) {
        var played = false
        val myAnnounceId = "announce_" + announceSeq.incrementAndGet()
        val proceed = Runnable {
            if (!played && alive) { played = true; announceProceed = null; announceId = null; playFile(f, gen = gen) }
        }
        try {
            requestFocus()
            announceProceed = proceed   // v0.37: продолжение зовёт единый TTS-слушатель
            announceId = myAnnounceId
            tts?.setSpeechRate(Cfg.speed)
            // spoken(): имя канала теперь звучит на каждое сообщение — слаг с подчёркиваниями
            // читать вслух нельзя (v0.76)
            // v1.01 (боевое: «ушло в такую-то» и имя нового канала звучат ОДНОВРЕМЕННО).
            // Объявление идёт живым TTS — это второй, независимый голос, и очередь реплик
            // (v0.94) его не контролировала. Ждём, пока реплики договорят, максимум 4с.
            val since = System.currentTimeMillis()
            val speakIt = object : Runnable {
                override fun run() {
                    if (!alive) return
                    val cueBusy = cuePlayer != null || cueQueue.isNotEmpty() ||
                        cuePlayWhenReady.isNotEmpty() || System.currentTimeMillis() < cuePendingUntil ||
                        pendingSynth.get() > 0
                    if (cueBusy && System.currentTimeMillis() - since < 4000) {
                        main.postDelayed(this, 250)
                        return
                    }
                    val mode = if (pendingSynth.get() > 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    try { tts?.speak(spoken(proj), mode, null, myAnnounceId) }
                    catch (_: Exception) { main.post(proceed) }
                }
            }
            main.post(speakIt)
            main.postDelayed(proceed, 7500) // safety: never hang on TTS
        } catch (e: Exception) {
            main.post(proceed)
        }
    }

    private fun routeDirty(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 31) {
            val d = audioManager.communicationDevice
            d != null && (d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.type == 26)
        } else @Suppress("DEPRECATION") audioManager.isBluetoothScoOn
    } catch (_: Exception) { false }

    private fun playFile(f: File, routeWait: Int = 0, seekTo: Int = 0, gen: Int = playGen) {
        // v0.36 (аудит): отложенные продолжения (routeWait-ретрай, докачка) обязаны умирать,
        // если юзер нажал «стоп» (playGen сменился — очередь сброшена сознательно), и обязаны
        // УХОДИТЬ В ОЧЕРЕДЬ, если случилась пауза/потеря наушников (часть нельзя терять).
        if (gen != playGen) return
        if (paused) { if (f !== currentFile) fileQueue.addFirst(f); return }
        if (holdForHeadset()) {
            fileQueue.addFirst(f)
            playing = false
            updateNotification()
            return
        }
        lastPlaybackTs = System.currentTimeMillis()   // v0.8: для сна микрофона
        try {
            grabMediaButtons()   // v0.7: вернуть приоритет кнопки наушников себе (чужой плеер мог перехватить)
            // v0.7.7 (боевое «громкость падает на порядок, фикс 0.7.6 не помог»): корень НИЖЕ распознавателя —
            // SCO-канал микрофона оставался ВКЛЮЧЁННЫМ на время озвучки и утаскивал звук в телефонный
            // канал гарнитуры (тихий, плоский). На время воспроизведения SCO ВЫКЛЮЧАЕМ (полный A2DP).
            // v0.9 (боевое «начинается и через полсекунды обрывается/тонет»): выключение SCO АСИНХРОННОЕ —
            // плеер стартовал на умирающем канале, и система перекидывала маршрут ПОСРЕДИ озвучки.
            // Теперь ждём реального погашения маршрута (шаги по 250мс, потолок 2с) и только потом стартуем.
            if (scoStarted) stopHeadsetMic()
            if (routeDirty() && routeWait < 8) {
                main.postDelayed({ if (alive) playFile(f, routeWait + 1, seekTo, gen) }, 250)
                return
            }
            requestFocus()
            player?.release()
            currentFile = f
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(f.absolutePath)
                setOnCompletionListener {
                    main.post {
                        if (!alive) return@post
                        // v0.15: в «беззвучной паузе» файл доигрывает молча — не двигаем очередь,
                        // а перезапускаем его с точки паузы, чтобы поток (и кнопка) остались живы.
                        if (paused) {
                            try {
                                player?.seekTo(pausedAtMs)
                                player?.start()
                            } catch (_: Exception) {}
                            return@post
                        }
                        // v1.15: часть кончилась В ТИШИНЕ под репликой — следующую не начинаем
                        // (она заиграла бы поверх фразы), а крутим эту с точки удержания.
                        if (cueHold) {
                            try {
                                player?.seekTo(cueHoldAtMs)
                                player?.start()
                            } catch (_: Exception) {}
                            return@post
                        }
                        currentFile = null
                        playNext()
                    }
                }
                setOnErrorListener { _, w, e ->
                    LogBus.add(tr("ошибка плеера $w/$e", "player error $w/$e"))
                    main.post {
                        if (alive) { currentFile = null; playNext() }
                    }
                    true
                }
                prepare()
                if (seekTo > 0) try { seekTo(seekTo) } catch (_: Exception) {}   // v0.29: продолжение с паузы
                setVolume(0f, 0f)     // v0.27: без этого первый слог бьёт по уху на полной громкости
                start()
                // v1.15: ожидание реплики у kickPlayback ограничено 3с — если фраза ещё
                // звучит, новая часть сразу встаёт на удержание с нуля и ждёт её конца
                if (cueHold || cuePlayer != null) {   // ttsSpeaking гаснет от живого объявления, а файловая фраза ещё звучит
                    cueHoldAtMs = seekTo      // точка — начало ЭТОЙ части, а не прежнего плеера
                    if (!cueHold) {
                        cueHold = true
                        cueHoldSince = System.currentTimeMillis()
                        armCueHoldGuard()
                    }
                }
                fadeIn(this)
                // v0.10 диагностика обрывов: видно, сколько раз стартовал плеер и что осталось
                LogBus.add(tr("играю часть (в очереди ещё ${playQueue.size + fileQueue.size})", "playing part (${playQueue.size + fileQueue.size} more in queue)"))
                if (Cfg.speed != 1.0f) {
                    try { playbackParams = playbackParams.setSpeed(Cfg.speed) } catch (_: Exception) {}
                }
            }
            updateNotification()
        } catch (e: Exception) {
            LogBus.add(tr("плеер не стартовал: ${e.message}", "player did not start: ${e.message}"))
            currentFile = null
            main.post { if (alive) playNext() }
        }
    }

    // v0.27 (слово юзера: «каждое слово очень резкое, сделай плавно, 0,3 секунды»): звук
    // нарастает за 300мс вместо мгновенного включения. Шаги мелкие (по 30мс), иначе подъём
    // слышен ступеньками. Пауза уважается: если её нажали посреди нарастания, громкость
    // остаётся нулевой — иначе заглушённая пауза сама себя расшумит.
    private fun fadeIn(mp: MediaPlayer, ms: Long = 300L) {
        val steps = 10
        val step = ms / steps
        for (i in 1..steps) {
            val v = i / steps.toFloat()
            main.postDelayed({
                if (!alive || paused || cueHold) return@postDelayed
                try { if (player === mp) mp.setVolume(v, v) } catch (_: Exception) {}
            }, step * i)
        }
    }

    private fun requestFocus() {
        if (focusReq == null) {
            focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
                )
                .setOnAudioFocusChangeListener(focusListener, main)
                .build()
        }
        audioManager.requestAudioFocus(focusReq!!)
    }

    private fun abandonFocus() {
        focusReq?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    // v1.44 (ревью v1.43): фраза «звук удалён» играет без своего фокуса. Отдать фокус сразу
    // за ней — пустить чужую музыку поверх фразы. Отдаём, когда реплики отзвучали;
    // если за это время стартовал новый ответ, фокус уже его — не трогаем. Потолок — от
    // зависания (60с), а не от длины: чтение текста вслух бывает дольше 8с (ревью v1.44).
    private fun abandonFocusAfterCues(tries: Int) {
        main.postDelayed({
            if (!alive || playing) return@postDelayed
            val busy = cuePlayer != null || cueQueue.isNotEmpty() || cuePlayWhenReady.isNotEmpty() ||
                ttsSpeaking || System.currentTimeMillis() < cuePendingUntil
            if (busy && tries < 120) abandonFocusAfterCues(tries + 1) else abandonFocus()
        }, 500)
    }

    private fun setupTts() {
        try {
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val r = tts?.setLanguage(Lang.ttsLocale)
                    ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                    // v0.37: ЕДИНЫЙ слушатель прогресса TTS — эхо-гейт рекордера (ttsSpeaking)
                    // и продолжение после объявления проекта (announceProceed). Раньше
                    // speakThenPlay ставил свой слушатель и перезаписывал бы этот.
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        // v0.38: synthesizeToFile («cuefile_») — беззвучная работа, эхо-гейт не трогать
                        override fun onStart(utteranceId: String?) {
                            if (utteranceId?.startsWith("cuefile_") != true) {
                                ttsSpeaking = true
                                ttsSpeakStartTs = System.currentTimeMillis()   // v0.46
                            }
                        }
                        // v0.51 (ревью): объявление проекта идёт с QUEUE_FLUSH и обрывает
                        // синтез реплики в файл. Обрезанный wav (>200 байт) осел бы в кэше
                        // НАВСЕГДА — «слушаю, бра…» переживало бы перезапуски. Чистим огрызок.
                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            if (utteranceId?.startsWith("cuefile_") == true) {
                                pendingSynth.decrementAndGet()
                                val p = utteranceId.removePrefix("cuefile_")
                                cueFiles.remove(p)?.let { try { it.delete() } catch (_: Exception) {} }
                                if (cuePlayWhenReady.contains(p) && cueResynthOnce.add(p)) {
                                    main.post {
                                        if (!(alive && cuePlayWhenReady.contains(p) && ensureCue(p))) {
                                            cueResynthOnce.remove(p)
                                            cuePlayWhenReady.remove(p)
                                        }
                                    }
                                    LogBus.add(tr("синтез фразы «${p.take(40)}» оборван объявлением, синтезирую заново", "synthesis of cue “${p.take(40)}” cut by an announcement, re-synthesizing"))
                                } else {
                                    cueResynthOnce.remove(p)
                                    cuePlayWhenReady.remove(p)
                                }
                                return
                            }
                            ttsSpeaking = false
                            lastTtsEndTs = System.currentTimeMillis()
                            // симметрия с onDone/onError: оборванное объявление не должно
                            // вешать озвучку на страховочные 3.5с
                            if (utteranceId == announceId) announceProceed?.let { main.post(it) }
                        }
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId?.startsWith("cuefile_") == true) {
                                pendingSynth.decrementAndGet()
                                // v0.54: фразу ждали, чтобы сыграть — файл готов ровно сейчас
                                val p = utteranceId.removePrefix("cuefile_")
                                cueResynthOnce.remove(p)
                                cuePendingUntil = System.currentTimeMillis() + 300
                                if (cuePlayWhenReady.remove(p)) cueFiles[p]?.let { f ->
                                    if (f.exists() && f.length() > 200) main.post { if (alive) playCueFile(f) }
                                }
                                return
                            }
                            ttsSpeaking = false
                            lastTtsEndTs = System.currentTimeMillis()
                            if (utteranceId == announceId) announceProceed?.let { main.post(it) }
                        }
                        @Suppress("OVERRIDE_DEPRECATION")
                        override fun onError(utteranceId: String?) {
                            if (utteranceId?.startsWith("cuefile_") == true) {
                                pendingSynth.decrementAndGet()
                                val p = utteranceId.removePrefix("cuefile_")
                                cueResynthOnce.remove(p)
                                // v0.55 (ревью): иначе метка «сыграть по готовности» висит вечно
                                // и однажды выстрелит не к месту, на чужом синтезе той же фразы
                                cuePlayWhenReady.remove(p)
                                return
                            }
                            ttsSpeaking = false
                            lastTtsEndTs = System.currentTimeMillis()
                            if (utteranceId == announceId) announceProceed?.let { main.post(it) }
                        }
                    })
                    prewarmCues()   // v0.38: реплики в файлы — играть их будем правильным маршрутом
                }
            }
        } catch (e: Exception) {
            LogBus.add("tts: ${e.message}")
        }
    }

    // ── dictation ─────────────────────────────────────────────────────────
    fun startListening() {
        if (!alive || listening || playing || Cfg.voiceOff) return
        // v0.77 (прямое слово юзера с самоката: «я должен отвечать в ту сессию, которая
        // говорила последнее сообщение — обязательно и неизбежно»). Правило теперь общее,
        // а не только на границе каналов (v0.76): любой подъём микрофона откалывает
        // ПРОТУХШЕЕ закрепление (его ставит и replyToLatest сам, по свежести на релее)
        // и возвращает динамику «последняя игравшая». Не трогаем: solo, свежий РУЧНОЙ
        // выбор (кнопка/голос, 3 минуты) и незакрытую диктовку — сегменты читают адресата
        // в момент отгрузки, смена посреди utt увезла бы сказанное в чужую сессию.
        if (Cfg.replyTarget.isNotEmpty() && lastSession != null && Cfg.replyTarget != lastSession &&
            audible(lastSession!!) && !speechInFlight() &&
            !SessionBook.isMuted(lastSession!!) &&   // в заглушённый канал отвечать нельзя
            !manualPinHolds()
        ) {
            Cfg.replyTarget = ""
            Cfg.save(this)
            lastAnnouncedTarget = null
            LogBus.add(tr("адресат откреплён: отвечаю тому, кто говорил последним («${lastSpokenName()}»)", "target unpinned: replying to whoever spoke last (“${lastSpokenName()}”)"))
            ensureTargetCues()
        }
        grabMediaButtons()   // v0.7: слушание = рабочий режим, кнопка наушников должна быть нашей
        // v0.36: проверка нужна ТОЛЬКО пути SpeechRecognizer — whisper пишет звук сам,
        // и на устройстве без сервисов Google он обязан работать
        if (!Cfg.whisper && !SpeechRecognizer.isRecognitionAvailable(this)) {
            LogBus.add(tr("распознавание речи недоступно на этом устройстве", "speech recognition is unavailable on this device"))
            return
        }
        if (!hasMicPermission()) {
            LogBus.add(tr("нет разрешения на микрофон — открой приложение и выдай", "no microphone permission — open the app and grant it"))
            beepBoth(ToneGenerator.TONE_PROP_NACK, 300)
            return
        }
        listening = true
        dictHold = false   // v0.37: любое поднятие микрофона снимает паузу диктовки
        micAsleep = false  // v0.50: проснулись
        listenStartedAt = System.currentTimeMillis()   // v0.12: свежеподнятый микрофон не усыплять
        micLiveAt = 0L
        listenIsResume = resumeRequested
        // v1.40 (боевое: «через три секунды после „ответь X, дальше Y“ уже играет Y — я и рта
        // не открыл»): 12с зазора считались от объявления, а фраза, «слушаю» и подъём SCO съедали
        // их почти целиком. Окно на ответ отсчитываем от РЕАЛЬНО поднятого микрофона.
        if (boundarySid != null && listenStartedAt < boundaryHoldUntil)
            boundaryHoldUntil = maxOf(boundaryHoldUntil, listenStartedAt + BOUNDARY_ANSWER_MS)
        retryDelay = 300L
        scoWaitCount = 0   // v0.36: свежий отсчёт ожидания SCO
        // v0.46 (аудит): noiseFloor не сбрасывался НИКОГДА (единственная запись — в onRmsChanged),
        // а lastVoiceTs переезжал из прошлой диктовки: первый же warn видел «тишина полчаса» и
        // стрелял, не сделав ни одной попытки продлить отсчёт. Каждый подъём микрофона — чистый лист.
        floorSr.reset()
        // v0.46 (ревью): тот же чистый лист жёсткому детектору whisper. wFloor — поле сервиса,
        // между диктовками кадров нет, поэтому фон прошлой (уличной) диктовки переезжает в новую
        // и слепит ЕДИНСТВЕННЫЙ детектор whisper, решающий, уедет ли звук вообще (hasSpeech).
        wFloor = 0f
        hotMs = 0L; lastRmsTs = 0L
        val nowUp = System.currentTimeMillis()
        // v0.46-fix: lastActivityTs — на 4с в прошлое. Придержка озвучки (playNext/speakingNow)
        // читает ЭТОТ штамп и приняла бы свежий «чистый лист» за живую речь: ложное вибро и
        // задержка ответа до 4с на молчащем юзере. Отсчёту тишины это не мешает (взвод всегда
        // позже подъёма, первый warn и так видит quietFor >= 10000). lastTextTs не смещаем.
        lastVoiceTs = nowUp; lastActivityTs = nowUp - 4000; lastTextTs = nowUp
        prevPartial = ""; lastPartialMaxLen = 0
        rmsFrames = 0; rmsVoiceFrames = 0; gatedFrames = 0; rmsMin = Float.MAX_VALUE; rmsMax = -Float.MAX_VALUE
        partialsSeen = 0; partialsNew = 0; partialsGrow = 0
        lastSensorLogTs = 0L
        startInForeground() // upgrade FGS type to MICROPHONE now that we're in use
        // v0.95 (боевое: «слушаю, X говорится очень тихо»): при поднятом SCO музыкальный канал,
        // на котором играют реплики, физически тонет (урок v0.37). Разговорный канал для реплик
        // использовать нельзя — он убивает кнопку гарнитуры (урок v0.45). Значит порядок:
        // сперва реплика в полный голос по A2DP, и только ПОТОМ поднимаем микрофонный канал.
        beepBoth(ToneGenerator.TONE_PROP_ACK, 120)
        startSilentKeepalive()        // v0.31: удержать медиа-кнопку за собой на время диктовки
        buzz(90)                      // v0.26: короткий толчок = микрофон встал
        // v0.51: «слушаю, brainstorm» вместо голого «слушаю» — адресата слышно ДО того, как
        // человек начал говорить, а не после того, как сообщение уехало не туда. Имя называем
        // ТОЛЬКО когда адресат сменился: длинная реплика на каждый подъём микрофона съедала бы
        // первое слово (рекордер стартует через 900мс, а эхо-гейт глушит кадры на всю реплику).
        val tgtSid = replyTargetSid()
        // v0.55 (боевое, слово юзера: «пришёл вопрос от playbook, я ответил — а ушло опять в
        // войсбридж, почему?»). Потому что ЗАКРЕПЛЁННЫЙ адресат сильнее правила «последняя
        // игравшая», а раз он не меняется, имя переставало звучать вовсе — и расхождение между
        // «кого я только что слушал» и «кому отвечу» становилось невидимым. Теперь имя звучит
        // ещё и тогда, когда отвечать предстоит НЕ ТОМУ, чья озвучка только что играла.
        val playedElsewhere = lastSession != null && tgtSid.isNotEmpty() && lastSession != tgtSid
        if (playedElsewhere) LogBus.add(tr("слушал «${SessionBook.all().firstOrNull { it.sid == lastSession }?.proj ?: "?"}», а отвечу в «${replyTargetName()}» — адресат закреплён", "listened to “${SessionBook.all().firstOrNull { it.sid == lastSession }?.proj ?: "?"}”, but will reply to “${replyTargetName()}” — target pinned"))
        lastAnnouncedTarget = tgtSid
        // v0.76 (прямое слово юзера с самоката: «имя сессии, в которую я отвечаю, должно
        // проговариваться ВСЕГДА, во всех случаях»). Прежняя экономия (имя только на смену
        // адресата) берегла первое слово диктовки, но оставляла его без ответа на вопрос
        // «кому я сейчас говорю» — а это дороже. targetChanged оставлен: старт диктовки
        // сдвигаем чуть дальше, когда имя звучит впервые (реплика длиннее).
        if (System.currentTimeMillis() < suppressListenCueUntil) suppressListenCueUntil = 0L
        else cue(listenCuePhrase())
        ensureTargetCues()            // не было в кэше — приготовим к следующему разу
        LogBus.add(if (Cfg.handsFree) tr("слушаю (скажи «отправляй» в конце)", "listening (say “send” at the end)") else tr("диктуй (нажми ещё раз — отправлю)", "dictate (press again — I will send)"))
        LogBus.add(tr("отвечаю в «${replyTargetName()}»", "replying to “${replyTargetName()}”"))
        // v0.15: видно, каким микрофоном пишем — от этого зависит, живёт ли кнопка гарнитуры
        // v0.51: строка печаталась ДО того, как канал реально поднялся, и врала «гарнитура»,
        // когда писал телефон (хвост №5 в CLAUDE.md). Теперь — что есть на самом деле.
        LogBus.add(when {
            !Cfg.btMic -> tr("микрофон: телефон", "mic: phone")
            scoStarted -> tr("микрофон: гарнитура", "mic: headset")
            else -> tr("микрофон: жду канал гарнитуры (не отдаст — пишу телефоном)", "mic: waiting for headset channel (if it doesn't come, recording on phone)")
        })
        main.postDelayed({
            if (alive && listening) LogBus.add(tr("режим звука: ${audioManager.mode}, sco=$scoStarted, кнопка держится=${silentKeeper != null}", "audio mode: ${audioManager.mode}, sco=$scoStarted, button held=${silentKeeper != null}"))
        }, 1500)
        // v0.26: даём реплике договорить, иначе распознаватель услышит наше же «слушаю»
        // v0.94 (слово юзера: «системные фразы часто слышно тихо — включай микрофон ПОСЛЕ
        // фразы, а не вместе с ней»). Микрофон ждёт, пока реплика реально ДОГОВОРИТ: раньше
        // ждали фиксированные 1250мс, и длинная фраза («ушло в X, дальше Y») попадала в
        // поднятый SCO-канал — там она и звучала тише и рвано. Потолок 6с на всякий случай.
        if (!Cfg.voiceCues || !ttsReady) startHeadsetMic()
        if (Cfg.voiceCues && ttsReady) {
            val since = System.currentTimeMillis()
            val waitCue = object : Runnable {
                override fun run() {
                    if (!alive || !listening) return
                    val busy = ttsSpeaking || cuePlayer != null ||
                        cueQueue.isNotEmpty() || cuePlayWhenReady.isNotEmpty() ||
                        System.currentTimeMillis() < cuePendingUntil
                    if (busy && System.currentTimeMillis() - since < 6000) {
                        main.postDelayed(this, 200)
                        return
                    }
                    // реплика договорила — теперь канал гарнитуры и запись
                    if (!scoStarted) {
                        startHeadsetMic()
                        // кнопку гарнитуры система может отдать чужому плееру ровно на смене
                        // маршрута (урок sleepAfterSend) — перезабираем сразу и через 1.6с
                        grabMediaButtons()
                        main.postDelayed({ if (alive) grabMediaButtons() }, 1600)
                        // 900мс, а не 700: каналу гарнитуры нужно ~1.1с, иначе AudioRecord
                        // привяжется к микрофону ТЕЛЕФОНА (пишем в карман) — контракт scoWaitGate
                        main.postDelayed({ if (alive && listening) startDictation() }, 900)
                    } else startDictation()
                }
            }
            main.postDelayed(waitCue, 900)
        }
        else startDictation()
        // v0.95: запись стартует позже (реплика + канал), диагностика маршрута ждёт её —
        // иначе activeRecordingConfigurations пуст и «пишу с микрофона телефона» не всплывёт
        main.postDelayed({ if (alive && listening) checkMicRoute() }, 3500)
        updateNotification()
    }

    // v0.11 (боевое с улицы «в какой-то момент он перестаёт меня распознавать, хотя я продолжаю»):
    // на шуме распознаватель сыпет ошибками и бэкофф уводил паузу до 5с. Всё это время микрофон
    // мёртв: ни финалов, ни RMS — система слепа и глуха ровно тогда, когда человек ещё говорит.
    // Пока черновик набран, держим переподъём быстрым: потолок 1с вместо 5с.
    // v0.46 (аудит): retryDelay сбрасывается в 300мс ТОЛЬКО живым финалом (onResults). В шуме
    // финалов нет — значит бэкофф постоянно сидит на потолке, и всё это время микрофон мёртв:
    // ни догадок, ни RMS, а отсчёт тишины тикает по настенным часам. «Быстро» теперь считается
    // не только по черновику, но и по живой догадке и по взведённому отсчёту.
    private fun nextRetryDelay(fast: Boolean = draft.isNotEmpty()): Long {
        val cap = if (fast) 1000L else 5000L
        return (retryDelay * 2).coerceAtMost(cap)
    }

    private fun startDictation() {
        if (Cfg.whisper) startWhisperCycle() else startRecognizerCycle()
    }

    // ── v0.35: серверное распознавание (Whisper) ──────────────────────────
    // Микрофон пишем сами (AudioRecord, 16кГц моно), режем на сегменты по паузам
    // и шлём на релей; там их подхватывает whisper. Прежние контракты сохранены:
    // полудуплекс, SCO-хореография, авто-отправка по тишине (свой таймер),
    // стоп-слово ловит СЕРВЕР по распознанному тексту, сон микрофона.
    private fun startWhisperCycle() {
        if (!alive || !listening) return
        // v0.80 (БЛОКЕР, найден ревью): в зазоре между каналами очередь НЕ пуста по
        // определению (следующий ответ ждёт), и этот гейт держал рекордер в вечном retry —
        // зазор был НЕМЫМ с рождения (v0.57): микрофон «поднимался», реплика звучала,
        // а не писалось ни байта. Отсюда все жалобы «дал 12 секунд и не дал ответить».
        if (playing || batch.isNotEmpty() || fileQueue.isNotEmpty() ||
            (playQueue.isNotEmpty() && !gapOpen())
        ) {
            main.postDelayed({ if (alive && listening) startWhisperCycle() }, 700); return
        }
        if (!scoWaitGate { startWhisperCycle() }) return
        // v0.36: старый поток ещё выходит (переключение тумблера) — не бросать молча, дождаться
        if (recThread != null) {
            main.postDelayed({ if (alive && listening) startWhisperCycle() }, 250)
            return
        }
        // v0.36 (ревью): флаги могли пережить прошлый цикл (тумблер/отмена в окно выхода) —
        // залипший finalizeReq стрелял бы пустым финалом по первому кадру новой диктовки
        finalizeReq = false
        cancelReq = false
        // v0.36 (ревью): сегменты старше 5 мин сервер уже слил abandoned-флашем и запомнил utt
        // как завершённый — лить продолжение в мёртвый utt = потерять его. Начинаем новый.
        if (uttId.isNotEmpty() && uttSpeechMs > 0 &&
            System.currentTimeMillis() - lastFinalTs > 300000) {
            LogBus.add(tr("черновик старше 5 минут — начинаю новое сообщение", "draft older than 5 minutes — starting a new message"))
            newUtt()
        }
        recThread = Thread { whisperLoop() }.also { it.isDaemon = true; it.start() }
    }

    // v0.36 (аудит): ожидание SCO больше не немое и не вечное. Гарнитура есть, но микрофон
    // не отдаёт — через ~5с честно скажем и продолжим ждать (микрофон телефона по директиве
    // юзера НЕ используется, пока гарнитура на связи). Гарнитуры нет вовсе — писать больше
    // не с чего, кроме телефона: говорим об этом один раз и пишем с него.
    // Возврат: true = SCO готов или работаем с телефона, можно стартовать запись.
    private fun scoWaitGate(retry: () -> Unit): Boolean {
        if (!Cfg.btMic || scoStarted) return true
        if (headsetPresent()) {
            startHeadsetMic()
            // НЕ возвращать true сразу при поднявшемся SCO: каналу нужно ~1.1с на устаканивание
            // (контракт v0.39), запись на поднимающемся SCO теряет начало фразы. Пусть следующий
            // заход через 800мс увидит scoStarted в первой строке — это проверенный боем путь.
            scoWaitCount++
            // v0.47 (боевое, слово юзера «попробовал с телефона без наушников — ничего не расслышал»):
            // ожидание было ВЕЧНЫМ вопреки комментарию v0.36. Если система считает гарнитуру
            // подключённой (A2DP-наушники без микрофона, наушники лежат рядом, BLE без SCO-профиля),
            // микрофон не поднимался НИКОГДА: recThread оставался null, и любая отправка отвечала
            // «ничего не расслышал». Теперь у гарнитуры есть 8с приоритета, дальше — микрофон
            // телефона, потому что писать телефоном лучше, чем не писать вовсе. Попытку вернуть
            // микрофон гарнитуре делает каждый следующий заход (startHeadsetMic выше), и как только
            // SCO поднимется, живой VOICE_COMMUNICATION-поток перекинется на неё сам.
            if (scoWaitCount >= SCO_WAIT_MAX) {
                if (scoWaitCount == SCO_WAIT_MAX) {
                    LogBus.add(tr("гарнитура не отдала микрофон ~8с — пишу с микрофона телефона (верну ей, как отдаст)", "headset did not give up the mic for ~8s — recording from the phone mic (will hand back when it releases)"))
                    cue(tr("гарнитура не отвечает", "headset not responding"))
                    buzz(300)
                }
                return true
            }
            main.postDelayed({ if (alive && listening) retry() }, 800)
            return false
        }
        if (scoWaitCount == 0) {
            LogBus.add(tr("гарнитуры нет — пишу с микрофона телефона", "no headset — recording from the phone mic"))
            scoWaitCount = 1
        }
        return true
    }

    private fun newUtt() {
        uttId = java.lang.Long.toString(System.currentTimeMillis(), 36)
        uttSeq = 0
        uttSpeechMs = 0
        segLiveMs = 0
        whArmedMs = 0L   // v0.46: абсолютный предел живёт вместе с черновиком, не с проходом рекордера
    }

    private fun whisperLoop() {
        val sr = 16000
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // v0.63 — ПРЯМАЯ ПРОВЕРКА догадки о мёртвой кнопке (юзер прав: она РАБОТАЛА).
        // Скрин 19.07 19:11 на v0.32/0.33 показывал в одной цепочке «sco=true», «кнопка
        // держится=true», черновик С ГАРНИТУРЫ и «кнопка: код 127» → «отправлено». Кнопка
        // жила при поднятом SCO. Сломалось это в тот же день: v0.35 завела СВОЙ рекордер с
        // источником VOICE_COMMUNICATION — телефонным по смыслу. Он и держит гарнитуру в
        // разговорном профиле, где нажатие забирает себе HFP-стек. SpeechRecognizer такого
        // источника не просил, поэтому на нём кнопка и была живой.
        // Меняем ОДНУ константу, ничего больше: VOICE_RECOGNITION пишет с того же микрофона
        // гарнитуры, но не заявляет телефонный разговор. Тумблером — чтобы сравнить в поле,
        // не теряя качества whisper (старый способ сравнения ронял распознавание в мусор).
        val src = if (Cfg.micVoiceComm) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                  else MediaRecorder.AudioSource.VOICE_RECOGNITION
        main.post { if (alive) LogBus.add(tr("рекордер: источник ", "recorder: source ") + (if (Cfg.micVoiceComm) tr("разговорный", "voice-communication") else tr("распознавание", "recognition")) + tr(" — жми кнопку, проверяем", " — press the button, we are testing")) }
        val rec = try {
            AudioRecord(src, sr,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sr * 2))
        } catch (_: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec?.release() } catch (_: Exception) {}
            main.post {
                if (!alive) return@post
                LogBus.add(tr("рекордер не поднялся — повтор через 1с", "recorder did not start — retry in 1s"))
                recThread = null
                main.postDelayed({ if (alive && listening) startWhisperCycle() }, 1000)
            }
            return
        }
        try { rec.startRecording() } catch (_: Exception) {
            try { rec.release() } catch (_: Exception) {}
            main.post { recThread = null; main.postDelayed({ if (alive && listening) startWhisperCycle() }, 1000) }
            return
        }
        if (micLiveAt == 0L) micLiveAt = System.currentTimeMillis()   // v1.43: окно «сейчас скажу» — отсюда
        if (uttId.isEmpty()) newUtt()
        val seg = java.io.ByteArrayOutputStream()
        var segSpeechMs = 0
        segLiveMs = 0
        var quietMs = 0
        var warned = false
        var badReads = 0    // v0.36: мёртвый AudioRecord крутил busy-spin на 100% ядра
        // v0.36 (ревью): потолок удержания меряется ОТ ВЗВОДА черновика (armedTs), не от
        // последней отгрузки — волновой шум отгружает сегменты каждые ~5с и потолок «от
        // отгрузки» не стрелял бы никогда (ровно болезнь v0.22 «сообщения не отправляются»).
        // 45с, а не 30: у whisper нет распознанных финалов, чтобы отличить живую речь от
        // шума и продлить — длинный монолог просто уедет двумя сообщениями.
        var armedTs = 0L
        // v0.46: та же болезнь, что и на пути SpeechRecognizer — wFloor учится на самой речи.
        // Но здесь детектор решает ЕЩЁ И нарезку сегментов и «грузить ли звук вообще»
        // (quietMs/segSpeechMs/hasSpeech), поэтому его НЕ ТРОГАЕМ вовсе: делать его чувствительнее
        // значило бы убить нарезку по паузам и молча выбрасывать речь. Вместо этого рядом живёт
        // ВТОРОЙ, «мягкий» детектор на честном фоне — он отвечает только за живость и авто-отправку.
        val floorWh = NoiseFloorTracker()
        var audioMs = 0L          // время по ЗВУКУ, не настенное: кадры приходят пачками
        var quietSoftMs = 0
        var hotWhMs = 0
        var sleepReq = false      // v0.50: сообщение ушло — цикл не перезапускаем, микрофон спит
        // v0.52 (боевые логи 30.07: пять сегментов, четыре по 20.0с без единой паузы — на улице
        // шум не даёт разрыва, и 90-секундный предел режет живой монолог): отправку, которую
        // выбрал ПОТОЛОК, а не человек, микрофон переживает бодрствуя — иначе хвост мысли гибнет.
        var capForced = false
        var partsSent = false   // v1.42: потолок уже отгрузил части этого монолога
        // v1.42 (боевое 15.10, лог whisper: сегмент обрезан по 20.0с посреди «Отправляе|шь»,
        // обрубок принят за стоп-слово): после обрезки по длине следующий кусок уходит коротким
        // (4с) — сервер, отложивший стоп-слово на обрезке, по нему за секунды видит, говорит ли
        // человек дальше. Без пробы продолжение пришло бы через паузу или ещё через 20с.
        var probeNext = false
        val frame = ShortArray(320)   // 20ms @ 16kHz
        val bytes = ByteArray(640)
        // v0.36 (аудит, этикет рации): очереди УБРАНЫ из условия цикла. Раньше прилетевший
        // ответ убивал рекордер в ту же секунду — речь юзера в окне придержки ПРОПАДАЛА,
        // а замёрзший lastVoiceTs через 4с отдавал озвучке право перебить посреди фразы.
        // Теперь рекордер живёт, пока реально не началось воспроизведение (playNext сам
        // делает stopListening, когда придержка отпустит), полудуплекс цел: на рестарте
        // цикла гейт на очереди остался в startWhisperCycle.
        while (alive && listening && Cfg.whisper && !playing && batch.isEmpty()) {
            // v0.36: ротация БЕЗ seg.reset() — в буфере речь, наговоренная за время серверной
            // обработки стоп-слова (4-7с), она принадлежит СЛЕДУЮЩЕМУ сообщению, не мусорка
            if (rotateReq) { rotateReq = false; newUtt(); armedTs = 0L; warned = false }
            if (cancelReq) {
                cancelReq = false
                seg.reset(); segSpeechMs = 0; quietMs = 0; quietSoftMs = 0; warned = false
                postCancel(uttId)
                newUtt()
                armedTs = 0L
            }
            val n = rec.read(frame, 0, frame.size)
            if (n <= 0) {
                badReads++
                if (badReads >= 25) {
                    main.post { if (alive) LogBus.add(tr("рекордер умер (код $n) — переподнимаю", "recorder died (code $n) — restarting")) }
                    break
                }
                try { Thread.sleep(20) } catch (_: InterruptedException) {}
                continue
            }
            badReads = 0
            val now = System.currentTimeMillis()
            // v0.37 (боевое «ушло по кругу»): пока говорит НАША реплика (и 700мс после) —
            // это эхо, не речь: не считаем голосом и в сегмент не пишем, иначе «ушло»
            // рождает сегмент, сервер шлёт новое «ушло», и рация зацикливается сама на себе.
            if (ttsGateActive()) {   // v0.46: тот же кап 8с, что у датчика речи — залипший ttsSpeaking иначе съел бы всю диктовку
                quietMs += 20
                quietSoftMs += 20
                audioMs += 20      // v0.46: дыра во времени звука — трекер фона сам сбросится
                continue
            }
            audioMs += 20
            var sum = 0.0
            for (i in 0 until n) sum += frame[i].toDouble() * frame[i]
            val db = (20.0 * Math.log10(Math.sqrt(sum / n) + 1)).toFloat()
            // ЖЁСТКИЙ детектор — нарезка сегментов и hasSpeech. Не трогать (см. комментарий выше).
            wFloor = if (wFloor == 0f) db else wFloor * 0.995f + db * 0.005f
            if (db > wFloor + 6f) {
                lastLoudTs = now
                segSpeechMs += 20; quietMs = 0
            } else quietMs += 20
            segLiveMs = segSpeechMs
            // МЯГКИЙ детектор (v0.46) — только живость и авто-отправка. Порог тот же (+6),
            // отличается ЛИШЬ тем, как считается фон: минимум за окно вместо среднего,
            // которое училось на речи. Подтверждение 160мс — против щелчков и швов асфальта.
            val soft = floorWh.feed(db, audioMs)
            if (floorWh.warm && db > soft + 6f) {
                hotWhMs += 20
                if (hotWhMs >= HOT_WH_MS) {
                    quietSoftMs = 0; warned = false
                    lastVoiceTs = now; lastActivityTs = now
                }
                // v0.46 (ревью): громкий кадр — НЕ тишина, даже если подтверждение ещё не набрано.
                // Раньше первые 7 кадров каждой вспышки прибавлялись к тишине, и рваная речь
                // (смычные, придыхания, провалы между слогами) копила отсчёт при говорящем человеке.
            } else {
                hotWhMs = maxOf(0, hotWhMs - 20)   // утечка: одиночный провал внутри слова не рвёт подтверждение
                quietSoftMs += 20
            }
            // v0.46 (ревью) ГЛАВНАЯ СТРАХОВКА: мягкий детектор не имеет права объявить тишину
            // РАНЬШЕ жёсткого. Жёсткий обнуляет quietMs одним громким кадром (поведение v0.45),
            // поэтому min гарантирует «новая версия только терпеливее старой, никогда не торопливее».
            // Вниз не подтягиваем: quietSoftMs может быть сколь угодно МЕНЬШЕ quietMs — это и есть
            // правка v0.46 (жёсткий ослеп на EMA-фоне, мягкий видит речь → отсчёт стоит на нуле).
            quietSoftMs = minOf(quietSoftMs, quietMs)
            for (i in 0 until n) {
                bytes[2 * i] = (frame[i].toInt() and 0xFF).toByte()
                bytes[2 * i + 1] = ((frame[i].toInt() shr 8) and 0xFF).toByte()
            }
            seg.write(bytes, 0, n * 2)
            val sendNowReq = finalizeReq
            val byPause = quietMs >= 2200 && segSpeechMs >= 500
            // v1.42: проба после обрезки — от 4с, как только в ней есть речь. Не гаснет без речи
            // (ревью): человек, продолживший на 4.1с, иначе ждал бы 20с, а сервер закрыл бы
            // сообщение по сроку. Молчит — проба не уходит, сервер по сроку примет команду.
            val byProbe = probeNext && seg.size() >= sr * 2 * 4 && segSpeechMs >= 300
            val byLen = seg.size() >= sr * 2 * 20
            if (sendNowReq || byPause || byLen || byProbe) {
                val hasSpeech = segSpeechMs >= 300
                if (sendNowReq) {
                    finalizeReq = false
                    // v0.50: «отправили что-то» = речь в этом сегменте ИЛИ уже отгруженная речь
                    // в этом utt (uttSpeechMs обнуляется ниже в newUtt — читаем ДО него)
                    val sentSomething = hasSpeech || uttSpeechMs > 0
                    val byCap = capForced
                    // v1.41: дорезанное потолком — часть монолога: помечаем ДО отгрузки (utt_sent
                    // может прийти раньше, чем рекордер дойдёт до следующей строки)
                    if (uttDict.size > 50) { uttDict.clear(); capPartUtts.clear() }
                    if (uttId.isNotEmpty()) uttDict[uttId] = listenStartedAt
                    if (byCap) {
                        capPartUtts[uttId] = listenStartedAt; capPartAt = now
                        if (sentSomething) partsSent = true
                        if (hasSpeech) lastRealSpeechTs = now   // придержка видит живую речь
                    }
                    uploadSeg(if (hasSpeech) seg.toByteArray() else ByteArray(0), true, part = byCap)
                    lastFinalTs = now
                    probeNext = false
                    main.post {
                        if (alive) LogBus.add(
                            if (byCap) tr("ушло на распознавание (дорезал потолок — говори дальше, микрофон не сплю)", "sent to recognition (cut by the cap — keep talking, mic stays awake)")
                            else tr("ушло на распознавание", "sent to recognition")
                        )
                    }
                    seg.reset(); segSpeechMs = 0; quietMs = 0; quietSoftMs = 0; warned = false
                    val carrySid = byCap && dictSidUtt == uttId   // v1.48: дорезанный монолог — тот же адресат
                    newUtt()
                    if (carrySid) dictSidUtt = uttId
                    armedTs = 0L
                    capForced = false
                    // v0.50: одно сообщение на ответ. v0.52: но только если отправку выбрал человек —
                    // дорезанный потолком монолог продолжается в следующее сообщение, микрофон живёт.
                    // v1.41 (ревью): кнопка после молча доехавших частей — хвост пуст, но монолог
                    // отправлен: спим, как после любой отправки
                    if ((sentSomething || partsSent) && !byCap) { sleepReq = true; break }
                    continue
                }
                // v1.42: «обрезан посреди речи» — по длине или пробой, но не на паузе: сервер
                // не финализирует стоп-слово в хвосте такого куска, пока не увидит следующий
                val cutMid = if (byPause) "" else if (byLen) "len" else if (byProbe) "probe" else ""
                probeNext = hasSpeech && cutMid == "len"
                if (hasSpeech) {
                    uploadSeg(seg.toByteArray(), false, cut = cutMid); uttSpeechMs += segSpeechMs; segLiveMs = 0; lastFinalTs = now
                    lastRealSpeechTs = now   // v0.98: подтверждённая речь продлевает придержку
                    // v0.42 (боевое «потолок 45с отрезал живую диктовку посреди мысли»):
                    // отгруженный сегмент = живая диктовка продолжается, потолок отодвигается.
                    // v0.46 (ревью): порог сдвига потолка = САМ критерий отгрузки (500мс), не 1500 —
                    // на 1500 рваная речь («слушай…» пауза «короче…» пауза) не двигала потолок вовсе
                    // и резалась 45с посреди мысли (откат правки v0.42). От шумового зависания теперь
                    // защищает НЕЗАВИСИМЫЙ whArmedMs/HARD_CAP_MS (90с ЗАПИСАННОГО звука).
                    if (segSpeechMs >= 500) { armedTs = now; lastTextTs = now }
                }
                seg.reset(); segSpeechMs = 0
            }
            if (Cfg.handsFree && Cfg.autoSend && uttSpeechMs + segSpeechMs >= 600) {
                if (armedTs == 0L) armedTs = now
                whArmedMs += 20L   // v0.46: предел считаем ЗАПИСАННЫМ звуком — озвучка/реплики его не тикают (их кадры ушли в continue выше)
                // v0.46: отсчёт тишины переехал на МЯГКИЙ детектор (quietSoftMs). Нарезка сегментов
                // осталась на жёстком (quietMs) — она отвечает за то, уедет ли звук вообще.
                if (quietSoftMs >= 10000 && !warned) {
                    warned = true
                    val qs = quietSoftMs; val qh = quietMs; val sp = segSpeechMs
                    val fl = soft; val wf = wFloor; val dbNow = db
                    main.post {
                        if (!alive) return@post
                        beepBoth(ToneGenerator.TONE_PROP_BEEP, 90)
                        LogBus.add(
                            (tr("бип (whisper): тихо %.1fс (жёстко %.1fс) | фон-мягкий %.1f жёсткий %.1f ", "beep (whisper): quiet %.1fs (hard %.1fs) | floor-soft %.1f hard %.1f ") +
                                tr("db %.1f речи в сегменте %dмс", "db %.1f speech in segment %dms")).format(qs / 1000f, qh / 1000f, fl, wf, dbNow, sp)
                        )
                    }
                }
                if (quietSoftMs >= 12000) finalizeReq = true
                // потолок удержания: второй механизм баланса v0.22 — что бы ни творил шум,
                // взведённый черновик уходит не позже чем через 45с
                if (now - armedTs > 45000) {
                    main.post { if (alive) LogBus.add(tr("потолок удержания 45с — отправляю принудительно", "hold cap 45s — forcing send")) }
                    finalizeReq = true
                    capForced = true   // v0.52: дорезал потолок — не спать, человек ещё говорит
                }
                // v0.46 (ревью): абсолютный предел от ПЕРВОГО взвода черновика, меряемый ЗАПИСАННЫМ
                // звуком — переживает перезапуски цикла (озвучка/смерть рекордера), сбрасывается
                // только вместе с самим черновиком (newUtt). Настенное «now - firstArmWh» тикало бы
                // и во время озвучки, а локальная переменная обнулялась каждым ответом сессии.
                if (whArmedMs > HARD_CAP_MS) {
                    main.post { if (alive) LogBus.add(tr("предел 90с записи — отправляю принудительно", "90s recording limit — forcing send")) }
                    finalizeReq = true
                    capForced = true   // v0.52: на самокате 90с набираются за полторы минуты живой речи
                }
            }
        }
        try { rec.stop() } catch (_: Exception) {}
        try { rec.release() } catch (_: Exception) {}
        if (cancelReq) {
            // v0.36 (аудит): «отмена» в кнопочном режиме роняла цикл раньше обработки флага —
            // отменённый хвост уезжал на сервер обычным сегментом, а флаг стрелял по следующей
            // диктовке. Теперь хвост выбрасываем и отменяем utt честно.
            cancelReq = false
            postCancel(uttId)
            uttId = ""; uttSpeechMs = 0; whArmedMs = 0L
        } else if (finalizeReq) {
            finalizeReq = false
            // v0.88 (боевое: «посреди ответа мелькнуло „ничего не расслышал“»): в utt не было
            // НИ ОДНОГО слова (например, микрофон постоял открытым в зазоре и юзер промолчал) —
            // отправлять нечего. Пустой финал заставлял сервер отвечать utt_empty, и реплика
            // «ничего не расслышал» падала поверх играющего ответа.
            if (uttSpeechMs == 0 && segSpeechMs < 300) {
                postCancel(uttId)
                LogBus.add(tr("закрыл пустую диктовку — на сервер ничего не слал", "closed empty dictation — nothing sent to the server"))
            } else {
                uploadSeg(if (segSpeechMs >= 300) seg.toByteArray() else ByteArray(0), true)
            }
            uttId = ""; uttSpeechMs = 0; whArmedMs = 0L
        } else if (segSpeechMs >= 500) {
            uploadSeg(seg.toByteArray(), false)
            uttSpeechMs += segSpeechMs; segLiveMs = 0
            lastRealSpeechTs = System.currentTimeMillis()   // v0.98
        }
        recThread = null
        // v0.50: после отправки цикл НЕ перезапускаем — микрофон засыпает до кнопки/тангенты/ответа
        if (sleepReq) main.post { if (alive) sleepAfterSend() }
        else if (alive && listening) main.post { if (alive && listening) startWhisperCycle() }
    }

    private fun wavWrap(pcm: ByteArray): ByteArray {
        val sr = 16000
        val out = java.io.ByteArrayOutputStream(pcm.size + 44)
        fun le(v: Int, n: Int) { for (i in 0 until n) out.write((v shr (8 * i)) and 0xFF) }
        out.write("RIFF".toByteArray()); le(36 + pcm.size, 4); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le(16, 4); le(1, 2); le(1, 2); le(sr, 4); le(sr * 2, 4); le(2, 2); le(16, 2)
        out.write("data".toByteArray()); le(pcm.size, 4); out.write(pcm, 0, pcm.size)
        return out.toByteArray()
    }

    // part=true (v1.41) — финал части, дорезанной потолком: «ушло» по ней не звучит, ждать его незачем
    // cut (v1.42) — сегмент обрезан посреди речи, не на паузе: "len" по 20с, "probe" — 4с-проба после
    // такой обрезки. Последнее слово может быть обрубком («Отправляе|шь»): сервер не примет его за
    // стоп-слово, не увидев продолжения (после "probe" ждёт дольше — следующий кусок до 20с)
    private fun uploadSeg(pcm: ByteArray, fin: Boolean, part: Boolean = false, cut: String = "") {
        val u = uttId
        // v0.51 (ревью): utt закрыт и очищен сном — хвост, дописанный рекордером на выходе,
        // отбрасываем. НО не молча: молчаливые потери в этом проекте уже стоили дней гадания.
        // v1.42: отдельным сообщением хвост больше не спасаем (слово владельца): после законного
        // «отправляй» это повтор команды или шум, а ложный финал на обрезке лечит сервер (cut).
        if (u.isEmpty()) {
            if (pcm.isNotEmpty()) main.post {
                if (alive) LogBus.add(tr("хвост после отправки отброшен (${pcm.size / 32000}с речи)", "tail after send dropped (${pcm.size / 32000}s of speech)"))
            }
            return
        }
        val sid = replyTargetSid()
        if (sid.isNotEmpty() && dictSidUtt != u) { dictSid = sid; dictSidUtt = u }
        // v0.36 (аудит): раньше пустой адресат уезжал на сервер, тот молча ронял текст,
        // а телефон получал utt_sent и уверенно говорил «ушло». Честный отказ — как в send().
        if (sid.isEmpty()) {
            main.post {
                if (!alive) return@post
                LogBus.add(tr("сегмент не отправлен: не знаю сессию-адресата (нажми «говорить» — уточню у релея)", "segment not sent: target session unknown (press “talk” — will ask the relay)"))
                if (fin) { cue(tr("не знаю, кому отправить", "no one to send to")); buzz(400) }   // v0.36 (ревью): финал не должен быть немым
            }
            return
        }
        val q = uttSeq++
        val body = (if (pcm.isEmpty()) ByteArray(0) else wavWrap(pcm))
            .toRequestBody("audio/wav".toMediaType())
        // v0.36 (ревью): &v= — версия приложения; сервер включает голосовую отмену только для
        // клиентов, которые умеют событие utt_cancelled (страховка порядка деплоя)
        val req = Request.Builder()
            // v0.61: nosil=1 — режим «отправка только словом и кнопкой». Клиентские таймеры
            // гасит сам Cfg.autoSend, но СЕРВЕРНАЯ финализация по смысловой тишине (20с звука,
            // в котором whisper не нашёл слов) живёт отдельно и на улице резала посреди мысли:
            // в ветер слов не находится, и «тишина» набиралась ровно пока человек говорил.
            .url("${Cfg.url}/utt?token=${Cfg.token}&session=$sid&utt=$u&seq=$q&v=${BuildConfig.VERSION_CODE}" +
                (if (!Cfg.autoSend) "&nosil=1" else "") + (if (fin) "&final=1" else "") + (if (cut.isNotEmpty()) "&cut=$cut" else ""))
            .post(body).build()
        // v0.36: после финала ждём подтверждение сервера — иначе смерть voice-whisper невидима
        // v1.13 (боевое: «ответил в первую сессию — и сразу пошла озвучка второй, а „ушло“ я не
        // услышал»). Окно ожидания подтверждения ставил только sendNow, то есть ЭКРАННАЯ кнопка.
        // Финал по стоп-слову, по тишине и по серверному решению его не ставил — и ждавший в
        // очереди ответ стартовал раньше, чем приходило «ушло». Порядок должен быть жёстким:
        // сначала системная фраза, потом чужой голос.
        if (fin) main.post {
            if (alive) {
                armUttAckTimer(u)
                if (!part) awaitSendCueUntil = System.currentTimeMillis() + 6000
            }
        }
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { if (alive) LogBus.add(tr("сегмент не ушёл (", "segment not sent (") + (e.message?.take(30) ?: "?") + tr(") — повтор через 3с", ") — retry in 3s")) }
                main.postDelayed({
                    http.newCall(req).enqueue(object : Callback {
                        override fun onFailure(c: Call, e2: IOException) {
                            main.post { if (alive) { LogBus.add(tr("сегмент ПОТЕРЯН: ", "segment LOST: ") + (e2.message?.take(40) ?: "?")); buzz(300) } }
                        }
                        override fun onResponse(c: Call, r: Response) { r.close() }
                    })
                }, 3000)
            }
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    private fun postCancel(u: String) {
        if (u.isEmpty()) return
        val req = Request.Builder()
            .url("${Cfg.url}/utt?token=${Cfg.token}&utt=$u&cancel=1")
            .post(ByteArray(0).toRequestBody(null)).build()
        // v0.36 (аудит): отмена была fire-and-forget — потерянный POST означал, что «отменённый»
        // текст доедет до сессии призраком через 10 минут. Один повтор, как у сегментов.
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.postDelayed({
                    http.newCall(req).enqueue(object : Callback {
                        override fun onFailure(c: Call, e2: IOException) {
                            main.post { if (alive) LogBus.add(tr("отмена не доехала до сервера — обрывок может прийти позже", "cancel did not reach the server — a fragment may arrive later")) }
                        }
                        override fun onResponse(c: Call, r: Response) { r.close() }
                    })
                }, 3000)
            }
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    private fun startRecognizerCycle() {
        if (!alive) return
        // v0.7.6 (боевое «озвучка глохнет на порядок через полсекунды»): активный SpeechRecognizer
        // заставляет систему ПРИГЛУШАТЬ наш плеер (audio ducking). Полудуплекс по-честному: пока играет
        // озвучка — распознаватель НЕ поднимается (поллинг 700мс), громкость полная; после — «слушаю».
        // v0.8 (боевое «глушение пережило 0.7.7»): между КУСОЧКАМИ одного ответа playing на миг падает,
        // распознаватель просыпался, включал SCO — и следующий кусок играл в телефонном канале тихо.
        // Гейт теперь на ВСЮ очередь: пока есть что играть — микрофона нет; +800мс на устаканивание маршрута.
        // v0.80: то же исключение для зазора — см. startWhisperCycle
        if (playing || batch.isNotEmpty() || fileQueue.isNotEmpty() ||
            (playQueue.isNotEmpty() && !gapOpen())
        ) {
            main.postDelayed({ if (alive && listening) startRecognizerCycle() }, 700); return
        }
        if (!scoWaitGate { startRecognizerCycle() }) return
        // v0.46 (аудит): храповик длины догадки залипал на пике ПРОШЛОЙ сессии распознавателя.
        // В шуме сессия кончается NO_MATCH, догадка выбрасывается, следующая считает с нуля и до
        // старого пика не дотягивает — рост текста больше не фиксируется, и потолок 30с стреляет
        // посреди монолога. Новый цикл — новый отсчёт длины.
        lastPartialMaxLen = 0
        prevPartial = ""
        hotMs = 0L; lastRmsTs = 0L   // v0.46: разрыв потока RMS не должен давать первому кадру dt=300мс
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    recActive = false   // v0.46: сессия распознавателя завершилась
                    if (!alive) return
                    val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val t = texts?.firstOrNull()?.trim().orEmpty()
                    if (t.isNotEmpty()) onFinalText(t)
                    retryDelay = 300L
                    clientErrStreak = 0   // v0.5.5: живой финал сбрасывает счётчик кода 5
                    if (pendingSend) { finishPendingSend(); return }
                    if (listening) main.postDelayed({ if (alive && listening) startRecognizerCycle() }, 150)
                }

                override fun onError(error: Int) {
                    recActive = false   // v0.46: сессия распознавателя завершилась (ошибкой)
                    if (!alive) return
                    if (pendingSend) { finishPendingSend(); return }
                    when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            LogBus.add(tr("нет разрешения на микрофон — диктовка выключена", "no microphone permission — dictation off"))
                            stopListening()
                        }
                        // v0.5.5 (боевое «куча ошибок код 5, распознавание выключено»): ERROR_CLIENT — это
                        // гонки жизненного цикла распознавателя, НЕ смертельно. Пересоздаём с бэкоффом;
                        // 20 подряд без единого финала → честно сдаёмся (черновик при этом уже улетит
                        // автоотправкой — она больше не требует живого распознавания).
                        SpeechRecognizer.ERROR_CLIENT -> {
                            clientErrStreak++
                            if (clientErrStreak >= 20) {
                                LogBus.add(tr("распознавание сыпет код 5 подряд ($clientErrStreak) — диктовка выключена, черновик доедет автоотправкой", "recognizer keeps throwing error 5 ($clientErrStreak) in a row — dictation off, draft will go out via auto-send"))
                                stopListening()
                            } else if (listening) {
                                // v0.7.1: таймер НЕ трогаем — в RMS-архитектуре его заводит только новый финал
                                main.postDelayed({ if (alive && listening) startRecognizerCycle() }, retryDelay)
                                retryDelay = nextRetryDelay()
                            }
                        }
                        else -> {
                            // no_match / timeout are normal in continuous mode; busy needs backoff
                            if (listening) {
                                // v0.7.1: перезапуск таймера УБРАН (кейс «писк/свист сдвигает отсчёт на 10с») —
                                // в RMS-архитектуре шумовой цикл не должен трогать уже идущий отсчёт
                                main.postDelayed(
                                    { if (alive && listening) startRecognizerCycle() },
                                    retryDelay
                                )
                                // v0.46: ERROR_CLIENT выше НЕ трогаем (там clientErrStreak гасит
                                // диктовку — с односекундным потолком он сработал бы в 5 раз быстрее)
                                // v0.46-fix: lastPartial не чистится ни в startListening, ни в
                                // stopListening — «непустая догадка» залипала на прошлой диктовке и
                                // держала потолок бэкоффа на 1с в покое. Считаем только СВЕЖУЮ догадку.
                                retryDelay = nextRetryDelay(
                                    draft.isNotEmpty() ||
                                        (lastPartial.isNotEmpty() &&
                                            System.currentTimeMillis() - lastPartialTs < PARTIAL_FRESH_MS) ||
                                        autoSendTimer != null
                                )
                            }
                        }
                    }
                }

                // v0.30 (скрин юзера: жмёт отправку, а «пустой черновик» — распознаватель за
                // полминуты не отдал НИ ОДНОГО финала). Промежуточные гипотезы при этом идут
                // постоянно, и мы их выбрасывали. Теперь держим последнюю как страховку:
                // в черновик она не пишется (иначе дубли с финалом), но по кнопке уйдёт.
                override fun onPartialResults(partialResults: Bundle) {
                    if (!alive) return
                    val t = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (t.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        // v0.46: САМЫЙ надёжный признак «он говорит прямо сейчас» раньше
                        // выбрасывался. Догадки сыплются в шуме постоянно и не зависят от
                        // сжатой AGC-шкалы громкости — это третий канал живости, не отказывающий
                        // заодно с двумя первыми (финалы и RMS дохнут от одной причины — плохой SNR).
                        if (!ttsGateActive()) {
                            // антифликер: движок перекидывается между двумя гипотезами А/Б/А/Б —
                            // «просто изменилась» продлевало бы отсчёт бесконечно
                            if (t != lastPartial && t != prevPartial) { lastActivityTs = now; partialsNew++ }
                            // потолок 30с двигает только РОСТ текста, а не любое шевеление
                            if (t.length > lastPartialMaxLen + 5) {
                                lastPartialMaxLen = t.length; lastTextTs = now; partialsGrow++
                            }
                            partialsSeen++
                            prevPartial = lastPartial
                            lastPartial = t; lastPartialTs = now
                            // v0.34 (боевое «не могу дождаться 10 секунд»): в шуме финалов НЕТ вовсе,
                            // черновик пуст — отсчёт даже не взводился, сообщение не уходило никогда.
                            // v0.46-fix: запись догадки и взвод отсчёта — ТОЖЕ под эхо-гейтом. Иначе
                            // распознаватель ловит нашу же реплику «слушаю»/«ушло», она садится в
                            // lastPartial, взводит окно (фантомный бип) и уезжает в сессию по кнопке.
                            // Гейт handsFree/autoSend вместо снятого draft.isEmpty() — иначе
                            // scheduleAutoSend уходит в логирующий return на КАЖДОМ партиале и вымывает журнал.
                            if (autoSendTimer == null && Cfg.handsFree && Cfg.autoSend) scheduleAutoSend()
                        }
                    }
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                // v0.7 (боевое «10 секунд бесконечно обнуляются от левого шума в квартире»): сброс по
                // «началу речи» УБРАН НАВСЕГДА — детектор дёргается от шороха. Живую речь теперь уважает
                // сам ВЫСТРЕЛ по громкости (lastLoudTs из onRmsChanged): речь у рта громкая, фон — нет.
                override fun onBeginningOfSpeech() {}
                // v0.7.3: эхо собственной озвучки в микрофон — НЕ голос юзера
                override fun onRmsChanged(rmsdB: Float) {
                    // v0.46: эхо-гейт был только на `playing`, а файловые реплики играет cuePlayer
                    // (playCueFile), который `playing` НЕ ставит — собственное «слушаю» на старте
                    // диктовки накачивало фон ровно в первые секунды. Кап 8с обязателен: у
                    // ttsSpeaking нет ни одного таймаута, и не пришедший onDone убил бы датчик навсегда.
                    if (playing || ttsGateActive()) { hotMs = 0L; lastRmsTs = 0L; gatedFrames++; return }
                    val now = System.currentTimeMillis()
                    if (rmsdB >= 7f) lastLoudTs = now
                    // v0.46: фон по минимуму вместо скользящего среднего — среднее училось на речи
                    noiseFloor = floorSr.feed(rmsdB, now)
                    val dt = if (lastRmsTs == 0L) 100L else (now - lastRmsTs).coerceIn(10L, 300L)
                    lastRmsTs = now
                    rmsFrames++
                    if (rmsdB < rmsMin) rmsMin = rmsdB
                    if (rmsdB > rmsMax) rmsMax = rmsdB
                    // v0.22: порог +5.5 dB над фоном НЕ ТРОГАЕМ — вся правка в том, чем считается фон.
                    // v0.46: подтверждение длительностью — при более честном (низком) фоне порог
                    // начинают пробивать щелчки, хлопки и швы асфальта; речь длится дольше 200мс.
                    if (floorSr.warm && rmsdB > noiseFloor + 5.5f) {
                        hotMs += dt
                        if (hotMs >= HOT_MS) { lastVoiceTs = now; lastActivityTs = now; rmsVoiceFrames++ }
                    } else hotMs = 0L
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Lang.recognizerTag)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // v0.46 (аудит): флаг не выставлялся НИГДЕ, а по контракту Android дефолт — false.
                // Значит весь партиальный тракт (v0.30/v0.34 и живость отсчёта по догадкам) мог
                // быть мёртв на его аппарате. Парная страховка — единая свежесть догадки
                // PARTIAL_FRESH_MS во всех трёх точках отправки, иначе оживший тракт начнёт
                // слать в сессию обрывки получасовой давности.
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            recActive = true   // v0.46: сессия распознавателя реально стартует
            if (micLiveAt == 0L) micLiveAt = System.currentTimeMillis()   // v1.43
            startListening(intent)
        }
    }

    // v0.7 (слово юзера «одной кнопкой и сразу озвучивать крайнее»): найти самую свежую сессию на релее
    // и заиграть её ПОСЛЕДНЕЕ сообщение. Стоп текущего, очередь чистится. Всё в фоне, ошибки — в журнал.
    // v0.49: накопленное при подключении лежит в heldQueue и ждёт явной команды.
    // Возврат true = было что проиграть, в сеть за историей идти не нужно.
    fun playHeld(): Boolean {
        if (heldQueue.isEmpty()) return false
        val n = heldQueue.size
        while (heldQueue.isNotEmpty()) playQueue.addLast(heldQueue.removeFirst())
        LogBus.add(tr("играю накопленное: $n част.", "playing backlog: $n parts"))
        updateNotification()
        kickPlayback()
        return true
    }

    fun heldCount(): Int = heldQueue.size

    // v0.75: идёт ли сейчас зазор между каналами и кому отвечать, пока он идёт.
    // Экран превращает круг в «ответить в X» — не надо вспоминать, кто только что говорил.
    // идёт ли прямо сейчас зазор между каналами (окно на твой ответ)
    private fun gapOpen(): Boolean =
        boundarySid != null && System.currentTimeMillis() < boundaryHoldUntil

    // v1.46: правая кнопка гарнитуры в зазоре между каналами или в ожидании хвоста = «дальше»:
    // не ждать, играть следующего. Раньше она там шла в onCancel — «отменено» и та же тишина.
    // Начатую диктовку не трогаем: с черновиком кнопка отправляет (ветка выше).
    // v1.47 (ревью): speechInFlight() считает речью и голос < 2.5с назад — на улице это ветер
    // в пустом микрофоне зазора, и кнопка падала в onCancel. Здесь речь — только по закону
    // «есть речь» (600мс сказанного в открытом utt, с неотгруженным сегментом) или черновик.
    private fun saidSomething(): Boolean =
        draft.isNotEmpty() || (Cfg.whisper && uttId.isNotEmpty() && uttSpeechMs + segLiveMs >= 600)

    fun skipWait(): Boolean {
        if (playing || paused || saidSomething()) return false
        val gap = gapOpen() && playQueue.isNotEmpty()
        val tail = partWaitDeadline > 0L && currentSid != null && lastPartNum < lastPartsTotal
        if (!gap && !tail) return false
        if (gap) boundaryHoldUntil = 0L
        if (tail) {
            // хвост не ждём; очередь пуста — playNext закроет сообщение и поднимет микрофон как обычно
            partWaitDeadline = 0L
            lastPartsTotal = lastPartNum
            if (playQueue.isNotEmpty()) boundarySuppress = true   // «дальше» уже сказано — второго зазора не ставим
        }
        LogBus.add(tr("кнопка «дальше» — не жду, играю следующего", "“next” button — not waiting, playing the next one"))
        // пустой микрофон зазора опускаем: иначе ветер (lastLoudTs) держал бы ответ придержкой «ты диктуешь»
        if (listening) stopListening()
        kickPlayback()
        return true
    }

    // v1.16-fix: ручной выбор другого канала во время озвучки главнее «ответь договорившему» —
    // иначе тап по кругу в зазоре увёл бы диктовку обратно в того, кого юзер уже сменил
    fun boundaryReplySid(): String =
        if (boundarySid != null && System.currentTimeMillis() < boundaryHoldUntil &&
            !(manualPinHolds() && Cfg.replyTarget.isNotEmpty() && Cfg.replyTarget != lastSession)
        ) (lastSession ?: "") else ""

    fun boundaryNextName(): String {
        val s = boundarySid ?: return ""
        return SessionBook.all().firstOrNull { it.sid == s }?.proj?.ifEmpty { s.take(4) } ?: s.take(4)
    }

    // Имя ТОГО, КТО ТОЛЬКО ЧТО ДОГОВОРИЛ. Не replyTargetName(): тот отдаёт ЗАКРЕПЛЁННОГО
    // адресата, и на границе каналов это разные сессии — подпись обещала бы одно,
    // а тап уводил адресата на другое (ревью v0.75).
    fun lastSpokenName(): String {
        val s = lastSession ?: return "—"
        return SessionBook.all().firstOrNull { it.sid == s }?.proj?.ifEmpty { s.take(8) } ?: s.take(8)
    }

    // v0.73 (слово юзера с поля: «зашёл в раздел прослушать — значит я ГОТОВ СЛУШАТЬ,
    // а он предлагает диктовать»). Экранное «послушать» — явное намерение: микрофон вниз,
    // чтобы этикет-придержка (она держит ответ, пока юзер говорит) не отложила озвучку.
    // dictHold НЕ трогаем: придержку снимает уже сам stopListening (она смотрит на listening),
    // а сброс dictHold молча выключал бы режим «не слушать» (v0.43) — ровно в тот момент,
    // ради которого его и включают: слушать ответы, не записывая улицу.
    fun listenIntent() {
        if (Cfg.voiceOff) toggleVoiceOff(announce = false)   // v1.28: явное «слушать» = хочу слышать
        touchUser()
        if (listening) stopListening()
        holdSince = 0L
        // v0.73.1 (боевое: «жму послушать — ничего»): playNext первым делом выходит при
        // paused, а круг с v0.71 ставит именно паузу — залипшая пауза глушила ВСЁ, что мы
        // сейчас поставим в очередь. Снимаем её, не трогая очередей (ничего не теряется).
        if (paused) { LogBus.add(tr("явное «слушать» — снимаю паузу", "explicit “listen” — releasing pause")); resumePlayback() }
        // Зазор между каналами существует для того, что валится САМО. Когда юзер ткнул
        // «слушать» пальцем, двенадцать секунд тишины в ответ — издевательство (и сторож
        // немого нажатия успевал соврать «не могу играть»). Гасим зазор на этот заход.
        boundarySid = null
        boundaryHoldUntil = 0L
        boundarySuppress = true
    }

    // Сторож немого нажатия: очередь есть, а звука нет — назвать причину вслух и в журнал,
    // вместо «нажал, и ничего не происходит» (класс отказов, стоивший проекту дней).
    private fun watchSilentPlay(what: String) {
        main.postDelayed({
            if (!alive) return@postDelayed
            if (!playing && (playQueue.isNotEmpty() || fileQueue.isNotEmpty())) {
                val noHs = holdForHeadset()
                LogBus.add(tr("«$what»: звука нет — ", "“$what”: no sound — ") + when {
                    noHs -> tr("включено «только в наушниках», а их нет", "“headphones only” is on and there are none")
                    paused -> tr("рация на паузе", "radio is paused")
                    else -> tr("очередь стоит", "queue is stalled")
                })
                cue(if (noHs) tr("надень наушники", "put on your headphones") else tr("не могу играть", "can't play"))
                buzz(300)
            }
        }, 2500)
    }

    // «Все подряд» с экрана: явное намерение слушать + сторож немого нажатия
    fun playHeldAll(): Boolean {
        listenIntent()
        val ok = playHeld()
        if (ok) watchSilentPlay(tr("все подряд", "all"))
        return ok
    }

    // v0.73: сообщения, которые юзер ЯВНО пропустил в разборе накопленного. Части одного
    // ответа приходят с разрывом до минуты (v0.42) — без этого хвост выброшенного сообщения
    // догонял бы юзера и играл, хотя он его отменил.
    private val skippedMsgIds = ArrayDeque<String>()

    // v0.71: состав накопленного и выборочное прослушивание — слово юзера с поля:
    // «не могу разобрать, чьи это десять ответов; хочу скипнуть или послушать конкретную».
    // ТОЛЬКО добавки: playHeld и очереди не тронуты, вызовы с главного потока (экран).
    fun heldBreakdown(): List<Triple<String, String, Int>> {
        val by = LinkedHashMap<String, Pair<String, Int>>()
        for (o in heldQueue) {
            val sid = o.optString("session")
            val proj = o.optString("proj").ifEmpty { sid.take(8) }
            val cur = by[sid]
            by[sid] = Pair(cur?.first?.ifEmpty { proj } ?: proj, (cur?.second ?: 0) + 1)
        }
        return by.map { Triple(it.key, it.value.first, it.value.second) }
    }

    fun playHeldFor(sid: String): Boolean {
        val mine = heldQueue.filter { it.optString("session") == sid }
        if (mine.isEmpty()) return false   // сначала убедиться, что есть что играть
        listenIntent()
        val rest = heldQueue.filter { it.optString("session") != sid }
        heldQueue.clear()
        for (o in rest) heldQueue.addLast(o)
        // v0.78 (боевое: «выбрал слушать voicebridge — заиграл playbook»): выбранный канал
        // идёт ПЕРВЫМ. addLast ставил его за теми частями, что уже ждали в playQueue, и
        // юзер слышал чужой ответ вместо выбранного. reversed() сохраняет порядок частей.
        for (o in mine.reversed()) playQueue.addFirst(o)
        // и снимаем «доигрываю сообщение текущего канала»: иначе остаток чужого многочастного
        // ответа всё равно шёл бы первым, а юзер явно выбрал этот канал (он сыграет следом)
        currentSid = null
        LogBus.add(tr("играю накопленное «${mine.first().optString("proj").ifEmpty { sid.take(8) }}»: ${mine.size} ч.", "playing backlog “${mine.first().optString("proj").ifEmpty { sid.take(8) }}”: ${mine.size} parts"))
        updateNotification()
        kickPlayback()
        watchSilentPlay(tr("слушать канал", "listen to channel"))
        return true
    }

    fun dropHeldFor(sid: String): Int {
        val keep = heldQueue.filter { it.optString("session") != sid }
        val n = heldQueue.size - keep.size
        // хвосты выброшенных сообщений не должны прилететь следом и заиграть
        for (o in heldQueue) if (o.optString("session") == sid) {
            // ключ с sid: msgid генерится в каждой сессии независимо (миллисекунды),
            // и две сессии могут выдать одинаковый — пропуск одной глушил бы чужое
            val mid = o.optString("msgid")
            if (mid.isNotEmpty() && !skippedMsgIds.contains("$sid|$mid")) {
                skippedMsgIds.addLast("$sid|$mid")
                while (skippedMsgIds.size > 50) skippedMsgIds.removeFirst()
            }
        }
        heldQueue.clear()
        for (o in keep) heldQueue.addLast(o)
        if (n > 0) LogBus.add(tr("пропустил накопленное: $n ч. — они остаются в ленте канала", "skipped backlog: $n parts — they stay in the channel feed"))
        updateNotification()
        return n
    }

    fun playLatestEverywhere() {
        if (playHeld()) return   // v0.49: сначала отдаём накопленное, без похода в сеть
        Thread {
            try {
                val raw = Net.get(Cfg.pin, "${Cfg.url}/sessions?token=${Cfg.token}") ?: run {
                    main.post { LogBus.add(tr("крайнее: нет связи с релеем", "latest: no connection to the relay")) }; return@Thread
                }
                val arr = JSONObject(raw).optJSONArray("sessions") ?: return@Thread
                var bestSid = ""; var bestProj = ""; var bestTs = 0L
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optLong("lastTs") > bestTs) { bestTs = o.optLong("lastTs"); bestSid = o.getString("sid"); bestProj = o.optString("proj") }
                }
                if (bestSid.isEmpty()) { main.post { LogBus.add(tr("крайнее: на релее пусто", "latest: relay is empty")) }; return@Thread }
                val hraw = Net.get(Cfg.pin, "${Cfg.url}/history?token=${Cfg.token}&session=$bestSid&limit=30") ?: return@Thread
                val evs = JSONObject(hraw).optJSONArray("events") ?: return@Thread
                // последнее СООБЩЕНИЕ = все части старшего msgid
                var lastId = ""; var lastTs = 0L
                for (i in 0 until evs.length()) {
                    val ev = evs.getJSONObject(i)
                    if (ev.optLong("ts") > lastTs) { lastTs = ev.optLong("ts"); lastId = ev.optString("msgid", ev.optString("ts")) }
                }
                val items = ArrayList<Pair<String, Long>>()
                val parts = ArrayList<Pair<Int, String>>()
                for (i in 0 until evs.length()) {
                    val ev = evs.getJSONObject(i)
                    if (ev.optString("msgid", ev.optString("ts")) == lastId) parts.add(Pair(ev.optInt("part", 1), ev.optString("audio")))
                }
                parts.sortBy { it.first }
                for (p in parts) items.add(Pair(p.second, lastTs))
                val proj = bestProj
                main.post {
                    if (!alive) return@post
                    LogBus.add(tr("крайнее: играю последнее из «${proj}»", "latest: playing the last one from “${proj}”"))
                    playFrom(bestSid, proj, items, keepTarget = false)
                }
            } catch (e: Exception) { main.post { LogBus.add(tr("крайнее: ошибка ${e.message?.take(60)}", "latest: error ${e.message?.take(60)}")) } }
        }.start()
    }

    // v0.7.4 (слово юзера «кнопка — сразу ответ, БЕЗ прослушивания»): свежайшая сессия релея становится
    // адресатом ответа, слушание стартует, озвучка НЕ запускается — диктуй с первой секунды.
    // v0.23 (скрин юзера: «отвечаю в … — диктуй» ДВЕНАДЦАТЬ раз подряд за 6 секунд): каждое
    // нажатие поднимало свой поток к релею, а когда «ничего не происходит», жмут ещё и ещё.
    // Повторный вызов при уже идущей диктовке бессмысленен — гасим его на входе.
    @Volatile private var replyLatestBusy = false

    // v0.25 (боевое: «нажимаю — ничего не происходит, в журнале пусто, сигналов нет»):
    // кнопка «говорить» шла через replyToLatest, а тот СНАЧАЛА идёт в сеть за списком сессий
    // и только потом поднимает микрофон. Нет связи или она медленная — микрофон не поднимается
    // вообще, и приложение выглядит мёртвым. Теперь микрофон встаёт СРАЗУ, а адресата
    // уточняем в фоне: диктовка не должна зависеть от сети.
    fun talkNow() {
        if (Cfg.voiceOff) toggleVoiceOff(announce = false)   // v1.28: «говорить» без голоса — сначала вернуть голос
        touchUser()
        if (listening) { sendNow(); return }
        // v0.36 (аудит): раньше во время озвучки тангента молча НЕ ДЕЛАЛА НИЧЕГО (гейт playing
        // в startListening), а журнал врал «поднимаю микрофон». Теперь — как двойное нажатие
        // гарнитуры: прервать и сразу диктовать.
        if (playing || paused) {
            LogBus.add(tr("тангента: прерываю озвучку — диктуй", "push-to-talk: interrupting playback — dictate"))
            stopPlayback(thenListen = true)
            return
        }
        LogBus.add(tr("кнопка «говорить» — поднимаю микрофон", "“talk” button — raising the mic"))
        startListening()
        if (Cfg.replyTarget.isEmpty() && lastSession == null) replyToLatest()
    }

    fun replyToLatest() {
        if (replyLatestBusy) return
        if (listening) { LogBus.add(tr("уже слушаю — просто диктуй", "already listening — just dictate")); return }
        replyLatestBusy = true
        Thread {
            try {
                val raw = Net.get(Cfg.pin, "${Cfg.url}/sessions?token=${Cfg.token}") ?: run {
                    main.post { LogBus.add(tr("ответ в крайнюю: нет связи с релеем", "reply to latest: no connection to the relay")) }; return@Thread
                }
                val arr = JSONObject(raw).optJSONArray("sessions") ?: return@Thread
                // v0.37 (боевое: диктовка уходила в сессию с мёртвым поллером и копилась в
                // никуда): предпочитаем свежайшую ЖИВУЮ сессию (relay отдаёт alive по
                // возрасту consume); живых нет — берём свежайшую вообще, но предупреждаем.
                var bestSid = ""; var bestProj = ""; var bestTs = 0L
                var aliveSid = ""; var aliveProj = ""; var aliveTs = 0L
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val ts = o.optLong("lastTs")
                    if (ts > bestTs) { bestTs = ts; bestSid = o.getString("sid"); bestProj = o.optString("proj") }
                    if (o.optBoolean("alive") && ts > aliveTs) { aliveTs = ts; aliveSid = o.getString("sid"); aliveProj = o.optString("proj") }
                }
                val deadTarget = aliveSid.isEmpty()
                if (aliveSid.isNotEmpty()) { bestSid = aliveSid; bestProj = aliveProj }
                if (bestSid.isEmpty()) { main.post { LogBus.add(tr("ответ в крайнюю: на релее пусто", "reply to latest: relay is empty")) }; return@Thread }
                main.post {
                    if (!alive) return@post
                    // v1.16-fix: пока шёл запрос, юзер мог выбрать канал руками — не перебиваем
                    if (Cfg.replyTarget.isNotEmpty() && manualPinHolds()) {
                        if (!listening) startListening()
                        return@post
                    }
                    Cfg.replyTarget = bestSid
                    targetEpoch++
                    Cfg.save(this@BridgeService)
                    ensureTargetCues()   // v0.51: имя адресата — голосом
                    LogBus.add(tr("отвечаю в «$bestProj» — диктуй", "replying to “$bestProj” — dictate"))
                    if (deadTarget) LogBus.add(tr("ВНИМАНИЕ: живых сессий на релее нет — сообщения могут ждать в очереди", "WARNING: no live sessions on the relay — messages may wait in the queue"))
                    beepBoth(ToneGenerator.TONE_PROP_PROMPT, 120)
                    if (!listening) startListening()
                }
            } catch (e: Exception) { main.post { LogBus.add(tr("ответ в крайнюю: ошибка ${e.message?.take(50)}", "reply to latest: error ${e.message?.take(50)}")) } }
            finally { replyLatestBusy = false }
        }.start()
    }

    // v0.23 (слово юзера: «увеличение или уменьшение скорости озвучки»): шаг прямо с главного
    // экрана. Ползунок в настройках остаётся, но на ходу до него не добраться.
    fun nudgeSpeed(delta: Float): Float {
        Cfg.speed = (Cfg.speed + delta).coerceIn(0.7f, 2.0f)
        Cfg.save(this)
        applySpeedNow()
        LogBus.add(tr("скорость озвучки: ${"%.1f".format(Cfg.speed)}×", "playback speed: ${"%.1f".format(Cfg.speed)}×"))
        return Cfg.speed
    }

    // v0.5.5: живое применение скорости к текущему воспроизведению (раньше — только со следующего куска)
    fun applySpeedNow() {
        try { player?.let { if (it.isPlaying) it.playbackParams = it.playbackParams.setSpeed(Cfg.speed) } } catch (_: Exception) {}
        try { tts?.setSpeechRate(Cfg.speed) } catch (_: Exception) {}
    }

    // keepButton=true — микрофон гасим, но беззвучный поток (silentKeeper) ОСТАВЛЯЕМ:
    // именно он держит медиакнопку за нами, а во сне после отправки кнопка — единственный
    // способ снова заговорить, не доставая телефон (v0.50).
    fun stopListening(keepButton: Boolean = false) {
        if (!listening) return
        // v0.94-fix: приглашение говорить, ждущее очереди, теперь неправда — микрофон гаснет.
        // Остальные реплики («ушло», предупреждения) в очереди трогать нельзя.
        try { cueFiles[listenCuePhrase()]?.let { cueQueue.remove(it) } } catch (_: Exception) {}
        listening = false
        pendingSend = false
        scoWaitCount = 0
        cancelAutoSend()
        resetSendWindow()   // v0.46
        main.removeCallbacks(flushSend)
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}   // v0.9: cancel не всегда гасит ducking — сессию в ноль
        recognizer = null
        recActive = false   // v0.46: диктовки нет — «датчик мёртв» больше не печатается ложно
        if (!keepButton) stopSilentKeepalive()
        stopHeadsetMic()
        updateNotification()
    }

    // v0.50 (слово юзера, боевое наблюдение: «просто ушло, ушло, ушло — я молчу, а оно
    // отправляет»): МОДЕЛЬ «ОДНО СООБЩЕНИЕ НА ОТВЕТ». Раньше микрофон оставался открытым
    // всё время ожидания ответа, поэтому улица, соседи и галлюцинации распознавания на шуме
    // уезжали в сессию сами собой. Это был корень целого класса бед, а пороги и таймеры
    // лечили лишь следствия. Теперь: сообщение ушло — микрофон СПИТ.
    // Будят его три вещи: кнопка гарнитуры, тангента на экране и приход ответа
    // (playNext поднимает микрофон после озвучки, если не включён ручной mute).
    private fun sleepAfterSend() {
        if (!listening) return
        stopListening(keepButton = true)
        micAsleep = true
        // v0.51 (ревью, КРИТИЧНО): utt закрыт — телефоном или сервером, — и этот id больше не наш.
        // Оставить его нельзя: следующая диктовка полилась бы в уже финализированный utt, а
        // transcribe.js (done-ring) сегменты старше минуты от финала просто удаляет. Юзер
        // послушал ответ, нажал кнопку, наговорил — и сообщение исчезло бы без следа.
        uttId = ""; uttSpeechMs = 0; whArmedMs = 0L
        updateNotification()
        // v0.51: teardown SCO асинхронный (до ~1.5с), и на смене маршрута система может отдать
        // кнопку чужой медиасессии. Во сне кнопка гарнитуры — ЕДИНСТВЕННЫЙ способ заговорить
        // снова, не доставая телефон, поэтому приоритет забираем сразу и ещё раз после устаканивания.
        grabMediaButtons()
        main.postDelayed({ if (alive) grabMediaButtons() }, 1600)
        LogBus.add(tr("сообщение ушло — микрофон спит (разбудит кнопка, тангента или ответ)", "message sent — mic asleep (button, push-to-talk or a reply will wake it)"))
        // v0.65: прошлая сессия получила ответ — зазор на границе сессий больше не нужен,
        // очередь едет дальше немедленно, не досиживая свой потолок.
        if (boundarySid != null) {
            boundarySid = null
            boundaryHoldUntil = 0L
            LogBus.add(tr("ответ отправлен — пускаю следующую сессию", "reply sent — letting the next session through"))
            kickPlayback()
        }
    }

    // v0.5 (слово юзера «упростить отправление голосом»): АВТО-ОТПРАВКА ПО ТИШИНЕ в безруком режиме.
    // Черновик непуст + 4с тишины → предупредительный бип; ещё 2с тишины → отправка (двойной бип из send()).
    // Любая речь (onBeginningOfSpeech / новый финал) сбрасывает отсчёт. «Отмена» стирает. Тумблер Cfg.autoSend.
    // Принцип v1 «тишина никогда не отправляет» ОСОЗНАННО пересмотрен боевым опытом: стоп-слово через
    // узкий SCO-микрофон = 9/10 отказов; предупредительный бип возвращает контроль без слов.
    // v0.46: окно ОДНОГО сообщения закрылось — сообщение реально ушло с телефона или стёрто.
    // Абсолютный потолок и храповик текста считаются заново со следующей мысли.
    private fun resetSendWindow() {
        firstArmTs = 0L
        lastTextTs = 0L
        lastPartialMaxLen = 0
        prevPartial = ""
        sendRearms = 0
        beepAtTs = 0L
    }

    // v0.46: одна плотная строка вместо десятка — LogBus держит 200 строк, а юзер читает СКРИНАМИ.
    // По ней однозначно разводятся пять исходов: датчик мёртв (кадров 0), шкала схлопнута
    // (rms min..max почти совпали), фон уполз к речи, виноват потолок, текстовый канал мёртв.
    private fun sensorLine(now: Long): String {
        val lo = if (rmsMin == Float.MAX_VALUE) 0f else rmsMin
        val hi = if (rmsMax == -Float.MAX_VALUE) 0f else rmsMax
        // -1.0 = метки нет (окно сброшено resetSendWindow), а не «текст был вечность назад»:
        // без этого печаталось «текст 1.7e9с назад» и строка врала о мёртвом канале.
        val actAgo = if (lastActivityTs == 0L) -1f else (now - lastActivityTs) / 1000f
        val txtAgo = if (lastTextTs == 0L) -1f else (now - lastTextTs) / 1000f
        // Locale.US: на русской локали %.1f печатает запятую — числа в журнале должны быть с точкой
        return String.format(
            java.util.Locale.US,
            tr("фон %.1f порог %.1f rms %.1f..%.1f кадров %d голосом %d | догадок %d (нов %d, рост %d)", "floor %.1f threshold %.1f rms %.1f..%.1f frames %d voiced %d | guesses %d (new %d, grew %d)") +
                tr(" | речь %.1fс назад, текст %.1fс назад", " | speech %.1fs ago, text %.1fs ago"),
            noiseFloor, noiseFloor + 5.5f, lo, hi, rmsFrames, rmsVoiceFrames,
            partialsSeen, partialsNew, partialsGrow,
            actAgo, txtAgo
        )
    }

    private fun sensorWindowReset() {
        // Вердикт «датчик мёртв» осмыслен ТОЛЬКО когда кадры обязаны были идти: не заглушены
        // эхо-гейтом (gatedFrames) и сессия распознавателя реально работала (recActive). При
        // придержке ответа (гейт очереди) и в бэкоффе кадров честно ноль — это не поломка.
        if (rmsFrames == 0) LogBus.add(when {
            gatedFrames > 0 -> tr("RMS заглушён эхо-гейтом ($gatedFrames кадров) — датчик жив", "RMS muted by the echo gate ($gatedFrames frames) — sensor alive")
            !recActive -> tr("RMS нет: распознаватель в перезапуске или ждёт очередь озвучки", "no RMS: recognizer is restarting or waiting for the playback queue")
            else -> tr("RMS не приходит вовсе — датчик МЁРТВ, а не слеп", "RMS not arriving at all — sensor DEAD, not blind")
        })
        rmsFrames = 0; rmsVoiceFrames = 0; gatedFrames = 0
        rmsMin = Float.MAX_VALUE; rmsMax = -Float.MAX_VALUE
        partialsSeen = 0; partialsNew = 0; partialsGrow = 0
    }

    private fun scheduleAutoSend() {
        cancelAutoSend()
        // v0.6 диагностика: причина «почему не тикает» видна в журнале сразу
        // v0.34: пустой черновик при свежей догадке распознавателя — тоже повод для отсчёта
        // v0.46: свежесть догадки единая (8с) во всех трёх точках — иначе включённый флаг
        // EXTRA_PARTIAL_RESULTS начнёт слать в сессию обрывки получасовой давности
        val partialFresh = lastPartial.isNotEmpty() &&
            System.currentTimeMillis() - lastPartialTs < PARTIAL_FRESH_MS
        if (draft.isEmpty() && !partialFresh) return
        if (!Cfg.handsFree) { LogBus.add(tr("отсчёт тишины НЕ идёт: выключен безрукий режим", "silence countdown NOT running: hands-free mode is off")); return }
        if (!Cfg.autoSend) { LogBus.add(tr("отсчёт тишины НЕ идёт: выключена галочка авто-отправки", "silence countdown NOT running: auto-send checkbox is off")); return }
        val armNow = System.currentTimeMillis()
        if (firstArmTs == 0L) firstArmTs = armNow
        if (lastTextTs == 0L) lastTextTs = armNow
        if (lastActivityTs == 0L) lastActivityTs = armNow
        LogBus.add(tr("отсчёт тишины: 10с до бипа", "silence countdown: 10s to beep"))
        // v0.5.5 (кейс «пикает/тихо, но не отправляет в полной тишине»): гейт `listening` УБРАН из warn/fire —
        // если распознаватель умер (код 5) между набором черновика и выстрелом, сообщение ВСЁ РАВНО уходит.
        // Черновик есть = отправляем; живость распознавания — не условие доставки.
        var warnRef: Runnable? = null
        val warn = object : Runnable {
            override fun run() {
            if (!alive) { cancelAutoSend(); return }
            if (draft.isEmpty() && lastPartial.isEmpty()) { cancelAutoSend(); return }
            // v0.11 (боевое с улицы: «отправляет, хотя я продолжаю говорить»): раньше отсчёт
            // перезапускал ТОЛЬКО распознанный финал. На улице шум рвёт распознавание — финалов
            // нет по 10-20с, хотя человек говорит, и таймер выстреливал в середине фразы.
            // v0.46: продлевает ЛЮБОЙ признак жизни — громкость над честным фоном, финал или
            // выросшая догадка. Ограничителей теперь три и они независимы (см. константы).
            val now = System.currentTimeMillis()
            val quietFor = now - lastActivityTs
            val noTextFor = now - lastTextTs
            val armedFor = now - firstArmTs
            if (quietFor < 10000 && noTextFor < SOFT_CAP_MS && armedFor < HARD_CAP_MS) {
                // v0.46: строка печаталась чаще раза в секунду и вытесняла со скрина всё
                // остальное — не чаще раза в 5с, зато сразу с числами датчика
                if (now - lastSensorLogTs > 5000) {
                    lastSensorLogTs = now
                    val ch = if (noTextFor < 1500) tr("текст", "text") else tr("громкость", "volume")
                    LogBus.add(tr("ты ещё говоришь ($ch) — отсчёт заново | ", "you are still talking ($ch) — countdown restarted | ") + sensorLine(now))
                    sensorWindowReset()
                }
                autoSendTimer = this
                main.postDelayed(this, (10000 - quietFor).coerceAtLeast(1000))
                return
            }
            val reason = when {
                quietFor >= 10000 -> tr("тишина ${quietFor / 1000}с", "silence ${quietFor / 1000}s")
                noTextFor >= SOFT_CAP_MS -> tr("потолок 30с без нового текста", "30s cap with no new text")
                else -> tr("предел 90с", "90s limit")
            }
            beepAtTs = now
            beepBoth(ToneGenerator.TONE_PROP_BEEP, 90)
            LogBus.add(tr("бип: причина=$reason | ", "beep: reason=$reason | ") + sensorLine(now) + tr(" | перевзводов $sendRearms", " | re-arms $sendRearms"))
            sensorWindowReset()
            // v0.7: выстрел уважает ЖИВУЮ речь по громкости (RMS), а не по нервному «началу речи»:
            // громко у микрофона прямо сейчас → подождать 1.5с (до 8 раз), потом слать в любом случае.
            val fire = object : Runnable {
                var postpones = 0
                override fun run() {
                    if (!alive || (draft.isEmpty() && lastPartial.isEmpty())) { cancelAutoSend(); return }
                    val n2 = System.currentTimeMillis()
                    // v0.46 — прямой ответ на дословную жалобу «пик, и через две секунды уже ушло,
                    // хотя я продолжал говорить»: если ПОСЛЕ бипа пришёл новый распознанный текст,
                    // значит человек говорит и бип был ошибкой. Перевзводим полный цикл — но не
                    // больше REARM_MAX раз, и абсолютный потолок 90с всё равно сверху.
                    if (lastTextTs > beepAtTs && sendRearms < REARM_MAX && n2 - firstArmTs < HARD_CAP_MS) {
                        sendRearms++
                        LogBus.add(tr("после бипа пришёл текст — отсчёт перевзведён ($sendRearms/$REARM_MAX)", "text arrived after the beep — countdown re-armed ($sendRearms/$REARM_MAX)"))
                        val w = warnRef
                        if (w != null) { autoSendTimer = w; main.postDelayed(w, 10000); return }
                    }
                    // v0.11: вторая ступень тоже слушает голос ОТНОСИТЕЛЬНО фона, а не по жёсткому 7dB
                    if (n2 - lastVoiceTs < 1500 && postpones < 8) {
                        postpones++
                        if (postpones == 1) LogBus.add(tr("слышу голос — жду паузу…", "hearing voice — waiting for a pause…"))
                        autoSendTimer = this
                        main.postDelayed(this, 1500)
                        return
                    }
                    var msg = draft.toString().trim()
                    val fromDraft = msg.isNotEmpty()   // v0.46-fix: финалы в черновике vs голая догадка
                    draft.setLength(0)
                    if (msg.isEmpty() && lastPartial.isNotEmpty()) {
                        // v0.46-fix: свежесть от NOW = 30с (SOFT_CAP_MS). 8с (PARTIAL_FRESH_MS) здесь
                        // меньше собственной задержки таймера (10с тишины + 2с до выстрела), поэтому
                        // ветка не срабатывала НИКОГДА — бип звучал, а надиктованное молча гибло.
                        // 30с всё так же отсекает обрывки получасовой давности.
                        if (n2 - lastPartialTs < SOFT_CAP_MS) {
                            val c = cleanPartialForSend(lastPartial)   // догадка идёт мимо onFinalText — та же нормализация
                            if (c == null) LogBus.add(tr("догадка = «отмена» — не отправляю", "guess = “cancel” — not sending"))
                            else {
                                msg = c
                                if (msg.isNotEmpty()) LogBus.add(tr("финалов не было — шлю распознанное на лету", "no finals — sending what was recognized on the fly"))
                            }
                        } else {
                            LogBus.add(tr("нечего отправлять — догадка протухла (${(n2 - lastPartialTs) / 1000}с)", "nothing to send — guess went stale (${(n2 - lastPartialTs) / 1000}s)"))
                        }
                    }
                    lastPartial = ""
                    // v0.46 (аудит, латентный ОБРАТНЫЙ баг, лежал в коде): autoSendTimer после
                    // выстрела не обнулялся, и условие `autoSendTimer == null` в onPartialResults
                    // становилось ложным НАВСЕГДА — на пути «одни догадки, финалов нет» (то есть
                    // ровно в шуме) отсчёт больше никогда не взводился.
                    cancelAutoSend()
                    resetSendWindow()
                    if (msg.isNotEmpty()) {
                        LogBus.add(tr("авто-отправка: причина=$reason (отсрочек $postpones)", "auto-send: reason=$reason (postponed $postpones times)"))
                        send(msg)
                        // v0.52 (боевое, логи с самоката 30.07): спим ТОЛЬКО когда отправку выбрала
                        // ТИШИНА, то есть человек замолчал. Если дорезал потолок (30с без нового
                        // текста, 90с абсолюта) — человек ЕЩЁ ГОВОРИТ, и сон отрезал бы ему хвост
                        // мысли: ровно жалоба «я ещё говорю, а оно уже ушло», только теперь молча.
                        if (reason.startsWith(tr("тишина", "silence"))) sleepAfterSend()
                        // v0.46-fix: если ушла ДОГАДКА (не финал), распознаватель ещё дописывает ту же
                        // фразу и отдаст СВОЙ финал — он уехал бы в сессию дублем. Сбрасываем сессию,
                        // как уже делает sendNow (ветка догадки впервые ожила окном 30с, и на медленном
                        // движке в шуме без этого сброса сообщение уходит дважды).
                        if (!fromDraft) {
                            try { recognizer?.cancel() } catch (_: Exception) {}
                            if (Cfg.handsFree && listening) startRecognizerCycle()
                        }
                    }
                }
            }
            autoSendTimer = fire
            main.postDelayed(fire, 2000)
            }
        }
        warnRef = warn
        autoSendTimer = warn
        main.postDelayed(warn, 10000)   // v0.5.1 (слово юзера): 4с мало для улицы/раздумий — 10с до бипа, +2с до отправки
    }

    private fun cancelAutoSend() {
        autoSendTimer?.let { main.removeCallbacks(it) }
        autoSendTimer = null
    }

    private val micSleepCheck = object : Runnable {
        override fun run() {
            if (!alive) return
            // v0.8 «улетел бытовой разговор про Дашу» → v0.12 (не усыплять свежеподнятый) →
            // v0.37 (боевое: юзер на созвоне с музыкой — рация 40 минут отправляла окружение):
            // старый сон требовал 2 мин ТИШИНЫ, а фоновая музыка/разговор рядом «продлевали
            // речь» бесконечно — микрофон не засыпал никогда. Теперь сон считается от
            // ВЗАИМОДЕЙСТВИЯ: 5 минут без озвучек, без подъёма микрофона и без нажатий —
            // спим, каким бы громким ни был фон. Живую диктовку хранит сам диалог:
            // ответы сессии и кнопки обновляют отсчёт.
            val now = System.currentTimeMillis()
            val idleFor = now - maxOf(lastPlaybackTs, maxOf(listenStartedAt, userActionTs))
            // v0.92 (слово юзера: «мне сейчас нечего ответить, микрофон надо просто выключить
            // и ждать следующего ответа — не лезть же в телефон»). Молчание после подъёма
            // микрофона — само по себе ответ «мне нечего сказать»: 70с без единого слова
            // (не путать с тишиной в шуме — считаем по РАСПОЗНАННОЙ речи и своему детектору),
            // и микрофон засыпает молча. Разбудит ответ канала, кнопка или тангента.
            val neverSpoke = listening && !playing && draft.isEmpty() && uttSpeechMs == 0 &&
                lastVoiceTs < listenStartedAt &&
                now - maxOf(listenStartedAt, userActionTs) > 70000
            if (neverSpoke) {
                LogBus.add(tr("70с молчания — микрофон спит; разбудит ответ, кнопка или тангента", "70s of silence — mic asleep; a reply, button or push-to-talk will wake it"))
                if (Cfg.whisper && recThread != null) cancelReq = true
                sleepAfterSend()
                main.postDelayed(this, 30000)
                return
            }
            if (Cfg.handsFree && listening && !playing && idleFor > 300000) {
                // v0.61: в режиме «только слово и кнопка» таймеров, которые отправили бы черновик
                // раньше, БОЛЬШЕ НЕТ — значит накопленное здесь может быть живым сообщением, а не
                // фоновым мусором, и выбросить его молча было бы худшей бедой проекта. Досылаем.
                // Финал и сон делает сам рекордер своим проверенным путём (finalizeReq -> sleepReq
                // -> sleepAfterSend), он же корректно чистит uttId. Своего stopListening здесь НЕ
                // зовём: sleepAfterSend вышел бы по !listening и оставил uttId на закрытом utt —
                // ровно критический баг v0.51 (следующее сообщение сервер удалил бы молча).
                if (!Cfg.autoSend && Cfg.whisper && recThread != null && uttSpeechMs > 0) {
                    finalizeReq = true
                    LogBus.add(tr("5 мин без общения — досылаю накопленное, чтобы не потерять", "5 min without activity — flushing the backlog so nothing is lost"))
                } else {
                    // накопленное за 5 минут без диалога — фоновый мусор, не сообщение
                    if (Cfg.whisper && recThread != null) cancelReq = true
                    cue(tr("микрофон выключен", "microphone off"))
                    buzz(400)
                    stopListening()
                    LogBus.add(tr("диктофон уснул (5 мин без общения) — разбудит ответ или кнопка", "recorder fell asleep (5 min without activity) — a reply or button will wake it"))
                }
            }
            // v0.47: пишем телефоном, потому что гарнитура не отдала микрофон за 8с — но она
            // могла очнуться (вышла из глубокого сна, доподключила HFP). Событий устройств при
            // этом НЕ будет (устройство и так числилось подключённым), поэтому пробуем сами:
            // как только SCO поднимется, живой VOICE_COMMUNICATION-поток перекинется на гарнитуру.
            // v0.95: не поднимать канал ПОСРЕДИ реплики — иначе она тонет (её и чиним)
            if (listening && Cfg.btMic && !scoStarted && headsetPresent() &&
                cuePlayer == null && cueQueue.isEmpty() && cuePlayWhenReady.isEmpty()
            ) {
                startHeadsetMic()
                if (scoStarted) LogBus.add(tr("гарнитура отдала микрофон — переключился на неё", "headset gave the mic back — switched to it"))
            }
            // v0.12 (боевое «кнопки вообще перестали работать»): чужой плеер перехватывает
            // приоритет медиакнопок, а мы заявляли его только на старте озвучки/слушания.
            // Раз в 30с тихо забираем обратно — рации кнопка нужна всегда.
            grabMediaButtons()
            main.postDelayed(this, 30000)
        }
    }

    // v0.46-fix: догадка (lastPartial) уходит в сессию мимо onFinalText — прогоняем её через ту
    // же нормализацию и стоп-слово. Возврат null = это была «отмена», слать нечего.
    private fun cleanPartialForSend(t: String): String? {
        val norm = t.lowercase().replace(Regex("[^а-яёa-z0-9 ]"), " ").trim()
        if (CANCEL_WORDS.contains(norm)) return null
        if (!Cfg.handsFree) return t.trim()
        val words = norm.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val sendRe = Regex("^([оа]т?|т)п+рав\\w*$")
        val tailFillers = setOf("пожалуйста", "сейчас", "давай", "сообщение", "это", "уже", "быстро")
        var idxSend = -1
        if (Lang.en) {
            if (EN_SEND_PHRASES.contains(words.joinToString(" "))) return ""   // весь сегмент — команда
        } else if (words.isNotEmpty()) {
            val li = words.size - 1
            if (sendRe.matches(words[li])) idxSend = li
            else if (words.size >= 2 && tailFillers.contains(words[li]) && sendRe.matches(words[li - 1])) idxSend = li - 1
        }
        if (idxSend < 0) return t.trim()
        val matched = words[idxSend]
        val idx = t.lowercase().lastIndexOf(matched)
        return (if (idx >= 0) t.substring(0, idx) else t).trim().trimEnd(',', '.', '!', ' ')
    }

    private fun onFinalText(t: String) {
        lastRealSpeechTs = System.currentTimeMillis()   // v0.98: слова на пути распознавателя
        val norm = t.lowercase().replace(Regex("[^а-яёa-z0-9 ]"), " ").trim()
        val tail = norm.split(Regex("\\s+")).lastOrNull() ?: ""

        if (CANCEL_WORDS.contains(norm)) {
            cancelAutoSend()
            resetSendWindow()   // v0.46
            draft.setLength(0); lastPartial = ""   // v0.46-fix: догадка ушла вместе с мыслью — иначе fire/кнопка дошлёт её
            beepBoth(ToneGenerator.TONE_PROP_NACK, 250)
            LogBus.add(tr("отмена по голосу — черновик пуст", "cancelled by voice — draft is empty"))
            return
        }

        // Voice send is a hands-free gesture; in button mode the button ships it.
        // v0.4 (боевой фидбек 18.07: «отправляй» срабатывал раз через 10): узкополосный SCO-микрофон
        // гарнитуры коверкает слово («отправляет/отправить/оправляй»), а точный матч последнего слова
        // всё это ронял. Теперь: НЕЧЁТКИЙ матч по корню [оа]?т?прав* — последним словом ИЛИ предпоследним,
        // когда последнее — «хвостик» вроде «пожалуйста» (естественная речь). Ложный плюс дешевле немоты:
        // отправка подтверждается бипом, случайную можно продолжить следующим сообщением.
        if (Cfg.handsFree) {
            val words = norm.split(Regex("\\s+")).filter { it.isNotEmpty() }
            // v0.12 (боевое «я говорю-говорю, а оно уже пишет ушло»): корень был НЕ в таймере тишины.
            // Старый широкий шаблон ^[оа]?т?п+рав\w*$ матчил «правда», «правильно», «право», «правил» —
            // частейшие слова живой речи. Любое из них последним словом = мгновенная отправка посреди
            // мысли. Теперь приставка ОБЯЗАТЕЛЬНА (от/о/ат/а/т + прав...): искажения SCO по-прежнему
            // ловятся, а «правда/правильно» — уже нет.
            val sendRe = Regex("^([оа]т?|т)п+рав\\w*$")
            val tailFillers = setOf("пожалуйста", "сейчас", "давай", "сообщение", "это", "уже", "быстро")
            var idxSend = -1
            if (Lang.en) {
                if (EN_SEND_PHRASES.contains(words.joinToString(" "))) idxSend = 0
            } else if (words.isNotEmpty()) {
                val li = words.size - 1
                if (sendRe.matches(words[li])) idxSend = li
                else if (words.size >= 2 && tailFillers.contains(words[li]) && sendRe.matches(words[li - 1])) idxSend = li - 1
            }
            if (idxSend >= 0) {
                cancelAutoSend()
                resetSendWindow()   // v0.46
                val matched = words[idxSend]
                val cut = if (Lang.en) "" else t.trim().let {
                    val idx = it.lowercase().lastIndexOf(matched)
                    if (idx >= 0) it.substring(0, idx) else it
                }.trim().trimEnd(',', '.', '!', ' ')
                if (cut.isNotEmpty() && draft.isEmpty()) draftStartTs = listenStartedAt   // v1.43
                if (cut.isNotEmpty()) draft.append(if (draft.isEmpty()) "" else " ").append(cut)
                val msg = draft.toString().trim()
                draft.setLength(0); lastPartial = ""   // v0.46-fix: мысль ушла — догадку не дошлём дублем
                // v0.12: в журнале видно ПРИЧИНУ отправки — «ушло» больше не выглядит самопроизвольным
                if (msg.isNotEmpty()) { LogBus.add(tr("стоп-слово «$matched» — отправляю", "stop word “$matched” — sending")); send(msg); sleepAfterSend() }
                else LogBus.add(tr("нечего отправлять", "nothing to send"))
                return
            }
        }

        val finTs = System.currentTimeMillis()
        lastFinalTs = finTs   // v0.8: жива реальная диктовка
        // v0.46-fix: распознанный ФИНАЛ — сильнейший текстовый признак жизни (сильнее роста
        // догадки). Без него lastTextTs замирал на подъёме микрофона, и на аппарате без partial'ов
        // SOFT_CAP_MS (30с) резал живой монолог — регресс относительно v0.45, где потолок
        // перевзводился каждым финалом. HARD_CAP_MS (firstArmTs) остаётся неподвижным.
        lastTextTs = finTs
        lastActivityTs = finTs
        if (draft.isEmpty()) draftStartTs = listenStartedAt   // v1.43: см. send()
        draft.append(if (draft.isEmpty()) "" else " ").append(t.trim())
        LogBus.add(tr("черновик: …${draft.takeLast(80)}", "draft: …${draft.takeLast(80)}"))
        scheduleAutoSend()   // v0.5: новый кусок в черновике — перезапустить отсчёт тишины
    }

    // ── send back to the session ──────────────────────────────────────────
    // v0.36 (аудит): сбой сети больше НЕ теряет надиктованное безвозвратно (draft очищается
    // до send во всех путях): один повтор через 3с, затем текст ложится в очередь unsent и
    // досылается при реконнекте. Отклонение сервером (4xx) не повторяем — это не сеть.
    // v1.43 (хвост ревью v1.42): began — подъём микрофона, на котором начат черновик. Ответом на
    // текущее сообщение считаем только начатое ПОСЛЕ того, как оно зазвучало — как в utt_sent.
    // Момент отправки для этого не годится: черновик, начатый до ответа, засчитывался бы ему.
    private fun takeDraftStart(): Long {
        val b = if (draftStartTs > 0) draftStartTs else listenStartedAt
        draftStartTs = 0L
        return b
    }

    private fun send(text: String, attempt: Int = 0, began: Long = takeDraftStart()) {
        val sentAt = System.currentTimeMillis()
        val sid = Cfg.replyTarget.ifEmpty { lastSession ?: "" }
        val projLabel = SessionBook.all().firstOrNull { it.sid == sid }?.proj ?: lastProj
        if (sid.isEmpty()) {
            LogBus.add(tr("не знаю, в какую сессию отправлять (ещё не было озвучек)", "don't know which session to send to (no playback yet)"))
            return
        }
        val body = JSONObject().put("session_id", sid).put("text", text).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url("${Cfg.url}/say?token=${Cfg.token}")
            .post(body)
            .build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post {
                    if (!alive) return@post
                    if (attempt == 0) {
                        LogBus.add(tr("отправка не удалась (${e.message?.take(30)}) — повтор через 3с", "send failed (${e.message?.take(30)}) — retry in 3s"))
                        main.postDelayed({ if (alive) send(text, 1, began) }, 3000)
                        return@post
                    }
                    if (unsent.size < 10) unsent.addLast(text)
                    beepBoth(ToneGenerator.TONE_PROP_NACK, 300)
                    buzz(500)
                    cue(tr("не ушло, отправлю при связи", "not sent, will send when back online"))
                    LogBus.add(tr("отправка НЕ удалась: ${e.message} — сохранил, дошлю при связи: ${text.take(60)}", "send FAILED: ${e.message} — saved, will resend when connected: ${text.take(60)}"))
                    kickPlayback()   // v0.5.3: не держать очередь озвучек из-за сбоя отправки
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val ok = response.isSuccessful
                val code = response.code
                val respBody = try { response.body?.string() } catch (_: Exception) { null }
                val err = if (!ok) respBody?.take(120) else null
                response.close()
                main.post {
                    if (!alive) return@post
                    if (ok) {
                        beepBoth(ToneGenerator.TONE_PROP_BEEP2, 150)
                        // v0.5.4 (боевое «бип слышно не всегда»): подтверждение тем каналом, который
                        // доказанно доходит — TTS «ушло» идёт тем же аудиопутём, что озвучки ответов;
                        // + вибро (не зависит от аудиомаршрутов вообще).
                        // v0.10: если ответ уже ждёт в очереди — «ушло» НЕ произносим. TTS берёт свой
                        // audio focus и стартует ровно тогда же, когда плеер: система дакала/рубила
                        // озвучку в первые полсекунды. Вибро подтверждает отправку, а лучшее
                        // подтверждение — сам ответ, который сейчас заиграет.
                        val queuedNow = playQueue.isNotEmpty() || fileQueue.isNotEmpty()
                        // v0.37: unread от релея = очередь сессии никто не читает — честно сказать
                        val unread = try { JSONObject(respBody ?: "{}").optBoolean("unread") } catch (_: Exception) { false }
                        if (unread) {
                            LogBus.add(tr("ВНИМАНИЕ: «${projLabel ?: sid.take(8)}» не забирает сообщения — сессия закрыта или приёмник умер. Текст ждёт в очереди", "WARNING: “${projLabel ?: sid.take(8)}” is not picking up messages — session closed or receiver died. Text waits in the queue"))
                            if (System.currentTimeMillis() - lastUnreadCueTs > 10000) {
                                lastUnreadCueTs = System.currentTimeMillis()
                                cue(tr("канал не слушает", "channel is not listening"))
                                suppressListenCueUntil = System.currentTimeMillis() + 8000   // v0.82
                            }
                            buzz(400)
                        } else {
                            // v1.13: подтверждение звучит ВСЕГДА (см. тот же фикс в utt_sent).
                            // Раньше гейт `!playing || paused` глотал его, если в этот момент
                            // играл ответ другого канала — юзер не знал, ушло ли сообщение.
                            cue(when {
                                playing && !paused -> sentPhraseOverAnswer(sid)   // v1.15: см. utt_sent
                                queuedNow -> sentPhraseWithNext(sid)   // v0.83: «ушло в X. дальше Y»
                                else -> sentCuePhrase(sid)
                            })
                        }
                        try {
                            val vib = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
                            if (Build.VERSION.SDK_INT >= 26) vib?.vibrate(android.os.VibrationEffect.createOneShot(180, 200))
                            else @Suppress("DEPRECATION") vib?.vibrate(180)
                        } catch (_: Exception) {}
                        LogBus.add(tr("отправлено в «${projLabel ?: "?"}»: ${text.take(60)}", "sent to “${projLabel ?: "?"}”: ${text.take(60)}"))
                        // v0.69: эхо для ленты-чата — сам движок не меняется (см. Feed.kt)
                        if (began >= speakerStartTs) answeredCurrent = true   // v0.81; v1.43: начало черновика, см. utt_sent
                        // v0.85: ручной выбор действовал на это сообщение (v1.16-fix: если оно ушло
                        // в него и отправлено после выбора)
                        if (sid == Cfg.replyTarget && sentAt >= manualPinTs) manualPinTs = 0L
                        lastSendOkTs = System.currentTimeMillis()   // v0.90
                        awaitSendCueUntil = 0L                      // v1.08
                        Feed.userSent(this@BridgeService, sid, text, unread)
                        applyPendingTarget()   // v0.78: отложенное переключение адресата
                    } else {
                        beepBoth(ToneGenerator.TONE_PROP_NACK, 300)
                        LogBus.add(tr("отправка ОТКЛОНЕНА ($code): ${err ?: "?"} — текст: ${text.take(60)}", "send REJECTED ($code): ${err ?: "?"} — text: ${text.take(60)}"))
                    }
                    kickPlayback()   // v0.5.3: отправка ушла — выпустить отложенный ответ из очереди
                }
            }
        })
    }
}
