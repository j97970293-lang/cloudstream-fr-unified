#!/usr/bin/env bash
# Reconstruit le jar Rhino 1.9.1 patché pour NuvioClient.
# Patches (voir rhino-parser-patches.patch) :
#   1. yield autorisé en argument d'appel et après une virgule
#   2. opérande de yield = AssignmentExpression (pas Expression, qui avale la virgule)
#   3. yield au niveau instruction = ExpressionStatement ((yield a), b)
# Usage :
#   git clone --depth 1 --branch Rhino1_9_1_Release https://github.com/mozilla/rhino.git /tmp/rhino
#   cd /tmp/rhino && git apply ../tools/rhino-parser-patches.patch
#   ./tools/build-rhino.sh   (copie le jar dans FrUnified/libs/)
set -euo pipefail
export JAVA_HOME="${JAVA_HOME:-/home/user/.cache/toolchain/jdk17}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/home/user/.cache/gradle}"
./gradlew --no-daemon -Dorg.gradle.jvmargs="-Xmx1500m -Dfile.encoding=UTF-8" :rhino:jar
cp rhino/build/libs/rhino-1.9.1.jar ../FrUnified/libs/rhino-nuvio-1.9.1.jar
echo "jar Rhino patché copié dans FrUnified/libs/"
