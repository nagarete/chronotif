# Priority apps sit in their own section above New

Users asked for some apps' notifications to "stay on top". Their important cards (a bank's code, a work chat) were pushed down by noisier apps while still New, and a glance at the Panel counted as a check and moved them to Earlier before anything was done about them. The user's own wish was the reverse, to quiet some apps. Time order is the product, so we didn't want priority to reorder a section. We chose a separate section, Priority, between Media players and New, in the same way calls and Media players already sit above New. Every section still runs newest first. Users opt in per app: an app is a Priority app, a Normal one (the default for every app) or a Quiet app.

## The decision

- **Priority** holds Priority apps' cards newest first, with App stacks under the same rule as Earlier (ADR 0004). A card stays there until it is opened, cleared or snoozed. A check doesn't move it to Earlier, and a snoozed card comes back to Priority. Priority has no Clear for the whole section.
- For a Priority app's conversation, the split falls at the last time the chat was opened, not at the last check.
- On the lock screen the Pile stays a single Pile, with Priority first, so its top card is a Priority card whenever there is one. A Priority card brings up the lock-screen view when it arrives, as a New card does, and never again after that.
- A **Quiet app**'s cards never count as New: they go to Earlier at their own time and never bring up the lock-screen view. Chronotif can't silence another app, so its sound and vibration stay Android's.
- Neither setting moves calls, Media players or Running.
- When an app's setting changes, nothing that was already checked comes back up. Cards leaving Priority, or belonging to an app made Quiet, go to Earlier.
- The setting is under *Settings → Panel → App priority*, and in each card's Preview, which asks to unlock first on the lock screen.

## Considered Options

- **Priority cards first inside New**: no new section, but New would stop being newest first, the one rule every section keeps.
- **Only mark Priority cards with an accent**: keeps strict order, but the card still gets buried, which is what users complained about.
- **Priority cards whole above the Pile, like a Media player**: more visible on the lock screen, but a few of them would push the Pile off the screen.
- **Mute only (Quiet apps without Priority apps)**: what the user first wanted, but it doesn't keep an important card from receding after a glance.

## Consequences

- Priority can grow if its cards are never opened or cleared. If that turns out to be a problem, a time limit can be added later.
- Priority conversations (one person in an app, as with iOS's Focus and priority people) are left for a later change. The terms are chosen so they fit.
- Usage data gains counts of Priority and Quiet apps, never which apps they are (ADR 0016).
- The Tutorial's Notifications part gains two steps, one making a sample app a Priority app and one making another a Quiet app, which change nothing outside the tutorial. The website demo mirrors the Priority section.
