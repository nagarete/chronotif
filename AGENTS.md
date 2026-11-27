# Chronotif

Android notification panel and lock-screen view built around one rule: newest first, split into New (since the last check) and Earlier (already checked). Chronotif started from how iOS handles notifications, but it isn't an iOS copy. Behaviour is decided here, recorded in `docs/notification-behavior.md` and the ADRs, and argued on its own merit.

## Sources of truth

Each of these is changed together with the code it describes, in the same commit:

- **Behaviour** (ordering, stacks, the lock screen, what counts as New): `docs/notification-behavior.md`.
- **Terms**: `GLOSSARY.md`. Use its words in code, docs and commit messages.
- **Decisions**: `docs/adr/`. When a change reverses an ADR, add a new ADR. In the old ADR, leave the text as it is and add a "Superseded by" note.
- **Principles and PR checklist**: `CONTRIBUTING.md` and `.github/pull_request_template.md`.
- **Release notes**: `website/src/releases.json`, rendered at chronotif.com/releases. A user-visible change adds a line to its `next` entry. The release steps are in `docs/guide.md`.
- **Website demo**: `website/src/demo.js` mirrors `core/Attention.kt`, and `website/index.html` describes the same model. Update both when visible behaviour changes.

## Grill the design first

A change to behaviour, a new setting, or reversing an ADR starts with the `grill-with-docs` skill from [mattpocock/skills](https://github.com/mattpocock/skills), before any code. It settles the decisions with the person who asked and writes the `GLOSSARY.md` terms and ADRs as they're settled. The PR lists the decisions it settled. Typo and small bug fixes skip it.

## Build the known best way first

Every change starts from the established best practice for that problem on Android, without being asked. Threading, memory, battery, start-up and frame time, and slow phones with long lists are requirements, even when the task and tests don't mention them. Never ship a simple version and wait for complaints. Example: icons and labels loaded on the main thread made the panel take seconds to open until #62 moved them off it, which is decade-old Android advice. When the best way costs something real (memory, complexity), say so and decide it with the user. When code you touch falls short, raise it instead of copying the pattern. See `CONTRIBUTING.md`.

## Ordering

Time order is the product. Every section sorts newest first by the time its card shows. Only two groupings exist: a Conversation stack, and in Earlier an App stack of one app's neighbouring cards that arrived within 30 minutes of its newest card (ADR 0004). A stack never gathers an app's cards from across the list. Check any new grouping, sorting or collapsing against this before building it.

## Comments

A comment says why: the platform quirk, the OEM workaround, the ADR it follows. It sits directly above the line it explains. Describe the code as it is now. History belongs in git and the ADRs, and a pending fix belongs in an issue.

A comment is a claim the code has to keep true. When you change behaviour, search for comments that describe it, especially absolutes (*only*, *never*, *nothing*, *always*), and fix them in the same commit.

## Verifying

- `./gradlew testDebugUnitTest lintDebug assembleDebug` must pass. Changes to `core/Attention.kt` update `AttentionTest`. Visible list changes get a Robolectric case in `NotificationBehaviorTest`.
- Lint is most of that check's time, and it reruns on any source edit. While working, run `assembleDebug` and the tests you touch (`testDebugUnitTest --tests '*NotificationBehaviorTest*'`); run the full check before committing.
- There's no emulator setup, so visible changes are confirmed on a real phone. The debug build (`app.chronotif.debug`) installs next to the stable app. After `adb install`, reconnect its listener with `cmd notification disallow_listener` then `allow_listener` for `app.chronotif.debug/app.chronotif.ListenerService`. MIUI/HyperOS needs this.
