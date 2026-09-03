#!/usr/bin/env bash
# Reconstruit les jars Rhino 1.9.1 patchés pour NuvioClient.
# Patches (voir rhino-parser-patches.patch) :
#   1. yield autorisé en argument d'appel et après une virgule
#   2. opérande de yield = AssignmentExpression (pas Expression, qui avale la virgule)
#   3. yield au niveau instruction = ExpressionStatement ((yield a), b)
# Patches Android (compat dex minSdk 21) :
#   4. SlotMapOwner : VarHandle/MethodHandles (Java 9+) → échange synchronisé
#   5. NativeCollectionIterator + SlotMapOwner : Collections.emptyIterator (Java 9) → emptyList().iterator()
#   6. J8Compat : Map/List/Set.of, copyOf, readAllBytes, requireNonNullElse… (API Java 9+)
# Usage :
#   RHINO_DIR=/tmp/rhino ./tools/build-rhino.sh
#   (RHINO_DIR doit contenir le clone mozilla/rhino tag Rhino1_9_1_Release,
#    déjà patché : rhino-parser-patches.patch + J8Compat + patchs Android)
#
# Le jar « embed » est celui réellement embarqué au dex par la tâche extractRhino
# de FrUnified/build.gradle.kts : il exclut les paquets optimizer/classfile/commonjs
# (qui dépendent de jdk.dynalink / JDK 9) et module-info.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RHINO_DIR="${RHINO_DIR:-/tmp/rhino}"
export JAVA_HOME="${JAVA_HOME:-/home/user/.cache/toolchain/jdk17}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/home/user/.cache/gradle}"

cd "$RHINO_DIR"
./gradlew --no-daemon -Dorg.gradle.jvmargs="-Xmx1500m -Dfile.encoding=UTF-8" :rhino:jar
cp rhino/build/libs/rhino-1.9.1.jar "$ROOT/FrUnified/libs/rhino-nuvio-1.9.1.jar"

TMP="$(mktemp -d)"
cd "$TMP"
jar xf "$RHINO_DIR/rhino/build/libs/rhino-1.9.1.jar"
rm -rf \
  org/mozilla/javascript/optimizer \
  org/mozilla/javascript/classfile \
  org/mozilla/javascript/commonjs \
  module-info.class \
  META-INF/versions
jar cf "$TMP/embed.jar" .
cp "$TMP/embed.jar" "$ROOT/FrUnified/libs/rhino-embed-1.9.1.jar"
rm -rf "$TMP"
echo "jars Rhino patchés copiés dans FrUnified/libs/"
