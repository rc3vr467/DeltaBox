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

_NDK=""

# 1) Preferred versions, in order
for _VER in 25.2.9519653 25.1.8937393 25.0.8775105; do
  if [ -f "$ANDROID_HOME/ndk/$_VER/source.properties" ]; then
    _NDK="$ANDROID_HOME/ndk/$_VER"
    break
  fi
done

# 2) Environment variables (GitHub Actions runners set ANDROID_NDK_HOME)
[ -z "$_NDK" ] && [ -f "$ANDROID_NDK_HOME/source.properties" ] && _NDK="$ANDROID_NDK_HOME"
[ -z "$_NDK" ] && [ -f "$NDK/source.properties" ] && _NDK="$NDK"

# 3) Any installed side-by-side NDK (newest), then legacy ndk-bundle
if [ -z "$_NDK" ] && [ -d "$ANDROID_HOME/ndk" ]; then
  for _D in $(ls -1d "$ANDROID_HOME"/ndk/*/ 2>/dev/null | sort -V -r); do
    if [ -f "${_D}source.properties" ]; then
      _NDK="${_D%/}"
      break
    fi
  done
fi
[ -z "$_NDK" ] && [ -f "$ANDROID_HOME/ndk-bundle/source.properties" ] && _NDK="$ANDROID_HOME/ndk-bundle"

if [ -z "$_NDK" ]; then
  echo "Error: NDK not found (ANDROID_HOME=$ANDROID_HOME)" >&2
  exit 1
fi

export ANDROID_NDK_HOME=$_NDK
export NDK=$_NDK
