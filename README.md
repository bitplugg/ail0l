# AIL0L — ИИ-агент, который развивается вместе с тобой

[![GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![commits](https://img.shields.io/github/commit-activity/m/bitplugg/ail0l)](https://github.com/bitplugg/ail0l/commits)
[![stars](https://img.shields.io/github/stars/bitplugg/ail0l)](https://github.com/bitplugg/ail0l/stargazers)
[![release](https://img.shields.io/github/v/release/bitplugg/ail0l)](https://github.com/bitplugg/ail0l/releases)
[![platform](https://img.shields.io/badge/platform-Android-green.svg)](https://developer.android.com)
[![llama.cpp](https://img.shields.io/badge/llama.cpp-ggml--org-orange)](https://github.com/ggml-org/llama.cpp)

Android-приложение (Kotlin, Jetpack Compose) на базе **llama.cpp**
([ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp)): GGUF-модели
запускаются прямо на устройстве. Агент умеет запоминать факты о тебе, искать по
своей памяти и в интернете, обмениваться сообщениями с другими устройствами
(чат-канал), озвучивать ответы и слушать речь, а также работать через облачные
API (Mistral / OpenAI / Anthropic).

## Возможности

- **Локальный интеллект**: движок llama.cpp, собранный в `.so` средствами NDK
  через JNI-обвязку в модуле `:llama` (только `arm64-v8a`).
- **Подбор модели под устройство**: при первом запуске приложение смотрит ОЗУ,
  ABI и число ядер CPU и предлагает скачать подходящую Qwen2.5 Instruct
  (Q4_K_M, 0.5 / 1.5 / 3B) с Hugging Face — возобновляемая загрузка
  по HTTP-Range.
- **Агент с памятью** (Room):
  - факты о пользователе: `запомни: …`, `забудь: …`, `что ты обо мне знаешь?`,
    автообучение из реплик;
  - **найди: …** — поиск по сохранённым фактам;
  - **сжатие долгих бесед** — автосуммаризация в «итоги прошлых бесед»,
    которые подмешиваются в контекст; старые факты (> 30 дней) подчищаются.
- **Мысли и рассуждения ИИ**:
  - отдельная вкладка **«Мысли ИИ»** в нижней навигации и внутри чата: агент
    показывает, как собирал контекст, какие факты вспомнил, какие инструменты
    вызвал (память, веб-поиск, синхронизация), сколько токенов сгенерировал;
  - вкладка «Контекст» — системный промпт, который реально видит модель.
- **Интернет-поиск**: `найди в интернете: …` — кастомный SearXNG (URL + ключ в
  настройках), с fallback на DuckDuckGo Instant Answer и Википедию.
- **Сетевой канал (sync)** через WorkManager:
  - снабжение при подключении к Wi-Fi/сети и периодическая досинхронизация;
  - **исходящие (outbox)** — очередь сообщений с неудачными повторами,
  - `позови <имя>: <текст>` отправляет сообщение на устройство по имени из
    контактов в настройках (`имя=устройствоId` построчно),
  - **шифрование** полезной нагрузки AES-256-GCM (PBKDF2) при заданном пароле
    синхронизации,
  - push-уведомления о полученных сообщениях, автоочистка старых исходящих.
- **Чат**: markdown-разметка ответов, копирование, **регенерация** ответа,
  голосовой ввод (**STT**), **озвучка ответов (TTS)**, экспорт/импорт беседы
  в текстовом формате, «отправить всё в сетевой канал».
- **Внешние API**: Mistral, любой OpenAI-совместимый сервер, Anthropic
  (SSE-стриминг) — переключаются в настройках.
- **Настройки**: выбор модели, сэмплинг (temperature / top-p / top-k),
  длина контекста, число потоков CPU и flash-внимание, повышение голоса из
  настроек (перезапуск движка применяет их к нативному контексту).
- **Material You**: Material3 + dynamic color (иконка и UI подстраиваются под
  цвета системы; adaptive icon с `monochrome`-слоем).

## Команды агента

| Команда | Что делает |
| --- | --- |
| `запомни: <текст>` | сохранить факт в долговременную память |
| `забудь: <текст>` | удалить факт из памяти |
| `что ты обо мне знаешь?` | показать сохранённые факты |
| `найди: <текст>` | поиск по памяти |
| `найди в интернете: <запрос>` | поиск в вебе (SearXNG → DDG → Википедия) |
| `позови <имя>: <текст>` | отправить сообщение на устройство `<имя>` |

## Сборка (только CLI, без Android Studio)

1. Установите зависимости (Arch Linux):

   ```bash
   sudo pacman -S --needed jdk17-openjdk git
   ```

2. Установите Android SDK (cmdline-tools → `sdkmanager`):

   ```bash
   sdkmanager "platform-tools" "platforms;android-35" \
       "build-tools;35.0.0" "ndk;29.0.13113456" "cmake;3.31.6"
   ```

   Экспортируйте `ANDROID_HOME=~/Android/Sdk` (скрипт сам доустановит
   недостающее при `INSTALL=1 ./gradle.sh`).

3. Соберите debug:

   ```bash
   ./gradle.sh assembleDebug
   ```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

**Релиз:** `./gradle.sh assembleRelease`. Если в корне репозитория лежит
`keystore.properties` (storeFile/storePassword/keyAlias/keyPassword), релиз
подписывается этим ключом; иначе — debug-ключом, чтобы APK всегда был
устанавливаемым.

## Релизы на GitHub (CI/CD)

GitHub Action `.github/workflows/release.yml` автоматически, при указании тега
`v*`:

1. собирает **assembleRelease** на `ubuntu-latest` (JDK 17, SDK + NDK + CMake);
2. генерирует **changelog** из коммитов между предыдущим и текущим тегом;
3. создаёт **GitHub Release** и прикрепляет APK.

Для подписи релиза своим ключом добавьте секреты репозитория:
`KEYSTORE_BASE64` (base64 от `.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`. Без них APK выкладывается в release подписанным debug-ключом.

Запуск вручную — вкладка **Actions → Build & Release → Run workflow**.

## Структура

```
app/src/main/java/com/ail0l/app/
  agent/            агент: команды памяти, веб-поиск, позови <имя>, контекст,
                    суммаризация, журнал мыслей
  ai/               движки (engines/), каталог моделей, загрузчик с HF,
                    веб-поиск (search/), провизор
  data/             Room (сущности + DAO + миграции), DataStore-настройки
  sync/             сетевой канал: MessagingChannel, SyncCoordinator,
                    SyncWorker (WorkManager), шифрование
  util/             уведомления, TTS, STT, markdown, AES-GCM, журнал мыслей
  ui/               Compose: чат, мысли ИИ, модели, память, настройки
llama/src/main/cpp/ JNI-обвязка llama.cpp (ai_chat.cpp, экспертиза гидратации
                    контекста: loadModel + hydrateContext вместо prompt-днёрка)
```

## Замечания

- Модуль `llama` тянет llama.cpp через CMake `FetchContent` с ветки `master` —
  API меняется быстро; если сборка сломалась, зафиксируйте тег
  (`GIT_TAG v0.4.1` в `llama/src/main/cpp/CMakeLists.txt`).
- Первый запуск скачивает модель (~0.5–1.8 ГБ в зависимости от устройства) —
  это задуманное поведение «подстрой под устройство».
- Сетевой канал не является мессенджером сам по себе: нужен какой-либо
  сервер/база между устройствами, реализующие REST-контракт `MessagingChannel`
  (`POST /{device}/messages`, `GET /{device}/messages?after={ts}`).
- APK собран только для `arm64-v8a` (эмулятор x86_64 не установит).