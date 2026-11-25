# Contributing to Chronotif

Thanks for helping! Chronotif is deliberately small, so the bar for new features is "does it
serve the NEW / EARLIER attention model, the lock screen, or interacting with real
notifications?". Bug fixes, device compatibility (especially Xiaomi/HyperOS, Samsung, Pixel),
translations and accessibility improvements are always welcome.

## Principles (please keep these true)

- **Build the known best way first.** If there's an established right way to do something on
  Android, the first version uses it. Don't leave it for a later fix. For example: no
  PackageManager, bitmap or disk work on the main thread (show a placeholder and fill it in);
  reuse views instead of rebuilding them; check speed on a slow phone with a long notification
  list. Passing tests isn't enough if the code falls short of that. If the best way has a real
  cost, say so in the PR instead of quietly shipping the simpler version. Going past the known
  best later is welcome. If you touch older code that falls short, fix it or point it out;
  don't copy it.
- **Local-first and private.** No accounts, and nothing from the user's notifications,
  contacts, calendar or Search ever leaves the device. There are four pieces of network code.
  Two are download-only: the opt-in updater (`Updater.kt`), which reads public GitHub Releases, and
  Search's currency rates (`ExchangeRates.kt`), on by default for new installs, which download the
  European Central Bank's public daily rates when a currency is typed (ADR 0008, ADR 0015).
  The third is Usage data (`UsageData.kt`, ADR 0016): anonymous counts, Panel speeds, settings and
  crash reports sent to PostHog, on unless turned off. A new Usage data field is added to
  `docs/usage-data.md` in the same commit, and must not reveal a notification's content or which
  app sent it. The fourth is Feedback (`Feedback.kt`): a message the user writes under
  *Settings → Send feedback*, posted to chronotif.com only when they tap Send, and emailed privately
  to the maintainer. A PR that adds other network use won't be merged.
- **Lightweight.** No polling, wakelocks, alarms or foreground services. Work happens in
  response to system events. The one exception is the opt-in daily update job. Avoid
  dependencies; the app currently has none.
- **Keep the accessibility service minimal.** `StatusBarSwipe` exists only to place a touch
  area over the status bar and to close the system shade when the Quick Settings tile opens the
  panel. Don't subscribe it to events, retrieve window content, inject
  gestures or log anything about other apps. It must stay opt-in, respect *Chronotif is on*,
  and be tested with TalkBack, the lock screen and fullscreen apps when changed.
- **Respect lock-screen privacy.** Anything shown on the lock screen must follow the
  system and per-app visibility rules (`LockPolicy`). The lock-screen view does what the Panel
  does (ADR 0013): replies and buttons that work in the background run while locked, unless the
  app asks for an unlock, and anything that opens an app asks to unlock first. When the lock
  screen hides sensitive content, redacted cards offer no reply or buttons and Search holds
  contacts and events back.

## Getting started

Requirements: JDK 17+ and the Android SDK (platform 36). See the README for build commands.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Please make sure tests and lint pass before opening a PR (CI runs the same checks).

## Making a change

1. Open an issue first for anything bigger than a small fix, so we can agree on the approach.
2. Fork, then create a branch from `main`.
3. Keep PRs focused: one change per PR, matching the surrounding code style.
4. Add or update unit tests when you change the attention model (`core/Attention.kt`).
5. If the change affects behaviour you can see, say which device/Android version you tested on
   and include a screenshot or short recording.
6. If people using the app will notice the change, add a line for it to the `next` entry in
   `website/src/releases.json`, in plain words.
7. Open a pull request against `main` and fill in the template.

## Reporting bugs

Use the bug report template. People without a GitHub account can use *Settings → Send feedback* in
the app or [chronotif.com/feedback](https://chronotif.com/feedback) instead. That Feedback is emailed
privately to the maintainer, who files an issue from it when something needs work. It isn't turned
into issues automatically, because people write private things into it; anything copied into an
issue leaves out what the sender would want private. For notification problems, the device model, OS version
(e.g. HyperOS 3 / Android 16) and the app that posted the notification matter most.
Please don't paste notification contents; they're private.

## License

By contributing you agree that your contribution is licensed under the project's license,
**GPL-3.0-or-later** (see `LICENSE`). New Kotlin files should start with:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
```
