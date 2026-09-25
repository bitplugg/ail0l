# AIIA — локальный ИИ-напарник

[![AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)
[![commits](https://img.shields.io/github/commit-activity/m/bitplugg/ail0l)](https://github.com/bitplugg/ail0l/commits)
[![stars](https://img.shields.io/github/stars/bitplugg/ail0l)](https://github.com/bitplugg/ail0l/stargazers)
[![release](https://img.shields.io/github/v/release/bitplugg/ail0l)](https://github.com/bitplugg/ail0l/releases)
[![Android](https://img.shields.io/badge/Android-29%2B-3DDC84.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple.svg)](https://kotlinlang.org)
[![llama.cpp](https://img.shields.io/badge/llama.cpp-ggml--org-orange.svg)](https://github.com/ggml-org/llama.cpp)
[![Local API](https://img.shields.io/badge/API-OpenAI%20compatible-10A37F.svg)](#local-openai-api)

> **AIIA** — Android-приложение на Kotlin и Jetpack Compose, которое запускает
> GGUF-модели через llama.cpp на устройстве, помогает с памятью и автоматизацией,
> а также умеет работать с облачными API, MCP и локальными инструментами.

Название GitHub-репозитория исторически остаётся
[`bitplugg/ail0l`](https://github.com/bitplugg/ail0l), но бренд приложения и
Android package уже используют **AIIA** и `com.aiia.app`.

## Содержание

- [Возможности](#возможности)
- [Архитектура](#архитектура)
- [Быстрый старт](#быстрый-старт)
- [Настройка приложения](#настройка-приложения)
- [Local OpenAI API](#local-openai-api)
- [P2P-синхронизация](#p2p-синхронизация)
- [Терминал и инструменты](#терминал-и-инструменты)
- [MCP](#mcp)
- [Плагины `.aiip`](#плагины-aiip)
- [SDK и шаблон плагина](#sdk-и-шаблон-плагина)
- [Данные и приватность](#данные-и-приватность)
- [Разработка и CI](#разработка-и-ci)
- [Совместимость и ограничения](#совместимость-и-ограничения)
- [Лицензия](#лицензия)

## Возможности

### Локальный инференс

- GGUF-модели выполняются локально через нативную библиотеку
  [`llama.cpp`](https://github.com/ggml-org/llama.cpp), без обязательного
  облачного запроса.
- JNI-мост `com.arm.aichat` загружает `libaiia-llama.so`; upstream-пакет JNI
  намеренно сохранён, чтобы не сломать ABI вендорного binding.
- Поддерживаются Qwen и другие совместимые GGUF-модели, включая Q4_K_M,
  Q5_K_M и Q8_0.
- Настройки sampling: temperature, top-k, top-p, context length, число потоков
  CPU и flash attention.
- Поддержка LoRA-профилей и `mmproj`-проекторов для Vision-моделей.
- KV state можно сохранять и восстанавливать через `ContextCacheManager`.
  Кэш проверяется по модели, prompt и размеру контекста.

### Hugging Face и загрузка моделей

- Поиск GGUF- и Vision-файлов через Hugging Face API.
- Фильтрация репозиториев и файлов по типу, квантизации и наличию `mmproj`.
- Возобновляемая загрузка через HTTP `Range` после прерывания сети или
  перезапуска приложения.
- Временный файл `.part`, прогресс, скорость и уведомления о загрузке.
- Проверка SHA-256, если размер или LFS-метаданные Hugging Face позволяют её
  выполнить.
- Скачанные модели и проекторы сохраняются в приватном хранилище приложения.
  Для закрытых или gated-моделей можно сохранить Hugging Face token.

### Агент, память и RAG

- Room хранит диалоги, сообщения, факты, outbox/inbox, напоминания, персоны и
  векторные представления.
- Команды памяти:

  ```text
  запомни: я предпочитаю Kotlin
  забудь: ...
  что ты обо мне знаешь?
  найди: ...
  ```

- Автообучение из реплик, поиск по памяти, дневные факты и суммаризация
  длинных диалогов.
- RAG использует ONNX Runtime, cosine similarity и локальные embeddings.
  Если ONNX-модель не настроена, используется встроенный hash-embedding fallback.
- Персоны позволяют переключать системный prompt, а для локальной модели —
  отдельный LoRA-путь и масштаб адаптера.
- KV cache уменьшает повторную обработку неизменённого контекста.

### Мультимодальность

- Вложения из галереи и камеры сохраняются в `MessageEntity.attachments`.
- Изображения копируются в приватный cache и передаются в локальный JNI-слой.
- `mmproj` загружается через mtmd; Vision-модель получает запрос с vision
  prompt.
- Поддерживаются облачные движки с OpenAI-совместимым vision-форматом, если
  конкретный провайдер принимает такой формат.

### Облачные и локальные движки

- Локальный llama.cpp.
- Mistral API.
- Любой OpenAI-совместимый endpoint.
- Anthropic API.
- SSE streaming и переключение движка из настроек.
- Ключи API и параметры моделей хранятся в приватном DataStore-хранилище.

### Синхронизация и обмен сообщениями

- WorkManager запускает синхронизацию по сети и по расписанию.
- Outbox повторяет неудачные отправки и очищает старые успешно отправленные
  записи.
- Встроенный P2P-канал работает через NSD `_aiia-sync._tcp` и прямое
  подключение по локальной сети.
- AES-256-GCM и PBKDF2 используются для шифрования сообщений перед отправкой,
  если задан пароль синхронизации.
- Контакты устройств задаются в настройках в формате `имя=идентификатор`.
- Quick Settings tile и виджет быстрых команд.

### Терминал и автоматизация

- Встроенный терминал на базе `ProcessBuilder` с режимами `shell`, `root` и
  `shizuku`.
- Интерактивный ввод, ANSI-цвета, управляющие клавиши и быстрые кнопки.
- Кнопка **«Разобрать в AIIA»** отправляет последние строки вывода на
  анализ агенту.
- Инструменты агента включают `exec_shell`, `open_app`, `get_battery`,
  `set_volume`, `set_brightness` и системные операции.
- Опасные команды и root/Shizuku-действия требуют явного подтверждения в UI.

### API, MCP и плагины

- Встроенный foreground Local OpenAI API на Ktor.
- MCP JSON-RPC через stdio и HTTP/SSE.
- Горячая загрузка `.dex`, `.jar` и `.aiip` через `DexClassLoader`.
- `manifest.json` с описанием разрешений и API version.
- Отдельный SDK и готовый шаблон `.aiip` в
  [`bitplugg/aiia-aiip-template`](https://github.com/bitplugg/aiia-aiip-template).

### Интерфейс

- Material 3 с Dynamic Color.
- Shared transitions и Compose Motion.
- Настройки разделены на Engine, Memory, Network, Automation и Advanced.
- Поддержка light/dark/auto, анимаций, размера шрифта терминала и моноширинного
  вывода.
- Launcher icon и monochrome-слой используют букву **A**.

## Архитектура

```text
app/src/main/java/com/aiia/app/
├── agent/                 Agent, RAG, KV cache, system tools
├── ai/
│   ├── download/          локальный каталог и provision manager
│   ├── engines/           Local, Mistral, OpenAI, Anthropic
│   ├── models/            Hugging Face API и resumable downloader
│   └── search/            SearXNG и web search fallback
├── api/                   Ktor Local OpenAI API и P2P HTTP endpoint
├── data/                  Room, DataStore, entities и миграции
├── dm/                    ленивая инициализация зависимостей
├── persona/               персоны и LoRA
├── plugins/
│   ├── engine/            .dex/.jar/.aiip plugin manager
│   └── mcp/               stdio и HTTP MCP transports
├── sync/                  WorkManager, NSD, P2P, wire protocol
├── terminal/              TerminalSession и Compose terminal screen
└── ui/                    Compose navigation, chat, models, settings

llama/src/main/
├── cpp/                   JNI, llama.cpp, mtmd, LoRA, KV state
└── java/com/arm/aichat/   вендорный Kotlin/Java binding

plugins/aiip_sdk/          отдельный SDK и Gradle-шаблон
```

Поток запроса:

```text
UI → Agent → Memory/RAG/Tools/MCP → EngineFactory
                                  ├─ LocalLlamaEngine → JNI → llama.cpp/mtmd
                                  ├─ OpenAI/Mistral/Anthropic HTTP client
                                  └─ Local OpenAI API → Ktor
```

## Быстрый старт

### Требования

- Android 10 (API 29) или новее.
- Реальное устройство или эмулятор с `arm64-v8a`.
- JDK 17.
- Android SDK Platform 35 и Build Tools 35.0.0.
- Android NDK и CMake, указанные ниже.
- Git и доступ к GitHub/Hugging Face.

Рекомендуемый NDK — `29.0.13113456`, CMake — `3.31.6`.

### Сборка

```bash
git clone https://github.com/bitplugg/ail0l.git
cd ail0l

export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

./gradlew.sh assembleDebug
```

Либо напрямую:

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

Готовые файлы:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

Установка на подключённое устройство:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Скрипт `gradle.sh` умеет подсказывать путь к SDK и запускать Gradle без
Android Studio. Первая native-сборка загружает зафиксированную ревизию
llama.cpp, поэтому нужен стабильный интернет.

## Настройка приложения

### 1. Движок и модель

1. Откройте **Настройки → Engine**.
2. Выберите `local`, `mistral`, `openai` или `anthropic`.
3. Для локального режима выберите GGUF-файл на вкладке **Модели** или
   укажите путь в настройках.
4. Для Vision-модели отдельно укажите совместимый `mmproj`.
5. При необходимости задайте LoRA path и scale.

Для облачного режима заполните API key, base URL и model. Ключи не нужно
коммитить или публиковать в issue.

### 2. Память и RAG

- Включите memory и auto-learning в разделе **Memory**.
- Для RAG укажите ONNX embedding-модель в разделе **Advanced**.
- Размерность по умолчанию — `384`.
- Индекс фактов создаётся автоматически при запуске RAG и дополняется по мере
  добавления новых фактов.

### 3. API и P2P

- Local API включается отдельно и работает как foreground service.
- P2P включается независимо от облачного sync.
- Для P2P задайте одинаковый пароль синхронизации на устройствах.
- Держите P2P-порт доступным только в доверенной локальной сети.

### 4. MCP

В разделе **Automation → MCP** вставьте JSON-массив серверов:

```json
[
  {
    "name": "files",
    "transport": "stdio",
    "command": ["python3", "/path/to/server.py"],
    "enabled": true
  },
  {
    "name": "remote-tools",
    "transport": "http",
    "url": "http://127.0.0.1:9000/mcp",
    "enabled": true
  }
]
```

Поддерживаются MCP `initialize`, `tools/list` и `tools/call`. HTTP-транспорт
умеет читать как обычный JSON-ответ, так и SSE-подобный ответ с полями
`data:`.

## Local OpenAI API

API включается в настройках и поднимает foreground service. По умолчанию:

| Параметр | Значение |
| --- | --- |
| Интерфейс | `127.0.0.1` |
| Порт | `8080` |
| Авторизация | `Authorization: Bearer <token>` или `X-Aiia-Key` |
| Протокол | OpenAI Chat Completions |
| Streaming | SSE при `"stream": true` |

Если токен не задан, авторизация не включается. Для локальной сети всегда
рекомендуется задать токен.

### Endpoints

| Метод | Путь | Назначение |
| --- | --- | --- |
| `GET` | `/health` | проверка состояния сервиса |
| `GET` | `/v1/models` | список доступных локальных моделей |
| `POST` | `/v1/chat/completions` | обычный или streaming chat completion |
| `POST` | `/p2p/receive` | приём зашифрованных P2P-сообщений |

### Пример запроса

```bash
curl http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer YOUR_TOKEN' \
  -d '{
    "model": "local",
    "stream": false,
    "messages": [
      {"role": "user", "content": "Расскажи, что такое AIIA?"}
    ]
  }'
```

### Streaming

```bash
curl -N http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer YOUR_TOKEN' \
  -d '{
    "model": "local",
    "stream": true,
    "messages": [
      {"role": "user", "content": "Сделай короткий план запуска проекта"}
    ]
  }'
```

Ожидаемый финал потока:

```text
data: [DONE]
```

## P2P-синхронизация

P2P не требует отдельного облачного relay-сервера:

1. Включите P2P и задайте device ID, имя и пароль.
2. Укажите одинаковый пароль на втором устройстве.
3. Добавьте контакт в формате `имя=ID` или дождитесь NSD-обнаружения.
4. Отправьте сообщение командой `позови <имя>: <текст>`.

Сервис объявляется как `_aiia-sync._tcp`. Для ручного вызова LAN endpoint
используйте порт `8081` и заголовок `X-Aiia-Key`:

```bash
curl http://192.168.1.20:8081/p2p/receive \
  -H 'Content-Type: application/json' \
  -H 'X-Aiia-Key: YOUR_SYNC_PASSWORD' \
  -d '{
    "device": "DEVICE_ID",
    "messages": [
      {
        "id": "message-uuid",
        "conversationId": 1,
        "sender": "DEVICE_A",
        "content": "encrypted-or-plain-content",
        "toDevice": "DEVICE_B",
        "createdAt": 1730000000000
      }
    ]
  }'
```

Если пароль синхронизации установлен, `content` должен быть зашифрован тем же
AES-256-GCM/PBKDF2-совместимым кодом, который использует AIIA.

## Терминал и инструменты

Терминал запускает процессы через `ProcessBuilder` и поддерживает:

- обычный shell;
- root через `su`;
- Shizuku через `rish`, если сервис Shizuku установлен и разрешён;
- ANSI-последовательности и основные control-клавиши;
- быстрый анализ последнего вывода через AIIA.

Root и Shizuku-команды выполняются только с правами, уже выданными ОС.
AIIA показывает отдельный диалог подтверждения для опасных вызовов агента.

Примеры инструментов, которые понимает агент:

```text
exec_shell {"command":"getprop ro.product.model"}
open_app {"package":"com.example.app"}
get_battery {}
```

## MCP

MCP-серверы подключаются через JSON-RPC 2.0. Конфигурация хранится в
DataStore и может редактироваться из настроек. Для stdio запускается
указанная команда, для HTTP выполняется POST к `url`.

AIIA запрашивает initialize и tools/list при старте, а описание инструментов
добавляется в системный контекст агента. Перед вызовом системного инструмента
показывается карточка с названием, аргументами и запросом подтверждения.

## Плагины `.aiip`

### Что поддерживается

- `.aiip` — ZIP-пакет с `manifest.json`, `plugin.dex` и `assets/`.
- `.dex` и `.jar` — отдельные файлы для hot reload.
- Для raw `.dex`/`.jar` рядом должен находиться sidecar-манифест
  `<имя>.manifest.json`, если entry class не указан иначе.
- Максимальный размер распакованного пакета — 256 MiB.
- ZIP path traversal проверяется до записи файлов.
- Перед загрузкой показываются permissions из manifest.

### Минимальный manifest

```json
{
  "id": "com.example.my-plugin",
  "name": "My plugin",
  "version": "1.0.0",
  "entryClass": "com.example.myplugin.MainPlugin",
  "apiVersion": 1,
  "permissions": [
    {
      "name": "tool-call",
      "description": "Разрешает запуск инструмента агента"
    }
  ]
}
```

### Установка

1. Откройте настройки плагинов.
2. Выберите `.aiip`, `.dex` или `.jar`.
3. Проверьте имя, версию и список permissions.
4. Подтвердите установку.
5. Для разработки поместите пакет в `AIIA/plugins` на внешнем хранилище или
   используйте hot reload.

`DexClassLoader` работает в процессе приложения. Это удобный механизм для
доверенных инструментов, но **не является отдельной Android security sandbox**:
не устанавливайте непроверенные плагины.

## SDK и шаблон плагина

Локальный SDK находится в [`plugins/aiip_sdk`](plugins/aiip_sdk). Готовый
публичный шаблон:

- https://github.com/bitplugg/aiia-aiip-template

Собрать example package:

```bash
cd plugins/aiip_sdk
./gradlew packageAiip
```

Результат:

```text
plugins/aiip_sdk/build/aiip/example.aiip
```

Структура пакета:

```text
example.aiip
├── manifest.json
├── plugin.dex
└── assets/
    └── example.txt
```

Минимальный plugin entry point:

```kotlin
package com.example.myplugin

import com.aiia.app.plugins.engine.AiiaPlugin
import com.aiia.app.plugins.engine.PluginManifest
import com.aiia.app.plugins.engine.PluginPermission
import com.aiia.app.plugins.engine.PluginTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

class MainPlugin : AiiaPlugin {
    override val manifest = PluginManifest(
        id = "com.example.my-plugin",
        name = "My plugin",
        version = "1.0.0",
        entryClass = "com.example.myplugin.MainPlugin",
        permissions = listOf(
            PluginPermission("tool-call", "Run a trusted tool")
        )
    )

    override fun tools() = listOf(
        PluginTool(
            name = "echo",
            description = "Return the supplied text",
            inputSchema = buildJsonObject { }
        )
    )

    override suspend fun call(
        name: String,
        arguments: JsonObject
    ): String = arguments.toString()
}
```

Gradle task извлекает `classes.jar` из Android AAR, конвертирует его в
`plugin.dex` через Android D8 и складывает результат вместе с assets в `.aiip`.

## Данные и приватность

Основные данные хранятся в приватных каталогах приложения:

- Room DB: диалоги, факты, сообщения, outbox/inbox, персоны, embeddings.
- DataStore: настройки, ключи, токены и состояние синхронизации.
- `files/aiia-kv-cache`: metadata и state KV-кэша.
- `cache/chat-images`: копии вложений.
- каталог provision manager: GGUF и mmproj-файлы.

Локальный режим не требует отправки сообщений на сервер. Облачные ключи и
сетевые endpoint используются только при выбранном соответствующем движке.
Не публикуйте API keys, `keystore.properties`, `.jks` и `.aiip`-пакеты с
секретами.

## Разработка и CI

Основные команды:

```bash
# Kotlin и KSP
./gradlew :app:compileDebugKotlin

# Полная debug-сборка
./gradlew assembleDebug

# Release с R8/resource shrinking
./gradlew assembleRelease

# Native llama.cpp/mtmd
./gradlew :llama:externalNativeBuildDebug

# Android lint и unit tests
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest

# SDK example package
plugins/aiip_sdk/gradlew packageAiip
```

Релиз создаётся GitHub Actions по тегу `v*`:

1. собирается `assembleRelease`;
2. генерируется changelog между тегами;
3. создаётся GitHub Release;
4. к релизу прикрепляется APK.

Для собственной подписи добавьте secrets `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS` и `KEY_PASSWORD`. Без них CI использует
debug signing key.

### Native-сборка

CMake использует `FetchContent` и фиксированную ревизию llama.cpp из
`llama/src/main/cpp/CMakeLists.txt`. Сборка включает mtmd и arm64 CPU backends.
Изменение upstream-ревизии может потребовать обновления C++ JNI-кода.

## Совместимость и ограничения

- Поддерживается `arm64-v8a`; x86/x86_64 APK не выпускается.
- Минимальная версия Android — API 29, target/compile SDK — 35.
- Vision-функции требуют совместимой модели и `mmproj`; нативный bridge
  загружает и декодирует изображение через mtmd, а качество OCR/описания
  зависит от выбранной VLM-модели и устройства.
- Терминал использует `ProcessBuilder`, а не полноценный Unix PTY.
- Синхронизация и API предназначены для доверенной локальной сети; не
  публикуйте токены и P2P-порт в интернет без дополнительной аутентификации.
- Плагины работают в процессе приложения и должны рассматриваться как
  доверенный код.
- Для RAG нужна отдельная ONNX embedding-модель; без неё включается fallback.
- Смена `applicationId` на `com.aiia.app` означает отдельную Android-установку
  и отдельное приватное хранилище по сравнению со старой установкой.

## Лицензия

Проект распространяется по [GNU Affero General Public License v3.0](LICENSE).
Native binding и производные части llama.cpp сохраняют MIT attribution в
`llama/NOTICE.md`.

## Полезные ссылки

- [Основной репозиторий](https://github.com/bitplugg/ail0l)
- [Релизы](https://github.com/bitplugg/ail0l/releases)
- [Issues](https://github.com/bitplugg/ail0l/issues)
- [Шаблон `.aiip`](https://github.com/bitplugg/aiia-aiip-template)
- [llama.cpp](https://github.com/ggml-org/llama.cpp)
- [Hugging Face](https://huggingface.co/)
