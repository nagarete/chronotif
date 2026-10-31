# Chronotif guide

[Back to the overview](../README.md)

Setup help, notification behaviour, privacy rules, and development details.

## How it works

| Piece | What it does | Cost when idle |
|---|---|---|
| Notification listener | Mirrors active notifications in memory; opens/dismisses/acts on them | Event-driven, no polling |
| Edge handle | A slim pill on the screen edge. Tap or swipe it to open the panel. Tinted with your accent colour while something is NEW | One tiny overlay window, no timers |
| Status-bar swipe (optional) | An invisible touch area on the left half of the status bar. Tap or swipe down there to open the panel instead of the system notifications | Two tiny overlay windows, no timers |
| Quick Settings tile | Another way into the panel; shows "N new" | Only when the shade is open |
| Panel | The shade-like screen: blurred backdrop, calls and media, NEW / EARLIER, collapsed Running. Drawn over the current app like the system shade, so videos and games keep running underneath | Exists only while open |
| Lock-screen view | Shown over the locked keyguard when the screen turns on | Started only on screen-on |

No foreground service, wakelocks or alarms. The only scheduled work is the once-a-day update
check, and only if you turn it on.

### What counts as a "meaningful check"

- The panel was open for at least 1.5 s, or you interacted with it. When it closes, everything
  it showed moves to EARLIER.
- On the lock screen, glancing isn't enough. Like iOS, the lock-screen notifications move to
  EARLIER when you **unlock** after they were shown.
- A notification comes back to NEW only when it actually brings something new: e.g. a new
  message in an existing chat. Incoming messages count even when the app marks the update
  "alert only once". Timestamp refreshes and progress updates don't.

The only persisted state is that last-check timestamp plus your settings, stored in app-private
storage and excluded from backups.

### Lock-screen privacy

- Content is only hidden when the device has a secure lock (PIN/pattern/password), matching
  Android itself.
