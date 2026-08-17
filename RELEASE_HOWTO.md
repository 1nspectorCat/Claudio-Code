# Как выложить это на GitHub (пошагово, для первого раза)

Всё бесплатно, аккаунт GitHub у тебя уже есть. Времени — минут двадцать.

## 0. Что публикуем и что НЕ публикуем

Публикуем папку `D:\bot\voicebridge_public` — она собрана с нуля и проверена: ни адреса
твоего сервера, ни токена, ни отпечатка сертификата, ни путей с твоим именем.

**Рабочий репозиторий `D:\bot\voicebridge_app` публиковать НЕЛЬЗЯ**: в его истории
коммитов лежат адрес релея и отпечаток. Удалить их из истории сложно, а публиковать
как есть — раздать свой сервер посторонним.

## 1. Создать репозиторий

1. Открой https://github.com/new
2. Repository name: `claudio-code` (или `voicebridge`)
3. Description: `Voice walkie-talkie for Claude Code sessions — hands-free, multi-session, self-hosted`
4. Public, **без** галочек «Add README / .gitignore / license» — они у нас свои.
5. Create repository.

## 2. Залить файлы

В терминале:

```bash
cd /d/bot/voicebridge_public
git init -b main
git add .
git commit -m "Claudio Code: voice walkie-talkie for Claude Code sessions"
git remote add origin https://github.com/<ТВОЙ_ЛОГИН>/claudio-code.git
git push -u origin main
```

Если попросит логин — вместо пароля нужен токен: Settings → Developer settings →
Personal access tokens → Fine-grained → доступ к этому репозиторию, права Contents: Read
and write.

## 3. Выложить готовый APK

1. На странице репозитория: Releases → Create a new release.
2. Tag: `v1.08`, Title: `Claudio Code v1.08`.
3. Прикрепить файл: `D:\bot\voicebridge_app\app\build\outputs\apk\personal\debug\ClaudioCode-1.08.apk`
   **ВАЖНО:** это ЛИЧНАЯ сборка с твоим сервером внутри. Для публики нужна сборка
   `store`: `gradle assembleStoreDebug` → `app/build/outputs/apk/store/debug/`. Она
   собирается с пустыми настройками, человек вводит свои.
4. Текст релиза — короткий список того, что умеет (можно взять из README).

## 4. Чтобы находили

В настройках репозитория (шестерёнка рядом с About) добавь **topics**:

```
claude-code · claude · voice-assistant · android · speech-to-text · whisper-cpp ·
hands-free · walkie-talkie · self-hosted · kotlin · ai-agents · voice-control
```

Это главный способ поиска на GitHub. Плюс заполни поле About одной строкой:
`Talk to your Claude Code sessions by voice, hands-free, from your phone.`

## 5. Где ещё об этом уместно сказать

- r/ClaudeAI на Reddit — там регулярно спрашивают про мобильный доступ к Claude Code.
- Anthropic Discord, канал по Claude Code.
- Hacker News (Show HN) — если захочешь, но там жёстко: нужен готовый README и
  честный раздел про ограничения (он у нас есть).

Формулируй как есть: «сделал для себя, чтобы вести сессии голосом на ходу; ставится на
свой сервер; вот ограничения». Такие посты принимают лучше, чем обещания.

## 6. Что сказать про приватность

В README это уже написано, но повтори в посте: общего облака нет, звук не уходит
никуда, кроме твоего сервера, токен и сертификат — твои.
