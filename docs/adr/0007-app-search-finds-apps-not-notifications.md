# App search finds apps, not notifications, and shows results while locked

The user asked for a pull-down search like the iPhone's lock-screen Spotlight. We built App search: pulled down from the top of the Panel or the lock-screen view, it finds apps by name and suggests the four apps that notified most recently. It never searches notifications. Chronotif's job is to show notifications in time order, and those are already all on screen; what a pull-down saves is the unlock-then-find-the-app step. The suggestions come from Chronotif's own record of which apps notified, so no usage-access permission is needed, and listing apps uses the package visibility Chronotif already has. Results show while locked, and tapping one unlocks and then launches it.

## Considered Options

- **Spotlight-style, everything (apps, contacts, Settings, the web)**: repeats the launcher's search, needs contacts and usage-access permissions, can't search Settings because Android has no public way to, and web search would only hand off to the browser after unlocking.
- **Search notifications**: a match would be found among cards the user can already see, and searching dismissed ones would mean archiving message text on disk, a separate privacy decision.
- **Results only after unlock, as on iOS**: hides most of what the gesture is for. The suggestion row only shows apps whose notifications the lock-screen view already shows.

## Consequences

- A request to search notifications, contacts or the web is declined by this rule.
- Anyone holding the locked phone can see which apps are installed by typing. The App search switch in settings turns it off.
- Launching from App search on the lock screen unlocks, which counts as a check like any unlock.
