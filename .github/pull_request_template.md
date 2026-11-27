## What and why

<!-- What does this change, and why? Link the issue if there is one. For a behaviour change, list the decisions grill-with-docs settled. -->

## How I tested it

<!-- Device(s) and Android/OS version(s). Screenshots or a short recording for UI changes. -->

## Checklist

- [ ] Designed with `grill-with-docs` (see CONTRIBUTING.md), with its glossary and ADR changes in this PR, or not needed: a typo or small bug fix
- [ ] `./gradlew testDebugUnitTest lintDebug assembleDebug` passes
- [ ] Uses the known best way on Android from the start (nothing slow on the main thread; checked with a long list on a slow phone if the panel or lock screen is touched)
- [ ] No new network access (beyond the opt-in updater, Search's currency rates, Usage data and Feedback), background services, wakelocks or new dependencies added; any new Usage data field is listed in `docs/usage-data.md`
- [ ] Lock-screen behaviour still respects privacy rules (if touched)
- [ ] User-visible changes are described in the `next` entry of `website/src/releases.json`
- [ ] I'm happy for this to be released under GPL-3.0-or-later
