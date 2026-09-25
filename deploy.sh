#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

SUFFIX="auto"
FLAVOR="debug"
NO_INSTALL=0

for arg in "$@"; do
    case "$arg" in
        release) FLAVOR="release" ;;
        --no-install) NO_INSTALL=1 ;;
        --no-version) SUFFIX="none" ;;
        help|--help|-h)
            sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) echo "⚠  Неизвестный аргумент: $arg"; exit 1 ;;
    esac
done

BGRADLE="app/build.gradle.kts"
if [[ ! -f "$BGRADLE" ]]; then
    echo "✗ $BGRADLE не найден"; exit 1
fi

CURRENT_VERSION="$(grep -oE 'versionName[[:space:]]*=[[:space:]]*"[^"]*"' "$BGRADLE" | sed -E 's/.*"([^"]*)".*/\1/' | tail -1)"
if [[ -z "$CURRENT_VERSION" ]]; then
    echo "✗ Не удалось прочитать versionName из $BGRADLE"; exit 1
fi

echo "──────────────────────────────────────────────"
echo "  AIIA » сборка и установка на устройство"
echo "──────────────────────────────────────────────"
echo "  Текущая версия:  $CURRENT_VERSION"
echo "  Флейвор:         $FLAVOR"
echo "──────────────────────────────────────────────"

NEW_VERSION="$CURRENT_VERSION"
if [[ "$SUFFIX" == "auto" ]]; then
    while true; do
        read -r -p "Добавить суффикс к версии? [beta/alpha/rc/другое/none → Enter]: " ANSWER
        case "${ANSWER,,}" in
            ""|none|n) NEW_VERSION="$CURRENT_VERSION"; break ;;
            beta|b) NEW_VERSION="${CURRENT_VERSION}-beta"; break ;;
            alpha|a) NEW_VERSION="${CURRENT_VERSION}-alpha"; break ;;
            rc) NEW_VERSION="${CURRENT_VERSION}-rc"; break ;;
            *)
                if [[ "$ANSWER" =~ ^[a-zA-Z0-9._-]+$ ]]; then
                    NEW_VERSION="${CURRENT_VERSION}-${ANSWER}"
                    break
                fi
                echo "✗ Некорректный суффикс. Используйте буквы/цифры/точку/дефис." ;;
        esac
    done
fi

if [[ "$NEW_VERSION" != "$CURRENT_VERSION" ]]; then
    echo "  Новая версия:    $NEW_VERSION"
    read -r -p "  Записать в $BGRADLE? [Y/n]: " APPLY
    case "${APPLY,,}" in
        ""|y|yes|д|да)
            grep -qE 'versionName[[:space:]]*=[[:space:]]*"' "$BGRADLE"
            sed -i -E "s/versionName[[:space:]]*=[[:space:]]*\"[^\"]*\"/versionName = \"$NEW_VERSION\"/" "$BGRADLE"
            echo "  ✔ versionName → $NEW_VERSION" ;;
        *) echo "  Пропускаем (соберём как есть, без записи)." ;;
    esac
fi

FINAL_VERSION="$(grep -oE 'versionName[[:space:]]*=[[:space:]]*"[^"]*"' "$BGRADLE" | sed -E 's/.*"([^"]*)".*/\1/' | tail -1)"

echo "──────────────────────────────────────────────"
echo "  Собираю assemble$([[ "$FLAVOR" == "release" ]] && echo Release || echo Debug)…"
echo "──────────────────────────────────────────────"

BUILD_START=$SECONDS
if [[ "$FLAVOR" == "release" ]]; then
    ./gradle.sh assembleRelease
    APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
else
    ./gradle.sh assembleDebug
    APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
fi

if [[ ! -f "$APK" ]]; then
    echo "✗ APK не найден: $APK"; exit 1
fi
BUILD_ELAPSED=$((SECONDS - BUILD_START))

SIZE="$(du -h "$APK" | cut -f1)"
echo "──────────────────────────────────────────────"
echo "  ✔ APK собран за ${BUILD_ELAPSED} с:"
echo "    версия:  $FINAL_VERSION"
echo "    размер:  $SIZE"
echo "    путь:    $APK"
echo "──────────────────────────────────────────────"

if [[ "$NO_INSTALL" == "1" ]]; then
    echo "  [deploy.sh] Установку пропустили (--no-install)."
    exit 0
fi

if ! command -v adb >/dev/null 2>&1; then
    echo "✗ adb не найден в PATH. APK готов: $APK"; exit 0
fi

if [[ "$(adb devices | grep -c 'device$')" -eq 0 ]]; then
    echo "⚠  Устройство не подключено/не авторизовано. APK готов: $APK"
    exit 0
fi

echo "  Устанавливаю на устройство…"
adb install -r "$APK"

echo "──────────────────────────────────────────────"
echo "  ✔ Готово! Версия $FINAL_VERSION на устройстве."
echo "──────────────────────────────────────────────"
