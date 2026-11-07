#!/bin/sh
set -eu

GRADLE_VERSION=8.11.1
GRADLE_SHA256=f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6
GRADLE_CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/codex-bootstrap"
GRADLE_DIR="$GRADLE_CACHE/gradle-$GRADLE_VERSION"

if [ ! -x "$GRADLE_DIR/bin/gradle" ]; then
    mkdir -p "$GRADLE_CACHE"
    ARCHIVE="$GRADLE_CACHE/gradle-$GRADLE_VERSION-bin.zip"
    curl -fL --retry 2 "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ARCHIVE"
    echo "$GRADLE_SHA256  $ARCHIVE" | shasum -a 256 -c -
    unzip -q -o "$ARCHIVE" -d "$GRADLE_CACHE"
    rm -f "$ARCHIVE"
fi

# Android Studio 2026 may bundle a JDK newer than this project's Gradle supports.
# Prefer an installed JDK 17 on macOS when the project is opened from the IDE.
if [ -x /usr/libexec/java_home ]; then
    JAVA_17=$(/usr/libexec/java_home -v 17 2>/dev/null || true)
    if [ -n "$JAVA_17" ]; then
        export JAVA_HOME="$JAVA_17"
    fi
fi

exec "$GRADLE_DIR/bin/gradle" "$@"
