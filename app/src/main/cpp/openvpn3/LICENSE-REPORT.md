# License obligations: OpenVPN 3 core vs DeltaBox (decision pending)

This is a technical summary, not legal advice. The project owner selects the license.

## Facts from the sources

- OpenVPN 3 (vendored, `LICENSE.md`): dual-licensed **AGPL-3.0-only OR MPL-2.0**. The recipient may choose either.
- DeltaBox (`LICENSE`): **GPL-3.0** (header: "GNU General Public License ... version 3").
- Vendored texts: `LICENSES/AGPL-3.0-only.txt`, `LICENSES/MPL-2.0.txt`. Copyright notices in each `.hpp/.cpp` must be kept unchanged.

## Option A: use OpenVPN 3 under MPL-2.0

- File-level copyleft: any MPL-covered file you modify must be distributed under MPL-2.0 with its source. The bridge files (`ovpn3_jni.cpp`) are new files; they are not MPL-covered unless they copy OpenVPN code.
- Larger work (MPL-2.0 section 3.3): DeltaBox may be distributed as a larger work under GPL-3.0, provided the MPL files stay under MPL-2.0 and their source remains available.
- Keep: MPL notices in headers, a copy of MPL-2.0, and a statement of which files are MPL.
- The FSF lists MPL-2.0 as compatible with GPL-3.0 (to be confirmed by counsel for this combination).
- No network-use source obligation.

## Option B: use OpenVPN 3 under AGPL-3.0

- Combining AGPL-3.0 code with GPL-3.0 code in one program means the combined work is distributed under AGPL-3.0 terms. DeltaBox's own code remains GPL-3.0 on its own; the combined binary must comply with both. Counsel should confirm.
- Distribution (APK): full corresponding source of the combined work must be available to recipients.
- AGPL section 13: if users interact with a modified version over a network, the modified source must be offered to them. For a client that only connects outward, this is probably not triggered, but it must be checked for any server-side use.
- Keep: all AGPL notices and a copy of the AGPL-3.0 text.

## What the project must do in either case

1. Keep all copyright and license headers in vendored files (unchanged).
2. Ship `LICENSE.md` and `LICENSES/` with the app source.
3. Make the complete corresponding source available for each released APK.
4. Record which license was chosen in `README-STATUS.md`.

## OpenSSL exception (applies to the AGPL-3.0 option)

`LICENSES/openvpn3-openssl-exception.txt` grants an additional permission under AGPL-3.0 section 7: the work may be linked with OpenSSL (or a modified OpenSSL) and conveyed, provided the Corresponding Source of the combination includes the source of the OpenSSL parts used. Any build that links OpenSSL (the JNI library does) must therefore ship or point to the OpenSSL source for the version used. This exception is part of the AGPL option only; it does not add obligations for the MPL option.
