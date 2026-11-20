# A snoozed notification comes back as New

A Reddit user asked to snooze notifications. Android lets a notification listener snooze one: the system hides it, from its own shade too, and posts it again when the time is up. It posts it unchanged, with the post time it had before. Chronotif decides New by when a notification arrived, so a snoozed notification that was already checked would come back into Earlier, below anything newer, and often under the "N earlier" pill on the lock screen. That defeats the point of snoozing. So Chronotif remembers which notifications it snoozed, and when Android posts one again it arrives at that moment and is New. A conversation shows at least its latest message in New, as for any New conversation.

## Considered Options

- **Keep its old time**: true to what Android reports, but the notification returns where nobody looks.
- **Snooze inside Chronotif only**: hide the card and show it again later, leaving the system shade alone. Needs a timer of our own, which the listener deliberately doesn't run, and the notification would still sit in the shade.

## Consequences

- The snoozed keys are kept in preferences, so a restart during a snooze still treats the return as New. One whose notification the app removed in the meantime is forgotten a day after it was due.
- A returned conversation's message cards keep their own send times, so its New card can show an older time than the moment it came back, and sorts by that time within New.
- Snoozing works on whole notifications, so snoozing one message snoozes its conversation.
