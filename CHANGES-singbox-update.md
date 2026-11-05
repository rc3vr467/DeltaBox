# DeltaBox core update (sing-box-testing 1.14.0-dev)
- sing-box bundled at ./sing-box (replace ../sing-box in libcore/go.mod); no longer cloned.
- sing-box/nekoutils, sing-box/boxapi: compatibility packages (hooks for geosite/geoip/selector, v2ray stats, proxy http client).
- Core patches: route/rule/rule_set_local.go (geoip:/geosite: paths), protocol/group/selector.go (OnProxySelected), box.go (CloseAllConnections).
- libcore: platform_box.go, box_include.go, dns_box.go, geoip.go, box.go, go.mod/go.sum, build.sh (tags/ldflags/go mod tidy).
- app: fmt/ConfigMigrate.kt converts the legacy config produced by ConfigBuilder to the 1.13/1.14 format (sniff/resolve actions, typed DNS servers, wireguard endpoint, domain_resolver).
- NOT compiled/tested here (no Go toolchain/network). Expect to fix compile errors from the first CI run.
