# DeltaBox 1.0.8 — merged update

Base: DeltaBox-main snapshot (AdBlock, TCP Test, ConfigMigrate, drawer changes kept as-is).

## Subscription and server management
- RawUpdater: an empty parse result is an error, so a bad response can no longer delete healthy servers.
- RawUpdater: only http(s):// and content:// subscription links are accepted.
- RawUpdater: all DB writes of one update run in a single Room transaction.
- GroupUpdater: a subscription that is already updating is skipped (returns false) instead of cancelling the worker.
- GroupUpdater: DNS lookups are scoped to the update and the pool is always closed; progress counter is thread-safe.
- SubscriptionUpdater: initial delay sign fixed; one failing subscription no longer stops the others.
- GroupManager: deleting a group removes the group row and its servers in one transaction.

## Data safety
- SagerDatabase: destructive migration only for schema versions 1 and 2. Other unmatched schema changes fail instead of wiping profiles.

## Logging and privacy
- Share-link parsing logs only the scheme (links contain passwords/UUIDs).

## Connection status bar
- StatsBar: dark panel with neon border (follows the notch around the connect button).
- Left: exit country flag and name, connected server address (from the active profile), live ping (every 30 s while connected, "--" when unknown), signal bars.
- Right: upload and download speeds.
- Connect button: 104dp with electric-blue and neon-green rings (bg_connect_rings.xml); cat logo unchanged.
- "Success: HTTP handshake" message removed from the bar.
- ExitCountry restored (flag shown only when the country is known; never guessed).
- Colours in colors_status_bar.xml; server list colours (PingStyle) unchanged.

## Not verified
- No Android SDK or network in the build environment: APK not built, no device tests.
- Kotlin parse-only check passes; full compile not verified.
