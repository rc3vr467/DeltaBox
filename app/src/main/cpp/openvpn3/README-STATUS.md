# OpenVPN 3 engine — status

Last updated in this tree. Read this before enabling the engine.

## What is in the tree

| Path | Contents | Status |
|---|---|---|
| `client/`, `openvpn/`, `LICENSE.md`, `LICENSES/` | Vendored from `openvpn3_master` @ `0b27d9d`, unmodified | copied; not compiled |
| `ovpn3_jni.cpp` | JNI bridge `OvpnSession : OpenVPNClient` | every TunBuilder override checked against the vendored headers; **syntax-checked** with g++ 13 and clang against a mock of the core API (`-fsyntax-only`); **not linked, not run** |
| `CMakeLists.txt` | Native build of the bridge + core | not run |
| `vcpkg.json` | fmt, lz4, jsoncpp, Asio, OpenSSL | installs happen only in CI |
| `../../java/.../ovpn3/OpenVpn3Native.kt` | JNI `external` declarations; `isLibraryLoaded` loads `libovpn3_jni.so` lazily | not compiled by kotlinc here |
| `../../java/.../ovpn3/OpenVpn3Listener.kt` | Callback interface; names and signatures match the `GetMethodID` calls | matches C++ descriptors (static check) |
| `../../java/.../ovpn3/OpenVpn3TunListener.kt` | Implements the listener on `VpnService.Builder` | not run on a device |
| `../../java/.../ovpn3/OpenVpn3Engine.kt` | Owns one native session: start / stop / destroy, single-session guard | not run |

## Bugs fixed in this revision (found by reading the core and by the C++ syntax check)

- `tun_builder_new()` and `tun_builder_set_layer()` were not overridden. The core calls both unconditionally and the base defaults return `false`, so **every** connection would have failed during TUN setup.
- `tun_builder_reroute_gw()` was not overridden, so any server pushing `redirect-gateway` would have failed. It is now emulated with `0.0.0.0/1` + `128.0.0.0/1` (and `::/1`, `8000::/1` for IPv6).
- Optional settings with no VpnService equivalent (proxy bypass, PAC URL, HTTP/HTTPS proxy, WINS, route metric) now return `true` and are logged as ignored, instead of aborting the connection.
- `AttachCurrentThread` got `JNIEnv**` where the C++ JNI API needs `void**`. Found by the compiler.
- `start()` could be called twice, which would call `std::thread` on a joinable thread and terminate the process. A guard is in place.
- A stop request that arrives before `connect()` starts is now honoured. `nativeDestroy()` keeps calling core `stop()` until the connect thread reports it has finished, for up to 10 s. If the core still has not stopped, the session is **kept** rather than freed, to avoid a use-after-free. It returns `false` in that case.
- Credentials and profile text copied into C++ `std::string`s are overwritten after use. This does not reach the core's own copy, and Kotlin `String`s cannot be wiped.
- `System.loadLibrary` was never called. It is now called lazily, so a default build never loads it.

## Not in place (explicit)

- **Service integration**: `OpenVpn3Engine` is not called from `bg/VpnService.kt` or `bg/BaseService.kt`. The existing sing-box/Xray path is unchanged.
- **Profile type, UI, strings**: no OpenVPN bean, no edit screen, no strings. The `openvpn_*` resources referenced by the failing CI tree are not in any provided source (see below).
- **Credentials, certificates, CA, private keys**: the engine accepts config text, username and password. Storage and UI are not implemented.
- **Reconnect and network switching**: not implemented.
- **Excluded routes**: `VpnService.Builder` has no exclude API, so `tun_builder_exclude_route` returns `false` and the core reports the failure. Connections that rely on exclusions will fail until this is handled.
- **DNS**: global servers and search domains are applied. Per-domain routing, custom ports and DoT/DoH are logged and not applied.
- **External PKI** and the app control channel: unsupported.
- **Licence**: OpenVPN 3 is AGPL-3.0-only OR MPL-2.0 with an OpenSSL exception. DeltaBox stays GPL-3.0 until the owner decides. See `LICENSE-REPORT.md`.

## Build wiring

- Gradle: `app/build.gradle.kts` adds `externalNativeBuild` **only** when `-Pdeltabox.openvpn3=true` is set. The default build does not configure CMake.
- The Kotlin files in `io.nekohasekai.sagernet.ovpn3` are compiled in the default build. They use only public Android APIs and do not load the library.
- CI: `.github/workflows/openvpn3-native.yml`. The default job builds without the engine. The engine job builds the native core for `arm64-v8a`, `armeabi-v7a` and `x86_64`, then fails if `libovpn3_jni.so` is missing from the APK for any of them.

## Tests

- Not run: Gradle, Kotlin compile, native link, device, OpenVPN server.
- Run: `tools/ovpn3-syntax-check/check.sh` (g++ and clang++ `-fsyntax-only` on `ovpn3_jni.cpp` against a mock of the core API; signatures copied from the vendored headers). Both pass with `-Wall -Wextra`.
- Required before enabling: CI build for all three ABIs; a host lifecycle test (create/start/stop/destroy in a loop under ASan/TSan); a device test against a real OpenVPN server, including redirect-gateway and IPv6 cases.

## Source of the `openvpn_preferences.xml` failure

The CI log references `io.nekohasekai.sagernet.app-main-71:/xml/openvpn_preferences.xml` and about 18 `openvpn_*` strings and 8 arrays that do not exist. None of the provided sources (this tree, DeltaBox 1.0.8, the OpenVPN 3 master ZIP) contain a file or string with that name, so the CI checkout is a different tree. Not resolved here.
