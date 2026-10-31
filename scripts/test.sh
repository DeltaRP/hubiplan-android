#!/usr/bin/env bash
# Baut die App und führt die Unit-Tests aus (lokal auf Ralfs PC, unter Windows in Git Bash
# oder in WSL, wo ein Android-SDK liegt).
#
#   scripts/test.sh [Gradle-Aufgaben …]
#
# Regeln: nie auf dem Testserver. Höchstens zwei Testläufe gleichzeitig (alle Projekte),
# jeder höchstens 8 Kerne: das Skript holt sich einen Platz mit testplatz (aus
# hubiplan/scripts/testplatz.sh), Gradle bekommt so viele Worker wie der Platz Kerne.
set -euo pipefail

if [ -z "${TESTPLATZ:-}" ]; then
    if ! command -v testplatz > /dev/null; then
        echo "Abbruch: testplatz fehlt (siehe hubiplan/docs/testserver.md, „Testplätze“)." >&2
        exit 2
    fi
    exec testplatz "$0" "$@"
fi

cd "$(dirname "$0")/.."
# Unter Windows liegt das SDK an der Standardstelle von Android Studio
if [ -z "${ANDROID_HOME:-}" ] && [ -n "${LOCALAPPDATA:-}" ] && [ -d "$LOCALAPPDATA/Android/Sdk" ]; then
    export ANDROID_HOME="$LOCALAPPDATA\Android\Sdk"
fi
# Ein veraltetes JAVA_HOME (z. B. altes JDK 9 unter Windows) durch das JDK von Android Studio ersetzen
if [ -n "${JAVA_HOME:-}" ] && [ ! -x "$JAVA_HOME/bin/java" ] && [ ! -x "$JAVA_HOME/bin/java.exe" ]; then
    JBR="${PROGRAMFILES:-}/Android/Android Studio/jbr"
    if [ -d "$JBR" ]; then
        export JAVA_HOME="$JBR"
    else
        unset JAVA_HOME
    fi
fi
if [ -z "${ANDROID_HOME:-}" ]; then
    echo "Abbruch: ANDROID_HOME ist nicht gesetzt (Android-SDK)." >&2
    exit 2
fi
TASKS=("$@")
DEFAULT=0
if [ ${#TASKS[@]} -eq 0 ]; then
    DEFAULT=1
    TASKS=(testFossDebugUnitTest assembleFossDebug lintFossDebug assemblePlayDebug)
fi
./gradlew --no-daemon --max-workers="$TEST_KERNE" "${TASKS[@]}"
if [ "$DEFAULT" = 1 ]; then
    # F-Droid: in der foss-Fassung dürfen keine Google-Dienste stecken (Firebase, Play-Dienste)
    if ./gradlew --no-daemon -q :app:dependencies --configuration fossReleaseRuntimeClasspath \
        | grep -E "com\.google\.(firebase|android\.gms|android\.play)|com\.google\.android\.datatransport"; then
        echo "Abbruch: Google-Bibliothek in der foss-Fassung." >&2
        exit 1
    fi
fi
echo "OK: $(git rev-parse --short HEAD) gebaut und getestet."
