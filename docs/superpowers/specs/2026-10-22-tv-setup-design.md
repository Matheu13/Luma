# Luma 1.2: TV setup (debloat, home screen, screensaver)

Date: 2026-10-22. Status: approved in conversation, awaiting written-spec review.

## Goal
Let anyone who installs Luma free their TV from inside Luma, with no PC: turn off ad and tracking apps,
make Luma the home screen (including on Google TV), use a calm Luma screensaver, and undo all of it with
one button. Luma keeps its promise of never going online. It talks only to the TV it runs on, and only
after the user allows developer access.

Inspired by Free My TV (github.com/simbasjungles/free-my-tv, MIT), which drives the TV's own ADB from the app.
Luma reuses its ADB client with credit. It does not adopt Free My TV's online features.

## Success criteria
- A Fire TV owner can enable developer access, press "Turn off ads & tracking", and have the Default list
  disabled. The TV keeps working normally, and nothing is uninstalled.
- "Restore original" re-enables exactly the packages Luma disabled, and restores the original home app,
  screensaver and Home-key setup.
- On Android TV and Google TV, "Make Luma the home screen" works without a PC.
- A crash loop in Luma (3 crashes within 5 minutes) returns the TV to its own launcher automatically.
- Luma's only network connection is to 127.0.0.1:5555.

## Scope
In scope (items 1–6 approved):
1. Debloat with Default and Extra tiers.
2. Restore original, plus the crash guard.
3. Make Luma the home screen via `cmd package set-home-activity`.
4. Luma screensaver (DreamService).
5. Luma's own Home-key accessibility service. Built only if Fire OS ignores `set-home-activity` (see Probe B).
6. Setup check screen, and a README setup guide for each platform.

Out of scope: trending picks, weather and prices; LG and Samsung; start-at-boot; adopting packages that were
already disabled before Luma; a unit-test framework.

## Components (app/src/com/dhyn/tvhome/)
| File | Responsibility | Depends on |
|---|---|---|
| `Adb.java` | ADB protocol client (CNXN / AUTH with RSA / OPEN `shell:` / WRTE / CLSE). Connects only to the fixed address 127.0.0.1:5555. RSA key pair stored in the app's private files dir. Notifies the UI when the TV shows the "Allow USB debugging?" prompt. Adapted from Free My TV's `Adb.java`; MIT copyright notice kept in the header. | java.net, javax.crypto |
| `TvControl.java` | The only caller of `Adb`. Exposes fixed operations: `disable(pkgs)`, `enable(pkgs)`, `setHome(component)`, `setScreensaver(component)`, `setHomeKey(on)`, `restoreAll()`, `status()`. Never runs a command built from user input or downloaded data. Checks every package against `Bloat.NEVER`. Records each change in prefs. | Adb, Bloat, prefs |
| `Bloat.java` | Static lists: `FIRE_DEFAULT`, `FIRE_EXTRA`, `ANDROID_TV_DEFAULT`, `NEVER`. Each entry has a package name, friendly label and, for Extra, a one-line warning. | — |
| `LumaDream.java` | DreamService screensaver: slow drift and crossfade through the five wallpapers, with the time. Deterministic, low CPU. | assets |
| `HomeKey.java` | AccessibilityService that opens Luma when Home is pressed on Fire TV (only if Probe B fails). | — |
| `MainActivity.java` | Settings gets a "TV setup" panel; update hint on resume; crash guard. ADB work runs on a background thread, with results posted to the UI. | TvControl |

Manifest: add `android.permission.INTERNET` (required for a localhost socket), the `LumaDream` service
(`BIND_DREAM_SERVICE`), and `HomeKey` (`BIND_ACCESSIBILITY_SERVICE`, only if built). Version 1.2, versionCode 14.

## User flow (Settings → TV setup, Luma panel style)
1. **Developer access.** Shows one-off steps for the platform (Fire TV: Settings → My Fire TV → Developer options → ADB
   debugging). Luma connects; the TV asks "Allow USB debugging?"; the user picks Always allow. If access is refused or
   off, Luma says so plainly and the other rows show as unavailable.
2. **Turn off ads & tracking.** Lists the Default tier by friendly name, confirms with "Turn off N apps" or "Cancel",
   then reports "N apps turned off. Nothing was deleted."
3. **Extra clean-up.** Extra tier items one at a time, each with its warning.
4. **Make Luma the home screen.**
5. **Screensaver: Luma / Original.**
6. **Restore original.** Asks for confirmation, then undoes everything Luma recorded.
7. **Setup check.** ✓/✗ for developer access, home screen, screensaver and apps turned off.

## Stored state (existing `luma` SharedPreferences)
`disabledByLuma` (package list), `originalHome` (component), `originalScreensaver` (component string),
`homeOnFireWasOn` (boolean), `homeKeyOn` (boolean), `crashTimes` (last 3 timestamps).

## Behaviour details
- **Already-off packages** (for example the 20 already disabled on the dev TV) are shown as "already off". They are
  never recorded in `disabledByLuma`, so Restore leaves them alone.
- **Packages that aren't installed** are skipped silently.
- **Re-enabled by an OS update:** on resume, `PackageManager.getApplicationEnabledSetting` (no ADB needed) checks
  `disabledByLuma`. If any are enabled again, Luma shows a hint offering to turn them off again.
- **Home on Fire conflict:** before turning on HomeKey, if Home on Fire's service is in
  `enabled_accessibility_services`, Luma offers to switch it off and records `homeOnFireWasOn` so Restore can turn
  it back on.
- **Crash guard:** the default uncaught-exception handler appends a timestamp. On the 3rd crash within 5 minutes it
  calls `TvControl.restoreHome()` synchronously, with a 3 s timeout, before the process exits.
- **Never-touch list (`Bloat.NEVER`)** is checked before every disable: launchers, Settings, System UI, package
  installer, app stores, input methods, accessibility services, Bluetooth and remote pairing, and Luma itself.

## Fire TV feasibility probes (run first)
- **A, read-only:** confirm Fire OS adbd accepts connections on 127.0.0.1:5555 from an app context. If it doesn't,
  stop and revisit the design for Fire TV.
- **B, reversible, ask the user before running:** `cmd package set-home-activity` to Luma, press Home, observe, then
  set Amazon's launcher back. If Fire OS ignores the setting, build `HomeKey.java`; otherwise drop item 5.

## Testing
Extend `tools/regression-audit.py` with TV-setup checks over ADB:
- a disable → restore round trip on one harmless package;
- set home → Home key → restore;
- set screensaver → restore;
- a simulated crash loop proving the guard returns the TV to its own launcher.

The audit always leaves the TV as it found it. The ADB client is verified against the real TV; no unit-test framework
is added.

## Docs and claims
- README: new features, a setup guide for each platform (Fire TV, Android TV, Google TV), and the privacy line changed to
  "Luma never goes online. It only talks to the TV it runs on, and only after you allow developer access."
  Credit Free My TV.
- LICENSE: add a third-party notice for the adapted `Adb.java`.
- Marketing: "No permissions" becomes "one permission, used only to talk to the TV itself". The launch videos need
  this edit when the music is redone.

## Order of work
1. Probes A and B.
2. `Adb.java` connecting on the dev TV.
3. Debloat and Restore.
4. Home screen.
5. Screensaver.
6. Crash guard.
7. Setup check, README, LICENSE and audit.
