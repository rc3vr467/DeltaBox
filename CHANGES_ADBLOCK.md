# Changes
- AdBlock (bg/AdBlockEngine.kt): switch in Black Wall screen under the level list. Adds a single sing-box `reject` route rule
  (domain_suffix list) in ConfigBuilder for the next connection; proxy servers are never blocked; any error is swallowed so the core still starts.
  Lists: assets/adblock_default.txt + downloadable hosts lists (AdAway sources).
- Black Wall screen: "How it works" card removed; switches use visible gray when off.
- Drawer: NavigationView elevation/shadow/scrim removed (the white boxes in light theme).
- Overflow menu: new "TCP Test" (direct TCP connect time, no core).
- Connect button: blue progress ring removed; disconnected = red cat only (no ring); connected = green ring only.
- Status panel: shows real exit IP + country flag/name (via tunnel, ExitIpInfo.kt) instead of server name.
