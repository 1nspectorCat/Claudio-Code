package com.vladiko.voicebridge

import java.util.Locale

// Язык по флейвору: store (публичная сборка) — английский, personal и hubtest — прежний русский.
// Строки остаются на месте вызова парой tr("рус", "eng"): русская половина — ровно прежний текст,
// поэтому личная сборка не меняется ни в звуке, ни на экране. Логика не ветвится по языку —
// только текст; места, где фраза служит ключом (кэш реплик, startsWith), берут ту же пару.
object Lang {
    val en: Boolean = BuildConfig.FLAVOR == "store"
    val locale: Locale = if (en) Locale.ENGLISH else Locale("ru")
    val ttsLocale: Locale = if (en) Locale.US else Locale("ru", "RU")
    val recognizerTag: String = if (en) "en-US" else "ru-RU"
}

fun tr(ru: String, en: String): String = if (Lang.en) en else ru
