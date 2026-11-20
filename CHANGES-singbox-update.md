# DeltaBox core update (sing-box 1.15.0-alpha.13)
- ./sing-box replaced with upstream sing-box 1.15.0-alpha.13 (docs/test/release/clients dropped, as before).
- DeltaBox patches re-applied: nekoutils + boxapi compat packages, route/rule/rule_set_local.go (geoip:/geosite: paths), protocol/group/selector.go (OnProxySelected hook), box.go (CloseAllConnections).
- libcore/go.mod: sagernet modules (sing-tun, cronet-go*, sing-openconnect, tailscale) pinned to the versions required by 1.15.0-alpha.13; libcore/go.sum merged with the core's go.sum; build.sh version string -> 1.15.0-alpha.13.
- NOTE: the previously bundled core was a newer testing snapshot (deps dated 2026-11-03, with HostPort/flow-tracker changes); alpha.13 is dated 2026-10-23, so some late upstream fixes are not included.
- TUN `stack` option is deprecated in 1.15 (removed in 1.17); it still works.
- NOT compiled here (no Go toolchain/network). Expect to fix compile errors, if any, from the first CI run.

# DeltaBox core update (sing-box-testing 1.14.0-dev)
- sing-box bundled at ./sing-box (replace ../sing-box in libcore/go.mod); no longer cloned.
- sing-box/nekoutils, sing-box/boxapi: compatibility packages (hooks for geosite/geoip/selector, v2ray stats, proxy http client).
- Core patches: route/rule/rule_set_local.go (geoip:/geosite: paths), protocol/group/selector.go (OnProxySelected), box.go (CloseAllConnections).
- libcore: platform_box.go, box_include.go, dns_box.go, geoip.go, box.go, go.mod/go.sum, build.sh (tags/ldflags/go mod tidy).
- app: fmt/ConfigMigrate.kt converts the legacy config produced by ConfigBuilder to the 1.13/1.14 format (sniff/resolve actions, typed DNS servers, wireguard endpoint, domain_resolver).
- NOT compiled/tested here (no Go toolchain/network). Expect to fix compile errors from the first CI run.
