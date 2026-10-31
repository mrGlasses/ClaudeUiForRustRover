#!/usr/bin/env bash
# Pre-push check without Gradle: compiles src/main and src/test with kotlinc against the IDE's own jars, runs the unit tests
# and builds the web panel. Catches compile errors and failing tests before CI does. CI (Gradle + verifyPlugin) stays the
# source of truth: this script approximates it (JUnit is a small stand-in, Java 21 instead of 25).
#
# usage: tools/precheck/precheck.sh <kotlinc dir> <dir with the IDE jars> [work dir]
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
KC=$1; JARS=$2; OUT=${3:-/tmp/clawed-precheck}
CP=$(find "$JARS" -name '*.jar' | sort | tr '\n' ':')
rm -rf "$OUT"; mkdir -p "$OUT/junit" "$OUT/main" "$OUT/test"

echo "== web panel"
(cd "$ROOT/webview" && npm run --silent typecheck && npm run --silent build >/dev/null) && echo ok

echo "== compile main"
javac -nowarn -d "$OUT/junit" $(find "$ROOT/tools/precheck/junit" -name '*.java')
"$KC/bin/kotlinc" -nowarn -jvm-target 21 -cp "$CP" -d "$OUT/main" $(find "$ROOT/src/main/kotlin" -name '*.kt') 2>&1 | grep -v '^warning:' || true
[ -f "$OUT/main/dev/metaclaude/PluginInfo.class" ] || { echo "MAIN DID NOT COMPILE"; exit 1; }

echo "== compile tests"
"$KC/bin/kotlinc" -nowarn -jvm-target 21 -cp "$CP:$OUT/main:$OUT/junit" -d "$OUT/test" $(find "$ROOT/src/test/kotlin" -name '*.kt') 2>&1 | grep -v '^warning:' || true
[ -n "$(find "$OUT/test" -name '*.class' | head -1)" ] || { echo "TESTS DID NOT COMPILE"; exit 1; }

echo "== run tests"
mkdir -p "$OUT/res" && sed 's/\${version}/0.0.0-precheck/' "$ROOT/src/main/resources/claude-ui.properties" > "$OUT/res/claude-ui.properties"
java -cp "$CP:$OUT/main:$OUT/test:$OUT/junit:$OUT/res:$KC/lib/kotlin-stdlib.jar" org.junit.PrecheckRunner "$OUT/test"

echo "== plugin zip (for the verifier)"
V=$(sed -n 's/^version = "\(.*\)"/\1/p' "$ROOT/build.gradle.kts")
Z="$OUT/zip/clawed-ui"; mkdir -p "$Z/lib" "$OUT/jar"
cp -r "$OUT/main/." "$OUT/jar/"
cp -r "$ROOT/src/main/resources/." "$OUT/jar/"
sed "s/\${version}/$V/" "$ROOT/src/main/resources/claude-ui.properties" > "$OUT/jar/claude-ui.properties"
# What Gradle's patchPluginXml adds: the version and since-build of the target platform (262).
sed -i "s|</name>|</name>\n  <version>$V</version>\n  <idea-version since-build=\"262\"/>|" "$OUT/jar/META-INF/plugin.xml"
mkdir -p "$OUT/jar/webview" && cp "$ROOT/webview/dist/panel.js" "$ROOT/webview/dist/panel.css" "$OUT/jar/webview/"
(cd "$OUT/jar" && zip -qr "$Z/lib/clawed-ui-$V.jar" .)
WS=$(find "$JARS" -name 'intellij.libraries.java.websocket.jar' | head -1)
[ -n "$WS" ] && cp "$WS" "$Z/lib/Java-WebSocket.jar"
(cd "$OUT/zip" && rm -f "clawed-ui-$V.zip" && zip -qr "clawed-ui-$V.zip" clawed-ui)
echo "built $OUT/zip/clawed-ui-$V.zip"
