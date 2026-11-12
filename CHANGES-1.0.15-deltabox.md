# DeltaBox 1.0.15 — changes in this delivery (reference tree: app/)

Status legend: DONE = written and reviewed by reading, NOT compiled; NOT DONE = see "Not done".

## DONE
1. Dark/Light palette (Phase 1)
   - `res/values/colors.xml`, `res/values-night/colors.xml`: delta_* tokens set to the approved palette
     (dark: #07070A / #0D0D12 / #14141B / #191922 / border #292832 / primary #8B5CF6 / secondary #A78BFA;
     light: #F5F6FA / #FFFFFF / #F0EFF6 / border #E5E3ED / primary #7041CF / secondary #8B5CF6).
   - `colors_delta_glass.xml` (+night): glass/popup strokes and fills aligned to the palette.
   - New `colors_deltabox_tokens.xml` (+night): chart colours.
   - Cat/brand colours (`delta_cat_button*`), `colors_cat.xml`, `ic_service_*` and launcher assets were NOT touched.
   - Values derived by me, not in your spec: light muted text #8A8696, light brand-dark #5B32B0.
   - Dark muted text #777586 (from your spec) has low contrast on the card colour; check readability.
   - State panel tint on the status bar is now the card colour in all states (state is still shown by dot + text colour).
   - Existing Theme.kt / ThemedActivity / DataStore.nightTheme (System/Dark/Light) were reused, not changed.
2. Live traffic chart (Phase 3)
   - New `widget/TrafficRingBuffer.kt` (90 samples, no allocation, session reset), `widget/TrafficChartView.kt`
     (Canvas, reused Paint/Path/gradients, smooth curves, gap handling, idle/waiting/active states).
   - Fed ONLY from the existing path `TrafficLooper -> SpeedDisplayData -> MainActivity.cbSpeedUpdate -> StatsBar.updateSpeed`
     (txRateProxy / rxRateProxy; direct/bypass rates are not mixed in). No rate is recomputed.
   - Sampling interval is unchanged (DataStore.speedInterval, default 1000 ms). 500 ms was NOT adopted (battery not measured).
   - Chart is hidden when speedInterval = 0 (no samples exist). Session totals (txTotal/rxTotal) are shown above the chart.
   - Edited: `layout_main.xml` (status row wrapped, chart + totals added below), `StatsBar.kt`, `MainActivity.kt` (1 call).
   - Known limit: the service may not deliver samples while the app is in background; the chart shows that as a gap.
3. Server diagnostics (Phase 4, partial)
   - New package `diagnostics/`: test kinds (TCP connect, URL latency through proxy), outcomes, health states
     (Unknown, Testing, Healthy, Slow, Unreachable, Timeout, Error), median / average / jitter / success rate /
     timeout rate / last success, internal score (needs >= 3 fresh samples of the same kind, else Unknown).
   - Results are in memory only (no Room change), per server and per test kind, with timestamps, 10 min expiry,
     and invalidation when the default network changes (`SagerNet.kt` network listener hook).
   - `ConfigurationFragment.runConnectionTest` records every real test result (cancelled tests are not recorded).
     Concurrency/duplicate prevention remain the existing `DataStore.runningTest` and `connectionTestConcurrent`.
   - Long-press a ping badge in the server list to see the measured data (`ServerQualityDialog`).
   - Unit tests: `src/test/.../ServerQualityCalculatorTest.kt`, `TrafficRingBufferTest.kt`.
4. Hygiene: `.gitignore` now ignores keystores; `docs/SIGNING.md`; `docs/BUILD_AND_VERIFY.md`.

## OpenVPN (Phase 5/6) — status, no code changed
- NOT active in the APK: `libcore/build.sh` has no `with_openvpn` tag (stub returns "OpenVPN is not included"),
  and the Kotlin app has no OpenVPN profile type, parser, UI or ConfigBuilder support.
- Static read covered about 150 of 4,092 lines (client.go dial/start/readLoop/waitReady, socket_buffer_linux.go).
  No provable bug was found in those parts: state updates are copy-on-write (no double-lock), loops are tied to the
  scope context. This does NOT mean the rest is correct; it was not reviewed.
- Suspect, unproven: fixed 7 MB UDP buffer with `SO_*BUFFORCE` and ignored errors (needs on-device `getsockopt` check).
- To verify before any enabling: that dials go through the platform socket protection, and `build_libbox` tag set vs `libcore/build.sh`.
- No speed/CPU/RAM numbers exist: no benchmark was run.

## Not done (in this delivery)
- Phase 2 full home redesign (connection/traffic/active-server cards). The existing status bar was kept and extended.
- Phase 7 server details screen, Phase 8 drawer redesign beyond colours, Phase 9 Black Wall layout redesign.
- Phase 12 RecyclerView/DiffUtil work: counted in `ConfigurationFragment.kt`: 0 DiffUtil uses and 3 `notifyDataSetChanged` calls; not changed.
- Real throughput test (needs an approved endpoint and a connected tunnel), ICMP ping (not possible without raw sockets),
  packet loss and handshake-only timing (not measurable with the current core API).
- `libcore/app/` (second, divergent app tree) was left untouched as decided.
