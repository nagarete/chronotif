# Contributing to Chronotif

Thanks for helping! Chronotif is deliberately small, so the bar for new features is "does it
serve the NEW / EARLIER attention model, the lock screen, or interacting with real
notifications?". Bug fixes, device compatibility (especially Xiaomi/HyperOS, Samsung, Pixel),
translations and accessibility improvements are always welcome.

## Principles (please keep these true)

- **Local-first and private.** No accounts, analytics or crash reporters, and nothing about
  the user or their notifications ever leaves the device. The only network code is the
  opt-in updater (`Updater.kt`), which only downloads public GitHub Releases. A PR that adds
  other network use won't be merged.
- **Lightweight.** No polling, wakelocks, alarms or foreground services. Work happens in
  response to system events. The one exception is the opt-in daily update job. Avoid
  dependencies; the app currently has none.
- **Keep the accessibility service minimal.** `StatusBarSwipe` exists only to place a touch
  area over the status bar and to close the system shade when the Quick Settings tile opens the
  panel. Don't subscribe it to events, retrieve window content, inject
  gestures or log anything about other apps. It must stay opt-in, respect *Chronotif is on*,
  and be tested with TalkBack, the lock screen and fullscreen apps when changed.
- **Respect lock-screen privacy.** Anything shown on the lock screen must follow the
  system and per-app visibility rules (`LockPolicy`), and interactions must require unlock.

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
6. Open a pull request against `main` and fill in the template.

## Reporting bugs

Use the bug report template. For notification problems, the device model, OS version
(e.g. HyperOS 3 / Android 16) and the app that posted the notification matter most.
Please don't paste notification contents; they're private.

## License

By contributing you agree that your contribution is licensed under the project's license,
**GPL-3.0-or-later** (see `LICENSE`). New Kotlin files should start with:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
```
