#!/usr/bin/env bash
# Compile everything under src/ and deploy to MotiveWave's dev extensions dir.
# Run this after every edit, then in MotiveWave: right-click chart -> Studies ->
# remove and re-add the study (or restart MotiveWave) to pick up class changes.
set -e
cd "$(dirname "$0")"
JAVAC="../tools/jdk-26.0.2.1+1/bin/javac.exe"
SDKJAR="C:/Program Files (x86)/MotiveWave/lib/mwave_sdk.jar"
EXT_DIR="/c/Users/MSI/MotiveWave Extensions"
DEV_DIR="$EXT_DIR/dev"

rm -rf build/classes
mkdir -p build/classes
"$JAVAC" -encoding UTF-8 -cp "$SDKJAR" -d build/classes $(find src -name "*.java")

rm -rf "$DEV_DIR"
mkdir -p "$DEV_DIR"
cp -r build/classes/* "$DEV_DIR/"
touch "$EXT_DIR/.last_updated"
echo "Deployed to $DEV_DIR"
