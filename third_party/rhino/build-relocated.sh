#!/usr/bin/env bash
# Relocate a patched Mozilla Rhino 1.9.1 jar from org.mozilla.javascript to
# com.frunified.rhino, avoiding conflicts with host applications.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
INPUT="${1:-${RHINO_JAR:-}}"
if [[ -z "$INPUT" || ! -f "$INPUT" ]]; then
  echo "Usage: $0 /path/to/patched-rhino-1.9.1.jar" >&2
  exit 2
fi

JAVA_HOME="${JAVA_HOME:?Set JAVA_HOME to JDK 17}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/asm"

for artifact in asm asm-commons asm-tree asm-analysis; do
  curl -fsSL -o "$WORK/asm/$artifact.jar" \
    "https://repo1.maven.org/maven2/org/ow2/asm/$artifact/9.7.1/$artifact-9.7.1.jar"
done
CP="$WORK/asm/asm.jar:$WORK/asm/asm-commons.jar:$WORK/asm/asm-tree.jar:$WORK/asm/asm-analysis.jar"

"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$WORK/classes" -cp "$CP" \
  "$ROOT/third_party/rhino/Relocate.java"
"$JAVA_HOME/bin/java" -cp "$WORK/classes:$CP" Relocate "$INPUT" \
  "$ROOT/src/fr/frunified/libs/rhino-nuvio-1.9.1.jar"

echo "Updated src/fr/frunified/libs/rhino-nuvio-1.9.1.jar"
