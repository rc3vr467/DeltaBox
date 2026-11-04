#!/bin/bash

if [ -z "$ANDROID_HOME" ]; then
  if [ -d "$HOME/Android/Sdk" ]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
  elif [ -d "$HOME/.local/lib/android/sdk" ]; then
    export ANDROID_HOME="$HOME/.local/lib/android/sdk"
  elif [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  fi
fi

# Try NDK versions in order of preference
_NDK=""
for NDK_VER in "25.2.9519653" "25.1.8937393" "25.0.8775105"; do
  if [ -f "$ANDROID_HOME/ndk/$NDK_VER/source.properties" ]; then
    _NDK="$ANDROID_HOME/ndk/$NDK_VER"
    break
  fi
done

# Fallback to env vars
[ -z "$_NDK" ] && [ -f "$ANDROID_NDK_HOME/source.properties" ] && _NDK="$ANDROID_NDK_HOME"
[ -z "$_NDK" ] && [ -f "$NDK/source.properties" ] && _NDK="$NDK"
[ -z "$_NDK" ] && [ -f "$ANDROID_HOME/ndk-bundle/source.properties" ] && _NDK="$ANDROID_HOME/ndk-bundle"

if [ ! -f "$_NDK/source.properties" ]; then
  echo "Error: NDK not found. Tried versions: 25.2.9519653, 25.1.8937393, 25.0.8775105"
  echo "ANDROID_HOME=$ANDROID_HOME"
  exit 1
fi

export ANDROID_NDK_HOME=$_NDK
export NDK=$_NDK
echo "Using NDK: $_NDK"
