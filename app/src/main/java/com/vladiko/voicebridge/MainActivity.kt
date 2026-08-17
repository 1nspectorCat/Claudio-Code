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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
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
    private var lastMsgs: List<InMsg> = emptyList()
    private var lastRows: List<Row> = emptyList()
    private var lastOffline = false
    private var feedExpandAll = false            // v0.71: пузыри по 3 строки / все целиком
    private val feedExpanded = HashSet<String>() // точечные раскрытия тапом (XOR с общим)
    private val aliveMap = HashMap<String, Boolean>()
    private var sheetShowAll = false
    private var sheetMode = "channels"   // чем занята шторка: "channels" | "held"
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
            today -> "сегодня"
            yesterday -> "вчера"
            else -> java.text.SimpleDateFormat("d MMMM", java.util.Locale("ru")).format(java.util.Date(ts))
        }
    }

    private fun hhmm(ts: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ts))

    private fun timeAgo(ts: Long): String {
        if (ts <= 0) return "?"
        val s = (System.currentTimeMillis() - ts) / 1000
        return when {
            s < 60 -> "только что"
            s < 3600 -> "${s / 60} мин назад"
            s < 86400 -> "${s / 3600} ч назад"
            else -> "${s / 86400} дн назад"
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    @SuppressLint("UseSwitchCompatOrMaterialCode", "BatteryLife")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cfg.load(this)
        SessionBook.load(this)
        Feed.load(this)

        rootFrame = FrameLayout(this).apply { setBackgroundColor(cBg) }

        buildMainScreen()
        buildSheetOverlay()
        buildSettingsOverlay()
        buildDebugOverlay()
        buildOnboardOverlay()

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
            LogBus.add("ссылка-настройка неполная — нужны url и token")
            return
        }
        // Только https и только с хостом: битая ссылка не должна затирать рабочий конфиг
        if (!url.startsWith("https://") || Uri.parse(url).host.isNullOrEmpty()) {
            LogBus.add("ссылка-настройка отклонена: нужен https-адрес с хостом")
            return
        }
        // Та же самая ссылка второй раз — ничего не менять и не рестартовать сервис
        if (url == Cfg.url && token == Cfg.token && (pin.isEmpty() || pin == Cfg.pin)) {
            LogBus.add("ссылка-настройка: конфиг уже такой — ничего не меняю")
            if (::onboardOverlay.isInitialized) onboardOverlay.visibility = View.GONE
            return
        }
        // Ссылку может подсунуть любой сайт/QR (BROWSABLE): молчаливая замена релея на
        // настроенном телефоне = угон диктовки. Замена — только с подтверждением руками.
        if (Cfg.token.isNotEmpty() && (url != Cfg.url || token != Cfg.token)) {
            val host = Uri.parse(url).host ?: url
            LogBus.add("ссылка-настройка: прошу подтвердить замену релея на «${host}»")
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage("заменить настройки релея на «${host}»?\nвся диктовка пойдёт на этот сервер.")
                .setPositiveButton("заменить") { _, _ -> applyDeepLink(url, token, pin) }
                .setNegativeButton("отмена") { _, _ -> LogBus.add("замена релея отклонена") }
                .show()
            return
        }
        applyDeepLink(url, token, pin)
    }

    private fun applyDeepLink(url: String, token: String, pin: String) {
        Cfg.url = url
        Cfg.token = token
        if (pin.isNotEmpty()) Cfg.pin = pin
        Cfg.save(this)
        if (::urlEdit.isInitialized) {
            urlEdit.setText(Cfg.url); tokenEdit.setText(Cfg.token); pinEdit.setText(Cfg.pin)
        }
        LogBus.add("конфиг пришёл по ссылке-настройке — сохранён")
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
            contentDescription = "текущий канал — тап открывает список"
            setOnClickListener { openSheet() }
            // наследник старой кнопки «слушать крайнее»: свежайшее сообщение ЛЮБОЙ сессии
            setOnLongClickListener {
                LogBus.add("чип (долгий тап) — играю крайнее отовсюду")
                ensureService { it.playLatestEverywhere() }
                true
            }
        }
        muteBtn = tv("🔇", 16f, cDim).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            contentDescription = "заглушить или включить микрофон"
            setOnClickListener {
                val svc = BridgeService.instance
                if (svc == null || !BridgeService.running) { LogBus.add("рация выключена"); return@setOnClickListener }
                svc.toggleMicMute()
            }
        }
        val gear = tv("⚙", 18f, cDim).apply {
            setPadding(dp(10), dp(4), dp(4), dp(4))
            contentDescription = "настройки"
            setOnClickListener { settingsOverlay.visibility = View.VISIBLE }
        }
        // v0.71: раскрыть/свернуть ВСЮ переписку одним тапом (пузыри по умолчанию — 3 строки)
        val expandAllBtn = tv("⤢", 16f, cFaint).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            contentDescription = "раскрыть или свернуть всю переписку"
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
                    LogBus.add("накопленное: " + br.joinToString(", ") { "${it.second} ${it.third} ч." })
                    // список показываем ВСЕГДА (слово юзера: «он даже не показывает список»):
                    // видно, чьи ответы ждут, и звук начинается только по твоей команде
                    if (br.isEmpty()) LogBus.add("накопленного нет — играть нечего") else openHeldSheet(br)
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
        val bBack = stripBtn("−10с", "назад на 10 секунд") { BridgeService.instance?.nudgeMs(-10000) }
        val bFwd = stripBtn("+10с", "вперёд на 10 секунд") { BridgeService.instance?.nudgeMs(10000) }
        val bSlow = stripBtn("−", "медленнее") {
            speedLabel.text = "%.1f×".format(BridgeService.instance?.nudgeSpeed(-0.1f) ?: Cfg.speed)
        }
        val bFast = stripBtn("+", "быстрее") {
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
        circleBig = tv("говорить", 15f, cBg, bold = true).apply { gravity = Gravity.CENTER }
        circleSub = tv("", 9.5f, cBg).apply { gravity = Gravity.CENTER; alpha = 0.75f }
        circle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = circleBg(cPlay, cAccSoft)
            addView(circleBig)
            addView(circleSub)
            contentDescription = "тангента: говорить, отправить, прервать"
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
                    if (Cfg.autoSend) "режим отправки: таймер тишины включён"
                    else "режим отправки: только «отправляй», кнопка и тангента"
                )
                true
            }
        }
        cancelBtn = tv("✕ отмена", 11f, cDim).apply {
            background = bordered(10)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = View.GONE
            contentDescription = "стереть черновик"
            setOnClickListener { BridgeService.instance?.onCancel() }
        }
        // v0.71: круг во время озвучки ставит паузу — «прервать и говорить» живёт здесь
        talkBtn = tv("🎙 прервать и говорить", 11f, cDim).apply {
            background = bordered(10)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = View.GONE
            contentDescription = "оборвать озвучку и диктовать"
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
        dockPause = dockBtn("⏸ пауза", "пауза и продолжение") {
            val svc = BridgeService.instance
            if (svc == null || !BridgeService.running) { LogBus.add("рация выключена"); return@dockBtn }
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
        val dockCh = dockBtn("каналы", "список каналов") { openSheet() }
        val dockLog = dockBtn("журнал", "журнал и отладка") { debugOverlay.visibility = View.VISIBLE; renderLog() }
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((i, b) in listOf(dockPause, dockCh, dockLog).withIndex()) {
                addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(8) })
            }
        }

        val gap = { h: Int -> LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(h) } }
        col.addView(head)
        col.addView(feedScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        col.addView(bannerBtn, gap(6))
        col.addView(playerStrip, gap(6))
        col.addView(pttZone, gap(2))
        col.addView(dock, gap(8))
        rootFrame.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    // ── тангента ─────────────────────────────────────────────────────────
    private fun circleTap() {
        if (Cfg.token.isEmpty()) { onboardOverlay.visibility = View.VISIBLE; return }
        LogBus.add("нажат круг")
        ensureService { svc ->
            val bound = svc.boundaryReplySid()
            when {
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
            LogBus.add("долгий тап — выключаю рацию")
            stopService(Intent(this, BridgeService::class.java))
        } else {
            LogBus.add("долгий тап — включаю рацию")
            ensureService { }
        }
    }

    private fun ensureService(then: (BridgeService) -> Unit) {
        val svc = BridgeService.instance
        if (svc != null && BridgeService.running) { then(svc); return }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            LogBus.add("нет разрешения на микрофон — выдай его в окне запроса")
            askPermissions()
            return
        }
        LogBus.add("рация была выключена — запускаю")
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
            main.postDelayed(this, 500)
        }
    }

    private fun paint() {
        val svc = BridgeService.instance
        val running = svc != null && BridgeService.running
        val st = if (!running) "" else svc!!.statusText()

        // канал-чип
        val name = if (running) svc!!.replyTargetName() else feedName()
        chipBtn.text = "▾ " + name + (if (Cfg.pickedSids.isNotEmpty()) "  ·  " + Cfg.pickedSids.split(",").count { it.isNotBlank() } + " в работе" else "")

        // микрофон-мьют в шапке
        val muted = running && svc!!.isMicMuted()
        muteBtn.setTextColor(if (muted) cPlay else cFaint)
        muteBtn.text = if (muted) "🔇 выкл" else "🔇"

        // круг
        val draft = st.startsWith("СЛУШАЮ · есть черновик")
        val playingWho = st.substringAfter("ИГРАЮ · ", "").ifEmpty { "ответ" }
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
                big = "выключена"; sub = "тап — включить" }
            // v0.75: зазор между каналами — круг предлагает ответить ТОМУ, кто только что
            // договорил, пока следующий канал ждёт своей очереди
            boundSid.isNotEmpty() -> { key = "bound$boundSid"; inner = cPlay; ring = cAccSoft; fg = cBg
                big = "ответить"; sub = svc!!.lastSpokenName() }
            st.startsWith("НЕТ СВЯЗИ") -> { key = "nolink"; inner = cWarn; ring = 0x14FFFFFF; fg = cText
                big = "нет связи"; sub = "переподключаюсь…" }
            svc!!.isPaused() -> { key = "paused"; inner = cWarn; ring = 0x14FFFFFF; fg = cText
                big = "пауза"; sub = "тап — дальше" }
            svc.isPlaying() -> { key = "play$playingWho"; inner = cLive; ring = cAccSoft; fg = cBg
                big = "играю"; sub = playingWho }
            draft -> { key = "draft"; inner = cPlay; ring = cAccSoft; fg = cBg
                big = "слушаю"; sub = "тап — отправить" }
            st.startsWith("СЛУШАЮ") -> { key = "listen"; inner = cPlay; ring = cAccSoft; fg = cBg
                big = "слушаю"; sub = "говори" }
            // dictHold: и пауза диктовки, и «не слушать» — движок их не различает,
            // подпись честно-нейтральная (черновик, если был, цел)
            svc.hasPendingDictation() -> { key = "micoff"; inner = cMic; ring = cAccSoft; fg = cText
                big = "микрофон спит"; sub = "тап — включить" }
            st.startsWith("ОТПРАВЛЕНО") -> { key = "sent"; inner = cCard; ring = cAccSoft; fg = cText
                big = "ушло ✓"; sub = "тап — сказать ещё" }
            st.startsWith("ЖДУ НАУШНИКИ") -> { key = "waitHs"; inner = cWarn; ring = 0x14FFFFFF; fg = cText
                big = "жду наушники"; sub = "ответы копятся" }
            else -> { key = "idle"; inner = cCard; ring = 0x14FFFFFF; fg = cText
                big = "на связи"; sub = "тап — говорить" }
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
            !running -> "долгий тап по кругу — вкл и выкл рации"
            Cfg.token.isEmpty() -> "рация не настроена — открой ⚙"
            !svc!!.isListening() && svc.boundaryReplySid().isNotEmpty() ->
                "12с на ответ · дальше «" + svc.boundaryNextName() + "» · тап — ответить"
            svc.isPaused() -> "тап по кругу — продолжить с того же места"
            svc.isPlaying() -> "тап по кругу — пауза с этого места"
            st.startsWith("СЛУШАЮ") && Cfg.autoSend -> "замолчи или скажи «отправляй» — уйдёт само"
            st.startsWith("СЛУШАЮ") -> "скажи «отправляй» — таймеров тишины нет"
            st.startsWith("ЖДУ НАУШНИКИ") -> "надень наушники или выключи «только в наушниках»"
            st.startsWith("ОТПРАВЛЕНО") -> "микрофон спит — кнопка гарнитуры тоже будит"
            else -> "долгий тап — выключить рацию"
        }
        cancelBtn.visibility =
            if (running && (svc!!.isListening() || svc.hasPendingDictation())) View.VISIBLE else View.GONE
        talkBtn.visibility =
            if (running && (svc!!.isPlaying() || svc.isPaused())) View.VISIBLE else View.GONE

        // плашка «есть ответы»
        val held = if (running) svc!!.heldCount() else 0
        when {
            held > 0 -> {
                bannerBtn.text = "есть ответы ($held) — послушать ▶"
                bannerBtn.background = rounded(cPlay, 12)
                bannerBtn.setTextColor(cBg)
                bannerBtn.visibility = View.VISIBLE
            }
            else -> bannerBtn.visibility = View.GONE
        }

        // док: подпись строго согласована с действием клика (ревью v0.69 — «▶ дальше»
        // при играющей озвучке делала паузу); в покое кнопка честно становится повтором
        dockPause.text = when {
            !running -> "⏸ пауза"
            svc!!.isPaused() || (svc.hasPendingDictation() && !svc.isPlaying()) -> "▶ дальше"
            svc.isPlaying() || svc.isListening() -> "⏸ пауза"
            else -> "↻ повтор"
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
            playerLabel.text = (if (svc.isPaused()) "пауза · " else "играю · ") + fmt(pos) + " / " + fmt(dur)
            val cur = "%.1f×".format(Cfg.speed)
            if (speedLabel.text != cur) speedLabel.text = cur
        }

        // отладка, если открыта
        if (debugOverlay.visibility == View.VISIBLE) {
            debugTech.text = "состояние: " + (if (running) st else "СЕРВИС ВЫКЛЮЧЕН") +
                "\nадресат: " + (if (running) svc!!.replyTargetName() else "—") +
                (if (Cfg.pickedSids.isNotEmpty()) " · в работе: " + (svc?.pickedNames() ?: "") else "") +
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
        if (Cfg.replyTarget.isNotEmpty()) return Cfg.replyTarget
        if (Cfg.pickedSids.isNotEmpty())
            return Cfg.pickedSids.split(",").first().trim()
        // сервис выключен: движок персистит lastSession в prefs при каждом воспроизведении
        val last = getSharedPreferences("bridge", MODE_PRIVATE).getString("lastSession", "") ?: ""
        if (last.isNotEmpty()) return last
        return SessionBook.all().maxByOrNull { it.lastTs }?.sid.orEmpty()
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
                    rows.add(Row(true, m.ctx, m.ts - 1, "ушло ✓", null, "c${m.msgid}"))
                }
                prevCtx = k
            } else {
                prevCtx = ""   // ответ без ctx разрывает «соседство» — повтор после него легитимен
            }
            val txt = m.texts.map { it.second }.filter { it.isNotBlank() }.joinToString(" ").trim()
            val secs = (m.bytes / 2500).coerceAtLeast(1)
            val durTxt = if (secs >= 60) "%d:%02d".format(secs / 60, secs % 60) else "${secs}с"
            rows.add(Row(false, txt.ifEmpty { "(голосовое, ~$durTxt)" }, m.ts, "", m, "i${m.msgid}"))
        }
        for (e in outs) {
            // relay режет текст utt_sent (с v0.71 — до 1000) — обрез не должен выглядеть потерей
            val text = if (e.text.length >= 1000) e.text + "…" else e.text
            val key = "o${e.ts}:${e.text.hashCode()}"
            val owner = ctxOwner[norm(e.text)]?.removeFirstOrNull()
            if (owner != null) {
                rows.add(Row(true, text, owner.ts - 1, "ушло ✓", null, key))
            } else {
                rows.add(Row(true, text, e.ts, if (e.unread) "⚠ канал не слушал" else "ушло ✓", null, key))
            }
        }
        rows.sortBy { it.ts }
        return if (rows.size > 120) rows.subList(rows.size - 120, rows.size) else rows
    }

    private fun renderFeed(rows: List<Row>, offline: Boolean, force: Boolean = false) {
        lastRows = rows
        lastOffline = offline
        val sig = rows.joinToString("|") { "${it.ts}:${it.out}:${it.text.hashCode()}:${it.note}" } +
            (if (offline) "#off" else "") + (if (feedExpandAll) "#xall" else "")
        if (!force && sig == lastFeedSig) return
        // юзер отлистал вверх читать старое — не дёргать ленту под пальцем; перерисуем,
        // когда он вернётся вниз (см. слушатель прокрутки). force = явное действие юзера.
        if (!force && !feedStick && feedBox.childCount > 0) {
            pendingRows = rows; pendingOffline = offline
            return
        }
        pendingRows = null
        lastFeedSig = sig
        feedBox.removeAllViews()
        if (rows.isEmpty()) {
            val msg = when {
                Cfg.token.isEmpty() -> "рация не настроена.\nоткрой первый запуск (⚙) или отсканируй QR со своего сервера"
                offline -> "нет связи с релеем — лента появится, когда связь вернётся"
                else -> "пока тихо.\nнажми круг и скажи что-нибудь — разговор появится здесь"
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
            val who = if (r.out) "ты · ${hhmm(r.ts)}" + (if (r.note.isNotEmpty()) " · ${r.note}" else "")
            else "${r.inMsg?.proj?.ifEmpty { "канал" } ?: "канал"} · ${hhmm(r.ts)}"
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
                    contentDescription = "послушать это сообщение"
                    setOnClickListener { playMsg(m, fromHere = false) }
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }
            val body = tv(r.text, 13f, cText).apply {
                maxWidth = maxW
                setPadding(0, dp(2), 0, 0)
            }
            applyExpand(body, feedExpandAll != (r.key in feedExpanded))
            bub.addView(capRow)
            bub.addView(body)
            bub.setOnClickListener {
                if (!feedExpanded.add(r.key)) feedExpanded.remove(r.key)
                applyExpand(body, feedExpandAll != (r.key in feedExpanded))
            }
            if (!r.out && r.inMsg != null) {
                val m = r.inMsg
                bub.setOnLongClickListener { playMsg(m, fromHere = true); true }
            }
            feedBox.addView(bub, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = if (r.out) Gravity.END else Gravity.START
                topMargin = dp(6)
            })
        }
        feedBox.addView(tv("тап — раскрыть/свернуть · ▶ — послушать · долгий — отсюда и дальше", 9.5f, cFaint).apply {
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
        if (items.isEmpty()) { LogBus.add("у сообщения нет аудио"); return }
        LogBus.add(if (fromHere) "лента: играю с выбранного места (${items.size} ч.)" else "лента: играю сообщение")
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
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 0, 1f))
            // v1.06: вернуть в список каналы, удалённые долгим тапом
            addView(tv("↺ вернуть", 12.5f, cDim).apply {
                background = bordered(10)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                contentDescription = "вернуть удалённые каналы"
                setOnClickListener {
                    SessionBook.unforgetAll(this@MainActivity)
                    lastSessionsFetch = 0L
                    fetchSessions()
                    LogBus.add("метки удаления сняты — каналы вернутся из списка сервера")
                }
            })
            // v1.03 (слово юзера: «не вижу кнопку обновления списка каналов»)
            addView(tv("↻ обновить", 12.5f, cDim).apply {
                val lp0 = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                lp0.leftMargin = dp(8)
                layoutParams = lp0
                background = bordered(10)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener {
                    lastSessionsFetch = 0L
                    fetchSessions()
                    LogBus.add("список каналов обновляется с сервера")
                }
            })
            addView(tv("✕ закрыть", 12.5f, cDim).apply {
                background = bordered(10)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                lp.leftMargin = dp(8)
                layoutParams = lp
                setOnClickListener { sheetOverlay.visibility = View.GONE }
            })
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
        sheetList.addView(tv("накопленные ответы", 12f, cFaint))
        for ((sid, proj, n) in br) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(11), dp(4), dp(11))
            }
            row.addView(tv("$proj · $n ч.", 13f, cText).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(tv("▶ слушать", 12f, cPlay).apply {
                setPadding(dp(8), dp(2), dp(8), dp(2))
                setOnClickListener {
                    BridgeService.instance?.playHeldFor(sid)
                    sheetOverlay.visibility = View.GONE
                }
            })
            row.addView(tv("🎙", 13f, cDim).apply {
                setPadding(dp(10), dp(2), dp(2), dp(2))
                contentDescription = "ответить в этот канал"
                setOnClickListener {
                    sheetOverlay.visibility = View.GONE
                    ensureService { it.replyToSession(sid) }
                }
            })
            row.addView(tv("✕", 14f, cFaint).apply {
                setPadding(dp(10), dp(2), dp(4), dp(2))
                contentDescription = "пропустить ответы этого канала"
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
        sheetList.addView(tv("все подряд ▶", 12.5f, cDim).apply {
            setPadding(dp(4), dp(12), dp(4), dp(6))
            setOnClickListener {
                BridgeService.instance?.playHeldAll()
                sheetOverlay.visibility = View.GONE
            }
        })
        sheetList.addView(tv("▶ слушать · 🎙 отвечать · ✕ пропустить (остаются в ленте)", 9.5f, cFaint).apply {
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
            try {
                if (raw != null) {
                    val arr = JSONObject(raw).optJSONArray("sessions")
                    if (arr != null) for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        parsed.add(Triple(o.getString("sid"), o.optString("proj"), o.optLong("lastTs")))
                        aliveMap[o.getString("sid")] = o.optBoolean("alive")
                    }
                }
            } catch (_: Exception) {}
            main.post {
                for (t in parsed) SessionBook.seen(this, t.first, t.second, t.third)
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
        LogBus.add("адресат откреплён — отвечаю в последнюю игравшую")
    }

    private fun rebuildSheet() {
        sheetList.removeAllViews()
        val pickedNow = BridgeService.instance?.pickedNames().orEmpty()
        sheetList.addView(tv(
            if (pickedNow.isEmpty()) "каналы · слышу все" else "в работе: $pickedNow",
            12f, if (pickedNow.isEmpty()) cPlay else cPlay
        ))
        val list = SessionBook.all()
        if (list.isEmpty()) {
            sheetList.addView(tv("(ещё не было ни одной озвучки)", 12f, cFaint).apply {
                setPadding(0, dp(10), 0, dp(10))
            })
        }
        val prefs = getSharedPreferences("bridge", MODE_PRIVATE)
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
                e.muted -> { stTxt = "выключен"; stCol = cFaint }
                deaf -> { stTxt = "не слушает"; stCol = cWarn }
                working -> { stTxt = "работает" + (if (mins > 0) " · $mins мин" else ""); stCol = cPlay }
                else -> { stTxt = "ответил · " + timeAgo(e.lastTs); stCol = cFaint }
            }
            // v0.82 (вопрос юзера: «объясни, что значат иконки»): подписи СЛОВАМИ.
            // Значки на ходу нечитаемы и требуют памяти — слово объясняет себя само.
            val replyBtn = tv("ответить", 11.5f, if (target) cPlay else cDim).apply {
                background = bordered(8)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                contentDescription = "отвечать в этот канал прямо сейчас"
                setOnClickListener {
                    sheetOverlay.visibility = View.GONE
                    ensureService { it.replyToSession(e.sid) }
                    refreshFeed(true)
                }
            }
            // v0.82 (слово юзера: «хочу выбрать пару каналов из пяти и ВИДЕТЬ, какие»).
            // Набор: пусто = слышу всех; иначе слышны ровно отмеченные.
            val soloBtn = tv(if (solo) "✓ в работе" else "в работу", 11.5f, if (solo) cPlay else cDim).apply {
                background = if (solo) rounded(cAccSoft, 8) else bordered(8)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                contentDescription = "добавить канал в работу или убрать из неё"
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
                    LogBus.add("отвечаю теперь в «${e.proj.ifEmpty { e.sid.take(8) }}»")
                }
                sheetOverlay.visibility = View.GONE
                refreshFeed(true)
            }
            // v0.82 (слово юзера про тестовые каналы: «удаляй их, пусть не мозолят глаза»)
            row.setOnLongClickListener {
                if (BridgeService.instance?.picked()?.contains(e.sid) == true)
                    BridgeService.instance?.togglePicked(e.sid)
                SessionBook.forget(this, e.sid)
                LogBus.add("канал убран из списка: ${e.proj.ifEmpty { e.sid.take(8) }}")
                rebuildSheet()
                true
            }
            sheetList.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            sheetList.addView(View(this).apply { setBackgroundColor(cLine) },
                LinearLayout.LayoutParams(MATCH_PARENT, 1))
        }
        if (!sheetShowAll && list.size > 8) {
            sheetList.addView(tv("ещё ${list.size - 8}…", 12.5f, cDim).apply {
                setPadding(dp(4), dp(11), dp(4), dp(6))
                setOnClickListener { sheetShowAll = true; rebuildSheet() }
            })
        }
        val allRow = tv("слышать все каналы · сбросить выбор", 12.5f, cDim).apply {
            setPadding(dp(4), dp(12), dp(4), dp(6))
            setOnClickListener {
                exitSolo()
                sheetOverlay.visibility = View.GONE
                refreshFeed(true)
            }
        }
        sheetList.addView(allRow)
        sheetList.addView(tv(
            "«в работу» — кого слышу (можно несколько каналов)\n" +
                "отвечаю всегда тому, кто говорил последним · «ответить» — сказать ЭТОМУ,\n" +
                "на одно сообщение · долгий тап по строке — убрать канал из списка",
            9.5f, cFaint
        ).apply {
            setPadding(dp(4), dp(4), dp(4), 0)
        })
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
        col.addView(tv("‹ настройки", 15f, cDim, bold = true).apply {
            setPadding(0, dp(6), 0, dp(12))
            setOnClickListener { settingsOverlay.visibility = View.GONE }
        })

        // v1.04 (слово юзера: «надел наушники — работаю в них, снял дома — с телефона»)
        col.addView(switchRow("сам следовать за наушниками",
            "надел — звук в них, снял — вслух; ничего не переключать руками",
            { Cfg.autoRoute }, { Cfg.autoRoute = it }))
        col.addView(divider(), LinearLayout.LayoutParams(MATCH_PARENT, 1))
        col.addView(switchRow("только в наушниках", "если авто-режим выключен: без наушников ответы ждут",
            { Cfg.onlyHeadset }, { Cfg.onlyHeadset = it }))
        col.addView(divider(), LinearLayout.LayoutParams(MATCH_PARENT, 1))
        col.addView(switchRow("называть канал перед ответом", null,
            { Cfg.announceProj }, { Cfg.announceProj = it }))
        col.addView(divider(), LinearLayout.LayoutParams(MATCH_PARENT, 1))
        col.addView(switchRow("голосовые подсказки", "«слушаю», «ушло», «связь потеряна»",
            { Cfg.voiceCues }, { Cfg.voiceCues = it }))
        col.addView(divider(), LinearLayout.LayoutParams(MATCH_PARENT, 1))
        col.addView(switchRow("отправка по тишине", "замолчал — ушло само; выкл = только «отправляй» и кнопки",
            { Cfg.autoSend }, { Cfg.autoSend = it }, expose = { autoSendSwitchRef = it }))
        col.addView(divider(), LinearLayout.LayoutParams(MATCH_PARENT, 1))

        advBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(6), 0, 0, 0)
        }
        col.addView(linkRow("для продвинутых", "whisper · связь · таймеры · кнопка гарнитуры") {
            advBox.visibility = if (advBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        col.addView(advBox)
        col.addView(divider(), LinearLayout.LayoutParams(MATCH_PARENT, 1))
        col.addView(linkRow("отладка", "журнал и диагностика — всё, что видим мы") {
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

        advBox.addView(advLabel("связь с релеем"))
        advBox.addView(tv("релей — твой сервер-мост. проще всего: ссылка-настройка или «адрес|токен|отпечаток» из буфера", 10f, cFaint))
        urlEdit = EditText(this).apply {
            setText(Cfg.url); hint = "https://адрес:порт"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        tokenEdit = EditText(this).apply {
            setText(Cfg.token); hint = "токен релея"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        pinEdit = EditText(this).apply {
            setText(Cfg.pin); hint = "SHA-256 отпечаток сертификата"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        for (e in listOf(urlEdit, tokenEdit, pinEdit)) {
            e.setTextColor(cText); e.setHintTextColor(cFaint); e.textSize = 13f
            e.typeface = Typeface.MONOSPACE
            e.backgroundTintList = android.content.res.ColorStateList.valueOf(cDim)
            advBox.addView(e)
        }
        advBox.addView(advBtn("вставить конфиг из буфера") { pasteConfig() },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        advBox.addView(advBtn("сохранить и перезапустить") {
            saveFields()
            if (BridgeService.running) restartService() else LogBus.add("настройки сохранены")
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        advBox.addView(advBtn("тест связи") { testRelay() },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })

        advBox.addView(advLabel("режимы"))
        advBox.addView(switchRow("безрукий режим", "микрофон всегда включён, отправка словом",
            { Cfg.handsFree }, { on ->
                Cfg.handsFree = on
                BridgeService.instance?.let { s -> if (on) s.startListening() else s.stopListening() }
            }))
        advBox.addView(switchRow("распознавание whisper", "на твоём сервере; лучше в шуме, нужен whisper-бэкенд",
            { Cfg.whisper }, { on ->
                Cfg.whisper = on
                BridgeService.instance?.let { s ->
                    if (s.isListening()) {
                        s.onWhisperToggled(on)
                        s.stopListening(); s.startListening()
                    }
                }
            }))
        advBox.addView(switchRow("разговорный источник записи", "выкл = «распознавание»: проверка кнопки гарнитуры",
            { Cfg.micVoiceComm }, { on ->
                Cfg.micVoiceComm = on
                LogBus.add("источник записи: " + (if (on) "разговорный" else "распознавание") +
                    " — со следующей диктовки")
            }))
        advBox.addView(switchRow("кнопка гарнитуры с первого нажатия", "по следу обрыва канала (без whisper)",
            { Cfg.routeAsButton }, { on ->
                Cfg.routeAsButton = on
                LogBus.add(if (on) "кнопка: ловлю первое нажатие по обрыву канала"
                    else "кнопка: как раньше — отправит второе нажатие")
            }))
        advBox.addView(switchRow("догонять пропущенное", "при подключении — за последние 30 мин",
            { Cfg.catchup }, { Cfg.catchup = it }))

        advBox.addView(advLabel("прочее"))
        advBox.addView(advBtn("разрешить работу в фоне (батарея)") {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (pm.isIgnoringBatteryOptimizations(packageName)) {
                    LogBus.add("фоновая работа уже разрешена")
                } else if (BuildConfig.FLAVOR == "store") {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    LogBus.add("найди это приложение в списке и разреши работу в фоне")
                } else {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                }
            } catch (e: Exception) {
                LogBus.add("не открылось: ${e.message}")
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        serviceBtn = advBtn("") {
            if (BridgeService.running) stopService(Intent(this, BridgeService::class.java))
            else ensureService { }
            main.postDelayed({ refreshServiceBtn() }, 600)
        }
        refreshServiceBtn()
        advBox.addView(serviceBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        advBox.addView(advBtn("показать первый запуск") {
            settingsOverlay.visibility = View.GONE
            onboardOverlay.visibility = View.VISIBLE
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    private fun refreshServiceBtn() {
        if (::serviceBtn.isInitialized)
            serviceBtn.text = if (BridgeService.running) "остановить сервис" else "запустить сервис"
    }

    private fun restartService() {
        LogBus.add("перезапускаю сервис с новыми настройками")
        stopService(Intent(this, BridgeService::class.java))
        main.postDelayed({
            try {
                startForegroundService(Intent(this, BridgeService::class.java))
            } catch (e: Exception) {
                LogBus.add("не смог перезапустить сервис: ${e.message?.take(60)}")
            }
        }, 600)
    }

    // ── отладка: журнал + диагностика ────────────────────────────────────
    private fun buildDebugOverlay() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(24))
        }
        col.addView(tv("‹ отладка", 15f, cDim, bold = true).apply {
            setPadding(0, dp(6), 0, dp(10))
            setOnClickListener { debugOverlay.visibility = View.GONE }
        })
        debugTech = tv("", 11f, cDim).apply {
            background = rounded(cCard, 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        col.addView(debugTech)
        col.addView(tv("журнал (свежее сверху)", 10.5f, cFaint).apply { setPadding(0, dp(12), 0, dp(4)) })
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
        col.addView(tv("первый запуск", 17f, cText, bold = true))
        col.addView(tv("рация говорит с ТВОИМ сервером — общего облака нет", 11f, cDim).apply {
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
        col.addView(step("шаг 1 · сервер",
            "запусти на своём сервере одну команду — она поставит мост и напечатает ссылку-настройку и QR-код.",
            "bash install-relay.sh"), gap(0))
        col.addView(step("шаг 2 · телефон",
            "отсканируй QR камерой (или открой ссылку claudio://…) — адрес, ключ и отпечаток встанут сами. печатать ничего не надо.",
            null), gap(10))
        col.addView(step("шаг 3 · проверка",
            "нажми круг и скажи «привет». ответ прозвучит в наушнике.",
            null), gap(10))

        col.addView(tv("если ссылки нет — вставь конфиг вручную:", 10.5f, cFaint).apply {
            setPadding(dp(2), dp(14), 0, dp(4))
        })
        col.addView(Button(this).apply {
            text = "вставить из буфера (адрес|токен|пин)"
            textSize = 13f; isAllCaps = false
            typeface = Typeface.MONOSPACE
            setTextColor(cText); background = rounded(cCard, 10); stateListAnimator = null
            setOnClickListener { pasteConfig() }
        })
        col.addView(Button(this).apply {
            text = "начать разговор"
            textSize = 15f; isAllCaps = false
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(cBg); background = rounded(cPlay, 14); stateListAnimator = null
            setPadding(dp(10), dp(14), dp(10), dp(14))
            setOnClickListener {
                if (Cfg.token.isEmpty()) {
                    LogBus.add("сначала настрой релей — шаги выше")
                } else {
                    onboardOverlay.visibility = View.GONE
                    ensureService { it.talkNow() }
                }
            }
        }, gap(12))
        col.addView(tv("настрою позже — просто посмотреть", 11f, cFaint).apply {
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
            if (txt.isEmpty()) { LogBus.add("буфер пуст"); return }
            if (txt.startsWith("{")) {
                val o = JSONObject(txt)
                if (o.has("url")) urlEdit.setText(o.getString("url"))
                if (o.has("token")) tokenEdit.setText(o.getString("token"))
                if (o.has("pin")) pinEdit.setText(o.getString("pin"))
            } else if (txt.startsWith("claudio://")) {
                val uu = Uri.parse(txt)
                if (!"setup".equals(uu.host, true)) {
                    LogBus.add("не разобрал ссылку — нужен вид claudio://setup?url=…&token=…")
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
                        LogBus.add("пин не найден в строке — впиши отпечаток сертификата вручную")
                    }
                    else -> {
                        LogBus.add("не разобрал строку — нужен формат адрес|токен|пин")
                        return
                    }
                }
            } else {
                tokenEdit.setText(txt)
            }
            saveFields()
            LogBus.add("конфиг вставлен и сохранён")
            if (Cfg.token.isNotEmpty()) onboardOverlay.visibility = View.GONE
        } catch (e: Exception) {
            LogBus.add("не разобрал буфер: ${e.message}")
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
            LogBus.add("кривой пин: ${e.message}"); return
        }
        val req = try {
            Request.Builder().url("${Cfg.url}/status?token=${Cfg.token}").build()
        } catch (e: Exception) {
            LogBus.add("кривой адрес: ${e.message}"); return
        }
        LogBus.add("проверяю ${Cfg.url} …")
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { LogBus.add("НЕТ СВЯЗИ: ${e.message}") }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()?.take(200)
                main.post { LogBus.add("релей ответил: $body") }
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
                    "вёрстка: root=${rootFrame.height} pad=${rootFrame.paddingTop}/${rootFrame.paddingBottom}" +
                        " col=${col.height} лента=${feedScroll.height} плеер=${playerStrip.height}" +
                        " круг=${circle.height} зона=${(circle.parent as View).height} док=${dockPause.height}"
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
