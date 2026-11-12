#!/usr/bin/env bash
# Syntax-checks the OpenVPN 3 JNI bridge against a MOCK of the core client API.
# The mock signatures are copied from the vendored openvpn3 headers. This catches
# C++ syntax errors, JNI misuse and override mismatches. It does NOT link or run anything.
# Usage: JNI_INCLUDE=/path/to/jdk/include ./check.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../../app/src/main/cpp/openvpn3/ovpn3_jni.cpp"
JNI_INCLUDE="${JNI_INCLUDE:?set JNI_INCLUDE to a JDK include dir containing jni.h}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cp "$SRC" "$TMP/ovpn3_jni.cpp"
for cxx in g++ clang++; do
  if command -v "$cxx" >/dev/null; then
    echo "== $cxx"
    "$cxx" -std=c++20 -fsyntax-only -Wall -Wextra -Wno-unused-parameter \
      -I"$HERE" -I"$JNI_INCLUDE" -I"$JNI_INCLUDE/linux" "$TMP/ovpn3_jni.cpp"
  fi
done
echo "syntax check passed"
