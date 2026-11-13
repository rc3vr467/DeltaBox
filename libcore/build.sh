#!/bin/bash

source ./env_java.sh || true
source ../buildScript/init/env_ndk.sh

BUILD=".build"

rm -rf $BUILD/android \
  $BUILD/java \
  $BUILD/javac-output \
  $BUILD/src

if [ -z "$GOPATH" ]; then
  GOPATH=$(go env GOPATH)
fi

export GOBIND=gobind-matsuri

# resolve module graph after the sing-box core update
go mod tidy -e || true

"$GOPATH"/bin/gomobile-matsuri bind -v -androidapi 21 -cache "$(realpath $BUILD)" -trimpath -ldflags='-s -w -checklinkname=0 -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -X github.com/sagernet/sing-box/constant.Version=1.14.0-testing' -tags='with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,with_openvpn,badlinkname,tfogo_checklinkname0' . || exit 1
rm -r libcore-sources.jar

proj=../app/libs
mkdir -p $proj
cp -f libcore.aar $proj
echo ">> install $(realpath $proj)/libcore.aar"
