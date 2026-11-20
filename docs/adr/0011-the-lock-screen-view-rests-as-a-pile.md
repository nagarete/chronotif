# The lock-screen view rests as a Pile

Superseded in part by [ADR 0012](0012-a-media-players-artwork-rests-large-on-the-lock-screen.md): a Media player with artwork rests with its artwork large above it.

A Reddit user wakes the phone to see the time, and the lock-screen view covered the screen with notifications. So the view now wakes with the clock first and New resting at the bottom as a Pile: the newest card whole, up to two slivers behind it and a count for the rest. The Pile is the whole list scrolled to its top, with everything after the first card folded under it: scrolling up unfolds it under the finger, New then Earlier, and the list pushes the clock away only when it reaches it. Nothing is rebuilt mid-gesture, which is what makes it smooth. A tap on the Pile unfolds it too. The Pile collapses New, which the ordering rule normally forbids, so it is kept to a resting state: it holds all of New in time order, never groups by app, and opens into the same cards in the same order. Only the top card takes swipes and press-and-hold while it rests. Unlocking with the Pile in sight checks all of New. Each wake starts at the Pile.

## Considered Options

- **A Full/Compact setting**: keeps today's view for anyone who wants it, but the open Pile already is today's view, and the setting would sit beside "Show on lock screen", which decides when the view appears, not how it looks.
- **A smaller window that leaves the stock lock screen showing**: the stock lock screen would show its own notifications underneath, which is what Chronotif replaces.

## Consequences

- Pull-down still opens Search in both states. Back folds the list back into the Pile, then shows the stock lock screen. Swiping up anywhere but *Tap to unlock* scrolls rather than unlocks.
- Media players and ongoing calls sit whole under the clock, above the Pile.
- With nothing New but something in Earlier, the view rests as the clock and an "N earlier" line.
