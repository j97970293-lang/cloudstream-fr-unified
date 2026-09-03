#!/usr/bin/env bash
# Régénère libs/rhino-{nuvio,embed}-1.9.1.jar à partir de
# tools/rhino-patched-base-1.9.1.jar (Rhino 1.9.1 PATCHÉ, package org.mozilla.javascript)
# en relogeant tout sous com.frunified.rhino.
#
# POURQUOI : l'app CloudStream embarque son propre Rhino stock (classes
# org.mozilla.javascript.* dans son APK, classes8.dex). Le classloader du plugin
# est parent-first : notre Rhino patché était donc MASQUÉ par le Rhino stock de
# l'app sur le téléphone (d'où des erreurs de syntaxe uniquement sur l'appareil,
# invisibles sur JVM). En relogeant sous com.frunified.rhino, aucun conflit.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
JAVA_BIN="${JAVA_HOME:-}/bin/java"
JAVAC="${JAVA_HOME:-}/bin/javac"
JAR="${JAVA_HOME:-}/bin/jar"
[ -x "$JAVA_BIN" ] || JAVA_BIN=java
[ -x "$JAVAC" ] || JAVAC=javac
[ -x "$JAR" ] || JAR=jar
ASM="$WORK/asm"
mkdir -p "$ASM"
for a in asm asm-commons asm-tree asm-analysis; do
  [ -f "$ASM/$a.jar" ] || curl -sL -o "$ASM/$a.jar" "https://repo1.maven.org/maven2/org/ow2/asm/$a/9.7.1/$a-9.7.1.jar"
done
CP="$ASM/asm.jar:$ASM/asm-commons.jar:$ASM/asm-tree.jar:$ASM/asm-analysis.jar"
cd "$ROOT/tools/relocate-rhino"
"$JAVAC" -encoding UTF-8 -cp "$CP" Relocate.java
"$JAVA_BIN" -cp ".:$CP" Relocate "$ROOT/tools/rhino-patched-base-1.9.1.jar" "$WORK/relocated-full.jar"
# Version complète (compilation + tests JVM)
cp "$WORK/relocated-full.jar" "$ROOT/FrUnified/libs/rhino-nuvio-1.9.1.jar"
# Version déxée : sans commonjs / optimizer / module-info
rm -rf "$WORK/embed" && mkdir -p "$WORK/embed" && cd "$WORK/embed"
"$JAR" xf "$WORK/relocated-full.jar"
rm -rf com/frunified/rhino/commonjs com/frunified/rhino/optimizer module-info.class
"$JAR" cf "$ROOT/FrUnified/libs/rhino-embed-1.9.1.jar" .
echo "OK : libs/rhino-nuvio-1.9.1.jar + libs/rhino-embed-1.9.1.jar (package com.frunified.rhino)"
