# DeltaBox — UI refinement (Refactoring UI pass)

Scope: **resources only** (`app/src/main/res`). No Kotlin/Java, Gradle, Manifest, dependencies,
view IDs, preference keys, launcher icons, cat drawables or `ic_service_*` were touched.
Base: DeltaBox_with_cat.zip. Nothing was merged from DeltaBox_PREMIUM_UI_FINAL.zip (it carries Kotlin / Manifest / DB / OpenVPN changes).

## What changed
| Area | Change | Book principle |
|---|---|---|
| Light surfaces | bg `#F3F4FA` (tinted), cards pure white + 1dp elevation + hairline | Depth: lighter card on tinted bg, subtle shadow |
| Light brand | green -> indigo `#4350D0` (text/links), `#3A46BF` (filled buttons, white text 7.5:1) | One primary hue, accessible shades |
| Greys | all neutrals tinted toward the brand hue (cool), 3 text levels: 15:1 / 7.8:1 / 4.7:1 | Greys with a hint of colour; hierarchy via colour, not just size |
| Dark surfaces | 4 distinct layers `#08080C -> #0F0F15 -> #15151D -> #1C1C26`, stroke `#2A2A38`; no shadow (0dp) | Dark mode: depth by lightness |
| Dark text | primary stays white (as requested before); secondary `#C2C4D8`, muted `#9094AE` (>=6:1) | De-emphasise without losing contrast |
| Server card | 16dp gutters, 4dp vertical rhythm, 44dp name row, 16sp title, muted 12sp meta | Spacing system, hierarchy |
| Status panel | 88dp min height, badge padding 12/4, 16sp status | 4/8dp grid |
| Drawer labels | 10sp -> 11sp (still wide-tracked caps) | Min legible size, letter-spacing for caps |
| Scale tokens | new `delta_space_1..8`, `delta_card_elevation` (1dp light / 0dp dark) | Defined spacing system |
| List bottom padding | 88dp -> 96dp (clearance for the taller panel) | — |

## Deliberately NOT changed
VPN state colours (`delta_state_*`, ping tiers, panels), cat colours, `delta_fab`, `delta_cat_button*`,
status-bar palette, Black Wall accent, all `@+id`s, themes' names (`Theme.SagerNet.Delta`, DayNight).

## Checked (static)
XML well-formed; `@+id` set identical to original (212/212); no unresolved resource refs introduced;
no night-only token without a default; only 8 files edited + 2 added, all under `res/`.
Contrast (WCAG): every text/background pair used >= 4.5:1.

## NOT checked
No Android SDK / network in the sandbox: **no Gradle build, no lint, no on-device or screenshot test was run.**
Please run `./gradlew :app:assembleDebug` and eyeball Light + Dark on the server list, drawer, settings.
Revert path: all colours are tokens in `values/colors.xml` / `values-night/colors.xml`; `RESOURCE_CHANGES.diff` lists every edit.
