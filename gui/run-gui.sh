#!/usr/bin/env bash
# Launches the jvd desktop app.
#
# The app is a single runnable jar, so there is nothing to assemble. This
# script only exists so the user can start it with one short command:
#
#   ./gui/run-gui.sh
#
# It also sets JAVA_HOME if the JDK 25 install under ~/tools is present, so
# the app does not need a system-wide JDK on the PATH.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$HERE/target/gui-0.1.0.jar"

if [ -x "$HOME/tools/jdk-25.0.5+8/bin/java" ]; then
  export JAVA_HOME="$HOME/tools/jdk-25.0.5+8"
fi

if [ ! -f "$JAR" ]; then
  echo "The app has not been built yet. Building it now..."
  (cd "$HERE/.." && mvn -B package -DskipTests -pl gui -am)
fi

exec "$JAVA_HOME/bin/java" -jar "$JAR" "$@"
