# Signing dependency (release.keystore)

The original source archive contained two DIFFERENT `release.keystore` files (repository root and
`libcore/`). They are NOT included in the delivered ZIP and nothing was deleted from your working copy.

How the build uses a key (`buildSrc/src/main/kotlin/Helpers.kt`, `setupAppCommon`):
- Reads `KEYSTORE_PASS`, `ALIAS_NAME`, `ALIAS_PASS` from `local.properties`, the environment, or the
  base64 `LOCAL_PROPERTIES` environment variable.
- Only when `KEYSTORE_PASS` is set, a `release` signing config is created with
  `storeFile = <repo root>/release.keystore`, and BOTH `release` and `debug` builds are signed with it.
- Without `KEYSTORE_PASS`, nothing is signed with it: debug builds use the normal Android debug key.

To build signed:
1. Put your keystore at `<repo root>/release.keystore` (it is git-ignored now).
2. Set `KEYSTORE_PASS`, `ALIAS_NAME`, `ALIAS_PASS` in `local.properties` (git-ignored) or the environment.

Security follow-up (needs your decision, not done automatically):
- If the repository containing these files was ever public, treat that key as exposed. Its password
  was not in the source, so the file alone is not a usable key, but it has been distributed.
- Rotating the key changes the app signature: existing installs cannot be updated in place
  (sideloaded APKs), and a Play-listed app needs Play App Signing key rotation. Plan this with your
  release process before changing the key.
- Remove the files from git history (for example with `git filter-repo`) only after deciding on rotation.