- **Secret** notifications (by the app, or your per-channel setting) are not shown at all.
- **Private** notifications are redacted ("App · Notification", or the app's public version)
  when Android's *hide sensitive content* setting is on, or when you turn on
  *Hide sensitive content* in Chronotif.
- If Android's *show notifications on lock screen* is off, the lock-screen view never appears.
- Opening, actions and replies always go through the system unlock first. Swiping a
  notification away doesn't.
- It never covers a ringing call or an alarm/call screen.

## Install

1. Download `chronotif-vX.Y.Z.apk` from
   [Releases](https://github.com/nagarete/chronotif/releases) (verify against the `.sha256` file if you like) and open it from
   your file manager or browser. Allow "install unknown apps" for that app if asked.
   Google Play Protect may stop the install because it hasn't seen the app before; see
   [Google Play Protect](#google-play-protect) below.
2. Open **Chronotif** and work through *Permissions*:
   - **Notification access.** Android 13+ blocks this for apps installed outside the Play
     Store, so the first attempt shows **"App was denied access: access to this permission
     can put your personal and financial info at risk"** (or a greyed-out toggle). That is
     expected. Press **Close**, then allow it once:
     1. Open **Settings → Apps → Chronotif** (or long-press the app icon → *App info*).
     2. Tap **⋮** (top right) → **Allow restricted settings**, and confirm with your PIN or
        fingerprint if asked. On **HyperOS** it's a switch instead: scroll down App info to
        **Advanced settings → Allow restricted settings**.
     3. Go back to Chronotif and tap **Grant** on *Notification access* again; this time the
        toggle works.

     *Allow restricted settings* only appears after you have tried to enable the permission
     once. If you don't see it, try the toggle first, then come back.

     <img src="../website/public/screens/install-denied.webp" width="300" alt="Android's 'App was denied access' dialog, with a Close button.">
     <img src="../website/public/screens/install-restricted.webp" width="300" alt="App info on HyperOS, with the Allow restricted settings switch under Advanced settings highlighted.">
   - **Display over other apps**: for the edge handle, the lock-screen view, and opening the
     panel over the current app without pausing it.
3. Optional: add the **Chronotif** Quick Settings tile (button in setup on Android 13+).
4. Optional: turn on the **status-bar swipe** (see [Status-bar swipe](#status-bar-swipe)).

After that you never need to open the app again. Use the edge handle or the tile.

### Updates

Choose **Stable** or **Nightly** under **Settings → Updates → Update channel**. Stable is the
default in stable APKs. Installing a nightly APK opts into Nightly unless you already chose a
channel. Both channels use the same app ID and signing key, so updates keep your settings and
permissions. Nightly contains changes still being tested. Switching back to Stable waits for
that version's stable release or a newer one; it does not downgrade the app.

Get stable from [the latest release](https://github.com/nagarete/chronotif/releases/latest)
and nightlies from [the release list](https://github.com/nagarete/chronotif/releases?q=nightly&expanded=true).
v0.2.2 and older have no channel setting. Install a nightly APK once to opt into Nightly.
Older updaters ignore nightly tags and continue to receive stable versions.

Turn on *Settings → Updates → Update automatically* and Chronotif checks GitHub Releases once
a day. When there's a newer version it downloads the APK, checks it against the release's
`.sha256` file and installs it:

- If **Install unknown apps** is allowed for Chronotif (Android 12+), the update installs
  silently and you get an "updated" notification.
- Otherwise you get an "update available" notification. Tap it to confirm the install.

*Check for updates* does the same thing on demand. The check reads the public release list
and sends nothing about you or your notifications. Android only accepts an update signed with
the same key as the installed app, so a tampered APK can't install.

Updating from **v0.1.0** has to be done by hand once: uninstall it, then install the new
version. v0.1.0 has no updater and was signed with a temporary key.

### Google Play Protect

Chronotif isn't on the Play Store, so **Google Play Protect** checks it the first time you
install it (and sometimes after an update). This is about the app being unknown to Google, not
about a problem with the APK. What you see depends on the phone and Play Protect version:

- **"Send app for a security check?"** or **"App scan recommended"**: tap **Scan app** (or
  **Send**). The install continues once the scan finishes.
- **"Blocked by Play Protect"** or **"Unsafe app blocked"**: tap **More details → Install
  anyway**.
- A bare **"App not installed"**: see below.

#### "App not installed"

Installing an unknown, newly released app from a file manager can also fail with a bare
"App not installed" (in `adb logcat`: `INSTALL_FAILED_VERIFICATION_FAILURE: Install not
allowed`). That's Play Protect too. Pause its scanning for a day, then open the APK again:

1. Open the **Play Store**, tap your profile icon, then **Play Protect**.
2. Tap **⚙** (top right).
3. Switch off **Scan apps with Play Protect**.
4. Choose **Pause**, not *Turn off*. Scanning turns itself back on the next day.

Or install over USB with `adb install chronotif-vX.Y.Z.apk`, which skips the on-device check.

<img src="../website/public/screens/install-playprotect-menu.webp" width="300" alt="The Play Store profile menu, with Play Protect highlighted.">
<img src="../website/public/screens/install-playprotect-home.webp" width="300" alt="The Play Protect screen, with the settings gear at the top right highlighted.">
<img src="../website/public/screens/install-playprotect-settings.webp" width="300" alt="Play Protect settings, with the Scan apps with Play Protect switch highlighted.">
<img src="../website/public/screens/install-playprotect-pause.webp" width="300" alt="Pause app scanning instead? dialog, with the Pause button highlighted.">

### Xiaomi / HyperOS / MIUI (Poco X3 NFC, Xiaomi 17T Pro)

Xiaomi adds its own permission layer. In setup, the *Xiaomi permissions* and *Xiaomi autostart*
buttons jump to the right screens:

- **Other permissions → Show on Lock screen**: required for the lock-screen view.
- **Other permissions → Display pop-up windows while running in the background**: required for
  the handle and lock-screen view to open.
- **Autostart**: on. Required: without it, MIUI blocks Chronotif from reconnecting after you
  clear recent apps, after a reboot, or whenever it closes Chronotif in the background.
  Notification access stays granted, but Chronotif stops receiving notifications until you turn
  access off and on again. Setup shows a warning when this happens.
- **Battery saver → No restrictions** for Chronotif (App info → Battery saver).

Optionally, lock Chronotif in Recents so the system doesn't kill it.

If you use the status-bar swipe, Xiaomi Security may now and then ask whether Chronotif should
keep its accessibility access. Choose to keep it, or the swipe stops working.

### Status-bar swipe

Android doesn't let apps receive touches on the status bar, except through an accessibility
service. Chronotif's is optional. It places an invisible touch area over the **left half** of
the status bar (on HyperOS, the side that opens notifications). Tap or swipe down there and
Chronotif opens instead of the system notifications. The right half keeps its normal system
behaviour, e.g. Control Center. When you use the Quick Settings tile, it also closes the system
shade, so the panel can open over your app without pausing it.

The service subscribes to no accessibility events and can't retrieve other apps' screen
content. Notification content still comes only through notification access.

It also needs **Display over other apps**: besides the touch area, Chronotif keeps a 1-pixel,
fully transparent, untouchable overlay that tells it when a fullscreen app hides the status bar.
Without that permission the touch area isn't shown.

To turn it on, tap **Grant** next to *Status-bar swipe* in setup, and turn on Chronotif's
service in Android's Accessibility settings. Depending on the phone it's listed as
**Status-bar swipe** or **Chronotif**, sometimes under *Downloaded apps*. Android 13+ may block this for sideloaded apps the same way as
notification access; use **Allow restricted settings** as described in [Install](#install), then
try again. Android shows a warning that the service can control your device; that's the
standard warning for every accessibility service.

The touch area is hidden while the phone is locked, in landscape, while Chronotif's own panel or
lock-screen view is open, and when *Use status-bar swipe* or *Chronotif is on* is off. While a
fullscreen app hides the status bar, touches pass through to the app. It covers the main display
only.

**Fullscreen apps** (videos, games): the status-bar swipe doesn't work there. When you swipe the
status bar in temporarily, the system handles swipes on it itself, so they open the system shade.
To open Chronotif in fullscreen, use the Quick Settings tile, or turn off the edge handle's
*Hide in landscape*.

## Using it

- **Open the panel**: tap the edge handle, or swipe it down/inward; or use the QS tile. With the
  [status-bar swipe](#status-bar-swipe) on, tap or swipe down on the left half of the status bar.
- **Close**: back, tap empty space, or fling up at the end of the list.
- **Open a notification**: tap it.
- **Dismiss**: swipe sideways. Swiping a collapsed stack dismisses the whole stack.
  **Clear** clears EARLIER; × clears an expanded stack.
- **Expand**: chevron or long-press. Expanded cards show full text, all conversation messages
  Android currently supplies, pictures and actions. Conversation history includes already-checked
  messages without requiring you to open EARLIER first.
- **Reply**: expand, tap the reply action, type, send.
- **Lock screen**: tap outside a card or swipe up (or use fingerprint/face) to unlock; back
  reveals the system lock screen. Left untouched, Chronotif stays visible until Android puts the
  display to sleep, without a timed handoff to the stock lock screen. This follows the device's
  display timeout, which can be longer than the stock lock-screen timeout.
  *Settings → Lock screen* chooses Off / *When something is new* (default) /
  *Whenever there are notifications*.
- **Turn it off**: switch off *Settings → Chronotif is on* to hide the edge handle, status-bar
  swipe and lock-screen view without uninstalling. Your handle and lock-screen settings are kept for when
  you turn it back on. The Quick Settings tile shows "Off" and still opens the panel.

The panel opens as a window over the current app only when Chronotif may *display over other
apps* and the phone is unlocked. Otherwise it opens as a regular screen, which pauses the app
underneath. From the Quick Settings tile it only opens as a window when the
[status-bar swipe](#status-bar-swipe) is in use (its service closes the system shade first) and
the phone runs Android 12 or newer.

## Limitations

Android doesn't let a third-party app replace the system shade or lock screen, so Chronotif
works **alongside** them:

- Swiping down from the top still opens the system shade, unless you turn on the
  [status-bar swipe](#status-bar-swipe); even then, the right half of the status bar and the
  launcher's own swipe down on the home screen and swipes in fullscreen apps still open the
  system's. Heads-up banners are
  still the system's.
- The lock-screen view is an activity on top of the (still locked) keyguard. You may briefly see
  the system lock screen as the screen turns on. It shows your home wallpaper.
- On stock Android 8+, the system posts "Chronotif is displaying over other apps". Long-press it
  to turn that notification off.
- Custom notification layouts (RemoteViews) are rendered from their text, not pixel-for-pixel.
- Apps that refuse input while *any* overlay is on screen (rare, mostly banking) may complain.
  Disable the edge handle and the status-bar swipe, and use the tile instead.

## Build

Requirements: JDK 17+ and the Android SDK (platform 36).

```sh
./gradlew testDebugUnitTest lintDebug assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

Without signing variables, release builds are signed with the debug key. To sign properly:

```sh
keytool -genkeypair -v -keystore release.jks -alias chronotif -keyalg RSA -keysize 4096 -validity 10000
export CHRONOTIF_KEYSTORE=$PWD/release.jks CHRONOTIF_KEYSTORE_PASSWORD=… \
       CHRONOTIF_KEY_ALIAS=chronotif CHRONOTIF_KEY_PASSWORD=…
./gradlew assembleRelease
```

Keep the keystore safe and out of git. Android only installs updates signed with the same key,
so losing it means every user has to uninstall and reinstall.

### Releasing on GitHub

`.github/workflows/build.yml` builds and tests pushes to main and pull requests. Published
APKs require these repository secrets: `KEYSTORE_B64` (`base64 -w0 release.jks`),
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`. Both channels use this same signing key.
Each release includes an APK and its SHA-256 checksum.

`versionName` in `app/build.gradle.kts` is the next stable version. Nightlies append
`-nightly.<build>` and use tags `nightly-<version>-<build>`. Their version codes increase with
the build number; the stable version reserves the highest code in that version's range so
it can replace its nightlies. Build numbers must be 1 through 9998.

At 01:00 Asia/Jakarta each day, CI publishes a nightly from main if its commit changed since
the last nightly. You can also run the build workflow manually to publish a nightly from a
selected branch. Scheduled and manual builds use the workflow run number. GitHub only runs
the schedule once this workflow is on the default branch.

To publish a specific commit as a nightly, commit it and push a tag with an unused build
number greater than previous nightlies for the same version:

```sh
git tag nightly-0.2.5-1
git push origin nightly-0.2.5-1
```

To build that nightly locally:

```sh
./gradlew testDebugUnitTest lintDebug assembleRelease -PnightlyBuild=1
```

To publish stable, push a tag matching `versionName`:

```sh
git tag v0.2.5
git push origin v0.2.5
```

Stable releases are marked as GitHub's latest release, including 0.x versions. Nightlies are
prereleases and never replace that link. After publishing stable, bump `versionName` to the
next patch version before publishing more nightlies. v0.2.4 is the current stable release.

## Code map

```
app/src/main/java/app/chronotif/
  core/Attention.kt     pure attention model: NEW/EARLIER split, stacks, arrival tracking (unit-tested)
  NotifStore.kt         in-memory mirror of active notifications
  ListenerService.kt    the notification listener; screen on/off/unlock events
  EdgeHandle.kt         edge overlay
  PanelTile.kt          Quick Settings tile
  LockPolicy.kt         when the lock view appears and what it may reveal
  Content.kt            extracting text/messages/pictures from notifications; app labels & icons
  Act.kt                open / dismiss / action / inline reply
  Updater.kt            opt-in self-update from GitHub Releases (the only network code)
  core/Version.kt       version comparison and checksum parsing (unit-tested)
  ui/NotifList.kt       renders sections with keyed view reuse (shared by panel and lock view)
  ui/CardView.kt        notification card and collapsed stack
  ui/PanelView.kt       the panel's content and gestures
  ui/Panel.kt           opens the panel as an overlay window, or falls back to PanelActivity
  ui/PanelActivity.kt   the panel as an activity (fallback)
  ui/LockActivity.kt    the lock-screen view
  ui/SetupActivity.kt   one-time setup & settings
```

## Contributing

Issues and pull requests are welcome. See [CONTRIBUTING.md](../CONTRIBUTING.md) for the ground
rules (local-first, lightweight) and [SECURITY.md](../SECURITY.md) to report
vulnerabilities privately.

## License

Chronotif is free software, licensed under the **GNU General Public License v3.0 or later**
(GPL-3.0-or-later). See [LICENSE](../LICENSE). You may use, study, modify and share it. Modified
versions you distribute must stay open source under the same license.
