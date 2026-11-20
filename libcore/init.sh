#!/bin/bash

chmod -R 777 .build 2>/dev/null
rm -rf .build 2>/dev/null

if [ -z "$GOPATH" ]; then
    GOPATH=$(go env GOPATH)
fi

# Install gomobile
# Uses upstream golang.org/x/mobile instead of the unmaintained MatsuriDayo/gomobile
# fork (branch master2), whose go.mod still carries the "gotypesalias" GODEBUG
# setting that newer Go toolchains (1.27+) reject outright. Upstream has since
# fixed gobind to support go/types.Alias directly, so it builds cleanly with
# current Go versions while still producing the same gomobile/gobind tools.
if [ ! -f "$GOPATH/bin/gomobile-matsuri" ]; then
    go install -v golang.org/x/mobile/cmd/gomobile@latest
    go install -v golang.org/x/mobile/cmd/gobind@latest
    mv "$GOPATH/bin/gomobile" "$GOPATH/bin/gomobile-matsuri"
    mv "$GOPATH/bin/gobind" "$GOPATH/bin/gobind-matsuri"
fi

GOBIND=gobind-matsuri gomobile-matsuri init
