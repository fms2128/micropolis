#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if [ -z "${JAVA_HOME:-}" ]; then
  if [ -x /opt/homebrew/opt/openjdk/bin/java ]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk"
  elif [ -x /usr/libexec/java_home ] && /usr/libexec/java_home &>/dev/null; then
    export JAVA_HOME="$(/usr/libexec/java_home)"
  fi
fi

./gradlew run
