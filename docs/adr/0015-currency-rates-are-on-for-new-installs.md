# Currency rates are on for new installs

Supersedes in part ADR 0008, which made the currency download opt-in.

The user wants people to meet the whole of Chronotif first and switch off what they don't like, the same reason the lock-screen view now shows whenever there are notifications on a new install. A currency question that stops to ask for a switch is the opposite of that, and the download sends nothing typed. So *Settings → Search → Currency rates* is on for new installs. Installs from before 0.2.7 keep it off unless turned on, because they were told it was opt-in. On its first start after the update, an older install writes the old default for each setting it never changed. An install that has never saved a setting and was never updated counts as new. The updater stays opt-in.

## Considered Options

- **Keep it opt-in**: no download without a yes, but the first currency question answers with a switch instead of an amount.
- **On for everyone, existing installs included**: one rule for all, but it breaks the opt-in promise made to people who already chose not to turn it on.
- **Rates bundled in the APK**: no download at all, but the rates would be as old as the build, wrong for anyone converting money today.

## Consequences

- A new install contacts the European Central Bank the first time someone types a currency, without asking. The bank sees the phone's IP address and that rates were requested, nothing more.
- The network promise in `CONTRIBUTING.md`, the README, About and the website now says the updater is opt-in and currency rates are on by default. Both stay download-only.
- A default that changes from now on can be pinned for older installs the same way, in `Prefs.keepOldDefaults`.
