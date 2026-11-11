#!/usr/bin/env bash
# Builds the artifacts a release publishes.
#
# The hosted site reads GitHub Releases and turns each asset into a download
# button, so the names produced here are the names the site will show. Three
# artifacts, each one file the user can run:
#
#   jvd.jar         the command line tool
#   jvd-verify.jar  the offline verifier
#   jvd-desktop.jar the desktop app
#
# The tar.gz bundles all three with the README, the licence, and a short
# install note, for the people who would rather download one file.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="${VERSION:-$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' pom.xml | head -1)}"
OUT="$ROOT/dist"
STAGE="$OUT/stage"

export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-25.0.5+8}"
export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.16/bin:$PATH"

echo "==> building (version $VERSION)"
./mvnw -B -q clean package -DskipTests

rm -rf "$STAGE"
mkdir -p "$STAGE"

echo "==> staging the artifacts"
cp cli/target/cli-$VERSION.jar "$STAGE/jvd.jar"
cp verifier-cli/target/verifier-cli-$VERSION.jar "$STAGE/jvd-verify.jar"
cp gui/target/gui-$VERSION.jar "$STAGE/jvd-desktop.jar"
cp README.md "$STAGE/README.md"
cp LICENSE "$STAGE/LICENSE"

cat > "$STAGE/INSTALL.md" <<EOF
# jverifydestroy ${VERSION}

Requires **JDK 25** and a Linux machine. The memory verifier reads
/proc/<pid>/mem, so it does not run on Windows or macOS; the tool reports
NOT_CHECKABLE there rather than guessing.

    java -jar jvd.jar destroy-file FILE --work work --log log
    java -jar jvd.jar verify --log log
    java -jar jvd-verify.jar --log DIR --checkpoint FILE --pubkey B64

The desktop app is in jvd-desktop.jar and needs a display:

    java -jar jvd-desktop.jar

The destruction prints the key it generated. Keep it: it is not written to
disk anywhere, and a transaction that has to be recovered needs it again.
EOF

echo "==> packaging"
tar -czf "$OUT/jvd-$VERSION.tar.gz" -C "$STAGE" --transform="s,^\./,," .
cp "$STAGE/jvd.jar" "$OUT/jvd-$VERSION.jar"
cp "$STAGE/jvd-verify.jar" "$OUT/jvd-verify-$VERSION.jar"
cp "$STAGE/jvd-desktop.jar" "$OUT/jvd-desktop-$VERSION.jar"

echo "==> checksums"
cd "$OUT"
# No "./" prefix: sha256sum -c resolves each name against the current
# directory, and the prefixed form only works when the files were checked
# out that way.
sha256sum ./*.jar ./*.tar.gz | sed 's: \./: :' | tee SHA256SUMS

echo
echo "artifacts in $OUT:"
ls -la "$OUT" | grep -vE '^total|stage'
