#!/usr/bin/env bash
#
# AIL0L · CLI-сборка без Android Studio.
#
#   ./gradle.sh [args...]
#
# Всё, что скрипт делает сам:
#   1. Находит JDK 17+ (JAVA_HOME или java в PATH).
#   2. Находит Android SDK (ANDROID_HOME / ANDROID_SDK_ROOT / local.properties /
#      типовые пути).
#   3. Допроверяет/устанавливает нужные компоненты через sdkmanager
#      (можно отключить INSTALL=0).
#   4. Пишет local.properties и запускает ./gradlew с переданными аргументами.
#
# Примеры:
#   ./gradle.sh                      -> assembleDebug (debugkka)
#   ./gradle.sh assembleRelease
#   ./gradle.sh installDebug         (нужен подключённый adb-устройство)
#   ./gradle.sh installDebug --reload

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

AUTO_INSTALL="${INSTALL:-1}"

# ---------------------------------------------------------------------------
# 1. JDK
# ---------------------------------------------------------------------------
find_java() {
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
        echo "$JAVA_HOME/bin/java"; return 0
    fi
    local spec javas
    spec="$(command -v java || true)"
    if [[ -n "$spec" && -x "$spec" ]]; then
        if command -v readlink >/dev/null 2>&1; then
            spec="$(readlink -f "$spec" 2>/dev/null || echo "$spec")"
        fi
        echo "$spec"; return 0
    fi
    for dir in /usr/lib/jvm/*/bin/java /opt/jdk*/bin/java /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java; do
        [[ -x "$dir" ]] && { echo "$dir"; return 0; }
    done
    return 1
}

JAVA_BIN="$(find_java || true)"
if [[ -z "$JAVA_BIN" ]]; then
    cat <<'EOF'
[gradle.sh] JDK 17+ не найден.
  Debian/Ubuntu:   sudo apt install openjdk-17-jdk
  Fedora:          sudo dnf install java-17-openjdk
  Arch:            sudo pacman -S jdk17-openjdk
После установки либо экспортируйте JAVA_HOME, либо установите java в PATH.
EOF
    exit 1
fi

JAVA_VERSION="$("$JAVA_BIN" -version 2>&1 | sed -n '1p')"
echo "[gradle.sh] Java: $JAVA_VERSION ($JAVA_BIN)"

# ---------------------------------------------------------------------------
# 2. Android SDK
# ---------------------------------------------------------------------------
SDK=""
if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]]; then SDK="$ANDROID_HOME"; fi
if [[ -z "$SDK" && -n "${ANDROID_SDK_ROOT:-}" && -d "$ANDROID_SDK_ROOT" ]]; then SDK="$ANDROID_SDK_ROOT"; fi
if [[ -z "$SDK" && -f local.properties ]]; then
    SDK="$(sed -n 's/^sdk\.dir=//p' local.properties | tail -1)"
fi
if [[ -z "$SDK" || ! -d "$SDK" ]]; then
    for dir in "$HOME/Android/Sdk" "$HOME/android-sdk" /opt/android-sdk /usr/local/share/android-commandlinetools "$HOME/Library/Android/sdk"; do
        if [[ -d "$dir" ]]; then SDK="$dir"; break; fi
    done
fi

if [[ -z "$SDK" || ! -d "$SDK" ]]; then
    cat <<'EOF'
[gradle.sh] Android SDK не найден.
  1) Установите command-line tools:
       https://developer.android.com/studio#command-line-tools-only
     (распакуйте архив, например, в ~/Android/Sdk)
  2) Экспортируйте путь:  export ANDROID_HOME=~/Android/Sdk
     (или создайте local.properties со строкой  sdk.dir=путь)
  3) Запустите снова.
Для CI/серверов можно выполнить это автоматически:
       INSTALL=1 ./gradle.sh
EOF
    exit 1
fi

export ANDROID_HOME="$SDK"
echo "[gradle.sh] Android SDK: $SDK"

if [[ ! -f local.properties ]]; then
    echo "sdk.dir=$SDK" > local.properties
    echo "[gradle.sh] Создан local.properties"
fi

# ---------------------------------------------------------------------------
# 3. Компоненты SDK (список того, что реально нужно)
# ---------------------------------------------------------------------------
REQUIRED_COMPONENTS=(
    "platform-tools"
    "platforms;android-35"
    "build-tools;35.0.0"
    "cmake;3.31.6"
    "ndk;29.0.13113456"
)

SDKMGR_BIN=""
for cand in "$SDK/cmdline-tools/latest/bin/sdkmanager" "$SDK/cmdline-tools/bin/sdkmanager" "$SDK/tools/bin/sdkmanager"; do
    if [[ -x "$cand" ]]; then SDKMGR_BIN="$cand"; break; fi
done

missing=()
for comp in "${REQUIRED_COMPONENTS[@]}"; do
    if [[ -f "$SDK/$comp/source.properties" ]] || [[ -d "$SDK/$comp" && "$comp" == "platform-tools" ]]; then
        continue
    fi
    missing+=("$comp")
done

if [[ ${#missing[@]} -gt 0 ]]; then
    echo "[gradle.sh] Отсутствуют компоненты SDK: ${missing[*]}"
    if [[ "$AUTO_INSTALL" == "1" && -n "$SDKMGR_BIN" ]]; then
        echo "[gradle.sh] Устанавливаю через sdkmanager (примите лицензии: yes по очереди)..."
        yes | "$SDKMGR_BIN" --licenses >/dev/null 2>&1 || true
        "$SDKMGR_BIN" "${missing[@]}"
        echo "[gradle.sh] Компоненты установлены."
    else
        cat <<EOF
  Установите их вручную:
    """$SDKMGR_BIN""" --licenses
    """$SDKMGR_BIN""" ${missing[*]}
  (соберите сами с пометой INSTALL=1, чтобы скрипт сделал это автоматически)
EOF
        exit 1
    fi
fi

# ---------------------------------------------------------------------------
# 4. Сборка
# ---------------------------------------------------------------------------
if ! command -v "$ROOT/gradlew" >/dev/null 2>&1 && [[ ! -x "$ROOT/gradlew" ]]; then
    echo "[gradle.sh] gradlew не найден — обновите репозиторий (gradle wrapper)."
    exit 1
fi

TASKS=("$@")
if [[ ${#TASKS[@]} -eq 0 ]]; then
    TASKS=(assembleDebug)
fi

echo "[gradle.sh] Запускаю: ./gradlew ${TASKS[*]}"
exec "$ROOT/gradlew" "${TASKS[@]}"