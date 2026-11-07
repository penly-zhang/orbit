#!/bin/sh
set -eu
POCKET_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -d "$POCKET_ROOT/.tools/amazon-corretto-17.jdk/Contents/Home" ]; then
    JAVA_HOME="$POCKET_ROOT/.tools/amazon-corretto-17.jdk/Contents/Home"
    export JAVA_HOME
fi
if [ -d "$POCKET_ROOT/.tools/android-sdk" ]; then
    ANDROID_HOME="$POCKET_ROOT/.tools/android-sdk"
    export ANDROID_HOME
fi
GRADLE_USER_HOME="$POCKET_ROOT/.tools/gradle-cache"
export GRADLE_USER_HOME
cd "$POCKET_ROOT"
if [ -x "$POCKET_ROOT/.tools/gradle-8.11.1/bin/gradle" ]; then
    exec "$POCKET_ROOT/.tools/gradle-8.11.1/bin/gradle" "$@"
fi
exec ./gradlew "$@"
