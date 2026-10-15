package com.vladiko.voicebridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import android.view.HapticFeedbackConstants
import android.view.inputmethod.InputMethodManager
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

// v0.69: полный редизайн под обывателя («Рация одной кнопки», макет утверждён 15.08).
// Один экран: лента-чат (пузыри из /history релея + локальное эхо «ушло»), круг-тангента,
// который сам и есть статус, шторка «каналы» (solo-режим), настройки человеческим языком.
// Вся техника (whisper, источники, таймеры) — «для продвинутых»; журнал — в «отладке».
// ДВИЖОК (BridgeService) НЕ МЕНЯЛСЯ — экран говорит с ним через прежний публичный API.
class MainActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())

    // Палитра прежняя: уголь / карточка / кремовый текст / единственный акцент терракота.
    private val cBg = 0xFF1B1A19.toInt()
    private val cCard = 0xFF262523.toInt()
    private val cLine = 0xFF33302C.toInt()
    private val cText = 0xFFF0EEE6.toInt()
    private val cDim = 0xFF9C968C.toInt()
    private val cFaint = 0xFF6B665E.toInt()
    private val cPlay = 0xFFD97757.toInt()
    private val cLive = 0xFFC96442.toInt()
    private val cMic = 0xFF343230.toInt()
    private val cWarn = 0xFF6E5B3A.toInt()
    private val cAccSoft = 0x29D97757          // терракота 16% — исходящие пузыри и кольцо круга

    // ── главный экран ──
    private lateinit var rootFrame: FrameLayout
    private lateinit var chipBtn: TextView
    private lateinit var muteBtn: TextView
    private lateinit var voiceBtn: TextView
    private lateinit var feedScroll: ScrollView
    private lateinit var feedBox: LinearLayout
    private lateinit var bannerBtn: TextView
    private lateinit var playerStrip: LinearLayout
    private lateinit var playerLabel: TextView
    private lateinit var scrubBar: SeekBar
    private lateinit var speedLabel: TextView
    private lateinit var circle: LinearLayout
    private lateinit var circleBig: TextView
    private lateinit var circleSub: TextView
    private lateinit var hintText: TextView
    private lateinit var cancelBtn: TextView
    private lateinit var talkBtn: TextView
    private lateinit var dockPause: TextView
    // окно текста: набранное уходит тем же путём, что надиктованное (/say → очередь канала)
    private lateinit var textEdit: EditText
    private lateinit var textSendBtn: TextView
    // скрепка: файл уходит POST /hubfile в канал хаба, открытый в ленте; хаб кладёт его
    // в папку проекта (inbox/дата) и отдаёт путь агенту со следующим сообщением
    private var fileTargetSid = ""
    private var fileTargetName = ""
    private val pickFile = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> uris.forEach { sendFile(it) } }
    private lateinit var textStatus: TextView
    private var textSending = false

    // ── оверлеи ──
    private lateinit var sheetOverlay: FrameLayout
    private lateinit var sheetList: LinearLayout
    private lateinit var settingsOverlay: ScrollView
    private lateinit var advBox: LinearLayout
    private lateinit var debugOverlay: LinearLayout
    private lateinit var debugTech: TextView
    private lateinit var logView: TextView
    private lateinit var onboardOverlay: ScrollView
    private lateinit var urlEdit: EditText
    private lateinit var tokenEdit: EditText
    private lateinit var pinEdit: EditText
    private lateinit var serviceBtn: Button
    private var autoSendSwitchRef: Switch? = null

    // ── состояние ленты ──
    private var feedSid = ""
    private var feedBusy = false
    private var lastFeedFetch = 0L
    // сентинел, а не "": sig ПУСТОЙ ленты — тоже пустая строка, и равенство съедало
    // первый рендер заглушки / чистку чужих пузырей при смене канала
    private var lastFeedSig = "#init"
    private var feedStick = true              // автопрокрутка вниз, пока юзер сам не отлистал
    private var feedForcePending = false      // force-запрос пришёл во время полёта — догнать
    private var pendingRows: List<Row>? = null   // перерисовка отложена: юзер читает старое
    private var pendingOffline = false
    private val feedBodies = ArrayList<TextView>()   // bubble texts; a live selection defers redraw
    private var lastMsgs: List<InMsg> = emptyList()
    private var lastRows: List<Row> = emptyList()
    private var lastOffline = false
    private var feedExpandAll = false            // v0.71: пузыри по 3 строки / все целиком
    private val feedExpanded = HashSet<String>() // точечные раскрытия тапом (XOR с общим)
    private val aliveMap = HashMap<String, Boolean>()
    private var sheetShowAll = false
    private var sheetEditMode = false                       // v1.09: правка списка галочками
    private val serverSids = LinkedHashMap<String, String>() // sid -> имя, всё что знает релей
    private var sheetMode = "channels"   // чем занята шторка: "channels" | "held" | "models" | "hubch"
    // экран «сессии хаба»: каналы создаёт и удаляет хаб по JSON-команде в служебный канал
    private val hubControlSid = "hub-control"
    private var hubReq = ""              // req последней команды; итог хаб кладёт в /hubstate
    private var hubReqCmd = ""
    private var hubReqArg = ""           // имя создаваемого канала или id удаляемого
    private var hubReqText = ""
    private var hubReqTs = 0L
    private var hubReqDone = false
    private var hubReqError = ""         // команда не дошла до релея
    private var hubProject = ""          // выбранный проект для нового канала
    private var hubFolder = ""           // или любая папка сервера (абсолютный путь)
    private var hubFoldersOpen = false
    private var hubNameEdit: EditText? = null
    private var hubGitEdit: EditText? = null     // канал по git-адресу вместо проекта из списка
    // экран «расходы»: цена и срок подписки вводит владелец
    private var planEditSub = ""
    private var planPriceEdit: EditText? = null
    private var planEndsEdit: EditText? = null
    // права канала: какие подписки могут работать в нём (пусто = все)
    private val hubAllowNew = LinkedHashSet<String>()
    private var hubAllowEditId = ""
    private val hubAllowDraft = LinkedHashSet<String>()
    private val hubAllowOpts = ArrayList<Pair<String, String>>()   // имя подписки → подпись
    private var hubReqWaitMs = 60000L    // ключ хаб проверяет пробным запуском: ждём дольше
    // экран «подключения»: ключи API и вход в подписки через браузер
    private var hubKeyProvider = ""      // у какого поставщика открыто поле ключа
    private var hubKeyReplace = false
    private var hubKeyEdit: EditText? = null
    private var hubNfClientIdEdit: EditText? = null
    private var hubNfClientSecretEdit: EditText? = null
    private var hubNfWorkspaceEdit: EditText? = null
    private var hubCodeEdit: EditText? = null
    private var lastModelsFetch = 0L     // экран «Модели»: состояние хаба с релея (/hubstate)
    private var lastModels: JSONObject? = null  // последнее полученное: сбой сети не стирает экран
    private var lastSheetTick = 0L       // v0.79: живое обновление открытого списка каналов
    private var lastSessionsFetch = 0L
    private var userScrubbing = false
    private var lastCircleKey = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 14) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun bordered(radius: Int = 12) = GradientDrawable().apply {
        setColor(0)
        setStroke(dp(1), cLine)
        cornerRadius = dp(radius).toFloat()
    }

    // Круг = два овала: полупрозрачное кольцо-«свечение» и тело поверх.
    private fun circleBg(inner: Int, ring: Int): LayerDrawable {
        val outer = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ring) }
        val body = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(inner) }
        return LayerDrawable(arrayOf(outer, body)).apply {
            setLayerInset(1, dp(8), dp(8), dp(8), dp(8))
        }
    }

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    // v1.05 (слово юзера: «прокручиваю вверх и не понимаю, это сегодня или неделю назад»):
    // разделители дней между пузырями, как в мессенджерах.
    private fun dayKey(ts: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(ts))

    private fun dayLabel(ts: Long): String {
        val today = dayKey(System.currentTimeMillis())
        val yesterday = dayKey(System.currentTimeMillis() - 86400000L)
        val k = dayKey(ts)
        return when (k) {
            today -> tr("сегодня", "today")
            yesterday -> tr("вчера", "yesterday")
            else -> java.text.SimpleDateFormat("d MMMM", Lang.locale).format(java.util.Date(ts))
        }
    }

    private fun hhmm(ts: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ts))

    private fun timeAgo(ts: Long): String {
        if (ts <= 0) return "?"
        val s = (System.currentTimeMillis() - ts) / 1000
        return when {
            s < 60 -> tr("только что", "just now")
            s < 3600 -> tr("${s / 60} мин назад", "${s / 60} min ago")
            s < 86400 -> tr("${s / 3600} ч назад", "${s / 3600} h ago")
            else -> tr("${s / 86400} дн назад", "${s / 86400} d ago")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    @SuppressLint("UseSwitchCompatOrMaterialCode", "BatteryLife")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cfg.load(this)
        SessionBook.load(this)
        Feed.load(this)
        savedInstanceState?.let { state ->
            fileTargetSid = state.getString("fileTargetSid").orEmpty()
            fileTargetName = state.getString("fileTargetName").orEmpty()
            feedSid = state.getString("feedSid").orEmpty()
            hubReq = state.getString("hubReq").orEmpty()
            hubReqCmd = state.getString("hubReqCmd").orEmpty()
            hubReqArg = state.getString("hubReqArg").orEmpty()
            hubReqText = state.getString("hubReqText").orEmpty()
            hubReqTs = state.getLong("hubReqTs")
            hubReqDone = state.getBoolean("hubReqDone")
            hubReqError = state.getString("hubReqError").orEmpty()
            hubReqWaitMs = state.getLong("hubReqWaitMs", 60000L)
        }

        rootFrame = FrameLayout(this).apply { setBackgroundColor(cBg) }

        buildMainScreen()
        buildSheetOverlay()
        buildSettingsOverlay()
        buildDebugOverlay()
        buildOnboardOverlay()
        textEdit.setText(savedInstanceState?.getString("textDraft").orEmpty())

        setContentView(rootFrame)
        // targetSdk 35: edge-to-edge принудителен — отступы под статусбар и жесты сами.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootFrame) { v, insets ->
            // ime() обязателен: targetSdk 35 = принудительный edge-to-edge, окно под
            // клавиатуру само не ресайзится — без этого она ложится поверх полей настроек
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout() or
                    androidx.core.view.WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    sheetOverlay.visibility == View.VISIBLE -> sheetOverlay.visibility = View.GONE
                    debugOverlay.visibility == View.VISIBLE -> debugOverlay.visibility = View.GONE
                    settingsOverlay.visibility == View.VISIBLE -> settingsOverlay.visibility = View.GONE
                    onboardOverlay.visibility == View.VISIBLE && Cfg.token.isNotEmpty() ->
                        onboardOverlay.visibility = View.GONE
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        askPermissions()
        // только живой запуск: после пересоздания (поворот) intent уже отработан
        if (savedInstanceState == null) handleDeepLink(intent)
        if (Cfg.token.isEmpty()) onboardOverlay.visibility = View.VISIBLE
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("fileTargetSid", fileTargetSid)
        outState.putString("fileTargetName", fileTargetName)
        outState.putString("feedSid", feedSid)
        outState.putString("textDraft", textEdit.text.toString())
        outState.putString("hubReq", hubReq)
        outState.putString("hubReqCmd", hubReqCmd)
        outState.putString("hubReqArg", hubReqArg)
        outState.putString("hubReqText", hubReqText)
        outState.putLong("hubReqTs", hubReqTs)
        outState.putBoolean("hubReqDone", hubReqDone)
        outState.putString("hubReqError", hubReqError)
        outState.putLong("hubReqWaitMs", hubReqWaitMs)
    }

    // ── ссылка-настройка: claudio://setup?url=…&token=…&pin=… ────────────
    // Скрипт установки релея печатает её (и QR) — конфиг встаёт сам, без клавиатуры.
    private fun handleDeepLink(i: Intent?) {
        val u = i?.data ?: return
        // тот же Intent доставляется повторно при пересоздании Activity (поворот) —
        // гасим data, чтобы настройка не переигрывалась и не дёргала рестарт сервиса
        i.data = null
        if (!"claudio".equals(u.scheme, true) || !"setup".equals(u.host, true)) return
        val url = u.getQueryParameter("url").orEmpty().trim().trimEnd('/')
        val token = u.getQueryParameter("token").orEmpty().trim()
        val pin = u.getQueryParameter("pin").orEmpty().trim()
        if (url.isEmpty() || token.isEmpty()) {
            LogBus.add(tr("ссылка-настройка неполная — нужны url и token", "setup link incomplete — url and token needed"))
            return
        }
        // Только https и только с хостом: битая ссылка не должна затирать рабочий конфиг
        if (!url.startsWith("https://") || Uri.parse(url).host.isNullOrEmpty()) {
            LogBus.add(tr("ссылка-настройка отклонена: нужен https-адрес с хостом", "setup link rejected: needs an https address with a host"))
            return
        }
        // Та же самая ссылка второй раз — ничего не менять и не рестартовать сервис
        if (url == Cfg.url && token == Cfg.token && (pin.isEmpty() || pin == Cfg.pin)) {
            LogBus.add(tr("ссылка-настройка: конфиг уже такой — ничего не меняю", "setup link: config is already the same — changing nothing"))
            if (::onboardOverlay.isInitialized) onboardOverlay.visibility = View.GONE
            return
        }
        // Ссылку может подсунуть любой сайт/QR (BROWSABLE): молчаливая настройка релея =
        // угон диктовки. Спрашиваем ВСЕГДА, в том числе на чистой установке — там чужая
        // ссылка опаснее всего: человек ещё не знает, какой адрес у него должен быть.
        val host = Uri.parse(url).host ?: url
        val fresh = Cfg.token.isEmpty()
        LogBus.add(tr("ссылка-настройка: прошу подтвердить сервер «${host}»", "setup link: asking to confirm server “${host}”"))
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(
                if (fresh) tr("подключить рацию к серверу «${host}»?\nвся диктовка пойдёт туда.\nэто твой сервер?", "connect the radio to server “${host}”?\nall dictation will go there.\nis this your server?")
                else tr("заменить настройки релея на «${host}»?\nвся диктовка пойдёт на этот сервер.", "replace relay settings with “${host}”?\nall dictation will go to this server.")
            )
            .setPositiveButton(if (fresh) tr("подключить", "connect") else tr("заменить", "replace")) { _, _ -> applyDeepLink(url, token, pin) }
            .setNegativeButton(tr("отмена", "cancel")) { _, _ -> LogBus.add(tr("настройка по ссылке отклонена", "setup by link rejected")) }
            .show()
    }

    private fun applyDeepLink(url: String, token: String, pin: String) {
        Cfg.url = url
        Cfg.token = token
        if (pin.isNotEmpty()) Cfg.pin = pin
        Cfg.save(this)
        if (::urlEdit.isInitialized) {
            urlEdit.setText(Cfg.url); tokenEdit.setText(Cfg.token); pinEdit.setText(Cfg.pin)
        }
        LogBus.add(tr("конфиг пришёл по ссылке-настройке — сохранён", "config came via setup link — saved"))
        if (::onboardOverlay.isInitialized) onboardOverlay.visibility = View.GONE
        if (BridgeService.running) restartService()
        testRelay()
    }

    // ── главный экран: шапка · лента · круг · док ────────────────────────
    private fun buildMainScreen() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }

        // шапка: канал-чип · заглушить микрофон · настройки
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chipBtn = tv("▾ —", 13.5f, cPlay).apply {
            background = GradientDrawable().apply {
                setColor(0); setStroke(dp(1), cPlay); cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            contentDescription = tr("текущий канал — тап открывает список", "current channel — tap to open the list")
            setOnClickListener { openSheet() }
            // наследник старой кнопки «слушать крайнее»: свежайшее сообщение ЛЮБОЙ сессии
            setOnLongClickListener {
                LogBus.add(tr("чип (долгий тап) — играю крайнее отовсюду", "chip (long tap) — playing the latest from everywhere"))
                ensureService { it.playLatestEverywhere() }
                true
            }
        }
        muteBtn = tv("🔇", 16f, cDim).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            contentDescription = tr("заглушить или включить микрофон", "mute or unmute the microphone")
            setOnClickListener {
                val svc = BridgeService.instance
                if (svc == null || !BridgeService.running) { LogBus.add(tr("рация выключена", "radio is off")); return@setOnClickListener }
                svc.toggleMicMute()
            }
        }
        val gear = tv("⚙", 18f, cDim).apply {
            setPadding(dp(10), dp(4), dp(4), dp(4))
            contentDescription = tr("настройки", "settings")
            setOnClickListener { settingsOverlay.visibility = View.VISIBLE }
        }
        // v0.71: раскрыть/свернуть ВСЮ переписку одним тапом (пузыри по умолчанию — 3 строки)
        val expandAllBtn = tv("⤢", 16f, cFaint).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            contentDescription = tr("раскрыть или свернуть всю переписку", "expand or collapse the whole conversation")
            setOnClickListener {
                feedExpandAll = !feedExpandAll
                feedExpanded.clear()
                setTextColor(if (feedExpandAll) cPlay else cFaint)
                renderFeed(lastRows, lastOffline, force = true)
            }
        }
        head.addView(chipBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        head.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        head.addView(expandAllBtn)
        voiceBtn = tv("🔈", 16f, cDim).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            contentDescription = tr("выключить или включить голос (переписка остаётся)", "turn voice off or on (conversation stays)")
            setOnClickListener {
                ensureService { svc -> svc.toggleVoiceOff() }
            }
        }
        head.addView(voiceBtn)
        head.addView(muteBtn)
        head.addView(gear)

        // лента-чат
        feedBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        feedScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(feedBox, MATCH_PARENT, WRAP_CONTENT)
            setOnScrollChangeListener { _, _, sy, _, _ ->
                val was = feedStick
                feedStick = sy + height >= feedBox.height - dp(48)
                // юзер дочитал и вернулся вниз — отдаём отложенную перерисовку
                if (feedStick && !was) pendingRows?.let { renderFeed(it, pendingOffline) }
            }
        }

        // плашка «есть ответы» (накопленное ждёт команды — контракт v0.49)
        bannerBtn = tv("", 12.5f, cText).apply {
            background = rounded(cWarn, 12)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            gravity = Gravity.CENTER
            visibility = View.GONE
            setOnClickListener {
                if (Cfg.token.isEmpty()) { onboardOverlay.visibility = View.VISIBLE; return@setOnClickListener }
                // v0.71 (слово юзера): куча из нескольких каналов — сначала разбивка,
                // послушать или пропустить каждый; один канал — играем сразу, как раньше
                ensureService { svc ->
                    val br = svc.heldBreakdown()
                    LogBus.add(tr("накопленное: ", "held: ") + br.joinToString(", ") { tr("${it.second} ${it.third} ч.", "${it.second} ${it.third} h") })
                    // список показываем ВСЕГДА (слово юзера: «он даже не показывает список»):
                    // видно, чьи ответы ждут, и звук начинается только по твоей команде
                    if (br.isEmpty()) LogBus.add(tr("накопленного нет — играть нечего", "nothing held — nothing to play")) else openHeldSheet(br)
                }
            }
        }

        // полоска плеера — видна только пока играет/на паузе
        playerLabel = tv("—", 11.5f, cDim)
        scrubBar = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(cPlay)
            thumbTintList = android.content.res.ColorStateList.valueOf(cPlay)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar) { userScrubbing = true }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    val d = BridgeService.instance?.durationMs() ?: 0
                    if (d > 0) BridgeService.instance?.seekToMs((sb.progress / 1000f * d).toInt())
                    userScrubbing = false
                }
            })
        }
        speedLabel = tv("%.1f×".format(Cfg.speed), 12f, cText, bold = true).apply {
            gravity = Gravity.CENTER
        }
        fun stripBtn(label: String, desc: String, onClick: () -> Unit) = tv(label, 12f, cText).apply {
            background = bordered(10)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            contentDescription = desc
            setOnClickListener { onClick() }
        }
        val stripRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bBack = stripBtn(tr("−10с", "−10s"), tr("назад на 10 секунд", "back 10 seconds")) { BridgeService.instance?.nudgeMs(-10000) }
        val bFwd = stripBtn(tr("+10с", "+10s"), tr("вперёд на 10 секунд", "forward 10 seconds")) { BridgeService.instance?.nudgeMs(10000) }
        val bSlow = stripBtn("−", tr("медленнее", "slower")) {
            speedLabel.text = "%.1f×".format(BridgeService.instance?.nudgeSpeed(-0.1f) ?: Cfg.speed)
        }
        val bFast = stripBtn("+", tr("быстрее", "faster")) {
            speedLabel.text = "%.1f×".format(BridgeService.instance?.nudgeSpeed(0.1f) ?: Cfg.speed)
        }
        for ((i, b) in listOf(bBack, bFwd, bSlow, speedLabel, bFast).withIndex()) {
            stripRow.addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, if (b === speedLabel) 0.9f else 1f).apply {
                if (i > 0) leftMargin = dp(6)
            })
        }
        playerStrip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cCard, 12)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
            addView(playerLabel)
            addView(scrubBar)
            addView(stripRow)
        }

        // круг-тангента: цвет и подпись = состояние рации
        circleBig = tv(tr("говорить", "talk"), 15f, cBg, bold = true).apply { gravity = Gravity.CENTER }
        circleSub = tv("", 9.5f, cBg).apply { gravity = Gravity.CENTER; alpha = 0.75f }
        circle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = circleBg(cPlay, cAccSoft)
            addView(circleBig)
            addView(circleSub)
            contentDescription = tr("тангента: говорить, отправить, прервать", "the circle: talk, send, interrupt")
            setOnClickListener { circleTap() }
            setOnLongClickListener { powerToggle(); true }
        }

        hintText = tv("", 10.5f, cFaint).apply {
            gravity = Gravity.CENTER
            // v0.61 живёт: сравнение режимов отправки одним жестом с главного экрана
            setOnLongClickListener {
                Cfg.autoSend = !Cfg.autoSend
                Cfg.save(this@MainActivity)
                autoSendSwitchRef?.isChecked = Cfg.autoSend
                LogBus.add(
                    if (Cfg.autoSend) tr("режим отправки: таймер тишины включён", "send mode: silence timer is on")
                    else tr("режим отправки: только «отправляй», кнопка и тангента", "send mode: only “send”, the button and the circle")
                )
                true
            }
        }
        cancelBtn = tv(tr("✕ отмена", "✕ cancel"), 11f, cDim).apply {
            background = bordered(10)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = View.GONE
            contentDescription = tr("стереть черновик", "erase draft")
            setOnClickListener { BridgeService.instance?.onCancel() }
        }
        // v0.71: круг во время озвучки ставит паузу — «прервать и говорить» живёт здесь
        talkBtn = tv(tr("🎙 прервать и говорить", "🎙 interrupt and talk"), 11f, cDim).apply {
            background = bordered(10)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = View.GONE
            contentDescription = tr("оборвать озвучку и диктовать", "cut off the voice and dictate")
            setOnClickListener { BridgeService.instance?.talkNow() }
        }
        // v0.71.1: подсказка и кнопки — ДВУМЯ рядами. Однострочный ряд «подсказка + отмена +
        // прервать» был шире экрана, и «прервать и говорить» уезжала за правый край (скрин юзера).
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(cancelBtn)
            addView(talkBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(10) })
        }
        val pttZone = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(6))
            addView(circle, LinearLayout.LayoutParams(dp(148), dp(148)))
            addView(hintText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(9) })
            addView(btnRow, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        }

        // док: пауза · каналы · журнал
        fun dockBtn(label: String, desc: String, onClick: () -> Unit) = tv(label, 12f, cDim).apply {
            background = bordered(12)
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, dp(9))
            contentDescription = desc
            setOnClickListener { onClick() }
        }
        dockPause = dockBtn(tr("⏸ пауза", "⏸ pause"), tr("пауза и продолжение", "pause and resume")) {
            val svc = BridgeService.instance
            if (svc == null || !BridgeService.running) { LogBus.add(tr("рация выключена", "radio is off")); return@dockBtn }
            when {
                svc.isPaused() -> svc.resumePlayback()
                svc.isPlaying() -> svc.pausePlayback()
                svc.isListening() -> svc.holdDictation()
                svc.hasPendingDictation() -> svc.resumeDictation()
                // в покое = «↻ повтор»: офлайн-повтор последнего ответа из lastBatch,
                // как тройное нажатие гарнитуры (сети не требует)
                else -> svc.replayLast()
            }
        }
        dockPause.setOnLongClickListener {
            BridgeService.instance?.stopPlayback(false); true
        }
        val dockCh = dockBtn(tr("каналы", "channels"), tr("список каналов", "channel list")) { openSheet() }
        val dockLog = dockBtn(tr("журнал", "log"), tr("журнал и отладка", "log and debug")) { debugOverlay.visibility = View.VISIBLE; renderLog() }
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((i, b) in listOf(dockPause, dockCh, dockLog).withIndex()) {
                addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(8) })
            }
        }

        // окно текста: строка ввода + «➤». Адресат — канал, открытый в ленте (туда же ляжет пузырь).
        // Движок и адресат голоса не трогаются: набор текста не меняет, куда уйдёт диктовка.
        textEdit = EditText(this).apply {
            hint = tr("написать текстом…", "type a message…")
            setHintTextColor(cFaint)
            setTextColor(cText)
            textSize = 14f
            background = bordered(14)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
            contentDescription = tr("текст сообщения в открытый канал", "message text for the open channel")
        }
        textSendBtn = tv("➤", 18f, cPlay).apply {
            gravity = Gravity.CENTER
            background = bordered(14)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            contentDescription = tr("отправить текст в открытый канал", "send text to the open channel")
            setOnClickListener { sendTyped() }
        }
        textStatus = tv("", 10f, cFaint).apply {
            visibility = View.GONE
            setPadding(dp(4), dp(3), dp(4), 0)
        }
        val clipBtn = tv("📎", 17f, cDim).apply {
            gravity = Gravity.CENTER
            background = bordered(14)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            contentDescription = tr("отправить файл в открытый канал хаба", "send a file to the open hub channel")
            setOnClickListener { pickFileForChannel() }
        }
        val textRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(textEdit, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(clipBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(6) })
            addView(textSendBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(6) })
        }
        val textBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(textRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(textStatus)
        }

        val gap = { h: Int -> LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(h) } }
        col.addView(head)
        col.addView(feedScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        col.addView(bannerBtn, gap(6))
        col.addView(playerStrip, gap(6))
        col.addView(textBox, gap(6))
        col.addView(pttZone, gap(2))
        col.addView(dock, gap(8))
        rootFrame.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    // ── окно текста ──────────────────────────────────────────────────────
    // Набранное уходит тем же /say, что распознанное телефоном, в очередь канала, открытого
    // в ленте. Релей кладёт его с той же меткой [ГОЛОС С ТЕЛЕФОНА] (на неё завязаны мост,
    // шлюз и правила сессий), поэтому приставка «(набрано текстом)» говорит получателю, что
    // ошибок распознавания тут нет. Подтверждение только видимое: без звука и без «ушло».
    private fun sendTyped() {
        if (textSending) return
        val draft = textEdit.text.toString()
        val raw = draft.trim()
        if (raw.isEmpty()) return
        if (Cfg.token.isEmpty() || Cfg.url.isEmpty()) { onboardOverlay.visibility = View.VISIBLE; return }
        val sid = feedSid.ifEmpty { currentSid() }
        val name = feedName()
        if (sid.isEmpty()) {
            showTextStatus(tr("не знаю, кому отправить: выбери канал в «каналы»", "don't know who to send to: pick a channel in “channels”"), cPlay)
            return
        }
        val client = try {
            Net.client(Cfg.pin).newBuilder().callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
        } catch (e: Exception) {
            showTextStatus(tr("не ушло: кривой пин в настройках", "not sent: bad pin in settings"), cPlay)
            return
        }
        val body = JSONObject().put("session_id", sid).put("text", tr("(набрано текстом) $raw", "(typed) $raw")).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = try {
            Request.Builder().url("${Cfg.url}/say?token=${Cfg.token}").post(body).build()
        } catch (e: Exception) {
            showTextStatus(tr("не ушло: кривой адрес релея в настройках", "not sent: bad relay address in settings"), cPlay)
            return
        }
        textSending = true
        textSendBtn.alpha = 0.4f
        showTextStatus(tr("отправляю в «$name»…", "sending to “$name”…"), cFaint)
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post {
                    textSending = false
                    textSendBtn.alpha = 1f
                    showTextStatus(tr("не ушло: нет связи — текст остался в поле", "not sent: offline — text stayed in the field"), cPlay)
                    LogBus.add(tr("текст НЕ ушёл в «$name»: ${e.message}", "text NOT sent to “$name”: ${e.message}"))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val ok = response.isSuccessful
                val code = response.code
                val respBody = try { response.body?.string() } catch (_: Exception) { null }
                response.close()
                val unread = try { JSONObject(respBody ?: "{}").optBoolean("unread") } catch (_: Exception) { false }
                main.post {
                    textSending = false
                    textSendBtn.alpha = 1f
                    if (!ok) {
                        showTextStatus(tr("не ушло: релей ответил $code — текст остался в поле", "not sent: relay replied $code — text stayed in the field"), cPlay)
                        LogBus.add(tr("текст НЕ ушёл в «$name»: $code ${respBody?.take(80)}", "text NOT sent to “$name”: $code ${respBody?.take(80)}"))
                        return@post
                    }
                    if (textEdit.text.toString() == draft) textEdit.setText("")
                    textSendBtn.performHapticFeedback(
                        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
                        else HapticFeedbackConstants.VIRTUAL_KEY
                    )
                    showTextStatus(
                        if (unread) tr("ушло в «$name», но канал не слушает — текст ждёт в очереди", "sent to “$name”, but the channel isn't listening — text waits in the queue")
                        else tr("ушло в «$name» ✓", "sent to “$name” ✓"),
                        if (unread) cPlay else cDim
                    )
                    LogBus.add(tr("текстом в «$name»: ${raw.take(60)}", "typed to “$name”: ${raw.take(60)}"))
                    // эхо в ленту тем же путём, что у голоса: Feed пишет факт и будит ленту
                    Feed.userSent(this@MainActivity, sid, raw, unread)
                }
            }
        })
    }

    private val fileLimit = 20L * 1024 * 1024   // столько принимает релей

    private fun pickFileForChannel() {
        if (Cfg.token.isEmpty() || Cfg.url.isEmpty()) { onboardOverlay.visibility = View.VISIBLE; return }
        val sid = feedSid.ifEmpty { currentSid() }
        if (!sid.startsWith("hub-") || sid == hubControlSid) {
            showTextStatus(tr("файл можно отправить только в канал хаба: открой его в ленте", "a file can only go to a hub channel: open it in the feed"), cPlay)
            return
        }
        fileTargetSid = sid
        fileTargetName = feedName()
        try { pickFile.launch(arrayOf("*/*")) }
        catch (_: Exception) { showTextStatus(tr("на телефоне нечем выбрать файл", "no app on the phone to pick a file"), cPlay) }
    }

    private fun sendFile(uri: Uri) {
        val sid = fileTargetSid
        val chName = fileTargetName
        if (sid.isEmpty()) return
        var name = "file"
        var size = -1L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    val si = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {}
        if (size > fileLimit) {
            showTextStatus(tr("«$name» больше 20 МБ — релей его не примет", "“$name” is over 20 MB — the relay won't accept it"), cPlay)
            return
        }
        showTextStatus(tr("отправляю «$name» в «$chName»…", "sending “$name” to “$chName”…"), cFaint)
        Thread {
            val bytes = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(65536)
                    while (out.size() <= fileLimit) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
            } catch (_: Exception) { null }
            if (bytes == null) { main.post { showTextStatus(tr("не смог прочитать «$name»", "couldn't read “$name”"), cPlay) }; return@Thread }
            if (bytes.size > fileLimit) {
                main.post { showTextStatus(tr("«$name» больше 20 МБ — релей его не примет", "“$name” is over 20 MB — the relay won't accept it"), cPlay) }
                return@Thread
            }
            val nameb = android.util.Base64.encodeToString(name.toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
            var result = ""
            try {
                val client = Net.client(Cfg.pin).newBuilder()
                    .callTimeout(180, java.util.concurrent.TimeUnit.SECONDS).build()
                val req = Request.Builder()
                    .url("${Cfg.url}/hubfile?token=${Cfg.token}&session=${Uri.encode(sid)}&nameb=$nameb")
                    .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
                    .build()
                client.newCall(req).execute().use { r -> if (!r.isSuccessful) result = tr("релей ответил ${r.code}", "relay replied ${r.code}") }
            } catch (e: Exception) {
                result = tr("нет связи", "offline")
            }
            main.post {
                if (result.isEmpty()) {
                    showTextStatus(tr("«$name» ушёл в «$chName»: хаб скажет «файл получил»", "“$name” sent to “$chName”: the hub will say “file received”"), cFaint)
                    LogBus.add(tr("файл ушёл в «$chName»: $name, ${bytes.size / 1024} КБ", "file sent to “$chName”: $name, ${bytes.size / 1024} KB"))
                } else {
                    showTextStatus(tr("«$name» не ушёл: $result", "“$name” not sent: $result"), cPlay)
                    LogBus.add(tr("файл НЕ ушёл в «$chName»: $result", "file NOT sent to “$chName”: $result"))
                }
            }
        }.start()
    }

    private fun showTextStatus(msg: String, color: Int) {
        textStatus.text = msg
        textStatus.setTextColor(color)
        textStatus.visibility = View.VISIBLE
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(textEdit.windowToken, 0)
        textEdit.clearFocus()
    }

    // ── тангента ─────────────────────────────────────────────────────────
    private fun circleTap() {
        if (Cfg.token.isEmpty()) { onboardOverlay.visibility = View.VISIBLE; return }
        // полудуплекс на экране: пока открыта клавиатура, круг микрофон не поднимает —
        // первый тап только убирает клавиатуру (набранное остаётся в поле)
        if (textEdit.hasFocus()) {
            hideKeyboard()
            LogBus.add(tr("круг: сначала убрал клавиатуру — набранное осталось в поле", "circle: hid the keyboard first — typed text stayed in the field"))
            return
        }
        LogBus.add(tr("нажат круг", "circle pressed"))
        val wasRunning = BridgeService.running
        ensureService { svc ->
            val bound = svc.boundaryReplySid()
            when {
                Cfg.voiceOff && wasRunning -> svc.toggleVoiceOff()   // v1.28: без голоса тап по кругу возвращает голос
                Cfg.voiceOff -> {}   // рация только что включилась в тихом режиме — голос не трогаем
                svc.isListening() -> svc.sendNow()
                // зазор между каналами: тап = отвечаю тому, кто только что договорил
                bound.isNotEmpty() -> svc.replyToSession(bound)
                // v0.71 (слово юзера с поля): во время озвучки круг = ПАУЗА и продолжение
                // с того же места. «Прервать и говорить» — отдельная кнопка под кругом:
                // обрыв ещё и выбрасывал очередь накопленных ответов. Озвучка проверяется
                // РАНЬШЕ dictHold — иначе тап при заглушённом микрофоне был немым (ревью v0.69).
                // isPaused СТРОГО раньше isPlaying: пауза = заглушение (v0.15), плеер в ней
                // крутится и isPlaying остаётся true — обратный порядок хоронил «дальше»
                svc.isPaused() -> svc.resumePlayback()
                svc.isPlaying() -> svc.pausePlayback()
                svc.hasPendingDictation() -> svc.resumeDictation()
                else -> svc.talkNow()
            }
        }
    }

    private fun powerToggle() {
        if (BridgeService.running) {
            LogBus.add(tr("долгий тап — выключаю рацию", "long tap — turning the radio off"))
            stopService(Intent(this, BridgeService::class.java))
        } else {
            LogBus.add(tr("долгий тап — включаю рацию", "long tap — turning the radio on"))
            ensureService { }
        }
    }

    private fun ensureService(then: (BridgeService) -> Unit) {
        val svc = BridgeService.instance
        if (svc != null && BridgeService.running) { then(svc); return }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            LogBus.add(tr("нет разрешения на микрофон — выдай его в окне запроса", "no microphone permission — grant it in the prompt"))
            askPermissions()
            return
        }
        LogBus.add(tr("рация была выключена — запускаю", "radio was off — starting"))
        startForegroundService(Intent(this, BridgeService::class.java))
        main.postDelayed({ BridgeService.instance?.let(then) }, 1200)
    }

    // ── тикер: круг, чип, подсказка, док, плеер ──────────────────────────
    private val ticker = object : Runnable {
        override fun run() {
            paint()
            if (System.currentTimeMillis() - lastFeedFetch > 4000) refreshFeed(false)
            // v0.79: открытый список каналов живёт сам — состояние «работает / ответила»
            // должно меняться на глазах, без закрыть-открыть
            if (sheetOverlay.visibility == View.VISIBLE && sheetMode == "channels" &&
                System.currentTimeMillis() - lastSheetTick > 5000
            ) {
                lastSheetTick = System.currentTimeMillis()
                rebuildSheet()
                if (System.currentTimeMillis() - lastSessionsFetch > 15000) fetchSessions()
            }
            // экран «Модели» обновляется сам: хаб шлёт состояние раз в минуту и после задачи
            if (sheetOverlay.visibility == View.VISIBLE && sheetMode == "models" &&
                System.currentTimeMillis() - lastModelsFetch > 20000
            ) fetchModels()
            // «сессии хаба»: пока ждём итог команды — часто, иначе как модели
            if (sheetOverlay.visibility == View.VISIBLE && (sheetMode == "hubch" || sheetMode == "conn" ||
                    sheetMode == "money") &&
                System.currentTimeMillis() - lastModelsFetch >
                (if ((hubReq.isNotEmpty() && !hubReqDone) || hubLoginActive()) 2000 else 20000)
            ) fetchModels()
            main.postDelayed(this, 500)
        }
    }

    private fun paint() {
        val svc = BridgeService.instance
        val running = svc != null && BridgeService.running
        val st = if (!running) "" else svc!!.statusText()

        // канал-чип
        val name = if (running) svc!!.replyTargetName() else feedName()
        chipBtn.text = "▾ " + name + (if (Cfg.pickedSids.isNotEmpty()) "  ·  " + Cfg.pickedSids.split(",").count { it.isNotBlank() } + tr(" в работе", " active") else "")

        // микрофон-мьют в шапке
        val muted = running && svc!!.isMicMuted()
        muteBtn.setTextColor(if (muted) cPlay else cFaint)
        muteBtn.text = if (muted) tr("🔇 выкл", "🔇 off") else "🔇"
        voiceBtn.setTextColor(if (Cfg.voiceOff) cPlay else cFaint)
        voiceBtn.text = if (Cfg.voiceOff) tr("🔈 выкл", "🔈 off") else "🔈"

        // круг
        val draft = st.startsWith(tr("СЛУШАЮ · есть черновик", "LISTENING · draft in progress"))
        val playingWho = st.substringAfter(tr("ИГРАЮ · ", "PLAYING · "), "").ifEmpty { tr("ответ", "reply") }
        // Порядок веток = порядок правды: озвучка ВСЕГДА раньше состояний микрофона,
        // иначе круг маскирует играющий ответ (ревью v0.69). Пауза/играю — по геттерам,
        // а не префиксам: «ПАУЗА ДИКТОВКИ» тоже начинается с «ПАУЗА».
        // зазор показываем, только пока микрофон опущен — иначе подпись «ответить» спорила бы
        // с тапом, который при поднятом микрофоне отправляет черновик (ревью v0.75)
        val boundSid = if (running && !svc!!.isListening()) svc.boundaryReplySid() else ""
        val key: String
        val inner: Int; val ring: Int; val fg: Int; val big: String; val sub: String
        when {
            !running -> { key = "off"; inner = cMic; ring = 0x14FFFFFF; fg = cDim
                big = tr("выключена", "off"); sub = tr("тап — включить", "tap — turn on") }
            Cfg.voiceOff -> { key = "voiceoff"; inner = cMic; ring = 0x14FFFFFF; fg = cDim
                big = tr("без голоса", "voice off"); sub = if (st.startsWith(tr("НЕТ СВЯЗИ", "OFFLINE"))) tr("нет связи · переподключаюсь", "offline · reconnecting") else tr("тап — включить голос", "tap — turn voice on") }
            // v0.75: зазор между каналами — круг предлагает ответить ТОМУ, кто только что
            // договорил, пока следующий канал ждёт своей очереди
            boundSid.isNotEmpty() -> { key = "bound$boundSid"; inner = cPlay; ring = cAccSoft; fg = cBg
                big = tr("ответить", "reply"); sub = svc!!.lastSpokenName() }
            st.startsWith(tr("НЕТ СВЯЗИ", "OFFLINE")) -> { key = "nolink"; inner = cWarn; ring = 0x14FFFFFF; fg = cText
                big = tr("нет связи", "offline"); sub = tr("переподключаюсь…", "reconnecting…") }
            svc!!.isPaused() -> { key = "paused"; inner = cWarn; ring = 0x14FFFFFF; fg = cText
                big = tr("пауза", "paused"); sub = tr("тап — дальше", "tap — continue") }
            svc.isPlaying() -> { key = "play$playingWho"; inner = cLive; ring = cAccSoft; fg = cBg
                big = tr("играю", "playing"); sub = playingWho }
            draft -> { key = "draft"; inner = cPlay; ring = cAccSoft; fg = cBg
                big = tr("слушаю", "listening"); sub = tr("тап — отправить", "tap — send") }
            st.startsWith(tr("СЛУШАЮ", "LISTENING")) -> { key = "listen"; inner = cPlay; ring = cAccSoft; fg = cBg
                big = tr("слушаю", "listening"); sub = tr("говори", "speak") }
            // dictHold: и пауза диктовки, и «не слушать» — движок их не различает,
            // подпись честно-нейтральная (черновик, если был, цел)
            svc.hasPendingDictation() -> { key = "micoff"; inner = cMic; ring = cAccSoft; fg = cText
                big = tr("микрофон спит", "mic asleep"); sub = tr("тап — включить", "tap — turn on") }
            st.startsWith(tr("ОТПРАВЛЕНО", "SENT")) -> { key = "sent"; inner = cCard; ring = cAccSoft; fg = cText
                big = tr("ушло ✓", "sent ✓"); sub = tr("тап — сказать ещё", "tap — say more") }
            st.startsWith(tr("ЖДУ НАУШНИКИ", "WAITING FOR HEADPHONES")) -> { key = "waitHs"; inner = cWarn; ring = 0x14FFFFFF; fg = cText
                big = tr("жду наушники", "waiting for headphones"); sub = tr("ответы копятся", "replies pile up") }
            else -> { key = "idle"; inner = cCard; ring = 0x14FFFFFF; fg = cText
                big = tr("на связи", "online"); sub = tr("тап — говорить", "tap — talk") }
        }
        if (key != lastCircleKey) {
            lastCircleKey = key
            circle.background = circleBg(inner, ring)
            circleBig.setTextColor(fg)
            circleSub.setTextColor(fg)
            circleBig.text = big
            circleSub.text = sub
        }

        // подсказка + отмена
        hintText.text = when {
            !running -> tr("долгий тап по кругу — вкл и выкл рации", "long tap on the circle — radio on and off")
            Cfg.token.isEmpty() -> tr("рация не настроена — открой ⚙", "radio not set up — open ⚙")
            !svc!!.isListening() && svc.boundaryReplySid().isNotEmpty() ->
                tr("12с на ответ · дальше «", "12s to reply · next “") + svc.boundaryNextName() + tr("» · тап — ответить", "” · tap — reply")
            svc.isPaused() -> tr("тап по кругу — продолжить с того же места", "tap the circle — continue from the same spot")
            svc.isPlaying() -> tr("тап по кругу — пауза с этого места", "tap the circle — pause here")
            st.startsWith(tr("СЛУШАЮ", "LISTENING")) && Cfg.autoSend -> tr("замолчи или скажи «отправляй» — уйдёт само", "stop talking or say “send” — it goes by itself")
            st.startsWith(tr("СЛУШАЮ", "LISTENING")) -> tr("скажи «отправляй» — таймеров тишины нет", "say “send” — no silence timers")
            st.startsWith(tr("ЖДУ НАУШНИКИ", "WAITING FOR HEADPHONES")) -> tr("надень наушники или выключи «только в наушниках»", "put on headphones or turn off “headphones only”")
            st.startsWith(tr("ОТПРАВЛЕНО", "SENT")) -> tr("микрофон спит — кнопка гарнитуры тоже будит", "mic asleep — the headset button wakes it too")
            else -> tr("долгий тап — выключить рацию", "long tap — turn the radio off")
        }
        cancelBtn.visibility =
            if (running && (svc!!.isListening() || svc.hasPendingDictation())) View.VISIBLE else View.GONE
        talkBtn.visibility =
            if (running && (svc!!.isPlaying() || svc.isPaused())) View.VISIBLE else View.GONE

        // плашка «есть ответы»
        val held = if (running) svc!!.heldCount() else 0
        when {
            held > 0 -> {
                bannerBtn.text = tr("есть ответы ($held) — послушать ▶", "replies waiting ($held) — listen ▶")
                bannerBtn.background = rounded(cPlay, 12)
                bannerBtn.setTextColor(cBg)
                bannerBtn.visibility = View.VISIBLE
            }
            else -> bannerBtn.visibility = View.GONE
        }

        // док: подпись строго согласована с действием клика (ревью v0.69 — «▶ дальше»
        // при играющей озвучке делала паузу); в покое кнопка честно становится повтором
        dockPause.text = when {
            !running -> tr("⏸ пауза", "⏸ pause")
            svc!!.isPaused() || (svc.hasPendingDictation() && !svc.isPlaying()) -> tr("▶ дальше", "▶ resume")
            svc.isPlaying() || svc.isListening() -> tr("⏸ пауза", "⏸ pause")
            else -> tr("↻ повтор", "↻ replay")
        }

        // плеер
        val showStrip = running && (svc!!.isPlaying() || svc.isPaused())
        playerStrip.visibility = if (showStrip) View.VISIBLE else View.GONE
        if (showStrip) {
            val pos = svc!!.positionMs()
            val dur = svc.durationMs()
            if (dur > 0 && !userScrubbing) {
                scrubBar.progress = (pos.toFloat() / dur * 1000).toInt().coerceIn(0, 1000)
            }
            playerLabel.text = (if (svc.isPaused()) tr("пауза · ", "paused · ") else tr("играю · ", "playing · ")) + fmt(pos) + " / " + fmt(dur)
            val cur = "%.1f×".format(Cfg.speed)
            if (speedLabel.text != cur) speedLabel.text = cur
        }

        // отладка, если открыта
        if (debugOverlay.visibility == View.VISIBLE) {
            debugTech.text = tr("состояние: ", "state: ") + (if (running) st else tr("СЕРВИС ВЫКЛЮЧЕН", "SERVICE OFF")) +
                tr("\nадресат: ", "\ntarget: ") + (if (running) svc!!.replyTargetName() else "—") +
                (if (Cfg.pickedSids.isNotEmpty()) tr(" · в работе: ", " · active: ") + (svc?.pickedNames() ?: "") else "") +
                "\nclaudio code " + BridgeService.APP_VERSION + " · " + BuildConfig.FLAVOR
        }
    }

    // ── лента-чат ────────────────────────────────────────────────────────
    private data class InMsg(
        val msgid: String, var ts: Long, var proj: String, var ctx: String,
        val texts: ArrayList<Pair<Int, String>>, val audios: ArrayList<Pair<Int, String>>, var bytes: Long
    )

    private data class Row(
        val out: Boolean, val text: String, val ts: Long, val note: String,
        val inMsg: InMsg?, val key: String
    )

    // Чей разговор показываем: solo → закреплённый адресат → последняя ИГРАВШАЯ.
    // Только sid, никогда имя: у юзера документированно живут четыре «playbook» разом,
    // обратный поиск по имени показывал бы чужой разговор (ревью v0.69).
    private fun currentSid(): String {
        // v0.85 (боевое: «отправил в voicebridge, а в ленте не появилось»): адресат ВСЕГДА
        // главнее набора. Набор — про то, кого слышу; лента должна показывать тот разговор,
        // в который реально уходит твоя реплика, иначе пузырь «ушло» падает в чужую ленту.
        val svc = BridgeService.instance
        if (svc != null && BridgeService.running) {
            val s = svc.replyTargetSid()
            if (s.isNotEmpty()) return s
        }
        // v1.14: канал, убранный из списка руками, не должен всплывать в шапке — а он всплывал
        // на каждом запуске приложения, потому что оставался адресатом или «последним игравшим».
        fun ok(s: String) = s.isNotEmpty() && !SessionBook.isForgotten(this, s)
        if (ok(Cfg.replyTarget)) return Cfg.replyTarget
        Cfg.pickedSids.split(",").map { it.trim() }.firstOrNull { ok(it) }?.let { return it }
        // сервис выключен: движок персистит lastSession в prefs при каждом воспроизведении
        val last = getSharedPreferences("bridge", MODE_PRIVATE).getString("lastSession", "") ?: ""
        if (ok(last)) return last
        return SessionBook.all().filter { ok(it.sid) }.maxByOrNull { it.lastTs }?.sid.orEmpty()
    }

    private fun feedName(): String {
        val sid = feedSid.ifEmpty { currentSid() }
        if (sid.isEmpty()) return "—"
        return SessionBook.all().firstOrNull { it.sid == sid }?.proj?.ifEmpty { sid.take(8) } ?: sid.take(8)
    }

    private fun refreshFeed(force: Boolean) {
        val sid = currentSid()
        if (sid != feedSid) {
            feedSid = sid; lastFeedSig = "#init"; feedStick = true
            pendingRows = null; lastMsgs = emptyList(); feedExpanded.clear()
        }
        if (sid.isEmpty() || Cfg.token.isEmpty() || Cfg.url.isEmpty()) {
            renderFeed(emptyList(), offline = false)
            return
        }
        if (feedBusy) {
            // смена канала/эхо в момент полёта старого запроса — не глотать, а догнать
            if (force) feedForcePending = true
            return
        }
        if (!force && System.currentTimeMillis() - lastFeedFetch < 4000) return
        feedBusy = true
        lastFeedFetch = System.currentTimeMillis()
        Thread {
            val raw = Net.get(Cfg.pin, "${Cfg.url}/history?token=${Cfg.token}&session=$sid&limit=80")
            val msgs = parseHistory(raw)
            // v0.73: релей держит историю в ОЗУ и теряет её при рестарте — всё увиденное
            // складываем на телефон и склеиваем по msgid. Запись/чтение prefs и склейка —
            // ЗДЕСЬ, в фоне: на главном потоке это мегабайты JSON и путь к ANR.
            val merged = if (raw == null) null else {
                Feed.rememberIn(this, sid, msgs.map {
                    Feed.In(it.msgid, it.ts, it.proj,
                        it.texts.map { t -> t.second }.filter { t -> t.isNotBlank() }.joinToString(" ").trim().take(900),
                        it.audios.filter { a -> a.second.isNotEmpty() }, it.bytes)
                })
                mergeCached(sid, msgs)
            }
            main.post {
                feedBusy = false
                val stale = sid != feedSid
                if (!stale) {
                    if (merged == null) {
                        // сеть моргнула (штатно на самокате) — старые пузыри НЕ стираем
                        renderFeed(buildRows(sid, lastMsgs), offline = true)
                    } else {
                        lastMsgs = merged
                        renderFeed(buildRows(sid, merged), offline = false)
                    }
                }
                if (feedForcePending || stale) {
                    feedForcePending = false
                    lastFeedFetch = 0
                    refreshFeed(true)
                }
            }
        }.start()
    }

    // Свежая история релея + локальная память: у релея приоритет (там полные части),
    // кэш добавляет всё, что релей уже забыл при рестарте или вытеснил своим капом.
    private fun mergeCached(sid: String, fresh: List<InMsg>): List<InMsg> {
        val byId = LinkedHashMap<String, InMsg>()
        for (c in Feed.inFor(this, sid)) {
            byId[c.msgid] = InMsg(
                c.msgid, c.ts, c.proj, "",
                arrayListOf(Pair(1, c.text)), ArrayList(c.audios), c.bytes
            )
        }
        // свежая версия побеждает, ТОЛЬКО если она не беднее: релей вытесняет старые части
        // своим капом, и огрызок из двух частей не должен затирать полное сообщение из кэша
        for (m in fresh) {
            val old = byId[m.msgid]
            val freshLen = m.texts.sumOf { it.second.length }
            val oldLen = old?.texts?.sumOf { it.second.length } ?: -1
            if (old == null || m.audios.size >= old.audios.size || freshLen >= oldLen) byId[m.msgid] = m
        }
        return byId.values.sortedBy { it.ts }
    }

    private fun parseHistory(raw: String?): List<InMsg> {
        val out = ArrayList<InMsg>()
        if (raw == null) return out
        try {
            val evs = JSONObject(raw).optJSONArray("events") ?: return out
            val byId = LinkedHashMap<String, InMsg>()
            for (i in 0 until evs.length()) {
                val ev = evs.getJSONObject(i)
                val id = ev.optString("msgid", ev.optString("ts"))
                val m = byId.getOrPut(id) { InMsg(id, 0L, "", "", ArrayList(), ArrayList(), 0L) }
                val ts = ev.optLong("ts")
                if (m.ts == 0L || ts < m.ts) m.ts = ts
                if (m.proj.isEmpty()) m.proj = ev.optString("proj")
                val cx = ev.optString("ctx")
                if (cx.isNotEmpty() && m.ctx.isEmpty()) m.ctx = cx
                m.texts.add(Pair(ev.optInt("part", 1), ev.optString("text")))
                m.audios.add(Pair(ev.optInt("part", 1), ev.optString("audio")))
                m.bytes += ev.optLong("bytes")
            }
            for (m in byId.values) {
                m.texts.sortBy { it.first }
                m.audios.sortBy { it.first }
                out.add(m)
            }
        } catch (_: Exception) {}
        return out
    }

    // Пузыри: входящие — текст частей из /history; исходящие — локальное эхо «ушло»
    // (Feed) плюс ctx ответа (реплика юзера, которую сессия процитировала) как запасной
    // источник для сообщений, отправленных до v0.69 или с другого телефона.
    private fun buildRows(sid: String, msgs: List<InMsg>): List<Row> {
        fun norm(s: String) = s.lowercase().replace(Regex("[^а-яёa-z0-9]+"), " ").trim().take(48)
        val rows = ArrayList<Row>()
        val outs = Feed.outFor(this, sid)
        // ctx ответа ↔ локальное эхо: совпал норм-ключ — реплика юзера ДОКАЗАННО дошла.
        // Такое эхо встаёт прямо перед своим ответом с СЕРВЕРНЫМ временем (часы телефона
        // документированно врали на полчаса — v0.36) и гасит застывшее «канал не слушал».
        // на один ключ может быть НЕСКОЛЬКО ответов (юзер дважды сказал одно и то же):
        // владельцы лежат очередью, каждое эхо забирает следующего по порядку
        val ctxOwner = HashMap<String, ArrayDeque<InMsg>>()
        for (m in msgs) {
            val k = norm(m.ctx)
            if (k.isNotEmpty()) ctxOwner.getOrPut(k) { ArrayDeque() }.addLast(m)
        }
        val echoKeys = HashSet<String>()
        for (e in outs) norm(e.text).let { if (it.isNotEmpty()) echoKeys.add(it) }
        var prevCtx = ""
        for (m in msgs) {
            val k = norm(m.ctx)
            if (k.isNotEmpty()) {
                // ctx-пузырь — запасной источник (нет эха: старое сообщение, другой телефон).
                // Дедупим только СОСЕДНИЕ ответы с тем же ctx; повтор реплики позже легитимен.
                // Системные ходы (<task-notification>…, [SYSTEM…) — не речь юзера, не рисуем.
                val system = m.ctx.startsWith("<") || m.ctx.startsWith("[SYSTEM")
                if (!system && k !in echoKeys && k != prevCtx) {
                    rows.add(Row(true, m.ctx, m.ts - 1, tr("ушло ✓", "sent ✓"), null, "c${m.msgid}"))
                }
                prevCtx = k
            } else {
                prevCtx = ""   // ответ без ctx разрывает «соседство» — повтор после него легитимен
            }
            val txt = m.texts.map { it.second }.filter { it.isNotBlank() }.joinToString(" ").trim()
            val secs = (m.bytes / 2500).coerceAtLeast(1)
            val durTxt = if (secs >= 60) "%d:%02d".format(secs / 60, secs % 60) else tr("${secs}с", "${secs}s")
            rows.add(Row(false, txt.ifEmpty { tr("(голосовое, ~$durTxt)", "(voice message, ~$durTxt)") }, m.ts, "", m, "i${m.msgid}"))
        }
        for (e in outs) {
            // relay режет текст utt_sent (с v0.71 — до 1000) — обрез не должен выглядеть потерей
            val text = if (e.text.length >= 1000) e.text + "…" else e.text
            val key = "o${e.ts}:${e.text.hashCode()}"
            val owner = ctxOwner[norm(e.text)]?.removeFirstOrNull()
            if (owner != null) {
                rows.add(Row(true, text, owner.ts - 1, tr("ушло ✓", "sent ✓"), null, key))
            } else {
                rows.add(Row(true, text, e.ts, if (e.unread) tr("⚠ канал не слушал", "⚠ channel not listening") else tr("ушло ✓", "sent ✓"), null, key))
            }
        }
        rows.sortBy { it.ts }
        return if (rows.size > 120) rows.subList(rows.size - 120, rows.size) else rows
    }

    // окно с текстом сообщения: выдели нужный кусок пальцем (системное «копировать»)
    // или скопируй всё кнопкой; пузырь при этом не теряет своих нажатий
    private fun copyDialog(text: String) {
        val body = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(cText)
            setTextIsSelectable(true)
            setPadding(dp(18), dp(14), dp(18), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(body) }
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(scroll)
            .setPositiveButton(tr("копировать всё", "copy all")) { _, _ -> copyText(text, window.decorView) }
            .setNegativeButton(tr("закрыть", "close"), null)
            .create()
        dlg.show()
    }

    private fun copyText(text: String, from: View) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(tr("сообщение", "message"), text))
            from.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            android.widget.Toast.makeText(this, tr("текст скопирован", "text copied"), android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, tr("не удалось скопировать", "could not copy"), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderFeed(rows: List<Row>, offline: Boolean, force: Boolean = false) {
        lastRows = rows
        lastOffline = offline
        val sig = rows.joinToString("|") { "${it.ts}:${it.out}:${it.text.hashCode()}:${it.note}" } +
            (if (offline) "#off" else "") + (if (feedExpandAll) "#xall" else "")
        if (!force && sig == lastFeedSig) return
        // юзер отлистал вверх читать старое — не дёргать ленту под пальцем; перерисуем,
        // когда он вернётся вниз (см. слушатель прокрутки). force = явное действие юзера.
        // v1.17: юзер выделяет кусок текста — перерисовка сняла бы выделение под пальцем
        if (!force && (!feedStick || feedBodies.any { it.hasSelection() }) && feedBox.childCount > 0) {
            pendingRows = rows; pendingOffline = offline
            return
        }
        pendingRows = null
        lastFeedSig = sig
        feedBox.removeAllViews()
        feedBodies.clear()
        if (rows.isEmpty()) {
            val msg = when {
                Cfg.token.isEmpty() -> tr("рация не настроена.\nоткрой первый запуск (⚙) или отсканируй QR со своего сервера", "radio not set up.\nopen first run (⚙) or scan the QR from your server")
                offline -> tr("нет связи с релеем — лента появится, когда связь вернётся", "no connection to the relay — the feed will appear when it returns")
                else -> tr("пока тихо.\nнажми круг и скажи что-нибудь — разговор появится здесь", "quiet so far.\ntap the circle and say something — the conversation will show up here")
            }
            feedBox.addView(tv(msg, 12f, cFaint).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(40), dp(16), dp(16))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return
        }
        val maxW = (resources.displayMetrics.widthPixels * 0.80f).toInt()
        var lastDay = ""
        // v0.71 (слово юзера): по умолчанию каждый пузырь свёрнут до 3 строк; тап по пузырю
        // раскрывает/сворачивает его, кнопка ⤢ в шапке — всю переписку разом.
        fun applyExpand(b: TextView, ex: Boolean) {
            b.maxLines = if (ex) Int.MAX_VALUE else 3
            b.ellipsize = if (ex) null else android.text.TextUtils.TruncateAt.END
            // v1.17 (слово юзера: копировать часть сообщения): раскрытый текст выделяется
            // долгим нажатием; свёрнутый не ловит касания — тап и долгий уходят пузырю
            b.setTextIsSelectable(ex)
        }
        for (r in rows) {
            val day = dayKey(r.ts)
            if (day != lastDay) {
                lastDay = day
                feedBox.addView(tv(dayLabel(r.ts), 10.5f, cFaint).apply {
                    gravity = Gravity.CENTER
                    background = rounded(cCard, 10)
                    setPadding(dp(12), dp(4), dp(12), dp(4))
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(16)
                    bottomMargin = dp(4)
                })
            }
            val bub = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(if (r.out) cAccSoft else cCard, 14)
                setPadding(dp(11), dp(7), dp(11), dp(8))
            }
            val who = if (r.out) tr("ты · ${hhmm(r.ts)}", "you · ${hhmm(r.ts)}") + (if (r.note.isNotEmpty()) " · ${r.note}" else "")
            else tr("${r.inMsg?.proj?.ifEmpty { "канал" } ?: "канал"} · ${hhmm(r.ts)}", "${r.inMsg?.proj?.ifEmpty { "channel" } ?: "channel"} · ${hhmm(r.ts)}")
            val capRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val cap = tv(who, 9.5f, if (r.note.startsWith("⚠")) cWarn else cFaint).apply {
                maxWidth = maxW
                if (r.out) gravity = Gravity.END
            }
            capRow.addView(cap, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            if (!r.out && r.inMsg != null) {
                val m = r.inMsg
                capRow.addView(tv("▶", 11f, cPlay).apply {
                    setPadding(dp(10), 0, dp(2), 0)
                    contentDescription = tr("послушать это сообщение", "listen to this message")
                    setOnClickListener { playMsg(m, fromHere = false) }
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }
            // копирование отдельной кнопкой: долгое нажатие на входящем занято «отсюда и дальше»
            capRow.addView(tv("⧉", 17f, cDim).apply {
                setPadding(dp(14), 0, dp(8), 0)
                contentDescription = tr("копировать текст сообщения", "copy message text")
                setOnClickListener { copyDialog(r.text) }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            val body = tv(r.text, 13f, cText).apply {
                maxWidth = maxW
                setPadding(0, dp(2), 0, 0)
            }
            val toggle = View.OnClickListener {
                if (!feedExpanded.add(r.key)) feedExpanded.remove(r.key)
                applyExpand(body, feedExpandAll != (r.key in feedExpanded))
            }
            body.setOnClickListener(toggle)   // before applyExpand: setOnClickListener forces clickable
            applyExpand(body, feedExpandAll != (r.key in feedExpanded))
            feedBodies.add(body)
            bub.addView(capRow)
            bub.addView(body)
            bub.setOnClickListener(toggle)
            if (!r.out && r.inMsg != null) {
                val m = r.inMsg
                // long press: a choice, so copying is reachable without hunting for the small ⧉
                bub.setOnLongClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setItems(arrayOf(tr("копировать текст", "copy text"), tr("слушать отсюда и дальше", "listen from here on"))) { _, which ->
                            if (which == 0) copyDialog(r.text) else playMsg(m, fromHere = true)
                        }
                        .show()
                    true
                }
            } else {
                // у своих сообщений долгое нажатие свободно: копирует
                bub.setOnLongClickListener { copyDialog(r.text); true }
            }
            feedBox.addView(bub, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = if (r.out) Gravity.END else Gravity.START
                topMargin = dp(6)
            })
        }
        feedBox.addView(tv(tr("тап — раскрыть/свернуть · ▶ — послушать · долгий — отсюда и дальше · в раскрытом долгий по тексту — выделить", "tap — expand/collapse · ▶ — listen · long press — from here on · when expanded, long press on text — select"), 9.5f, cFaint).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        if (feedStick) feedScroll.post { feedScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun playMsg(m: InMsg, fromHere: Boolean) {
        val sid = feedSid.ifEmpty { currentSid() }
        if (sid.isEmpty()) return
        val proj = m.proj
        val chosen = if (fromHere) lastMsgs.filter { it.ts >= m.ts }.sortedBy { it.ts } else listOf(m)
        val items = chosen.flatMap { mm -> mm.audios.map { Pair(it.second, mm.ts) } }
            .filter { it.first.isNotEmpty() }
        if (items.isEmpty()) { LogBus.add(tr("у сообщения нет аудио", "this message has no audio")); return }
        LogBus.add(if (fromHere) tr("лента: играю с выбранного места (${items.size} ч.)", "feed: playing from the chosen spot (${items.size} parts)") else tr("лента: играю сообщение", "feed: playing the message"))
        // явное «слушать» с экрана: микрофон вниз, иначе этикет отложит озвучку (v0.73)
        ensureService { it.listenIntent(); it.playFrom(sid, proj, items) }
    }

    // ── шторка «каналы» ──────────────────────────────────────────────────
    private fun buildSheetOverlay() {
        sheetList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // v0.86.1 (боевое: «список развернулся на весь экран — как его свернуть?»):
        // явная кнопка «закрыть» в шапке + потолок высоты, чтобы затемнение сверху
        // оставалось видимым и тап по нему по-прежнему закрывал шторку.
        val closeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // v1.10 (скрин юзера: три кнопки не влезали в ширину и «закрыть» обрезалось
            // до «зак»). Ряд теперь делится по весу: каждая кнопка получает свою треть,
            // подписи короче, шрифт и отступы мельче. Распорка убрана — она отбирала
            // ширину у самих кнопок.
            fun sheetBtn(label: String, weight: Float, onTap: () -> Unit) =
                tv(label, 11.5f, cDim).apply {
                    background = bordered(10)
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    gravity = Gravity.CENTER
                    maxLines = 1
                    val lp = LinearLayout.LayoutParams(0, WRAP_CONTENT, weight)
                    lp.leftMargin = dp(5)
                    lp.rightMargin = dp(5)
                    layoutParams = lp
                    setOnClickListener { onTap() }
                }
            // v1.09 (слово юзера: «вернуть нужны не все, а конкретные — галочками»)
            addView(sheetBtn(tr("✎ править", "✎ edit"), 1.1f) {
                sheetEditMode = !sheetEditMode
                if (sheetEditMode) { lastSessionsFetch = 0L; fetchSessions() }
                rebuildSheet()
            }.apply { contentDescription = tr("выбрать галочками, какие каналы держать в списке", "tick which channels to keep in the list") })
            // v1.03 (слово юзера: «не вижу кнопку обновления списка каналов»)
            addView(sheetBtn(tr("↻ обновить", "↻ refresh"), 1.15f) {
                lastSessionsFetch = 0L
                fetchSessions()
                LogBus.add(tr("список каналов обновляется с сервера", "channel list is refreshing from the server"))
            })
            addView(sheetBtn(tr("✕ закрыть", "✕ close"), 1.1f) { sheetOverlay.visibility = View.GONE })
        }
        val listScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            addView(sheetList, MATCH_PARENT, WRAP_CONTENT)
        }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cCard)
                cornerRadii = floatArrayOf(
                    dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f
                )
            }
            setPadding(dp(16), dp(10), dp(16), dp(22))
            addView(closeRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(listScroll, LinearLayout.LayoutParams(
                MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.62f).toInt()
            ))
            isClickable = true   // тапы по самой шторке не закрывают её
        }
        sheetOverlay = FrameLayout(this).apply {
            setBackgroundColor(0x8C000000.toInt())
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
            addView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }
        rootFrame.addView(sheetOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    // v0.71: разбивка накопленных ответов по каналам — послушать выбранный или пропустить.
    // Пропущенное не теряется: сообщения остаются в /history и видны в ленте канала.
    private fun openHeldSheet(br: List<Triple<String, String, Int>>) {
        sheetMode = "held"
        sheetList.removeAllViews()
        sheetList.addView(tv(tr("накопленные ответы", "saved replies"), 12f, cFaint))
        for ((sid, proj, n) in br) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(11), dp(4), dp(11))
            }
            row.addView(tv(tr("$proj · $n ч.", "$proj · $n parts"), 13f, cText).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(tv(tr("▶ слушать", "▶ listen"), 12f, cPlay).apply {
                setPadding(dp(8), dp(2), dp(8), dp(2))
                setOnClickListener {
                    BridgeService.instance?.playHeldFor(sid)
                    sheetOverlay.visibility = View.GONE
                }
            })
            row.addView(tv("🎙", 13f, cDim).apply {
                setPadding(dp(10), dp(2), dp(2), dp(2))
                contentDescription = tr("ответить в этот канал", "reply to this channel")
                setOnClickListener {
                    sheetOverlay.visibility = View.GONE
                    ensureService { it.replyToSession(sid) }
                }
            })
            row.addView(tv("✕", 14f, cFaint).apply {
                setPadding(dp(10), dp(2), dp(4), dp(2))
                contentDescription = tr("пропустить ответы этого канала", "skip this channel's replies")
                setOnClickListener {
                    BridgeService.instance?.dropHeldFor(sid)
                    val nb = BridgeService.instance?.heldBreakdown().orEmpty()
                    if (nb.isEmpty()) sheetOverlay.visibility = View.GONE else openHeldSheet(nb)
                }
            })
            sheetList.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            sheetList.addView(View(this).apply { setBackgroundColor(cLine) },
                LinearLayout.LayoutParams(MATCH_PARENT, 1))
        }
        sheetList.addView(tv(tr("все подряд ▶", "play all ▶"), 12.5f, cDim).apply {
            setPadding(dp(4), dp(12), dp(4), dp(6))
            setOnClickListener {
                BridgeService.instance?.playHeldAll()
                sheetOverlay.visibility = View.GONE
            }
        })
        sheetList.addView(tv(tr("▶ слушать · 🎙 отвечать · ✕ пропустить (остаются в ленте)", "▶ listen · 🎙 reply · ✕ skip (they stay in the feed)"), 9.5f, cFaint).apply {
            setPadding(dp(4), dp(4), dp(4), 0)
        })
        sheetOverlay.visibility = View.VISIBLE
    }

    private fun openSheet() {
        sheetShowAll = false
        sheetMode = "channels"
        rebuildSheet()
        sheetOverlay.visibility = View.VISIBLE
        fetchSessions()
    }

    private fun fetchSessions() {
        if (Cfg.token.isEmpty() || Cfg.url.isEmpty()) return
        lastSessionsFetch = System.currentTimeMillis()
        // список освежается с релея в фоне — имена, время, кто жив
        Thread {
            val raw = Net.get(Cfg.pin, "${Cfg.url}/sessions?token=${Cfg.token}")
            val parsed = ArrayList<Triple<String, String, Long>>()
            val noHistory = HashSet<String>()
            try {
                if (raw != null) {
                    val arr = JSONObject(raw).optJSONArray("sessions")
                    if (arr != null) for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        if (o.optString("sid") == hubControlSid) continue
                        if (o.optInt("count") == 0) noHistory.add(o.getString("sid"))
                        parsed.add(Triple(o.getString("sid"), o.optString("proj"), o.optLong("lastTs")))
                        serverSids[o.getString("sid")] = o.optString("proj")
                        aliveMap[o.getString("sid")] = o.optBoolean("alive")
                    }
                }
            } catch (_: Exception) {}
            main.post {
                val inBook = SessionBook.all().map { it.sid }.toHashSet()
                for (t in parsed) {
                    // 15.10 (боевое: «убрал лишние — все вернулись»): у канала без единого сообщения
                    // (count 0: инвентарь хаба, Codex-каналы) релей ставит lastTs «сейчас» на каждой
                    // публикации, и удалённая строка воскресала через 15с. Такой канал возвращает
                    // только настоящее сообщение (событие voice) или галочка в «править».
                    if (t.first in noHistory && t.first !in inBook && SessionBook.isForgotten(this, t.first)) continue
                    SessionBook.seen(this, t.first, t.second, t.third)
                }
                // поздний ответ сети не смеет подменить шторку накопленного списком каналов
                if (sheetOverlay.visibility == View.VISIBLE && sheetMode == "channels") rebuildSheet()
            }
        }.start()
    }

    // Выход из solo: движковый clearSolo НЕ сбрасывает Cfg.replyTarget (v0.62), а прилипший
    // адресат — корень провального дня v0.41. Открепляем на стороне экрана.
    private fun exitSolo() {
        // clearSolo идемпотентен и заодно снимает поштучные мьюты — зовём безусловно,
        // иначе замьюченный ранее канал не разглушить ничем (ревью v0.82)
        BridgeService.instance?.clearSolo() ?: run {
            Cfg.pickedSids = ""; Cfg.soloSid = ""; Cfg.save(this)
            for (x in SessionBook.all()) if (x.muted) SessionBook.setMuted(this, x.sid, false)
        }
        Cfg.replyTarget = ""
        Cfg.save(this)
        LogBus.add(tr("адресат откреплён — отвечаю в последнюю игравшую", "target unpinned — replying to the last one that played"))
    }

    private fun rebuildSheet() {
        sheetList.removeAllViews()
        val pickedNow = BridgeService.instance?.pickedNames().orEmpty()
        sheetList.addView(tv(
            if (pickedNow.isEmpty()) tr("каналы · слышу все", "channels · hearing all") else tr("в работе: $pickedNow", "active: $pickedNow"),
            12f, if (pickedNow.isEmpty()) cPlay else cPlay
        ))
        // Hub screens need the owner's hub server, which is not public yet: hidden in the store build.
        if (!sheetEditMode && BuildConfig.FLAVOR != "store") {
            sheetList.addView(linkRow(tr("модели и лимиты", "models and limits"), tr("какие нейросети подключены к хабу, кто ведущий, лимиты", "which AIs are connected to the hub, who leads, limits")) {
                openModels()
            })
            sheetList.addView(linkRow(tr("сессии хаба", "hub sessions"), tr("каналы хаба: создать с проектом, удалить", "hub channels: create with a project, delete")) {
                openHubChannels()
            })
            sheetList.addView(linkRow(tr("подключения", "connections"), tr("ключи API и вход в подписки для хаба", "API keys and subscription sign-in for the hub")) {
                openConnections()
            })
            sheetList.addView(linkRow(tr("расходы", "costs"), tr("хватает ли связки подписок: деньги, лимиты, объём, качество", "is the subscription bundle enough: money, limits, volume, quality")) {
                openMoney()
            })
            sheetList.addView(divider(), LinearLayout.LayoutParams(MATCH_PARENT, 1))
        }
        val list = SessionBook.all()
        if (list.isEmpty()) {
            sheetList.addView(tv(tr("(ещё не было ни одной озвучки)", "(no voice yet)"), 12f, cFaint).apply {
                setPadding(0, dp(10), 0, dp(10))
            })
        }
        val prefs = getSharedPreferences("bridge", MODE_PRIVATE)

        // v1.09: режим правки — ВСЕ каналы, которые знает сервер, с галочками
        if (sheetEditMode) {
            sheetList.addView(tv(tr("отметь, какие каналы держать в списке", "tick which channels to keep in the list"), 11f, cFaint).apply {
                setPadding(dp(2), dp(6), dp(2), dp(8))
            })
            val known = LinkedHashMap<String, String>()
            for (e in list) known[e.sid] = e.proj.ifEmpty { e.sid.take(8) }
            for ((sid, nm) in serverSids) if (!known.containsKey(sid)) known[sid] = nm.ifEmpty { sid.take(8) }
            if (known.isEmpty()) {
                sheetList.addView(tv(tr("сервер пока не отдал ни одного канала", "the server has not returned any channels yet"), 12f, cFaint))
            }
            for ((sid, nm) in known) {
                val inList = list.any { it.sid == sid }
                val row = LinkedHashMap<String, String>()   // placeholder to keep style
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                }
                line.addView(tv(if (inList) "☑" else "☐", 17f, if (inList) cPlay else cFaint).apply {
                    setPadding(0, 0, dp(10), 0)
                })
                line.addView(tv(nm, 14f, if (inList) cText else cDim).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                line.setOnClickListener {
                    if (inList) {
                        if (BridgeService.instance?.picked()?.contains(sid) == true)
                            BridgeService.instance?.togglePicked(sid)
                        SessionBook.forget(this@MainActivity, sid)
                        LogBus.add(tr("канал убран из списка: $nm", "channel removed from list: $nm"))
                    } else {
                        SessionBook.unforget(this@MainActivity, sid)
                        SessionBook.seen(this@MainActivity, sid, nm, System.currentTimeMillis())
                        LogBus.add(tr("канал возвращён в список: $nm", "channel put back in list: $nm"))
                    }
                    rebuildSheet()
                }
                sheetList.addView(line, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                sheetList.addView(View(this).apply { setBackgroundColor(cLine) },
                    LinearLayout.LayoutParams(MATCH_PARENT, 1))
            }
            sheetList.addView(tv(tr("✓ готово", "✓ done"), 13f, cPlay).apply {
                background = bordered(10)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener { sheetEditMode = false; rebuildSheet() }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
            return
        }

        val shown = if (sheetShowAll) list else list.take(8)
        for (e in shown) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(11), dp(4), dp(11))
            }
            val solo = BridgeService.instance?.picked()?.contains(e.sid)
                ?: Cfg.pickedSids.split(",").contains(e.sid)
            val target = !solo && Cfg.replyTarget == e.sid
            val deaf = aliveMap[e.sid] == false
            val unheard = e.lastTs > prefs.getLong("heard_${e.sid}", 0L)
            // имя — главное на строке: крупнее, во всю ширину, метки минимальные
            val nm = tv(
                (if (unheard) "● " else "") + e.proj.ifEmpty { e.sid.take(8) },
                15f, if (unheard) cText else cDim
            ).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            // v0.79 (слово юзера: «хочу понимать — канал работает или уже отработал»).
            // Косвенно, но честно: спросил и ответа ещё нет — «работает N мин»; ответ
            // свежее вопроса — «ответила N мин назад». Обновляется тикером сама.
            val askTs = Feed.lastAskTs(this, e.sid)
            val working = askTs > e.lastTs
            val mins = ((System.currentTimeMillis() - maxOf(askTs, e.lastTs)) / 60000).toInt()
            val stTxt: String; val stCol: Int
            when {
                e.muted -> { stTxt = tr("выключен", "off"); stCol = cFaint }
                deaf -> { stTxt = tr("не слушает", "not listening"); stCol = cWarn }
                working -> { stTxt = tr("работает", "working") + (if (mins > 0) tr(" · $mins мин", " · $mins min") else ""); stCol = cPlay }
                else -> { stTxt = tr("ответил · ", "replied · ") + timeAgo(e.lastTs); stCol = cFaint }
            }
            // v0.82 (вопрос юзера: «объясни, что значат иконки»): подписи СЛОВАМИ.
            // Значки на ходу нечитаемы и требуют памяти — слово объясняет себя само.
            val replyBtn = tv(tr("ответить", "reply"), 11.5f, if (target) cPlay else cDim).apply {
                background = bordered(8)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                contentDescription = tr("отвечать в этот канал прямо сейчас", "reply to this channel right now")
                setOnClickListener {
                    sheetOverlay.visibility = View.GONE
                    ensureService { it.replyToSession(e.sid) }
                    refreshFeed(true)
                }
            }
            // v0.82 (слово юзера: «хочу выбрать пару каналов из пяти и ВИДЕТЬ, какие»).
            // Набор: пусто = слышу всех; иначе слышны ровно отмеченные.
            val soloBtn = tv(if (solo) tr("✓ в работе", "✓ active") else tr("в работу", "make active"), 11.5f, if (solo) cPlay else cDim).apply {
                background = if (solo) rounded(cAccSoft, 8) else bordered(8)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                contentDescription = tr("добавить канал в работу или убрать из неё", "add the channel to active or remove it")
                setOnClickListener {
                    val svc = BridgeService.instance
                    if (svc != null && BridgeService.running) svc.togglePicked(e.sid)
                    else {
                        val set = Cfg.pickedSids.split(",").filter { it.isNotEmpty() }.toMutableSet()
                        if (!set.remove(e.sid)) set.add(e.sid)
                        Cfg.pickedSids = set.joinToString(",")
                        Cfg.soloSid = if (set.size == 1) set.first() else ""
                        Cfg.save(this@MainActivity)
                    }
                    rebuildSheet()
                    refreshFeed(true)
                }
            }
            // v0.86 (боевое, обычный смартфон: «имя канала съедается кнопками, места нет»):
            // ДВЕ строки. Верхняя — имя во всю ширину и статус, нижняя — кнопки.
            val line1 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(nm, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(tv(stTxt, 10.5f, stCol), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    leftMargin = dp(8)
                })
            }
            val line2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
                addView(soloBtn)
                addView(replyBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
            }
            row.orientation = LinearLayout.VERTICAL
            row.addView(line1, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            row.addView(line2, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            // тап по строке = ответить в этот канал (на одно сообщение)
            row.setOnClickListener {
                run {
                    // v0.78: через защищённую точку — начатая диктовка уедет СТАРОМУ адресату
                    val svc = BridgeService.instance
                    if (svc != null && BridgeService.running) svc.setReplyTargetManual(e.sid)
                    else { Cfg.replyTarget = e.sid; Cfg.save(this@MainActivity) }
                    BridgeService.instance?.onReplyTargetPicked()   // штамп ручного выбора
                    LogBus.add(tr("отвечаю теперь в «${e.proj.ifEmpty { e.sid.take(8) }}»", "now replying in “${e.proj.ifEmpty { e.sid.take(8) }}”"))
                }
                sheetOverlay.visibility = View.GONE
                refreshFeed(true)
            }
            // v0.82 (слово юзера про тестовые каналы: «удаляй их, пусть не мозолят глаза»)
            row.setOnLongClickListener {
                if (BridgeService.instance?.picked()?.contains(e.sid) == true)
                    BridgeService.instance?.togglePicked(e.sid)
                SessionBook.forget(this, e.sid)
                LogBus.add(tr("канал убран из списка: ${e.proj.ifEmpty { e.sid.take(8) }}", "channel removed from list: ${e.proj.ifEmpty { e.sid.take(8) }}"))
                rebuildSheet()
                true
            }
            sheetList.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            sheetList.addView(View(this).apply { setBackgroundColor(cLine) },
                LinearLayout.LayoutParams(MATCH_PARENT, 1))
        }
        if (!sheetShowAll && list.size > 8) {
            sheetList.addView(tv(tr("ещё ${list.size - 8}…", "${list.size - 8} more…"), 12.5f, cDim).apply {
                setPadding(dp(4), dp(11), dp(4), dp(6))
                setOnClickListener { sheetShowAll = true; rebuildSheet() }
            })
        }
        val allRow = tv(tr("слышать все каналы · сбросить выбор", "hear all channels · reset choice"), 12.5f, cDim).apply {
            setPadding(dp(4), dp(12), dp(4), dp(6))
            setOnClickListener {
                exitSolo()
                sheetOverlay.visibility = View.GONE
                refreshFeed(true)
            }
        }
        sheetList.addView(allRow)
        sheetList.addView(tv(
            tr("«в работу» — кого слышу (можно несколько каналов)\n", "“active” — whom I hear (several channels allowed)\n") +
                tr("отвечаю всегда тому, кто говорил последним · «ответить» — сказать ЭТОМУ,\n", "I always reply to whoever spoke last · “reply” — say it to THIS one,\n") +
                tr("на одно сообщение · долгий тап по строке — убрать канал из списка", "for one message · long press on a row — remove channel from list"),
            9.5f, cFaint
        ).apply {
            setPadding(dp(4), dp(4), dp(4), 0)
        })
    }

    // ── экран «сессии хаба»: каналы хаба, создать и удалить ──────────────
    // Телефон кладёт JSON-команду через /say в служебный канал hub-control; хаб выполняет её,
    // сам даёт id и кладёт итог (по req) и новый список каналов в /hubstate.
    private fun openHubChannels() {
        sheetMode = "hubch"
        renderHubChannels(lastModels, loading = true)
        sheetOverlay.visibility = View.VISIBLE
        fetchModels()
    }

    private fun jstr(o: JSONObject, k: String) = o.optString(k, "").let { if (it == "null") "" else it }

    private fun renderHubChannels(st: JSONObject?, loading: Boolean = false) {
        sheetList.removeAllViews()
        sheetList.addView(tv(tr("‹ каналы", "‹ channels"), 12.5f, cDim).apply {
            setPadding(dp(2), dp(4), dp(2), dp(8))
            setOnClickListener { sheetMode = "channels"; rebuildSheet(); fetchSessions() }
        })
        hubCommandStatus(st)?.let { (text, col) ->
            sheetList.addView(tv(text, 12.5f, col).apply { setPadding(dp(2), 0, dp(2), dp(8)) })
        }
        if (st == null) {
            sheetList.addView(tv(
                if (loading) tr("запрашиваю состояние хаба…", "asking for hub state…")
                else tr("хаб не прислал состояние: хаб не запущен или релей ещё не знает /hubstate", "hub sent no state: the hub is not running or the relay does not know /hubstate yet"),
                12f, cFaint
            ).apply { setPadding(dp(2), dp(10), dp(2), dp(10)) })
            return
        }
        val labels = HashMap<String, String>()
        st.optJSONArray("agents")?.let { ag ->
            for (i in 0 until ag.length()) {
                val a = ag.optJSONObject(i) ?: continue
                labels[a.optString("name")] = a.optString("label", a.optString("name"))
            }
        }
        hubAllowOpts.clear()
        st.optJSONArray("allow_options")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONArray(i) ?: continue
                val mark = (if (o.optBoolean(2)) tr(" · ключ, твои деньги", " · key, your money") else "") +
                    (if (o.optString(3) == "yes") tr(" · учится на коде", " · learns from code") else "")
                hubAllowOpts.add(o.optString(0) to (o.optString(1).ifEmpty { o.optString(0) } + mark))
            }
        }
        val chans = st.optJSONArray("channels")
        val n = chans?.length() ?: 0
        val room = st.optInt("max_channels", 10) - 1   // один из десяти — служебный канал хаба
        sheetList.addView(tv(tr("каналы хаба · $n из $room", "hub channels · $n of $room"), 11f, cDim, bold = true).apply {
            setPadding(dp(2), 0, 0, dp(4))
        })
        if (chans != null) for (i in 0 until chans.length()) {
            val item = chans.optJSONObject(i) ?: continue
            sheetList.addView(hubChannelCard(item, labels),
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
        sheetList.addView(tv(tr("новый канал", "new channel"), 11f, cDim, bold = true).apply { setPadding(dp(2), dp(6), 0, dp(4)) })
        val projects = st.optJSONArray("projects")
        when {
            n >= room -> sheetList.addView(tv(
                tr("больше каналов релей не пропустит: удали ненужный, чтобы создать новый", "the relay will not allow more channels: delete an unused one to create a new one"), 12f, cFaint))
            projects == null || projects.length() == 0 -> sheetList.addView(tv(
                tr("в настройках хаба нет списка проектов (projects)", "no project list (projects) in hub settings"), 12f, cFaint))
            else -> addHubCreateForm(projects, st.optJSONArray("folders"))
        }
        sheetList.addView(tv(
            tr("канал из настроек хаба удаляется только на сервере · при удалении сессии агентов ", "a channel from hub settings is deleted only on the server · when a session is deleted, agents ") +
                tr("уходят в архив, папка проекта остаётся", "go to the archive, the project folder stays"),
            9.5f, cFaint
        ).apply { setPadding(dp(4), dp(8), dp(4), 0) })
    }

    private fun hubChannelCard(c: JSONObject, labels: Map<String, String>): LinearLayout {
        val id = jstr(c, "id")
        val name = jstr(c, "name").ifEmpty { id }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bordered(12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(tv(name, 14f, cText, bold = true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(tv(jstr(c, "project"), 11f, cDim))
        card.addView(head)
        val lead = jstr(c, "lead")
        val last = c.optLong("last_activity", 0L) * 1000L
        card.addView(tv(
            tr("ведущий: ", "lead: ") + (labels[lead] ?: lead.ifEmpty { "—" }) +
                " · " + (if (last > 0) tr("активность ${timeAgo(last)}", "active ${timeAgo(last)}") else tr("ещё не работал", "not worked yet")),
            11f, cDim
        ).apply { setPadding(0, dp(3), 0, 0) })
        val pinned = jstr(c, "pinned")
        if (pinned.isNotEmpty()) {
            val since = c.optLong("pinned_since", 0L) * 1000L
            card.addView(tv(tr("закреплён ${labels[pinned] ?: pinned}", "pinned ${labels[pinned] ?: pinned}") + (if (since > 0) tr(" с ${hhmm(since)}", " since ${hhmm(since)}") else "") +
                tr(" · «сам выбирай» снимет", " · “choose yourself” unpins"), 11f, cPlay).apply { setPadding(0, dp(3), 0, 0) })
        }
        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        btns.addView(tv(tr("ответить", "reply"), 11.5f, cDim).apply {
            background = bordered(8)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            contentDescription = tr("отвечать в этот канал", "reply to this channel")
            setOnClickListener {
                SessionBook.seen(this@MainActivity, id, name, if (last > 0) last else System.currentTimeMillis())
                val svc = BridgeService.instance
                if (svc != null && BridgeService.running) svc.setReplyTargetManual(id)
                else { Cfg.replyTarget = id; Cfg.save(this@MainActivity) }
                BridgeService.instance?.onReplyTargetPicked()
                LogBus.add(tr("отвечаю теперь в «$name»", "now replying in “$name”"))
                sheetOverlay.visibility = View.GONE
                refreshFeed(true)
            }
        })
        btns.addView(tv(tr("доступ", "access"), 11.5f, cDim).apply {
            background = bordered(8)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            contentDescription = tr("кто может работать в этом канале", "who can work in this channel")
            setOnClickListener {
                if (hubAllowEditId == id) hubAllowEditId = "" else {
                    hubAllowEditId = id
                    hubAllowDraft.clear()
                    c.optJSONArray("allow")?.let { a -> for (i in 0 until a.length()) hubAllowDraft.add(a.optString(i)) }
                }
                renderHub()
            }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
        if (c.optBoolean("deletable")) {
            btns.addView(tv(tr("удалить", "delete"), 11.5f, 0xFFC9483C.toInt()).apply {
                background = bordered(8)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                contentDescription = tr("удалить канал хаба", "delete hub channel")
                setOnClickListener { confirmHubDelete(id, name) }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
        } else {
            val why = jstr(c, "why_not")
            if (why.isNotEmpty()) btns.addView(tv(why, 10f, cFaint),
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(10) })
        }
        val allow = c.optJSONArray("allow")
        card.addView(tv(tr("доступ: ", "access: ") + allowText(allow), 11f, if (allow == null) cDim else cPlay).apply {
            setPadding(0, dp(3), 0, 0)
        }, 2)
        card.addView(btns)
        if (hubAllowEditId == id) {
            card.addView(allowChips(hubAllowDraft), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(6)
            })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            row.addView(smallBtn(tr("сохранить доступ", "save access"), cPlay) {
                val cmd = JSONObject().put("cmd", "channel.update").put("id", id)
                cmd.put("allow", if (hubAllowDraft.isEmpty()) JSONObject.NULL else org.json.JSONArray(hubAllowDraft.toList()))
                if (sendHubCommand(cmd, "channel.update", id, tr("меняю доступ в «$name»", "changing access in “$name”"))) {
                    hubAllowEditId = ""
                }
            })
            row.addView(smallBtn(tr("отмена", "cancel"), cDim) { hubAllowEditId = ""; renderHub() },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
            card.addView(row)
        }
        return card
    }

    // «, сброс сегодня в 13:17» / «завтра в 9:05» / «13 октября в 16:04» — по часам телефона
    private fun resetText(ts: Long): String {
        if (ts <= 0) return ""
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        val now = java.util.Date()
        val tomorrow = java.util.Date(now.time + 86_400_000L)
        val d = java.util.Date(ts)
        val word = when (day.format(d)) {
            day.format(now) -> tr("сегодня", "today")
            day.format(tomorrow) -> tr("завтра", "tomorrow")
            else -> java.text.SimpleDateFormat("d MMMM", Lang.locale).format(d)
        }
        return tr(", сброс $word в ${hhmm(ts)}", ", resets $word at ${hhmm(ts)}")
    }

    private fun allowText(allow: org.json.JSONArray?): String {
        if (allow == null || allow.length() == 0) return tr("все подписки", "all subscriptions")
        val names = HashMap<String, String>()
        for ((k, v) in hubAllowOpts) names[k] = v.substringBefore(" ·")
        return (0 until allow.length()).joinToString(", ") { names[allow.optString(it)] ?: allow.optString(it) }
    }

    // выбор разрешённых: «все» или отмеченные подписки; ключи и бесплатные модели помечены
    private fun allowChips(sel: MutableSet<String>): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val all = sel.isEmpty()
        box.addView(tv((if (all) "● " else "○ ") + tr("все подписки", "all subscriptions"), 13f, if (all) cPlay else cText).apply {
            setPadding(dp(6), dp(6), dp(6), dp(6))
            if (all) background = rounded(cAccSoft, 8)
            setOnClickListener { sel.clear(); renderHub() }
        })
        for ((key, label) in hubAllowOpts) {
            val on = key in sel
            box.addView(tv((if (on) "☑ " else "☐ ") + label, 13f, if (on) cPlay else cText).apply {
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener { if (!sel.remove(key)) sel.add(key); renderHub() }
            })
        }
        return box
    }

    private fun addHubCreateForm(projects: org.json.JSONArray, folders: org.json.JSONArray?) {
        val edit = hubNameEdit ?: EditText(this).apply {
            hint = tr("имя канала", "channel name")
            setHintTextColor(cFaint)
            setTextColor(cText)
            textSize = 14f
            background = bordered(12)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
            contentDescription = tr("имя нового канала", "new channel name")
        }.also { hubNameEdit = it }
        (edit.parent as? android.view.ViewGroup)?.removeView(edit)
        sheetList.addView(edit, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        sheetList.addView(tv(tr("проект", "project"), 10.5f, cFaint).apply { setPadding(dp(2), dp(8), 0, dp(2)) })
        for (i in 0 until projects.length()) {
            val pr = projects.optJSONObject(i) ?: continue
            val key = jstr(pr, "key")
            val exists = pr.optBoolean("exists")
            val picked = exists && key == hubProject
            sheetList.addView(tv(
                (if (picked) "● " else "○ ") + key + (if (exists) "" else tr(" · нет папки на сервере", " · no folder on the server")),
                13f, if (!exists) cFaint else if (picked) cPlay else cText
            ).apply {
                setPadding(dp(6), dp(8), dp(6), dp(8))
                if (picked) background = rounded(cAccSoft, 8)
                if (exists) setOnClickListener { hubProject = key; hubFolder = ""; renderHub() }
            })
        }
        if (folders != null && folders.length() > 0) {
            sheetList.addView(tv((if (hubFoldersOpen) "▾ " else "▸ ") + tr("или любая папка на сервере", "or any folder on the server") +
                (if (hubFolder.isNotEmpty()) ": " + hubFolder.substringAfterLast('/') else ""), 12f,
                if (hubFolder.isNotEmpty()) cPlay else cDim).apply {
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setOnClickListener { hubFoldersOpen = !hubFoldersOpen; renderHub() }
            })
            if (hubFoldersOpen) for (i in 0 until folders.length()) {
                val fr = folders.optJSONObject(i) ?: continue
                val root = jstr(fr, "root").trimEnd('/')
                val dirs = fr.optJSONArray("dirs") ?: continue
                for (j in 0 until dirs.length()) {
                    val d = dirs.optString(j)
                    if (d.isEmpty()) continue
                    val path = "$root/$d"
                    val picked = path == hubFolder
                    sheetList.addView(tv((if (picked) "● " else "○ ") + d, 13f, if (picked) cPlay else cText).apply {
                        setPadding(dp(18), dp(7), dp(6), dp(7))
                        if (picked) background = rounded(cAccSoft, 8)
                        setOnClickListener { hubFolder = path; hubProject = ""; renderHub() }
                    })
                }
            }
        }
        sheetList.addView(tv(tr("или git-адрес проекта (https://… или git@…) — хаб склонирует его", "or the project git address (https://… or git@…) — the hub will clone it"), 10.5f, cFaint).apply {
            setPadding(dp(2), dp(8), 0, dp(2))
        })
        val git = persistentEdit(hubGitEdit, tr("git-адрес, если проекта нет в списке", "git address, if the project is not in the list"), password = false).also { hubGitEdit = it }
        sheetList.addView(git, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        sheetList.addView(tv(tr("кто может работать в канале", "who can work in the channel"), 10.5f, cFaint).apply { setPadding(dp(2), dp(8), 0, dp(2)) })
        sheetList.addView(allowChips(hubAllowNew), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        sheetList.addView(tv(tr("создать канал", "create channel"), 13f, cPlay).apply {
            background = bordered(10)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener {
                val name = edit.text.toString().trim()
                val gitUrl = hubGitEdit?.text?.toString()?.trim().orEmpty()
                if (hubBusy()) return@setOnClickListener
                if (gitUrl.isNotEmpty() && Regex("^https?://[^/]*@", RegexOption.IGNORE_CASE).containsMatchIn(gitUrl)) {
                    // токен в адресе ушёл бы в журнал и очередь релея: не отправляем вовсе
                    hubGitEdit?.setText("")
                    showHubNote(tr("токен в адресе не поддерживается, нужен ключ развёртывания (deploy key)", "token in the address is not supported, a deploy key is needed"))
                    return@setOnClickListener
                }
                if (gitUrl.isNotEmpty()) {
                    hubGitEdit?.let { hideKeyboard(it) }
                    val cmd = JSONObject().put("cmd", "channel.create").put("name", name).put("git", gitUrl)
                    if (hubAllowNew.isNotEmpty()) cmd.put("allow", org.json.JSONArray(hubAllowNew.toList()))
                    // клон идёт на сервере в фоне: ждём итог дольше обычного
                    if (!sendHubCommand(cmd, "channel.create", name.ifEmpty { gitUrl.substringAfterLast('/') },
                        tr("клонирую проект и создаю канал", "cloning the project and creating the channel"), waitMs = 600000L)) return@setOnClickListener
                    hubAllowNew.clear()
                    hubGitEdit?.setText("")
                    edit.setText("")
                    return@setOnClickListener
                }
                if (hubFolder.isNotEmpty()) {
                    edit.clearFocus()
                    hideKeyboard(edit)
                    val shownF = name.ifEmpty { hubFolder.substringAfterLast('/') }
                    val cmd = JSONObject().put("cmd", "channel.create").put("name", name).put("folder", hubFolder)
                    if (hubAllowNew.isNotEmpty()) cmd.put("allow", org.json.JSONArray(hubAllowNew.toList()))
                    if (!sendHubCommand(cmd, "channel.create", shownF, tr("создаю канал «$shownF»", "creating channel “$shownF”"))) return@setOnClickListener
                    hubAllowNew.clear()
                    edit.setText("")
                    return@setOnClickListener
                }
                if (hubProject.isEmpty()) {
                    hubReq = ""; LogBus.add(tr("канал не создан: не выбран проект", "channel not created: no project chosen"))
                    showHubNote(tr("выбери проект", "choose a project"))
                    return@setOnClickListener
                }
                edit.clearFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(edit.windowToken, 0)
                val shown = name.ifEmpty { hubProject }
                val cmd = JSONObject().put("cmd", "channel.create").put("name", name).put("project", hubProject)
                if (hubAllowNew.isNotEmpty()) cmd.put("allow", org.json.JSONArray(hubAllowNew.toList()))
                if (!sendHubCommand(cmd, "channel.create", shown, tr("создаю канал «$shown»", "creating channel “$shown”"))) return@setOnClickListener
                hubAllowNew.clear()
                edit.setText("")
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
    }

    private fun showHubNote(text: String) {
        hubReq = ""
        hubReqText = text
        renderHub()
        sheetList.addView(tv(text, 12f, cPlay).apply { setPadding(dp(2), dp(6), dp(2), 0) }, 1)
    }

    private fun confirmHubDelete(id: String, name: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(tr("удалить канал «$name»?\nсессии агентов уйдут в архив хаба, папка проекта останется.", "delete channel “$name”?\nagent sessions will go to the hub archive, the project folder stays."))
            .setPositiveButton(tr("удалить", "delete")) { _, _ ->
                sendHubCommand(JSONObject().put("cmd", "channel.delete").put("id", id),
                    "channel.delete", id, tr("удаляю канал «$name»", "deleting channel “$name”"))
            }
            .setNegativeButton(tr("отмена", "cancel"), null)
            .show()
    }

    // secret=true: ключ или код входа идут POST /hubsecret (релей держит их в памяти и в лог
    // не пишет), а не через /say и общую очередь
    // одна ячейка слежения за итогом: пока ждём прошлую команду (клон до 10 минут), новую не шлём
    private fun hubBusy(): Boolean {
        val busy = hubReq.isNotEmpty() && !hubReqDone && hubReqError.isEmpty() &&
            System.currentTimeMillis() - hubReqTs < hubReqWaitMs
        if (busy) android.widget.Toast.makeText(this, tr("подожди итог: $hubReqText", "wait for the result: $hubReqText"), android.widget.Toast.LENGTH_SHORT).show()
        return busy
    }

    private fun sendHubCommand(cmd: JSONObject, kind: String, arg: String, what: String,
                               secret: Boolean = false, waitMs: Long = 60000L): Boolean {
        if (Cfg.token.isEmpty() || Cfg.url.isEmpty()) { onboardOverlay.visibility = View.VISIBLE; return false }
        if (hubBusy()) return false
        val req = java.util.UUID.randomUUID().toString()
        cmd.put("req", req)
        hubReq = req; hubReqCmd = kind; hubReqArg = arg; hubReqText = what; hubReqWaitMs = waitMs
        hubReqTs = System.currentTimeMillis(); hubReqDone = false; hubReqError = ""
        if (!Regex("^[0-9a-fA-F]{64}$").matches(Cfg.pin.trim())) {
            hubReqError = tr("кривой пин в настройках", "bad pin in settings"); renderHub(); return false
        }
        val client = try {
            Net.client(Cfg.pin).newBuilder().callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
        } catch (e: Exception) {
            hubReqError = tr("кривой пин в настройках", "bad pin in settings"); renderHub(); return false
        }
        // без приставки «(набрано текстом)»: это команда хабу, не реплика
        val payload = if (secret) cmd.toString()
            else JSONObject().put("session_id", hubControlSid).put("text", cmd.toString()).toString()
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val path = if (secret) "hubsecret" else "say"
        val call = try {
            client.newCall(Request.Builder().url("${Cfg.url}/$path?token=${Cfg.token}").post(body).build())
        } catch (e: Exception) {
            hubReqError = tr("кривой адрес релея в настройках", "bad relay address in settings"); renderHub(); return false
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post {
                    if (hubReq == req) { hubReqError = tr("нет связи", "offline"); renderHub() }
                    LogBus.add(tr("команда хабу не ушла: ${e.javaClass.simpleName}", "hub command not sent: ${e.javaClass.simpleName}"))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val ok = response.isSuccessful
                response.close()
                main.post {
                    if (hubReq != req) return@post
                    if (!ok) { hubReqError = tr("релей ответил $code", "relay answered $code"); renderHub() }
                    else lastModelsFetch = 0L   // итог хаб положит в /hubstate через секунды
                }
            }
        })
        // Дать обработчику кнопки очистить принятые поля перед перерисовкой.
        main.post { renderHub() }
        return true
    }

    // строка итога последней команды; при первом найденном итоге правит список каналов рации
    private fun hubCommandStatus(st: JSONObject?): Pair<String, Int>? {
        if (hubReq.isEmpty()) return null
        val red = 0xFFC9483C.toInt()
        if (hubReqError.isNotEmpty()) return tr("$hubReqText — не ушло: $hubReqError", "$hubReqText — not sent: $hubReqError") to red
        val ctl = st?.optJSONArray("control")
        if (ctl != null) for (i in 0 until ctl.length()) {
            val r = ctl.optJSONObject(i) ?: continue
            if (r.optString("req") != hubReq) continue
            val ok = r.optBoolean("ok")
            if (ok && !hubReqDone) {
                val id = jstr(r, "id")
                if (hubReqCmd == "channel.create" && id.isNotEmpty()) {
                    SessionBook.seen(this, id, hubReqArg, System.currentTimeMillis())
                } else if (hubReqCmd == "channel.delete") {
                    if (BridgeService.instance?.picked()?.contains(id) == true) BridgeService.instance?.togglePicked(id)
                    SessionBook.forget(this, id)
                    if (Cfg.replyTarget == id) { Cfg.replyTarget = ""; Cfg.save(this) }
                }
                if (hubReqCmd == "channel.create") LogBus.add(tr("канал хаба создан: $hubReqArg", "hub channel created: $hubReqArg"))
                if (hubReqCmd == "channel.delete") LogBus.add(tr("канал хаба удалён", "hub channel deleted"))
            }
            hubReqDone = true
            return if (ok) tr("$hubReqText — готово", "$hubReqText — done") to cPlay
            else tr("$hubReqText — не вышло: ${jstr(r, "error")}", "$hubReqText — failed: ${jstr(r, "error")}") to red
        }
        if (System.currentTimeMillis() - hubReqTs > hubReqWaitMs) {
            hubReqDone = true
            return tr("$hubReqText — хаб не ответил за ${hubReqWaitMs / 60000} мин: он запущен?", "$hubReqText — hub did not answer in ${hubReqWaitMs / 60000} min: is it running?") to red
        }
        return "$hubReqText…" to cFaint
    }

    private fun renderHub() {
        when (sheetMode) {
            "conn" -> renderConnections(lastModels)
            "money" -> renderMoney(lastModels)
            else -> renderHubChannels(lastModels)
        }
    }

    // ── экран «расходы»: хватает ли связки подписок ──────────────────────
    // Сверху строка-вердикт, ниже подписки (цена, срок, окна с темпом и прогнозом), объём
    // работы с линейкой цен API и качество по агентам. Всё считает хаб: /hubstate → money.
    private fun openMoney() {
        sheetMode = "money"
        renderMoney(lastModels, loading = true)
        sheetOverlay.visibility = View.VISIBLE
        fetchModels()
    }

    private fun tokensShort(n: Long) = if (n <= 0) "0" else tokens(n)

    private fun renderMoney(st: JSONObject?, loading: Boolean = false) {
        sheetList.removeAllViews()
        sheetList.addView(tv(tr("‹ каналы", "‹ channels"), 12.5f, cDim).apply {
            setPadding(dp(2), dp(4), dp(2), dp(8))
            setOnClickListener { sheetMode = "channels"; rebuildSheet(); fetchSessions() }
        })
        hubCommandStatus(st)?.let { (text, col) ->
            sheetList.addView(tv(text, 12.5f, col).apply { setPadding(dp(2), 0, dp(2), dp(8)) })
        }
        val m = st?.optJSONObject("money")
        if (m == null) {
            sheetList.addView(tv(if (loading) tr("запрашиваю состояние хаба…", "asking the hub for its state…") else tr("хаб не прислал расходы", "hub sent no usage"), 12f, cFaint)
                .apply { setPadding(dp(2), dp(10), dp(2), dp(10)) })
            return
        }
        val red = 0xFFC9483C.toInt()
        val labels = HashMap<String, String>()
        st.optJSONArray("agents")?.let { ag ->
            for (i in 0 until ag.length()) ag.optJSONObject(i)?.let { labels[jstr(it, "name")] = jstr(it, "label") }
        }
        sheetList.addView(tv(jstr(m, "verdict"), 14.5f, cText, bold = true).apply {
            setPadding(dp(2), 0, dp(2), dp(10))
        })
        // подписки
        sheetList.addView(tv(tr("подписки", "subscriptions"), 11f, cDim, bold = true).apply { setPadding(dp(2), 0, 0, dp(4)) })
        val subs = m.optJSONArray("subscriptions")
        if (subs != null) for (i in 0 until subs.length()) {
            val sub = subs.optJSONObject(i) ?: continue
            val name = jstr(sub, "name")
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = bordered(12)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(tv(subTitle(name), 14f, cText, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val paidKey = sub.optBoolean("paid_key")
            val price = if (sub.isNull("price_month")) null else sub.optDouble("price_month")
            head.addView(tv(when {
                paidKey -> tr("ключ API: платишь за расход", "API key: you pay per use")
                price == null -> tr("цена не задана", "no price set")
                price == 0.0 -> tr("подарок", "gift")
                else -> String.format(java.util.Locale.US, tr("$%.2f/мес", "$%.2f/mo"), price).replace(".00", "")
            }, 11.5f, if (price == null && !paidKey) red else cDim))
            card.addView(head)
            val ends = jstr(sub, "ends")
            if (ends.isNotEmpty()) card.addView(tv(tr("до $ends · осталось дней: ${sub.optInt("days_left")}", "until $ends · days left: ${sub.optInt("days_left")}"), 11f,
                if (sub.optInt("days_left") <= 5) red else cDim).apply { setPadding(0, dp(3), 0, 0) })
            sub.optJSONArray("windows")?.let { ws ->
                for (j in 0 until ws.length()) {
                    val w = ws.optJSONObject(j) ?: continue
                    val who = w.optJSONArray("agents")?.let { a -> (0 until a.length()).joinToString(", ") { labels[a.optString(it)] ?: a.optString(it) } }.orEmpty()
                    val pace = if (w.isNull("pace_pct_h")) "" else String.format(java.util.Locale.US, tr(" · темп %.1f%%/ч", " · pace %.1f%%/h"), w.optDouble("pace_pct_h"))
                    val runsOut = w.optLong("runs_out_at", 0L)
                    val fc = when {
                        w.isNull("lasts") -> ""
                        w.optBoolean("lasts") -> tr(" · хватит до сброса", " · lasts until reset")
                        runsOut > 0 -> tr(" · при таком темпе кончится", " · at this pace it runs out") + resetText(runsOut * 1000L).removePrefix(tr(", сброс", ", resets"))
                        else -> ""
                    }
                    card.addView(tv(tr("${jstr(w, "window_ru")}: осталось ${w.optInt("left_pct")}%$pace$fc", "${jstr(w, "window_ru")}: ${w.optInt("left_pct")}% left$pace$fc") +
                        resetText(w.optLong("resets_at") * 1000L) + (if (who.isNotEmpty()) " ($who)" else ""), 10.5f,
                        if (!w.isNull("lasts") && !w.optBoolean("lasts")) red else cFaint).apply { setPadding(0, dp(3), 0, 0) })
                }
            }
            if (!paidKey) {
                if (planEditSub == name) {
                    val firstTime = planPriceEdit == null
                    val pe = persistentEdit(planPriceEdit, tr("цена в месяц, $ (подарок — 0)", "price per month, $ (gift — 0)"), password = false).also {
                        planPriceEdit = it
                        it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    }
                    val ee = persistentEdit(planEndsEdit, tr("до какого числа, ГГГГ-ММ-ДД", "until which date, YYYY-MM-DD"), password = false).also { planEndsEdit = it }
                    if (firstTime) {
                        pe.setText(if (price == null) "" else String.format(java.util.Locale.US, "%.2f", price).removeSuffix(".00"))
                        ee.setText(ends)
                    }
                    card.addView(pe, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
                    card.addView(ee, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
                    row.addView(smallBtn(tr("сохранить", "save"), cPlay) {
                        if (hubBusy()) return@smallBtn
                        val pr = pe.text.toString().trim().replace(",", ".").toDoubleOrNull()
                        val en = ee.text.toString().trim()
                        if (pr == null || !pr.isFinite() || pr < 0) {
                            android.widget.Toast.makeText(this, tr("цена — число, подарок — 0", "price is a number, gift is 0"), android.widget.Toast.LENGTH_SHORT).show()
                            return@smallBtn
                        }
                        if (en.isNotEmpty() && !Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(en)) {
                            android.widget.Toast.makeText(this, tr("дата в виде ГГГГ-ММ-ДД", "date as YYYY-MM-DD"), android.widget.Toast.LENGTH_SHORT).show()
                            return@smallBtn
                        }
                        hideKeyboard(pe)
                        val cmd = JSONObject().put("cmd", "plan.set").put("sub", name).put("price", pr)
                        if (en.isNotEmpty()) cmd.put("ends", en)
                        if (sendHubCommand(cmd, "plan.set", name, tr("сохраняю цену ${subTitle(name)}", "saving price of ${subTitle(name)}"))) {
                            planEditSub = ""
                            pe.setText(""); ee.setText("")
                        }
                    })
                    row.addView(smallBtn(tr("отмена", "cancel"), cDim) { planEditSub = ""; renderHub() },
                        LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
                    card.addView(row)
                } else card.addView(smallBtn(tr("цена и срок", "price and term"), cDim) {
                    planEditSub = name
                    // поля общие: заполнить текущими значениями ЭТОЙ подписки, а не прошлой
                    planPriceEdit?.setText(if (price == null) "" else
                        String.format(java.util.Locale.US, "%.2f", price).removeSuffix(".00"))
                    planEndsEdit?.setText(ends)
                    renderHub()
                },
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(6) })
            }
            sheetList.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
        // объём работы: линейка, не счёт
        val vol = m.optJSONObject("volume")
        for ((key, title) in listOf("today" to tr("сегодня", "today"), "week" to tr("7 дней", "7 days"))) {
            val v = vol?.optJSONObject(key) ?: continue
            sheetList.addView(tv(tr("объём работы · $title", "work volume · $title"), 11f, cDim, bold = true).apply { setPadding(dp(2), dp(8), 0, dp(4)) })
            var sum = 0.0
            var allPriced = true
            val names = v.keys().asSequence().toList()
            if (names.isEmpty()) sheetList.addView(tv(tr("работы не было", "no work yet"), 11f, cFaint))
            for (n in names) {
                val a = v.optJSONObject(n) ?: continue
                val priced = a.optBoolean("priced", true) && a.optInt("no_split") == 0
                if (!priced) allPriced = false
                sum += a.optDouble("api_usd", 0.0)
                sheetList.addView(tv(tr("${labels[n] ?: n}: задач ${a.optInt("tasks")}", "${labels[n] ?: n}: tasks ${a.optInt("tasks")}") +
                    (if (a.optInt("jobs") > 0) tr(", поручений ${a.optInt("jobs")}", ", jobs ${a.optInt("jobs")}") else "") +
                    tr(" · токенов ${tokensShort(a.optLong("tokens"))} (вход ${tokensShort(a.optLong("tok_in"))}, ", " · tokens ${tokensShort(a.optLong("tokens"))} (in ${tokensShort(a.optLong("tok_in"))}, ") +
                    tr("кэш ${tokensShort(a.optLong("tok_cache_read") + a.optLong("tok_cache_write"))}, выход ${tokensShort(a.optLong("tok_out"))})", "cache ${tokensShort(a.optLong("tok_cache_read") + a.optLong("tok_cache_write"))}, out ${tokensShort(a.optLong("tok_out"))})") +
                    (if (a.optDouble("api_usd", 0.0) > 0) String.format(java.util.Locale.US, tr(" · ≈ $%.2f по ценам API", " · ≈ $%.2f at API prices"), a.optDouble("api_usd")) else "") +
                    (if (!priced) tr(" · цены нет", " · no price") else ""), 10.5f, cFaint).apply { setPadding(dp(2), dp(2), dp(2), dp(2)) })
            }
            if (names.isNotEmpty()) sheetList.addView(tv(String.format(java.util.Locale.US, tr("всего ≈ $%.2f по ценам API", "total ≈ $%.2f at API prices"), sum) +
                (if (!allPriced) tr(" (без моделей, у которых нет цены)", " (excluding models with no price)") else ""), 11f, cDim).apply { setPadding(dp(2), dp(2), 0, 0) })
        }
        // качество за 7 дней
        val q = m.optJSONObject("quality_week")
        if (q != null && q.length() > 0) {
            sheetList.addView(tv(tr("качество · 7 дней", "quality · 7 days"), 11f, cDim, bold = true).apply { setPadding(dp(2), dp(10), 0, dp(4)) })
            for (n in q.keys().asSequence().toList()) {
                val a = q.optJSONObject(n) ?: continue
                val ret = if (a.isNull("returns_pct")) "" else tr(" · возвраты ведущим ${a.optDouble("returns_pct")}%", " · returned to lead ${a.optDouble("returns_pct")}%")
                sheetList.addView(tv(tr("${labels[n] ?: n}: жалобы ${a.optDouble("complaints_pct")}%$ret · ", "${labels[n] ?: n}: complaints ${a.optDouble("complaints_pct")}%$ret · ") +
                    tr("пустые и упавшие ${a.optDouble("failed_pct")}% · эскалации ${a.optDouble("escalations_pct")}%", "empty and failed ${a.optDouble("failed_pct")}% · escalations ${a.optDouble("escalations_pct")}%"),
                    10.5f, if (a.optDouble("complaints_pct") >= 20) red else cFaint).apply { setPadding(dp(2), dp(2), dp(2), dp(2)) })
            }
        }
        val pr = m.optJSONObject("prices")
        sheetList.addView(tv(tr("цены API — ", "API prices — ") + (jstr(pr ?: JSONObject(), "source").ifEmpty { tr("источник не найден", "source not found") }) +
            (jstr(pr ?: JSONObject(), "checked").let { if (it.isNotEmpty()) tr(", сверка $it", ", checked $it") else "" }) +
            tr(" · это линейка объёма работы, не счёт · голосом: «как с лимитами»", " · this is a work volume gauge, not a bill · by voice: “how are the limits”"), 9.5f, cFaint).apply {
            setPadding(dp(4), dp(10), dp(4), 0)
        })
    }

    // пока пишут имя, ключ или код, экран не перерисовываем: поле потеряло бы фокус
    private fun hubTyping() = hubNameEdit?.hasFocus() == true || hubGitEdit?.hasFocus() == true ||
        planPriceEdit?.hasFocus() == true || planEndsEdit?.hasFocus() == true || hubKeyEdit?.hasFocus() == true ||
        hubNfClientIdEdit?.hasFocus() == true || hubNfClientSecretEdit?.hasFocus() == true ||
        hubNfWorkspaceEdit?.hasFocus() == true ||
        hubCodeEdit?.hasFocus() == true

    private fun hubLoginActive(): Boolean {
        val lg = lastModels?.optJSONObject("login") ?: return false
        return jstr(lg, "state") in setOf("starting", "open_link", "checking")
    }

    // ── экран «подключения»: ключи API и вход в подписки ─────────────────
    // Ключ и код входа уходят POST /hubsecret; хаб проверяет ключ, кладёт его к себе (600)
    // и отдаёт в /hubstate только последние четыре символа. Удаление и вход — командой в hub-control.
    private fun openConnections() {
        sheetMode = "conn"
        renderConnections(lastModels, loading = true)
        sheetOverlay.visibility = View.VISIBLE
        fetchModels()
    }

    private fun persistentEdit(old: EditText?, hintText: String, password: Boolean): EditText {
        val e: EditText = old ?: EditText(this).apply {
            hint = hintText
            setHintTextColor(cFaint)
            setTextColor(cText)
            textSize = 14f
            background = bordered(12)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            maxLines = 1
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        e.hint = hintText              // одно поле на разные шаги: подсказка всегда текущая
        e.isSaveEnabled = !password
        (e.parent as? android.view.ViewGroup)?.removeView(e)
        return e
    }

    private fun hideKeyboard(v: View) {
        v.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun smallBtn(text: String, color: Int, onClick: () -> Unit) = tv(text, 11.5f, color).apply {
        background = bordered(8)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnClickListener { onClick() }
    }

    private fun renderConnections(st: JSONObject?, loading: Boolean = false) {
        sheetList.removeAllViews()
        sheetList.addView(tv(tr("‹ каналы", "‹ channels"), 12.5f, cDim).apply {
            setPadding(dp(2), dp(4), dp(2), dp(8))
            setOnClickListener { sheetMode = "channels"; rebuildSheet(); fetchSessions() }
        })
        hubCommandStatus(st)?.let { (text, col) ->
            sheetList.addView(tv(text, 12.5f, col).apply { setPadding(dp(2), 0, dp(2), dp(8)) })
        }
        if (st == null) {
            sheetList.addView(tv(
                if (loading) tr("запрашиваю состояние хаба…", "asking the hub for its state…")
                else tr("хаб не прислал состояние: хаб не запущен или релей ещё не знает /hubstate", "hub sent no state: hub is not running or the relay does not know /hubstate yet"),
                12f, cFaint
            ).apply { setPadding(dp(2), dp(10), dp(2), dp(10)) })
            return
        }
        val red = 0xFFC9483C.toInt()
        // ── вход в подписки
        sheetList.addView(tv(tr("вход в подписки", "subscription login"), 11f, cDim, bold = true).apply { setPadding(dp(2), 0, 0, dp(4)) })
        val lg = st.optJSONObject("login")
        if (lg != null) sheetList.addView(loginCard(lg),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
        val logins = st.optJSONArray("logins")
        if (logins != null) for (i in 0 until logins.length()) {
            val c = logins.optJSONObject(i) ?: continue
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            // кто сейчас вошёл в эту программу на сервере хаба (хаб проверяет раз в 10 минут)
            val loggedIn = if (c.isNull("logged_in") || !c.has("logged_in")) null else c.optBoolean("logged_in")
            val plan = jstr(c, "plan")
            val account = jstr(c, "account")
            val who = when {
                c.has("installed") && !c.optBoolean("installed") -> tr("не установлена на сервере", "not installed on the server")
                loggedIn == true -> tr("вошёл: ", "signed in: ") + plan.ifEmpty { tr("есть вход", "signed in") } +
                    (if (account.isNotEmpty()) " ($account)" else "")
                loggedIn == false -> tr("входа нет", "not signed in")
                else -> ""
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tv(jstr(c, "label"), 13.5f, cText))
            if (who.isNotEmpty()) col.addView(tv(who, 11f,
                if (loggedIn == true) cPlay else if (loggedIn == false) 0xFFC9483C.toInt() else cFaint
            ).apply { setPadding(0, dp(2), 0, 0) })
            row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (c.optBoolean("unverified") && who.isNotEmpty() && c.optBoolean("installed"))
                col.addView(tv(tr("вход с телефона ещё не проверен на этой программе", "login from the phone is not yet tested for this tool"), 10f, cFaint))
            if (c.optBoolean("phone") && !(c.has("installed") && !c.optBoolean("installed"))) {
                if (!hubLoginActive()) {
                    val start = {
                        sendHubCommand(JSONObject().put("cmd", "login.start").put("cli", jstr(c, "cli")),
                            "login.start", jstr(c, "cli"), tr("начинаю вход в ${jstr(c, "label")}", "starting login to ${jstr(c, "label")}"))
                    }
                    if (loggedIn == true) row.addView(smallBtn(tr("сменить аккаунт", "switch account"), cDim) {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setMessage(tr("Сейчас хаб работает через ${plan.ifEmpty { jstr(c, "label") }}", "The hub currently works through ${plan.ifEmpty { jstr(c, "label") }}") +
                                (if (account.isNotEmpty()) " ($account)" else "") + ".\n" +
                                tr("Новый вход заменит этот: все задачи хаба пойдут через другой аккаунт.", "A new login replaces this one: all hub tasks will go through the other account."))
                            .setPositiveButton(tr("сменить", "switch")) { _, _ -> start() }
                            .setNegativeButton(tr("отмена", "cancel"), null)
                            .show()
                    }) else row.addView(smallBtn(tr("войти", "sign in"), cPlay) { start() })
                }
            } else row.addView(tv(jstr(c, "note").ifEmpty { tr("вход только с терминала", "login only from the terminal") }, 10.5f, cFaint))
            sheetList.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        // ── ключи API: деньги владельца
        sheetList.addView(tv(tr("ключи API", "API keys"), 11f, cDim, bold = true).apply { setPadding(dp(2), dp(12), 0, dp(4)) })
        sheetList.addView(tv(
            tr("Ключ API — это твои деньги, а не подписка: каждый запрос списывается с баланса. ", "An API key is your money, not a subscription: every request is charged to the balance. ") +
                tr("Прежде чем подключать, поставь лимит трат в кабинете поставщика.", "Before connecting, set a spending limit in the provider console."),
            11.5f, red
        ).apply {
            background = bordered(10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
        val free = st.optJSONArray("free_projects")
        val freeList = if (free == null) "" else (0 until free.length()).joinToString(", ") { free.optString(it) }
        val subs = HashMap<String, JSONObject>()
        st.optJSONArray("subscriptions")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { subs[jstr(it, "name")] = it }
        }
        val provs = st.optJSONArray("providers")
        if (provs != null) for (i in 0 until provs.length()) {
            val item = provs.optJSONObject(i) ?: continue
            sheetList.addView(providerCard(item, subs, freeList),
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
        sheetList.addView(tv(
            tr("ключ хранится только на сервере хаба; на экран приходят последние 4 символа · ", "the key is stored only on the hub server; the screen gets only the last 4 characters · ") +
                tr("отключение не отзывает ключ у поставщика — отзови его там", "disconnecting does not revoke the key at the provider — revoke it there"),
            9.5f, cFaint
        ).apply { setPadding(dp(4), dp(4), dp(4), 0) })
    }

    private fun loginCard(lg: JSONObject): LinearLayout {
        val red = 0xFFC9483C.toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bordered(12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val state = jstr(lg, "state")
        val label = jstr(lg, "label")
        val userCode = jstr(lg, "user_code")      // код устройства: его вводят на странице, не сюда
        val localhost = lg.optBoolean("localhost")
        val lid = jstr(lg, "id")
        card.addView(tv(when (state) {
            "starting" -> tr("$label: запускаю вход…", "$label: starting login…")
            "open_link" -> if (userCode.isNotEmpty()) tr("$label: открой страницу и введи на ней код", "$label: open the page and enter the code there")
                else tr("$label: открой ссылку, войди и скопируй код со страницы", "$label: open the link, sign in and copy the code from the page")
            "checking" -> tr("$label: проверяю…", "$label: checking…")
            "done" -> tr("$label: вход выполнен", "$label: signed in")
            "cancelled" -> tr("$label: вход отменён", "$label: login cancelled")
            else -> "$label: " + jstr(lg, "error").ifEmpty { tr("вход не удался", "login failed") }
        }, 13f, if (state == "done") cPlay else if (state == "error" || state == "expired") red else cText))
        if (lg.optInt("restarts") > 0 && state == "open_link")
            card.addView(tv(tr("прежняя ссылка истекла — эта новая", "the old link expired — this one is new"), 10.5f, cFaint).apply { setPadding(0, dp(2), 0, 0) })
        val cancel = {
            sendHubCommand(JSONObject().put("cmd", "login.cancel"), "login.cancel", lid, tr("отменяю вход", "cancelling login"))
        }
        val fresh = {
            sendHubCommand(JSONObject().put("cmd", "login.restart").put("cli", jstr(lg, "cli")),
                "login.restart", lid, tr("беру новую ссылку", "getting a new link"))
        }
        val url = jstr(lg, "url")
        if (state == "open_link" && url.isNotEmpty()) {
            card.addView(smallBtn(tr("1 · открыть страницу входа", "1 · open the login page"), cPlay) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                catch (_: Exception) { LogBus.add(tr("ссылку входа нечем открыть", "nothing to open the login link with")) }
            }.apply { setPadding(dp(10), dp(8), dp(10), dp(8)) },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
            if (userCode.isNotEmpty()) {
                // поток с кодом устройства: владелец вводит код на сайте, хаб сам видит, что вход прошёл
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, 0)
                }
                row.addView(tv(tr("2 · код для страницы: ", "2 · code for the page: "), 12f, cDim))
                row.addView(tv(userCode, 18f, cText, bold = true).apply { setTextIsSelectable(true) })
                row.addView(smallBtn(tr("копировать", "copy"), cDim) { copyText(userCode, this@MainActivity.window.decorView) },
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
                card.addView(row)
                card.addView(tv(tr("3 · после ввода кода хаб сам заметит вход — ничего сюда вставлять не нужно", "3 · after you enter the code the hub notices the login by itself — nothing to paste here"),
                    10.5f, cFaint).apply { setPadding(0, dp(6), 0, 0) })
            } else {
                if (localhost) card.addView(tv(
                    tr("после входа браузер покажет «не удаётся открыть страницу» — это нормально: ", "after login the browser will show “can’t open the page” — that is fine: ") +
                        tr("скопируй адрес из адресной строки и вставь его сюда как код", "copy the address from the address bar and paste it here as the code"), 10.5f, cFaint
                ).apply { setPadding(0, dp(6), 0, 0) })
                val code = persistentEdit(hubCodeEdit,
                    if (localhost) tr("2 · адрес из адресной строки", "2 · address from the address bar") else tr("2 · код со страницы", "2 · code from the page"), password = false
                ).also { hubCodeEdit = it }
                card.addView(code, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
                card.addView(smallBtn(tr("3 · отправить", "3 · send"), cPlay) {
                    if (hubBusy()) return@smallBtn
                    val v = code.text.toString().trim()
                    if (v.isEmpty()) return@smallBtn
                    hideKeyboard(code)
                    if (sendHubCommand(JSONObject().put("kind", "login_code").put("login", lid).put("code", v),
                        "login_code", lid, tr("отправляю код", "sending code"), secret = true)) code.setText("")
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
            }
            val btns = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            btns.addView(smallBtn(tr("новая ссылка", "new link"), cDim) { fresh() })
            btns.addView(smallBtn(tr("отменить вход", "cancel login"), cDim) { cancel() },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
            card.addView(btns)
        } else if (state == "starting" || state == "checking") {
            card.addView(smallBtn(tr("отменить вход", "cancel login"), cDim) { cancel() },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        } else if (state == "error" || state == "expired") {
            card.addView(smallBtn(tr("попробовать снова", "try again"), cPlay) { fresh() },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        return card
    }

    private fun providerCard(p: JSONObject, subs: Map<String, JSONObject>, freeList: String): LinearLayout {
        val red = 0xFFC9483C.toInt()
        val prov = jstr(p, "provider")
        val label = jstr(p, "label")
        val connected = p.optBoolean("connected")
        val needsKey = p.optBoolean("needs_key")
        val check = jstr(p, "check")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bordered(12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(tv(label, 14f, cText, bold = true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(tv(when {
            check == "checking" -> tr("проверяю…", "checking…")
            p.optBoolean("no_money") -> tr("нет денег на ключе", "no money on the key")
            connected -> tr("подключён", "connected") + jstr(p, "hint").let { if (it.isEmpty()) "" else " $it" }
            else -> tr("не подключён", "not connected")
        }, 11f, if (p.optBoolean("no_money")) red else if (connected) cPlay else cDim))
        card.addView(head)
        card.addView(tv(jstr(p, "model").substringAfter("/") +
            (if (p.optBoolean("paid")) tr(" · платит ключ", " · key pays") else tr(" · бесплатно", " · free")), 11f, cDim
        ).apply { setPadding(0, dp(3), 0, 0) })
        if (jstr(p, "trains") == "yes") card.addView(tv(
            tr("учится на твоём коде · хаб даёт ему только: ", "learns from your code · the hub gives it only: ") + freeList.ifEmpty { "—" }, 11f, red
        ).apply { setPadding(0, dp(3), 0, 0) })
        if (check == "error" && jstr(p, "detail").isNotEmpty()) card.addView(
            tv(jstr(p, "detail"), 11f, red).apply { setPadding(0, dp(3), 0, 0) })
        if (prov == "neuroflash") addNeuroflashDetails(card, p)
        val bal = p.optJSONObject("balance")
        if (bal != null && !bal.isNull("amount")) {
            val cur = jstr(bal, "currency").let { if (it == "USD" || it.isEmpty()) "$" else "$it " }
            val ok = bal.optBoolean("available", true)
            card.addView(tv(tr("на счёте у поставщика: ", "balance at the provider: ") + cur + String.format(java.util.Locale.US, "%.2f", bal.optDouble("amount")) +
                (if (!ok) tr(" · платить нечем", " · cannot pay") else ""), 11f, if (ok) cDim else red).apply { setPadding(0, dp(3), 0, 0) })
        } else if (jstr(p, "balance_note").isNotEmpty())
            card.addView(tv(tr("баланс: ", "balance: ") + jstr(p, "balance_note"), 10.5f, cFaint).apply { setPadding(0, dp(3), 0, 0) })
        val used = p.optDouble("usage_usd", Double.NaN)
        if (!used.isNaN()) {
            val lim = p.optDouble("limit_usd", Double.NaN)
            card.addView(tv(tr("у поставщика потрачено ", "spent at the provider ") + String.format(java.util.Locale.US, "$%.2f", used) +
                (if (lim.isNaN()) tr(" · лимита трат нет", " · no spending limit") else tr(" из ", " of ") + String.format(java.util.Locale.US, "$%.2f", lim)),
                10.5f, if (lim.isNaN()) red else cFaint).apply { setPadding(0, dp(3), 0, 0) })
        }
        subs[prov]?.let { sub ->
            for ((key, t) in listOf("today" to tr("сегодня", "today"), "week" to tr("7 дней", "7 days"))) {
                val c = sub.optJSONObject(key) ?: continue
                val tok = c.optLong("tokens", 0L)
                val cost = c.optDouble("cost_usd", 0.0)
                card.addView(tv(tr("$t: задач ${c.optInt("tasks") + c.optInt("jobs")} · токенов ${tokens(tok)}", "$t: tasks ${c.optInt("tasks") + c.optInt("jobs")} · tokens ${tokens(tok)}") +
                    (if (cost > 0) " · " + String.format(java.util.Locale.US, "$%.2f", cost) else ""),
                    10.5f, cFaint).apply { setPadding(0, dp(3), 0, 0) })
            }
        }
        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        if (needsKey) btns.addView(smallBtn(if (connected) tr("заменить ключ", "replace key") else tr("добавить ключ", "add key"), cPlay) {
            hubKeyProvider = if (hubKeyProvider == prov) "" else prov
            hubKeyReplace = connected
            renderHub()
        }) else if (!connected) btns.addView(smallBtn(tr("подключить", "connect"), cPlay) { confirmFreeProvider(prov, label, freeList) })
        if (connected) btns.addView(smallBtn(tr("отключить", "disconnect"), red) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(tr("отключить $label?\nхаб сотрёт ключ у себя; у поставщика ключ останется — отзови его там.", "disconnect $label?\nthe hub will erase the key on its side; the key stays valid at the provider — revoke it there."))
                .setPositiveButton(tr("отключить", "disconnect")) { _, _ ->
                    sendHubCommand(JSONObject().put("cmd", "key.delete").put("provider", prov),
                        "key.delete", prov, tr("отключаю $label", "disconnecting $label"))
                }
                .setNegativeButton(tr("отмена", "cancel"), null)
                .show()
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
        card.addView(btns)
        if (needsKey && hubKeyProvider == prov) {
            val isNeuroflash = prov == "neuroflash"
            val edit = if (isNeuroflash) null else
                persistentEdit(hubKeyEdit, tr("ключ $label", "$label key"), password = true).also { hubKeyEdit = it }
            if (isNeuroflash) {
                val clientId = persistentEdit(hubNfClientIdEdit, "Client ID", password = false)
                    .also { hubNfClientIdEdit = it }
                val clientSecret = persistentEdit(hubNfClientSecretEdit, "Client Secret", password = true)
                    .also { hubNfClientSecretEdit = it }
                val workspaceId = persistentEdit(hubNfWorkspaceEdit, tr("Workspace ID (необязательно)", "Workspace ID (optional)"), password = false)
                    .also { hubNfWorkspaceEdit = it }
                for (field in listOf(clientId, clientSecret, workspaceId))
                    card.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
            } else {
                card.addView(edit!!, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
            }
            card.addView(smallBtn(tr("проверить и подключить", "check and connect"), cPlay) {
                val key = if (isNeuroflash) {
                    val id = hubNfClientIdEdit?.text?.toString()?.trim().orEmpty()
                    val secret = hubNfClientSecretEdit?.text?.toString()?.trim().orEmpty()
                    if (id.isEmpty() || secret.isEmpty()) return@smallBtn
                    JSONObject().put("client_id", id).put("client_secret", secret).apply {
                        hubNfWorkspaceEdit?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                            put("workspace_id", it)
                        }
                    }.toString()
                } else edit!!.text.toString().trim()
                if (key.isEmpty()) return@smallBtn
                hideKeyboard(if (isNeuroflash) hubNfClientSecretEdit!! else edit!!)
                confirmPaidKey(prov, label, key, hubKeyReplace) {
                    if (isNeuroflash) {
                        hubNfClientIdEdit?.setText("")
                        hubNfClientSecretEdit?.setText("")
                        hubNfWorkspaceEdit?.setText("")
                    } else edit!!.setText("")
                }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        return card
    }

    private fun addNeuroflashDetails(card: LinearLayout, p: JSONObject) {
        fun line(text: String, color: Int = cDim) {
            card.addView(tv(text, 10.5f, color).apply { setPadding(0, dp(3), 0, 0) })
        }
        p.optJSONArray("models")?.let { models ->
            val names = (0 until models.length()).mapNotNull { i ->
                val row = models.optJSONObject(i) ?: return@mapNotNull null
                if (!row.optBoolean("available")) return@mapNotNull null
                row.optString("id", "").trim().takeIf { it.isNotEmpty() && it != "null" }
            }
            if (names.isNotEmpty()) line("\u043c\u043e\u0434\u0435\u043b\u0438: ${names.joinToString(", ")}")
        }
        val quotas = p.optJSONArray("quotas")
        if (quotas != null) {
            val tokens = (0 until quotas.length()).mapNotNull { quotas.optJSONObject(it) }
                .firstOrNull { it.optString("usageTypeKey") == "tokens" }
            if (tokens != null) {
                fun amount(key: String): Double? = if (!tokens.has(key) || tokens.isNull(key)) null else
                    tokens.optDouble(key, Double.NaN).takeIf { it.isFinite() }
                fun show(period: String, usedKey: String, limitKey: String) {
                    val used = amount(usedKey)
                    val limit = amount(limitKey)
                    if (used != null || limit != null) {
                        val u = used?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—"
                        val l = limit?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—"
                        line("$period: $u / $l")
                    }
                }
                show(tr("день", "day"), "usedAmountDaily", "limitAmountDaily")
                show(tr("месяц", "month"), "usedAmount", "limitAmount")
                if (tokens.optBoolean("fairUseLimitReached"))
                line("fair use limit reached", 0xFFC9483C.toInt())
            }
        }
        val checked = jstr(p, "checked_at").toLongOrNull()
        if (checked != null && checked > 0L && checked <= Long.MAX_VALUE / 1000L) {
            val formatted = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(checked * 1000L))
            line("\u043f\u0440\u043e\u0432\u0435\u0440\u0435\u043d\u043e: $formatted")
        }
    }

    private fun confirmPaidKey(prov: String, label: String, key: String, replace: Boolean, sent: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(tr("Ключ $label тратит твои деньги: каждая задача хаба списывается с баланса.\n", "Key $label spends your money: every hub task is charged to the balance.\n") +
                tr("Лимит трат у поставщика поставлен?", "Is a spending limit set at the provider?") +
                (if (replace) tr("\nСтарый ключ хаб заменит этим.", "\nThe hub will replace the old key with this one.") else ""))
            .setPositiveButton(tr("подключить", "connect")) { _, _ ->
                if (hubBusy()) return@setPositiveButton
                val body = JSONObject().put("provider", prov).put("key", key)
                if (replace) body.put("replace", true)
                if (!sendHubCommand(body, "key.add", prov, tr("проверяю ключ $label", "checking key $label"), secret = true,
                    waitMs = 180000L)) return@setPositiveButton
                hubKeyProvider = ""
                sent()
            }
            .setNegativeButton(tr("отмена", "cancel"), null)
            .show()
    }

    private fun confirmFreeProvider(prov: String, label: String, freeList: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(tr("$label: бесплатные модели учатся на коде, который им отправляют.\n", "$label: free models learn from the code sent to them.\n") +
                tr("Хаб даст им только проекты: ${freeList.ifEmpty { "—" }}. Подключить?", "The hub will give them only these projects: ${freeList.ifEmpty { "—" }}. Connect?"))
            .setPositiveButton(tr("подключить", "connect")) { _, _ ->
                sendHubCommand(JSONObject().put("cmd", "provider.add").put("provider", prov),
                    "provider.add", prov, tr("подключаю $label", "connecting $label"), waitMs = 180000L)
            }
            .setNegativeButton(tr("отмена", "cancel"), null)
            .show()
    }

    // ── экран «Модели»: состояние хаба (только чтение) ───────────────────
    // Хаб кладёт на релей JSON (POST /hubstate), телефон читает последнее: GET /hubstate.
    private fun openModels() {
        sheetMode = "models"
        renderModels(lastModels, loading = true)
        sheetOverlay.visibility = View.VISIBLE
        fetchModels()
    }

    private fun fetchModels() {
        if (Cfg.token.isEmpty() || Cfg.url.isEmpty()) return
        lastModelsFetch = System.currentTimeMillis()
        Thread {
            val raw = Net.get(Cfg.pin, "${Cfg.url}/hubstate?token=${Cfg.token}")
            // релей отдаёт {"ok","ts","state":{…хаб…}}; state=null — хаб ещё ничего не прислал
            val st = try {
                if (raw.isNullOrBlank()) null else JSONObject(raw).optJSONObject("state")
            } catch (_: Exception) { null }
            main.post {
                // поздний ответ не смеет подменить шторку, которую уже переключили
                if (st != null) lastModels = st
                if (sheetOverlay.visibility == View.VISIBLE && sheetMode == "models") renderModels(lastModels)
                // пока пишут имя канала, экран не перерисовываем: поле потеряло бы фокус
                if (sheetOverlay.visibility == View.VISIBLE && (sheetMode == "hubch" || sheetMode == "conn" ||
                        sheetMode == "money") && !hubTyping()) renderHub()
            }
        }.start()
    }

    private fun renderModels(st: JSONObject?, loading: Boolean = false) {
        sheetList.removeAllViews()
        sheetList.addView(tv(tr("‹ каналы", "‹ channels"), 12.5f, cDim).apply {
            setPadding(dp(2), dp(4), dp(2), dp(8))
            setOnClickListener { sheetMode = "channels"; rebuildSheet(); fetchSessions() }
        })
        hubCommandStatus(st)?.let { (text, col) ->
            sheetList.addView(tv(text, 12.5f, col).apply { setPadding(dp(2), 0, dp(2), dp(8)) })
        }
        if (st == null) {
            sheetList.addView(tv(
                if (loading) tr("запрашиваю состояние хаба…", "asking the hub for its state…")
                else tr("хаб не прислал состояние: хаб не запущен или релей ещё не знает /hubstate", "the hub sent no state: it is not running or the relay does not know /hubstate yet"),
                12f, cFaint
            ).apply { setPadding(dp(2), dp(10), dp(2), dp(10)) })
            return
        }
        val agents = st.optJSONArray("agents")
        val labels = HashMap<String, String>()
        if (agents != null) for (i in 0 until agents.length()) {
            val a = agents.optJSONObject(i) ?: continue
            labels[a.optString("name")] = a.optString("label", a.optString("name"))
        }
        // JSON null приходит из optString строкой "null"
        fun str(o: JSONObject, k: String) = o.optString(k, "").let { if (it == "null") "" else it }
        val lead = str(st, "lead")
        val cls = str(st, "classifier")
        val ts = st.optLong("ts") * 1000L
        sheetList.addView(tv(
            tr("ведущий: ", "lead: ") + (labels[lead] ?: lead.ifEmpty { "—" }) +
                (if (cls.isNotEmpty()) tr("  ·  классификатор: ", "  ·  classifier: ") + (labels[cls] ?: cls) else ""),
            12.5f, cPlay
        ))
        sheetList.addView(tv(tr("обновлено ${timeAgo(ts)}", "updated ${timeAgo(ts)}"), 10f, cFaint).apply { setPadding(0, dp(2), 0, dp(8)) })
        // подписки сверху: модели одной подписки делят её лимит и расход
        val subs = st.optJSONArray("subscriptions")
        if (subs != null && subs.length() > 0) {
            sheetList.addView(tv(tr("подписки", "subscriptions"), 11f, cDim, bold = true).apply { setPadding(dp(2), 0, 0, dp(4)) })
            for (i in 0 until subs.length()) {
                val sub = subs.optJSONObject(i) ?: continue
                sheetList.addView(subCard(sub, labels),
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
            }
            sheetList.addView(tv(tr("нейросети", "AI models"), 11f, cDim, bold = true).apply { setPadding(dp(2), dp(4), 0, dp(4)) })
        }
        if (agents == null || agents.length() == 0) {
            sheetList.addView(tv(tr("к хабу не подключено ни одной нейросети", "no AI models connected to the hub"), 12f, cFaint))
            return
        }
        for (i in 0 until agents.length()) {
            val item = agents.optJSONObject(i) ?: continue
            sheetList.addView(modelCard(item),
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
        sheetList.addView(tv(tr("только просмотр · меняются подписки пока на сервере", "view only · subscriptions can only be changed on the server for now"), 9.5f, cFaint).apply {
            setPadding(dp(4), dp(4), dp(4), 0)
        })
    }

    private fun modelCard(a: JSONObject): LinearLayout {
        val status = a.optString("status", "ok")
        val role = a.optString("role", "agent")
        // светофор: в строю / экономит / почти кончился / отдыхает; служебные — серые
        val dot = when {
            role == "classifier" || role == "helper" -> cFaint
            status == "ok" -> 0xFF6FA86A.toInt()
            status == "conserve" -> 0xFFD8B04A.toInt()
            status == "critical" -> cPlay
            else -> 0xFFC9483C.toInt()
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bordered(12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(tv("●", 14f, dot).apply { setPadding(0, 0, dp(8), 0) })
        head.addView(tv(a.optString("label", a.optString("name")), 14f, cText, bold = true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val roleRu = when (role) {
            "lead" -> tr("ведущий", "lead")
            "classifier" -> tr("классификатор", "classifier")
            "helper" -> tr("служебный", "helper")
            else -> a.optString("tier_ru", "")
        }
        head.addView(tv(roleRu, 11f, if (role == "lead") cPlay else cDim))
        card.addView(head)
        // закреплён владельцем на обсуждение: маршрутизатор в этих каналах не вмешивается
        a.optJSONArray("pinned_in")?.let { pins ->
            if (pins.length() > 0) card.addView(tv(tr("закреплено: ", "pinned: ") +
                (0 until pins.length()).joinToString(", ") { "«${pins.optString(it)}»" }, 11f, cPlay
            ).apply { setPadding(0, dp(3), 0, 0) })
        }
        // модель, которая реально отвечала («opus» → «Opus 5.5»), иначе как записано в настройках
        val model = jstr(a, "model_label").ifEmpty { jstr(a, "model") }
        card.addView(tv(
            a.optString("program", "?") + (if (model.isNotEmpty()) " · $model" else ""),
            11f, cDim
        ).apply { setPadding(0, dp(3), 0, 0) })
        if (role != "classifier" && role != "helper") {
            val until = a.optLong("resting_until", 0L)
            val limit = a.optString("limit", "")
            val wins = a.optJSONArray("windows")
            val stLine = a.optString("status_ru", status) +
                (if (until > 0) tr(" · отдыхает до ${hhmm(until * 1000L)}", " · resting until ${hhmm(until * 1000L)}") else "") +
                (if ((wins == null || wins.length() == 0) && limit.isNotEmpty() && limit != "null") " · $limit" else "")
            card.addView(tv(stLine, 11f, if (status == "ok") cDim else dot).apply { setPadding(0, dp(3), 0, 0) })
            // остаток по окнам и сброс — по часам телефона (хаб отдаёт время сброса в секундах)
            if (role != "classifier" && role != "helper" && (wins == null || wins.length() == 0) &&
                jstr(a, "limit_error").isNotEmpty()) card.addView(tv(tr("лимит не прочитан", "limit not read"), 10.5f, cFaint).apply {
                setPadding(0, dp(3), 0, 0)
            })
            if (wins != null && wins.length() > 0) {
                val parts = (0 until wins.length()).mapNotNull { i ->
                    val w = wins.optJSONObject(i) ?: return@mapNotNull null
                    val name = when (jstr(w, "window")) {
                        "five_hour" -> tr("5 часов", "5 hours")
                        "seven_day" -> tr("неделя", "week")
                        "month" -> tr("месяц", "month")
                        else -> jstr(w, "window")
                    }
                    val ts = w.optLong("resets_at", 0L) * 1000L
                    tr("$name: осталось ${w.optInt("left_pct")}%", "$name: ${w.optInt("left_pct")}% left") + resetText(ts)
                }
                card.addView(tv(parts.joinToString(" · "), 10.5f,
                    if (parts.any { it.contains(tr("осталось 0%", ": 0% left")) }) 0xFFC9483C.toInt() else cFaint
                ).apply { setPadding(0, dp(3), 0, 0) })
            }
        }
        val worker = role != "classifier" && role != "helper"
        for ((key, title) in listOf("today" to tr("сегодня", "today"), "week" to tr("7 дней", "7 days"))) {
            val c = a.optJSONObject(key) ?: continue
            card.addView(tv("$title: " + countsLine(c, redo = true, spend = worker), 10.5f, cFaint).apply {
                setPadding(0, dp(3), 0, 0)
            })
        }
        return card
    }

    // счётчики хаба за период: задачи, ошибки (и из них пустые ответы), расход в $-эквиваленте и токены
    private fun countsLine(c: JSONObject, redo: Boolean, spend: Boolean): String {
        val parts = ArrayList<String>()
        parts.add(tr("задач ${c.optInt("tasks")}", "tasks ${c.optInt("tasks")}"))
        if (c.optInt("jobs") > 0) parts.add(tr("поручений ${c.optInt("jobs")}", "jobs ${c.optInt("jobs")}"))
        parts.add(tr("ошибок ${c.optInt("errors")}", "errors ${c.optInt("errors")}"))
        if (c.optInt("empty") > 0) parts.add(tr("пустых ответов ${c.optInt("empty")}", "empty replies ${c.optInt("empty")}"))
        if (c.optInt("limits") > 0) parts.add(tr("лимит ${c.optInt("limits")}", "limit ${c.optInt("limits")}"))
        if (redo) parts.add(tr("переделок ${c.optInt("redo")}", "redos ${c.optInt("redo")}"))
        // Google расход не отдаёт: «$0.00» там была бы неправдой, показываем только известный расход
        if (spend && c.optDouble("cost_usd", 0.0) > 0) {
            parts.add(tr("расход ", "cost ") + String.format(java.util.Locale.US, "$%.2f", c.optDouble("cost_usd", 0.0)))
            val tok = c.optLong("tokens", 0L)
            if (tok > 0) parts.add(tr("токенов ", "tokens ") + tokens(tok))
        }
        return parts.joinToString(" · ")
    }

    private fun tokens(n: Long): String = when {
        n >= 1_000_000 -> String.format(java.util.Locale.US, tr("%.1f млн", "%.1f M"), n / 1e6)
        n >= 1_000 -> String.format(java.util.Locale.US, tr("%.1f тыс", "%.1f k"), n / 1e3)
        else -> n.toString()
    }

    private fun subCard(s: JSONObject, labels: Map<String, String>): LinearLayout {
        val name = s.optString("name", "")
        val title = s.optString("label", "").let { if (it.isEmpty() || it == "null") subTitle(name) else it }
        val until = s.optLong("resting_until", 0L) * 1000L
        val resting = until > System.currentTimeMillis()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bordered(12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = if (resting) 0xFFC9483C.toInt() else 0xFF6FA86A.toInt()
        head.addView(tv("●", 14f, dot).apply { setPadding(0, 0, dp(8), 0) })
        head.addView(tv(title, 14f, cText, bold = true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(tv(if (resting) tr("отдыхает до ${hhmm(until)}", "resting until ${hhmm(until)}") else tr("в строю", "ready"), 11f, if (resting) dot else cDim))
        card.addView(head)
        val ag = s.optJSONArray("agents")
        if (ag != null && ag.length() > 0) {
            val names = (0 until ag.length()).map { labels[ag.optString(it)] ?: ag.optString(it) }
            card.addView(tv(names.joinToString(", "), 11f, cDim).apply { setPadding(0, dp(3), 0, 0) })
        }
        for ((key, t) in listOf("today" to tr("сегодня", "today"), "week" to tr("7 дней", "7 days"))) {
            val c = s.optJSONObject(key) ?: continue
            card.addView(tv("$t: " + countsLine(c, redo = false, spend = true), 10.5f, cFaint).apply {
                setPadding(0, dp(3), 0, 0)
            })
        }
        return card
    }

    private fun subTitle(name: String) = when (name) {
        "google" -> "Google"
        "claude" -> "Claude Pro"
        "openai" -> "OpenAI"
        "opencode" -> "OpenCode"
        "kimi" -> "Kimi"
        else -> name.ifEmpty { tr("без подписки", "no subscription") }
    }

    // ── настройки ────────────────────────────────────────────────────────
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun switchRow(
        title: String, sub: String?, get: () -> Boolean, set: (Boolean) -> Unit,
        expose: ((Switch) -> Unit)? = null
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(11), dp(2), dp(11))
        }
        val colTx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        colTx.addView(tv(title, 13.5f, cText))
        if (sub != null) colTx.addView(tv(sub, 10f, cFaint).apply { setPadding(0, dp(2), 0, 0) })
        val sw = Switch(this).apply {
            isChecked = get()
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = android.content.res.ColorStateList(states, intArrayOf(cPlay, cDim))
            trackTintList = android.content.res.ColorStateList(states, intArrayOf(0x66D97757.toInt(), cMic))
            setOnCheckedChangeListener { _, on -> set(on); Cfg.save(this@MainActivity) }
        }
        expose?.invoke(sw)
        row.addView(colTx, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(sw)
        return row
    }

    private fun linkRow(title: String, sub: String?, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(12), dp(2), dp(12))
            setOnClickListener { onClick() }
        }
        val colTx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        colTx.addView(tv(title, 13.5f, cText))
        if (sub != null) colTx.addView(tv(sub, 10f, cFaint).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(colTx, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(tv("›", 16f, cFaint))
        return row
    }

    private fun divider() = View(this).apply { setBackgroundColor(cLine) }

    @SuppressLint("BatteryLife")
    private fun buildSettingsOverlay() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(24))
        }
        col.addView(tv(tr("‹ настройки", "‹ settings"), 15f, cDim, bold = true).apply {
            setPadding(0, dp(6), 0, dp(12))
            setOnClickListener { settingsOverlay.visibility = View.GONE }
        })

        // v1.04 (слово юзера: «надел наушники — работаю в них, снял дома — с телефона»)
        col.addView(switchRow(tr("сам следовать за наушниками", "follow the headphones automatically"),
            tr("надел — звук в них, снял — вслух; ничего не переключать руками", "put them on — sound goes there, take them off — it plays aloud; no manual switching"),
            { Cfg.autoRoute }, { Cfg.autoRoute = it }))
        col.addView(divider(), LinearLayout.LayoutParams(MATCH_PARENT, 1))
        col.addView(switchRow(tr("только в наушниках", "headphones only"), tr("если авто-режим выключен: без наушников ответы ждут", "if auto mode is off: without headphones, replies wait"),
            { Cfg.onlyHeadset }, { Cfg.onlyHeadset = it }))
        col.addView(divider(), LinearLayout.LayoutParams(MATCH_PARENT, 1))
        col.addView(switchRow(tr("называть канал перед ответом", "announce the channel before a reply"), null,
            { Cfg.announceProj }, { Cfg.announceProj = it }))
        col.addView(divider(), LinearLayout.LayoutParams(MATCH_PARENT, 1))
        col.addView(switchRow(tr("голосовые подсказки", "voice cues"), tr("«слушаю», «ушло», «связь потеряна»", "“listening”, “sent”, “connection lost”"),
            { Cfg.voiceCues }, { Cfg.voiceCues = it }))
        col.addView(divider(), LinearLayout.LayoutParams(MATCH_PARENT, 1))
        col.addView(switchRow(tr("отправка по тишине", "send on silence"), tr("замолчал — ушло само; выкл = только «отправляй» и кнопки", "go quiet — it sends itself; off = only “send” and buttons"),
            { Cfg.autoSend }, { Cfg.autoSend = it }, expose = { autoSendSwitchRef = it }))
        col.addView(divider(), LinearLayout.LayoutParams(MATCH_PARENT, 1))

        advBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(6), 0, 0, 0)
        }
        col.addView(linkRow(tr("для продвинутых", "advanced"), tr("whisper · связь · таймеры · кнопка гарнитуры", "whisper · connection · timers · headset button")) {
            advBox.visibility = if (advBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        col.addView(advBox)
        col.addView(divider(), LinearLayout.LayoutParams(MATCH_PARENT, 1))
        col.addView(linkRow(tr("отладка", "debug"), tr("журнал и диагностика — всё, что видим мы", "log and diagnostics — everything we see")) {
            debugOverlay.visibility = View.VISIBLE
            renderLog()
        })

        buildAdvanced()

        col.addView(tv("claudio code " + BridgeService.APP_VERSION, 10.5f, cFaint).apply {
            setPadding(dp(2), dp(18), 0, 0)
        })

        settingsOverlay = ScrollView(this).apply {
            setBackgroundColor(cBg)
            visibility = View.GONE
            isClickable = true
            addView(col, MATCH_PARENT, WRAP_CONTENT)
        }
        rootFrame.addView(settingsOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    @SuppressLint("BatteryLife")
    private fun buildAdvanced() {
        fun advLabel(t: String) = tv(t, 12f, cDim, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) }
        fun advBtn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            typeface = Typeface.MONOSPACE
            setTextColor(cText)
            background = rounded(cCard, 10)
            stateListAnimator = null
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { onClick() }
        }

        advBox.addView(advLabel(tr("связь с релеем", "relay connection")))
        advBox.addView(tv(tr("релей — твой сервер-мост. проще всего: ссылка-настройка или «адрес|токен|отпечаток» из буфера", "the relay is your bridge server. easiest: a setup link or “address|token|fingerprint” from the clipboard"), 10f, cFaint))
        urlEdit = EditText(this).apply {
            setText(Cfg.url); hint = tr("https://адрес:порт", "https://address:port")
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        tokenEdit = EditText(this).apply {
            setText(Cfg.token); hint = tr("токен релея", "relay token")
            inputType = InputType.TYPE_CLASS_TEXT
        }
        pinEdit = EditText(this).apply {
            setText(Cfg.pin); hint = tr("SHA-256 отпечаток сертификата", "SHA-256 certificate fingerprint")
            inputType = InputType.TYPE_CLASS_TEXT
        }
        for (e in listOf(urlEdit, tokenEdit, pinEdit)) {
            e.setTextColor(cText); e.setHintTextColor(cFaint); e.textSize = 13f
            e.typeface = Typeface.MONOSPACE
            e.backgroundTintList = android.content.res.ColorStateList.valueOf(cDim)
            advBox.addView(e)
        }
        advBox.addView(advBtn(tr("вставить конфиг из буфера", "paste config from clipboard")) { pasteConfig() },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        advBox.addView(advBtn(tr("сохранить и перезапустить", "save and restart")) {
            saveFields()
            if (BridgeService.running) restartService() else LogBus.add(tr("настройки сохранены", "settings saved"))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        advBox.addView(advBtn(tr("тест связи", "test connection")) { testRelay() },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })

        advBox.addView(advLabel(tr("режимы", "modes")))
        advBox.addView(switchRow(tr("безрукий режим", "hands-free mode"), tr("микрофон всегда включён, отправка словом", "mic always on, send by voice word"),
            { Cfg.handsFree }, { on ->
                Cfg.handsFree = on
                BridgeService.instance?.let { s -> if (on) s.startListening() else s.stopListening() }
            }))
        advBox.addView(switchRow(tr("распознавание whisper", "whisper recognition"), tr("на твоём сервере; лучше в шуме, нужен whisper-бэкенд", "on your server; better in noise, needs a whisper backend"),
            { Cfg.whisper }, { on ->
                Cfg.whisper = on
                BridgeService.instance?.let { s ->
                    if (s.isListening()) {
                        s.onWhisperToggled(on)
                        s.stopListening(); s.startListening()
                    }
                }
            }))
        advBox.addView(switchRow(tr("разговорный источник записи", "voice-call recording source"), tr("выкл = «распознавание»: проверка кнопки гарнитуры", "off = “recognition”: headset button check"),
            { Cfg.micVoiceComm }, { on ->
                Cfg.micVoiceComm = on
                LogBus.add(tr("источник записи: ", "recording source: ") + (if (on) tr("разговорный", "voice call") else tr("распознавание", "recognition")) +
                    tr(" — со следующей диктовки", " — from the next dictation"))
            }))
        advBox.addView(switchRow(tr("кнопка гарнитуры с первого нажатия", "headset button from the first press"), tr("по следу обрыва канала (без whisper)", "by the channel drop trace (no whisper)"),
            { Cfg.routeAsButton }, { on ->
                Cfg.routeAsButton = on
                LogBus.add(if (on) tr("кнопка: ловлю первое нажатие по обрыву канала", "button: catching the first press by the channel drop")
                    else tr("кнопка: как раньше — отправит второе нажатие", "button: as before — the second press sends"))
            }))
        advBox.addView(switchRow(tr("догонять пропущенное", "catch up on missed"), tr("при подключении — за последние 30 мин", "on connect — the last 30 min"),
            { Cfg.catchup }, { Cfg.catchup = it }))

        advBox.addView(advLabel(tr("прочее", "other")))
        advBox.addView(advBtn(tr("разрешить работу в фоне (батарея)", "allow background work (battery)")) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (pm.isIgnoringBatteryOptimizations(packageName)) {
                    LogBus.add(tr("фоновая работа уже разрешена", "background work is already allowed"))
                } else if (BuildConfig.FLAVOR == "store") {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    LogBus.add(tr("найди это приложение в списке и разреши работу в фоне", "find this app in the list and allow background work"))
                } else {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                }
            } catch (e: Exception) {
                LogBus.add(tr("не открылось: ${e.message}", "could not open: ${e.message}"))
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        serviceBtn = advBtn("") {
            if (BridgeService.running) stopService(Intent(this, BridgeService::class.java))
            else ensureService { }
            main.postDelayed({ refreshServiceBtn() }, 600)
        }
        refreshServiceBtn()
        advBox.addView(serviceBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        advBox.addView(advBtn(tr("показать первый запуск", "show first launch")) {
            settingsOverlay.visibility = View.GONE
            onboardOverlay.visibility = View.VISIBLE
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    private fun refreshServiceBtn() {
        if (::serviceBtn.isInitialized)
            serviceBtn.text = if (BridgeService.running) tr("остановить сервис", "stop service") else tr("запустить сервис", "start service")
    }

    private fun restartService() {
        LogBus.add(tr("перезапускаю сервис с новыми настройками", "restarting the service with new settings"))
        stopService(Intent(this, BridgeService::class.java))
        main.postDelayed({
            try {
                startForegroundService(Intent(this, BridgeService::class.java))
            } catch (e: Exception) {
                LogBus.add(tr("не смог перезапустить сервис: ${e.message?.take(60)}", "could not restart the service: ${e.message?.take(60)}"))
            }
        }, 600)
    }

    // ── отладка: журнал + диагностика ────────────────────────────────────
    private fun buildDebugOverlay() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(24))
        }
        col.addView(tv(tr("‹ отладка", "‹ debug"), 15f, cDim, bold = true).apply {
            setPadding(0, dp(6), 0, dp(10))
            setOnClickListener { debugOverlay.visibility = View.GONE }
        })
        debugTech = tv("", 11f, cDim).apply {
            background = rounded(cCard, 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        col.addView(debugTech)
        col.addView(tv(tr("журнал (свежее сверху)", "log (newest first)"), 10.5f, cFaint).apply { setPadding(0, dp(12), 0, dp(4)) })
        logView = tv("", 11f, cDim)
        val logScroll = ScrollView(this).apply { addView(logView, MATCH_PARENT, WRAP_CONTENT) }
        col.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        debugOverlay = col
        debugOverlay.setBackgroundColor(cBg)
        debugOverlay.visibility = View.GONE
        debugOverlay.isClickable = true
        rootFrame.addView(debugOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun renderLog() {
        if (::logView.isInitialized)
            logView.text = LogBus.dump().lines().reversed().joinToString("\n")
        refreshServiceBtn()
    }

    // ── первый запуск ────────────────────────────────────────────────────
    private fun buildOnboardOverlay() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        col.addView(tv(tr("первый запуск", "first launch"), 17f, cText, bold = true))
        col.addView(tv(tr("рация говорит с ТВОИМ сервером — общего облака нет", "the radio talks to YOUR server — there is no shared cloud"), 11f, cDim).apply {
            setPadding(0, dp(4), 0, dp(12))
        })

        fun step(n: String, body: String, code: String?): LinearLayout {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(cCard, 14)
                setPadding(dp(12), dp(10), dp(12), dp(11))
            }
            card.addView(tv(n, 11f, cPlay))
            card.addView(tv(body, 12f, cText).apply { setPadding(0, dp(4), 0, 0) })
            if (code != null) card.addView(tv(code, 10.5f, cDim).apply {
                background = GradientDrawable().apply {
                    setColor(cBg); setStroke(dp(1), cLine); cornerRadius = dp(8).toFloat()
                }
                setPadding(dp(9), dp(7), dp(9), dp(7))
                val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT); lp.topMargin = dp(7)
                layoutParams = lp
            })
            return card
        }
        val gap = { h: Int -> LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(h) } }
        col.addView(step(tr("шаг 1 · сервер", "step 1 · server"),
            tr("запусти на своём сервере одну команду — она поставит мост и напечатает ссылку-настройку и QR-код.", "run one command on your server — it installs the bridge and prints a setup link and QR code."),
            "bash install-relay.sh"), gap(0))
        col.addView(step(tr("шаг 2 · телефон", "step 2 · phone"),
            tr("отсканируй QR камерой (или открой ссылку claudio://…) — адрес, ключ и отпечаток встанут сами. печатать ничего не надо.", "scan the QR with the camera (or open the claudio://… link) — address, key and fingerprint fill in by themselves. no typing needed."),
            null), gap(10))
        col.addView(step(tr("шаг 3 · проверка", "step 3 · check"),
            tr("нажми круг и скажи «привет». ответ прозвучит в наушнике.", "press the circle and say “hello”. the reply will play in your earphone."),
            null), gap(10))

        col.addView(tv(tr("если ссылки нет — вставь конфиг вручную:", "if there is no link — paste the config by hand:"), 10.5f, cFaint).apply {
            setPadding(dp(2), dp(14), 0, dp(4))
        })
        col.addView(Button(this).apply {
            text = tr("вставить из буфера (адрес|токен|пин)", "paste from clipboard (address|token|pin)")
            textSize = 13f; isAllCaps = false
            typeface = Typeface.MONOSPACE
            setTextColor(cText); background = rounded(cCard, 10); stateListAnimator = null
            setOnClickListener { pasteConfig() }
        })
        col.addView(Button(this).apply {
            text = tr("начать разговор", "start talking")
            textSize = 15f; isAllCaps = false
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(cBg); background = rounded(cPlay, 14); stateListAnimator = null
            setPadding(dp(10), dp(14), dp(10), dp(14))
            setOnClickListener {
                if (Cfg.token.isEmpty()) {
                    LogBus.add(tr("сначала настрой релей — шаги выше", "set up the relay first — steps above"))
                } else {
                    onboardOverlay.visibility = View.GONE
                    ensureService { it.talkNow() }
                }
            }
        }, gap(12))
        col.addView(tv(tr("настрою позже — просто посмотреть", "I'll set up later — just look around"), 11f, cFaint).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
            setOnClickListener { onboardOverlay.visibility = View.GONE }
        })

        onboardOverlay = ScrollView(this).apply {
            setBackgroundColor(cBg)
            visibility = View.GONE
            isClickable = true
            addView(col, MATCH_PARENT, WRAP_CONTENT)
        }
        rootFrame.addView(onboardOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    // ── служебное (перенесено из старого экрана без изменений логики) ────
    private fun saveFields() {
        // Нормализуем ПРЯМО В ПАМЯТИ: Cfg.save чистит только prefs, и хвостовой «/»
        // в Cfg.url давал «//history» → 404 до следующего Cfg.load (ревью v0.69)
        Cfg.url = urlEdit.text.toString().trim().trimEnd('/')
        Cfg.token = tokenEdit.text.toString().trim()
        Cfg.pin = pinEdit.text.toString().trim()
        Cfg.save(this)
    }

    // Принимает JSON {"url":..,"token":..,"pin":..} или строку "url|token|pin".
    private fun pasteConfig() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val txt = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
            if (txt.isEmpty()) { LogBus.add(tr("буфер пуст", "clipboard is empty")); return }
            if (txt.startsWith("{")) {
                val o = JSONObject(txt)
                if (o.has("url")) urlEdit.setText(o.getString("url"))
                if (o.has("token")) tokenEdit.setText(o.getString("token"))
                if (o.has("pin")) pinEdit.setText(o.getString("pin"))
            } else if (txt.startsWith("claudio://")) {
                val uu = Uri.parse(txt)
                if (!"setup".equals(uu.host, true)) {
                    LogBus.add(tr("не разобрал ссылку — нужен вид claudio://setup?url=…&token=…", "couldn't parse the link — it must look like claudio://setup?url=…&token=…"))
                    return
                }
                handleDeepLink(Intent(Intent.ACTION_VIEW, uu))
                return
            } else if (txt.contains("|")) {
                val p = txt.split("|")
                when {
                    p.size >= 3 -> {
                        urlEdit.setText(p[0].trim()); tokenEdit.setText(p[1].trim()); pinEdit.setText(p[2].trim())
                    }
                    p.size == 2 -> {
                        urlEdit.setText(p[0].trim()); tokenEdit.setText(p[1].trim())
                        LogBus.add(tr("пин не найден в строке — впиши отпечаток сертификата вручную", "pin not found in the string — enter the certificate fingerprint by hand"))
                    }
                    else -> {
                        LogBus.add(tr("не разобрал строку — нужен формат адрес|токен|пин", "couldn't parse the string — format must be address|token|pin"))
                        return
                    }
                }
            } else {
                tokenEdit.setText(txt)
            }
            saveFields()
            LogBus.add(tr("конфиг вставлен и сохранён", "config pasted and saved"))
            if (Cfg.token.isNotEmpty()) onboardOverlay.visibility = View.GONE
        } catch (e: Exception) {
            LogBus.add(tr("не разобрал буфер: ${e.message}", "couldn't parse the clipboard: ${e.message}"))
        }
    }

    private fun askPermissions() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31) need += Manifest.permission.BLUETOOTH_CONNECT
        val missing = need.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }

    private fun testRelay() {
        if (::urlEdit.isInitialized) saveFields()   // тест идёт на то, что в полях, как раньше
        val client = try { Net.client(Cfg.pin) } catch (e: Exception) {
            LogBus.add(tr("кривой пин: ${e.message}", "bad pin: ${e.message}")); return
        }
        val req = try {
            Request.Builder().url("${Cfg.url}/status?token=${Cfg.token}").build()
        } catch (e: Exception) {
            LogBus.add(tr("кривой адрес: ${e.message}", "bad address: ${e.message}")); return
        }
        LogBus.add(tr("проверяю ${Cfg.url} …", "checking ${Cfg.url} …"))
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { LogBus.add(tr("НЕТ СВЯЗИ: ${e.message}", "NO CONNECTION: ${e.message}")) }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()?.take(200)
                main.post { LogBus.add(tr("релей ответил: $body", "relay replied: $body")) }
            }
        })
    }

    // ── жизненный цикл ───────────────────────────────────────────────────
    private val logListener: () -> Unit = { main.post { renderLog() } }
    private val feedListener: () -> Unit = { main.post { refreshFeed(true) } }

    override fun onResume() {
        super.onResume()
        SessionBook.load(this)
        LogBus.listener = logListener
        Feed.listener = feedListener
        renderLog()
        BridgeService.instance?.ensureMicFgs()
        lastCircleKey = ""            // перекрасить круг после возврата
        main.removeCallbacks(ticker)
        main.post(ticker)
        refreshFeed(true)
        // v0.72: диагностика вёрстки в журнал — на скрине юзера между кругом и подсказкой
        // возникли необъяснимые дыры; эта строка покажет, кто реально сколько занял
        main.postDelayed({
            if (::feedScroll.isInitialized) {
                val col = rootFrame.getChildAt(0)
                LogBus.add(
                    tr("вёрстка: root=${rootFrame.height} pad=${rootFrame.paddingTop}/${rootFrame.paddingBottom}", "layout: root=${rootFrame.height} pad=${rootFrame.paddingTop}/${rootFrame.paddingBottom}") +
                        tr(" col=${col.height} лента=${feedScroll.height} плеер=${playerStrip.height}", " col=${col.height} feed=${feedScroll.height} player=${playerStrip.height}") +
                        tr(" круг=${circle.height} зона=${(circle.parent as View).height} док=${dockPause.height}", " circle=${circle.height} zone=${(circle.parent as View).height} dock=${dockPause.height}")
                )
            }
        }, 1600)
    }

    override fun onPause() {
        super.onPause()
        if (LogBus.listener === logListener) LogBus.listener = null
        if (Feed.listener === feedListener) Feed.listener = null
        main.removeCallbacks(ticker)
    }
}
