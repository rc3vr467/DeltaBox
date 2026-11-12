# Build and verify on your machine

This delivery was NOT compiled (the authoring environment had no Go, Android SDK/NDK or network).
Run these in order and send me the full output of anything that fails.

1. Core (needs Go + gomobile-matsuri + NDK as in `libcore/build.sh`):
   `chmod +x run gradlew libcore/*.sh && ./libcore/build.sh`   (produces `app/libs/libcore.aar`)
   Note: `CHANGES-singbox-update.md` says the Go core update had never been compiled either.
2. App: `./gradlew :app:assembleDebug`
3. Unit tests added in this delivery: `./gradlew :app:testDebugUnitTest`
   (`ServerQualityCalculatorTest`, `TrafficRingBufferTest`; JUnit 4.13.2 was added as testImplementation)
4. Lint/resources: `./gradlew :app:lintDebug`
5. Manual checks on a device: switch System/Dark/Light, connect, watch the chart with real traffic,
   disconnect and reconnect (chart must restart), run TCP test and URL test, long-press a ping badge.
